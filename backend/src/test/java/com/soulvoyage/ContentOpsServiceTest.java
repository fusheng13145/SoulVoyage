package com.soulvoyage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.domain.content.ContentDraftRepository;
import com.soulvoyage.domain.content.ContentOpsService;
import com.soulvoyage.domain.content.ContentRevisionRepository;
import com.soulvoyage.domain.content.ContentStore;
import com.soulvoyage.domain.content.ContentType;
import com.soulvoyage.domain.content.ExerciseLibraryRepository;
import com.soulvoyage.domain.content.KgNodeRepository;
import com.soulvoyage.domain.content.SceneCardRepository;
import com.soulvoyage.domain.content.dto.ContentOpsDtos.DraftView;
import com.soulvoyage.domain.content.dto.ContentOpsDtos.PreviewView;
import com.soulvoyage.domain.content.dto.ContentOpsDtos.PublishView;
import com.soulvoyage.domain.content.dto.ContentOpsDtos.RevisionView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** H-04 内容运营闭环：草稿不生效、预览校验、发布快照、冲突检测、回滚与三类内容 apply。 */
@SpringBootTest
@ActiveProfiles("test")
class ContentOpsServiceTest {

    @Autowired ContentOpsService ops;
    @Autowired KgNodeRepository kgRepo;
    @Autowired SceneCardRepository sceneRepo;
    @Autowired ExerciseLibraryRepository exerciseRepo;
    @Autowired ContentDraftRepository draftRepo;
    @Autowired ContentRevisionRepository revisionRepo;
    @Autowired ContentStore store;
    @Autowired ObjectMapper mapper;

    private final List<String> kgCodes = new ArrayList<>();
    private final List<String> sceneCodes = new ArrayList<>();
    private final List<String> exerciseCodes = new ArrayList<>();

    /** 共享 H2 库：清理本类创建的内容，避免污染其它测试的精确计数断言。 */
    @AfterEach
    void cleanup() {
        kgCodes.forEach(c -> kgRepo.findByCode(c).ifPresent(kgRepo::delete));
        sceneCodes.forEach(c -> sceneRepo.findByCode(c).ifPresent(sceneRepo::delete));
        exerciseCodes.forEach(c -> exerciseRepo.findByCode(c).ifPresent(exerciseRepo::delete));
        for (String c : kgCodes) cleanupHistory(ContentType.KG, c);
        for (String c : sceneCodes) cleanupHistory(ContentType.SCENE, c);
        for (String c : exerciseCodes) cleanupHistory(ContentType.EXERCISE, c);
        store.bump();
    }

    private void cleanupHistory(ContentType type, String code) {
        draftRepo.findByContentTypeAndCode(type.name(), code).ifPresent(draftRepo::delete);
        revisionRepo.findByContentTypeAndCodeOrderByRevisionNoDesc(type.name(), code)
                .forEach(revisionRepo::delete);
    }

    private ObjectNode kgSnapshot(String definition) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "DISTORTION");
        n.put("name", "H04 测试灾难化");
        n.put("status", 1);
        ObjectNode p = n.putObject("payload");
        p.put("definition", definition);
        p.putArray("socraticTemplates").add("最坏结果是什么？");
        p.put("typicalSignature", "完了");
        p.putArray("emotions").add("焦虑");
        return n;
    }

    private JsonNode payloadOf(String code) {
        try {
            return mapper.readTree(kgRepo.findByCode(code).orElseThrow().getPayloadJson());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void draftPreviewPublishAndRollback() {
        String code = "cd_h04_" + System.nanoTime();
        kgCodes.add(code);
        long actor = 1L;

        DraftView draft = ops.saveDraft(actor, ContentType.KG, code, kgSnapshot("v1"), null, "初稿");
        assertTrue(draft.valid());
        assertNull(draft.baseRevisionId());
        assertFalse(kgRepo.findByCode(code).isPresent(), "草稿不应写 live");

        PreviewView preview = ops.preview(ContentType.KG, code);
        assertTrue(preview.valid());
        assertFalse(preview.conflict());

        PublishView first = ops.publishDraft(actor, ContentType.KG, code);
        assertEquals(1, first.revisionNo());
        assertEquals("v1", payloadOf(code).path("definition").asText());
        assertTrue(kgRepo.findByCode(code).isPresent());
        assertThrows(BizException.class, () -> ops.getDraft(ContentType.KG, code), "发布后草稿应删除");

        ops.saveDraft(actor, ContentType.KG, code, kgSnapshot("v2"), null, null);
        PreviewView preview2 = ops.preview(ContentType.KG, code);
        assertFalse(preview2.conflict(), "新草稿应基于最新版本");
        PublishView second = ops.publishDraft(actor, ContentType.KG, code);
        assertEquals(2, second.revisionNo());
        assertEquals("v2", payloadOf(code).path("definition").asText());

        List<RevisionView> revisions = ops.revisions(ContentType.KG, code);
        assertEquals(2, revisions.size());
        RevisionView rev1 = revisions.stream()
                .filter(r -> r.revisionNo() == 1).findFirst().orElseThrow();
        PublishView rollback = ops.rollback(actor, ContentType.KG, code, rev1.id());
        assertEquals(3, rollback.revisionNo());
        assertEquals("v1", payloadOf(code).path("definition").asText());

        List<RevisionView> after = ops.revisions(ContentType.KG, code);
        assertEquals("ROLLBACK", after.get(0).action());
        assertEquals(rev1.id(), after.get(0).sourceRevisionId());
    }

    @Test
    void publishRejectsStaleDraftWithConflict() {
        String code = "cd_h04c_" + System.nanoTime();
        kgCodes.add(code);
        long actor = 1L;
        ops.publishDirect(actor, ContentType.KG, code, kgSnapshot("base"), "KG_UPSERT");
        ops.saveDraft(actor, ContentType.KG, code, kgSnapshot("draft"), null, null);
        ops.publishDirect(actor, ContentType.KG, code, kgSnapshot("other"), "KG_UPSERT");

        PreviewView preview = ops.preview(ContentType.KG, code);
        assertTrue(preview.conflict());

        BizException ex = assertThrows(BizException.class,
                () -> ops.publishDraft(actor, ContentType.KG, code));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertEquals("other", payloadOf(code).path("definition").asText(), "冲突时不得覆盖 live");
    }

    @Test
    void invalidDraftIsPreviewedButCannotPublish() {
        String code = "cd_h04i_" + System.nanoTime();
        kgCodes.add(code);
        long actor = 1L;
        ObjectNode bad = mapper.createObjectNode();
        bad.put("type", "DISTORTION");
        bad.put("name", "缺字段");
        bad.put("status", 1);
        bad.putObject("payload").put("definition", "d");

        DraftView draft = ops.saveDraft(actor, ContentType.KG, code, bad, null, null);
        assertFalse(draft.valid());
        assertFalse(draft.errors().isEmpty());

        PreviewView preview = ops.preview(ContentType.KG, code);
        assertFalse(preview.valid());
        assertThrows(BizException.class, () -> ops.publishDraft(actor, ContentType.KG, code));
        assertFalse(kgRepo.findByCode(code).isPresent(), "校验失败不得写 live");

        ops.deleteDraft(actor, ContentType.KG, code);
        assertThrows(BizException.class, () -> ops.getDraft(ContentType.KG, code));
    }

    @Test
    void publishesSceneAndExerciseSnapshots() {
        long actor = 1L;
        String sceneCode = "H04_SCENE_" + System.nanoTime();
        sceneCodes.add(sceneCode);
        ObjectNode scene = mapper.createObjectNode();
        scene.put("title", "H04 测试场景");
        scene.put("description", "用于验证发布管线");
        scene.putArray("difficulties").add("MILD").add("NORMAL");
        scene.put("npcName", "小磊");
        scene.put("relation", "室友");
        ObjectNode persona = scene.putObject("persona");
        persona.put("motivation", "m");
        persona.put("bottomLine", "b");
        persona.put("triggers", "t");
        persona.put("style", "s");
        scene.putArray("goalDimensions").add("LISTEN");
        scene.put("maxTurns", 10);
        ObjectNode opening = scene.putObject("openingLines");
        opening.put("MILD", "轻");
        opening.put("NORMAL", "中");
        scene.putArray("tags").add("人际冲突");
        scene.putArray("recommendedFor").add("人际冲突");
        scene.put("status", 1);

        ops.saveDraft(actor, ContentType.SCENE, sceneCode, scene, null, null);
        PublishView sp = ops.publishDraft(actor, ContentType.SCENE, sceneCode);
        assertEquals(1, sp.revisionNo());
        assertEquals("H04 测试场景", sceneRepo.findByCode(sceneCode).orElseThrow().getTitle());
        assertTrue(store.scenes().stream().anyMatch(s -> s.code().equals(sceneCode)));

        String exCode = "ex_h04_" + System.nanoTime();
        exerciseCodes.add(exCode);
        ObjectNode exercise = mapper.createObjectNode();
        exercise.put("name", "H04 测试呼吸");
        exercise.putArray("applyEmotions").add("ANXIETY");
        exercise.putArray("steps").addObject().put("step", "吸气").put("desc", "4 秒");
        exercise.put("durationMin", 5);
        exercise.put("status", 1);

        ops.saveDraft(actor, ContentType.EXERCISE, exCode, exercise, null, null);
        PublishView ep = ops.publishDraft(actor, ContentType.EXERCISE, exCode);
        assertEquals(1, ep.revisionNo());
        assertEquals("H04 测试呼吸", exerciseRepo.findByCode(exCode).orElseThrow().getName());
        assertTrue(store.exercises().stream().anyMatch(e -> e.id().equals(exCode)));
    }
}
