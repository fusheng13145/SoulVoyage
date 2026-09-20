package com.soulvoyage.domain.content;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** N3 阅读记录：每日一读按业务日去重（同一篇同一天只算一次）。 */
@Getter
@Setter
@Entity
@Table(name = "user_read_log")
public class UserReadLogEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 48)
    private String code;

    @Column(name = "read_date", nullable = false)
    private LocalDate readDate;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
