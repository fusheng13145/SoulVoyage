package com.soulvoyage.domain.notify.dto;

import java.util.List;

/** 通知中心与偏好读接口的响应契约。 */
public class NotificationDtos {

    public record NotificationItem(Long id, String kind, String title, String body, String link,
                                   String readAt, String createdAt) {}

    public record NotificationListResp(List<NotificationItem> items, long total, long unreadCount) {}

    public record PrefsResp(String theme, boolean checkinReminderOn, String reminderTime,
                            boolean planReminderOn, boolean letterOn, boolean hapticOn,
                            boolean companionAnalysisOn, boolean counselorBoardOn, String updatedAt) {}

    /** 偏好单项/多项更新（部分字段为空即不改；未知键忽略，与既有兼容语义一致）。 */
    public record PrefsPatchReq(String theme, Boolean checkinReminderOn, String reminderTime,
                                Boolean planReminderOn, Boolean letterOn, Boolean hapticOn,
                                Boolean companionAnalysisOn, Boolean counselorBoardOn) {}
}
