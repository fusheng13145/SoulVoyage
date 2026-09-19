package com.soulvoyage.domain.exercise;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 练习打卡（手册 §4.4 / §6.5）：记录用户是否完成某个自助练习，供方案反馈与画像偏好使用。 */
@Getter
@Setter
@Entity
@Table(name = "exercise_record")
public class ExerciseRecordEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "exercise_id", nullable = false)
    private Long exerciseId;          // exercise_library 种子自增 id（闭集内）

    @Column(name = "plan_report_id")
    private Long planReportId;        // 来源疏导方案报告（可空）

    @Column(name = "plan_id")
    private Long planId;              // G4 挂真实成长计划（可空）

    @Column(name = "plan_item_seq")
    private Integer planItemSeq;      // 计划内条目序号（完成时回写 plan_item）

    @Column(name = "scheduled_date")
    private java.time.LocalDate scheduledDate; // C4 排期日

    /** O1 业务归属日（Asia/Shanghai），UK(user,exercise,check_date) 防同日重复 */
    @Column(name = "check_date", nullable = false)
    private java.time.LocalDate checkDate;

    /** C4 实际跟练时长（秒），前端计时上报 */
    @Column(name = "duration_actual")
    private Integer durationActual;

    @Column(nullable = false)
    private Short completed = 0;

    @Column(length = 255)
    private String feedback;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
