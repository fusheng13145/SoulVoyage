package com.soulvoyage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.agent.risk.RiskArchiveAgent;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.report.ReportEntity;
import com.soulvoyage.domain.report.ReportRepository;
import com.soulvoyage.domain.risk.RiskEventEntity;
import com.soulvoyage.domain.risk.RiskEventRepository;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import com.soulvoyage.orchestrator.pipeline.StepSpec;
import com.soulvoyage.orchestrator.agent.AgentRuntime;
import com.soulvoyage.orchestrator.protocol.AgentMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * O4 双轨组合矩阵（手册·风险研判）：规则轨（确定性词表）× 语义轨（上游 report.riskLevel）
 * 四象限合并——取最高级别、规则一票升级、触发归属（KEYWORD/… vs LLM_SEMANTIC）逐格钉死。
 */
@SpringBootTest
@ActiveProfiles("test")
class M9DualTrackMatrixTest {

    @Autowired RiskArchiveAgent agent;
    @Autowired ReportRepository reportRepo;
    @Autowired RiskEventRepository riskRepo;
    @Autowired UserRepository userRepo;
    @Autowired CryptoService crypto;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;

    private static final String RULE_LOW = "和室友吵了一架，越想越气。";
    private static final String RULE_MEDIUM = "已经两周了，一直很低落，觉得自己一无是处。";
    private static final String RULE_HIGH = "最近很累，不想活了";

    private long newUser() {
        UserEntity u = new UserEntity();
        u.setUsername("dt" + System.nanoTime());
        u.setNickname("t");
        u.setPasswordHash(encoder.encode("Passw0rd!2026"));
        return userRepo.save(u).getId();
    }

    /** 直接驱动收口 Agent：taskId 用 nano 时间戳造虚拟外键（队列/画像路径均容错） */
    private JsonNode archive(long uid, String diaryText, String semanticLevel) {
        long taskId = System.nanoTime();
        if (semanticLevel != null) {
            ReportEntity r = new ReportEntity();
            r.setUserId(uid);
            r.setTaskId(taskId);
            r.setType("TRACE");
            r.setTitle("t");
            r.setContentEnc(crypto.encryptUserField(uid, "{}"));
            r.setRiskLevel(semanticLevel);
            reportRepo.save(r);
        }
        ObjectNode payload = mapper.createObjectNode().put("diaryText", diaryText);
        var request = new AgentMessage(null, taskId, 4, "SUPPORT", "RISK_ARCHIVE",
                AgentMessage.MsgType.REQUEST, payload, null, null);
        var rt = new AgentRuntime(taskId, uid, "DT-" + taskId,
                new StepSpec("risk", "RISK_ARCHIVE", "archive_receipt.json"));
        return agent.run(request, rt);
    }

    private RiskEventEntity topEvent(long uid) {
        List<RiskEventEntity> events = riskRepo.findByUserIdOrderByCreatedAtDesc(uid);
        assertEquals(1, events.size(), "每档矩阵恰好一条事件");
        return events.get(0);
    }

    private String crisisState(long uid) {
        return userRepo.findById(uid).orElseThrow().getCrisisState();
    }

    @Test
    void lowXLowProducesNoEvent() {
        long uid = newUser();
        var receipt = archive(uid, RULE_LOW, "LOW");
        assertEquals("LOW", receipt.path("riskLevel").asText());
        assertTrue(receipt.path("riskEventId").isNull());
        assertTrue(receipt.path("referral").isNull());
        assertTrue(riskRepo.findByUserIdOrderByCreatedAtDesc(uid).isEmpty());
        assertEquals("NORMAL", crisisState(uid));
    }

    @Test
    void lowRuleXMediumSemanticEventBelongsToSemanticTrack() {
        long uid = newUser();
        var receipt = archive(uid, RULE_LOW, "MEDIUM");
        assertEquals("MEDIUM", receipt.path("riskLevel").asText());
        var e = topEvent(uid);
        assertEquals("LLM_SEMANTIC", e.getTriggerType());
        assertNull(e.getRuleCode(), "语义轨胜出时不得冒领规则编码");
        assertEquals("NORMAL", crisisState(uid), "MEDIUM 不触发危机生命周期");
        assertTrue(receipt.path("referral").path("show").asBoolean());
    }

    @Test
    void lowRuleXHighSemanticEntersCrisis() {
        long uid = newUser();
        var receipt = archive(uid, RULE_LOW, "HIGH");
        assertEquals("HIGH", receipt.path("riskLevel").asText());
        var e = topEvent(uid);
        assertEquals("LLM_SEMANTIC", e.getTriggerType());
        assertEquals("CRISIS", crisisState(uid));
    }

    @Test
    void highRuleXLowSemanticRuleOverridesWithOneVote() {
        long uid = newUser();
        var receipt = archive(uid, RULE_HIGH, "LOW");
        assertEquals("HIGH", receipt.path("riskLevel").asText(), "规则轨一票升级，不被供应商乐观判定抹掉");
        var e = topEvent(uid);
        assertEquals("RISK_CRISIS", e.getRuleCode());
        assertNotEquals("LLM_SEMANTIC", e.getTriggerType());
        assertEquals("CRISIS", crisisState(uid));
    }

    @Test
    void mediumRuleXHighSemanticTakesHighestAndKeepsRuleEvidence() {
        long uid = newUser();
        var receipt = archive(uid, RULE_MEDIUM, "HIGH");
        assertEquals("HIGH", receipt.path("riskLevel").asText());
        var e = topEvent(uid);
        assertEquals("HIGH", e.getLevel());
        assertEquals("CRISIS", crisisState(uid));
    }

    @Test
    void mediumRuleXLowSemanticStaysMediumForReview() {
        long uid = newUser();
        var receipt = archive(uid, RULE_MEDIUM, "LOW");
        assertEquals("MEDIUM", receipt.path("riskLevel").asText());
        var e = topEvent(uid);
        assertEquals("MEDIUM", e.getLevel());
        assertEquals("LOW_MOOD_PERSISTENT", e.getRuleCode());
        assertEquals("NORMAL", crisisState(uid));
    }

    /** 同级并列取规则轨归属（ordinal >= 语义），触发类型不飘 */
    @Test
    void tiePrefersRuleTrack() {
        long uid = newUser();
        archive(uid, RULE_MEDIUM, "MEDIUM");
        var e = topEvent(uid);
        assertEquals("MEDIUM", e.getLevel());
        assertEquals("LOW_MOOD_PERSISTENT", e.getRuleCode(), "平级时规则编码优先，归属确定");
    }
}
