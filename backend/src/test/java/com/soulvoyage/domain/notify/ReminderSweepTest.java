package com.soulvoyage.domain.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.common.time.BusinessCalendar;
import com.soulvoyage.domain.checkin.CheckInService;
import com.soulvoyage.domain.plan.PlanService;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 下篇·G5 提醒扫描回归（直调 sweep，不测触发器）：
 * 打卡提醒尊重设定时间、当日 dedup 幂等；已打卡不打扰；计划提醒只在有未完成项时出。
 */
@SpringBootTest
@ActiveProfiles("test")
class ReminderSweepTest {

    @Autowired ReminderScheduler scheduler;
    @Autowired PreferencesService prefs;
    @Autowired CheckInService checkIn;
    @Autowired PlanService plans;
    @Autowired NotificationService notify;
    @Autowired BusinessCalendar cal;
    @Autowired UserRepository userRepo;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;

    private long newUser() {
        UserEntity u = new UserEntity();
        u.setUsername("rs" + System.nanoTime());
        u.setNickname("t");
        u.setPasswordHash(encoder.encode("Passw0rd!2026"));
        return userRepo.save(u).getId();
    }

    private long countKind(long uid, String kind) {
        return ((List<?>) notify.list(uid, 0, 50).get("items")).stream()
                .map(i -> (Map<?, ?>) i)
                .filter(m -> kind.equals(m.get("kind")))
                .count();
    }

    @Test
    void checkInReminderWaitsForTimeAndDedupsPerDay() {
        long uid = newUser();
        prefs.update(uid, Map.of("checkinReminderOn", true, "reminderTime", "20:00"));

        // 设定时间之前：不出声
        scheduler.sweepCheckIn(LocalDateTime.of(cal.today(), java.time.LocalTime.of(9, 0)));
        assertEquals(0, countKind(uid, "SYSTEM"));

        // 过了 20:00 且今日未打卡 → 1 条；重跑不重复（dedupKey=日期）
        var now = LocalDateTime.of(cal.today(), java.time.LocalTime.of(21, 5));
        scheduler.sweepCheckIn(now);
        scheduler.sweepCheckIn(now);
        assertEquals(1, countKind(uid, "SYSTEM"));

        // 已打卡的当天不再重复；次日未打卡仍会轻轻提醒（不永久停发，也不骚扰：≤2 条/24h 护栏内）
        checkIn.checkIn(uid, null, new CheckInService.CheckInReq("CALM", 4, 3, null));
        scheduler.sweepCheckIn(LocalDateTime.of(cal.today(), java.time.LocalTime.of(22, 5)));
        assertEquals(1, countKind(uid, "SYSTEM"));
        scheduler.sweepCheckIn(LocalDateTime.of(cal.today().plusDays(1), java.time.LocalTime.of(21, 5)));
        assertEquals(2, countKind(uid, "SYSTEM"));
    }

    @Test
    void planReminderOnlyWhenOpenItemToday() {
        long uid = newUser();
        prefs.update(uid, Map.of("planReminderOn", true));
        var input = mapper.createObjectNode();
        input.put("planTitle", "轻一点开始");
        input.put("planDays", 3);
        input.putArray("planItems").addObject()
                .put("day", 1).put("exerciseId", "ex_478_breath").put("guidance", "睡前做");
        String planNo = plans.materialize(uid, null, input);

        scheduler.sweepPlan(LocalDateTime.of(cal.today(), java.time.LocalTime.of(21, 5)));
        assertEquals(1, countKind(uid, "PLAN"));
        scheduler.sweepPlan(LocalDateTime.of(cal.today(), java.time.LocalTime.of(22, 5)));
        assertEquals(1, countKind(uid, "PLAN"));   // 计划×日期 dedup

        // 放弃计划后不再提醒
        plans.drop(uid, Long.parseLong(planNo.replace("pl_", "")));
        scheduler.sweepPlan(LocalDateTime.of(cal.today().plusDays(1), java.time.LocalTime.of(21, 5)));
        assertEquals(1, countKind(uid, "PLAN"));
    }
}
