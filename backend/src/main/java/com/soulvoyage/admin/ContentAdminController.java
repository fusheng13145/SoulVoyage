package com.soulvoyage.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.audit.AuditService;
import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.domain.content.ContentStore;
import com.soulvoyage.domain.content.ExerciseLibraryEntity;
import com.soulvoyage.domain.content.ExerciseLibraryRepository;
import com.soulvoyage.domain.content.KgNodeEntity;
import com.soulvoyage.domain.content.KgNodeRepository;
import com.soulvoyage.domain.content.SceneCardEntity;
import com.soulvoyage.domain.content.SceneCardRepository;
import com.soulvoyage.llm.LlmClient;
import com.soulvoyage.llm.PromptTemplates;
import lombok.RequiredArgsConstructor;
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

    private final KgNodeRepository kgRepo;
    private final SceneCardRepository sceneRepo;
    private final ExerciseLibraryRepository exerciseRepo;
    private final ContentStore store;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final PromptTemplates prompts;
    private final LlmClient llm;

    public record KgBody(String type, String name, JsonNode payload, Short status) {}

    public record SceneBody(String title, String description, List<String> difficulties,
                            String npcName, String relation, JsonNode persona, List<String> goalDimensions,
                            Integer maxTurns, JsonNode openingLines, List<String> tags,
                            List<String> recommendedFor, Short status) {}

    public record ExerciseBody(String name, List<String> applyEmotions, JsonNode steps,
                               Integer durationMin, Short status) {}

    // ---------------- 读（管理视角，含下架行） ----------------

    @GetMapping("/kg")
    public ApiResponse<List<KgNodeEntity>> listKg(@RequestParam(required = false) String type) {
        return ApiResponse.ok(type == null || type.isBlank()
                ? kgRepo.findAll() : kgRepo.findByTypeAndStatus(type, (short) 1));
    }

    @GetMapping("/scenes")
    public ApiResponse<List<SceneCardEntity>> listScenes() {
        return ApiResponse.ok(sceneRepo.findAll());
    }

    @GetMapping("/exercises")
    public ApiResponse<List<ExerciseLibraryEntity>> listExercises() {
        return ApiResponse.ok(exerciseRepo.findAll());
    }

    // ---------------- 写（upsert + 版本 bump + 审计） ----------------

    @PutMapping("/kg/{code}")
    public ApiResponse<Map<String, Object>> upsertKg(@AuthenticationPrincipal AuthPrincipal p,
                                                     @PathVariable String code, @RequestBody KgBody body) {
        KgNodeEntity e = kgRepo.findByCode(code).orElseGet(() -> {
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
        kgRepo.save(e);
        return published(p, "KG_UPSERT", code);
    }

    @PutMapping("/scenes/{code}")
    public ApiResponse<Map<String, Object>> upsertScene(@AuthenticationPrincipal AuthPrincipal p,
                                                        @PathVariable String code, @RequestBody SceneBody body) {
        SceneCardEntity e = sceneRepo.findByCode(code).orElseGet(() -> {
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
        sceneRepo.save(e);
        return published(p, "SCENE_UPSERT", code);
    }

    @PutMapping("/exercises/{code}")
    public ApiResponse<Map<String, Object>> upsertExercise(@AuthenticationPrincipal AuthPrincipal p,
                                                           @PathVariable String code, @RequestBody ExerciseBody body) {
        if (!code.startsWith("ex_")) throw new BizException(ErrorCode.BAD_PARAMS, "练习编码须为 ex_* 小写");
        ExerciseLibraryEntity e = exerciseRepo.findByCode(code).orElseGet(() -> {
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
        exerciseRepo.save(e);
        return published(p, "EXERCISE_UPSERT", code);
    }

    /** 手动强制刷新（管理端改完库后校对用）；返回当前版本号 */
    @PostMapping("/refresh")
    public ApiResponse<Map<String, Object>> refresh(@AuthenticationPrincipal AuthPrincipal p) {
        long v = store.bump();
        audit.record(p.userId(), "CONTENT_REFRESH", "content:version", null);
        return ApiResponse.ok(Map.of("contentVersion", v));
    }

    // ---------------- A3 预览试跑 ----------------

    public record PreviewBody(String template, String user, Map<String, String> vars) {}

    /** 预览试跑：模板存在性 + {{var}} 渲染 + 强制策略注入 + 一次模型调用（开发期即 Mock），全审计 */
    @PostMapping("/preview")
    public ApiResponse<Map<String, Object>> preview(@AuthenticationPrincipal AuthPrincipal p,
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
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("template", tpl);
        m.put("system", system);
        m.put("output", resp.content());
        m.put("model", resp.model());
        m.put("tokensIn", resp.tokensIn());
        m.put("tokensOut", resp.tokensOut());
        m.put("costMs", resp.costMs());
        return ApiResponse.ok(m);
    }

    // ---------------- internals ----------------

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.BAD_PARAMS, "场景卡内部数据损坏，请联系管理员修复");
        }
    }

    private ApiResponse<Map<String, Object>> published(AuthPrincipal p, String action, String code) {
        long v = store.bump();
        audit.record(p.userId(), action, "content:" + code, null);
        return ApiResponse.ok(Map.of("code", code, "contentVersion", v, "effective", true));
    }
}
