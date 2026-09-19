package com.soulvoyage.domain.checkin;

import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/** 下篇·G1/G3 打卡端点：POST 打卡（当日重复即更新）、月热力数据、streak、补签 */
@RestController
@RequestMapping("/api/v1/mood-check-ins")
@RequiredArgsConstructor
public class MoodCheckInController {

    public record CheckInReq(@NotBlank String emotion, Integer rating, Integer energy,
                             @Size(max = 200) String note) {}

    private final CheckInService checkIn;
    private final StreakService streak;

    @PostMapping
    public ApiResponse<CheckInService.CheckInView> checkIn(@AuthenticationPrincipal AuthPrincipal p,
                                                           @RequestParam(required = false) LocalDate date,
                                                           @Valid @RequestBody CheckInReq req) {
        var r = new CheckInService.CheckInReq(req.emotion(), req.rating(), req.energy(), req.note());
        return ApiResponse.ok(checkIn.checkIn(p.userId(), date, r));
    }

    /** month=YYYY-MM；返回当月打卡数据 + 今天是否已打 */
    @GetMapping
    public ApiResponse<Map<String, Object>> month(@AuthenticationPrincipal AuthPrincipal p,
                                                  @RequestParam String month) {
        int y, m;
        try {
            String[] parts = month.split("-");
            y = Integer.parseInt(parts[0]);
            m = Integer.parseInt(parts[1]);
        } catch (Exception e) {
            throw new com.soulvoyage.common.exception.BizException(
                    com.soulvoyage.common.api.ErrorCode.BAD_PARAMS, "month 格式应为 YYYY-MM");
        }
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("items", checkIn.month(p.userId(), y, m));
        out.put("today", checkIn.today(p.userId()));
        return ApiResponse.ok(out);
    }

    @GetMapping("/streak")
    public ApiResponse<StreakService.StreakView> streak(@AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(streak.compute(p.userId()));
    }

    @PostMapping("/makeup")
    public ApiResponse<CheckInService.CheckInView> makeup(@AuthenticationPrincipal AuthPrincipal p,
                                                          @RequestParam LocalDate date,
                                                          @Valid @RequestBody CheckInReq req) {
        var r = new CheckInService.CheckInReq(req.emotion(), req.rating(), req.energy(), req.note());
        return ApiResponse.ok(checkIn.makeup(p.userId(), date, r));
    }
}
