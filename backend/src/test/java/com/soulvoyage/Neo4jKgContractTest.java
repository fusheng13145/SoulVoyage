package com.soulvoyage;

import com.soulvoyage.domain.content.ContentStore;
import com.soulvoyage.kg.DbKgService;
import com.soulvoyage.kg.KgSearchService;
import com.soulvoyage.kg.Neo4jKgService;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.EnabledIf;

import java.util.List;
import java.util.Map;
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
    /** G9 漂移注入用：直接对图执行 Cypher（模拟外部干预造成的图侧脏数据，非应用写路径） */
    @Autowired Driver driver;
    @Value("${soulvoyage.neo4j.database:neo4j}") String database;

    private static final List<String> EMOTIONS = List.of("焦虑", "愤怒", "悲伤", "羞耻", "平静");
    private static final List<List<String>> TAG_SETS = List.of(
            List.of(), List.of("人际冲突"), List.of("学业压力", "人际冲突"), List.of("日常", "其他"));
    /** G9 受管四类：kg_node 的全量派生视图，对账做严格全等 */
    private static final List<String> MANAGED_LABELS = List.of(
            "CognitiveDistortion", "StrengthTechnique", "PsyTopic", "CommCase");
    /** G9 闭集三类：权威词表在部署侧 seed.cypher，应用侧不可知，对账只做单向包含 */
    private static final List<String> CLOSED_LABELS = List.of("Emotion", "EventTag", "Stressor");
    /**
     * seed.cypher 定义的 16 情绪闭集。闭集的 reserved（图上多出内容引用的词）必须落在这张表内——
     * 落在表外说明图侧混进了非 seed 来源的情绪节点，那是真漂移，必须被拦下。
     */
    private static final List<String> SEED_EMOTIONS = List.of(
            "喜悦", "愤怒", "悲伤", "恐惧", "焦虑", "惊讶", "厌恶", "羞耻", "内疚",
            "委屈", "孤独", "麻木", "平静", "压力", "期待", "其他");
    /** G9 对账覆盖的五类关系 */
    private static final List<String> AUDIT_REL_TYPES = List.of(
            "TRIGGERS", "EASED_BY", "SHOWS_DISTORTION", "USES_TECHNIQUE", "MAPS_TO");

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

    // ─────────────────────────── G9：DB ↔ 图对账 ───────────────────────────

    /**
     * 一致态：图与 kg_node 上架内容逐项对齐。
     *
     * <p>断言口径刻意不止看 {@code consistent} 一个布尔——七类节点的 expect/actual 与五类关系的
     * expect/actual 必须逐行相等，且 missing/extra 均为空。这样即便一致性的判定被算错，明细行也会
     * 把它暴露出来，避免"结论为真、数据为假"的假绿。
     */
    @Test
    void auditReportsConsistentWhenGraphMatchesCatalog() {
        graph.forceSync();
        Map<String, Object> report = graph.audit();

        assertEquals(true, report.get("available"), "图可用时对账应可用");
        assertEquals(true, report.get("consistent"), () -> "图与 kg_node 应一致，实报：" + report);
        assertEquals(store.version(), report.get("contentVersion"), "对账应回带当前内容版本");
        assertEquals(graph.syncedVersion(), report.get("syncedVersion"), "对账应回带已同步版本");

        List<Map<String, Object>> nodes = rowsOf(report, "nodes");
        assertEquals(MANAGED_LABELS.size() + CLOSED_LABELS.size(), nodes.size(),
                "应覆盖四类受管 + 三类闭集：" + nodes);

        // 受管四类：全等口径——expect/actual 相等，且 missing/extra/reserved 三者皆空
        for (String label : MANAGED_LABELS) {
            Map<String, Object> row = auditRow(nodes, "label", label);
            assertEquals("managed", row.get("kind"), label + " 应标为受管节点");
            assertEquals(row.get("expect"), row.get("actual"),
                    () -> label + " 期望/实际节点数不一致：" + row);
            assertEquals(List.of(), row.get("missing"), label + " 不应有缺失节点");
            assertEquals(List.of(), row.get("extra"), label + " 不应有多余节点");
            assertEquals(List.of(), row.get("reserved"), label + " 受管节点不应有 seed 预留词");
            assertTrue((int) row.get("expect") > 0, label + " 应然节点数应大于 0（否则是空集对空集的假一致）");
        }

        // 闭集三类：单向包含口径——内容引用的词一个不能缺，图侧多出的词归 reserved 而非 extra
        for (String label : CLOSED_LABELS) {
            Map<String, Object> row = auditRow(nodes, "label", label);
            assertEquals("closed-set", row.get("kind"), label + " 应标为闭集节点");
            assertEquals(List.of(), row.get("missing"), label + " 内容引用的闭集词缺图即为真漂移");
            assertEquals(List.of(), row.get("extra"), label + " 闭集的多余词记在 reserved，不该落在 extra");
            assertTrue((int) row.get("actual") >= (int) row.get("expect"),
                    () -> label + " 图侧闭集不应少于内容引用的词：" + row);
        }
        // seed 预置 16 情绪闭集，当前内容只引用其中一部分 → 必有预留词；
        // 且预留词必须落在 seed 词表内，否则是图侧混入的非 seed 来源节点（真漂移）。
        // 这两条断言把"闭集口径真的在生效、且仍有检出能力"钉住，避免退化成"闭集一律放过"。
        Map<String, Object> emotionRow = auditRow(nodes, "label", "Emotion");
        List<String> reserved = castList(emotionRow.get("reserved"));
        assertTrue(!reserved.isEmpty(), () -> "seed 的 16 情绪闭集应有未被内容引用的预留词：" + emotionRow);
        assertTrue(SEED_EMOTIONS.containsAll(reserved),
                () -> "Emotion 预留词必须来自 seed 的 16 情绪闭集，实报：" + reserved);
        assertEquals((int) emotionRow.get("expect") + reserved.size(), (int) emotionRow.get("actual"),
                "闭集口径自洽：图侧总数 = 内容引用数 + 预留数");

        List<Map<String, Object>> rels = rowsOf(report, "relations");
        assertEquals(AUDIT_REL_TYPES.size(), rels.size(), "应覆盖五类关系：" + rels);
        for (String type : AUDIT_REL_TYPES) {
            Map<String, Object> row = auditRow(rels, "type", type);
            assertEquals(row.get("expect"), row.get("actual"),
                    () -> type + " 期望/实际关系数不一致（重复引用应只计一次）：" + row);
        }
    }

    /**
     * 漂移检出 + 重同步自愈——这是 G9 存在的全部理由。
     *
     * <p>图侧没有独立写入口，漂移只可能来自外部干预（人工改图、半途失败的批处理）或历史遗留。
     * 删掉一个受管节点后<b>内容版本号不变</b>，{@code ensureSynced()} 因此不会触发重建：正是这种
     * "版本号相同、图却已脏"的情形，靠重启或重连都发现不了，只能由对账兜住。
     */
    @Test
    void auditDetectsDriftAndSelfHealsOnResync() {
        graph.forceSync();
        int before = graph.managedNodeCount();
        String victim = store.distortions().get(0).kgNodeId();

        deleteManagedNode(victim);
        assertEquals(before - 1, graph.managedNodeCount(), "删除后受管节点数应减一：图侧确实产生了漂移");

        Map<String, Object> drifted = graph.audit();
        assertEquals(true, drifted.get("available"), "图可达，对账应可用");
        assertEquals(false, drifted.get("consistent"), () -> "删除节点后应检出漂移：" + drifted);
        Map<String, Object> cdRow = auditRow(rowsOf(drifted, "nodes"), "label", "CognitiveDistortion");
        assertTrue(castList(cdRow.get("missing")).contains(victim),
                () -> "缺失清单应点名被删节点 " + victim + "：" + cdRow);

        // 重同步即自愈：MERGE 按 kg_node 重建，对账随之收敛
        graph.forceSync();
        Map<String, Object> healed = graph.audit();
        assertEquals(true, healed.get("consistent"), () -> "重同步后应收敛为一致：" + healed);
        assertEquals(before, graph.managedNodeCount(), "重同步应恢复图规模");
    }

    /** 直接对图执行删除——模拟外部干预造成的图侧脏数据，不走应用写路径 */
    private void deleteManagedNode(String kgNodeId) {
        try (Session s = driver.session(SessionConfig.forDatabase(database))) {
            s.executeWrite(tx -> tx.run("MATCH (n:SvManaged {kgNodeId: $id}) DETACH DELETE n",
                    Map.of("id", kgNodeId)).consume());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(Map<String, Object> report, String key) {
        return (List<Map<String, Object>>) report.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object value) {
        return (List<String>) value;
    }

    private static Map<String, Object> auditRow(List<Map<String, Object>> rows, String key, String value) {
        return rows.stream().filter(r -> value.equals(String.valueOf(r.get(key)))).findFirst()
                .orElseThrow(() -> new AssertionError("未找到对账行 " + key + "=" + value + "，实际：" + rows));
    }
}
