package com.soulvoyage.domain.companion;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** 漫聊会话分段（下篇·C0）：自然日 + 30min 静默切段，随时续聊 */
@Getter
@Setter
@Entity
@Table(name = "companion_session")
public class CompanionSessionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** O1 业务日；分段键 = 自然日 + 30min 静默 */
    @Column(name = "chat_date", nullable = false)
    private LocalDate chatDate;

    @Column(name = "segment_no", nullable = false)
    private Integer segmentNo = 1;

    /** ACTIVE / SEALED(封口待消化) / DIGESTED(已进 COMPANION_PIPELINE) */
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(nullable = false)
    private Integer turns = 0;

    /** 静默计时基准 */
    @Column(name = "last_turn_at")
    private Instant lastTurnAt;

    /** 会话级排除分析（companion_analysis_on 关闭时新建会话置 1） */
    @Column(name = "excl_flag", nullable = false)
    private Short exclFlag = 0;

    @Column(name = "digested_at")
    private Instant digestedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
