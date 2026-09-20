package com.soulvoyage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.agent.companion.CompanionService;
import com.soulvoyage.agent.simulate.SceneCatalog;
import com.soulvoyage.agent.simulate.SimulationService;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.diary.DiaryEntity;
import com.soulvoyage.domain.diary.DiaryRepository;
import com.soulvoyage.domain.notify.NotificationEntity;
import com.soulvoyage.domain.notify.NotificationRepository;
import com.soulvoyage.domain.plan.PlanItemRepository;
import com.soulvoyage.domain.plan.PlanService;
import com.soulvoyage.domain.report.ReportEntity;
import com.soulvoyage.domain.report.ReportRepository;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import com.soulvoyage.orchestrator.OrchestratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M10 上线门禁·越权扫描矩阵（手册 §7.2 资源属主断言）。
 *
 * 两条判据缺一不可：
 * ① 属主侧必须拿到业务成功响应（正控制）——证明路由真实存在、id 真实可访问；
 *    否则「越权被拒」可能只是 URL 写错造成的假阳性；
 * ② 越权侧只能拿到 403/404，响应体不得出现属主明文，且扫描结束后属主数据未被改动。
 *
 * 走 RANDOM_PORT + TestRestTemplate 打真实 HTTP 栈（Security 过滤器链 + 内容协商），
 * 不用 MVC 切片：门禁要验收的是线上那套栈。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class M10IdorMatrixTest {

    /** 明文探针一律 ASCII：不让 HTTP 解码链路参与判据，避免编码问题掩盖真实结论 */
    private static final String DIARY_CANARY = "idor-canary-diary-original";
    private static final String DIARY_EDITED = "idor-canary-diary-edited";
    private static final String REPORT_CANARY = "idor-canary-report-content";
    private static final String CHAT_CANARY = "idor-canary-companion-text";
    private static final String SIM_CANARY = "idor-canary-simulate-text";
    private static final String NOTE_CANARY = "idor-canary-notification-body";
    private static final List<String> CANARIES =
            List.of(DIARY_CANARY, DIARY_EDITED, REPORT_CANARY, CHAT_CANARY, SIM_CANARY, NOTE_CANARY);

    private static final String PWD = "Passw0rd!2026";
    private static final String INTRUDER_TEXT = "hello from another account";

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepo;
    @Autowired DiaryRepository diaryRepo;
    @Autowired ReportRepository reportRepo;
    @Autowired NotificationRepository notifyRepo;
    @Autowired PlanItemRepository planItemRepo;
    @Autowired CompanionService companion;
    @Autowired SimulationService simulation;
    @Autowired SceneCatalog scenes;
    @Autowired PlanService plans;
    @Autowired CryptoService crypto;
    @Autowired OrchestratorService orchestrator;

    private final List<String> matrix = new ArrayList<>();
    private String ownerJwt;
    private String intruderJwt;

    record Actor(String jwt, long uid) {}

    @BeforeEach
    void tuneTimeouts() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(10_000);
        f.setReadTimeout(30_000);   // SSE 由服务端 complete 收尾；万一挂住应失败而不是拖死回归
        rest.getRestTemplate().setRequestFactory(f);
    }

    @Test
    void everyOwnedResourceRefusesForeignPrincipal() throws Exception {
        Actor a = register("idra");
        Actor b = register("idrb");
        ownerJwt = a.jwt();
        intruderJwt = b.jwt();
        assertNotEquals(a.uid(), b.uid(), "两个探针账号必须是不同主体");

        // ---- A 的资源面：文本域走仓储直种，会话/训练走真实服务（含 mock LLM 回合）----
        long diaryId = seedDiary(a, DIARY_CANARY);
        long reportId = seedReport(a, REPORT_CANARY);
        long sessionId = companion.openOrReuse(a.uid()).sessionId();
        long turnId = companion.turn(a.uid(), sessionId, "今天有点闷 " + CHAT_CANARY).turnId();
        long simId = simulation.open(a.uid(), firstNormalScene(), null).simulateId();
        long noteId = seedNotification(a, NOTE_CANARY);

        // ---- 下篇·C1/C2 文本域 ----
        scan("日记详情", HttpMethod.GET, "/api/v1/diaries/" + diaryId, null, 200, DIARY_CANARY, 404);
        scan("日记编辑", HttpMethod.PUT, "/api/v1/diaries/" + diaryId,
                Map.of("content", DIARY_EDITED, "moodSelfRating", 4), 200, null, 404);
        scan("报告详情", HttpMethod.GET, "/api/v1/reports/" + reportId, null, 200, REPORT_CANARY, 404);
        scan("报告收藏", HttpMethod.POST, "/api/v1/reports/" + reportId + "/star",
                Map.of("starred", true), 200, null, 404);
        scan("报告评价", HttpMethod.POST, "/api/v1/reports/" + reportId + "/feedback",
                Map.of("rating", "USEFUL", "note", "idor feedback"), 200, null, 404);
        var reanalyze = scan("日记重分析", HttpMethod.POST, "/api/v1/diaries/" + diaryId + "/reanalyze",
                null, 200, null, 404);
        String diaryTask = data(reanalyze).path("taskNo").asText();
        awaitTerminal(diaryTask);

        // 越权删除必须无副作用：B 删不动（404），A 随后仍读得到自己的编辑结果
        var foreignDelete = exchange(intruderJwt, HttpMethod.DELETE, "/api/v1/diaries/" + diaryId, null);
        assertEquals(404, foreignDelete.getStatusCode().value(), "他人日记删不掉");
        noLeak("日记删除", foreignDelete);
        assertTrue(get(a.jwt(), "/api/v1/diaries/" + diaryId).getBody().contains(DIARY_EDITED),
                "越权删除探针后属主日记应完好");
        record("日记删除", 200, 404, true);

        // ---- 下篇·C5 任务（字符串单号 + SSE 事件流）----
        scan("任务查询", HttpMethod.GET, "/api/v1/tasks/" + diaryTask, null, 200, "SUCCESS", 403);
        // 属主拿到 400（业务层「任务已结束」）即证明属主断言已放行；越权方只可能拿到 403
        scan("任务取消", HttpMethod.POST, "/api/v1/tasks/" + diaryTask + "/cancel", null, 400, null, 403);
        var streamOwner = exchange(intruderJwt, HttpMethod.GET, "/api/v1/tasks/" + diaryTask + "/stream",
                null, MediaType.TEXT_EVENT_STREAM);
        assertEquals(403, streamOwner.getStatusCode().value(),
                "越权订阅必须被拒，且 SSE 的 Accept 不能把错误响应打回 500/406");
        noLeak("任务事件流", streamOwner);
        var ownerStream = exchange(a.jwt(), HttpMethod.GET, "/api/v1/tasks/" + diaryTask + "/stream",
                null, MediaType.TEXT_EVENT_STREAM);
        assertEquals(200, ownerStream.getStatusCode().value(), "属主应能订阅自己的任务流");
        assertTrue(ownerStream.getBody().contains("event:done"), "终态任务流应补发 done 事件");
        record("任务事件流", 200, streamOwner.getStatusCode().value(), true);

        // ---- 下篇·C3 模拟训练 ----
        sseTurn("训练逐轮", "/api/v1/simulations/" + simId + "/turns", SIM_CANARY);
        scan("训练回放", HttpMethod.GET, "/api/v1/simulations/" + simId, null, 200, SIM_CANARY, 404);
        scan("训练中断", HttpMethod.POST, "/api/v1/simulations/" + simId + "/interrupt", null,
                200, null, 404);
        var finish = scan("训练收束复盘", HttpMethod.POST, "/api/v1/simulations/" + simId + "/finish",
                null, 202, null, 404);
        awaitTerminal(data(finish).path("taskNo").asText());

        // ---- 下篇·C0 树洞漫聊 ----
        scan("漫聊回放", HttpMethod.GET, "/api/v1/companion/sessions/" + sessionId, null,
                200, CHAT_CANARY, 404);
        scan("这句别分析", HttpMethod.POST, "/api/v1/companion/turns/" + turnId + "/no-analyze",
                null, 200, null, 404);
        sseTurn("漫聊逐轮", "/api/v1/companion/sessions/" + sessionId + "/turns", CHAT_CANARY);
        var end = scan("漫聊收段", HttpMethod.POST, "/api/v1/companion/sessions/" + sessionId + "/end",
                null, 202, null, 404);
        String digestTask = data(end).path("taskNo").asText();
        if (!digestTask.isBlank()) awaitTerminal(digestTask);

        // ---- 下篇·G4/G5：计划必须在所有流水线跑完后现种，避免被 ACTION 计划收口改掉 ----
        long planId = Long.parseLong(
                plans.materialize(a.uid(), null, mapper.createObjectNode()).replace("pl_", ""));
        scan("计划详情", HttpMethod.GET, "/api/v1/plans/" + planId, null, 200, null, 404);
        // 请求体夹带他人 planId：B 的打卡正常落库，但绝不能给 A 的 plan_item 盖章
        var foreignCheckIn = post(b.jwt(), "/api/v1/exercise-records", Map.of(
                "exerciseId", "ex_54321", "completed", true,
                "planId", "pl_" + planId, "planItemSeq", 1));
        assertEquals(200, foreignCheckIn.getStatusCode().value(), "B 自己的跟练打卡应正常落库");
        assertNull(planItemRepo.findByPlanIdAndSeq(planId, 1).orElseThrow().getDoneAt(),
                "请求体夹带他人 planId 不得给属主盖章完成（G4 回写旁路）");
        record("跟练回写(planId 夹带)", 200, 404, true);
        scan("计划放弃", HttpMethod.POST, "/api/v1/plans/" + planId + "/drop", null, 200, null, 404);
        assertEquals("DROPPED", json(get(a.jwt(), "/api/v1/plans/" + planId)).path("status").asText(),
                "属主放弃应真实生效");
        scan("通知已读", HttpMethod.POST, "/api/v1/notifications/" + noteId + "/read", null,
                200, null, 404);
        assertNotNull(notifyRepo.findById(noteId).orElseThrow().getReadAt(), "属主已读真实落库");

        // ---- 下篇·S2 导出：一次性限时链接不可跨账号领取 ----
        String archiveFile = data(post(a.jwt(), "/api/v1/archive/export", null)).path("fileId").asText();
        exportPair("档案导出领取", HttpMethod.GET, "/api/v1/archive/export/" + archiveFile, a, b);
        String dataFile = data(get(a.jwt(), "/api/v1/users/me/data-export")).path("fileId").asText();
        exportPair("个人数据导出领取", HttpMethod.POST, "/api/v1/users/me/data-export/" + dataFile, a, b);

        // ---- 垂直越权：管理端角色门（ADMIN 200 作正控制，防 URL 写错的假阳性）----
        String adminPath = "/api/v1/admin/metrics/overview";
        var adminOk = exchange(promoteAndLogin("idoradmin"), HttpMethod.GET, adminPath, null);
        assertEquals(200, adminOk.getStatusCode().value(), "ADMIN 正控制：管理端路由真实存在");
        assertTrue(adminOk.getBody().contains("llmCalls"), "管理端应回指标数据: " + brief(adminOk.getBody()));
        var anon = rest.exchange(adminPath, HttpMethod.GET, new HttpEntity<>(headers(null)), String.class);
        assertEquals(401, anon.getStatusCode().value(), "匿名不得进管理端");
        var userToken = exchange(ownerJwt, HttpMethod.GET, adminPath, null);
        assertEquals(403, userToken.getStatusCode().value(), "普通令牌不得进管理端");
        noLeak("运营看板", userToken);
        record("运营看板 角色门", 200, "用户 403 / 匿名 401", true);

        // ---- 属主终检：整轮扫描只动过属主自己的东西 ----
        var report = json(get(a.jwt(), "/api/v1/reports/" + reportId));
        assertTrue(report.path("starred").asBoolean(), "属主收藏状态应保留");
        assertEquals("USEFUL", report.path("feedback").asText());
        assertEquals("DIGESTED", json(get(a.jwt(), "/api/v1/companion/sessions/" + sessionId))
                .path("status").asText(), "会话状态只由属主自己的收段决定");
        assertEquals(0, reportRepo.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                b.uid(), PageRequest.of(0, 1)).getTotalElements(),
                "越权方不应被写入任何属主产物");
        assertNull(diaryRepo.findById(diaryId).orElseThrow().getDeletedAt(),
                "整轮扫描结束后属主日记仍未被删除");
        assertEquals(200, exchange(a.jwt(), HttpMethod.DELETE, "/api/v1/diaries/" + diaryId, null)
                .getStatusCode().value(), "属主删除自己日记应成功（DELETE 路由正控制）");

        printMatrix();
        assertTrue(matrix.size() >= 24, "矩阵行数异常，疑似探针被跳过: " + matrix.size());
    }

    // ---------------- 探针原语 ----------------

    /** 一行矩阵：属主拿指定业务码（正控制），越权方只允许 given 的状态集，且响应体不含属主明文 */
    private ResponseEntity<String> scan(String label, HttpMethod m, String path, Object body,
                                        int ownerStatus, String ownerMustContain,
                                        int... intruderStatuses) {
        ResponseEntity<String> owner = exchange(ownerJwt, m, path, body);
        assertEquals(ownerStatus, owner.getStatusCode().value(), label + " 属主侧");
        if (ownerMustContain != null) {
            assertTrue(owner.getBody().contains(ownerMustContain),
                    label + " 属主侧应读回真实内容（正控制，防空 200 假阳性）");
        }
        ResponseEntity<String> intruder = exchange(intruderJwt, m, path, body);
        assertTrue(Arrays.stream(intruderStatuses).anyMatch(s -> s == intruder.getStatusCode().value()),
                label + " 越权侧应 " + Arrays.toString(intruderStatuses) + " 实得 "
                        + intruder.getStatusCode().value() + " body=" + brief(intruder.getBody()));
        noLeak(label, intruder);
        record(label, ownerStatus, intruderStatuses[0], true);
        return owner;
    }

    /**
     * 逐轮端点是 SSE：协议约定业务失败也在流内下发 error 事件（前端统一在流内处理），
     * 所以越权判据换成「流内只有 error、没有增量与收尾事件、不含属主明文」。
     */
    private void sseTurn(String label, String path, String canary) {
        ResponseEntity<String> owner = exchange(ownerJwt, HttpMethod.POST, path,
                Map.of("userText", "想把事情说清楚 " + canary), MediaType.TEXT_EVENT_STREAM);
        assertEquals(200, owner.getStatusCode().value(), label + " 属主侧");
        assertTrue(owner.getBody().contains("event:turn_done"),
                label + " 属主侧应拿到收尾事件，实得 body=" + brief(owner.getBody()));
        ResponseEntity<String> intruder = exchange(intruderJwt, HttpMethod.POST, path,
                Map.of("userText", INTRUDER_TEXT), MediaType.TEXT_EVENT_STREAM);
        String ib = intruder.getBody();
        assertTrue(intruder.getStatusCode().is2xxSuccessful()
                        && ib.contains("event:error")
                        && !ib.contains("turn_done") && !ib.contains("ai_delta") && !ib.contains("npc_delta"),
                label + " 越权侧应只在流内回 error（HTTP " + intruder.getStatusCode().value()
                        + " body=" + brief(ib) + "）");
        noLeak(label, intruder);
        record(label + "(SSE)", 200, "404 流内 error", true);
    }

    /** 导出领取：越权方 403 且链接仍在，属主随后领取成功；领取即焚后二次领取拿不到明文 */
    private void exportPair(String label, HttpMethod m, String path, Actor owner, Actor intruder) {
        var foreign = exchange(intruder.jwt(), m, path, null);
        assertEquals(403, foreign.getStatusCode().value(), label + " 越权侧");
        noLeak(label, foreign);
        var mine = exchange(owner.jwt(), m, path, null);
        assertEquals(200, mine.getStatusCode().value(), label + " 属主侧");
        record(label, 200, 403, true);
        var replay = exchange(owner.jwt(), m, path, null);
        assertEquals(404, replay.getStatusCode().value(), label + " 一次性链接二次领取应失效");
        assertTrue(noCanary(replay.getBody()), label + " 二次领取不得回放明文");
    }

    // ---------------- HTTP ----------------

    private ResponseEntity<String> get(String jwt, String path) {
        return exchange(jwt, HttpMethod.GET, path, null);
    }

    private ResponseEntity<String> post(String jwt, String path, Object body) {
        return exchange(jwt, HttpMethod.POST, path, body);
    }

    private ResponseEntity<String> exchange(String jwt, HttpMethod m, String path, Object body) {
        return exchange(jwt, m, path, body, MediaType.APPLICATION_JSON);
    }

    private ResponseEntity<String> exchange(String jwt, HttpMethod m, String path, Object body,
                                            MediaType accept) {
        HttpHeaders h = headers(jwt);
        h.setAccept(List.of(accept));
        return rest.exchange(path, m, new HttpEntity<>(body, h), String.class);
    }

    private HttpHeaders headers(String jwt) {
        HttpHeaders h = new HttpHeaders();
        if (jwt != null) h.setBearerAuth(jwt);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private JsonNode data(ResponseEntity<String> r) throws Exception {
        assertTrue(r.getStatusCode().is2xxSuccessful(), "响应应为 2xx: " + brief(r.getBody()));
        return mapper.readTree(r.getBody()).path("data");
    }

    private JsonNode json(ResponseEntity<String> r) throws Exception {
        return mapper.readTree(r.getBody()).path("data");
    }

    private static String brief(String body) {
        if (body == null) return "<null>";
        return body.length() > 240 ? body.substring(0, 240) + "…" : body;
    }

    private void noLeak(String label, ResponseEntity<String> r) {
        assertTrue(noCanary(r.getBody()), label + " 越权响应不得出现属主明文");
    }

    private boolean noCanary(String body) {
        return body == null || CANARIES.stream().noneMatch(body::contains);
    }

    private void record(String label, int ownerStatus, Object intruderStatus, boolean leakChecked) {
        matrix.add(String.format("%-24s 属主 %-3s | 越权 %-14s | 明文不泄露 %s",
                label, ownerStatus, intruderStatus, leakChecked ? "√" : "n/a"));
    }

    private void printMatrix() {
        System.out.println("\n===== M10 越权扫描矩阵（真实 HTTP 栈）=====");
        matrix.forEach(System.out::println);
        System.out.println("共 " + matrix.size() + " 行，全部通过\n");
    }

    // ---------------- 造数据 ----------------

    private Actor register(String prefix) throws Exception {
        ResponseEntity<String> r = rest.exchange("/api/v1/auth/register", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", prefix + System.nanoTime(),
                        "password", PWD, "nickname", "t"), headers(null)), String.class);
        assertEquals(200, r.getStatusCode().value(), "注册探针账号失败: " + brief(r.getBody()));
        JsonNode d = mapper.readTree(r.getBody()).path("data");
        return new Actor(d.path("accessToken").asText(), d.path("userId").asLong());
    }

    /** 管理端正控制：注册后改角色再登录（角色写在令牌里，必须重签） */
    private String promoteAndLogin(String prefix) throws Exception {
        Actor admin = register(prefix);
        UserEntity u = userRepo.findById(admin.uid()).orElseThrow();
        u.setRole("ADMIN");
        userRepo.save(u);
        ResponseEntity<String> r = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", u.getUsername(), "password", PWD),
                        headers(null)), String.class);
        assertEquals(200, r.getStatusCode().value(), "管理员登录失败: " + brief(r.getBody()));
        return mapper.readTree(r.getBody()).path("data").path("accessToken").asText();
    }

    private long seedDiary(Actor a, String text) {
        DiaryEntity d = new DiaryEntity();
        d.setUserId(a.uid());
        d.setContentEnc(crypto.encryptUserField(a.uid(), text));
        d.setRecordDate(LocalDate.now());
        d.setMoodSelfRating((short) 3);
        return diaryRepo.save(d).getId();
    }

    private long seedReport(Actor a, String text) {
        ReportEntity r = new ReportEntity();
        r.setUserId(a.uid());
        r.setType("TRACE");
        r.setTaskId(0L);
        r.setTitle("idor report");
        r.setContentEnc(crypto.encryptUserField(a.uid(),
                mapper.createObjectNode().put("summary", text).toString()));
        r.setRiskLevel("LOW");
        return reportRepo.save(r).getId();
    }

    private long seedNotification(Actor a, String body) {
        NotificationEntity n = new NotificationEntity();
        n.setUserId(a.uid());
        n.setKind("SYSTEM");
        n.setTitle("idor note");
        n.setBody(body);
        return notifyRepo.save(n).getId();
    }

    private String firstNormalScene() {
        return scenes.list().stream().filter(c -> c.supports("NORMAL")).map(c -> c.code())
                .findFirst().orElseThrow(() -> new AssertionError("没有支持 NORMAL 难度的场景卡"));
    }

    private void awaitTerminal(String taskNo) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            String s = orchestrator.byNo(taskNo).getStatus();
            if (s.equals("SUCCESS") || s.equals("FAILED") || s.equals("CANCELLED")
                    || s.equals("PARTIAL_SUCCESS")) return;
            Thread.sleep(100);
        }
        fail("任务未在 20s 内进入终态: " + orchestrator.byNo(taskNo).getStatus());
    }
}
