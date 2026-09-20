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
import java.util.Map;

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

    /** 开场：只创建会话与首句（配置化开场白，不消耗 LLM 调用）；同场景有历史弱项时写入教练记忆 */
    public Opened open(long userId, String sceneCode, String difficulty) {
        SceneCard scene = scenes.require(sceneCode);
        String diff = (difficulty == null || difficulty.isBlank()) ? "NORMAL" : difficulty;
        if (!scene.supports(diff)) {
            throw new BizException(ErrorCode.BAD_PARAMS, "该场景不支持难度: " + diff);
        }
        ObjectNode snap = mapper.createObjectNode().put("tension", initialTension(diff));
        coachMemory(userId, scene).ifPresent(m -> snap.put("coachMemory", m));
        SimulateSessionEntity s = new SimulateSessionEntity();
        s.setUserId(userId);
        s.setSceneCode(scene.code());
        s.setDifficulty(diff);
        s.setNpcStateSnapEnc(crypto.encryptUserField(userId, snap.toString()));
        s = sessionRepo.save(s);
        return new Opened(s.getId(), scene.code(), scene.title(), scene.description(),
                scene.npcName(), scene.relation(), scene.openingLine(diff),
                scene.maxTurns(), scene.goalDimensions());
    }

    /** C3 弱项注入：同场景上次复盘的 weaknesses 原话，NPC 据此制造练习机会 */
    private java.util.Optional<String> coachMemory(long userId, SceneCard scene) {
        return sessionRepo.findFirstByUserIdAndSceneCodeAndWeaknessesEncIsNotNullOrderByIdDesc(userId, scene.code())
                .flatMap(x -> {
                    try {
                        JsonNode w = mapper.readTree(crypto.decryptUserField(userId, x.getWeaknessesEnc()));
                        if (!w.isArray() || w.isEmpty()) return java.util.Optional.empty();
                        var sb = new StringBuilder("TA 上次在这个场景的待改进点：");
                        for (int i = 0; i < w.size(); i++) {
                            sb.append(w.get(i).asText());
                            sb.append(i < w.size() - 1 ? "；" : "。");
                        }
                        return java.util.Optional.of(sb.toString());
                    } catch (Exception e) {
                        return java.util.Optional.empty();   // 密钥销毁/坏数据：视作无记忆
                    }
                });
    }

    public TurnResult turn(long userId, long simulateId, String userText) {
        SimulateSessionEntity s = owned(userId, simulateId);
        if ("INTERRUPTED".equals(s.getStatus())) {
            s.setStatus("RUNNING");     // C3 续练：中断会话可直接接着说
        } else if (!"RUNNING".equals(s.getStatus())) {
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

    /** C3 中途退出：对话留档为 INTERRUPTED，可续练也可事后复盘已进行的轮次 */
    public String interrupt(long userId, long simulateId) {
        SimulateSessionEntity s = owned(userId, simulateId);
        if ("RUNNING".equals(s.getStatus())) {
            s.setStatus("INTERRUPTED");
            sessionRepo.save(s);
        }
        return s.getStatus();
    }

    /** C3 会话列表（分页 + 可选状态过滤） */
    public Map<String, Object> list(long userId, String status, int page, int size) {
        var pr = org.springframework.data.domain.PageRequest.of(page, Math.min(Math.max(size, 1), 50));
        var result = (status == null || status.isBlank())
                ? sessionRepo.findByUserIdOrderByStartedAtDesc(userId, pr)
                : sessionRepo.findByUserIdAndStatusOrderByStartedAtDesc(userId, status, pr);
        var items = result.getContent().stream().map(s -> {
            Map<String, Object> n = new java.util.LinkedHashMap<String, Object>();
            n.put("simulateId", s.getId());
            n.put("sceneCode", s.getSceneCode());
            n.put("sceneTitle", sceneTitle(s.getSceneCode()));
            n.put("difficulty", s.getDifficulty());
            n.put("status", s.getStatus());
            n.put("totalTurns", s.getTotalTurns());
            n.put("avgScore", s.getAvgScore());
            n.put("dimensionScores", parseDims(s.getDimensionScores()));
            n.put("startedAt", s.getStartedAt() == null ? "" : s.getStartedAt().toString());
            n.put("finishedAt", s.getFinishedAt() == null ? "" : s.getFinishedAt().toString());
            n.put("reportId", s.getReportId() == null ? null : "rp_" + s.getReportId());
            return n;
        }).toList();
        return Map.of("items", items, "page", result.getNumber(),
                "size", result.getSize(), "total", result.getTotalElements());
    }

    /** C3 训练历史卡：每场景 best/avg 分 + 上次四维雷达 */
    public List<Map<String, Object>> stats(long userId) {
        Map<String, List<SimulateSessionEntity>> byScene = new java.util.LinkedHashMap<>();
        for (SimulateSessionEntity s : sessionRepo.findByUserId(userId)) {
            byScene.computeIfAbsent(s.getSceneCode(), k -> new java.util.ArrayList<>()).add(s);
        }
        var out = new java.util.ArrayList<Map<String, Object>>();
        byScene.forEach((code, list) -> {
            Map<String, Object> n = new java.util.LinkedHashMap<>();
            n.put("sceneCode", code);
            n.put("sceneTitle", sceneTitle(code));
            n.put("times", list.size());
            java.util.OptionalDouble best = list.stream().map(SimulateSessionEntity::getAvgScore)
                    .filter(java.util.Objects::nonNull).mapToDouble(java.math.BigDecimal::doubleValue).max();
            java.util.OptionalDouble avg = list.stream().map(SimulateSessionEntity::getAvgScore)
                    .filter(java.util.Objects::nonNull).mapToDouble(java.math.BigDecimal::doubleValue).average();
            n.put("bestScore", best.isPresent() ? Math.round(best.getAsDouble() * 10) / 10.0 : null);
            n.put("avgScore", avg.isPresent() ? Math.round(avg.getAsDouble() * 10) / 10.0 : null);
            list.stream()
                    .max(java.util.Comparator.comparing(SimulateSessionEntity::getId))
                    .filter(x -> x.getDimensionScores() != null)   // 同场景最新一次的雷达
                    .ifPresent(x -> n.put("lastDimensions", parseDims(x.getDimensionScores())));
            out.add(n);
        });
        return out;
    }

    private String sceneTitle(String code) {
        return scenes.list().stream().filter(c -> c.code().equals(code))
                .map(SceneCard::title).findFirst().orElse(code);
    }

    private Object parseDims(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Double>>() {});
        } catch (Exception e) {
            return Map.of();
        }
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
        String system = templates.render(templates.system("npc_v1"), Map.ofEntries(
                Map.entry("sceneTitle", scene.title()),
                Map.entry("sceneBackground", scene.description()),
                Map.entry("npcName", scene.npcName()),
                Map.entry("relation", scene.relation()),
                Map.entry("motivation", scene.persona().motivation()),
                Map.entry("bottomLine", scene.persona().bottomLine()),
                Map.entry("triggers", scene.persona().triggers()),
                Map.entry("style", scene.persona().style()),
                Map.entry("mood", d.mood().name()),
                Map.entry("intensity", String.valueOf(d.intensity())),
                Map.entry("coachMemory", readCoachMemory(s))));
        String user = buildTurnPayload(s, scene, d, turnNo, userText);
        var resp = llm.chat(new LlmClient.LlmRequest("npc_v1", system, user, 400));
        return validator.validate("npc_reply.json", resp.content());
    }

    private String readCoachMemory(SimulateSessionEntity s) {
        try {
            JsonNode snap = mapper.readTree(
                    crypto.decryptUserField(s.getUserId(), s.getNpcStateSnapEnc()));
            if (snap.hasNonNull("coachMemory")) return snap.path("coachMemory").asText();
        } catch (Exception ignore) {
            // 快照坏/密钥销毁：按首次训练处理
        }
        return "这是你们第一次把这件事摆到台面上，没有历史包袱。";
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
