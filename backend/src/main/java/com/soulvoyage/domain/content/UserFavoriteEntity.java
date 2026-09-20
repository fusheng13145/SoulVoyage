package com.soulvoyage.domain.content;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** N3 收藏：科普文章等内容的书签，进档案导出。 */
@Getter
@Setter
@Entity
@Table(name = "user_favorite")
public class UserFavoriteEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 16)
    private String type;

    @Column(name = "ref_code", nullable = false, length = 48)
    private String refCode;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
