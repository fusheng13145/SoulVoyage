package com.soulvoyage.domain.notify;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** G5 用户偏好（服务端真源；前端 localStorage 降级为缓存） */
@Getter
@Setter
@Entity
@Table(name = "user_preferences")
public class UserPreferencesEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /** system / light / dark */
    @Column(nullable = false, length = 8)
    private String theme = "system";

    @Column(name = "checkin_reminder_on", nullable = false)
    private Short checkinReminderOn = 0;

    /** HH:MM，业务时区 */
    @Column(name = "reminder_time", nullable = false, length = 5)
    private String reminderTime = "20:00";

    @Column(name = "plan_reminder_on", nullable = false)
    private Short planReminderOn = 0;

    @Column(nullable = false)
    private Short letterOn = 1;

    @Column(name = "haptic_on", nullable = false)
    private Short hapticOn = 1;

    /** C0 漫聊参与情绪分析总开关 */
    @Column(name = "companion_analysis_on", nullable = false)
    private Short companionAnalysisOn = 1;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
