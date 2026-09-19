package com.soulvoyage.domain.plan;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** G4 成长计划：SUPPORT 输出升级为可执行载体（3/5/7 天，每日 1-2 练习项 + 引导语） */
@Getter
@Setter
@Entity
@Table(name = "growth_plan")
public class GrowthPlanEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "source_report_id")
    private Long sourceReportId;

    @Column(nullable = false)
    private Short days = 3;

    @Column(nullable = false, length = 128)
    private String title = "";

    /** ACTION / DONE / DROPPED */
    @Column(nullable = false, length = 16)
    private String status = "ACTION";

    /** 生成时快照（权威逐日数据在 plan_item） */
    @Lob
    @Column(name = "items_json")
    private String itemsJson;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /** 到期小结是否已生成 */
    @Column(name = "summary_done", nullable = false)
    private Short summaryDone = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
