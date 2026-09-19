package com.soulvoyage.domain.notify;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** G5 站内通知（无短信/邮件，隐私最小化）；dedup_key 保证定时扫描重跑不重复 */
@Getter
@Setter
@Entity
@Table(name = "notification")
public class NotificationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** SYSTEM / PLAN / ACHIEVEMENT / LETTER / RISK_CARE */
    @Column(nullable = false, length = 16)
    private String kind;

    @Column(name = "dedup_key", length = 64)
    private String dedupKey;

    @Column(nullable = false, length = 64)
    private String title;

    @Column(nullable = false, length = 500)
    private String body;

    /** 应用内跳转路径（实现增列：点击直达） */
    @Column(length = 128)
    private String link;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
