package com.soulvoyage.domain.plan;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** G4 计划逐日条目 */
@Getter
@Setter
@Entity
@Table(name = "plan_item")
public class PlanItemEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    /** 全局序号，从 1 */
    @Column(nullable = false)
    private Integer seq;

    @Column(name = "exercise_code", nullable = false, length = 32)
    private String exerciseCode;

    /** 一句引导语 */
    @Column(length = 255)
    private String guidance;

    @Column(name = "scheduled_date", nullable = false)
    private LocalDate scheduledDate;

    @Column(name = "done_at")
    private Instant doneAt;

    @Column(length = 255)
    private String feedback;
}
