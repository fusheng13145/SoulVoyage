package com.soulvoyage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.common.time.BusinessCalendar;
import com.soulvoyage.domain.checkin.CheckInService;
import com.soulvoyage.domain.checkin.StreakService;
import com.soulvoyage.domain.letter.GrowthLetterRepository;
import com.soulvoyage.domain.letter.GrowthLetterService;
import com.soulvoyage.domain.notify.NotificationService;
import com.soulvoyage.domain.notify.PreferencesService;
import com.soulvoyage.domain.plan.GrowthPlanEntity;
import com.soulvoyage.domain.plan.GrowthPlanRepository;
import com.soulvoyage.domain.plan.PlanService;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import com.soulvoyage.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 下篇·G1-G6 成长节奏回归：打卡/补签/streak（断签不羞辱）、计划物化→跟练回写→到期小结、
 * 危机态计划降级为 grounding、成长来信周扫（素材不足不写、同周幂等跳过）。
 * 定时任务按 CrisisLifecycle 模式直调 sweep 方法，不测触发器。
 */
@SpringBootTest
@ActiveProfiles("test")
class GrowthRhythmTest {

    @Autowired CheckInService checkIn;
    @Autowired StreakService streak;
    @Autowired PlanService plans;
    @Autowired GrowthPlanRepository planRepo;
    @Autowired GrowthLetterService letters;
    @Autowired GrowthLetterRepository letterRepo;
    @Autowired PreferencesService prefs;
    @Autowired NotificationService notify;
    @Autowired BusinessCalendar cal;
    @Autowired UserRepository userRepo;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;

    private long newUser() {
        UserEntity u = new UserEntity();
        u.setUsername("gr" + System.nanoTime());
        u.setNickname("t");
        u.setPasswordHash(encoder.encode("Passw0rd!2026"));
        return userRepo.save(u).getId();
    }

    private ObjectNode planResult() {
        ObjectNode n = mapper.createObjectNode();
        n.put("planTitle", "把睡眠找回来");
        n.put("planDays", 3);
        ArrayNode items = n.putArray("planItems");
        items.addObject().put("day", 1).put("exerciseId", "ex_478_breath").put("guidance", "睡前在床上做");
        items.addObject().put("day", 1).put("exerciseId", "ex_cbt_write").put("guidance", "写白天最堵的一件事");
        items.addObject().put("day", 2).put("exerciseId", "ex_action").put("guidance", "挑一件五分钟的小事");
        return n;
    }

    @Test
    void checkInMakeupAndStreak() {
        long uid = newUser();
        var v = checkIn.checkIn(uid, null, new CheckInService.CheckInReq("JOY", 4, 3, "见了朋友"));
        assertEquals(cal.today(), v.date());
        assertNotNull(checkIn.today(uid));
        assertEquals(1, streak.compute(uid).totalDays());

        var mk = checkIn.makeup(uid, cal.today().minusDays(3),
                new CheckInService.CheckInReq("CALM", 3, 3, null));
        assertTrue(mk.madeUp());
        assertEquals(2, checkIn.month(uid, cal.today().getYear(), cal.today().getMonthValue()).size());
        // 每月仅 1 次补签救济
        assertThrows(BizException.class, () -> checkIn.makeup(uid, cal.today().minusDays(2),
                new CheckInService.CheckInReq("CALM", 3, 3, null)));
        // 未来日拒绝
        assertThrows(BizException.class, () -> checkIn.checkIn(uid, cal.today().plusDays(1),
                new CheckInService.CheckInReq("JOY", 5, 5, null)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void planMaterializeProgressAndSummary() {
        long uid = newUser();
        String planNo = plans.materialize(uid, null, planResult());
        long planId = Long.parseLong(planNo.replace("pl_", ""));

        Map<String, Object> active = plans.active(uid);
        assertNotNull(active);
        List<Map<String, Object>> todayItems = (List<Map<String, Object>>) active.get("items");
        assertEquals(2, todayItems.size());          // day1 两项，每日上限内
        assertEquals("把睡眠找回来", active.get("title"));

        plans.markItemDone(uid, planId, 1, "做完呼吸舒服多了");
        Map<String, Object> active2 = plans.active(uid);
        assertEquals(1, ((List<Map<String, Object>>) active2.get("items")).stream()
                .filter(i -> i.get("doneAt") != null).count());
        assertEquals(1, active2.get("doneCount"));

        // 到期小结：补齐全部项 + 把 endDate 拨到昨天再直调
        plans.markItemDone(uid, planId, 2, null);
        plans.markItemDone(uid, planId, 3, null);
        GrowthPlanEntity p = planRepo.findById(planId).orElseThrow();
        p.setEndDate(cal.today().minusDays(1));
        planRepo.save(p);
        assertTrue(plans.summarizeDue() >= 1);
        assertEquals("DONE", planRepo.findById(planId).orElseThrow().getStatus());
        var kinds = ((List<Map<String, Object>>) notify.list(uid, 0, 10).get("items"))
                .stream().map(m -> String.valueOf(m.get("kind"))).toList();
        assertTrue(kinds.contains("PLAN_SUMMARY"));
        // 小结幂等：summary_done 后再扫不重复出报告
        assertEquals(0, plans.summarizeDue());
    }

    @Test
    void crisisStateDowngradesPlanToGrounding() {
        long uid = newUser();
        UserEntity u = userRepo.findById(uid).orElseThrow();
        u.setCrisisState("CRISIS");
        userRepo.save(u);
        plans.materialize(uid, null, planResult());
        var active = plans.active(uid);
        assertNotNull(active);                       // 计划永不断供
        assertEquals("照顾此刻的小计划", active.get("title"));
        List<?> items = (List<?>) active.get("items");
        assertEquals(1, items.size());
        Map<?, ?> first = (Map<?, ?>) items.get(0);
        assertEquals("ex_54321", first.get("exerciseId"));   // 只留着陆，不压任务
    }

    @Test
    void letterSweepWritesOnceAndSkipsCompletedWeek() throws Exception {
        long uid = newUser();
        // 素材不足：sweep 不提交、不生成
        assertEquals(0, letters.sweep(LocalDate.now()));

        prefs.update(uid, Map.of("letterOn", true));
        LocalDate weekStart = LocalDate.now().minusWeeks(1).with(WeekFields.ISO.dayOfWeek(), 1);
        for (int i = 0; i < 3; i++) {   // 上周 3 个 SELF_RATING 数据点 → 过 MIN_DATA_POINTS 门槛
            checkIn.checkIn(uid, weekStart.plusDays(i),
                    new CheckInService.CheckInReq("JOY", 4, 3, i == 1 ? "今天散步看到了晚霞" : null));
        }
        assertEquals(1, letters.sweep(LocalDate.now()));

        for (int i = 0; i < 150 && letterRepo.findByUserIdOrderByStatWeekDesc(uid).isEmpty(); i++) {
            Thread.sleep(100);
        }
        var list = letters.list(uid);
        assertEquals(1, list.size());
        String letter = String.valueOf(list.get(0).get("letter"));
        assertTrue(letter.contains("3 次记录"));           // 数字只用统计值
        assertTrue(letter.contains("今天散步看到了晚霞"));   // 「」引用只能来自自己的原话
        // 同周重跑：已有信直接跳过
        assertEquals(0, letters.sweep(LocalDate.now()));
    }
}
