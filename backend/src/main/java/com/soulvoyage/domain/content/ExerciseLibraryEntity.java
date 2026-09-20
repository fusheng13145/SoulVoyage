package com.soulvoyage.domain.content;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** 自助练习库（闭集）：M8 起 DB 真源，code 统一小写 ex_*，classpath exercises.json 仅作初始种子。 */
@Getter
@Setter
@Entity
@Table(name = "exercise_library")
public class ExerciseLibraryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 64)
    private String name;

    /** 适用情绪/场景枚举 JSON 数组 */
    @Column(name = "apply_emotions", nullable = false, length = 256)
    private String applyEmotions;

    /** 引导步骤 JSON（规则层内容，不由 LLM 生成） */
    @Column(name = "steps_json", nullable = false, length = 4000)
    private String stepsJson;

    @Column(name = "duration_min", nullable = false)
    private Short durationMin = 5;

    /** 1 上架 2 下架 */
    @Column(nullable = false)
    private Short status = 1;
}
