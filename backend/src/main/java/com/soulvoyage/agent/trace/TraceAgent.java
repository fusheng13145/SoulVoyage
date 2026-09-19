package com.soulvoyage.agent.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.common.time.BusinessCalendar;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.report.ReportEntity;
import com.soulvoyage.domain.report.ReportRepository;
import com.soulvoyage.kg.KgSearchService;
import com.soulvoyage.llm.LlmClient;
import com.soulvoyage.llm.OutputValidator;
import com.soulvoyage.llm.PromptTemplates;
import com.soulvoyage.orchestrator.agent.Agent;
import com.soulvoyage.orchestrator.agent.AgentRuntime;
import com.soulvoyage.orchestrator.agent.OutputInvalidException;
import com.soulvoyage.orchestrator.protocol.AgentMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

/**
 * 溯源推理 Agent（手册 §4.2）：情绪结果 + 日记 → KG 候选注入 → CBT 复盘。
 * 幻觉约束双保险：Prompt 闭集候选 + kgNodeId 反向校验（输出必须引用候选卡 id）。
 */
@Component
@RequiredArgsConstructor
public class TraceAgent implements Agent {

    private static final int CANDIDATE_LIMIT = 4;

    private final LlmClient llm;
    private final PromptTemplates templates;
    private final OutputValidator validator;
    private final KgSearchService kg;
    private final CryptoService crypto;
    private final ReportRepository reportRepo;
    private final ObjectMapper mapper;
    private final BusinessCalendar cal;

    @Override
    public String code() { return "TRACE"; }

    @Override
    public JsonNode run(AgentMessage request, AgentRuntime rt) {
        JsonNode input = request.payload();
        String text = input.path("diaryText").asText("");
        JsonNode prev = input.path("prev");
        if (text.isBlank() || prev.isMissingNode()) {
            return fallback();
        }

        List<String> tags = eventTags(prev);
        List<KgSearchService.DistortionCard> candidates =
                kg.distortionsFor(prev.path("primaryEmotion").asText(), tags, CANDIDATE_LIMIT);
        List<String> stressorHint = kg.stressorsFor(tags);

        String system = templates.render(templates.system("trace_v1"), Map.of(
                "kgCandidates", candidateBlock(candidates),
                "stressorEnum", String.join("、", kg.stressorOptions())));
        String user = buildUserPayload(text, prev, candidates, stressorHint);

        var resp = llm.chat(new LlmClient.LlmRequest("trace_v1", system, user, 1800));
        JsonNode result = validator.validate(rt.spec().outputSchema(), resp.content());

        assertKgNodeIds(result, candidates);

        String title = "情绪复盘 · " + prev.path("primaryEmotion").asText("")
                + " · " + cal.today();
        ReportEntity report = new ReportEntity();
        report.setUserId(rt.userId());
        report.setType("TRACE");
        report.setTaskId(rt.taskId());
        report.setTitle(title);
        report.setContentEnc(crypto.encryptUserField(rt.userId(), result.toString()));
        report.setRiskLevel(maxRisk(result.path("riskSignals")));
        report = reportRepo.save(report);

        ObjectNode out = result.deepCopy();
        out.put("reportId", "rp_" + report.getId());
        return out;
    }

    /** KG 反向校验（手册 §5.3）：误区 id 必须来自本步骤注入的候选集，防止模型编造节点 */
    public static void assertKgNodeIds(JsonNode result, List<KgSearchService.DistortionCard> candidates) {
        Set<String> allowed = candidates.stream()
                .map(KgSearchService.DistortionCard::kgNodeId).collect(java.util.stream.Collectors.toSet());
        for (JsonNode d : result.path("cognitiveDistortions")) {
            String id = d.path("kgNodeId").asText("");
            if (!allowed.contains(id)) {
                throw new OutputInvalidException("输出引用了不在候选集中的误区节点: " + id);
            }
        }
    }

    private List<String> eventTags(JsonNode prev) {
        return StreamSupport.stream(prev.path("eventTags").spliterator(), false)
                .map(n -> n.path("tag").asText("")).filter(s -> !s.isBlank()).toList();
    }

    private String candidateBlock(List<KgSearchService.DistortionCard> candidates) {
        if (candidates.isEmpty()) return "（本次无匹配候选卡，请输出空数组并置 insufficientEvidence=true）";
        var sb = new StringBuilder();
        for (var c : candidates) {
            sb.append("- id=").append(c.kgNodeId()).append(" | ").append(c.name())
                    .append(" | ").append(c.definition())
                    .append(" | 典型句式: ").append(c.typicalSignature())
                    .append(" | 苏格拉底提问: ").append(c.socraticTemplate()).append('\n');
        }
        return sb.toString();
    }

    private String buildUserPayload(String text, JsonNode prev,
                                    List<KgSearchService.DistortionCard> candidates,
                                    List<String> stressorHint) {
        var root = mapper.createObjectNode();
        root.put("diaryText", text);
        root.set("emotionResult", prev.deepCopy());
        root.set("kgCandidates", mapper.valueToTree(candidates));
        root.set("stressorHint", mapper.valueToTree(stressorHint));
        return root.toString();
    }

    private String maxRisk(JsonNode signals) {
        String level = "LOW";
        for (JsonNode s : signals) {
            String l = s.path("level").asText("LOW");
            if ("HIGH".equals(l)) return "HIGH";
            if ("MEDIUM".equals(l)) level = "MEDIUM";
        }
        return level;
    }

    /** 兜底：证据不足时不强行归因，返回满足 schema 的最小结构 */
    private JsonNode fallback() {
        var out = mapper.createObjectNode();
        out.putArray("stressors");
        out.putArray("cognitiveDistortions");
        out.putArray("socraticQuestions").add("今天没有留下文字也没有关系——此刻你最想从哪一件小事开始说起？");
        ObjectNode r = out.putObject("report");
        r.put("eventSummary", "本次记录内容较少，未提取到具体事件。");
        r.put("emotionSummary", "情绪信号有限，暂以平静基调归档。");
        r.put("thoughtSummary", "没有足够的想法素材可供梳理。");
        r.put("insight", "复盘需要一点素材，哪怕只写一句「今天最堵的一口气」也够用。");
        r.put("suggestion", "睡前用一句话记录今天印象最深的瞬间，明天回来继续。");
        out.putArray("riskSignals");
        out.put("insufficientEvidence", true);
        return out;
    }
}
