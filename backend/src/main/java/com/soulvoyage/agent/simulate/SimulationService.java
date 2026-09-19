package com.soulvoyage.agent.simulate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.simulate.SimulateSessionEntity;
import com.soulvoyage.domain.simulate.SimulateSessionRepository;
import com.soulvoyage.domain.simulate.SimulateTurnEntity;
import com.soulvoyage.domain.simulate.SimulateTurnRepository;
import com.soulvoyage.llm.LlmClient;
import com.soulvoyage.llm.OutputValidator;
import com.soulvoyage.llm.PromptTemplates;
import com.soulvoyage.orchestrator.OrchestratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 模拟训练会话服务（UC2 前半，手册 §6.4）：开场 → 逐轮对话（导演裁决情绪档位 + LLM 人设措辞）→ 交棒复盘。
 * 多轮循环留在会话层（每轮是无状态 HTTP/SSE 请求）；复盘走调度中心 SIMULATE_PIPELINE。
 * NPC"多生气"由 NpcDirector 规则层裁决，LLM 只负责按档位措辞——冲突升级可控、可测。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimulationService {

    /** 危机兜底跳出话术（手册 §4.3 兜底 / §7.3 SOP 第②步：会话内温和话术，不评判不诊断） */
    public static final String CRISIS_BREAK_LINE =
            "（我先跳出角色）你刚才说的话让我担心的是你本人，不是这段剧情。愿意多和我说说吗？"
                    + "如果此刻很难熬，请联系信任的人，或随时拨打心理援助热线 12356。";

    private static final int TRANSCRIPT_WINDOW = 6;

    private final SceneCatalog scenes;
    private final LlmClient llm;
    private final PromptTemplates templates;
    private final OutputValidator validator;
    private final CryptoService crypto;
    private final SimulateSessionRepository sessionRepo;
    private final SimulateTurnRepository turnRepo;
    private final OrchestratorService orchestrator;
    private final ObjectMapper mapper;

    public record Opened(long simulateId, String sceneCode, String title, String background,
                         String npcName, String relation, String openingLine,
                         int maxTurns, List<String> goalDimensions) {}

    public record TurnResult(int turnNo, String npcText, String emotionCue, String npcEmotion,
                             int tension, String stateTag, boolean crisis, boolean maxTurnsReached) {}

    /** 开场：只创建会话与首句（配置化开场白，不消耗 LLM 调用） */
    public Opened open(long userId, String sceneCode, String difficulty) {
        SceneCard scene = scenes.require(sceneCode);
        String diff = (difficulty == null || difficulty.isBlank()) ? "NORMAL" : difficulty;
        if (!scene.supports(diff)) {
            throw new BizException(ErrorCode.BAD_PARAMS, "该场景不支持难度: " + diff);
        }
        SimulateSessionEntity s = new SimulateSessionEntity();
        s.setUserId(userId);
        s.setSceneCode(scene.code());
        s.setDifficulty(diff);
        s.setNpcStateSnapEnc(crypto.encryptUserField(userId,
                mapper.createObjectNode().put("tension", initialTension(diff)).toString()));
        s = sessionRepo.save(s);
        return new Opened(s.getId(), scene.code(), scene.title(), scene.description(),
                scene.npcName(), scene.relation(), scene.openingLine(diff),
                scene.maxTurns(), scene.goalDimensions());
    }

    public TurnResult turn(long userId, long simulateId, String userText) {
        SimulateSessionEntity s = owned(userId, simulateId);
        if (!"RUNNING".equals(s.getStatus())) {
            throw new BizException(ErrorCode.BAD_PARAMS, "会话当前状态不可继续对话: " + s.getStatus());
        }
        SceneCard scene = scenes.require(s.getSceneCode());
        if (userText == null || userText.isBlank()) {
            throw new BizException(ErrorCode.BAD_PARAMS, "发言不能为空");
        }
        if (userText.length() > 500) {
            throw new BizException(ErrorCode.BAD_PARAMS, "单轮发言过长（≤500字）");
        }
        if (s.getTotalTurns() >= scene.maxTurns()) {
            throw new BizException(ErrorCode.BAD_PARAMS, "轮数已达上限，请结束会话查看复盘");
        }

        int prevTension = readTension(userId, s);
        NpcDirector.Decision d = NpcDirector.react(s.getDifficulty(), userText, prevTension);

        int turnNo = s.getTotalTurns() + 1;
        String npcText;
        String emotionCue;
        if (d.crisis()) {
            npcText = CRISIS_BREAK_LINE;          // 剧情外真实危机：立即温和退出，不经 LLM
            emotionCue = "放下角色，转向关心你这个人";
            s.setStatus("ABORTED_RISK");
            s.setFinishedAt(Instant.now());
        } else {
            try {
                JsonNode reply = npcLlmReply(s, scene, d, turnNo, userText);
                npcText = reply.path("reply").asText();
                emotionCue = reply.path("emotionCue").asText("");
            } catch (Exception e) {
                // 单轮输出校验失败不杀会话：按导演档位走剧本兜底措辞
                log.warn("npc reply degraded at sim={} turn={}: {}", simulateId, turnNo, e.getMessage());
                npcText = scriptedFallback(scene, d);
                emotionCue = d.mood().name();
            }
        }

        persistTurn(userId, s, turnNo, userText, npcText, d);
        updateDirectorState(userId, s, d);

        return new TurnResult(turnNo, npcText, emotionCue, d.mood().name(), d.tension(),
                d.stateTag(), d.crisis(), s.getTotalTurns() >= scene.maxTurns());
    }

    /** 结束：提交调度中心复盘任务（202 语义，结果经 GET /tasks/{taskNo} 与 /reports/{id} 获取） */
    public String finish(long userId, long simulateId) {
        SimulateSessionEntity s = owned(userId, simulateId);
        if ("FINISHED".equals(s.getStatus()) || s.getReportId() != null) {
            throw new BizException(ErrorCode.BAD_PARAMS, "会话已复盘，请勿重复结束");
        }
        if (s.getTotalTurns() == 0) {
            throw new BizException(ErrorCode.BAD_PARAMS, "至少完成一轮对话才能复盘");
        }
        ObjectNode input = mapper.createObjectNode().put("simulateId", simulateId);
        return orchestrator.submit(userId, "SIMULATE_PIPELINE", input, null).getTaskNo();
    }

    /** 会话回放（属主解密）：前端刷新/复盘页对照原文 */
    public JsonNode transcript(long userId, long simulateId) throws Exception {
        SimulateSessionEntity s = owned(userId, simulateId);
        SceneCard scene = scenes.require(s.getSceneCode());
        var root = mapper.createObjectNode();
        root.put("simulateId", s.getId());
        root.put("sceneCode", s.getSceneCode());
        root.put("sceneTitle", scene.title());
        root.put("npcName", scene.npcName());
        root.put("difficulty", s.getDifficulty());
        root.put("status", s.getStatus());
        root.put("totalTurns", s.getTotalTurns());
        root.put("maxTurns", scene.maxTurns());
        if (s.getReportId() != null) root.put("reportId", "rp_" + s.getReportId());
        ArrayNode arr = root.putArray("turns");
        for (SimulateTurnEntity t : turnRepo.findBySimulateIdOrderByTurnNoAsc(simulateId)) {
            ObjectNode n = arr.addObject();
            n.put("turnNo", t.getTurnNo());
            n.put("userText", crypto.decryptUserField(userId, t.getUserTextEnc()));
            n.put("npcText", t.getNpcText());
            n.put("npcEmotion", t.getNpcEmotion());
            n.put("stateTag", t.getStateTag());
            n.put("crisis", t.getCrisisFlag() == 1);
        }
        return root;
    }

    public SimulateSessionEntity owned(long userId, long simulateId) {
        return sessionRepo.findByIdAndUserId(simulateId, userId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND));
    }

    // ---------------- internals ----------------

    private JsonNode npcLlmReply(SimulateSessionEntity s, SceneCard scene,
                                 NpcDirector.Decision d, int turnNo, String userText) {
        String system = templates.render(templates.system("npc_v1"), java.util.Map.of(
                "sceneTitle", scene.title(),
                "sceneBackground", scene.description(),
                "npcName", scene.npcName(),
                "relation", scene.relation(),
                "motivation", scene.persona().motivation(),
                "bottomLine", scene.persona().bottomLine(),
                "triggers", scene.persona().triggers(),
                "style", scene.persona().style(),
                "mood", d.mood().name(),
                "intensity", String.valueOf(d.intensity())));
        String user = buildTurnPayload(s, scene, d, turnNo, userText);
        var resp = llm.chat(new LlmClient.LlmRequest("npc_v1", system, user, 400));
        return validator.validate("npc_reply.json", resp.content());
    }

    /** NPC 只知道说出口的话：最近窗口逐字稿 + 本轮输入（手册 §4.3 "不读心"） */
    private String buildTurnPayload(SimulateSessionEntity s, SceneCard scene,
                                    NpcDirector.Decision d, int turnNo, String userText) {
        var root = mapper.createObjectNode();
        root.put("sceneCode", scene.code());
        root.put("difficulty", s.getDifficulty());
        root.put("mood", d.mood().name());
        root.put("intensity", d.intensity());
        root.put("turnNo", turnNo);
        ArrayNode tr = root.putArray("transcript");
        List<SimulateTurnEntity> history = turnRepo.findBySimulateIdOrderByTurnNoAsc(s.getId());
        int from = Math.max(0, history.size() - TRANSCRIPT_WINDOW);
        for (SimulateTurnEntity t : history.subList(from, history.size())) {
            tr.addObject()
                    .put("turn", t.getTurnNo())
                    .put("user", crypto.decryptUserField(s.getUserId(), t.getUserTextEnc()))
                    .put("npc", t.getNpcText());
        }
        tr.addObject().put("turn", turnNo).put("user", userText);
        return root.toString();
    }

    private String scriptedFallback(SceneCard scene, NpcDirector.Decision d) {
        return switch (d.mood()) {
            case ESCALATED -> "你看，又是这样，说什么都能吵起来。" + scene.npcName() + "不想继续这个调子了。";
            case DISSATISFIED -> "先别急着给我下结论，我把话说完行吗？这事没你想的那么简单。";
            case SOFTENED -> "……行，你说的这点我认同。那我们看看具体怎么办。";
            default -> "嗯，你说的我听见了。那你想怎么弄？";
        };
    }

    private void persistTurn(long userId, SimulateSessionEntity s, int turnNo,
                             String userText, String npcText, NpcDirector.Decision d) {
        SimulateTurnEntity t = new SimulateTurnEntity();
        t.setSimulateId(s.getId());
        t.setTurnNo(turnNo);
        t.setUserTextEnc(crypto.encryptUserField(userId, userText));
        t.setNpcText(npcText);
        t.setNpcEmotion(d.mood().name());
        t.setStateTag(d.stateTag());
        t.setCrisisFlag((short) (d.crisis() ? 1 : 0));
        turnRepo.save(t);
        s.setTotalTurns(turnNo);
        sessionRepo.save(s);
    }

    private int initialTension(String difficulty) {
        return switch (difficulty) {
            case "MILD" -> 15;
            case "HARD" -> 45;
            default -> 28;
        };
    }

    private int readTension(long userId, SimulateSessionEntity s) {
        try {
            return mapper.readTree(crypto.decryptUserField(userId, s.getNpcStateSnapEnc()))
                    .path("tension").asInt(28);
        } catch (Exception e) {
            return 28;
        }
    }

    private void updateDirectorState(long userId, SimulateSessionEntity s, NpcDirector.Decision d) {
        try {
            ObjectNode prev = mapper.readValue(
                    crypto.decryptUserField(userId, s.getNpcStateSnapEnc()), ObjectNode.class);
            prev.put("tension", d.tension());
            prev.put("mood", d.mood().name());
            if (d.stateTag() != null) {
                prev.putArray("lastEvents").add(d.stateTag());
            }
            s.setNpcStateSnapEnc(crypto.encryptUserField(userId, prev.toString()));
            sessionRepo.save(s);
        } catch (Exception e) {
            log.warn("director snapshot update failed: sim={}", s.getId(), e);
        }
    }
}
