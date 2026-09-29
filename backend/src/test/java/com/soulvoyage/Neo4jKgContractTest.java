package com.soulvoyage;

import com.soulvoyage.domain.content.ContentStore;
import com.soulvoyage.kg.DbKgService;
import com.soulvoyage.kg.KgSearchService;
import com.soulvoyage.kg.Neo4jKgService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.EnabledIf;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M16 契约对齐：Neo4j 图实现（KgSearchService 的图版本）与 DbKgService 在<b>同输入下结果逐字段一致</b>。
 *
 * <p>这是"零改动替换"的验收口径本身——不是"看起来差不多"，而是 record 级别的 equals 断言；
 * 任一方法出现召回集合、顺序或字段差异即失败。图侧数据来自同一次 content:version 同步，
 * 因此差异只可能来自 Cypher 与 Java 打分逻辑的语义偏差，正是本测试要拦下的东西。
 *
 * <p>需要真实 Neo4j 实例（本机 127.0.0.1:7687），故默认跳过；本机执行方式：
 * <pre>
 *   export SV_NEO4J_TEST=true SV_NEO4J_PASS=123456
 *   mvn test -Dtest=Neo4jKgContractTest
 * </pre>
 * 无图实例的环境（CI / H2 回归）保持 138 例基线不受影响。
 */
@SpringBootTest(properties = "soulvoyage.neo4j.enabled=true")
@ActiveProfiles("test")
@EnabledIf(expression = "#{environment.getProperty('SV_NEO4J_TEST') == 'true'}", loadContext = true)
class Neo4jKgContractTest {

    @Autowired Neo4jKgService graph;
    @Autowired DbKgService db;
    @Autowired ContentStore store;

    private static final List<String> EMOTIONS = List.of("焦虑", "愤怒", "悲伤", "羞耻", "平静");
    private static final List<List<String>> TAG_SETS = List.of(
            List.of(), List.of("人际冲突"), List.of("学业压力", "人际冲突"), List.of("日常", "其他"));

    @Test
    void distortionRecallMatchesDbImpl() {
        for (String emotion : EMOTIONS) {
            for (List<String> tags : TAG_SETS) {
                for (int max : List.of(1, 3, 8)) {
                    List<KgSearchService.DistortionCard> expect = db.distortionsFor(emotion, tags, max);
                    List<KgSearchService.DistortionCard> actual = graph.distortionsFor(emotion, tags, max);
                    assertEquals(expect, actual,
                            () -> "distortionsFor mismatch: emotion=" + emotion + " tags=" + tags + " max=" + max);
                }
            }
        }
    }

    @Test
    void psytopicRecallMatchesDbImpl() {
        for (String emotion : EMOTIONS) {
            for (String stressor : List.of("学业压力", "人际冲突", "就业压力")) {
                for (int max : List.of(2, 5, 20)) {
                    List<KgSearchService.PsyTopicCard> expect = db.psyTopicsFor(List.of(stressor), emotion, max);
                    List<KgSearchService.PsyTopicCard> actual = graph.psyTopicsFor(List.of(stressor), emotion, max);
                    assertEquals(expect, actual,
                            () -> "psyTopicsFor mismatch: stressor=" + stressor + " emotion=" + emotion);
                }
            }
            // 空压力源 + 仅情绪命中的分支
            assertEquals(db.psyTopicsFor(List.of(), emotion, 10), graph.psyTopicsFor(List.of(), emotion, 10));
        }
        // primaryEmotion 为空串/null 的分支（DbKgService 判 null，Cypher 侧须同样短路）
        assertEquals(db.psyTopicsFor(List.of("学业压力"), null, 10),
                graph.psyTopicsFor(List.of("学业压力"), null, 10));
    }

    @Test
    void psytopicLookupAndExistenceMatchDbImpl() {
        String first = store.psyTopics().get(0).kgNodeId();
        assertEquals(db.psyTopic(first), graph.psyTopic(first));
        assertEquals(Optional.empty(), graph.psyTopic("psy_not_exists"));
        assertTrue(graph.psyTopicExists(first));
        assertFalse(graph.psyTopicExists("psy_not_exists"));
        assertEquals(db.psyTopicExists(first), graph.psyTopicExists(first));
    }

    @Test
    void caseRecallMatchesDbImpl() {
        String scene = store.commCases().get(0).scene();
        List<String> distortionIds = List.of("cd_should_statements", "cd_labeling", "cd_should_statements");
        for (int max : List.of(1, 3, 15)) {
            assertEquals(db.casesFor(scene, distortionIds, max), graph.casesFor(scene, distortionIds, max),
                    () -> "casesFor mismatch: scene=" + scene + " max=" + max);
        }
        // sceneTag 为 null 时只按误区引用计分
        assertEquals(db.casesFor(null, distortionIds, 15), graph.casesFor(null, distortionIds, 15));
        // 全空输入 → 双方空集
        assertEquals(List.of(), graph.casesFor(null, List.of(), 5));
    }

    @Test
    void caseExistenceMatchesDbImpl() {
        String first = store.commCases().get(0).kgNodeId();
        assertTrue(graph.commCaseExists(first));
        assertFalse(graph.commCaseExists("cc_not_exists"));
        assertEquals(db.commCaseExists(first), graph.commCaseExists(first));
    }

    @Test
    void stressorMappingKeepsOrderAndDedup() {
        for (List<String> tags : TAG_SETS) {
            assertEquals(db.stressorsFor(tags), graph.stressorsFor(tags),
                    () -> "stressorsFor mismatch (顺序/去重是同契约的一部分): " + tags);
        }
        // 重复标签 + 未映射标签：保序去重语义与 DB 实现一致
        List<String> messy = List.of("学业压力", "学业压力", "不存在", "人际冲突");
        assertEquals(db.stressorsFor(messy), graph.stressorsFor(messy));
        assertEquals(db.stressorOptions(), graph.stressorOptions());
    }

    @Test
    void syncIsIdempotentAndCoversWholeCatalog() {
        long version = store.version();
        graph.forceSync();
        int first = graph.managedNodeCount();
        graph.forceSync();
        assertEquals(first, graph.managedNodeCount(), "重复同步不应改变图规模（MERGE 幂等）");
        assertEquals(version, store.version(), "同步不改变内容版本号");
        assertEquals(store.distortions().size() + store.psyTopics().size()
                        + store.commCases().size() + store.techniques().size(),
                first, "受管节点数应等于四类内容条目总数（kg_node 与图一一对应）");
    }
}
