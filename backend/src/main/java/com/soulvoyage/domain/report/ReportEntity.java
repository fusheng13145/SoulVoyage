package com.soulvoyage.domain.report;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "report")
public class ReportEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 16)
    private String type;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "biz_ref_id")
    private Long bizRefId;

    @Column(nullable = false, length = 128)
    private String title = "";

    @Lob
    @Column(name = "content_enc", nullable = false)
    private byte[] contentEnc;

    @Column(name = "risk_level", nullable = false, length = 8)
    private String riskLevel = "LOW";

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
