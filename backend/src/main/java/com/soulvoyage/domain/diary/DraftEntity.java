package com.soulvoyage.domain.diary;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 服务端草稿（下篇·C1）：编辑器 30s 自动保存，换端不丢；(user_id, kind) 幂等 upsert 单行 */
@Getter
@Setter
@Entity
@Table(name = "draft")
public class DraftEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 16)
    private String kind = "DIARY";

    @Lob
    @Column(name = "content_enc", nullable = false)
    private byte[] contentEnc;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
