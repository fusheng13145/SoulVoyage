package com.soulvoyage.domain.plan;

import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.domain.plan.dto.PlanDtos.ActivePlanResp;
import com.soulvoyage.domain.plan.dto.PlanDtos.AdaptReq;
import com.soulvoyage.domain.plan.dto.PlanDtos.PlanAdaptationView;
import com.soulvoyage.domain.plan.dto.PlanDtos.PlanItemView;
import com.soulvoyage.domain.plan.dto.PlanDtos.PlanListResp;
import com.soulvoyage.domain.plan.dto.PlanDtos.PlanView;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 下篇·G4 计划端点：今日计划卡 / 计划列表 / 主动放弃（跟练完成回写见 C4 /exercise-records） */
@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
public class PlanController {

    private final PlanService plans;

    @GetMapping("/active")
    public ApiResponse<ActivePlanResp> active(@AuthenticationPrincipal AuthPrincipal p) {
        Map<String, Object> plan = plans.active(p.userId());
        return ApiResponse.ok(new ActivePlanResp(plan == null ? null : view(plan)));
    }

    @GetMapping
    public ApiResponse<PlanListResp> list(@AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(new PlanListResp(plans.list(p.userId()).stream()
                .map(PlanController::view).toList()));
    }

    @GetMapping("/{id}")
    public ApiResponse<PlanView> detail(@AuthenticationPrincipal AuthPrincipal p,
                                        @PathVariable long id) {
        return ApiResponse.ok(view(plans.detail(p.userId(), id)));
    }

    @PostMapping("/{id}/drop")
    public ApiResponse<Void> drop(@AuthenticationPrincipal AuthPrincipal p, @PathVariable long id) {
        plans.drop(p.userId(), id);
        return ApiResponse.ok(null);
    }

    /** H-03：应用一次计划自适应（POSTPONE / REDUCE / REPLACE）。 */
    @PostMapping("/{id}/adapt")
    public ApiResponse<PlanView> adapt(@AuthenticationPrincipal AuthPrincipal p,
                                       @PathVariable long id,
                                       @RequestBody AdaptReq req) {
        return ApiResponse.ok(view(plans.adapt(p.userId(), id, req == null ? null : req.action())));
    }

    /** H-03「今天不做」：今日未完成项顺延，保留计划。 */
    @PostMapping("/{id}/skip-today")
    public ApiResponse<PlanView> skipToday(@AuthenticationPrincipal AuthPrincipal p,
                                           @PathVariable long id) {
        return ApiResponse.ok(view(plans.skipToday(p.userId(), id)));
    }

    @SuppressWarnings("unchecked")
    private static PlanView view(Map<String, Object> m) {
        List<PlanItemView> items = ((List<Map<String, Object>>) m.get("items")).stream()
                .map(PlanController::item)
                .toList();
        Map<String, Object> ad = (Map<String, Object>) m.get("adaptation");
        PlanAdaptationView adaptation = ad == null ? null : new PlanAdaptationView(
                (String) ad.get("type"), (String) ad.get("title"),
                (String) ad.get("reason"), (String) ad.get("actionLabel"));
        return new PlanView(
                (String) m.get("planId"), (String) m.get("title"),
                ((Number) m.get("days")).intValue(), (String) m.get("status"),
                (String) m.get("startDate"), (String) m.get("endDate"),
                ((Number) m.get("daysLeft")).intValue(),
                ((Number) m.get("doneCount")).intValue(), ((Number) m.get("totalCount")).intValue(),
                items, adaptation,
                ((Number) m.get("completionRate")).intValue(),
                ((Number) m.get("overdueCount")).intValue(),
                ((Number) m.get("remainingCount")).intValue(),
                (String) m.get("nextScheduledDate"),
                (Boolean) m.get("todaySkipped"));
    }

    private static PlanItemView item(Map<String, Object> m) {
        return new PlanItemView(
                ((Number) m.get("seq")).intValue(), (String) m.get("exerciseId"),
                (String) m.get("guidance"), (String) m.get("scheduledDate"),
                (String) m.get("doneAt"), (String) m.get("feedback"),
                (String) m.get("exerciseName"), ((Number) m.get("durationMin")).intValue());
    }
}
