package com.soulvoyage.audit;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "audit_log")
public class AuditLogEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 48)
    private String action;

    @Column(length = 128)
    private String target;

    @Column(length = 46)
    private String ip;

    @Column(name = "hash_chain", nullable = false, length = 64)
    private String hashChain;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
