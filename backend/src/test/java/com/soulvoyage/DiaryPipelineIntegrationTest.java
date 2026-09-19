package com.soulvoyage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.emotion.EmotionTrajectoryEntity;
import com.soulvoyage.domain.emotion.EmotionTrajectoryRepository;
import com.soulvoyage.domain.task.AgentMessageEntity;
import com.soulvoyage.domain.task.AgentMessageRepository;
import com.soulvoyage.domain.task.TaskInstanceEntity;
import com.soulvoyage.domain.task.TaskInstanceRepository;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
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

        var payload = orchestrator.finalPayload(done);
        assertEquals("愤怒", payload.get("primaryEmotion").asText());   // mock 由"气/吵"判定
        assertTrue(payload.get("intensity").asDouble() > 0);

        // 情绪时序落库
        List<EmotionTrajectoryEntity> traj = trajRepo
                .findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(uid,
                        java.time.LocalDate.of(2026, 9, 1), java.time.LocalDate.of(2026, 9, 30));
        assertEquals(1, traj.size());

        // 中间结果 append-only 且密文存储（明文关键词不得出现在 BLOB 中）
        List<AgentMessageEntity> msgs = msgRepo.findByTaskIdOrderByStepSeqAscIdAsc(done.getId());
        assertTrue(msgs.size() >= 3);   // REQUEST + MIDDLE_RESULT + FINAL
        assertTrue(msgs.stream().allMatch(m -> m.getMsgType().equals("REQUEST")
                || m.getMsgType().equals("MIDDLE_RESULT") || m.getMsgType().equals("FINAL")));
        byte[] cipher = msgs.get(1).getPayloadEnc();
        assertFalse(new String(cipher, StandardCharsets.ISO_8859_1).contains("室友"));
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
