package com.leyon.soul.admin;

import com.leyon.soul.admin.dto.AdminDtos.GuardrailsResp;
import com.leyon.soul.admin.dto.AdminDtos.MetricsOverviewResp;
import com.leyon.soul.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * O3/H-05 观测与成本看板：只做路由，聚合全部下沉 AdminMetricsService（Controller 零 Repository 依赖）。
 */
@RestController
@RequestMapping("/api/v1/admin/metrics")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') and @perms.has('admin:task')")
public class AdminMetricsController {

    private final AdminMetricsService metrics;

    @GetMapping("/overview")
    public ApiResponse<MetricsOverviewResp> overview(@RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(metrics.overview(days));
    }

    /** H-05 护栏：失败告警 / 成本预算 / 质量抽检 / 慢请求 TopN（只读）。 */
    @GetMapping("/guardrails")
    public ApiResponse<GuardrailsResp> guardrails(@RequestParam(defaultValue = "10") int top) {
        return ApiResponse.ok(metrics.guardrails(top));
    }
}
