package com.soulvoyage.kg;

import com.soulvoyage.domain.content.ContentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionCallback;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link Neo4jKgService} 的零依赖单元测试：<b>不连真图库</b>，因此 CI 与 H2 回归恒跑。
 *
 * <p>与 {@code Neo4jKgContractTest}（需真图实例、只在有实例时执行）互补——后者钉"图活着时
 * 召回是否与 DbKgService 逐字段等价"，本测试钉"图活着时每条 Cypher 路径、每个字段映射、
 * 每种降级分支各自做什么"，两者覆盖面不重叠。
 *
 * <p><b>核心手法</b>：{@code Session.executeRead/executeWrite} 收到的是 lambda，若直接 stub 成
 * 固定返回值，lambda 体永远不被执行、覆盖率为零。故这里一律用 {@code thenAnswer} 把参数
 * （Function）取出来真跑一遍，让内部的 {@code tx.run(...)} 与结果映射真实发生；
 * {@code Result.list(Function)} 同理。记录字段值用真实的 {@link Values} 构造而非逐方法 mock，
 * 使 {@code asString/asInt/asList} 的语义与生产一致。
 */
@ExtendWith(MockitoExtension.class)
class Neo4jKgServiceTest {

    @Mock ContentStore store;
    @Mock Driver driver;
    @Mock KgSearchService fallback;
    @Mock Session session;
    /** 驱动 5.x 的 TransactionCallback 收到的是 TransactionContext（与 Transaction 是平行接口，非父子），
     *  二者同承 QueryRunner，故 run(...) 的用法完全一致 */
    @Mock TransactionContext tx;

    private Neo4jKgService service;

    /** 已执行的 Cypher 语句，供断言"确实发了预期的查询" */
    private final List<String> executed = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new Neo4jKgService(store, driver, fallback, "neo4j", 60_000L);
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    /** 内容侧最小空集：synchronize() 会读这五个方法，不 stub 会 NPE */
    private void stubEmptyContent() {
        lenient().when(store.version()).thenReturn(7L);
        lenient().when(store.distortions()).thenReturn(List.of());
        lenient().when(store.techniques()).thenReturn(List.of());
        lenient().when(store.psyTopics()).thenReturn(List.of());
        lenient().when(store.commCases()).thenReturn(List.of());
        lenient().when(store.stressorMap()).thenReturn(Map.of());
    }

    private static Record rec(Map<String, Value> fields) {
        Record r = mock(Record.class);
        lenient().when(r.get(anyString())).thenAnswer(inv -> {
            Value v = fields.get(inv.<String>getArgument(0));
            return v == null ? Values.NULL : v;
        });
        return r;
    }

    private static Map<String, Value> fields(Object... kv) {
        Map<String, Value> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], Values.value(kv[i + 1]));
        }
        return m;
    }

    /**
     * Result.list 必须真跑传入的映射函数，否则记录→record 的转换代码全不计入覆盖；
     * single() 空集时返回 null 会让 NPE，故由调用方保证有值。
     */
    @SuppressWarnings("unchecked")
    private static Result resultOf(List<Record> records) {
        Result res = mock(Result.class);
        lenient().when(res.list(any())).thenAnswer(inv -> {
            Function<Record, Object> f = inv.getArgument(0);
            return records.stream().map(f).toList();
        });
        lenient().when(res.single()).thenReturn(records.isEmpty() ? null : records.get(0));
        return res;
    }

    /**
     * 安装两个工作单元转发：<b>必须用 {@code doAnswer(...).when(...)} 而非 {@code when(...).thenAnswer(...)}</b>。
     * 后者在"同一测试内重复 stub 同一方法"时，第二次的 {@code any()} 实参会先命中第一次注册的 answer，
     * 于是 answer 拿到 null 并抛 NPE；前者只在真实调用时才执行 answer，天然规避这一点。
     * 两个方法未必都被走到（纯读路径只过 executeRead），故用 lenient 声明，避免被 strict stubs 判为多余。
     */
    @SuppressWarnings("unchecked")
    private void installWork() {
        lenient().doAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).execute(tx))
                .when(session).executeRead(any());
        lenient().doAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).execute(tx))
                .when(session).executeWrite(any());
    }

    /** 所有查询都返回同一份结果；单参与双参两个 run 重载未必都被走到，故 lenient 声明 */
    private void stubSession(Result result) {
        lenient().when(driver.session(any(SessionConfig.class))).thenReturn(session);
        lenient().when(tx.run(anyString())).thenAnswer(inv -> {
            executed.add(inv.getArgument(0));
            return result;
        });
        lenient().when(tx.run(anyString(), any(Value.class))).thenAnswer(inv -> {
            executed.add(inv.getArgument(0));
            return result;
        });
        installWork();
    }

    /** 按 Cypher 片段分派结果，供 audit() 这种一条链路发多条不同查询的场景使用 */
    private void stubSessionByQuery(Map<String, Result> byFragment, Result fallbackResult) {
        lenient().when(driver.session(any(SessionConfig.class))).thenReturn(session);
        lenient().when(tx.run(anyString()))
                .thenAnswer(inv -> pick(inv.getArgument(0), byFragment, fallbackResult));
        lenient().when(tx.run(anyString(), any(Value.class)))
                .thenAnswer(inv -> pick(inv.getArgument(0), byFragment, fallbackResult));
        installWork();
    }

    private Result pick(String query, Map<String, Result> byFragment, Result fallbackResult) {
        executed.add(query);
        for (var e : byFragment.entrySet()) {
            if (query.contains(e.getKey())) return e.getValue();
        }
        return fallbackResult;
    }

    // ─────────────────────────── 召回成功路径（字段映射） ───────────────────────────

    @Test
    @DisplayName("distortionsFor：模板非空时首条模板同时落到单值字段与列表字段")
    void distortionsForMapsTemplates() {
        stubEmptyContent();
        stubSession(resultOf(List.of(rec(fields(
                "id", "d1", "name", "灾难化", "definition", "总往最坏处想",
                "sig", "要是搞砸了怎么办", "templates", List.of("t1", "t2"))))));

        var out = service.distortionsFor("焦虑", List.of(), 3);

        assertEquals(1, out.size());
        assertEquals("d1", out.get(0).kgNodeId());
        assertEquals("灾难化", out.get(0).name());
        assertEquals("总往最坏处想", out.get(0).definition());
        assertEquals("t1", out.get(0).socraticTemplate(), "单模板字段取列表首条");
        assertEquals(List.of("t1", "t2"), out.get(0).socraticTemplates());
    }

    @Test
    @DisplayName("distortionsFor：模板为空时单值字段回落空串，不留 null")
    void distortionsForEmptyTemplatesFallBackToBlank() {
        stubEmptyContent();
        stubSession(resultOf(List.of(rec(fields(
                "id", "d2", "name", "以偏概全", "definition", "d", "sig", "s",
                "templates", List.of())))));

        var out = service.distortionsFor("焦虑", List.of(), 3);

        assertEquals("", out.get(0).socraticTemplate());
        assertTrue(out.get(0).socraticTemplates().isEmpty());
    }

    @Test
    @DisplayName("distortionsFor：templates 字段整体缺失（NULL）按空列表处理")
    void distortionsForMissingTemplatesField() {
        stubEmptyContent();
        stubSession(resultOf(List.of(rec(fields("id", "d3", "name", "n", "definition", "d", "sig", "s")))));

        var out = service.distortionsFor("焦虑", List.of(), 3);

        assertTrue(out.get(0).socraticTemplates().isEmpty());
    }

    @Test
    @DisplayName("stressorsOf：列表中的 null 元素被过滤，不污染候选集")
    void strListFiltersNullElements() {
        stubEmptyContent();
        stubSession(resultOf(List.of(rec(fields(
                "id", "d4", "name", "n", "definition", "d", "sig", "s",
                "templates", Arrays.asList("t1", null))))));

        var out = service.distortionsFor("焦虑", List.of(), 3);

        assertEquals(List.of("t1"), out.get(0).socraticTemplates());
    }

    @Test
    @DisplayName("distortionsFor：事件标签为 null 时按空列表处理，不 NPE")
    void distortionsForNullTags() {
        stubEmptyContent();
        stubSession(resultOf(List.of()));

        assertTrue(service.distortionsFor("焦虑", null, 3).isEmpty());
    }

    @Test
    @DisplayName("psyTopicsFor：readingSec 缺失时回落到默认 40 秒")
    void psyTopicsForDefaultsReadingSec() {
        stubEmptyContent();
        stubSession(resultOf(List.of(rec(fields(
                "id", "p1", "title", "什么是焦虑", "summary", "s", "microAction", "深呼吸",
                "aboutTags", List.of("考试"))))));

        var out = service.psyTopicsFor(List.of("考试压力"), "焦虑", 2);

        assertEquals(1, out.size());
        assertEquals("p1", out.get(0).kgNodeId());
        assertEquals(List.of("考试"), out.get(0).aboutTags());
        assertEquals(40, out.get(0).readingSec(), "缺失字段走 asInt(40) 默认值分支");
    }

    @Test
    @DisplayName("psyTopicsFor：显式 readingSec 覆盖默认值；压力源为 null 时不 NPE")
    void psyTopicsForExplicitReadingSecAndNullStressors() {
        stubEmptyContent();
        stubSession(resultOf(List.of(rec(fields(
                "id", "p2", "title", "t", "summary", "s", "microAction", "m",
                "aboutTags", List.of(), "readingSec", 75)))));

        var out = service.psyTopicsFor(null, null, 2);

        assertEquals(75, out.get(0).readingSec());
    }

    @Test
    @DisplayName("psyTopic：命中返回 Optional.of，未命中返回 Optional.empty")
    void psyTopicHitAndMiss() {
        stubEmptyContent();
        stubSession(resultOf(List.of(rec(fields(
                "id", "p3", "title", "t", "summary", "s", "microAction", "m",
                "aboutTags", List.of(), "readingSec", 30)))));
        assertTrue(service.psyTopic("p3").isPresent());

        stubSession(resultOf(List.of()));
        assertTrue(service.psyTopic("nobody").isEmpty());
    }

    @Test
    @DisplayName("casesFor：八个字段全量映射；distortionIds 为 null 时按空列表处理")
    void casesForMapsAllFields() {
        stubEmptyContent();
        stubSession(resultOf(List.of(rec(fields(
                "id", "c1", "title", "被误解时", "scene", "宿舍", "situation", "被室友误会",
                "unhelpful", "冷战", "helpful", "先问一句",
                "distortionRefs", List.of("d1"), "techniqueRefs", List.of("t1"))))));

        var out = service.casesFor("宿舍", null, 3);

        assertEquals(1, out.size());
        assertEquals("c1", out.get(0).kgNodeId());
        assertEquals("宿舍", out.get(0).scene());
        assertEquals("先问一句", out.get(0).helpful());
        assertEquals(List.of("d1"), out.get(0).distortionRefs());
        assertEquals(List.of("t1"), out.get(0).techniqueRefs());
    }

    @Test
    @DisplayName("stressorsFor：按输入标签顺序去重保序，未映射标签与重复值都不进结果")
    void stressorsForKeepsInputOrderAndDedupes() {
        stubEmptyContent();
        stubSession(resultOf(List.of(
                rec(fields("tag", "t1", "stressor", "学业压力")),
                rec(fields("tag", "t2", "stressor", "人际压力")))));

        var out = service.stressorsFor(List.of("t2", "t1", "t2", "未映射标签"));

        assertEquals(List.of("人际压力", "学业压力"), out, "按输入标签顺序，且同一压力源只出现一次");
    }

    @Test
    @DisplayName("stressorsFor：标签为 null 时按空列表处理")
    void stressorsForNullTags() {
        stubEmptyContent();
        stubSession(resultOf(List.of()));

        assertTrue(service.stressorsFor(null).isEmpty());
    }

    @Test
    @DisplayName("stressorOptions：直接透传图侧 name 列")
    void stressorOptionsPassThrough() {
        stubEmptyContent();
        stubSession(resultOf(List.of(rec(fields("name", "学业压力")), rec(fields("name", "人际压力")))));

        assertEquals(List.of("学业压力", "人际压力"), service.stressorOptions());
    }

    @Test
    @DisplayName("psyTopicExists / commCaseExists：true 与 false 两条分支都走通")
    void existsQueries() {
        stubEmptyContent();
        stubSession(resultOf(List.of(rec(fields("ex", true)))));
        assertTrue(service.psyTopicExists("p1"));
        assertTrue(service.commCaseExists("c1"));

        stubSession(resultOf(List.of(rec(fields("ex", false)))));
        assertFalse(service.psyTopicExists("p2"));
        assertFalse(service.commCaseExists("c2"));
    }

    // ─────────────────────────── 同步 ───────────────────────────

    @Test
    @DisplayName("synchronize：五类内容非空时逐条发出重建语句，含闭集情绪合并")
    void synchronizeEmitsAllStatements() {
        when(store.version()).thenReturn(11L);
        when(store.distortions()).thenReturn(List.of(new ContentStore.DistortionEntry(
                "d1", "灾难化", "def", "sig", List.of("t1"), List.of("焦虑"))));
        when(store.techniques()).thenReturn(List.of(new ContentStore.TechEntry(
                "k1", "深呼吸", "desc", "紧张时", List.of("紧张"), List.of("吸气", "呼气"))));
        when(store.psyTopics()).thenReturn(List.of(new ContentStore.PsyEntry(
                "p1", "标题", "摘要", "微行动", List.of("考试"), 45)));
        when(store.commCases()).thenReturn(List.of(new ContentStore.CaseEntry(
                "c1", "标题", "宿舍", "情境", "无益", "有益", List.of("d1"), List.of("k1"), List.of("人际"))));
        when(store.stressorMap()).thenReturn(Map.of("考试", "学业压力"));
        stubSession(resultOf(List.of()));

        service.forceSync();

        assertEquals(11L, service.syncedVersion());
        assertTrue(executed.stream().anyMatch(q -> q.contains("DETACH DELETE")), "先清空受管节点");
        assertTrue(executed.stream().anyMatch(q -> q.contains("MERGE (:Emotion")), "闭集情绪补缺");
        assertTrue(executed.stream().anyMatch(q -> q.contains("MERGE (d:CognitiveDistortion")), "误区");
        assertTrue(executed.stream().anyMatch(q -> q.contains("MERGE (t:StrengthTechnique")), "技巧");
        assertTrue(executed.stream().anyMatch(q -> q.contains("MERGE (p:PsyTopic")), "科普");
        assertTrue(executed.stream().anyMatch(q -> q.contains("MERGE (c:CommCase")), "沟通案例");
        assertTrue(executed.stream().anyMatch(q -> q.contains("MERGE (t:EventTag")), "事件标签与压力源映射");
    }

    @Test
    @DisplayName("synchronize：内容全空时只清空受管节点，不发空参数的 MERGE")
    void synchronizeWithEmptyContentSkipsStatements() {
        stubEmptyContent();
        stubSession(resultOf(List.of()));

        service.forceSync();

        assertEquals(1, executed.size(), "仅 CLEAR_MANAGED 一条");
        assertTrue(executed.get(0).contains("DETACH DELETE"));
    }

    @Test
    @DisplayName("ensureSynced：版本号未变时直接短路，不重复建图")
    void ensureSyncedShortCircuitsOnSameVersion() {
        stubEmptyContent();
        stubSession(resultOf(List.of()));
        service.forceSync();
        int before = executed.size();

        service.distortionsFor("焦虑", List.of(), 3);

        assertEquals(1, before, "forceSync 在空内容下只发一条清空语句");
        // 判据落在"有没有再建图"上：distortionsFor 会先无条件解一次压力源映射，故不能用总条数卡
        long clears = executed.stream().filter(q -> q != null && q.contains("DETACH DELETE")).count();
        assertEquals(1, clears, "版本未变 → 不复跑 synchronize");
        assertTrue(executed.stream().anyMatch(q -> q != null && q.contains("MAPS_TO")),
                "确实走到了图侧压力源映射");
        assertTrue(executed.get(executed.size() - 1).contains("MATCH (d:CognitiveDistortion)"),
                "最后发出的是召回查询本身");
    }

    @Test
    @DisplayName("ensureSynced：读版本号抛错时如实回落 DB，不向外抛异常")
    void ensureSyncedFallsBackWhenVersionReadFails() {
        when(store.version()).thenThrow(new IllegalStateException("db down"));
        when(fallback.distortionsFor(anyString(), anyList(), anyInt())).thenReturn(List.of());

        assertTrue(service.distortionsFor("焦虑", List.of(), 3).isEmpty());
        verify(fallback, times(1)).distortionsFor(anyString(), anyList(), anyInt());
        verify(driver, times(0)).session(any(SessionConfig.class));
    }

    @Test
    @DisplayName("ensureSynced：建图抛错时回落 DB，且失败会被记入熔断窗口")
    void ensureSyncedFallsBackWhenSyncFails() {
        when(store.version()).thenReturn(3L);
        when(store.distortions()).thenThrow(new RuntimeException("graph write failed"));
        when(fallback.distortionsFor(anyString(), anyList(), anyInt())).thenReturn(List.of());

        assertTrue(service.distortionsFor("焦虑", List.of(), 3).isEmpty());
        assertTrue(service.describe().contains("circuit=open"), "同步失败也要开熔断，避免每条链路反复付建连代价");
    }

    // ─────────────────────────── 熔断与降级 ───────────────────────────

    @Test
    @DisplayName("查询抛错：回落 DB 并开熔断，下一次直接走 DB 不再建连")
    void graphQueryFailureTripsCircuit() {
        stubEmptyContent();
        lenient().when(driver.session(any(SessionConfig.class))).thenReturn(session);
        lenient().when(tx.run(anyString())).thenThrow(new RuntimeException("connection reset"));
        lenient().when(tx.run(anyString(), any(Value.class))).thenThrow(new RuntimeException("connection reset"));
        installWork();
        when(fallback.psyTopicsFor(anyList(), any(), anyInt())).thenReturn(List.of());

        assertTrue(service.psyTopicsFor(List.of(), "焦虑", 2).isEmpty());
        assertTrue(service.describe().contains("circuit=open"));

        // 熔断窗口内第二次调用：直接 fail-fast 走 DB
        assertTrue(service.psyTopicsFor(List.of(), "焦虑", 2).isEmpty());
        verify(fallback, times(2)).psyTopicsFor(anyList(), any(), anyInt());
    }

    @Test
    @DisplayName("成功即闭合熔断：一次失败后再成功，circuit 回到 closed")
    void successClosesCircuit() {
        stubEmptyContent();
        stubSession(resultOf(List.of()));

        service.distortionsFor("焦虑", List.of(), 3);

        assertTrue(service.describe().contains("circuit=closed"));
    }

    @Test
    @DisplayName("describe：尚未同步时 syncedVersion 回显 none，而非 Long.MIN_VALUE")
    void describeBeforeAnySync() {
        String d = service.describe();

        assertTrue(d.startsWith("kg=neo4j, circuit=closed, syncedVersion=none"), d);
    }

    @Test
    @DisplayName("session()：database 为空时走无参 session() 重载")
    void blankDatabaseUsesDefaultSession() {
        var svc = new Neo4jKgService(store, driver, fallback, "", 60_000L);
        stubEmptyContent();
        // resultOf() 内部会调 mock，必须先在 when(...) 之外算好，否则会被当成嵌套 stubbing
        Result empty = resultOf(List.of());
        when(driver.session()).thenReturn(session);
        lenient().when(tx.run(anyString())).thenReturn(empty);
        lenient().when(tx.run(anyString(), any(Value.class))).thenReturn(empty);
        installWork();

        svc.forceSync();

        verify(driver, times(1)).session();
        verify(driver, times(0)).session(any(SessionConfig.class));
    }

    @Test
    @DisplayName("forceSync：建图失败时原样抛出，供调用方感知（与在线链路不同，它是显式触发）")
    void forceSyncPropagatesFailure() {
        when(store.distortions()).thenThrow(new RuntimeException("boom"));

        assertThrows(RuntimeException.class, () -> service.forceSync());
    }

    @Test
    @DisplayName("managedNodeCount：正常返回计数；图不可达返回 -1 而非抛异常或 0")
    void managedNodeCountDegradesToMinusOne() {
        stubEmptyContent();
        stubSession(resultOf(List.of(rec(fields("c", 42)))));
        assertEquals(42, service.managedNodeCount());

        when(driver.session(any(SessionConfig.class))).thenThrow(new RuntimeException("unreachable"));
        assertEquals(-1, service.managedNodeCount(), "运维需区分“图真的空（0）”与“图读不到（-1）”");
    }

    // ─────────────────────────── G9 对账 ───────────────────────────

    @Test
    @DisplayName("audit：应然与实然完全对应时 consistent=true，闭集多出的词记入 reserved 不判错")
    void auditConsistent() {
        when(store.version()).thenReturn(5L);
        when(store.distortions()).thenReturn(List.of(new ContentStore.DistortionEntry(
                "d1", "灾难化", "def", "sig", List.of("t1"), List.of("焦虑"))));
        when(store.techniques()).thenReturn(List.of());
        when(store.psyTopics()).thenReturn(List.of());
        when(store.commCases()).thenReturn(List.of());
        when(store.stressorMap()).thenReturn(Map.of("考试", "学业压力"));

        Map<String, Result> byFragment = new LinkedHashMap<>();
        byFragment.put("labels(n)", resultOf(List.of(
                rec(fields("id", "d1", "lbls", List.of("CognitiveDistortion", "SvManaged"))))));
        byFragment.put("(n:Emotion)", resultOf(List.of(
                rec(fields("name", "焦虑")), rec(fields("name", "喜悦")))));
        byFragment.put("(n:EventTag)", resultOf(List.of(rec(fields("name", "考试")))));
        byFragment.put("(n:Stressor)", resultOf(List.of(rec(fields("name", "学业压力")))));
        byFragment.put("type(r)", resultOf(List.of(
                rec(fields("t", "TRIGGERS", "c", 1L)), rec(fields("t", "MAPS_TO", "c", 1L)))));
        stubSessionByQuery(byFragment, resultOf(List.of()));

        Map<String, Object> audit = service.audit();

        assertEquals(true, audit.get("available"));
        assertEquals(true, audit.get("consistent"), "焦虑 与 考试/学业压力 两侧对齐");
        assertEquals(5L, audit.get("contentVersion"));
        assertEquals(5L, audit.get("syncedVersion"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) audit.get("nodes");
        Map<String, Object> emotionRow = nodes.stream()
                .filter(r -> "Emotion".equals(r.get("label"))).findFirst().orElseThrow();
        assertEquals("closed-set", emotionRow.get("kind"));
        assertTrue(((List<?>) emotionRow.get("missing")).isEmpty());
        assertEquals(List.of(), emotionRow.get("extra"), "闭集不报 extra");
        assertEquals(List.of("喜悦"), emotionRow.get("reserved"), "闭集多出的词记 reserved");
        assertEquals(2, emotionRow.get("actual"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rels = (List<Map<String, Object>>) audit.get("relations");
        assertTrue(rels.stream().anyMatch(r -> "TRIGGERS".equals(r.get("type"))
                && Long.valueOf(1L).equals(r.get("expect")) && Long.valueOf(1L).equals(r.get("actual"))));
    }

    @Test
    @DisplayName("audit：受管节点缺失与多余都判为漂移，关系计数不符同样判漂移")
    void auditDetectsDrift() {
        when(store.version()).thenReturn(9L);
        when(store.distortions()).thenReturn(List.of(
                new ContentStore.DistortionEntry("d1", "灾难化", "def", "sig", List.of(), List.of("焦虑")),
                new ContentStore.DistortionEntry("d2", "以偏概全", "def", "sig", List.of(), List.of())));
        when(store.techniques()).thenReturn(List.of());
        when(store.psyTopics()).thenReturn(List.of());
        when(store.commCases()).thenReturn(List.of());
        when(store.stressorMap()).thenReturn(Map.of());

        Map<String, Result> byFragment = new LinkedHashMap<>();
        // 图侧只有 d1（缺 d2），且多出一个未登记的 d9
        byFragment.put("labels(n)", resultOf(List.of(
                rec(fields("id", "d1", "lbls", List.of("CognitiveDistortion", "SvManaged"))),
                rec(fields("id", "d9", "lbls", List.of("CognitiveDistortion", "SvManaged"))),
                rec(fields("id", "x", "lbls", List.of("Unknown"))))));
        byFragment.put("(n:Emotion)", resultOf(List.of(rec(fields("name", "焦虑")))));
        byFragment.put("(n:EventTag)", resultOf(List.of()));
        byFragment.put("(n:Stressor)", resultOf(List.of()));
        // TRIGGERS 期望 1，实给 0 制造关系漂移
        byFragment.put("type(r)", resultOf(List.of(rec(fields("t", "TRIGGERS", "c", 0L)))));
        stubSessionByQuery(byFragment, resultOf(List.of()));

        Map<String, Object> audit = service.audit();

        assertEquals(false, audit.get("consistent"));
        assertTrue(((String) audit.get("note")).startsWith("存在漂移"), "漂移态的说明文案");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) audit.get("nodes");
        Map<String, Object> distortionRow = nodes.stream()
                .filter(r -> "CognitiveDistortion".equals(r.get("label"))).findFirst().orElseThrow();
        assertEquals("managed", distortionRow.get("kind"));
        assertEquals(List.of("d2"), distortionRow.get("missing"));
        assertEquals(List.of("d9"), distortionRow.get("extra"));
        assertEquals(List.of(), distortionRow.get("reserved"), "受管行不产出 reserved");
    }

    @Test
    @DisplayName("audit：闭集节点名为 NULL 的行被过滤，不产生空名条目")
    void auditFiltersNullClosedSetNames() {
        when(store.version()).thenReturn(1L);
        when(store.distortions()).thenReturn(List.of());
        when(store.techniques()).thenReturn(List.of());
        when(store.psyTopics()).thenReturn(List.of());
        when(store.commCases()).thenReturn(List.of());
        when(store.stressorMap()).thenReturn(Map.of());

        Map<String, Result> byFragment = new LinkedHashMap<>();
        byFragment.put("labels(n)", resultOf(List.of()));
        byFragment.put("(n:Emotion)", resultOf(List.of(rec(fields()), rec(fields("name", "焦虑")))));
        byFragment.put("(n:EventTag)", resultOf(List.of()));
        byFragment.put("(n:Stressor)", resultOf(List.of()));
        byFragment.put("type(r)", resultOf(List.of()));
        stubSessionByQuery(byFragment, resultOf(List.of()));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) service.audit().get("nodes");
        Map<String, Object> emotionRow = nodes.stream()
                .filter(r -> "Emotion".equals(r.get("label"))).findFirst().orElseThrow();

        assertEquals(1, emotionRow.get("actual"), "NULL 名不入计数");
    }

    @Test
    @DisplayName("audit：同步失败时如实回 available=false，不编造一致结论")
    void auditUnavailableWhenSyncFails() {
        when(store.version()).thenReturn(5L);
        when(store.distortions()).thenThrow(new RuntimeException("graph write failed"));

        Map<String, Object> audit = service.audit();

        assertEquals(false, audit.get("available"));
        assertNull(audit.get("consistent"), "未对账就不该给出一致性结论");
        assertEquals(5L, audit.get("contentVersion"));
        assertEquals("none", audit.get("syncedVersion"));
        assertTrue(((String) audit.get("note")).startsWith("未对账"));
    }

    @Test
    @DisplayName("audit：版本号已同步但图此刻查询抛错，仍须回 available=false（真机三态实测的坑）")
    void auditUnavailableWhenGraphQueryFails() {
        stubEmptyContent();
        stubSession(resultOf(List.of()));
        service.forceSync();          // 先让同步成功，构造"版本号已同步、图却掉了"的状态

        when(driver.session(any(SessionConfig.class))).thenThrow(new RuntimeException("graph unreachable"));

        Map<String, Object> audit = service.audit();

        assertEquals(false, audit.get("available"),
                "ensureSynced 短路返回 true 只代表版本号同步过，不代表图可达");
        assertEquals(7L, audit.get("syncedVersion"), "已同步过的版本号如实回显，不伪装成 none");
    }

    @Test
    @DisplayName("audit：同步成功后的 syncedVersion 以数字回显")
    void auditReportsSyncedVersionNumber() {
        when(store.version()).thenReturn(5L);
        when(store.distortions()).thenReturn(List.of());
        when(store.techniques()).thenReturn(List.of());
        when(store.psyTopics()).thenReturn(List.of());
        when(store.commCases()).thenReturn(List.of());
        when(store.stressorMap()).thenReturn(Map.of());
        stubSession(resultOf(List.of()));

        Map<String, Object> audit = service.audit();

        assertEquals(true, audit.get("available"));
        assertEquals(5L, audit.get("syncedVersion"));
        assertEquals(true, audit.get("consistent"), "两侧都空 → 一致");
    }
}
