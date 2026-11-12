package com.soulvoyage.domain.content;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** H-04 内容发布快照：每次发布/回滚追加一条，不可修改。 */
@Getter
@Setter
@Entity
@Table(name = "content_revision",
        uniqueConstraints = @UniqueConstraint(name = "uk_content_revision_type_code_no",
                columnNames = {"content_type", "code", "revision_no"}))
public class ContentRevisionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_type", nullable = false, length = 16)
    private String contentType;

    @Column(nullable = false, length = 48)
    private String code;

    @Column(name = "revision_no", nullable = false)
    private Integer revisionNo;

    @Column(name = "payload_json", nullable = false, length = 65535)
    private String payloadJson;

    /** PUBLISH / ROLLBACK */
    @Column(nullable = false, length = 16)
    private String action;

    @Column(name = "source_revision_id")
    private Long sourceRevisionId;

    @Column(name = "published_by", nullable = false)
    private Long publishedBy;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @PrePersist
    void touch() {
        publishedAt = Instant.now();
    }
}
