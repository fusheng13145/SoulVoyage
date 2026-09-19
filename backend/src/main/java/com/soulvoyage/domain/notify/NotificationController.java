package com.soulvoyage.domain.notify;

import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 下篇·G5 通知中心 + 偏好端点（铃铛列表 / 已读 / 提醒开关） */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notify;
    private final PreferencesService prefs;

    @GetMapping("/notifications")
    public ApiResponse<Map<String, Object>> list(@AuthenticationPrincipal AuthPrincipal p,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(notify.list(p.userId(), page, size));
    }

    @PostMapping("/notifications/{id}/read")
    public ApiResponse<Void> read(@AuthenticationPrincipal AuthPrincipal p, @PathVariable long id) {
        notify.markRead(p.userId(), id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/notifications/read-all")
    public ApiResponse<Void> readAll(@AuthenticationPrincipal AuthPrincipal p) {
        notify.markAllRead(p.userId());
        return ApiResponse.ok(null);
    }

    @GetMapping("/preferences")
    public ApiResponse<Map<String, Object>> getPrefs(@AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(prefs.get(p.userId()));
    }

    @PutMapping("/preferences")
    public ApiResponse<Map<String, Object>> putPrefs(@AuthenticationPrincipal AuthPrincipal p,
                                                     @RequestBody Map<String, Object> patch) {
        return ApiResponse.ok(prefs.update(p.userId(), patch));
    }
}
