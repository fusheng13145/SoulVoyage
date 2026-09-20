package com.soulvoyage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.admin.AdminMetricsController;
import com.soulvoyage.admin.AdminOpsController;
import com.soulvoyage.admin.AdminRiskController;
import com.soulvoyage.admin.AdminSupportController;
import com.soulvoyage.admin.AdminTaskController;
import com.soulvoyage.admin.ContentAdminController;
import com.soulvoyage.audit.AuditLogRepository;
import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.auth.RolePermissionEntity;
import com.soulvoyage.auth.RolePermissionRepository;
import com.soulvoyage.common.api.PageResp;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.crisis.CrisisService;
import com.soulvoyage.domain.risk.RiskEventEntity;
import com.soulvoyage.domain.risk.RiskEventRepository;
import com.soulvoyage.domain.task.AgentMessageEntity;
import com.soulvoyage.domain.task.AgentMessageRepository;
import com.soulvoyage.domain.task.TaskInstanceEntity;
import com.soulvoyage.domain.task.TaskInstanceRepository;
import com.soulvoyage.domain.task.TaskStepLogEntity;
import com.soulvoyage.domain.task.TaskStepLogRepository;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import com.soulvoyage.common.util.Ulid;
import com.soulvoyage.orchestrator.OrchestratorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M9 管理端 + RBAC 回归（A1–A5）：权限表一票否决、任务时间线、失败重跑续接、
 * 复核队列二次授权与结案联动、冻结断会话、审计链校验、内容预览试跑。
 */
@SpringBootTest
@ActiveProfiles("test")
class M9AdminConsoleTest {

    @Autowired AdminTaskController adminTasks;
    @Autowired AdminMetricsController metrics;
    @Autowired AdminRiskController adminRisk;
    @Autowired AdminSupportController adminUsers;
    @Autowired AdminOpsController adminOps;
    @Autowired ContentAdminController adminContent;
    @Autowired OrchestratorService orchestrator;
    @Autowired TaskInstanceRepository taskRepo;
    @Autowired TaskStepLogRepository stepRepo;
    @Autowired AgentMessageRepository msgRepo;
    @Autowired RiskEventRepository riskRepo;
    @Autowired UserRepository userRepo;
    @Autowired RolePermissionRepository permRepo;
    @Autowired AuditLogRepository auditRepo;
    @Autowired CrisisService crisis;
    @Autowired CryptoService crypto;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;

    private static final String PWD = "Passw0rd!2026";

    private long newUser(String role) {
        UserEntity u = new UserEntity();
        u.setUsername("m9" + System.nanoTime());
        u.setNickname("t");
        u.setPasswordHash(encoder.encode(PWD));
        u.setRole(role);
        return userRepo.save(u).getId();
    }

    private <T> T as(AuthPrincipal p, Supplier<T> call) {
        var auth = new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role())));
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            return call.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ---------------- RBAC：表是权限真源 ----------------

    @Test
    void rolePermissionTableIsTheGate() {
        long adminUid = newUser("ADMIN");
        long userUid = newUser("USER");
        var admin = new AuthPrincipal(adminUid, "ADMIN");
        var user = new AuthPrincipal(userUid, "USER");

        // USER 角色即使拿管理员令牌也进不来
        assertThrows(AccessDeniedException.class,
                () -> as(user, () -> adminTasks.list(null, null, 0, 10)));
        assertTrue(as(admin, () -> adminTasks.list(null, null, 0, 10)).getData().total() >= 0, "ADMIN 默认放行");

        // 摘掉 admin:task 权限行：ADMIN 角色也被拒——权限真源在表不在注解里的字符串
        var row = permRepo.findAll().stream()
                .filter(r -> r.getRole().equals("ADMIN") && r.getPermissionCode().equals("admin:task"))
                .findFirst().orElseThrow();
        permRepo.delete(row);
        permRepo.flush();
        try {
            assertThrows(AccessDeniedException.class, () -> as(admin, () -> metrics.overview(7)));
        } finally {
            var restored = new RolePermissionEntity();
            restored.setRole("ADMIN");
            restored.setPermissionCode("admin:task");
            permRepo.save(restored);
        }
        assertNotNull(as(admin, () -> metrics.overview(7)).getData());
    }

    // ---------------- A1 任务监控与失败重跑 ----------------

    @Test
    void failedTaskRetriesFromScratchAndReachesTerminal() throws Exception {
        long uid = newUser("USER");
        long adminUid = newUser("ADMIN");
        var admin = new AuthPrincipal(adminUid, "ADMIN");

        // 造一条"首步即失败"的历史任务：REQUEST 消息带原始输入，步骤日志 FAILED
        TaskInstanceEntity t = new TaskInstanceEntity();
        t.setTaskNo(Ulid.next());
        t.setUserId(uid);
        t.setPipelineCode("DIARY_PIPELINE");
        t.setStatus("FAILED");
        t.setErrorMsg("模拟失败：模型超时");
        t = taskRepo.save(t);
        final TaskInstanceEntity tf = t;

        ObjectNode input = mapper.createObjectNode()
                .put("diaryText", "和室友因为熄灯时间吵了一架，觉得很委屈，越想越气。")
                .put("recordDate", "2026-09-19");
        AgentMessageEntity req = new AgentMessageEntity();
        req.setMessageNo(Ulid.next());
        req.setTaskId(tf.getId());
        req.setStepSeq(1);
        req.setFromAgent("ORCHESTRATOR");
        req.setToAgent("EMOTION");
        req.setMsgType("REQUEST");
        req.setPayloadEnc(crypto.encryptUserField(uid, input.toString()));
        msgRepo.save(req);

        TaskStepLogEntity failed = new TaskStepLogEntity();
        failed.setTaskId(tf.getId());
        failed.setStepSeq(1);
        failed.setAgentCode("EMOTION");
        failed.setStepCode("emotion");
        failed.setStatus("FAILED");
        failed.setErrorMsg("模拟失败：模型超时");
        stepRepo.save(failed);

        // 列表过滤到这条 FAILED
        PageResp<Map<String, Object>> page = as(admin,
                () -> adminTasks.list("DIARY_PIPELINE", "FAILED", 0, 10)).getData();
        assertTrue(page.items().stream().anyMatch(m -> m.get("taskNo").equals(tf.getTaskNo())));
        assertEquals(uid, ((Number) page.items().stream()
                .filter(m -> m.get("taskNo").equals(tf.getTaskNo())).findFirst().orElseThrow()
                .get("userId")).longValue());

        // 详情时间线含成本字段键
        Map<String, Object> detail = as(admin, () -> adminTasks.detail(tf.getTaskNo())).getData();
        assertEquals("FAILED", detail.get("status"));
        assertTrue(((List<?>) detail.get("steps")).get(0) instanceof Map<?, ?> s && s.containsKey("costMs"));

        // 非 FAILED 不许重跑
        TaskInstanceEntity ok = taskRepo.save(taskOf(uid, "SUCCESS"));
        assertThrows(BizException.class, () -> orchestrator.retry(ok));

        as(admin, () -> adminTasks.retry(admin, tf.getTaskNo()));
        awaitRunning(tf.getTaskNo());   // 重跑是异步提交：先等它真正进入 RUNNING，再等终态
        awaitFinish(tf.getTaskNo());
        assertEquals("SUCCESS", orchestrator.byNo(tf.getTaskNo()).getStatus());
        assertTrue(auditRepo.findForAdmin(adminUid, "ADMIN_RETRY",
                org.springframework.data.domain.PageRequest.of(0, 10)).getTotalElements() >= 1);
    }

    private TaskInstanceEntity taskOf(long uid, String status) {
        TaskInstanceEntity t = new TaskInstanceEntity();
        t.setTaskNo(Ulid.next());
        t.setUserId(uid);
        t.setPipelineCode("DIARY_PIPELINE");
        t.setStatus(status);
        return t;
    }

    // ---------------- A2 复核队列：二次授权 + 结案联动 ----------------

    @Test
    void revealRequiresPasswordAndReviewClosesCrisis() {
        long victim = newUser("USER");
        long adminUid = newUser("ADMIN");
        var admin = new AuthPrincipal(adminUid, "ADMIN");

        ObjectNode evidence = mapper.createObjectNode().put("ruleCode", "RISK_CRISIS").put("level", "HIGH");
        RiskEventEntity e = new RiskEventEntity();
        e.setUserId(victim);
        e.setLevel("HIGH");
        e.setTriggerType("KEYWORD_RULE");
        e.setRuleCode("RISK_CRISIS");
        e.setEvidenceRefEnc(crypto.encryptUserField(victim, evidence.toString()));
        e.setActionTaken("CRISIS_CARD+PROFILE_FLAG");
        e.setNeedsReview((short) 0);
        e = riskRepo.save(e);
        final RiskEventEntity ef = e;
        crisis.onHighRisk(victim, ef.getId());
        assertEquals("CRISIS", userRepo.findById(victim).orElseThrow().getCrisisState());

        var queue = as(admin, () -> adminRisk.queue((short) 0, null, 0, 50)).getData();
        assertTrue(queue.items().stream().anyMatch(m -> ((Number) m.get("id")).longValue() == ef.getId()));
        // 队列元数据视图不含证据字段
        assertFalse(queue.items().get(0).containsKey("evidence"));

        // 口令错：拒绝 + DENIED 审计，不落明文
        assertThrows(BizException.class, () -> as(admin,
                () -> adminRisk.reveal(admin, ef.getId(), new AdminRiskController.RevealBody("wrong-pass"))));
        assertTrue(auditRepo.findForAdmin(adminUid, "ADMIN_VIEW_RISK_DENIED",
                org.springframework.data.domain.PageRequest.of(0, 10)).getTotalElements() >= 1);

        // 口令对：证据解密回显 + 正向审计
        var revealed = as(admin, () -> adminRisk.reveal(admin, ef.getId(),
                new AdminRiskController.RevealBody(PWD)));
        assertEquals("RISK_CRISIS", ((com.fasterxml.jackson.databind.JsonNode)
                revealed.getData().get("evidence")).path("ruleCode").asText());
        assertTrue(auditRepo.findForAdmin(adminUid, "ADMIN_VIEW_RISK",
                org.springframework.data.domain.PageRequest.of(0, 10)).getTotalElements() >= 1);

        // 结案 + 联动解除危机（S1：CRISIS→NORMAL by ADMIN_CLOSED）
        as(admin, () -> adminRisk.review(admin, ef.getId(), new AdminRiskController.ReviewBody(true)));
        assertEquals((short) 1, riskRepo.findById(ef.getId()).orElseThrow().getReviewed());
        assertEquals("NORMAL", userRepo.findById(victim).orElseThrow().getCrisisState());
        assertEquals(0, as(admin, () -> adminRisk.queue((short) 0, null, 0, 50)).getData().items().stream()
                .filter(m -> ((Number) m.get("id")).longValue() == ef.getId()).count());
    }

    // ---------------- A4 冻结即断会话，危机看板有全景 ----------------

    @Test
    void freezeRejectsSelfAndRevokesSessions() {
        long adminUid = newUser("ADMIN");
        long target = newUser("USER");
        var admin = new AuthPrincipal(adminUid, "ADMIN");

        assertThrows(BizException.class, () -> as(admin, () -> adminUsers.freeze(admin, adminUid)),
                "不许冻结自己");

        as(admin, () -> adminUsers.freeze(admin, target));
        assertEquals((short) 2, userRepo.findById(target).orElseThrow().getStatus());
        assertThrows(BizException.class, () -> as(admin, () -> adminUsers.freeze(admin, target)),
                "重复冻结拒绝");
        as(admin, () -> adminUsers.unfreeze(admin, target));
        assertEquals((short) 1, userRepo.findById(target).orElseThrow().getStatus());

        // 危机看板：处于 CRISIS 的用户带迁移轨迹
        crisis.onHighRisk(target, null);
        var board = as(admin, () -> adminUsers.crisisBoard()).getData();
        var row = board.stream().filter(m -> ((Number) m.get("id")).longValue() == target)
                .findFirst().orElseThrow();
        assertEquals("CRISIS", row.get("crisisState"));
        assertFalse(((List<?>) row.get("transitions")).isEmpty());
        // 元数据视图不含敏感列
        assertFalse(row.containsKey("passwordHash"));
        assertFalse(row.containsKey("phoneEnc"));
    }

    // ---------------- A5 审计链校验 + 密钥看板 ----------------

    @Test
    void auditChainVerifiesIntactAndKeysBoardReads() {
        long uid = newUser("ADMIN");
        var admin = new AuthPrincipal(uid, "ADMIN");

        // 校验动作本身也入链：第一次 verify 落 ADMIN_AUDIT_VERIFY，第二次才校验到非空链
        as(admin, () -> adminOps.verifyAudit(admin));
        var v = as(admin, () -> adminOps.verifyAudit(admin));
        assertTrue((Boolean) v.getData().get("intact"), "M9 后审计链必须完整");
        assertTrue(((Number) v.getData().get("checked")).longValue() > 0);

        // 产生过密文的用户必有 ACTIVE 密钥
        crypto.encryptUserField(uid, "for-keys-board");
        var keys = as(admin, () -> adminOps.keys()).getData();
        assertTrue(keys.stream().anyMatch(m -> "ACTIVE".equals(m.get("status"))));

        var audits = as(admin, () -> adminOps.audit(uid, "ADMIN_AUDIT_VERIFY", 0, 10)).getData();
        assertTrue(audits.total() >= 1);
        var exports = as(admin, () -> adminOps.exportRecords(0, 10)).getData();
        assertNotNull(exports.items());
    }

    // ---------------- A3 预览试跑 ----------------

    @Test
    void contentPreviewRendersTemplateAndCallsModel() {
        long uid = newUser("ADMIN");
        var admin = new AuthPrincipal(uid, "ADMIN");

        assertThrows(BizException.class, () -> as(admin, () -> adminContent.preview(admin,
                new ContentAdminController.PreviewBody("../application", "hi", null))),
                "路径穿越模板名必须被拒");
        assertThrows(BizException.class, () -> as(admin, () -> adminContent.preview(admin,
                new ContentAdminController.PreviewBody("no_such_tpl", "hi", null))),
                "不存在模板应 404 语义");

        var r = as(admin, () -> adminContent.preview(admin,
                new ContentAdminController.PreviewBody("support_v1", "最近两周很焦虑，睡不好。", Map.of())));
        assertEquals("support_v1", r.getData().get("template"));
        assertFalse(String.valueOf(r.getData().get("system")).isBlank());
        assertFalse(String.valueOf(r.getData().get("output")).isBlank());
        assertTrue(auditRepo.findForAdmin(uid, "CONTENT_PREVIEW",
                org.springframework.data.domain.PageRequest.of(0, 10)).getTotalElements() >= 1);
    }

    // ---------------- O3 看板契约 ----------------

    @Test
    void metricsOverviewShapeMatchesBoardContract() {
        long uid = newUser("ADMIN");
        var admin = new AuthPrincipal(uid, "ADMIN");
        Map<String, Object> m = as(admin, () -> metrics.overview(7)).getData();
        for (String k : List.of("windowDays", "tasks", "riskEvents", "agents", "tokensByDay", "runtime"))
            assertTrue(m.containsKey(k), "看板缺字段: " + k);
        assertEquals(7, ((Number) m.get("windowDays")).intValue());
        Map<?, ?> tasks = (Map<?, ?>) m.get("tasks");
        assertTrue(tasks.containsKey("today") && tasks.containsKey("window"));
        // days 越界钳制而不是报错
        assertEquals(90, ((Number) as(admin, () -> metrics.overview(999)).getData().get("windowDays")).intValue());
    }

    private void awaitRunning(String taskNo) throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            if (!"FAILED".equals(orchestrator.byNo(taskNo).getStatus())) return;
            Thread.sleep(100);
        }
        fail("重跑任务未在 15s 内进入执行: " + taskNo);
    }

    private void awaitFinish(String taskNo) throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            String s = orchestrator.byNo(taskNo).getStatus();
            if (s.equals("SUCCESS") || s.equals("FAILED") || s.equals("PARTIAL_SUCCESS")) return;
            Thread.sleep(100);
        }
        fail("任务未在 15s 内结束: " + orchestrator.byNo(taskNo).getStatus());
    }
}
