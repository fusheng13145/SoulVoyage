package com.soulvoyage.domain.plan;

import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.domain.plan.dto.PlanDtos.ActivePlanResp;
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

    @SuppressWarnings("unchecked")
    private static PlanView view(Map<String, Object> m) {
        List<PlanItemView> items = ((List<Map<String, Object>>) m.get("items")).stream()
                .map(PlanController::item)
                .toList();
        return new PlanView(
                (String) m.get("planId"), (String) m.get("title"),
                ((Number) m.get("days")).intValue(), (String) m.get("status"),
                (String) m.get("startDate"), (String) m.get("endDate"),
                ((Number) m.get("daysLeft")).intValue(),
                ((Number) m.get("doneCount")).intValue(), ((Number) m.get("totalCount")).intValue(),
                items);
    }

    private static PlanItemView item(Map<String, Object> m) {
        return new PlanItemView(
                ((Number) m.get("seq")).intValue(), (String) m.get("exerciseId"),
                (String) m.get("guidance"), (String) m.get("scheduledDate"),
                (String) m.get("doneAt"), (String) m.get("feedback"),
                (String) m.get("exerciseName"), ((Number) m.get("durationMin")).intValue());
    }
}
