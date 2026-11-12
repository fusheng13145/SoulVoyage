package com.leyon.soul;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leyon.soul.agent.support.ExerciseCatalog;
import com.leyon.soul.common.exception.BizException;
import com.leyon.soul.common.time.BusinessCalendar;
import com.leyon.soul.domain.emotion.EmotionTrajectoryEntity;
import com.leyon.soul.domain.emotion.EmotionTrajectoryRepository;
import com.leyon.soul.domain.plan.GrowthPlanEntity;
import com.leyon.soul.domain.plan.GrowthPlanRepository;
import com.leyon.soul.domain.plan.PlanItemEntity;
import com.leyon.soul.domain.plan.PlanItemRepository;
import com.leyon.soul.domain.plan.PlanService;
import com.leyon.soul.domain.user.UserEntity;
import com.leyon.soul.domain.user.UserRepository;
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

/** H-03 计划自适应：建议优先级、顺延/减量/替换、今天不做幂等、越权与危机态抑制。 */
@SpringBootTest
@ActiveProfiles("test")
class PlanAdaptationTest {

    @Autowired PlanService plans;
    @Autowired GrowthPlanRepository planRepo;
    @Autowired PlanItemRepository itemRepo;
    @Autowired EmotionTrajectoryRepository trajRepo;
    @Autowired ExerciseCatalog exercises;
    @Autowired UserRepository userRepo;
    @Autowired PasswordEncoder encoder;
    @Autowired BusinessCalendar cal;
    @Autowired ObjectMapper mapper;

    private long newUser(String crisisState) {
        UserEntity u = new UserEntity();
        u.setUsername("ad" + System.nanoTime());
        u.setNickname("t");
        u.setPasswordHash(encoder.encode("Passw0rd!2026"));
        u.setCrisisState(crisisState);
        return userRepo.save(u).getId();
    }

    /** seq1/seq2 在今天，seq3 在明天；seq1 用长练习，便于验证替换。 */
    private ObjectNode planResult() {
        ObjectNode n = mapper.createObjectNode();
        n.put("planTitle", "自适应测试计划");
        n.put("planDays", 3);
        ArrayNode items = n.putArray("planItems");
        items.addObject().put("day", 1).put("exerciseId", "ex_cbt_write").put("guidance", "写三栏表");
        items.addObject().put("day", 1).put("exerciseId", "ex_478_breath").put("guidance", "睡前呼吸");
        items.addObject().put("day", 2).put("exerciseId", "ex_action").put("guidance", "做一件小事");
        return n;
    }

    private long materialize(long uid) {
        return Long.parseLong(plans.materialize(uid, null, planResult()).replace("pl_", ""));
    }

    private void setScheduled(long planId, int seq, LocalDate date) {
        PlanItemEntity it = itemRepo.findByPlanIdAndSeq(planId, seq).orElseThrow();
        it.setScheduledDate(date);
        itemRepo.save(it);
    }

    private void traj(long uid, LocalDate date, String valence) {
        EmotionTrajectoryEntity t = new EmotionTrajectoryEntity();
        t.setUserId(uid);
        t.setRecordDate(date);
        t.setSourceType("SELF_RATING");
        t.setPrimaryEmotion("焦虑");
        t.setValence(new BigDecimal(valence));
        t.setIntensity(new BigDecimal("0.500"));
        trajRepo.save(t);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> adaptationOf(Map<String, Object> plan) {
        assertNotNull(plan);
        return (Map<String, Object>) plan.get("adaptation");
    }

    @Test
    void suggestsPostponeAndMovesOverdueItems() {
        long uid = newUser("NORMAL");
        long planId = materialize(uid);
        LocalDate today = cal.today();
        setScheduled(planId, 1, today.minusDays(2));
        setScheduled(planId, 2, today.minusDays(1));

        Map<String, Object> active = plans.active(uid);
        Map<String, Object> ad = adaptationOf(active);
        assertNotNull(ad);
        assertEquals("POSTPONE", ad.get("type"));
        assertEquals(2, ((Number) active.get("overdueCount")).intValue());

        plans.adapt(uid, planId, "POSTPONE");
        for (PlanItemEntity it : itemRepo.findByPlanIdOrderBySeqAsc(planId)) {
            assertFalse(it.getScheduledDate().isBefore(today), "逾期项应顺延到今天或未来");
        }
        assertEquals(0, ((Number) plans.active(uid).get("overdueCount")).intValue());
    }

    @Test
    void skipTodayMovesItemsAndIsIdempotent() {
        long uid = newUser("NORMAL");
        long planId = materialize(uid);
        LocalDate today = cal.today();
        setScheduled(planId, 3, today);
        GrowthPlanEntity p = planRepo.findById(planId).orElseThrow();
        p.setEndDate(today);
        p.setDays((short) 1);
        planRepo.save(p);

        Map<String, Object> after = plans.skipToday(uid, planId);
        assertTrue(planRepo.findById(planId).orElseThrow().getEndDate().isAfter(today));
        List<Map<String, Object>> items = (List<Map<String, Object>>) after.get("items");
        assertTrue(items.stream().noneMatch(i -> today.toString().equals(i.get("scheduledDate"))),
                "今天不应再有未完成项");
        Map<String, Object> active = plans.active(uid);
        assertTrue((Boolean) active.get("todaySkipped"));
        assertTrue(((List<?>) active.get("items")).isEmpty());

        List<LocalDate> before = itemRepo.findByPlanIdOrderBySeqAsc(planId).stream()
                .map(PlanItemEntity::getScheduledDate).toList();
        plans.skipToday(uid, planId);
        List<LocalDate> again = itemRepo.findByPlanIdOrderBySeqAsc(planId).stream()
                .map(PlanItemEntity::getScheduledDate).toList();
        assertEquals(before, again, "重复「今天不做」应幂等");
    }

    @Test
    void reduceKeepsAtMostOneItemPerDay() {
        long uid = newUser("NORMAL");
        long planId = materialize(uid);
        LocalDate today = cal.today();

        Map<String, Object> after = plans.adapt(uid, planId, "REDUCE");
        List<PlanItemEntity> items = itemRepo.findByPlanIdOrderBySeqAsc(planId);
        assertEquals(2, items.size());
        assertEquals(1, items.stream().filter(it -> today.equals(it.getScheduledDate())).count());
        assertEquals(2, ((Number) after.get("remainingCount")).intValue());
        assertEquals(2, ((Number) after.get("totalCount")).intValue());
    }

    @Test
    void replacesWithLighterExerciseWhenMoodLow() {
        long uid = newUser("NORMAL");
        long planId = materialize(uid);
        LocalDate today = cal.today();
        traj(uid, today.minusDays(1), "-0.500");
        traj(uid, today, "-0.500");

        Map<String, Object> ad = adaptationOf(plans.active(uid));
        assertNotNull(ad);
        assertEquals("REPLACE", ad.get("type"));

        PlanItemEntity target = itemRepo.findByPlanIdAndSeq(planId, 1).orElseThrow();
        String before = target.getExerciseCode();
        int beforeMin = exercises.require(before).durationMin();

        plans.adapt(uid, planId, "REPLACE");
        PlanItemEntity after = itemRepo.findByPlanIdAndSeq(planId, 1).orElseThrow();
        assertNotEquals(before, after.getExerciseCode());
        assertTrue(exercises.require(after.getExerciseCode()).durationMin() <= beforeMin);
    }

    @Test
    void crisisPlanHasNoSuggestion() {
        long uid = newUser("CRISIS");
        materialize(uid);
        assertNull(adaptationOf(plans.active(uid)));
    }

    @Test
    void rejectsForeignAndEndedPlans() {
        long owner = newUser("NORMAL");
        long planId = materialize(owner);
        long other = newUser("NORMAL");
        assertThrows(BizException.class, () -> plans.adapt(other, planId, "POSTPONE"));

        GrowthPlanEntity p = planRepo.findById(planId).orElseThrow();
        p.setEndDate(cal.today().minusDays(1));
        planRepo.save(p);
        assertThrows(BizException.class, () -> plans.adapt(owner, planId, "POSTPONE"));
        assertThrows(BizException.class, () -> plans.skipToday(owner, planId));
    }
}
