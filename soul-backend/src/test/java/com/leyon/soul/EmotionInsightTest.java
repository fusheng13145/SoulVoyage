package com.leyon.soul;

import com.leyon.soul.auth.AuthPrincipal;
import com.leyon.soul.common.time.BusinessCalendar;
import com.leyon.soul.domain.emotion.EmotionController;
import com.leyon.soul.domain.emotion.EmotionTrajectoryEntity;
import com.leyon.soul.domain.emotion.EmotionTrajectoryRepository;
import com.leyon.soul.domain.emotion.dto.EmotionDtos.InsightResp;
import com.leyon.soul.domain.profile.EmotionProfileEntity;
import com.leyon.soul.domain.profile.EmotionProfileRepository;
import com.leyon.soul.domain.user.UserEntity;
import com.leyon.soul.domain.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** H-02 情绪洞察：确定性规则、波动分档、解释来源、危机态与文案边界。 */
@SpringBootTest
@ActiveProfiles("test")
class EmotionInsightTest {

    @Autowired EmotionController controller;
    @Autowired EmotionTrajectoryRepository trajRepo;
    @Autowired EmotionProfileRepository profileRepo;
    @Autowired UserRepository userRepo;
    @Autowired PasswordEncoder encoder;
    @Autowired BusinessCalendar cal;

    private long newUser(String crisisState) {
        UserEntity u = new UserEntity();
        u.setUsername("ins" + System.nanoTime());
        u.setNickname("t");
        u.setPasswordHash(encoder.encode("SvFixture!2026"));
        u.setCrisisState(crisisState);
        return userRepo.save(u).getId();
    }

    private void point(long uid, LocalDate date, String emotion, String valence) {
        EmotionTrajectoryEntity t = new EmotionTrajectoryEntity();
        t.setUserId(uid);
        t.setRecordDate(date);
        t.setSourceType("SELF_RATING");
        t.setPrimaryEmotion(emotion);
        t.setValence(new BigDecimal(valence));
        t.setIntensity(new BigDecimal("0.50"));
        trajRepo.save(t);
    }

    private void weekProfile(long uid, LocalDate date, String avgValence,
                             String stressorJson, String distortionJson) {
        EmotionProfileEntity p = new EmotionProfileEntity();
        p.setUserId(uid);
        p.setStatWeek(statWeek(date));
        p.setAvgValence(avgValence == null ? null : new BigDecimal(avgValence));
        p.setStressorTopJson(stressorJson);
        p.setDistortionTopJson(distortionJson);
        profileRepo.save(p);
    }

    private String statWeek(LocalDate d) {
        WeekFields wf = WeekFields.ISO;
        return d.get(wf.weekBasedYear()) + "-W" + String.format("%02d", d.get(wf.weekOfWeekBasedYear()));
    }

    private InsightResp insight(long uid, int weeks) {
        return controller.insights(new AuthPrincipal(uid, "USER"), weeks).getData();
    }

    private void assertNoDiagnosis(InsightResp r) {
        StringBuilder sb = new StringBuilder();
        r.weekly().forEach(w -> {
            sb.append(w.headline()).append('\n');
            w.lines().forEach(l -> sb.append(l).append('\n'));
        });
        sb.append(r.volatility().label()).append('\n').append(r.volatility().hint()).append('\n');
        r.stressors().forEach(s -> sb.append(s.what()).append('\n').append(s.tip()).append('\n'));
        r.distortions().forEach(d -> sb.append(d.definition()).append('\n')
                .append(d.typicalSignature()).append('\n').append(d.question()).append('\n'));
        r.actions().forEach(a -> sb.append(a.title()).append('\n').append(a.detail()).append('\n'));
        String text = sb.toString();
        for (String bad : List.of("抑郁症", "焦虑症", "PTSD", "诊断")) {
            assertFalse(text.contains(bad), "洞察文案不应包含诊断词：" + bad);
        }
    }

    @Test
    void buildsDigestExplanationAndActions() {
        long uid = newUser("NORMAL");
        LocalDate today = cal.today();
        for (int i = 6; i >= 0; i--) point(uid, today.minusDays(i), "平静", "0.200");
        weekProfile(uid, today, "0.200",
                "[{\"name\":\"学业压力\",\"count\":4}]",
                "[{\"name\":\"灾难化思维\",\"count\":3}]");

        InsightResp r = insight(uid, 4);

        assertEquals(4, r.weeks());
        assertEquals(statWeek(today), r.weekly().get(0).statWeek());
        assertEquals("偏明亮的一周", r.weekly().get(0).headline());
        assertTrue(r.weekly().get(0).lines().stream().anyMatch(l -> l.contains("学业压力")));
        assertTrue(r.weekly().get(0).lines().stream().anyMatch(l -> l.contains("灾难化思维")));

        assertEquals("STEADY", r.volatility().level());
        assertEquals(7, r.volatility().sampleDays());
        assertNotNull(r.volatility().stdDev());

        assertEquals(1, r.stressors().size());
        assertEquals("学业压力", r.stressors().get(0).name());
        assertFalse(r.stressors().get(0).what().isBlank(), "压力源释义应来自内容字典");
        assertFalse(r.stressors().get(0).tip().isBlank());

        assertEquals(1, r.distortions().size());
        assertFalse(r.distortions().get(0).definition().isBlank(), "误区解释应来自知识库");
        assertFalse(r.distortions().get(0).question().isBlank());

        assertTrue(r.actions().size() >= 2);
        assertEquals("CBT_WRITE", r.actions().get(0).code());
        assertTrue(r.actions().stream().anyMatch(a -> "STRESSOR_STEP".equals(a.code())));
        assertNoDiagnosis(r);
    }

    @Test
    void flagsVolatileAndInsufficientSamples() {
        long volatileUser = newUser("NORMAL");
        LocalDate today = cal.today();
        for (int i = 7; i >= 0; i--) {
            point(volatileUser, today.minusDays(i), "起伏",
                    i % 2 == 0 ? "0.900" : "-0.900");
        }
        InsightResp volatileResp = insight(volatileUser, 4);
        assertEquals("VOLATILE", volatileResp.volatility().level());
        assertNotNull(volatileResp.volatility().maxDailyDelta());
        assertTrue(volatileResp.volatility().maxDailyDelta().compareTo(new BigDecimal("1.000")) > 0);
        assertTrue(volatileResp.actions().stream().anyMatch(a -> "BREATH_ANCHOR".equals(a.code())));
        assertNoDiagnosis(volatileResp);

        long sparseUser = newUser("NORMAL");
        point(sparseUser, today, "平静", "0.100");
        point(sparseUser, today.minusDays(1), "平静", "0.200");
        InsightResp sparse = insight(sparseUser, 4);
        assertEquals("INSUFFICIENT", sparse.volatility().level());
        assertEquals(2, sparse.volatility().sampleDays());
        assertNull(sparse.volatility().stdDev());
    }

    @Test
    void synthesizesWeeklyDigestFromTrajectoryWithoutProfile() {
        long uid = newUser("NORMAL");
        LocalDate today = cal.today();
        point(uid, today, "平静", "0.300");
        point(uid, today, "期待", "0.300");

        InsightResp r = insight(uid, 4);
        assertEquals(1, r.weekly().size());
        assertEquals("偏明亮的一周", r.weekly().get(0).headline());
        assertTrue(r.weekly().get(0).lines().stream().anyMatch(l -> l.contains("2 条情绪记录")));
        assertTrue(r.weekly().get(0).lines().stream().anyMatch(l -> l.contains("还没有溯源报告")));
        assertTrue(r.stressors().isEmpty());
        assertTrue(r.distortions().isEmpty());
        assertNoDiagnosis(r);
    }

    @Test
    void crisisUserOnlyGetsCareAction() {
        long uid = newUser("CRISIS");
        LocalDate today = cal.today();
        point(uid, today, "焦虑", "-0.600");
        weekProfile(uid, today, "-0.600",
                "[{\"name\":\"学业压力\",\"count\":2}]",
                "[{\"name\":\"灾难化思维\",\"count\":1}]");

        InsightResp r = insight(uid, 4);
        assertTrue(r.crisisMode());
        assertEquals(1, r.actions().size());
        assertEquals("CARE_RESOURCES", r.actions().get(0).code());
        assertEquals("/crisis", r.actions().get(0).route());
        assertNoDiagnosis(r);
    }

    @Test
    void clampsWeeksAndHandlesEmptyData() {
        long uid = newUser("NORMAL");
        InsightResp r = insight(uid, 99);
        assertEquals(12, r.weeks());
        assertTrue(r.weekly().isEmpty());
        assertEquals("INSUFFICIENT", r.volatility().level());
        assertFalse(r.actions().isEmpty());
        assertEquals(1, insight(uid, 0).weeks());
        assertNoDiagnosis(r);
    }
}
