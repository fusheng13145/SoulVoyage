package com.soulvoyage;

import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.domain.emotion.EmotionController;
import com.soulvoyage.domain.emotion.EmotionTrajectoryEntity;
import com.soulvoyage.domain.emotion.EmotionTrajectoryRepository;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** M6 轻量打卡回归：SELF_RATING 直写轨迹 + 参数校验 + 轨迹可查 */
@SpringBootTest
@ActiveProfiles("test")
class SelfRatingTest {

    @Autowired EmotionController controller;
    @Autowired EmotionTrajectoryRepository trajRepo;
    @Autowired UserRepository userRepo;
    @Autowired PasswordEncoder encoder;

    private long newUser() {
        UserEntity u = new UserEntity();
        u.setUsername("sr" + System.nanoTime());
        u.setNickname("t");
        u.setPasswordHash(encoder.encode("Passw0rd!2026"));
        return userRepo.save(u).getId();
    }

    private EmotionController.SelfRatingReq req(String emotion, String valence, String intensity,
                                                String note, LocalDate date) {
        return new EmotionController.SelfRatingReq(emotion, new BigDecimal(valence),
                new BigDecimal(intensity), note, date);
    }

    @Test
    void writesSelfRatingPointAndSurfacesInTrajectory() {
        long uid = newUser();
        var p = new AuthPrincipal(uid, "USER");
        LocalDate today = LocalDate.now();

        var resp = controller.selfRating(p, req("平静", "0.4", "0.68", "午后晒了太阳", today));
        assertEquals(0, resp.getCode());
        List<EmotionTrajectoryEntity> rows = trajRepo
                .findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(uid, today, today);
        assertEquals(1, rows.size());
        EmotionTrajectoryEntity t = rows.get(0);
        assertEquals("SELF_RATING", t.getSourceType());
        assertEquals("平静", t.getPrimaryEmotion());
        assertEquals(0, t.getValence().compareTo(new BigDecimal("0.400")));
        assertEquals(0, t.getIntensity().compareTo(new BigDecimal("0.68")));
        assertTrue(t.getEventTags().contains("午后晒了太阳"), "备注应进 eventTags");

        Map<String, Object> traj = controller.trajectory(p, today, today).getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) traj.get("points");
        assertEquals(1, points.size());
        assertEquals("SELF_RATING", points.get(0).get("sourceType"));
    }

    @Test
    void rejectsInvalidInput() {
        long uid = newUser();
        var p = new AuthPrincipal(uid, "USER");
        LocalDate today = LocalDate.now();

        assertThrows(BizException.class, () -> controller.selfRating(p, req("", "0", "0.5", null, null)));
        assertThrows(BizException.class, () -> controller.selfRating(p, req("a".repeat(33), "0", "0.5", null, null)));
        assertThrows(BizException.class, () -> controller.selfRating(p, req("开心", "1.5", "0.5", null, null)));
        assertThrows(BizException.class, () -> controller.selfRating(p, req("开心", "0", "1.2", null, null)));
        assertThrows(BizException.class, () -> controller.selfRating(p, req("开心", "0", "0.5", null, today.plusDays(1))));
        assertThrows(BizException.class, () -> controller.selfRating(p,
                req("开心", "0", "0.5", "字".repeat(201), null)));
        assertTrue(trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(uid, today.minusDays(1), today.plusDays(1)).isEmpty(),
                "校验失败不应落库");
    }

    @Test
    void defaultsToBusinessToday() {
        long uid = newUser();
        var p = new AuthPrincipal(uid, "USER");
        var resp = controller.selfRating(p, req("期待", "-0.2", "0.36", null, null));
        LocalDate d = LocalDate.parse(String.valueOf(resp.getData().get("date")));
        List<EmotionTrajectoryEntity> rows = trajRepo
                .findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(uid, d, d);
        assertEquals(1, rows.size());
        assertNull(rows.get(0).getEventTags(), "无备注则 eventTags 留空");
    }
}
