package com.soulvoyage.domain.profile;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 周维度情感画像（手册 §4.5 成长档案：日报→周报数据源）。
 * stat_week 为 ISO 周编号（如 2026-W38），按 (user_id, stat_week) 幂等 upsert。
 */
@Getter
@Setter
@Entity
@Table(name = "emotion_profile")
public class EmotionProfileEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "stat_week", nullable = false, length = 8)
    private String statWeek;

    @Column(name = "stressor_top_json")
    private String stressorTopJson;

    @Column(name = "distortion_top_json")
    private String distortionTopJson;

    @Column(name = "avg_valence", precision = 4, scale = 3)
    private BigDecimal avgValence;

    @Column(name = "risk_level", nullable = false, length = 8)
    private String riskLevel = "LOW";

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
