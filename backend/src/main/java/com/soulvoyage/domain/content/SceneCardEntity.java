package com.soulvoyage.domain.content;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 场景卡（N1）：M8 起 DB 真源，classpath scenes/scenes.json 仅作初始种子。 */
@Getter
@Setter
@Entity
@Table(name = "scene_card")
public class SceneCardEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 64)
    private String title;

    @Column(nullable = false, length = 512)
    private String description;

    /** 支持难度，逗号分隔：MILD,NORMAL,HARD */
    @Column(name = "difficulties", nullable = false, length = 32)
    private String difficulties = "MILD,NORMAL,HARD";

    /** {npcName, relation, persona{...}, openingLines{...}} 打包 JSON */
    @Column(name = "persona_json", nullable = false, length = 4000)
    private String personaJson;

    /** 考察维度 JSON 数组：LISTEN/BOUNDARY/EMPATHY/CONCESSION */
    @Column(name = "goal_dimensions", nullable = false, length = 128)
    private String goalDimensions;

    @Column(name = "max_turns", nullable = false)
    private Integer maxTurns = 20;

    /** N1 场景标签，逗号分隔 */
    @Column(length = 128)
    private String tags;

    /** N1 画像推荐：命中的压力源，逗号分隔 */
    @Column(name = "recommended_for", length = 128)
    private String recommendedFor;

    /** 1 上架 2 下架 */
    @Column(nullable = false)
    private Short status = 1;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
