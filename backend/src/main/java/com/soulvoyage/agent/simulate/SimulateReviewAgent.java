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

        String system = templates.render(templates.system("simulate_review_v1"), Map.of(
                "goalDimensions", String.join("、", scene.goalDimensions())));
        var resp = llm.chat(new LlmClient.LlmRequest("simulate_review_v1",
                system, buildUserPayload(session, scene, turns), 1800));
        JsonNode result = validator.validate(rt.spec().outputSchema(), resp.content());

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
        sessionRepo.save(session);

        ObjectNode out = result.deepCopy();
        out.put("reportId", "rp_" + report.getId());
        out.put("simulateId", simulateId);
        return out;
    }

    /** 复盘输入：解密逐轮原文 + 导演档位/关键事件（评分以用户原话为准，标签仅参考） */
    private String buildUserPayload(SimulateSessionEntity s, SceneCard scene,
                                    Iterable<SimulateTurnEntity> turns) {
        var root = mapper.createObjectNode();
        root.put("sceneCode", scene.code());
        root.put("sceneTitle", scene.title());
        root.put("difficulty", s.getDifficulty());
        root.put("npcName", scene.npcName());
        root.put("npcRelation", scene.relation());
        root.set("goalDimensions", mapper.valueToTree(scene.goalDimensions()));
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
