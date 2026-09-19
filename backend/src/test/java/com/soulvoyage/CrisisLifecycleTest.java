package com.soulvoyage;

import com.soulvoyage.domain.crisis.CrisisLifecycleRepository;
import com.soulvoyage.domain.crisis.CrisisService;
import com.soulvoyage.domain.profile.EmotionProfileEntity;
import com.soulvoyage.domain.profile.EmotionProfileRepository;
import com.soulvoyage.domain.risk.RiskEventEntity;
import com.soulvoyage.domain.risk.RiskEventRepository;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 下篇·S1 危机生命周期回归（M5 验收"危机自动降级回归"）：
 * NORMAL→CRISIS→(届满)COOLING→(画像达标)NORMAL 自动降级；两次进入强制 REVIEW→adminClose；
 * 冷却期内新风险事件阻断降级。强干预窗口以 cooldown=1s 加速。
 */
@SpringBootTest(properties = {"soulvoyage.crisis.cooldown=1s"})
@ActiveProfiles("test")
class CrisisLifecycleTest {

    @Autowired CrisisService crisis;
    @Autowired CrisisLifecycleRepository lifecycleRepo;
    @Autowired EmotionProfileRepository profileRepo;
    @Autowired RiskEventRepository riskEventRepo;
    @Autowired UserRepository userRepo;
    @Autowired PasswordEncoder encoder;

    private long newUser() {
        UserEntity u = new UserEntity();
        u.setUsername("cx" + System.nanoTime());
        u.setNickname("t");
        u.setPasswordHash(encoder.encode("Passw0rd!2026"));
        return userRepo.save(u).getId();
    }

    private String state(long uid) {
        return userRepo.findById(uid).orElseThrow().getCrisisState();
    }

    private void positiveWeeks(long uid, int n) {
        for (String w : CrisisService.lastCompleteWeeks(LocalDate.now(), n)) {
            EmotionProfileEntity p = new EmotionProfileEntity();
            p.setUserId(uid);
            p.setStatWeek(w);
            p.setAvgValence(new BigDecimal("0.200"));
            profileRepo.save(p);
        }
    }

    @Test
    void crisisExpiresToCoolingThenAutoRecoversToNormal() throws Exception {
        long uid = newUser();
        crisis.onHighRisk(uid, null);
        assertEquals("CRISIS", state(uid));
        crisis.onHighRisk(uid, null);   // 窗口内再 HIGH = HIGH_REFRESH，只续时不另计次
        Thread.sleep(1100);
        crisis.sweep();
        assertEquals("COOLING", state(uid));

        crisis.sweep();                  // 画像缺失，不满足降级条件
        assertEquals("COOLING", state(uid));

        positiveWeeks(uid, 2);
        crisis.sweep();
        assertEquals("NORMAL", state(uid));
        UserEntity u = userRepo.findById(uid).orElseThrow();
        assertNull(u.getCrisisStartedAt());
        assertNull(u.getCrisisEndsAt());

        List<String> reasons = lifecycleRepo.findByUserIdOrderByCreatedAtDescIdDesc(uid)
                .stream().map(c -> c.getReason()).toList();
        assertTrue(reasons.contains("HIGH_REFRESH"));
        assertTrue(reasons.contains("COOLDOWN_EXPIRED"));
        assertTrue(reasons.contains("AUTO_RECOVERED"));
        // 恢复后计数清零：再次进入仍按第 1 次算（→COOLING 而非 REVIEW）
        crisis.onHighRisk(uid, null);
        assertEquals(1, lifecycleRepo.countEntriesSinceLastNormal(uid, java.time.Instant.EPOCH));
    }

    @Test
    void reEntryDuringCoolingForcesReviewThenAdminClose() throws Exception {
        long uid = newUser();
        crisis.onHighRisk(uid, null);
        Thread.sleep(1100);
        crisis.sweep();
        assertEquals("COOLING", state(uid));

        crisis.onHighRisk(uid, null);   // 冷却期二次进入 = RE_ENTRY，重新计时
        assertEquals("CRISIS", state(uid));
        Thread.sleep(1100);
        crisis.sweep();
        assertEquals("REVIEW", state(uid));   // 累计 2 次进入 → 强制人工复盘

        positiveWeeks(uid, 2);          // REVIEW 只能由管理员结案，画像达标不自动降级
        crisis.sweep();
        assertEquals("REVIEW", state(uid));

        crisis.adminClose(uid, 9L, null);
        assertEquals("NORMAL", state(uid));
        assertTrue(lifecycleRepo.findByUserIdOrderByCreatedAtDescIdDesc(uid).stream()
                .anyMatch(c -> "TWO_ENTRIES_FORCED_REVIEW".equals(c.getReason())));
    }

    @Test
    void newRiskEventDuringCoolingBlocksRecovery() throws Exception {
        long uid = newUser();
        crisis.onHighRisk(uid, null);
        Thread.sleep(1100);
        crisis.sweep();
        assertEquals("COOLING", state(uid));

        positiveWeeks(uid, 2);
        // 冷却期内新风险事件（水位 = 进入 COOLING 的时刻）→ 阻断自动降级
        RiskEventEntity r = new RiskEventEntity();
        r.setUserId(uid);
        r.setLevel("MEDIUM");
        r.setTriggerType("KEYWORD_RULE");
        r.setActionTaken("REFERRAL_UPGRADE");
        riskEventRepo.save(r);
        crisis.sweep();
        assertEquals("COOLING", state(uid));
    }
}
