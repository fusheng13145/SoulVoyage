package com.leyon.soul.domain.content;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** H-04 内容运营草稿：每 (type, code) 一条，未发布不影响 live 表。 */
@Getter
@Setter
@Entity
@Table(name = "content_draft",
        uniqueConstraints = @UniqueConstraint(name = "uk_content_draft_type_code",
                columnNames = {"content_type", "code"}))
public class ContentDraftEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_type", nullable = false, length = 16)
    private String contentType;

    @Column(nullable = false, length = 48)
    private String code;

    /** 完整快照草稿；MySQL JSON，纯 String 绑定（@Lob 会被驱动送成二进制）。 */
    @Column(name = "payload_json", nullable = false, length = 65535)
    private String payloadJson;

    @Column(name = "base_revision_id")
    private Long baseRevisionId;

    @Column(length = 255)
    private String note;

    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
