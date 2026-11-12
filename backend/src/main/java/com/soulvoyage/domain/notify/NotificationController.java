package com.soulvoyage.domain.notify;

import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.domain.notify.dto.NotificationDtos.NotificationItem;
import com.soulvoyage.domain.notify.dto.NotificationDtos.NotificationListResp;
import com.soulvoyage.domain.notify.dto.NotificationDtos.PrefsPatchReq;
import com.soulvoyage.domain.notify.dto.NotificationDtos.PrefsResp;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 下篇·G5 通知中心 + 偏好端点（铃铛列表 / 已读 / 提醒开关） */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notify;
    private final PreferencesService prefs;

    @GetMapping("/notifications")
    @SuppressWarnings("unchecked")
    public ApiResponse<NotificationListResp> list(@AuthenticationPrincipal AuthPrincipal p,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        Map<String, Object> m = notify.list(p.userId(), page, size);
        List<NotificationItem> items = ((List<Map<String, Object>>) m.get("items")).stream()
                .map(x -> new NotificationItem(
                        ((Number) x.get("id")).longValue(), (String) x.get("kind"),
                        (String) x.get("title"), (String) x.get("body"),
                        (String) x.get("link"), (String) x.get("readAt"),
                        (String) x.get("createdAt")))
                .toList();
        return ApiResponse.ok(new NotificationListResp(
                items, ((Number) m.get("total")).longValue(),
                ((Number) m.get("unreadCount")).longValue()));
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
    public ApiResponse<PrefsResp> getPrefs(@AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(prefsView(prefs.get(p.userId())));
    }

    @PutMapping("/preferences")
    public ApiResponse<PrefsResp> putPrefs(@AuthenticationPrincipal AuthPrincipal p,
                                           @RequestBody PrefsPatchReq req) {
        Map<String, Object> patch = new java.util.HashMap<>();
        if (req.theme() != null) patch.put("theme", req.theme());
        if (req.checkinReminderOn() != null) patch.put("checkinReminderOn", req.checkinReminderOn());
        if (req.reminderTime() != null) patch.put("reminderTime", req.reminderTime());
        if (req.planReminderOn() != null) patch.put("planReminderOn", req.planReminderOn());
        if (req.letterOn() != null) patch.put("letterOn", req.letterOn());
        if (req.hapticOn() != null) patch.put("hapticOn", req.hapticOn());
        if (req.companionAnalysisOn() != null) patch.put("companionAnalysisOn", req.companionAnalysisOn());
        if (req.counselorBoardOn() != null) patch.put("counselorBoardOn", req.counselorBoardOn());
        return ApiResponse.ok(prefsView(prefs.update(p.userId(), patch)));
    }

    private static PrefsResp prefsView(Map<String, Object> m) {
        return new PrefsResp(
                (String) m.get("theme"),
                Boolean.TRUE.equals(m.get("checkinReminderOn")), (String) m.get("reminderTime"),
                Boolean.TRUE.equals(m.get("planReminderOn")), Boolean.TRUE.equals(m.get("letterOn")),
                Boolean.TRUE.equals(m.get("hapticOn")), Boolean.TRUE.equals(m.get("companionAnalysisOn")),
                Boolean.TRUE.equals(m.get("counselorBoardOn")), (String) m.get("updatedAt"));
    }
}
