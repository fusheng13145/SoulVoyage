package com.soulvoyage.domain.plan.dto;

import java.util.List;

/** 成长计划读接口的响应契约（planId 保持 pl_N 字符串口径）。 */
public class PlanDtos {

    public record PlanItemView(int seq, String exerciseId, String guidance, String scheduledDate,
                               String doneAt, String feedback, String exerciseName, int durationMin) {}

    public record PlanView(String planId, String title, int days, String status, String startDate,
                           String endDate, int daysLeft, int doneCount, int totalCount,
                           List<PlanItemView> items) {}

    public record ActivePlanResp(PlanView plan) {}

    public record PlanListResp(List<PlanView> items) {}
}
