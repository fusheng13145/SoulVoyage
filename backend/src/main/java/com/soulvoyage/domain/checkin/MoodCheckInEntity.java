package com.soulvoyage.domain.checkin;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** G1 每日心情打卡：每日一条（UK 防重复、支持改），同步写 emotion_trajectory(SELF_RATING) */
@Getter
@Setter
@Entity
@Table(name = "mood_check_in")
public class MoodCheckInEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** O1 业务归属日，支持补写 */
    @Column(name = "check_date", nullable = false)
    private LocalDate checkDate;

    /** 整体心情 1-5（可空：只选表情不打分） */
    @Column
    private Short rating;

    /** 16 情绪盘闭集 code（与前端 EMOTIONS / 后端 EmotionCatalog 同源） */
    @Column(name = "emotion_code", nullable = false, length = 16)
    private String emotionCode;

    @Column
    private Short energy;

    /** ✦ 可选一句话密文 */
    @Lob
    @Column(name = "note_enc")
    private byte[] noteEnc;

    /** G3 补签救济（每自然月 1 次） */
    @Column(name = "made_up", nullable = false)
    private Short madeUp = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }
}
