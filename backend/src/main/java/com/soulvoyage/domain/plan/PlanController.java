package com.soulvoyage.domain.plan;

import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/** 下篇·G4 计划端点：今日计划卡 / 计划列表 / 主动放弃（跟练完成回写见 C4 /exercise-records） */
@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
public class PlanController {

    private final PlanService plans;

    @GetMapping("/active")
    public ApiResponse<Map<String, Object>> active(@AuthenticationPrincipal AuthPrincipal p) {
        Map<String, Object> out = new HashMap<>();
        out.put("plan", plans.active(p.userId()));
        return ApiResponse.ok(out);
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(Map.of("items", plans.list(p.userId())));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@AuthenticationPrincipal AuthPrincipal p,
                                                   @PathVariable long id) {
        return ApiResponse.ok(plans.detail(p.userId(), id));
    }

    @PostMapping("/{id}/drop")
    public ApiResponse<Void> drop(@AuthenticationPrincipal AuthPrincipal p, @PathVariable long id) {
        plans.drop(p.userId(), id);
        return ApiResponse.ok(null);
    }
}
