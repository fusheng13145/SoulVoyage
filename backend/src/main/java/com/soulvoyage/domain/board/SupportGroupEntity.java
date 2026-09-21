package com.soulvoyage.domain.board;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** M12 辅导员群体：成员由管理端维护，看板只出该群体内已授权用户的匿名聚合统计 */
@Getter
@Setter
@Entity
@Table(name = "support_group")
public class SupportGroupEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 48)
    private String name;

    /** 1 启用 2 停用（停用后 stats 直接抑制，不毁成员关系） */
    @Column(nullable = false)
    private Short status = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }
}
