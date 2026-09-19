package com.soulvoyage.domain.diary;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 情绪日记（下篇·C1：diary 表自 M0 就在，M5 补齐实体与写入链路）。
 * 原文 ✦ 信封加密存储（content_enc），注销销毁 DEK 后物理不可读。
 */
@Getter
@Setter
@Entity
@Table(name = "diary")
public class DiaryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Lob
    @Column(name = "content_enc", nullable = false)
    private byte[] contentEnc;

    /** 用户自评心情 1-5（可空：只交给 AI 分析时不强制自评） */
    @Column(name = "mood_self_rating")
    private Short moodSelfRating;

    @Column(name = "record_date", nullable = false)
    private LocalDate recordDate;

    /** 关联 DIARY_PIPELINE 任务（重新分析后指向最新一次） */
    @Column(name = "task_id")
    private Long taskId;

    /** 应用显式写入（H2/MySQL 语义一致，列表排序可用） */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
