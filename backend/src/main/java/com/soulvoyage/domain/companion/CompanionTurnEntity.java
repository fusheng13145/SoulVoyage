package com.soulvoyage.domain.companion;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 漫聊逐轮记录（下篇·C0）：用户消息 ✦ 加密，同 simulate_turn 规范 */
@Getter
@Setter
@Entity
@Table(name = "companion_turn")
public class CompanionTurnEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "turn_no", nullable = false)
    private Integer turnNo;

    /** ✦ 用户消息密文 */
    @Lob
    @Column(name = "user_text_enc", nullable = false)
    private byte[] userTextEnc;

    /** 陪伴回复（平台生成，明文便于回放） */
    @Column(name = "ai_text", nullable = false, length = 2000)
    private String aiText;

    /** 情绪响应策略档位：FOLLOW/LOW_ENERGY/WARM_UP… */
    @Column(name = "mood_tag", length = 16)
    private String moodTag;

    /** 逐轮 RiskRules 命中（HIGH 当轮拦截） */
    @Column(name = "risk_hit", nullable = false)
    private Short riskHit = 0;

    /** 「这句别分析」：本条排除画像管道 */
    @Column(name = "no_analyze", nullable = false)
    private Short noAnalyze = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
