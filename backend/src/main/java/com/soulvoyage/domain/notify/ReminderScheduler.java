package com.soulvoyage.domain.notify;

import com.soulvoyage.agent.support.ExerciseCatalog;
import com.soulvoyage.common.time.BusinessCalendar;
import com.soulvoyage.domain.checkin.MoodCheckInRepository;
import com.soulvoyage.domain.plan.GrowthPlanEntity;
import com.soulvoyage.domain.plan.GrowthPlanRepository;
import com.soulvoyage.domain.plan.PlanItemEntity;
import com.soulvoyage.domain.plan.PlanItemRepository;
import com.soulvoyage.domain.plan.PlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

/**
 * 下篇·G5 提醒扫描：站内通知唯一主动触达面（无短信/邮件，隐私最小化）。
 * 每小时 :05 轻扫（提醒默认关，开启者才进扫描集）；dedupKey=日期粒度 → 重跑幂等；
 * 频率护栏（≤2 条/日）在 NotificationService 内统一兜底。测试直调 sweep 方法（不测触发器）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderScheduler {

    private final UserPreferencesRepository prefsRepo;
    private final MoodCheckInRepository checkInRepo;
    private final GrowthPlanRepository planRepo;
    private final PlanItemRepository itemRepo;
    private final ExerciseCatalog exercises;
    private final NotificationService notify;
    private final PlanService plans;
    private final BusinessCalendar cal;

    @Scheduled(cron = "0 5 * * * *", zone = "${soulvoyage.business-zone:Asia/Shanghai}")
    public void hourlySweep() {
        LocalDateTime now = LocalDateTime.now(cal.zone());
        try {
            sweepCheckIn(now);
            sweepPlan(now);
        } catch (Exception e) {
            log.error("reminder sweep failed", e);
        }
    }

    /** G4 到期小结：每天 04:30（业务时区）扫过期未小结的计划 */
    @Scheduled(cron = "0 30 4 * * *", zone = "${soulvoyage.business-zone:Asia/Shanghai}")
    public void dailyPlanSummary() {
        try {
            int n = plans.summarizeDue();
            if (n > 0) log.info("plan summaries generated: {}", n);
        } catch (Exception e) {
            log.error("plan summary sweep failed", e);
        }
    }

    /** 打卡提醒：过用户设定时间且今日未打卡 → 1 条（当日 dedup） */
    void sweepCheckIn(LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        for (UserPreferencesEntity p : prefsRepo.findByCheckinReminderOnOrderByUserId((short) 1)) {
            try {
                if (LocalTime.parse(p.getReminderTime()).isAfter(now.toLocalTime())) continue;
                if (checkInRepo.findByUserIdAndCheckDate(p.getUserId(), today).isPresent()) continue;
                notify.push(p.getUserId(), "SYSTEM", "checkin:" + today, "checkin_reminder",
                        Map.of(), "/today");
            } catch (Exception e) {
                log.warn("checkin reminder failed user={}: {}", p.getUserId(), e.toString());
            }
        }
    }

    /** 计划跟练提醒：进行中期 + 今日有未完成项 → 1 条（计划×日期 dedup） */
    void sweepPlan(LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        for (UserPreferencesEntity p : prefsRepo.findByPlanReminderOnOrderByUserId((short) 1)) {
            try {
                var plan = planRepo.findByUserIdAndStatusOrderByEndDateAsc(p.getUserId(), "ACTION")
                        .stream()
                        .filter(x -> !today.isBefore(x.getStartDate()) && !today.isAfter(x.getEndDate()))
                        .findFirst().orElse(null);
                if (plan == null) continue;
                var open = itemRepo.findByPlanIdAndScheduledDateOrderBySeqAsc(plan.getId(), today)
                        .stream().filter(x -> x.getDoneAt() == null).findFirst().orElse(null);
                if (open == null) continue;
                notify.push(p.getUserId(), "PLAN", "planReminder:" + plan.getId() + ":" + today,
                        "plan_reminder", Map.of("guidance", guidanceOf(open)), "/today");
            } catch (Exception e) {
                log.warn("plan reminder failed user={}: {}", p.getUserId(), e.toString());
            }
        }
    }

    private String guidanceOf(PlanItemEntity item) {
        String name = exercises.exists(item.getExerciseCode())
                ? exercises.require(item.getExerciseCode()).name() : "今日练习";
        return "「" + name + "」：" + item.getGuidance() + "。";
    }
}
