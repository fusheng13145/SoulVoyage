package com.leyon.soul.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leyon.soul.admin.dto.ContentAdminDtos.ContentVersionResp;
import com.leyon.soul.admin.dto.ContentAdminDtos.ContentWriteResp;
import com.leyon.soul.admin.dto.ContentAdminDtos.KgAuditNode;
import com.leyon.soul.admin.dto.ContentAdminDtos.KgAuditRelation;
import com.leyon.soul.admin.dto.ContentAdminDtos.KgGraphAuditResp;
import com.leyon.soul.admin.dto.ContentAdminDtos.PreviewResp;
import com.leyon.soul.domain.content.ContentOpsService;
import com.leyon.soul.domain.content.ContentType;
import com.leyon.soul.domain.content.dto.ContentOpsDtos.DraftView;
import com.leyon.soul.domain.content.dto.ContentOpsDtos.PreviewView;
import com.leyon.soul.domain.content.dto.ContentOpsDtos.PublishView;
import com.leyon.soul.domain.content.dto.ContentOpsDtos.RevisionView;
import com.leyon.soul.audit.AuditService;
import com.leyon.soul.auth.AuthPrincipal;
import com.leyon.soul.common.api.ApiResponse;
import com.leyon.soul.common.api.ErrorCode;
import com.leyon.soul.common.exception.BizException;
import com.leyon.soul.domain.content.ContentStore;
import com.leyon.soul.domain.content.ExerciseLibraryEntity;
import com.leyon.soul.domain.content.KgNodeEntity;
import com.leyon.soul.domain.content.SceneCardEntity;
import com.leyon.soul.kg.Neo4jKgService;
import com.leyon.soul.llm.LlmClient;
import com.leyon.soul.llm.PromptTemplates;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内容管理端最小写口（N4 热更新）：kg_node / scene_card / exercise_library 三类内容的 upsert 与上下架。
 * 写成功即 bump content:version——本 JVM 快照立刻失效，多实例 30s 轮询收敛；全部落审计。
 */
@RestController
@RequestMapping("/api/v1/admin/content")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') and @perms.has('admin:scene')")
public class ContentAdminController {

    private static final List<String> KG_TYPES = List.of("DISTORTION", "PSY_TOPIC", "COMM_CASE", "STRENGTH_TECH");

    private final ContentStore store;
    private final ContentOpsService ops;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final PromptTemplates prompts;
    private final LlmClient llm;
    /** G9：图实现只在 soul.neo4j.enabled=true 时装配，用 ObjectProvider 取（DB 模式取到空） */
    private final ObjectProvider<Neo4jKgService> neo4jKg;

    public record KgBody(String type, String name, JsonNode payload, Short status) {}

    public record SceneBody(String title, String description, List<String> difficulties,
                            String npcName, String relation, JsonNode persona, List<String> goalDimensions,
                            Integer maxTurns, JsonNode openingLines, List<String> tags,
                            List<String> recommendedFor, Short status) {}

    public record ExerciseBody(String name, List<String> applyEmotions, JsonNode steps,
                               Integer durationMin, Short status) {}

    /** H-04 草稿保存请求：payload 允许暂时不完整，发布前统一校验。 */
    public record DraftBody(JsonNode payload, Long baseRevisionId, String note) {}

    // ---------------- 读（管理视角，含下架行） ----------------

    @GetMapping("/kg")
    public ApiResponse<List<KgNodeEntity>> listKg(@RequestParam(required = false) String type) {
        return ApiResponse.ok(ops.listKg(type));
    }

    /**
     * G9 对账：kg_node（上架内容这一唯一写入口的产物）与 Neo4j 图逐项比对——七类节点比集合差、
     * 五类关系比计数，输出 missing/extra 明细。图未启用时如实回"未启用"，不假装一致。
     * 本端点是运维主动动作（不进任何在线链路），也是内容批量改动或故障恢复后的验收手段。
     */
    @GetMapping("/kg/graph-audit")
    public ApiResponse<KgGraphAuditResp> kgGraphAudit() {
        Neo4jKgService graph = neo4jKg.getIfAvailable();
        if (graph == null) {
            return ApiResponse.ok(new KgGraphAuditResp(false,
                    "图未启用（soul.neo4j.enabled=false）：KG 直接走 kg_node 表，不存在派生视图",
                    null, null, null, null, null, null));
        }
        return ApiResponse.ok(auditView(graph.audit()));
    }

    @GetMapping("/scenes")
    public ApiResponse<List<SceneCardEntity>> listScenes() {
        return ApiResponse.ok(ops.listScenes());
    }

    @GetMapping("/exercises")
    public ApiResponse<List<ExerciseLibraryEntity>> listExercises() {
        return ApiResponse.ok(ops.listExercises());
    }

    // ---------------- 写（upsert + 版本 bump + 审计） ----------------

    @PutMapping("/kg/{code}")
    public ApiResponse<ContentWriteResp> upsertKg(@AuthenticationPrincipal AuthPrincipal p,
                                                  @PathVariable String code, @RequestBody KgBody body) {
        KgNodeEntity e = ops.findKg(code).orElseGet(() -> {
            if (body.type() == null || !KG_TYPES.contains(body.type()))
                throw new BizException(ErrorCode.BAD_PARAMS, "新建知识节点需要合法 type");
            KgNodeEntity n = new KgNodeEntity();
            n.setCode(code);
            n.setType(body.type());
            return n;
        });
        if (body.type() != null) {
            if (!KG_TYPES.contains(body.type())) throw new BizException(ErrorCode.BAD_PARAMS, "未知节点类型");
            e.setType(body.type());
        }
        if (body.name() != null) e.setName(body.name());
        if (body.payload() != null) e.setPayloadJson(body.payload().toString());
        if (body.status() != null) e.setStatus(body.status());
        if (e.getName() == null || e.getPayloadJson() == null)
            throw new BizException(ErrorCode.BAD_PARAMS, "缺少 name/payload");
        PublishView pv = ops.publishDirect(p.userId(), ContentType.KG, code, ops.snapshot(e), "KG_UPSERT");
        return ApiResponse.ok(new ContentWriteResp(code, pv.contentVersion(), pv.effective(), pv.revisionNo()));
    }

    @PutMapping("/scenes/{code}")
    public ApiResponse<ContentWriteResp> upsertScene(@AuthenticationPrincipal AuthPrincipal p,
                                                     @PathVariable String code, @RequestBody SceneBody body) {
        SceneCardEntity e = ops.findScene(code).orElseGet(() -> {
            SceneCardEntity n = new SceneCardEntity();
            n.setCode(code);
            return n;
        });
        if (body.title() != null) e.setTitle(body.title());
        if (body.description() != null) e.setDescription(body.description());
        if (body.difficulties() != null) e.setDifficulties(String.join(",", body.difficulties()));
        if (body.goalDimensions() != null) e.setGoalDimensions(mapper.valueToTree(body.goalDimensions()).toString());
        if (body.maxTurns() != null) e.setMaxTurns(body.maxTurns());
        if (body.tags() != null) e.setTags(String.join(",", body.tags()));
        if (body.recommendedFor() != null) e.setRecommendedFor(String.join(",", body.recommendedFor()));
        if (body.status() != null) e.setStatus(body.status());
        // persona_json：只合并传入的字段，保留其余
        ObjectNode persona = e.getPersonaJson() == null ? mapper.createObjectNode()
                : (ObjectNode) read(e.getPersonaJson());
        if (body.npcName() != null) persona.put("npcName", body.npcName());
        if (body.relation() != null) persona.put("relation", body.relation());
        if (body.persona() != null) persona.set("persona", body.persona());
        if (body.openingLines() != null) persona.set("openingLines", body.openingLines());
        e.setPersonaJson(persona.toString());
        if (e.getTitle() == null || e.getDescription() == null)
            throw new BizException(ErrorCode.BAD_PARAMS, "新建场景卡需要 title/description");
        if (e.getGoalDimensions() == null) e.setGoalDimensions("[]");
        if (e.getMaxTurns() == null) e.setMaxTurns(20);
        PublishView pv = ops.publishDirect(p.userId(), ContentType.SCENE, code, ops.snapshot(e), "SCENE_UPSERT");
        return ApiResponse.ok(new ContentWriteResp(code, pv.contentVersion(), pv.effective(), pv.revisionNo()));
    }

    @PutMapping("/exercises/{code}")
    public ApiResponse<ContentWriteResp> upsertExercise(@AuthenticationPrincipal AuthPrincipal p,
                                                        @PathVariable String code,
                                                        @RequestBody ExerciseBody body) {
        if (!code.startsWith("ex_")) throw new BizException(ErrorCode.BAD_PARAMS, "练习编码须为 ex_* 小写");
        ExerciseLibraryEntity e = ops.findExercise(code).orElseGet(() -> {
            ExerciseLibraryEntity n = new ExerciseLibraryEntity();
            n.setCode(code);
            return n;
        });
        if (body.name() != null) e.setName(body.name());
        if (body.applyEmotions() != null) e.setApplyEmotions(mapper.valueToTree(body.applyEmotions()).toString());
        if (body.steps() != null) e.setStepsJson(body.steps().toString());
        if (body.durationMin() != null) e.setDurationMin(body.durationMin().shortValue());
        if (body.status() != null) e.setStatus(body.status());
        if (e.getName() == null || e.getApplyEmotions() == null || e.getStepsJson() == null)
            throw new BizException(ErrorCode.BAD_PARAMS, "新建练习需要 name/applyEmotions/steps");
        PublishView pv = ops.publishDirect(p.userId(), ContentType.EXERCISE, code, ops.snapshot(e), "EXERCISE_UPSERT");
        return ApiResponse.ok(new ContentWriteResp(code, pv.contentVersion(), pv.effective(), pv.revisionNo()));
    }

    /** 手动强制刷新（管理端改完库后校对用）；返回当前版本号 */
    @PostMapping("/refresh")
    public ApiResponse<ContentVersionResp> refresh(@AuthenticationPrincipal AuthPrincipal p) {
        long v = store.bump();
        audit.record(p.userId(), "CONTENT_REFRESH", "content:version", null);
        return ApiResponse.ok(new ContentVersionResp(v));
    }

    // ---------------- H-04 草稿 / 预览 / 发布 / 回滚 ----------------

    @GetMapping("/snapshot/{type}/{code}")
    public ApiResponse<JsonNode> snapshot(@PathVariable String type, @PathVariable String code) {
        return ApiResponse.ok(ops.snapshot(ContentType.parse(type), code));
    }

    @GetMapping("/drafts")
    public ApiResponse<List<DraftView>> drafts(@RequestParam(required = false) String type) {
        return ApiResponse.ok(ops.drafts(type));
    }

    @GetMapping("/drafts/{type}/{code}")
    public ApiResponse<DraftView> draft(@PathVariable String type, @PathVariable String code) {
        return ApiResponse.ok(ops.getDraft(ContentType.parse(type), code));
    }

    @PutMapping("/drafts/{type}/{code}")
    public ApiResponse<DraftView> saveDraft(@AuthenticationPrincipal AuthPrincipal p,
                                            @PathVariable String type, @PathVariable String code,
                                            @RequestBody DraftBody body) {
        return ApiResponse.ok(ops.saveDraft(p.userId(), ContentType.parse(type), code,
                body.payload(), body.baseRevisionId(), body.note()));
    }

    @DeleteMapping("/drafts/{type}/{code}")
    public ApiResponse<Void> deleteDraft(@AuthenticationPrincipal AuthPrincipal p,
                                         @PathVariable String type, @PathVariable String code) {
        ops.deleteDraft(p.userId(), ContentType.parse(type), code);
        return ApiResponse.ok(null);
    }

    @PostMapping("/drafts/{type}/{code}/preview")
    public ApiResponse<PreviewView> previewDraft(@PathVariable String type, @PathVariable String code) {
        return ApiResponse.ok(ops.preview(ContentType.parse(type), code));
    }

    @PostMapping("/drafts/{type}/{code}/publish")
    public ApiResponse<PublishView> publishDraft(@AuthenticationPrincipal AuthPrincipal p,
                                                 @PathVariable String type, @PathVariable String code) {
        return ApiResponse.ok(ops.publishDraft(p.userId(), ContentType.parse(type), code));
    }

    @GetMapping("/revisions/{type}/{code}")
    public ApiResponse<List<RevisionView>> revisions(@PathVariable String type, @PathVariable String code) {
        return ApiResponse.ok(ops.revisions(ContentType.parse(type), code));
    }

    @PostMapping("/revisions/{type}/{code}/{revisionId}/rollback")
    public ApiResponse<PublishView> rollback(@AuthenticationPrincipal AuthPrincipal p,
                                             @PathVariable String type, @PathVariable String code,
                                             @PathVariable long revisionId) {
        return ApiResponse.ok(ops.rollback(p.userId(), ContentType.parse(type), code, revisionId));
    }

    // ---------------- A3 预览试跑 ----------------

    public record PreviewBody(String template, String user, Map<String, String> vars) {}

    /** 预览试跑：模板存在性 + {{var}} 渲染 + 强制策略注入 + 一次模型调用（开发期即 Mock），全审计 */
    @PostMapping("/preview")
    public ApiResponse<PreviewResp> preview(@AuthenticationPrincipal AuthPrincipal p,
                                            @RequestBody PreviewBody body) {
        String tpl = body.template() == null ? "" : body.template().trim();
        if (!tpl.matches("[a-z0-9_]{1,32}") || body.user() == null || body.user().isBlank())
            throw new BizException(ErrorCode.BAD_PARAMS, "需要合法 template 与 user 文本");
        String system;
        try {
            system = prompts.render(prompts.system(tpl),
                    body.vars() == null ? Map.of() : body.vars());
        } catch (Exception ex) {
            throw new BizException(ErrorCode.NOT_FOUND, "Prompt 模板不存在: " + tpl);
        }
        var resp = llm.chat(new LlmClient.LlmRequest(tpl, system, body.user(), 800));
        audit.record(p.userId(), "CONTENT_PREVIEW", "prompt:" + tpl, null);
        return ApiResponse.ok(new PreviewResp(
                tpl, system, resp.content(), resp.model(),
                resp.tokensIn(), resp.tokensOut(), resp.costMs()));
    }

    @SuppressWarnings("unchecked")
    private static KgGraphAuditResp auditView(Map<String, Object> m) {
        List<KgAuditNode> nodes = ((List<Map<String, Object>>) m.get("nodes")).stream()
                .map(n -> new KgAuditNode(
                        (String) n.get("label"), (String) n.get("kind"),
                        ((Number) n.get("expect")).intValue(), ((Number) n.get("actual")).intValue(),
                        (List<String>) n.get("missing"), (List<String>) n.get("extra"),
                        (List<String>) n.get("reserved")))
                .toList();
        List<KgAuditRelation> relations = ((List<Map<String, Object>>) m.get("relations")).stream()
                .map(r -> new KgAuditRelation(
                        (String) r.get("type"), ((Number) r.get("expect")).longValue(),
                        ((Number) r.get("actual")).longValue()))
                .toList();
        return new KgGraphAuditResp(true, null,
                ((Number) m.get("contentVersion")).longValue(),
                ((Number) m.get("syncedVersion")).longValue(),
                Boolean.TRUE.equals(m.get("consistent")), nodes, relations, (String) m.get("note"));
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.BAD_PARAMS, "场景卡内部数据损坏，请联系管理员修复");
        }
    }
}
