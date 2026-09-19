package com.soulvoyage;

import com.fasterxml.jackson.databind.JsonNode;
import com.soulvoyage.agent.companion.CompanionService;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.domain.companion.CompanionTurnRepository;
import com.soulvoyage.domain.risk.RiskEventRepository;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 下篇·C0 漫聊逐轮验收（M7 上线门禁）：高危句式当轮 100% 拦截——
 * 命中危机规则即替换为陪伴话术（不经 LLM）、risk_event 同步归档、进入 S1 危机态；
 * 另覆盖「这句别分析」、收段消化、单条长度/空文护栏与已收段拒聊。
 */
@SpringBootTest
@ActiveProfiles("test")
class CompanionTurnTest {

    @Autowired CompanionService companion;
    @Autowired CompanionTurnRepository turnRepo;
    @Autowired RiskEventRepository riskEventRepo;
    @Autowired UserRepository userRepo;
    @Autowired PasswordEncoder encoder;

    private long newUser() {
        UserEntity u = new UserEntity();
        u.setUsername("cp" + System.nanoTime());
        u.setNickname("t");
        u.setPasswordHash(encoder.encode("Passw0rd!2026"));
        return userRepo.save(u).getId();
    }

    private long openSession(long uid) {
        return companion.openOrReuse(uid).sessionId();
    }

    /** 门禁条款：每句高危话术都必须当轮拦截，无一漏网 */
    @Test
    void everyCrisisPhraseIsBlockedInTurn() throws Exception {
        long uid = newUser();
        long sid = openSession(uid);
        String[] crisisLines = {
                "最近很累，不想活了",
                "我想到自杀这件事",
                "手腕上有割腕的痕迹，很疼",
                "真的活不下去撑不下去",
                "活着没意思，想把一切结束",
        };
        for (int i = 0; i < crisisLines.length; i++) {
            var r = companion.turn(uid, sid, crisisLines[i]);
            assertTrue(r.crisis(), "第 " + (i + 1) + " 句未触发危机标记: " + crisisLines[i]);
            assertTrue(r.aiText().startsWith(CompanionService.CRISIS_CARE_LINE),
                    "当轮回复必须是陪伴话术（不含 LLM 生成内容）");
            assertEquals("LOW_ENERGY", r.moodTag());
        }
        // 全部当轮同步归档：triggerType=COMPANION_TURN 的风险事件数量 = 高危句数
        assertEquals(crisisLines.length,
                riskEventRepo.findByUserIdOrderByCreatedAtDesc(uid).stream()
                        .filter(e -> "COMPANION_TURN".equals(e.getTriggerType()) && "HIGH".equals(e.getLevel()))
                        .count());
        // P0 中 P0：危机一票当轮即进入 S1 生命周期，不等异步消化
        assertEquals("CRISIS", userRepo.findById(uid).orElseThrow().getCrisisState());
        // 高危轮落库 riskHit=1
        assertEquals(crisisLines.length, turnRepo.findBySessionIdOrderByTurnNoAsc(sid).stream()
                .filter(t -> t.getRiskHit() == 1).count());
    }

    @Test
    void ordinaryTurnFlowsNormallyAndNoAnalyzeIsRespected() throws Exception {
        long uid = newUser();
        long sid = openSession(uid);
        var r = companion.turn(uid, sid, "今天室友把我的外卖拿错了，有点无语");
        assertFalse(r.crisis());
        assertFalse(r.aiText().startsWith(CompanionService.CRISIS_CARE_LINE));
        assertTrue(r.turnId() > 0);

        companion.markNoAnalyze(uid, r.turnId());
        JsonNode tr = companion.transcript(uid, sid);
        assertTrue(tr.get("turnList").get(0).get("noAnalyze").asBoolean());

        // 全轮排除分析后收段：不再提交消化任务
        String taskNo = companion.end(uid, sid);
        assertNull(taskNo);

        // 已收段拒聊
        assertThrows(BizException.class, () -> companion.turn(uid, sid, "还在吗"));
    }

    @Test
    void inputGuardsRejectBadTurns() {
        long uid = newUser();
        long sid = openSession(uid);
        assertThrows(BizException.class, () -> companion.turn(uid, sid, "   "));
        assertThrows(BizException.class, () -> companion.turn(uid, sid, "长".repeat(501)));
        // 陌生会话不可越权
        long other = newUser();
        assertThrows(BizException.class, () -> companion.turn(other, sid, "hi"));
    }
}
