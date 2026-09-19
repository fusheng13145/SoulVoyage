package com.soulvoyage.agent.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.common.time.BusinessCalendar;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.report.ReportEntity;
import com.soulvoyage.domain.report.ReportRepository;
import com.soulvoyage.domain.task.AgentMessageEntity;
import com.soulvoyage.domain.task.AgentMessageRepository;
import com.soulvoyage.llm.LlmClient;
import com.soulvoyage.llm.OutputValidator;
import com.soulvoyage.llm.PromptTemplates;
import com.soulvoyage.orchestrator.agent.Agent;
import com.soulvoyage.orchestrator.agent.AgentRuntime;
import com.soulvoyage.orchestrator.agent.OutputInvalidException;
import com.soulvoyage.orchestrator.protocol.AgentMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 疏导干预 Agent（手册 §4.4）：情绪 + 压力/误区画像 → 练习库闭集匹配 → LLM 只做"组合排序写文案"。
 * 反幻觉沿用溯源模块的"闭集注入 + 反向校验"：matchedExercises[].exerciseId 必须来自本步候选集。
 * HIGH 危机画像下由调度中心旁路（不进入流水线）；即便被调用，也只产出无害 grounding 方案。
 */
@Component
@RequiredArgsConstructor
public class SupportAgent implements Agent {

    private static final int CANDIDATE_LIMIT = 4;
    private static final String PSY_ANCHOR = "node:psy_self_regulation_body";

    /** 主导情绪 → 练习库适用标签（exercise.applyEmotions 的英文枚举，与 schema 种子同源） */
    private static final Map<String, List<String>> EMOTION_TO_CATEGORIES = Map.ofEntries(
            Map.entry("焦虑", List.of("ANXIETY")),
            Map.entry("恐惧", List.of("FEAR", "ACUTE_STRESS")),
            Map.entry("压力", List.of("HIGH_PRESSURE")),
            Map.entry("悲伤", List.of("SADNESS", "LOW_MOOD")),
            Map.entry("羞耻", List.of("SHAME")),
            Map.entry("内疚", List.of("GUILT", "SHAME")),
            Map.entry("愤怒", List.of("ANGER", "ACUTE_STRESS")),
            Map.entry("厌恶", List.of("CONFUSED")),
            Map.entry("孤独", List.of("LONELINESS", "LOW_MOOD")),
            Map.entry("麻木", List.of("NUMBNESS")),
            Map.entry("困惑", List.of("CONFUSED")),
            Map.entry("嫉妒", List.of("SHAME")));

    private final LlmClient llm;
    private final PromptTemplates templates;
    private final OutputValidator validator;
    private final ExerciseCatalog exercises;
    private final CryptoService crypto;
    private final ReportRepository reportRepo;
    private final AgentMessageRepository msgRepo;
    private final ObjectMapper mapper;
    private final BusinessCalendar cal;

    @Override
    public String code() { return "SUPPORT"; }

    @Override
    public JsonNode run(AgentMessage request, AgentRuntime rt) {
        JsonNode input = request.payload();
        JsonNode trace = input.path("prev");                 // 上一步 TRACE 输出（含压力源/误区）
        JsonNode emotion = loadEmotionResult(rt.taskId(), rt.userId());   // 首步 EMOTION 中间结果（从密文留痕取回）

        Set<String> categories = categoriesFor(emotion, trace);
        List<Exercise> candidates = pickCandidates(categories, trace);

        String system = templates.render(templates.system("support_v1"), Map.of(
                "exerciseCandidates", candidateBlock(candidates),
                "psyAnchor", PSY_ANCHOR));
        String user = buildUserPayload(emotion, trace, candidates);

        var resp = llm.chat(new LlmClient.LlmRequest("support_v1", system, user, 1200));
        JsonNode result = validator.validate(rt.spec().outputSchema(), resp.content());

        assertExerciseIds(result, candidates);

        String primary = emotion.path("primaryEmotion").asText("情绪");
        ReportEntity report = new ReportEntity();
        report.setUserId(rt.userId());
        report.setType("SUPPORT");
        report.setTaskId(rt.taskId());
        report.setTitle("自助方案 · " + primary + " · " + cal.today());
        report.setContentEnc(crypto.encryptUserField(rt.userId(), result.toString()));
        report.setRiskLevel("LOW");   // 疏导方案本身不抬升风险，研判统一交 RISK_ARCHIVE
        report = reportRepo.save(report);

        ObjectNode out = result.deepCopy();
        out.put("reportId", "rp_" + report.getId());
        return out;
    }

    /** EMOTION 中间结果：从 append-only 密文留痕里取回（与 RISK_ARCHIVE 读库同源思路，避免跨步骤透传膨胀） */
    private JsonNode loadEmotionResult(long taskId, long userId) {
        for (AgentMessageEntity m : msgRepo.findByTaskIdOrderByStepSeqAscIdAsc(taskId)) {
            if ("EMOTION".equals(m.getFromAgent()) && "MIDDLE_RESULT".equals(m.getMsgType())) {
                try {
                    return mapper.readTree(crypto.decryptUserField(userId, m.getPayloadEnc()));
                } catch (Exception ignore) {
                    // 落空则返回空对象，匹配退化为通用 grounding
                }
            }
        }
        return mapper.createObjectNode();
    }

    private Set<String> categoriesFor(JsonNode emotion, JsonNode trace) {
        Set<String> cats = new LinkedHashSet<>();
        String primary = emotion.path("primaryEmotion").asText("");
        cats.addAll(EMOTION_TO_CATEGORIES.getOrDefault(primary, List.of()));
        for (JsonNode s : emotion.path("secondaryEmotions")) {
            cats.addAll(EMOTION_TO_CATEGORIES.getOrDefault(s.path("emotion").asText(""), List.of()));
        }
        // 溯源发现认知误区 → 追加"认知书写"适用标签
        if (trace.path("cognitiveDistortions").size() > 0) {
            cats.add("SADNESS");
            cats.add("SHAME");
        }
        // 高强度负性情绪 → 急性着陆
        if (emotion.path("intensity").asDouble(0) >= 0.6 && emotion.path("valence").asDouble(0) < 0) {
            cats.add("ACUTE_STRESS");
        }
        return cats;
    }

    private List<Exercise> pickCandidates(Set<String> categories, JsonNode trace) {
        List<Exercise> matched = exercises.list().stream()
                .filter(e -> e.applyEmotions().stream().anyMatch(categories::contains))
                .limit(CANDIDATE_LIMIT)
                .toList();
        if (!matched.isEmpty()) return matched;
        // 无匹配 → 通用 grounding（手册 §4.4 兜底）
        return List.of(exercises.require("ex_54321"));
    }

    private String candidateBlock(List<Exercise> candidates) {
        var sb = new StringBuilder();
        for (Exercise e : candidates) {
            sb.append("- id=").append(e.id()).append(" | ").append(e.name())
                    .append(" | 适用=").append(e.applyEmotions())
                    .append(" | 时长").append(e.durationMin()).append("分钟")
                    .append(" | 步骤: ").append(e.steps().stream()
                            .map(Exercise.Step::step).collect(Collectors.joining("→")))
                    .append('\n');
        }
        return sb.toString();
    }

    private String buildUserPayload(JsonNode emotion, JsonNode trace, List<Exercise> candidates) {
        var root = mapper.createObjectNode();
        root.set("emotionResult", emotion.deepCopy());
        ObjectNode tp = root.putObject("traceSignals");
        tp.set("stressors", trace.path("stressors").deepCopy());
        tp.set("cognitiveDistortions", trace.path("cognitiveDistortions").deepCopy());
        root.set("candidates", mapper.valueToTree(candidates));
        root.put("psyAnchor", PSY_ANCHOR);
        return root.toString();
    }

    /** 反向闭集校验（手册 §5.3 思路延伸）：练习 id 必须来自本步注入候选，防模型发明新疗法 */
    public static void assertExerciseIds(JsonNode result, List<Exercise> candidates) {
        Set<String> allowed = candidates.stream().map(Exercise::id).collect(Collectors.toSet());
        for (JsonNode ex : result.path("matchedExercises")) {
            String id = ex.path("exerciseId").asText("");
            if (!allowed.contains(id)) {
                throw new OutputInvalidException("方案引用了不在候选库中的练习: " + id);
            }
        }
    }
}
