package com.soulvoyage.domain.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.audit.AuditService;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.domain.content.dto.ContentOpsDtos.DraftView;
import com.soulvoyage.domain.content.dto.ContentOpsDtos.PreviewView;
import com.soulvoyage.domain.content.dto.ContentOpsDtos.PublishView;
import com.soulvoyage.domain.content.dto.ContentOpsDtos.RevisionView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * H-04 内容运营闭环：草稿 → 校验预览 → 发布（版本快照）→ 回滚。
 * 草稿不触达 live；发布/回滚复用同一校验器与 apply 管线；所有写操作落审计并 bump 内容版本。
 */
@Service
@RequiredArgsConstructor
public class ContentOpsService {

    private final KgNodeRepository kgRepo;
    private final SceneCardRepository sceneRepo;
    private final ExerciseLibraryRepository exerciseRepo;
    private final ContentDraftRepository draftRepo;
    private final ContentRevisionRepository revisionRepo;
    private final ContentStore store;
    private final ContentSchemaValidator validator;
    private final AuditService audit;
    private final ObjectMapper mapper;

    // ---------------- live 快照 ----------------

    public List<KgNodeEntity> listKg(String type) {
        return type == null || type.isBlank()
                ? kgRepo.findAll() : kgRepo.findByTypeAndStatus(type, (short) 1);
    }

    public List<SceneCardEntity> listScenes() {
        return sceneRepo.findAll();
    }

    public List<ExerciseLibraryEntity> listExercises() {
        return exerciseRepo.findAll();
    }

    public java.util.Optional<KgNodeEntity> findKg(String code) {
        return kgRepo.findByCode(code);
    }

    public java.util.Optional<SceneCardEntity> findScene(String code) {
        return sceneRepo.findByCode(code);
    }

    public java.util.Optional<ExerciseLibraryEntity> findExercise(String code) {
        return exerciseRepo.findByCode(code);
    }

    public JsonNode snapshot(ContentType type, String code) {
        return switch (type) {
            case KG -> kgRepo.findByCode(code).map(this::snapshot)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND));
            case SCENE -> sceneRepo.findByCode(code).map(this::snapshot)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND));
            case EXERCISE -> exerciseRepo.findByCode(code).map(this::snapshot)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND));
        };
    }

    public JsonNode snapshot(KgNodeEntity e) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", e.getType());
        n.put("name", e.getName());
        n.set("payload", read(e.getPayloadJson(), "知识节点内容损坏"));
        n.put("status", e.getStatus() == null ? 1 : e.getStatus());
        return n;
    }

    public JsonNode snapshot(SceneCardEntity e) {
        JsonNode persona = read(e.getPersonaJson(), "场景卡内部数据损坏");
        ObjectNode n = mapper.createObjectNode();
        n.put("title", e.getTitle());
        n.put("description", e.getDescription());
        n.set("difficulties", mapper.valueToTree(csv(e.getDifficulties())));
        n.put("npcName", persona.path("npcName").asText(""));
        n.put("relation", persona.path("relation").asText(""));
        n.set("persona", persona.path("persona"));
        n.set("goalDimensions", read(e.getGoalDimensions(), "场景卡考察维度损坏"));
        n.put("maxTurns", e.getMaxTurns() == null ? 20 : e.getMaxTurns());
        n.set("openingLines", persona.path("openingLines"));
        n.set("tags", mapper.valueToTree(csv(e.getTags())));
        n.set("recommendedFor", mapper.valueToTree(csv(e.getRecommendedFor())));
        n.put("status", e.getStatus() == null ? 1 : e.getStatus());
        return n;
    }

    public JsonNode snapshot(ExerciseLibraryEntity e) {
        ObjectNode n = mapper.createObjectNode();
        n.put("name", e.getName());
        n.set("applyEmotions", read(e.getApplyEmotions(), "练习适用情绪损坏"));
        n.set("steps", read(e.getStepsJson(), "练习步骤损坏"));
        n.put("durationMin", e.getDurationMin() == null ? 5 : e.getDurationMin());
        n.put("status", e.getStatus() == null ? 1 : e.getStatus());
        return n;
    }

    // ---------------- 草稿 ----------------

    @Transactional
    public DraftView saveDraft(long actorId, ContentType type, String code,
                               JsonNode payload, Long baseRevisionId, String note) {
        List<String> codeErrors = validator.codeErrors(type, code);
        if (!codeErrors.isEmpty()) throw new BizException(ErrorCode.BAD_PARAMS, codeErrors.get(0));
        if (payload == null || !payload.isObject())
            throw new BizException(ErrorCode.BAD_PARAMS, "草稿必须是 JSON 对象");

        ContentDraftEntity d = draftRepo.findByContentTypeAndCode(type.name(), code)
                .orElseGet(() -> {
                    ContentDraftEntity n = new ContentDraftEntity();
                    n.setContentType(type.name());
                    n.setCode(code);
                    return n;
                });
        Long base = baseRevisionId != null ? baseRevisionId : d.getBaseRevisionId();
        if (base == null && d.getId() == null) base = latestRevisionId(type, code);
        d.setBaseRevisionId(base);
        d.setPayloadJson(payload.toString());
        String trimmed = note == null ? null : note.trim();
        d.setNote(trimmed == null || trimmed.isEmpty() ? null
                : trimmed.substring(0, Math.min(255, trimmed.length())));
        d.setUpdatedBy(actorId);
        d = draftRepo.save(d);

        audit.record(actorId, "CONTENT_DRAFT_SAVE", "content_draft:" + type.name() + ":" + code, null);
        return view(d, validator.validate(type, code, payload));
    }

    @Transactional
    public void deleteDraft(long actorId, ContentType type, String code) {
        draftRepo.findByContentTypeAndCode(type.name(), code).ifPresent(d -> {
            draftRepo.delete(d);
            audit.record(actorId, "CONTENT_DRAFT_DELETE",
                    "content_draft:" + type.name() + ":" + code, null);
        });
    }

    public DraftView getDraft(ContentType type, String code) {
        ContentDraftEntity d = draft(type, code);
        return view(d, validator.validate(type, code, read(d.getPayloadJson(), "草稿数据损坏")));
    }

    public List<DraftView> drafts(String typeParam) {
        List<ContentDraftEntity> list = typeParam == null || typeParam.isBlank()
                ? draftRepo.findAllByOrderByUpdatedAtDesc()
                : draftRepo.findByContentTypeOrderByUpdatedAtDesc(ContentType.parse(typeParam).name());
        return list.stream().map(d -> view(d, validator.validate(
                ContentType.parse(d.getContentType()), d.getCode(),
                read(d.getPayloadJson(), "草稿数据损坏")))).toList();
    }

    public PreviewView preview(ContentType type, String code) {
        ContentDraftEntity d = draft(type, code);
        ContentSchemaValidator.Result r = validator.validate(type, code,
                read(d.getPayloadJson(), "草稿数据损坏"));
        Long latest = latestRevisionId(type, code);
        boolean conflict = latest != null
                && (d.getBaseRevisionId() == null || !d.getBaseRevisionId().equals(latest));
        return new PreviewView(type.name(), code, r.valid(), r.errors(), r.warnings(),
                r.normalized(), type.name() + " · " + summary(type, r.normalized()),
                d.getBaseRevisionId(), latest, conflict);
    }

    // ---------------- 发布 / 回滚 ----------------

    @Transactional
    public PublishView publishDraft(long actorId, ContentType type, String code) {
        ContentDraftEntity d = draft(type, code);
        ContentSchemaValidator.Result r = validator.validate(type, code,
                read(d.getPayloadJson(), "草稿数据损坏"));
        Long latest = latestRevisionId(type, code);
        boolean conflict = latest != null
                && (d.getBaseRevisionId() == null || !d.getBaseRevisionId().equals(latest));
        if (conflict) {
            throw new BizException(ErrorCode.CONFLICT, "内容已被他人更新，请重新载入最新版本再发布");
        }
        if (!r.valid()) throw invalid(r.errors());
        PublishView view = publishSnapshot(type, code, r.normalized(),
                "CONTENT_PUBLISH", null, actorId, r.warnings());
        draftRepo.delete(d);
        return view;
    }

    /** 兼容既有直接 PUT：仍走同一校验/快照/版本管线。 */
    @Transactional
    public PublishView publishDirect(long actorId, ContentType type, String code,
                                     JsonNode snapshot, String auditAction) {
        ContentSchemaValidator.Result r = validator.validate(type, code, snapshot);
        if (!r.valid()) throw invalid(r.errors());
        return publishSnapshot(type, code, r.normalized(), auditAction, null, actorId, r.warnings());
    }

    public List<RevisionView> revisions(ContentType type, String code) {
        return revisionRepo.findByContentTypeAndCodeOrderByRevisionNoDesc(type.name(), code).stream()
                .map(r -> new RevisionView(r.getId(), r.getRevisionNo(), r.getAction(),
                        r.getSourceRevisionId(), r.getPublishedBy(),
                        r.getPublishedAt() == null ? null : r.getPublishedAt().toString(),
                        summaryOrFallback(type, r.getPayloadJson())))
                .toList();
    }

    @Transactional
    public PublishView rollback(long actorId, ContentType type, String code, long revisionId) {
        ContentRevisionEntity rev = revisionRepo
                .findByIdAndContentTypeAndCode(revisionId, type.name(), code)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND));
        ContentSchemaValidator.Result r = validator.validate(type, code,
                read(rev.getPayloadJson(), "历史快照损坏"));
        if (!r.valid()) throw new BizException(ErrorCode.BAD_PARAMS,
                "该历史版本已不符合当前 schema：" + firstErrors(r.errors()));
        return publishSnapshot(type, code, r.normalized(),
                "CONTENT_ROLLBACK", rev.getId(), actorId, r.warnings());
    }

    // ---------------- internals ----------------

    private PublishView publishSnapshot(ContentType type, String code, JsonNode snapshot,
                                        String auditAction, Long sourceRevisionId,
                                        long actorId, List<String> warnings) {
        apply(type, code, snapshot);
        int revisionNo = revisionRepo
                .findTopByContentTypeAndCodeOrderByRevisionNoDesc(type.name(), code)
                .map(r -> r.getRevisionNo() + 1).orElse(1);
        ContentRevisionEntity r = new ContentRevisionEntity();
        r.setContentType(type.name());
        r.setCode(code);
        r.setRevisionNo(revisionNo);
        r.setPayloadJson(snapshot.toString());
        r.setAction("CONTENT_ROLLBACK".equals(auditAction) ? "ROLLBACK" : "PUBLISH");
        r.setSourceRevisionId(sourceRevisionId);
        r.setPublishedBy(actorId);
        revisionRepo.save(r);

        long version = store.bump();
        audit.record(actorId, auditAction, "content:" + code, null);
        return new PublishView(type.name(), code, revisionNo, version, true, warnings);
    }

    private void apply(ContentType type, String code, JsonNode s) {
        switch (type) {
            case KG -> {
                KgNodeEntity e = kgRepo.findByCode(code).orElseGet(() -> {
                    KgNodeEntity n = new KgNodeEntity();
                    n.setCode(code);
                    return n;
                });
                e.setType(s.path("type").asText());
                e.setName(s.path("name").asText());
                e.setPayloadJson(s.path("payload").toString());
                e.setStatus((short) s.path("status").asInt(1));
                kgRepo.save(e);
            }
            case SCENE -> {
                SceneCardEntity e = sceneRepo.findByCode(code).orElseGet(() -> {
                    SceneCardEntity n = new SceneCardEntity();
                    n.setCode(code);
                    return n;
                });
                e.setTitle(s.path("title").asText());
                e.setDescription(s.path("description").asText());
                e.setDifficulties(join(s.path("difficulties")));
                ObjectNode persona = mapper.createObjectNode();
                persona.set("npcName", s.path("npcName"));
                persona.set("relation", s.path("relation"));
                persona.set("persona", s.path("persona"));
                persona.set("openingLines", s.path("openingLines"));
                e.setPersonaJson(persona.toString());
                e.setGoalDimensions(s.path("goalDimensions").toString());
                e.setMaxTurns(s.path("maxTurns").asInt(20));
                e.setTags(join(s.path("tags")));
                e.setRecommendedFor(join(s.path("recommendedFor")));
                e.setStatus((short) s.path("status").asInt(1));
                sceneRepo.save(e);
            }
            case EXERCISE -> {
                ExerciseLibraryEntity e = exerciseRepo.findByCode(code).orElseGet(() -> {
                    ExerciseLibraryEntity n = new ExerciseLibraryEntity();
                    n.setCode(code);
                    return n;
                });
                e.setName(s.path("name").asText());
                e.setApplyEmotions(s.path("applyEmotions").toString());
                e.setStepsJson(s.path("steps").toString());
                e.setDurationMin((short) s.path("durationMin").asInt(5));
                e.setStatus((short) s.path("status").asInt(1));
                exerciseRepo.save(e);
            }
        }
    }

    private ContentDraftEntity draft(ContentType type, String code) {
        return draftRepo.findByContentTypeAndCode(type.name(), code)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND));
    }

    private Long latestRevisionId(ContentType type, String code) {
        return revisionRepo.findTopByContentTypeAndCodeOrderByRevisionNoDesc(type.name(), code)
                .map(ContentRevisionEntity::getId).orElse(null);
    }

    private DraftView view(ContentDraftEntity d, ContentSchemaValidator.Result r) {
        return new DraftView(d.getContentType(), d.getCode(),
                read(d.getPayloadJson(), "草稿数据损坏"),
                d.getBaseRevisionId(), d.getNote(),
                d.getUpdatedBy() == null ? 0 : d.getUpdatedBy(),
                d.getUpdatedAt() == null ? null : d.getUpdatedAt().toString(),
                r.valid(), r.errors(), r.warnings());
    }

    private String summaryOrFallback(ContentType type, String payloadJson) {
        try {
            return summary(type, read(payloadJson, "历史快照损坏"));
        } catch (Exception e) {
            return "（快照不可解析）";
        }
    }

    private String summary(ContentType type, JsonNode p) {
        String status = p.path("status").asInt(1) == 1 ? " · 上架" : " · 下架";
        return switch (type) {
            case KG -> p.path("type").asText("") + " · " + p.path("name").asText("") + status;
            case SCENE -> p.path("title").asText("") + status;
            case EXERCISE -> p.path("name").asText("")
                    + (p.has("durationMin") ? " · " + p.path("durationMin").asInt() + " 分钟" : "")
                    + status;
        };
    }

    private BizException invalid(List<String> errors) {
        return new BizException(ErrorCode.BAD_PARAMS, "内容校验未通过：" + firstErrors(errors));
    }

    private String firstErrors(List<String> errors) {
        return errors.stream().limit(3).collect(Collectors.joining("；"));
    }

    private JsonNode read(String json, String message) {
        if (json == null || json.isBlank()) return mapper.createObjectNode();
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_PARAMS, message);
        }
    }

    private List<String> csv(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isBlank()).toList();
    }

    private String join(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) return "";
        List<String> values = new ArrayList<>();
        for (JsonNode n : arr) values.add(n.asText());
        return String.join(",", values);
    }
}
