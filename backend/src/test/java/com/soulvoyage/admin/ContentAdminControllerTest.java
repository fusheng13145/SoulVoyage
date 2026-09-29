package com.soulvoyage.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.audit.AuditService;
import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.domain.content.ContentStore;
import com.soulvoyage.domain.content.ExerciseLibraryEntity;
import com.soulvoyage.domain.content.ExerciseLibraryRepository;
import com.soulvoyage.domain.content.KgNodeEntity;
import com.soulvoyage.domain.content.KgNodeRepository;
import com.soulvoyage.domain.content.SceneCardEntity;
import com.soulvoyage.domain.content.SceneCardRepository;
import com.soulvoyage.kg.Neo4jKgService;
import com.soulvoyage.llm.LlmClient;
import com.soulvoyage.llm.PromptTemplates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * N4 内容管理端写口的分支覆盖：三类内容 upsert（新建校验 / 已存在增量合并 / 缺字段拒绝）、
 * persona 部分合并与损坏数据的收口、G9 图对账在「未启用」与「已启用」两态的真实回答，
 * 以及 A3 预览试跑的模板名校验与模板缺失收口。
 *
 * <p>写成功一律 bump 版本 + 落审计，这条链路每个写口都要各测一次，不能只测其中一个。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContentAdminControllerTest {

    @Mock KgNodeRepository kgRepo;
    @Mock SceneCardRepository sceneRepo;
    @Mock ExerciseLibraryRepository exerciseRepo;
    @Mock ContentStore store;
    @Mock AuditService audit;
    @Mock PromptTemplates prompts;
    @Mock LlmClient llm;
    @Mock ObjectProvider<Neo4jKgService> neo4jKg;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AuthPrincipal admin = new AuthPrincipal(1L, "ADMIN");

    private ContentAdminController controller;

    @BeforeEach
    void setUp() {
        controller = new ContentAdminController(kgRepo, sceneRepo, exerciseRepo, store, audit,
                mapper, prompts, llm, neo4jKg);
        when(store.bump()).thenReturn(42L);
    }

    private ObjectNode obj(String json) throws Exception {
        return (ObjectNode) mapper.readTree(json);
    }

    /** 数组/标量 JSON 用这个，不强制转 ObjectNode */
    private JsonNode node(String json) throws Exception {
        return mapper.readTree(json);
    }

    // ---------------- 读 ----------------

    @Test
    @DisplayName("listKg：不带 type 时列出全部，带 type 时只列上架行")
    void listKgFiltersByType() {
        KgNodeEntity a = new KgNodeEntity();
        a.setCode("d1");
        when(kgRepo.findAll()).thenReturn(List.of(a));
        when(kgRepo.findByTypeAndStatus("DISTORTION", (short) 1)).thenReturn(List.of(a));

        assertEquals(1, controller.listKg(null).getData().size());
        assertEquals(1, controller.listKg("   ").getData().size());
        assertEquals(1, controller.listKg("DISTORTION").getData().size());

        verify(kgRepo, never()).findByTypeAndStatus(eq(""), any());
    }

    @Test
    @DisplayName("listScenes / listExercises：管理视角返回全量含下架")
    void listScenesAndExercisesReturnAll() {
        when(sceneRepo.findAll()).thenReturn(List.of(new SceneCardEntity()));
        when(exerciseRepo.findAll()).thenReturn(List.of(new ExerciseLibraryEntity()));

        assertEquals(1, controller.listScenes().getData().size());
        assertEquals(1, controller.listExercises().getData().size());
    }

    @Test
    @DisplayName("kgGraphAudit：图未启用时如实回 available=false，不假装一致")
    void kgGraphAuditReportsDisabledGraph() {
        when(neo4jKg.getIfAvailable()).thenReturn(null);

        Map<String, Object> data = controller.kgGraphAudit().getData();

        assertEquals(false, data.get("available"));
        assertTrue(String.valueOf(data.get("reason")).contains("soulvoyage.neo4j.enabled=false"));
    }

    @Test
    @DisplayName("kgGraphAudit：图启用时透传审计结果")
    void kgGraphAuditDelegatesWhenEnabled() {
        Neo4jKgService graph = org.mockito.Mockito.mock(Neo4jKgService.class);
        when(neo4jKg.getIfAvailable()).thenReturn(graph);
        when(graph.audit()).thenReturn(Map.of("available", true, "drift", 0));

        Map<String, Object> data = controller.kgGraphAudit().getData();

        assertEquals(true, data.get("available"));
        assertEquals(0, data.get("drift"));
    }

    // ---------------- upsertKg ----------------

    @Test
    @DisplayName("upsertKg：已存在的节点做增量更新，type 为 null 时保留原类型")
    void upsertKgUpdatesExistingNode() throws Exception {
        KgNodeEntity exist = new KgNodeEntity();
        exist.setCode("d1");
        exist.setType("DISTORTION");
        exist.setName("旧名");
        exist.setPayloadJson("{}");
        when(kgRepo.findByCode("d1")).thenReturn(Optional.of(exist));

        ContentAdminController.KgBody body = new ContentAdminController.KgBody(
                null, "灾难化", obj("{\"definition\":\"x\"}"), (short) 2);
        Map<String, Object> data = controller.upsertKg(admin, "d1", body).getData();

        assertEquals("DISTORTION", exist.getType());
        assertEquals("灾难化", exist.getName());
        assertEquals("{\"definition\":\"x\"}", exist.getPayloadJson());
        assertEquals((short) 2, exist.getStatus());
        assertEquals("d1", data.get("code"));
        assertEquals(42L, data.get("contentVersion"));
        assertEquals(true, data.get("effective"));
        verify(kgRepo).save(exist);
        verify(audit).record(1L, "KG_UPSERT", "content:d1", null);
    }

    @Test
    @DisplayName("upsertKg：新建节点必须有合法 type 与 name/payload")
    void upsertKgCreatesNewNodeWithValidType() throws Exception {
        when(kgRepo.findByCode("d9")).thenReturn(Optional.empty());

        ContentAdminController.KgBody body = new ContentAdminController.KgBody(
                "PSY_TOPIC", "情绪是什么", obj("{\"summary\":\"s\"}"), null);
        controller.upsertKg(admin, "d9", body);

        ArgumentCaptor<KgNodeEntity> c = ArgumentCaptor.forClass(KgNodeEntity.class);
        verify(kgRepo).save(c.capture());
        assertEquals("d9", c.getValue().getCode());
        assertEquals("PSY_TOPIC", c.getValue().getType());
    }

    @Test
    @DisplayName("upsertKg：新建时缺 type 或 type 未知一律拒绝")
    void upsertKgRejectsNewNodeWithoutValidType() throws Exception {
        when(kgRepo.findByCode("d9")).thenReturn(Optional.empty());

        BizException missing = assertThrows(BizException.class, () -> controller.upsertKg(admin, "d9",
                new ContentAdminController.KgBody(null, "n", obj("{}"), null)));
        assertEquals(ErrorCode.BAD_PARAMS, missing.getErrorCode());

        BizException unknown = assertThrows(BizException.class, () -> controller.upsertKg(admin, "d9",
                new ContentAdminController.KgBody("NOT_A_TYPE", "n", obj("{}"), null)));
        assertEquals(ErrorCode.BAD_PARAMS, unknown.getErrorCode());
    }

    @Test
    @DisplayName("upsertKg：已存在节点改成未知类型同样拒绝")
    void upsertKgRejectsUnknownTypeOnExistingNode() throws Exception {
        KgNodeEntity exist = new KgNodeEntity();
        exist.setCode("d1");
        exist.setType("DISTORTION");
        exist.setName("n");
        exist.setPayloadJson("{}");
        when(kgRepo.findByCode("d1")).thenReturn(Optional.of(exist));

        BizException ex = assertThrows(BizException.class, () -> controller.upsertKg(admin, "d1",
                new ContentAdminController.KgBody("BOGUS", null, null, null)));

        assertEquals(ErrorCode.BAD_PARAMS, ex.getErrorCode());
    }

    @Test
    @DisplayName("upsertKg：合并后仍缺 name 或 payload 时拒绝落库")
    void upsertKgRejectsIncompleteNode() throws Exception {
        when(kgRepo.findByCode("d9")).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> controller.upsertKg(admin, "d9",
                new ContentAdminController.KgBody("DISTORTION", null, obj("{}"), null)));

        assertEquals(ErrorCode.BAD_PARAMS, ex.getErrorCode());
        verify(kgRepo, never()).save(any());
    }

    // ---------------- upsertScene ----------------

    @Test
    @DisplayName("upsertScene：新建场景卡补齐默认考察维度与回合上限")
    void upsertSceneCreatesWithDefaults() {
        when(sceneRepo.findByCode("sc_new")).thenReturn(Optional.empty());

        ContentAdminController.SceneBody body = new ContentAdminController.SceneBody(
                "宿舍冲突", "和室友的边界", List.of("MILD", "HARD"), null, null, null, null, null, null,
                List.of("沟通"), List.of("人际冲突"), null);

        controller.upsertScene(admin, "sc_new", body);

        ArgumentCaptor<SceneCardEntity> c = ArgumentCaptor.forClass(SceneCardEntity.class);
        verify(sceneRepo).save(c.capture());
        assertEquals("MILD,HARD", c.getValue().getDifficulties());
        assertEquals("沟通", c.getValue().getTags());
        assertEquals("人际冲突", c.getValue().getRecommendedFor());
        assertEquals("[]", c.getValue().getGoalDimensions());
        assertEquals(20, c.getValue().getMaxTurns());
        assertEquals("宿舍冲突", c.getValue().getTitle());
    }

    @Test
    @DisplayName("upsertScene：新建缺 title/description 时拒绝")
    void upsertSceneRejectsIncompleteNewCard() {
        when(sceneRepo.findByCode("sc_new")).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> controller.upsertScene(admin, "sc_new",
                new ContentAdminController.SceneBody(null, null, null, null, null, null, null, null, null,
                        null, null, null)));

        assertEquals(ErrorCode.BAD_PARAMS, ex.getErrorCode());
    }

    @Test
    @DisplayName("upsertScene：persona 只合并传入字段，其余原样保留")
    void upsertSceneMergesPersonaPartially() throws Exception {
        SceneCardEntity exist = new SceneCardEntity();
        exist.setCode("sc1");
        exist.setTitle("旧标题");
        exist.setDescription("旧描述");
        exist.setPersonaJson("{\"npcName\":\"旧 NPC\",\"relation\":\"室友\",\"persona\":{\"tone\":\"hard\"}}");
        when(sceneRepo.findByCode("sc1")).thenReturn(Optional.of(exist));

        ContentAdminController.SceneBody body = new ContentAdminController.SceneBody(
                null, null, null, "新 NPC", null, null, List.of("LISTEN", "BOUNDARY"), 30, null, null, null, null);

        controller.upsertScene(admin, "sc1", body);

        ObjectNode persona = obj(exist.getPersonaJson());
        assertEquals("新 NPC", persona.path("npcName").asText());
        assertEquals("室友", persona.path("relation").asText(), "未传入的字段必须保留原值");
        assertEquals("hard", persona.path("persona").path("tone").asText());
        assertEquals("旧标题", exist.getTitle());
        assertEquals(30, exist.getMaxTurns());
        assertEquals("[\"LISTEN\",\"BOUNDARY\"]", exist.getGoalDimensions());
    }

    @Test
    @DisplayName("upsertScene：已有 persona 数据损坏时收口为可读错误")
    void upsertSceneRejectsBrokenPersonaJson() {
        SceneCardEntity exist = new SceneCardEntity();
        exist.setCode("sc1");
        exist.setTitle("t");
        exist.setDescription("d");
        exist.setPersonaJson("{ 这不是 JSON");
        when(sceneRepo.findByCode("sc1")).thenReturn(Optional.of(exist));

        BizException ex = assertThrows(BizException.class, () -> controller.upsertScene(admin, "sc1",
                new ContentAdminController.SceneBody(null, null, null, "n", null, null, null, null, null,
                        null, null, null)));

        assertEquals(ErrorCode.BAD_PARAMS, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("场景卡内部数据损坏"));
    }

    // ---------------- upsertExercise ----------------

    @Test
    @DisplayName("upsertExercise：编码必须是小写 ex_ 前缀")
    void upsertExerciseRejectsBadCode() throws Exception {
        BizException ex = assertThrows(BizException.class, () -> controller.upsertExercise(admin, "EX_BAD",
                new ContentAdminController.ExerciseBody("n", List.of("焦虑"), node("[]"), 5, null)));

        assertEquals(ErrorCode.BAD_PARAMS, ex.getErrorCode());
    }

    @Test
    @DisplayName("upsertExercise：新建缺 name/applyEmotions/steps 时拒绝")
    void upsertExerciseRejectsIncompleteNewRecord() {
        when(exerciseRepo.findByCode("ex_new")).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> controller.upsertExercise(admin, "ex_new",
                new ContentAdminController.ExerciseBody("只有名字", null, null, null, null)));

        assertEquals(ErrorCode.BAD_PARAMS, ex.getErrorCode());
        verify(exerciseRepo, never()).save(any());
    }

    @Test
    @DisplayName("upsertExercise：正常写入并 bump 版本、落审计")
    void upsertExercisePersistsAndBumps() throws Exception {
        when(exerciseRepo.findByCode("ex_478_breath")).thenReturn(Optional.empty());

        ContentAdminController.ExerciseBody body = new ContentAdminController.ExerciseBody(
                "4-7-8 呼吸", List.of("焦虑", "愤怒"), node("[{\"step\":\"吸气\"}]"), 3, (short) 1);

        Map<String, Object> data = controller.upsertExercise(admin, "ex_478_breath", body).getData();

        ArgumentCaptor<ExerciseLibraryEntity> c = ArgumentCaptor.forClass(ExerciseLibraryEntity.class);
        verify(exerciseRepo).save(c.capture());
        assertEquals("4-7-8 呼吸", c.getValue().getName());
        assertEquals("[\"焦虑\",\"愤怒\"]", c.getValue().getApplyEmotions());
        assertEquals("[{\"step\":\"吸气\"}]", c.getValue().getStepsJson());
        assertEquals((short) 3, c.getValue().getDurationMin());
        assertEquals(42L, data.get("contentVersion"));
        verify(audit).record(1L, "EXERCISE_UPSERT", "content:ex_478_breath", null);
    }

    // ---------------- refresh / preview ----------------

    @Test
    @DisplayName("refresh：强制刷新返回当前版本号并落审计")
    void refreshBumpsVersion() {
        Map<String, Object> data = controller.refresh(admin).getData();

        assertEquals(42L, data.get("contentVersion"));
        verify(audit).record(1L, "CONTENT_REFRESH", "content:version", null);
    }

    @Test
    @DisplayName("preview：模板名不合法或 user 为空时拒绝")
    void previewRejectsInvalidInput() {
        assertThrows(BizException.class, () -> controller.preview(admin,
                new ContentAdminController.PreviewBody("Bad-Name", "x", null)));
        assertThrows(BizException.class, () -> controller.preview(admin,
                new ContentAdminController.PreviewBody("", "x", null)));
        assertThrows(BizException.class, () -> controller.preview(admin,
                new ContentAdminController.PreviewBody("emotion_v1", "   ", null)));
        assertThrows(BizException.class, () -> controller.preview(admin,
                new ContentAdminController.PreviewBody("emotion_v1", null, null)));
        assertThrows(BizException.class, () -> controller.preview(admin,
                new ContentAdminController.PreviewBody("t".repeat(33), "x", null)));
    }

    @Test
    @DisplayName("preview：模板不存在时收口为 NOT_FOUND 而不是 500")
    void previewReportsMissingTemplate() {
        when(prompts.system("ghost_v1")).thenThrow(new IllegalStateException("no such template"));

        BizException ex = assertThrows(BizException.class, () -> controller.preview(admin,
                new ContentAdminController.PreviewBody("ghost_v1", "文本", null)));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("ghost_v1"));
    }

    @Test
    @DisplayName("preview：vars 为 null 时按空变量渲染，并回传模型用量")
    void previewRendersAndReportsUsage() {
        when(prompts.system("emotion_v1")).thenReturn("SYS");
        when(prompts.render(eq("SYS"), any())).thenReturn("SYS-PLAIN");
        when(llm.chat(any())).thenReturn(new LlmClient.LlmResponse("{\"ok\":true}", "mock-llm-v1", 11, 22, 33L));

        Map<String, Object> data = controller.preview(admin,
                new ContentAdminController.PreviewBody(" emotion_v1 ", "今天很累", null)).getData();

        assertEquals("emotion_v1", data.get("template"));
        assertEquals("SYS-PLAIN", data.get("system"));
        assertEquals("{\"ok\":true}", data.get("output"));
        assertEquals("mock-llm-v1", data.get("model"));
        assertEquals(11, data.get("tokensIn"));
        assertEquals(22, data.get("tokensOut"));
        assertEquals(33L, data.get("costMs"));
        verify(prompts).render("SYS", Map.of());
        verify(audit).record(1L, "CONTENT_PREVIEW", "prompt:emotion_v1", null);
    }

    @Test
    @DisplayName("preview：传入 vars 时按变量渲染")
    void previewRendersWithVars() {
        when(prompts.system("trace_v1")).thenReturn("SYS");
        when(prompts.render(eq("SYS"), any())).thenReturn("SYS-RENDERED");
        when(llm.chat(any())).thenReturn(new LlmClient.LlmResponse("out", "mock-llm-v1", 1, 1, 1L));

        Map<String, Object> data = controller.preview(admin,
                new ContentAdminController.PreviewBody("trace_v1", "文本", Map.of("name", "小岛"))).getData();

        assertEquals("SYS-RENDERED", data.get("system"));
        assertNotNull(data.get("output"));
        assertFalse(String.valueOf(data.get("template")).isBlank());
    }
}
