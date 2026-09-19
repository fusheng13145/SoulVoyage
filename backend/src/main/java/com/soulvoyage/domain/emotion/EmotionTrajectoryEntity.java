package com.soulvoyage.domain.emotion;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "emotion_trajectory")
public class EmotionTrajectoryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "record_date", nullable = false)
    private LocalDate recordDate;

    @Column(name = "source_type", nullable = false, length = 16)
    private String sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "primary_emotion", nullable = false, length = 32)
    private String primaryEmotion;

    @Column(nullable = false, precision = 4, scale = 3)
    private BigDecimal valence;

    @Column(nullable = false, precision = 3, scale = 2)
    private BigDecimal intensity;

    @Column(name = "event_tags")
    private String eventTags;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
