package com.soulvoyage.domain.task;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "task_instance")
public class TaskInstanceEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_no", nullable = false, length = 26, unique = true)
    private String taskNo;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "pipeline_code", nullable = false, length = 32)
    private String pipelineCode;

    @Column(nullable = false, length = 24)
    private String status = "PENDING";

    @Column(nullable = false)
    private Short priority = 5;

    @Column(name = "client_req_id", length = 64)
    private String clientReqId;

    @Column(name = "error_msg", length = 512)
    private String errorMsg;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    void stamp() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
