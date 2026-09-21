package com.soulvoyage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.asr.MockAsrClient;
import com.soulvoyage.domain.diary.DiaryEntity;
import com.soulvoyage.domain.diary.DiaryRepository;
import com.soulvoyage.domain.diary.VoiceDiaryController;
import com.soulvoyage.domain.risk.RiskEventRepository;
import com.soulvoyage.domain.task.AgentMessageEntity;
import com.soulvoyage.domain.task.AgentMessageRepository;
import com.soulvoyage.domain.task.TaskInstanceEntity;
import com.soulvoyage.domain.user.UserRepository;
import com.soulvoyage.orchestrator.OrchestratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M11 语音日记（手册 §11.3 首项）的上线证据。
 *
 * 验收判据只有一条硬口径：**新增输入源零改动 Agent**——语音不带来第二条流水线、
 * 不带来第二个落库口子、也不带来一套更松的护栏。这里用四组断言把它钉住：
 * ① 转写端点只回文本、不建档（提交仍走 POST /tasks）；
 * ② 语音标记的危机文本与手写路径同样当轮拦截并进危机态；
 * ③ 同一段文本以 TEXT 与 VOICE 各跑一遍，步骤序列逐条全等（Agent 看不到输入源）；
 * ④ 音频全程不落盘（配置上内存阈值高于业务上限 + 上传前后临时目录零新增）。
 *
 * 走 RANDOM_PORT + TestRestTemplate：multipart 与 Security 过滤器链只能在真实 HTTP 栈上验。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class M11VoiceDiaryTest {

    private static final String PWD = "Passw0rd!2026";
    private static final String CRISIS_TEXT = "感觉一切都完了，真的撑不下去，活着没意思。";
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepo;
    @Autowired DiaryRepository diaryRepo;
    @Autowired AgentMessageRepository msgRepo;
    @Autowired RiskEventRepository riskEventRepo;
    @Autowired OrchestratorService orchestrator;
    @Autowired Environment env;

    record Actor(String jwt, long uid) {}

    @BeforeEach
    void tuneTimeouts() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(10_000);
        f.setReadTimeout(60_000);   // 四步流水线含 Mock LLM 逐步延迟
        rest.getRestTemplate().setRequestFactory(f);
    }

    // ---------------------------------------------------------------- ① 转写只回文本

    @Test
    void transcriptionReturnsTextAndBuildsNoDiary() throws Exception {
        Actor a = register("v1a");
        assertEquals(0, diaryRepo.findByUserIdAndDeletedAtIsNullOrderByRecordDateDescIdDesc(a.uid()).size(),
                "新账号本就不该有日记");

        ResponseEntity<String> r = upload(a.jwt(), 64_000, "audio/webm", 12_345L);
        assertEquals(200, r.getStatusCode().value(), "合法录音应转写成功: " + brief(r.getBody()));
        JsonNode d = data(r);
        assertEquals(MockAsrClient.SAMPLE_TRANSCRIPT, d.path("text").asText(),
                "转写文本应逐字回给属主（供校对），不做任何加工");
        assertEquals(MockAsrClient.MODEL, d.path("model").asText());
        assertEquals(12_345, d.path("durationMs").asLong());
        assertTrue(d.path("costMs").asLong() >= 0);

        assertEquals(0, diaryRepo.findByUserIdAndDeletedAtIsNullOrderByRecordDateDescIdDesc(a.uid()).size(),
                "转写阶段绝不能建档——是否成稿由用户校对后提交决定");

        // 校对后的文本交回来才成稿，且带上输入源标记
        String taskNo = submitDiary(a, d.path("text").asText(), DiaryEntity.SOURCE_VOICE, 12_345L);
        awaitFinish(taskNo);
        long taskId = orchestrator.byNo(taskNo).getId();
        DiaryEntity saved = diaryRepo.findByTaskIdAndUserId(taskId, a.uid()).orElseThrow();
        assertEquals(DiaryEntity.SOURCE_VOICE, saved.getSource());
        assertEquals(12_345, saved.getVoiceDurationMs());

        JsonNode item = data(rest.exchange("/api/v1/diaries?page=0&size=20", HttpMethod.GET,
                new HttpEntity<>(headers(a.jwt())), String.class)).path("items").get(0);
        assertEquals(DiaryEntity.SOURCE_VOICE, item.path("source").asText(), "列表要能标出语音角标");
        JsonNode detail = get(a.jwt(), "/api/v1/diaries/" + saved.getId());
        assertEquals(DiaryEntity.SOURCE_VOICE, detail.path("source").asText());
        assertEquals(12_345, detail.path("voiceDurationMs").asLong());
        assertEquals(MockAsrClient.SAMPLE_TRANSCRIPT, detail.path("content").asText(), "原文仍可解密读回");
    }

    // ---------------------------------------------------------------- 参数与鉴权矩阵

    @Test
    void badUploadsAreRejectedBeforeAnyModelCall() throws Exception {
        Actor a = register("v1b");

        assertEquals(2001, codeOf(upload(a.jwt(), 0, "audio/webm", null)), "空文件应 2001");
        assertEquals(2001, codeOf(upload(a.jwt(), 1024, "application/x-msdownload", null)),
                "非音频类型应 2001（不许借上传口投喂别的字节流）");
        assertEquals(2001, codeOf(upload(a.jwt(), 1024, "text/plain", null)), "text/plain 同样拒绝");
        assertEquals(2001, codeOf(upload(a.jwt(), (int) MAX_BYTES + 1024, "audio/webm", null)),
                "超过 5MB 业务上限应 2001（容器 6MB 兜底在其后）");
        assertEquals(2001, codeOf(upload(a.jwt(), 1024, "audio/webm", 91_000L)),
                "时长超过 90 秒应 2001");
        assertEquals(200, upload(a.jwt(), 1024, "audio/wav; codecs=1", null).getStatusCode().value(),
                "带参数的 MIME 主类型相同即放行");
        assertEquals(200, upload(a.jwt(), 1024, "Audio/MPEG", null).getStatusCode().value(),
                "大小写不敏感");

        // 匿名探测走无请求体的同路由请求：JDK 客户端在流式上传下读不到 401 响应体，
        // 而"这条路由整体在鉴权后"与请求体无关，GET 同样能把门守住。
        ResponseEntity<String> anon = rest.exchange("/api/v1/diaries/voice-transcriptions", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);
        assertEquals(401, anon.getStatusCode().value(), "转写端点必须登录（属主自己的录音）");
        assertEquals(1001, codeOf(anon));
    }

    // ---------------------------------------------------------------- ④ 音频不落盘

    @Test
    void audioStaysInMemoryAndNeverTouchesDisk() throws Exception {
        long threshold = bytesOf(env.getProperty("spring.servlet.multipart.file-size-threshold", "0"));
        long maxBytes = env.getProperty("soulvoyage.asr.max-bytes", Long.class, MAX_BYTES);
        assertTrue(threshold >= maxBytes,
                "内存阈值必须不低于业务上限，否则录音会被容器转写成临时文件：" + threshold + " < " + maxBytes);

        Actor a = register("v1c");
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        Set<String> before = scratchFiles(tmp);
        // 4MB 刻意贴近上限：任何"按大小转存磁盘"的配置在这里都会露出来
        assertEquals(200, upload(a.jwt(), 4 * 1024 * 1024, "audio/mp4", 80_000L).getStatusCode().value());
        assertEquals(before, scratchFiles(tmp), "转写一次录音不应在临时目录留下任何新文件");
    }

    // ---------------------------------------------------------------- ② 语音不绕过危机拦截

    @Test
    void crisisTextFromVoiceIsBlockedOnTheSameTurn() throws Exception {
        Actor a = register("v1d");
        String taskNo = submitDiary(a, CRISIS_TEXT, DiaryEntity.SOURCE_VOICE, 8_000L);
        awaitFinish(taskNo);

        JsonNode receipt = orchestrator.finalPayload(orchestrator.byNo(taskNo));
        assertEquals("HIGH", receipt.path("riskLevel").asText(), "语音转写的危机文本同样被规则轨一票升级");
        assertTrue(receipt.path("referral").path("show").asBoolean(), "高危必须给转介卡");
        assertEquals("CRISIS", userRepo.findById(a.uid()).orElseThrow().getCrisisState());
        assertEquals(1, riskEventRepo.findByUserIdOrderByCreatedAtDesc(a.uid()).size());

        DiaryEntity d = diaryRepo.findByTaskIdAndUserId(orchestrator.byNo(taskNo).getId(), a.uid()).orElseThrow();
        assertEquals(DiaryEntity.SOURCE_VOICE, d.getSource(), "危机不剥夺数据所有权：语音日记照常成稿可读");
    }

    // ---------------------------------------------------------------- ③ 零改动 Agent

    @Test
    void voiceAndTextInputsRunTheVerySamePipeline() throws Exception {
        Actor typed = register("v1e");
        Actor voiced = register("v1f");
        String text = MockAsrClient.SAMPLE_TRANSCRIPT;

        String t1 = submitDiary(typed, text, DiaryEntity.SOURCE_TEXT, null);
        String t2 = submitDiary(voiced, text, DiaryEntity.SOURCE_VOICE, 15_000L);
        awaitFinish(t1);
        awaitFinish(t2);

        TaskInstanceEntity a1 = orchestrator.byNo(t1);
        TaskInstanceEntity a2 = orchestrator.byNo(t2);
        assertEquals("SUCCESS", a1.getStatus());
        assertEquals("SUCCESS", a2.getStatus());
        assertEquals(stepShape(a1.getId()), stepShape(a2.getId()),
                "同一文本换成语音来源，步骤数/顺序/收发方必须逐条全等——Agent 对输入源零感知");
        assertEquals(orchestrator.finalPayload(a1).path("riskLevel").asText(),
                orchestrator.finalPayload(a2).path("riskLevel").asText(), "风险结论不得随输入源漂移");
    }

    // ---------------------------------------------------------------- 用户级滑动窗

    @Test
    void transcriptionWindowIsPerUserNotGlobal() throws Exception {
        Actor a = register("v1g");
        Actor b = register("v1h");
        for (int i = 0; i < VoiceDiaryController.RATE_PER_MINUTE; i++) {
            assertEquals(200, upload(a.jwt(), 1024, "audio/webm", null).getStatusCode().value(),
                    "窗内第 " + (i + 1) + " 次应放行");
        }
        ResponseEntity<String> over = upload(a.jwt(), 1024, "audio/webm", null);
        assertEquals(429, over.getStatusCode().value(), "同用户第 11 次应被滑动窗拒掉");
        assertEquals(4002, codeOf(over));

        // 限流是"每个用户一扇窗"，不是全局闸：同一段时间里别的账号不该被牵连
        List<Integer> otherUser = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            otherUser.add(upload(b.jwt(), 1024, "audio/webm", null).getStatusCode().value());
        }
        assertTrue(otherUser.stream().allMatch(c -> c == 200), "别的账号不该被牵连: " + otherUser);
    }

    // ---------------------------------------------------------------- helpers

    private HttpHeaders headers(String jwt) {
        HttpHeaders h = new HttpHeaders();
        if (jwt != null) h.setBearerAuth(jwt);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<String> get0(String jwt, String path) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(jwt)), String.class);
    }

    private JsonNode get(String jwt, String path) throws Exception {
        return data(get0(jwt, path));
    }

    /** multipart 上传一段"录音"：part 的 Content-Type 就是浏览器 MediaRecorder 给的那个 */
    private ResponseEntity<String> upload(String jwt, int bytes, String contentType, Long durationMs) {
        HttpHeaders h = new HttpHeaders();
        if (jwt != null) h.setBearerAuth(jwt);
        h.setContentType(MediaType.MULTIPART_FORM_DATA);

        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        ByteArrayResource audio = new ByteArrayResource(new byte[Math.max(bytes, 0)]) {
            @Override
            public String getFilename() {
                return "voice.bin";
            }
        };
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new HttpEntity<>(audio, partHeaders));
        if (durationMs != null) form.add("durationMs", String.valueOf(durationMs));

        return rest.exchange("/api/v1/diaries/voice-transcriptions", HttpMethod.POST,
                new HttpEntity<>(form, h), String.class);
    }

    private String submitDiary(Actor a, String text, String source, Long durationMs) throws Exception {
        var payload = new java.util.HashMap<String, Object>();
        payload.put("diaryText", text);
        payload.put("recordDate", java.time.LocalDate.now().toString());
        payload.put("diarySource", source);
        if (durationMs != null) payload.put("voiceDurationMs", durationMs);
        ResponseEntity<String> r = rest.exchange("/api/v1/tasks", HttpMethod.POST,
                new HttpEntity<>(Map.of("pipelineCode", "DIARY_PIPELINE", "payload", payload,
                        "clientReqId", "m11-" + System.nanoTime()), headers(a.jwt())), String.class);
        assertEquals(202, r.getStatusCode().value(), "提交任务应 202: " + brief(r.getBody()));
        return data(r).path("taskNo").asText();
    }

    /** 步骤骨架：序号 + 收发方 + 消息类型——不含业务正文，正是"Agent 是否感知输入源"的判据面 */
    private List<String> stepShape(long taskId) {
        List<String> shape = new ArrayList<>();
        for (AgentMessageEntity m : msgRepo.findByTaskIdOrderByStepSeqAscIdAsc(taskId)) {
            shape.add(m.getStepSeq() + ":" + m.getFromAgent() + ">" + m.getToAgent() + ":" + m.getMsgType());
        }
        return shape;
    }

    /** 临时目录里"疑似上传转存"的文件名快照（tomcat/multipart/upload/servlet 前缀 + .tmp 后缀） */
    private Set<String> scratchFiles(Path tmp) throws IOException {
        try (Stream<Path> s = Files.list(tmp)) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> {
                        String l = n.toLowerCase();
                        return l.startsWith("tomcat") || l.startsWith("multipart")
                                || l.startsWith("upload") || l.endsWith(".tmp");
                    })
                    .sorted().collect(Collectors.toSet());
        }
    }

    private long bytesOf(String size) {
        String s = size == null ? "" : size.trim().toLowerCase();
        if (s.endsWith("kb")) return Long.parseLong(s.replace("kb", "").trim()) * 1024;
        if (s.endsWith("mb")) return Long.parseLong(s.replace("mb", "").trim()) * 1024 * 1024;
        if (s.endsWith("gb")) return Long.parseLong(s.replace("gb", "").trim()) * 1024 * 1024 * 1024;
        return Long.parseLong(s.isEmpty() ? "0" : s);
    }

    private JsonNode data(ResponseEntity<String> r) throws Exception {
        assertTrue(r.getStatusCode().is2xxSuccessful(), "响应应为 2xx: " + brief(r.getBody()));
        return mapper.readTree(r.getBody()).path("data");
    }

    private int codeOf(ResponseEntity<String> r) throws Exception {
        return mapper.readTree(r.getBody()).path("code").asInt();
    }

    private String brief(String s) {
        return s == null ? "" : s.substring(0, Math.min(200, s.length()));
    }

    private void awaitFinish(String taskNo) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            String s = orchestrator.byNo(taskNo).getStatus();
            if (s.equals("SUCCESS") || s.equals("FAILED") || s.equals("PARTIAL_SUCCESS")) return;
            Thread.sleep(150);
        }
        fail("任务未在 30s 内结束: " + orchestrator.byNo(taskNo).getStatus());
    }

    private Actor register(String prefix) throws Exception {
        ResponseEntity<String> r = rest.exchange("/api/v1/auth/register", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", prefix + System.nanoTime(),
                        "password", PWD, "nickname", "t"), headers(null)), String.class);
        assertEquals(200, r.getStatusCode().value(), "注册探针账号失败: " + brief(r.getBody()));
        JsonNode d = mapper.readTree(r.getBody()).path("data");
        return new Actor(d.path("accessToken").asText(), d.path("userId").asLong());
    }
}
