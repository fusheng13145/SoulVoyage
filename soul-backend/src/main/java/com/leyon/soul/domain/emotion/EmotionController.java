package com.leyon.soul.domain.emotion;

import com.leyon.soul.auth.AuthPrincipal;
import com.leyon.soul.common.api.ApiResponse;
import com.leyon.soul.domain.emotion.dto.EmotionDtos.InsightResp;
import com.leyon.soul.domain.emotion.dto.EmotionDtos.ProfileResp;
import com.leyon.soul.domain.emotion.dto.EmotionDtos.SelfRatingReq;
import com.leyon.soul.domain.emotion.dto.EmotionDtos.SelfRatingResp;
import com.leyon.soul.domain.emotion.dto.EmotionDtos.TrajectoryResp;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * UC3 情绪接口（手册 §6.3）：只做路由与登录态注入，查询/打卡下沉到 EmotionService。
 * 均为登录态 + 隐式属主（查询强制以 JWT userId 为键，天然防 IDOR）。
 */
@RestController
@RequestMapping("/api/v1/emotions")
@RequiredArgsConstructor
public class EmotionController {

    private final EmotionService emotionService;
    private final EmotionInsightService insightService;

    /** O2：range 14/30/90（默认 30）+ granularity=day|week；显式 from/to 亦钳制跨度 ≤90 天。 */
    @GetMapping("/trajectory")
    public ApiResponse<TrajectoryResp> trajectory(
            @AuthenticationPrincipal AuthPrincipal p,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "30") int range,
            @RequestParam(defaultValue = "day") String granularity) {
        return ApiResponse.ok(emotionService.trajectory(p.userId(), from, to, range, granularity));
    }

    /** M6 轻量打卡：今日页情绪盘直写轨迹点。 */
    @PostMapping("/self-rating")
    public ApiResponse<SelfRatingResp> selfRating(
            @AuthenticationPrincipal AuthPrincipal p,
            @RequestBody SelfRatingReq req) {
        return ApiResponse.ok(emotionService.selfRating(p.userId(), req));
    }

    /** H-02：确定性洞察（周摘要/波动/解释/下一步），只读。 */
    @GetMapping("/insights")
    public ApiResponse<InsightResp> insights(
            @AuthenticationPrincipal AuthPrincipal p,
            @RequestParam(defaultValue = "4") int weeks) {
        return ApiResponse.ok(insightService.insight(p.userId(), weeks));
    }

    /** O2：近 N 周参数（默认 12，钳制 1..52），DB 层 limit。 */
    @GetMapping("/profile")
    public ApiResponse<ProfileResp> profile(
            @AuthenticationPrincipal AuthPrincipal p,
            @RequestParam(defaultValue = "12") int weeks) {
        return ApiResponse.ok(emotionService.profile(p.userId(), weeks));
    }
}
