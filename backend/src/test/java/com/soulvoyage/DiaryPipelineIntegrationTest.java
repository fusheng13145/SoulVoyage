package com.soulvoyage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.agent.support.SupportAgent;
import com.soulvoyage.agent.trace.TraceAgent;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.emotion.EmotionTrajectoryEntity;
import com.soulvoyage.domain.emotion.EmotionTrajectoryRepository;
import com.soulvoyage.domain.report.ReportEntity;
import com.soulvoyage.domain.report.ReportRepository;
import com.soulvoyage.domain.risk.RiskEventEntity;
import com.soulvoyage.domain.risk.RiskEventRepository;
import com.soulvoyage.domain.task.AgentMessageEntity;
import com.soulvoyage.domain.task.AgentMessageRepository;
import com.soulvoyage.domain.task.TaskInstanceEntity;
import com.soulvoyage.domain.task.TaskInstanceRepository;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import com.soulvoyage.kg.KgSearchService;
import com.soulvoyage.orchestrator.OrchestratorService;
import com.soulvoyage.orchestrator.agent.OutputInvalidException;
import com.soulvoyage.llm.OutputValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class DiaryPipelineIntegrationTest {

    @Autowired OrchestratorService orchestrator;
    @Autowired TaskInstanceRepository taskRepo;
    @Autowired AgentMessageRepository msgRepo;
    @Autowired EmotionTrajectoryRepository trajRepo;
    @Autowired ReportRepository reportRepo;
    @Autowired RiskEventRepository riskEventRepo;
    @Autowired UserRepository userRepo;
    @Autowired CryptoService crypto;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired OutputValidator validator;

    private long newUser() {
        UserEntity u = new UserEntity();
        u.setUsername("u" + System.nanoTime());
        u.setNickname("t");
        u.setPasswordHash(encoder.encode("Passw0rd!2026"));
        return userRepo.save(u).getId();
    }

    @Test
    void diaryPipelineEndToEnd() throws Exception {
        long uid = newUser();
        var input = mapper.createObjectNode();
        input.put("diaryText", "和室友因为熄灯时间吵了一架，觉得很委屈，越想越气。");
        input.put("recordDate", "2026-09-19");

        TaskInstanceEntity t = orchestrator.submit(uid, "DIARY_PIPELINE", input, "it-" + System.nanoTime());
        awaitFinish(t.getTaskNo());

        TaskInstanceEntity done = orchestrator.byNo(t.getTaskNo());
        assertEquals("SUCCESS", done.getStatus());

        // 四步流水线（M4）：情绪→溯源→疏导→风险收口，最终 payload 为 ArchiveReceipt
        var receipt = orchestrator.finalPayload(done);
        assertEquals("LOW", receipt.path("riskLevel").asText());
        assertTrue(receipt.path("riskEventId").isNull(), "无风险不应产生风险事件");
        assertEquals("AES-256-GCM", receipt.path("encryption").path("algo").asText());
        assertTrue(receipt.path("archived").path("reports").asInt() >= 2);
        assertTrue(receipt.path("profileUpdated").asBoolean());

        // 溯源契约仍在 TRACE 中间结果里（report 五字段 + 压力源 + KG 候选内误区）
        var trace = middlePayload(uid, done.getId(), "TRACE");
        assertTrue(trace.has("report"), "TRACE 中间结果应包含复盘报告五字段");
        assertTrue(trace.get("reportId").asText().startsWith("rp_"));
        assertEquals("人际冲突", trace.path("stressors").get(0).path("source").asText());
        String kgId = trace.path("cognitiveDistortions").get(0).path("kgNodeId").asText();
        assertTrue(kgId.startsWith("cd_"));

        // 疏导契约（手册 §4.4）：练习全部来自闭集 + disclaimer 恒 true
        var support = middlePayload(uid, done.getId(), "SUPPORT");
        assertTrue(support.path("matchedExercises").size() >= 1);
        assertTrue(support.path("disclaimer").asBoolean());
        for (JsonNode ex : support.path("matchedExercises")) {
            assertTrue(ex.path("exerciseId").asText().startsWith("ex_"));
        }

        // 情绪步骤中间结果：mock 由「气/吵」判定愤怒
        List<AgentMessageEntity> msgs = msgRepo.findByTaskIdOrderByStepSeqAscIdAsc(done.getId());
        assertTrue(msgs.size() >= 9);   // (REQUEST+MIDDLE)×4 + FINAL
        String emotionMiddle = crypto.decryptUserField(uid,
                msgs.stream().filter(m -> m.getFromAgent().equals("EMOTION")).findFirst().orElseThrow()
                        .getPayloadEnc());
        assertTrue(emotionMiddle.contains("愤怒"));

        // 情绪时序落库
        List<EmotionTrajectoryEntity> traj = trajRepo
                .findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(uid,
                        java.time.LocalDate.of(2026, 9, 1), java.time.LocalDate.of(2026, 9, 30));
        assertEquals(1, traj.size());

        // 报告加密落库（TRACE + SUPPORT 两份），BLOB 中不得出现明文关键词
        List<ReportEntity> reports = reportRepo.findByTaskId(done.getId());
        assertEquals(2, reports.size());
        ReportEntity traceReport = reports.stream()
                .filter(r -> r.getType().equals("TRACE")).findFirst().orElseThrow();
        assertFalse(new String(traceReport.getContentEnc(), StandardCharsets.ISO_8859_1).contains("室友"));
        assertEquals("LOW", traceReport.getRiskLevel());
        assertTrue(crypto.decryptUserField(uid, traceReport.getContentEnc()).contains(kgId));

        // 全链路密文存储：任何 BLOB 均不含明文关键词
        assertTrue(msgs.stream().allMatch(m ->
                !new String(m.getPayloadEnc(), StandardCharsets.ISO_8859_1).contains("室友")));
    }

    @Test
    void crisisDiaryArchivesHighAndEntersCrisisMode() throws Exception {
        long uid = newUser();
        var input = mapper.createObjectNode();
        input.put("diaryText", "感觉一切都完了，真的撑不下去，活着没意思。");
        input.put("recordDate", "2026-09-19");

        var t = orchestrator.submit(uid, "DIARY_PIPELINE", input, "cx-" + System.nanoTime());
        awaitFinish(t.getTaskNo());
        var done = orchestrator.byNo(t.getTaskNo());
        assertEquals("SUCCESS", done.getStatus());

        // 规则轨一票升级：LLM 语义轨未报，仍判 HIGH + 转介 + 危机事件
        var receipt = orchestrator.finalPayload(done);
        assertEquals("HIGH", receipt.path("riskLevel").asText());
        assertNotEquals(0, receipt.path("riskEventId").asLong());
        assertTrue(receipt.path("referral").path("show").asBoolean());

        assertEquals("CRISIS", userRepo.findById(uid).orElseThrow().getCrisisState(), "HIGH 后应进入危机态（S1 状态机）");
        List<RiskEventEntity> events = riskEventRepo.findByUserIdOrderByCreatedAtDesc(uid);
        assertEquals(1, events.size());
        assertEquals("HIGH", events.get(0).getLevel());
        assertEquals("KEYWORD_RULE", events.get(0).getTriggerType());
        assertEquals("CRISIS_CARD+PROFILE_FLAG", events.get(0).getActionTaken());
        // 证据引用只存定位，不存原文
        assertFalse(new String(events.get(0).getEvidenceRefEnc(), StandardCharsets.ISO_8859_1)
                .contains("活着没意思"));

        // 第二个任务：危机模式旁路溯源/疏导，只跑 情绪→风险收口
        var t2 = orchestrator.submit(uid, "DIARY_PIPELINE",
                mapper.createObjectNode().put("diaryText", "今天还行"), "cx2-" + System.nanoTime());
        awaitFinish(t2.getTaskNo());
        var done2 = orchestrator.byNo(t2.getTaskNo());
        assertEquals("SUCCESS", done2.getStatus());
        List<AgentMessageEntity> msgs2 = msgRepo.findByTaskIdOrderByStepSeqAscIdAsc(done2.getId());
        assertEquals(5, msgs2.size(), "危机模式只应有 (REQ+MID)×2 + FINAL");
        assertTrue(msgs2.stream().noneMatch(m -> "SUPPORT".equals(m.getToAgent())));
        assertTrue(msgs2.stream().noneMatch(m -> "TRACE".equals(m.getToAgent())));
    }

    @Test
    void persistentLowMoodUpgradesToMediumEvent() throws Exception {
        long uid = newUser();
        var input = mapper.createObjectNode();
        input.put("diaryText", "已经两周了，每天都很疲惫，高兴不起来，觉得自己很失败。");
        var t = orchestrator.submit(uid, "DIARY_PIPELINE", input, "md-" + System.nanoTime());
        awaitFinish(t.getTaskNo());

        var receipt = orchestrator.finalPayload(orchestrator.byNo(t.getTaskNo()));
        assertEquals("MEDIUM", receipt.path("riskLevel").asText());
        assertTrue(receipt.path("referral").path("show").asBoolean());

        List<RiskEventEntity> events = riskEventRepo.findByUserIdOrderByCreatedAtDesc(uid);
        assertEquals(1, events.size());
        assertEquals("MEDIUM", events.get(0).getLevel());
        assertEquals("REFERRAL_UPGRADE", events.get(0).getActionTaken());
        assertEquals("NORMAL", userRepo.findById(uid).orElseThrow().getCrisisState(), "MEDIUM 不进危机态");
    }

    @Test
    void kgReverseValidationRejectsInventedNode() {
        var candidates = List.of(new KgSearchService.DistortionCard(
                "cd_mind_reading", "读心术", "d", "t", "q"));
        var good = mapper.createObjectNode();
        good.putArray("cognitiveDistortions").addObject().put("kgNodeId", "cd_mind_reading");
        assertDoesNotThrow(() -> TraceAgent.assertKgNodeIds(good, candidates));

        var bad = mapper.createObjectNode();
        bad.putArray("cognitiveDistortions").addObject().put("kgNodeId", "cd_fake_hallucination");
        assertThrows(OutputInvalidException.class,
                () -> TraceAgent.assertKgNodeIds(bad, candidates));
    }

    @Test
    void supportExerciseIdClosedSetAndDisclaimerEnforced() {
        // 练习 id 反向闭集校验（与 KG 候选校验同一模式）
        var candidates = List.of(new com.soulvoyage.agent.support.Exercise(
                "ex_54321", "54321 感官着陆", List.of("ANXIETY"), List.of(), 5));
        var good = mapper.createObjectNode();
        good.putArray("matchedExercises").addObject().put("exerciseId", "ex_54321");
        assertDoesNotThrow(() -> SupportAgent.assertExerciseIds(good, candidates));

        var bad = mapper.createObjectNode();
        bad.putArray("matchedExercises").addObject().put("exerciseId", "ex_cure_everything");
        assertThrows(OutputInvalidException.class,
                () -> SupportAgent.assertExerciseIds(bad, candidates));

        // disclaimer 恒 true：Schema const 拦截
        var plan = mapper.createObjectNode();
        plan.put("planTitle", "小方案");
        plan.putArray("matchedExercises").addObject()
                .put("exerciseId", "ex_54321").put("reason", "r").put("schedule", "此刻");
        var edu = plan.putObject("psyEducation");
        edu.put("topic", "t").put("content", "c").put("kgSource", "node:x");
        plan.put("disclaimer", false);
        assertThrows(OutputInvalidException.class,
                () -> validator.validate("support_plan.json", plan.toString()));
    }

    @Test
    void idempotentSubmitReturnsSameTask() {
        long uid = newUser();
        var input = mapper.createObjectNode().put("diaryText", "心情一般");
        String reqId = "idem-" + System.nanoTime();
        var a = orchestrator.submit(uid, "DIARY_PIPELINE", input, reqId);
        var b = orchestrator.submit(uid, "DIARY_PIPELINE", input, reqId);
        assertEquals(a.getTaskNo(), b.getTaskNo());
    }

    @Test
    void unknownPipelineRejected() {
        assertThrows(com.soulvoyage.common.exception.BizException.class,
                () -> orchestrator.submit(newUser(), "NOT_EXIST", mapper.createObjectNode(), null));
    }

    @Test
    void validatorBlocksOutOfBoundsOutput() {
        // 1) 情绪不在闭集枚举 → Schema 拒绝
        ObjectNode bad = mapper.createObjectNode();
        bad.put("primaryEmotion", "抑郁症前兆");
        bad.put("intensity", 0.5);
        bad.put("valence", -0.5);
        bad.putArray("eventTags");
        assertThrows(OutputInvalidException.class,
                () -> validator.validate("emotion_result.json", bad.toString()));

        // 2) Schema 合法但含诊断病名 → 词表拦截（三层硬约束之输出校验层）
        ObjectNode sneaky = mapper.createObjectNode();
        sneaky.put("primaryEmotion", "悲伤");
        sneaky.put("intensity", 0.8);
        sneaky.put("valence", -0.8);
        sneaky.putArray("eventTags").addObject()
                .put("tag", "其他")
                .put("evidence", "你很可能患有抑郁症");
        assertThrows(OutputInvalidException.class,
                () -> validator.validate("emotion_result.json", sneaky.toString()));

        // 3) trace 输出：source 不在压力源闭集 → Schema 拒绝
        ObjectNode badTrace = mapper.createObjectNode();
        badTrace.putArray("stressors").addObject()
                .put("source", "前世今生").put("confidence", 0.5).putArray("evidence").add("x");
        badTrace.putArray("cognitiveDistortions");
        badTrace.putArray("socraticQuestions").add("q");
        ObjectNode r = badTrace.putObject("report");
        r.put("eventSummary", "a"); r.put("emotionSummary", "b"); r.put("thoughtSummary", "c");
        r.put("insight", "d"); r.put("suggestion", "e");
        badTrace.putArray("riskSignals");
        assertThrows(OutputInvalidException.class,
                () -> validator.validate("trace_result.json", badTrace.toString()));
    }

    private JsonNode middlePayload(long uid, long taskId, String agent) throws Exception {
        return msgRepo.findByTaskIdOrderByStepSeqAscIdAsc(taskId).stream()
                .filter(m -> agent.equals(m.getFromAgent()) && "MIDDLE_RESULT".equals(m.getMsgType()))
                .findFirst()
                .map(m -> {
                    try {
                        return mapper.readTree(crypto.decryptUserField(uid, m.getPayloadEnc()));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .orElseThrow(() -> new AssertionError("缺少 " + agent + " 中间结果"));
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
