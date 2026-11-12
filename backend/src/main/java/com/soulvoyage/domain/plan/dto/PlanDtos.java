package com.soulvoyage.domain.plan.dto;

import java.util.List;

/** 成长计划读接口的响应契约（planId 保持 pl_N 字符串口径）。 */
public class PlanDtos {

    public record PlanItemView(int seq, String exerciseId, String guidance, String scheduledDate,
                               String doneAt, String feedback, String exerciseName, int durationMin) {}

    /** H-03 自适应建议：type=POSTPONE/REDUCE/REPLACE；无建议时整个对象为 null。 */
    public record PlanAdaptationView(String type, String title, String reason, String actionLabel) {}

    public record PlanView(String planId, String title, int days, String status, String startDate,
                           String endDate, int daysLeft, int doneCount, int totalCount,
                           List<PlanItemView> items, PlanAdaptationView adaptation,
                           int completionRate, int overdueCount, int remainingCount,
                           String nextScheduledDate, boolean todaySkipped) {}

    public record ActivePlanResp(PlanView plan) {}

    public record PlanListResp(List<PlanView> items) {}

    /** H-03 调整动作请求。 */
    public record AdaptReq(String action) {}
}
