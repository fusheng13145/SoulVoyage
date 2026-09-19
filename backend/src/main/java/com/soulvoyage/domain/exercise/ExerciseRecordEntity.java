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

    @Column(nullable = false)
    private Short completed = 0;

    @Column(length = 255)
    private String feedback;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
