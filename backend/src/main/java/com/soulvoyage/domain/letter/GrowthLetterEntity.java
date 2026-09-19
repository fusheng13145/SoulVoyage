package com.soulvoyage.domain.letter;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** G6 成长来信：每周一个人格化周报（第二人称信），引用只取用户自己的记录片段 */
@Getter
@Setter
@Entity
@Table(name = "growth_letter")
public class GrowthLetterEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** ISO 周，如 2026-W38 */
    @Column(name = "stat_week", nullable = false, length = 8)
    private String statWeek;

    /** ✦ 信正文密文 */
    @Lob
    @Column(name = "content_enc", nullable = false)
    private byte[] contentEnc;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
