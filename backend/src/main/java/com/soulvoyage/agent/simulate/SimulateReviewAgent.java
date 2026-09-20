package com.soulvoyage.agent.simulate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.common.time.BusinessCalendar;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.report.ReportEntity;
import com.soulvoyage.domain.report.ReportRepository;
import com.soulvoyage.domain.simulate.SimulateSessionEntity;
import com.soulvoyage.domain.simulate.SimulateSessionRepository;
import com.soulvoyage.domain.simulate.SimulateTurnEntity;
import com.soulvoyage.domain.simulate.SimulateTurnRepository;
import com.soulvoyage.llm.LlmClient;
import com.soulvoyage.llm.OutputValidator;
import com.soulvoyage.llm.PromptTemplates;
import com.soulvoyage.orchestrator.agent.Agent;
import com.soulvoyage.orchestrator.agent.AgentRuntime;
import com.soulvoyage.orchestrator.protocol.AgentMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 心智训练 Agent 的复盘步骤（手册 §4.3 SimulateReview 契约）：
 * 逐轮对话记录 + 导演关键事件 → NVO 四要素评分/改写建议 → 加密落 report(type=SIMULATE)。
 * 对话循环本身在会话服务层完成（每轮一次 LLM 调用），调度中心负责本"复盘"步骤的编排/校验/留痕。
 */
@Component
@RequiredArgsConstructor
public class SimulateReviewAgent implements Agent {

    private final LlmClient llm;
    private final PromptTemplates templates;
    private final OutputValidator validator;
    private final SceneCatalog scenes;
    private final com.soulvoyage.kg.KgSearchService kg;
    private final CryptoService crypto;
    private final SimulateSessionRepository sessionRepo;
    private final SimulateTurnRepository turnRepo;
    private final ReportRepository reportRepo;
    private final ObjectMapper mapper;
    private final BusinessCalendar cal;

    @Override
    public String code() { return "SIMULATE"; }

    @Override
    public JsonNode run(AgentMessage request, AgentRuntime rt) {
        long simulateId = request.payload().path("simulateId").asLong(0);
        SimulateSessionEntity session = sessionRepo.findById(simulateId).orElse(null);
        if (session == null || !session.getUserId().equals(rt.userId())) {
            return fallback("会话不存在");
        }
        SceneCard scene = scenes.require(session.getSceneCode());
        var turns = turnRepo.findBySimulateIdOrderByTurnNoAsc(simulateId);
        if (turns.isEmpty()) return fallback("无对话记录");

        // N2：按场景标签召回真实沟通案例，复盘引用做闭集校验
        String sceneTag = scene.tags() == null || scene.tags().isEmpty() ? null : scene.tags().get(0);
        var caseCandidates = kg.casesFor(sceneTag == null ? scene.relation() : sceneTag, List.of(), 2);

        String system = templates.render(templates.system("simulate_review_v1"), Map.of(
                "goalDimensions", String.join("、", scene.goalDimensions()),
                "caseCandidates", caseBlock(caseCandidates)));
        var resp = llm.chat(new LlmClient.LlmRequest("simulate_review_v1",
                system, buildUserPayload(session, scene, turns, caseCandidates), 1800));
        JsonNode result = validator.validate(rt.spec().outputSchema(), resp.content());
        assertCaseRef(result, caseCandidates);

        boolean crisisSeen = turns.stream().anyMatch(t -> t.getCrisisFlag() == 1);
        ReportEntity report = new ReportEntity();
        report.setUserId(rt.userId());
        report.setType("SIMULATE");
        report.setTaskId(rt.taskId());
        report.setBizRefId(simulateId);
        report.setTitle("沟通复盘 · " + scene.title() + " · " + cal.today());
        report.setContentEnc(crypto.encryptUserField(rt.userId(), result.toString()));
        report.setRiskLevel(crisisSeen ? "HIGH" : "LOW");
        report = reportRepo.save(report);

        session.setStatus("FINISHED");
        session.setReportId(report.getId());
        session.setFinishedAt(Instant.now());
        applyScoreProfile(result, session, rt.userId());
        sessionRepo.save(session);

        ObjectNode out = result.deepCopy();
        out.put("reportId", "rp_" + report.getId());
        out.put("simulateId", simulateId);
        String ref = result.path("referenceCase").asText("");
        caseCandidates.stream().filter(c -> c.kgNodeId().equals(ref)).findFirst().ifPresent(c ->
                out.putObject("referenceCaseDetail")
                        .put("kgNodeId", c.kgNodeId()).put("title", c.title())
                        .put("situation", c.situation())
                        .put("unhelpful", c.unhelpful()).put("helpful", c.helpful()));
        return out;
    }

    /** C3 训练档案落库：综合分 + 四维均分（雷达图数据源）+ 弱项评语密文（续练注入用） */
    private void applyScoreProfile(JsonNode result, SimulateSessionEntity session, long userId) {
        session.setAvgScore(java.math.BigDecimal.valueOf(
                result.path("overall").path("avgScore").asDouble(0)));
        Map<String, int[]> agg = new java.util.LinkedHashMap<>();
        for (JsonNode ts : result.path("turnScores")) {
            int pts = switch (ts.path("grade").asText()) {
                case "A" -> 92; case "B" -> 75; case "C" -> 60; default -> 42;
            };
            agg.computeIfAbsent(ts.path("dimension").asText("LISTEN"), k -> new int[2])[0] += pts;
            agg.get(ts.path("dimension").asText("LISTEN"))[1]++;
        }
        ObjectNode dims = mapper.createObjectNode();
        agg.forEach((dim, sum) -> dims.put(dim,
                Math.round((double) sum[0] / sum[1] * 10) / 10.0));
        session.setDimensionScores(dims.toString());
        JsonNode weaknesses = result.path("overall").path("weaknesses");
        if (weaknesses.isArray() && !weaknesses.isEmpty()) {
            session.setWeaknessesEnc(crypto.encryptUserField(userId, weaknesses.toString()));
        }
    }

    private String caseBlock(List<com.soulvoyage.kg.KgSearchService.CommCaseCard> cases) {
        if (cases.isEmpty()) return "（本场场景暂无匹配案例，不要输出 referenceCase）";
        var sb = new StringBuilder();
        for (var c : cases) {
            sb.append("- id=").append(c.kgNodeId()).append(" | ").append(c.title())
                    .append(" | 反面: ").append(c.unhelpful())
                    .append(" | 推荐: ").append(c.helpful()).append('\n');
        }
        return sb.toString();
    }

    /** 反向闭集校验（手册 §5.3 延伸）：referenceCase 若输出，必须来自本步注入的案例候选，防止编造案例 id */
    public static void assertCaseRef(JsonNode result,
                                     List<com.soulvoyage.kg.KgSearchService.CommCaseCard> cases) {
        String ref = result.path("referenceCase").asText("");
        if (ref.isBlank()) return;
        boolean ok = cases.stream().anyMatch(c -> c.kgNodeId().equals(ref));
        if (!ok) {
            throw new com.soulvoyage.orchestrator.agent.OutputInvalidException("复盘引用了不在候选集中的案例: " + ref);
        }
    }

    /** 复盘输入：解密逐轮原文 + 导演档位/关键事件（评分以用户原话为准，标签仅参考） */
    private String buildUserPayload(SimulateSessionEntity s, SceneCard scene,
                                    Iterable<SimulateTurnEntity> turns,
                                    List<com.soulvoyage.kg.KgSearchService.CommCaseCard> caseCandidates) {
        var root = mapper.createObjectNode();
        root.put("sceneCode", scene.code());
        root.put("sceneTitle", scene.title());
        root.put("difficulty", s.getDifficulty());
        root.put("npcName", scene.npcName());
        root.put("npcRelation", scene.relation());
        root.set("goalDimensions", mapper.valueToTree(scene.goalDimensions()));
        ArrayNode cc = root.putArray("caseCandidates");
        for (var c : caseCandidates) {
            cc.addObject().put("kgNodeId", c.kgNodeId()).put("title", c.title())
                    .put("situation", c.situation());
        }
        ArrayNode arr = root.putArray("turns");
        for (SimulateTurnEntity t : turns) {
            ObjectNode n = arr.addObject();
            n.put("turn", t.getTurnNo());
            n.put("userText", crypto.decryptUserField(s.getUserId(), t.getUserTextEnc()));
            n.put("npcText", t.getNpcText());
            n.put("npcEmotion", t.getNpcEmotion());
            if (t.getStateTag() != null) n.put("stateTag", t.getStateTag());
            n.put("crisis", t.getCrisisFlag() == 1);
        }
        return root.toString();
    }

    private JsonNode fallback(String reason) {
        var out = mapper.createObjectNode();
        out.put("insufficient", true);
        out.put("reason", reason);
        return out;
    }
}
