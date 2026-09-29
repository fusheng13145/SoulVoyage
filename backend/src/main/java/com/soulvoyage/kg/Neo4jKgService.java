package com.soulvoyage.kg;

import com.soulvoyage.domain.content.ContentStore;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * KG 的 Neo4j 实现（M16）：{@link KgSearchService} 的图版本，由 soulvoyage.neo4j.enabled 切换。
 *
 * <p>三条设计立场（与手册 §5.3 及 M8 既有决策对齐）：
 * <ol>
 *   <li><b>MySQL kg_node 仍是唯一写入口</b>：管理端 N4 写口（PUT /admin/content/** → kg_node upsert →
 *       ContentStore.bump()）保持不变，Neo4j 是 {@code content:version} 驱动的<b>只读派生视图</b>——
 *       版本号变化才重建，因此"管理端改内容即时生效"这条既有验收口径在新实现下依旧成立，
 *       不需要双写、也不引入分布式一致性成本。</li>
 *   <li><b>召回打分与 {@link DbKgService} 逐字等价</b>：情绪 +2 / 每个压力源命中 +1 / 典型句式含情绪 +1，
 *       排序 score 降序后按 kgNodeId 升序，取 TopN——等价性由契约测试与真机对拍钉住，不做"近似"。</li>
 *   <li><b>图不可用不是单点故障</b>：同步失败或 Cypher 抛错时回落到 DB 实现（KG 的定位是"约束幻觉"的
 *       增强项，宁可退到表兜底也不能让 Agent 链路因图库抖动而失败）。</li>
 * </ol>
 *
 * <p>同步使用标签 {@code :SvManaged} 圈定"本服务管理"的节点集：每次全量重建会先 DETACH DELETE 这批节点，
 * 而 Emotion / EventTag / Stressor 三类闭集节点不打该标签（保留 seed 定义，只做 MERGE 补缺），
 * 因此重建既保证与 kg_node 一致，又不会把 16 情绪闭集误删。
 */
@Slf4j
public class Neo4jKgService implements KgSearchService {

    private static final String MANAGED = "SvManaged";

    private final ContentStore store;
    private final Driver driver;
    private final KgSearchService fallback;
    private final String database;

    /** 已同步的内容版本号；与 ContentStore 的 content:version 同源 */
    private volatile long syncedVersion = Long.MIN_VALUE;

    /**
     * 短路熔断窗口截止时刻（epoch ms）：失败后在该时刻前一律直接走 DB 回落，不再尝试建连。
     *
     * <p>缘起于 2026-09-29 真机故障注入：仅靠 driver 超时（默认 30s）仍会让每条 Agent 链路
     * 为图库抖动付一次超时代价；一次 SIMULATE 复盘内多次 KG 召回就会叠加成几十秒。
     * 熔断把"图已挂"的判定缓存 30s，使链路延迟回到 DB 侧水平；窗口过后自动放行一次探测，
     * 图库恢复即自然闭环（同时补做版本比对，内容变更不会因熔断被永久跳过）。
     */
    private static final long CIRCUIT_COOLDOWN_MS = 30_000L;

    private volatile long circuitOpenUntil = 0L;

    /** 熔断冷却窗口；生产固定 30s，测试可注入短窗口以验证半开探测 */
    private final long circuitCooldownMs;

    public Neo4jKgService(ContentStore store, Driver driver, KgSearchService fallback, String database) {
        this(store, driver, fallback, database, CIRCUIT_COOLDOWN_MS);
    }

    Neo4jKgService(ContentStore store, Driver driver, KgSearchService fallback, String database, long circuitCooldownMs) {
        this.store = store;
        this.driver = driver;
        this.fallback = fallback;
        this.database = database;
        this.circuitCooldownMs = circuitCooldownMs;
    }

    // ─────────────────────────── 同步 ───────────────────────────

    private boolean ensureSynced() {
        long version;
        try {
            version = store.version();
        } catch (Exception e) {
            log.warn("kg graph: read content version failed", e);
            return false;
        }
        if (version == syncedVersion) return true;
        synchronized (this) {
            if (version == syncedVersion) return true;
            try {
                synchronize();
                syncedVersion = version;
                log.info("kg graph synced at content version {}", version);
                return true;
            } catch (Exception e) {
                log.warn("kg graph sync failed at content version {}, keep previous graph", version, e);
                return false;
            }
        }
    }

    private void synchronize() {
        var distortions = store.distortions();
        var techniques = store.techniques();
        var psyTopics = store.psyTopics();
        var commCases = store.commCases();
        var stressorMap = store.stressorMap();

        Set<String> emotions = new LinkedHashSet<>();
        distortions.forEach(d -> emotions.addAll(d.emotions()));
        techniques.forEach(t -> emotions.addAll(t.emotions()));

        List<Map<String, Object>> dRows = distortions.stream().map(Neo4jKgService::rowOf).toList();
        List<Map<String, Object>> tRows = techniques.stream().map(Neo4jKgService::rowOf).toList();
        List<Map<String, Object>> pRows = psyTopics.stream().map(Neo4jKgService::rowOf).toList();
        List<Map<String, Object>> cRows = commCases.stream().map(Neo4jKgService::rowOf).toList();
        List<Map<String, Object>> pairs = stressorMap.entrySet().stream()
                .map(e -> Map.<String, Object>of("tag", nz(e.getKey()), "stressor", nz(e.getValue())))
                .toList();

        try (Session s = session()) {
            s.executeWrite(tx -> {
                tx.run(CLEAR_MANAGED).consume();
                if (!emotions.isEmpty()) {
                    tx.run(SYNC_EMOTIONS, Values.parameters("names", List.copyOf(emotions))).consume();
                }
                if (!dRows.isEmpty()) {
                    tx.run(SYNC_DISTORTIONS, Values.parameters("rows", dRows)).consume();
                }
                if (!tRows.isEmpty()) {
                    tx.run(SYNC_TECHNIQUES, Values.parameters("rows", tRows)).consume();
                }
                if (!pRows.isEmpty()) {
                    tx.run(SYNC_PSY_TOPICS, Values.parameters("rows", pRows)).consume();
                }
                if (!cRows.isEmpty()) {
                    tx.run(SYNC_COMM_CASES, Values.parameters("rows", cRows)).consume();
                }
                if (!pairs.isEmpty()) {
                    tx.run(SYNC_STRESSOR_MAP, Values.parameters("pairs", pairs)).consume();
                }
                return null;
            });
        }
    }

    private static final String CLEAR_MANAGED = "MATCH (n:" + MANAGED + ") DETACH DELETE n";

    private static final String SYNC_EMOTIONS = """
            UNWIND $names AS name
            MERGE (:Emotion {name: name})""";

    private static final String SYNC_DISTORTIONS = """
            UNWIND $rows AS row
            MERGE (d:CognitiveDistortion {kgNodeId: row.kgNodeId})
            SET d:SvManaged, d.name = row.name, d.definition = row.definition,
                d.typicalSignature = row.typicalSignature, d.socraticTemplates = row.socraticTemplates,
                d.emotions = row.emotions
            FOREACH (en IN row.emotions |
              MERGE (e:Emotion {name: en})
              MERGE (e)-[:TRIGGERS]->(d))""";

    private static final String SYNC_TECHNIQUES = """
            UNWIND $rows AS row
            MERGE (t:StrengthTechnique {kgNodeId: row.kgNodeId})
            SET t:SvManaged, t.name = row.name, t.description = row.description, t.whenToUse = row.whenToUse,
                t.emotions = row.emotions, t.steps = row.steps
            FOREACH (en IN row.emotions |
              MERGE (e:Emotion {name: en})
              MERGE (e)-[:EASED_BY]->(t))""";

    private static final String SYNC_PSY_TOPICS = """
            UNWIND $rows AS row
            MERGE (p:PsyTopic {kgNodeId: row.kgNodeId})
            SET p:SvManaged, p.title = row.title, p.summary = row.summary, p.microAction = row.microAction,
                p.aboutTags = row.aboutTags, p.readingSec = row.readingSec""";

    private static final String SYNC_COMM_CASES = """
            UNWIND $rows AS row
            MERGE (c:CommCase {kgNodeId: row.kgNodeId})
            SET c:SvManaged, c.title = row.title, c.scene = row.scene, c.situation = row.situation,
                c.unhelpful = row.unhelpful, c.helpful = row.helpful,
                c.distortionRefs = row.distortionRefs, c.techniqueRefs = row.techniqueRefs,
                c.aboutTags = row.aboutTags
            FOREACH (dr IN row.distortionRefs |
              MERGE (d:CognitiveDistortion {kgNodeId: dr})
              MERGE (c)-[:SHOWS_DISTORTION]->(d))
            FOREACH (tr IN row.techniqueRefs |
              MERGE (t:StrengthTechnique {kgNodeId: tr})
              MERGE (c)-[:USES_TECHNIQUE]->(t))""";

    private static final String SYNC_STRESSOR_MAP = """
            UNWIND $pairs AS p
            MERGE (t:EventTag {name: p.tag})
            MERGE (s:Stressor {name: p.stressor})
            MERGE (t)-[:MAPS_TO]->(s)""";

    // ─────────────────────────── 查询（与 DbKgService 等价） ───────────────────────────

    private static final String Q_DISTORTIONS = """
            MATCH (d:CognitiveDistortion)
            OPTIONAL MATCH (d)-[:TRIGGERS]-(e:Emotion)
            WITH d, collect(DISTINCT e.name) AS ems
            WITH d, ems,
                 (CASE WHEN $emotion IN ems THEN 2 ELSE 0 END)
               + size([s IN $stressors WHERE s IN ems])
               + (CASE WHEN d.typicalSignature CONTAINS $emotion THEN 1 ELSE 0 END) AS score
            WHERE score > 0
            RETURN d.kgNodeId AS id, d.name AS name, d.definition AS definition,
                   d.typicalSignature AS sig, d.socraticTemplates AS templates
            ORDER BY score DESC, id ASC
            LIMIT $max""";

    private static final String Q_STRESSOR_MAP = """
            MATCH (t:EventTag)-[:MAPS_TO]->(s:Stressor)
            RETURN t.name AS tag, s.name AS stressor""";

    private static final String Q_STRESSOR_OPTIONS = """
            MATCH (:EventTag)-[:MAPS_TO]->(s:Stressor)
            RETURN DISTINCT s.name AS name
            ORDER BY name ASC""";

    private static final String Q_PSY_TOPICS = """
            MATCH (p:PsyTopic)
            WITH p, size([x IN $stressors WHERE x IN p.aboutTags]) * 2
                  + (CASE WHEN $emotion IS NOT NULL AND $emotion IN p.aboutTags THEN 1 ELSE 0 END) AS score
            WHERE score > 0
            RETURN p.kgNodeId AS id, p.title AS title, p.summary AS summary,
                   p.microAction AS microAction, p.aboutTags AS aboutTags, p.readingSec AS readingSec
            ORDER BY score DESC, id ASC
            LIMIT $max""";

    private static final String Q_PSY_ONE = """
            MATCH (p:PsyTopic {kgNodeId: $code})
            RETURN p.kgNodeId AS id, p.title AS title, p.summary AS summary,
                   p.microAction AS microAction, p.aboutTags AS aboutTags, p.readingSec AS readingSec""";

    private static final String Q_COMM_CASES = """
            MATCH (c:CommCase)
            WITH c, (CASE WHEN $sceneTag IS NOT NULL
                          AND (c.scene CONTAINS $sceneTag OR $sceneTag IN c.aboutTags)
                          THEN 2 ELSE 0 END)
                  + 2 * size([d IN $distortionIds WHERE d IN c.distortionRefs]) AS score
            WHERE score > 0
            RETURN c.kgNodeId AS id, c.title AS title, c.scene AS scene, c.situation AS situation,
                   c.unhelpful AS unhelpful, c.helpful AS helpful,
                   c.distortionRefs AS distortionRefs, c.techniqueRefs AS techniqueRefs
            ORDER BY score DESC, id ASC
            LIMIT $max""";

    private static final String Q_PSY_EXISTS =
            "MATCH (p:PsyTopic {kgNodeId: $code}) RETURN count(p) > 0 AS ex";

    private static final String Q_CASE_EXISTS =
            "MATCH (c:CommCase {kgNodeId: $code}) RETURN count(c) > 0 AS ex";

    @Override
    public List<DistortionCard> distortionsFor(String primaryEmotion, List<String> eventTags, int maxCount) {
        List<String> tags = eventTags == null ? List.of() : eventTags;
        return viaGraph(() -> {
            List<String> stressors = stressorsOf(tags);
            try (Session s = session()) {
                return s.executeRead(tx -> tx.run(Q_DISTORTIONS, Values.parameters(
                                "emotion", primaryEmotion, "stressors", stressors, "max", maxCount))
                        .list(r -> {
                            List<String> templates = strList(r, "templates");
                            return new DistortionCard(r.get("id").asString(), r.get("name").asString(),
                                    r.get("definition").asString(), r.get("sig").asString(),
                                    templates.isEmpty() ? "" : templates.get(0), templates);
                        }));
            }
        }, () -> fallback.distortionsFor(primaryEmotion, tags, maxCount), "distortionsFor");
    }

    @Override
    public List<PsyTopicCard> psyTopicsFor(List<String> stressors, String primaryEmotion, int maxCount) {
        List<String> ss = stressors == null ? List.of() : stressors;
        return viaGraph(() -> {
            try (Session s = session()) {
                return s.executeRead(tx -> tx.run(Q_PSY_TOPICS, Values.parameters(
                                "stressors", ss, "emotion", primaryEmotion, "max", maxCount))
                        .list(Neo4jKgService::psyCard));
            }
        }, () -> fallback.psyTopicsFor(ss, primaryEmotion, maxCount), "psyTopicsFor");
    }

    @Override
    public Optional<PsyTopicCard> psyTopic(String code) {
        return viaGraph(() -> {
            try (Session s = session()) {
                return s.executeRead(tx -> tx.run(Q_PSY_ONE, Values.parameters("code", code))
                        .list(Neo4jKgService::psyCard).stream().findFirst());
            }
        }, () -> fallback.psyTopic(code), "psyTopic");
    }

    @Override
    public List<CommCaseCard> casesFor(String sceneTag, List<String> distortionIds, int maxCount) {
        List<String> ids = distortionIds == null ? List.of() : distortionIds;
        return viaGraph(() -> {
            try (Session s = session()) {
                return s.executeRead(tx -> tx.run(Q_COMM_CASES, Values.parameters(
                                "sceneTag", sceneTag, "distortionIds", ids, "max", maxCount))
                        .list(r -> new CommCaseCard(r.get("id").asString(), r.get("title").asString(),
                                r.get("scene").asString(), r.get("situation").asString(),
                                r.get("unhelpful").asString(), r.get("helpful").asString(),
                                strList(r, "distortionRefs"), strList(r, "techniqueRefs"))));
            }
        }, () -> fallback.casesFor(sceneTag, ids, maxCount), "casesFor");
    }

    @Override
    public List<String> stressorsFor(List<String> eventTags) {
        List<String> tags = eventTags == null ? List.of() : eventTags;
        return viaGraph(() -> stressorsOf(tags), () -> fallback.stressorsFor(tags), "stressorsFor");
    }

    @Override
    public List<String> stressorOptions() {
        return viaGraph(() -> {
            try (Session s = session()) {
                return s.executeRead(tx -> tx.run(Q_STRESSOR_OPTIONS).list(r -> r.get("name").asString()));
            }
        }, fallback::stressorOptions, "stressorOptions");
    }

    @Override
    public boolean psyTopicExists(String code) {
        return viaGraph(() -> {
            try (Session s = session()) {
                return s.executeRead(tx -> tx.run(Q_PSY_EXISTS, Values.parameters("code", code))
                        .single().get("ex").asBoolean());
            }
        }, () -> fallback.psyTopicExists(code), "psyTopicExists");
    }

    @Override
    public boolean commCaseExists(String code) {
        return viaGraph(() -> {
            try (Session s = session()) {
                return s.executeRead(tx -> tx.run(Q_CASE_EXISTS, Values.parameters("code", code))
                        .single().get("ex").asBoolean());
            }
        }, () -> fallback.commCaseExists(code), "commCaseExists");
    }

    // ─────────────────────────── internals ───────────────────────────

    /**
     * 事件标签 → 压力源：与 DbKgService 同语义（按输入标签顺序去重保序，未映射的标签忽略）。
     * 图侧按 MAPS_TO 关系取全量映射表后在内存按序组装——保序是接口契约的一部分（Prompt 注入顺序）。
     */
    private List<String> stressorsOf(List<String> eventTags) {
        Map<String, String> map = new LinkedHashMap<>();
        try (Session s = session()) {
            s.executeRead(tx -> tx.run(Q_STRESSOR_MAP).list(r -> map.put(
                    r.get("tag").asString(), r.get("stressor").asString())));
        }
        List<String> out = new ArrayList<>();
        for (String tag : eventTags) {
            String stressor = map.get(tag);
            if (stressor != null && !out.contains(stressor)) out.add(stressor);
        }
        return out;
    }

    private <T> T viaGraph(Supplier<T> graphCall, Supplier<T> fallbackCall, String op) {
        if (System.currentTimeMillis() < circuitOpenUntil) return fallbackCall.get();
        if (!ensureSynced()) return trip(op, null, fallbackCall);
        try {
            T result = graphCall.get();
            circuitOpenUntil = 0L;          // 成功即闭合熔断
            return result;
        } catch (Exception e) {
            return trip(op, e, fallbackCall);
        }
    }

    /** 打开熔断窗口并回落 DB；e 为 null 表示同步阶段的失败（已在 ensureSynced 内记过日志）。 */
    private <T> T trip(String op, Exception e, Supplier<T> fallbackCall) {
        circuitOpenUntil = System.currentTimeMillis() + circuitCooldownMs;
        if (e != null) {
            log.warn("kg graph query failed ({}), fallback to db impl for next {}s: {}",
                    op, circuitCooldownMs / 1000, e.getMessage());
        } else {
            log.warn("kg graph unavailable ({}), fallback to db impl for next {}s",
                    op, circuitCooldownMs / 1000);
        }
        return fallbackCall.get();
    }

    private Session session() {
        return database == null || database.isBlank()
                ? driver.session()
                : driver.session(SessionConfig.forDatabase(database));
    }

    private static PsyTopicCard psyCard(Record r) {
        return new PsyTopicCard(r.get("id").asString(), r.get("title").asString(), r.get("summary").asString(),
                r.get("microAction").asString(), strList(r, "aboutTags"), r.get("readingSec").asInt(40));
    }

    private static List<String> strList(Record r, String field) {
        Value v = r.get(field);
        if (v == null || v.isNull()) return List.of();
        return v.asList(x -> x == null || x.isNull() ? null : x.asString()).stream()
                .filter(Objects::nonNull).toList();
    }

    private static Map<String, Object> rowOf(ContentStore.DistortionEntry d) {
        return Map.<String, Object>of("kgNodeId", nz(d.kgNodeId()), "name", nz(d.name()),
                "definition", nz(d.definition()), "typicalSignature", nz(d.typicalSignature()),
                "socraticTemplates", d.socraticTemplates(), "emotions", d.emotions());
    }

    private static Map<String, Object> rowOf(ContentStore.PsyEntry p) {
        return Map.<String, Object>of("kgNodeId", nz(p.kgNodeId()), "title", nz(p.title()),
                "summary", nz(p.summary()), "microAction", nz(p.microAction()),
                "aboutTags", p.aboutTags(), "readingSec", p.readingSec());
    }

    private static Map<String, Object> rowOf(ContentStore.CaseEntry c) {
        return Map.<String, Object>of("kgNodeId", nz(c.kgNodeId()), "title", nz(c.title()),
                "scene", nz(c.scene()), "situation", nz(c.situation()), "unhelpful", nz(c.unhelpful()),
                "helpful", nz(c.helpful()), "distortionRefs", c.distortionRefs(),
                "techniqueRefs", c.techniqueRefs(), "aboutTags", c.aboutTags());
    }

    private static Map<String, Object> rowOf(ContentStore.TechEntry t) {
        return Map.<String, Object>of("kgNodeId", nz(t.kgNodeId()), "name", nz(t.name()),
                "description", nz(t.description()), "whenToUse", nz(t.whenToUse()),
                "emotions", t.emotions(), "steps", t.steps());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 当前已同步到的内容版本（Long.MIN_VALUE 表示尚未同步过；运维观测用） */
    public long syncedVersion() {
        return syncedVersion;
    }

    /** 显式触发一次同步（不改变懒同步语义；管理端排查与测试用） */
    public void forceSync() {
        synchronized (this) {
            synchronize();
            syncedVersion = store.version();
        }
    }

    /**
     * 受管节点总数（运维与测试观测用）：只计 :SvManaged 圈定的四类内容节点，
     * 不含 Emotion / EventTag / Stressor 三类闭集节点（它们由 seed 定义、不参与重建）。
     */
    public int managedNodeCount() {
        try (Session s = session()) {
            return s.executeRead(tx ->
                    tx.run("MATCH (n:" + MANAGED + ") RETURN count(n) AS c").single().get("c").asInt());
        }
    }
}
