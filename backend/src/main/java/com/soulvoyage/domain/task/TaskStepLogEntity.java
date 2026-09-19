package com.soulvoyage.domain.task;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "task_step_log")
public class TaskStepLogEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "step_seq", nullable = false)
    private Integer stepSeq;

    @Column(name = "agent_code", nullable = false, length = 32)
    private String agentCode;

    @Column(name = "step_code", nullable = false, length = 48)
    private String stepCode;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private Short attempt = 1;

    @Column(name = "cost_ms")
    private Integer costMs;

    @Column(name = "tokens_in")
    private Integer tokensIn;

    @Column(name = "tokens_out")
    private Integer tokensOut;

    @Column(name = "llm_calls", nullable = false)
    private Short llmCalls = 0;

    @Column(length = 64)
    private String model;

    @Column(name = "error_msg", length = 512)
    private String errorMsg;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
