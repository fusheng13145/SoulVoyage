package com.soulvoyage.domain.content;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 知识图谱节点（M8 · N2 单表多型）：DISTORTION/PSY_TOPIC/COMM_CASE/STRENGTH_TECH 四类共用，
 * 差异字段全部进 payload_json（与 classpath kg/*.json、Neo4j seed.cypher 三方同源）。
 * M8 起本表为运行时内容真源，classpath JSON 降级为初始种子（N4 热更新）。
 */
@Getter
@Setter
@Entity
@Table(name = "kg_node")
public class KgNodeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String type;

    @Column(nullable = false, unique = true, length = 48)
    private String code;

    @Column(nullable = false, length = 64)
    private String name;

    /** 内容字段 JSON：误区 definition/emotions/socraticTemplates；科普 summary/microAction/aboutTags；… */
    @Column(name = "payload_json", nullable = false, length = 4000)
    private String payloadJson;

    /** 1 上架 2 下架 */
    @Column(nullable = false)
    private Short status = 1;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
