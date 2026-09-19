package com.soulvoyage.domain.crisis;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 危机状态机迁移留痕（append-only）。created_at 由应用显式写入，保证 H2 测试可断言时序。 */
@Getter
@Setter
@Entity
@Table(name = "crisis_lifecycle")
public class CrisisLifecycleEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "from_state", nullable = false, length = 16)
    private String fromState;

    @Column(name = "to_state", nullable = false, length = 16)
    private String toState;

    @Column(nullable = false, length = 64)
    private String reason;

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
