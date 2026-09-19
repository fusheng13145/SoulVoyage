package com.soulvoyage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.agent.simulate.NpcDirector;
import com.soulvoyage.agent.simulate.SimulationService;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.report.ReportEntity;
import com.soulvoyage.domain.report.ReportRepository;
import com.soulvoyage.domain.risk.RiskEventEntity;
import com.soulvoyage.domain.risk.RiskEventRepository;
import com.soulvoyage.domain.simulate.SimulateSessionEntity;
import com.soulvoyage.domain.simulate.SimulateSessionRepository;
import com.soulvoyage.domain.simulate.SimulateTurnEntity;
import com.soulvoyage.domain.simulate.SimulateTurnRepository;
import com.soulvoyage.domain.task.AgentMessageRepository;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import com.soulvoyage.orchestrator.OrchestratorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** UC2 人际模拟训练全链路（Mock LLM 回放）：开场 → 多轮（导演状态机）→ 复盘任务 → 加密报告 */
@SpringBootTest
@ActiveProfiles("test")
class SimulatePipelineIntegrationTest {

    @Autowired SimulationService simulation;
    @Autowired OrchestratorService orchestrator;
    @Autowired SimulateSessionRepository sessionRepo;
    @Autowired SimulateTurnRepository turnRepo;
    @Autowired ReportRepository reportRepo;
    @Autowired RiskEventRepository riskEventRepo;
    @Autowired AgentMessageRepository msgRepo;
    @Autowired UserRepository userRepo;
    @Autowired CryptoService crypto;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;

    private long newUser() {
        UserEntity u = new UserEntity();
        u.setUsername("s" + System.nanoTime());
        u.setNickname("t");
        u.setPasswordHash(encoder.encode("Passw0rd!2026"));
        return userRepo.save(u).getId();
    }

    @Test
    void simulateHappyPathProducesEncryptedReviewReport() throws Exception {
        long uid = newUser();
        var opened = simulation.open(uid, "DORM_CONFLICT", "NORMAL");
        assertEquals("小磊", opened.npcName());
        assertFalse(opened.openingLine().isBlank());

        // 第 1 轮：我-信息立边界 → 导演判 BOUNDARY_SET，NPC 缓和
        var t1 = simulation.turn(uid, opened.simulateId(),
                "我注意到这周有三次熄灯后还在语音，我感到白天没精神，我们可以约定一个折中时间吗");
        assertEquals("BOUNDARY_SET", t1.stateTag());
        assertEquals("SOFTENED", t1.npcEmotion());
        assertFalse(t1.npcText().isBlank());

        // 第 2 轮：指控式表达 → 张力显著抬升
        var t2 = simulation.turn(uid, opened.simulateId(),
                "你总是这样！你从来不听，自私，闭嘴，烦不烦，我受够你了");
        assertTrue(t2.tension() >= t1.tension() + 20, "指控应显著升温");
        assertEquals("ESCALATED", t2.npcEmotion());
        assertEquals("CONFLICT_UP", t2.stateTag());

        String taskNo = simulation.finish(uid, opened.simulateId());
        awaitFinish(taskNo);
        var task = orchestrator.byNo(taskNo);
        assertEquals("SUCCESS", task.getStatus());

        SimulateSessionEntity s = sessionRepo.findById(opened.simulateId()).orElseThrow();
        assertEquals("FINISHED", s.getStatus());
        assertNotNull(s.getReportId());

        // M4 起末步为 RISK_ARCHIVE：复盘契约看 SIMULATE 中间结果，最终 payload 是 ArchiveReceipt
        var receipt = orchestrator.finalPayload(task);
        assertEquals("LOW", receipt.path("riskLevel").asText());
        assertTrue(receipt.path("profileUpdated").asBoolean());

        var payload = middlePayload(uid, task.getId(), "SIMULATE");
        assertTrue(payload.path("turnScores").size() == 2);
        assertEquals("BOUNDARY", payload.path("turnScores").get(0).path("dimension").asText());
        assertEquals("A", payload.path("turnScores").get(0).path("grade").asText());
        assertEquals("D", payload.path("turnScores").get(1).path("grade").asText());
        assertTrue(payload.path("rewriteSuggestions").size() >= 1);
        int avg = payload.path("overall").path("avgScore").asInt();
        assertTrue(avg > 40 && avg < 100);
        assertTrue(payload.path("reportId").asText().startsWith("rp_"));

        // 敏感数据全密文：逐轮用户发言与报告正文 BLOB 均不得含明文关键词
        List<SimulateTurnEntity> turns = turnRepo.findBySimulateIdOrderByTurnNoAsc(opened.simulateId());
        assertEquals(2, turns.size());
        for (SimulateTurnEntity t : turns) {
            assertFalse(new String(t.getUserTextEnc(), StandardCharsets.ISO_8859_1).contains("熄灯"));
            assertFalse(new String(t.getUserTextEnc(), StandardCharsets.ISO_8859_1).contains("自私"));
        }
        assertEquals(crypto.decryptUserField(uid, turns.get(0).getUserTextEnc()).indexOf("我注意到"), 0);

        ReportEntity report = reportRepo.findById(s.getReportId()).orElseThrow();
        assertEquals("SIMULATE", report.getType());
        assertEquals("LOW", report.getRiskLevel());
        assertEquals(opened.simulateId(), report.getBizRefId());
        String json = crypto.decryptUserField(uid, report.getContentEnc());
        assertTrue(json.contains("BOUNDARY_SET") || json.contains("立边界"));
        assertFalse(new String(report.getContentEnc(), StandardCharsets.ISO_8859_1).contains("熄灯"));

        // 会话回放接口：属主可见解密原文
        var view = simulation.transcript(uid, opened.simulateId());
        assertTrue(view.path("turns").get(0).path("userText").asText().contains("我注意到"));
    }

    @Test
    void crisisSpeechBreaksOutGentlyAndArchivesHighRisk() throws Exception {
        long uid = newUser();
        var opened = simulation.open(uid, "GROUP_PROJECT", "HARD");

        var tc = simulation.turn(uid, opened.simulateId(), "吵这些没意义，我真的不想活了，也撑不下去。");
        assertTrue(tc.crisis());
        assertEquals(SimulationService.CRISIS_BREAK_LINE, tc.npcText());

        SimulateSessionEntity s = sessionRepo.findById(opened.simulateId()).orElseThrow();
        assertEquals("ABORTED_RISK", s.getStatus());
        // 危机退出后禁止继续扮演对话
        assertThrows(BizException.class,
                () -> simulation.turn(uid, opened.simulateId(), "继续吧"));

        String taskNo = simulation.finish(uid, opened.simulateId());
        awaitFinish(taskNo);
        var task = orchestrator.byNo(taskNo);
        assertEquals("SUCCESS", task.getStatus());
        ReportEntity r = reportRepo.findById(sessionRepo.findById(opened.simulateId())
                .orElseThrow().getReportId()).orElseThrow();
        assertEquals("HIGH", r.getRiskLevel());

        // RISK_ARCHIVE 收口：剧情外危机 → SIMULATE_BREAKOUT/HIGH，加密归档 + 用户进入危机模式
        var receipt = orchestrator.finalPayload(task);
        assertEquals("HIGH", receipt.path("riskLevel").asText());
        assertTrue(receipt.path("referral").path("show").asBoolean());
        List<RiskEventEntity> events = riskEventRepo.findByUserIdOrderByCreatedAtDesc(uid);
        assertEquals(1, events.size());
        assertEquals("SIMULATE_BREAKOUT", events.get(0).getTriggerType());
        assertFalse(new String(events.get(0).getEvidenceRefEnc(), StandardCharsets.ISO_8859_1)
                .contains("不想活"));
        assertEquals((short) 1, userRepo.findById(uid).orElseThrow().getCrisisFlag());
    }

    @Test
    void sceneValidationAndOwnership() {
        long uid = newUser();
        long other = newUser();
        assertThrows(BizException.class, () -> simulation.open(uid, "NO_SUCH", "NORMAL"));
        assertThrows(BizException.class, () -> simulation.open(uid, "PARTNER_AVOIDANT", "MILD"));

        var opened = simulation.open(uid, "FAMILY_EXPECTATION", null);   // 缺省难度
        assertEquals("NORMAL", sessionRepo.findById(opened.simulateId()).orElseThrow().getDifficulty());
        assertThrows(BizException.class, () -> simulation.turn(other, opened.simulateId(), "喂？"));
        assertThrows(BizException.class, () -> simulation.finish(uid, opened.simulateId())); // 0 轮不可复盘
    }

    @Test
    void directorStateSurvivesAcrossTurns() {
        long uid = newUser();
        var opened = simulation.open(uid, "DORM_CONFLICT", "NORMAL");
        int tension = -1;
        for (int i = 0; i < 6; i++) {
            var r = simulation.turn(uid, opened.simulateId(), "把话说清楚点行吗");
            tension = r.tension();
        }
        assertTrue(tension > 38, "无信号口语也应缓慢升温，快照经每轮密文往返: " + tension);
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
            String st = orchestrator.byNo(taskNo).getStatus();
            if (st.equals("SUCCESS") || st.equals("FAILED") || st.equals("PARTIAL_SUCCESS")) return;
            Thread.sleep(100);
        }
        fail("复盘任务未在 15s 内结束");
    }
}
