package com.soulvoyage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.agent.trace.TraceAgent;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.emotion.EmotionTrajectoryEntity;
import com.soulvoyage.domain.emotion.EmotionTrajectoryRepository;
import com.soulvoyage.domain.report.ReportEntity;
import com.soulvoyage.domain.report.ReportRepository;
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

        // 两步流水线：末步（TRACE）输出为最终 payload
        var payload = orchestrator.finalPayload(done);
        assertTrue(payload.has("report"), "最终输出应包含复盘报告五字段");
        assertTrue(payload.get("reportId").asText().startsWith("rp_"));
        assertEquals("人际冲突", payload.path("stressors").get(0).path("source").asText());
        // 误区引用必须来自 KG 候选集（mock 输出经反向校验后仅剩候选 id）
        String kgId = payload.path("cognitiveDistortions").get(0).path("kgNodeId").asText();
        assertTrue(kgId.startsWith("cd_"));

        // 情绪步骤中间结果：mock 由「气/吵」判定愤怒
        List<AgentMessageEntity> msgs = msgRepo.findByTaskIdOrderByStepSeqAscIdAsc(done.getId());
        assertTrue(msgs.size() >= 5);   // (REQUEST+MIDDLE)×2 + FINAL
        String emotionMiddle = crypto.decryptUserField(uid,
                msgs.stream().filter(m -> m.getFromAgent().equals("EMOTION")).findFirst().orElseThrow()
                        .getPayloadEnc());
        assertTrue(emotionMiddle.contains("愤怒"));

        // 情绪时序落库
        List<EmotionTrajectoryEntity> traj = trajRepo
                .findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(uid,
                        java.time.LocalDate.of(2026, 9, 1), java.time.LocalDate.of(2026, 9, 30));
        assertEquals(1, traj.size());

        // 复盘报告加密落库，BLOB 中不得出现明文关键词
        List<ReportEntity> reports = reportRepo
                .findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(uid,
                        org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
        assertEquals(1, reports.size());
        assertFalse(new String(reports.get(0).getContentEnc(), StandardCharsets.ISO_8859_1).contains("室友"));
        assertEquals("LOW", reports.get(0).getRiskLevel());
        assertTrue(crypto.decryptUserField(uid, reports.get(0).getContentEnc()).contains(kgId));

        // 全链路密文存储：任何 BLOB 均不含明文关键词
        assertTrue(msgs.stream().allMatch(m ->
                !new String(m.getPayloadEnc(), StandardCharsets.ISO_8859_1).contains("室友")));
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

    private void awaitFinish(String taskNo) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String s = orchestrator.byNo(taskNo).getStatus();
            if (s.equals("SUCCESS") || s.equals("FAILED") || s.equals("PARTIAL_SUCCESS")) return;
            Thread.sleep(100);
        }
        fail("任务未在 10s 内结束: " + orchestrator.byNo(taskNo).getStatus());
    }
}
