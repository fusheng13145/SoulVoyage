package com.soulvoyage.domain.content;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 内容字典与缓存版本（N4）：content:version 是各内容缓存的失效信号；kg:stressor_map 存压力源字典。 */
@Getter
@Setter
@Entity
@Table(name = "content_meta")
public class ContentMetaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "meta_key", nullable = false, unique = true, length = 64)
    private String metaKey;

    @Column(name = "meta_value", nullable = false, length = 2000)
    private String metaValue;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
