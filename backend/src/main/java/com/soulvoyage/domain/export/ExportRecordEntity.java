package com.soulvoyage.domain.export;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A5 导出留痕：谁在何时领过什么导出。只记元数据（file_ref 为一次性快照号），
 * 快照内容本身仍是内存 TTL 领取即焚，不落库。
 */
@Getter
@Setter
@Entity
@Table(name = "export_record")
public class ExportRecordEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** ARCHIVE（成长档案打印页）/ PERSONAL_DATA（S2 可携带权全量导出） */
    @Column(nullable = false, length = 24)
    private String kind;

    /** PENDING/CLAIMED/EXPIRED */
    @Column(nullable = false, length = 16)
    private String status = "PENDING";

    @Column(name = "file_ref", nullable = false, length = 26)
    private String fileRef;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
