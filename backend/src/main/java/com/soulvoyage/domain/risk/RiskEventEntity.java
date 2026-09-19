package com.soulvoyage.domain.risk;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 风险事件（手册 §4.5 / §5.1，✦ 证据引用加密归档）。
 * 管理员复盘入口：只见编号与元数据，解密 evidence_ref 需二次授权（§7.3 SOP 第④步）。
 */
@Getter
@Setter
@Entity
@Table(name = "risk_event")
public class RiskEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 8)
    private String level;               // MEDIUM / HIGH

    @Column(name = "trigger_type", nullable = false, length = 32)
    private String triggerType;         // KEYWORD_RULE/LLM_SEMANTIC/TRACE_SIGNAL/SIMULATE_BREAKOUT

    @Column(name = "rule_code", length = 48)
    private String ruleCode;

    @Lob
    @Column(name = "evidence_ref_enc")
    private byte[] evidenceRefEnc;      // ✦ 命中定位（规则码/偏移），非用户原文

    @Column(name = "action_taken", nullable = false, length = 64)
    private String actionTaken;         // 仅记录系统动作，CRISIS_CARD/PROFILE_FLAG/...

    @Column(name = "task_id")
    private Long taskId;

    @Column(nullable = false)
    private Short reviewed = 0;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
