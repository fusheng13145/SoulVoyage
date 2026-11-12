package com.soulvoyage.agent.simulate;

import com.fasterxml.jackson.databind.JsonNode;
import com.soulvoyage.agent.simulate.dto.SimulationDtos.InterruptResp;
import com.soulvoyage.agent.simulate.dto.SimulationDtos.OpenSceneResp;
import com.soulvoyage.agent.simulate.dto.SimulationDtos.SimulationListItem;
import com.soulvoyage.agent.simulate.dto.SimulationDtos.SimulationListResp;
import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.orchestrator.api.TaskDtos.TaskNoResp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UC2 模拟训练接口（手册 §6.4）：场景列表 / 开场 / 逐轮（SSE 流式 NPC）/ 结束（202 复盘任务）。
 * 全部登录态 + 属主断言（会话按 userId 联合查询，天然防 IDOR）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SimulationController {

    private final SceneCatalog scenes;
    private final SimulationService service;

    public record OpenReq(@NotBlank String sceneCode, String difficulty) {}

    public record TurnReq(@NotBlank String userText) {}

    @GetMapping("/scenes")
    public ApiResponse<List<Map<String, Object>>> listScenes() {
        return ApiResponse.ok(scenes.list().stream().map(c -> Map.<String, Object>of(
                "code", c.code(), "title", c.title(), "description", c.description(),
                "npcName", c.npcName(), "relation", c.relation(),
                "difficulties", c.difficulties(), "goalDimensions", c.goalDimensions(),
                "maxTurns", c.maxTurns(),
                "tags", c.tags(), "recommendedFor", c.recommendedFor())).toList());
    }

    @PostMapping("/simulations")
    public ApiResponse<OpenSceneResp> open(@AuthenticationPrincipal AuthPrincipal p,
                                           @Valid @RequestBody OpenReq req) {
        var o = service.open(p.userId(), req.sceneCode(), req.difficulty());
        return ApiResponse.ok(new OpenSceneResp(
                o.simulateId(), o.sceneCode(), o.title(), o.background(),
                o.npcName(), o.relation(),
                req.difficulty() == null || req.difficulty().isBlank() ? "NORMAL" : req.difficulty(),
                o.openingLine(), o.maxTurns(), o.goalDimensions()));
    }

    @GetMapping("/simulations/{id}")
    public ApiResponse<JsonNode> transcript(@AuthenticationPrincipal AuthPrincipal p,
                                            @PathVariable Long id) throws Exception {
        return ApiResponse.ok(service.transcript(p.userId(), id));
    }

    /**
     * 逐轮：SSE 流式回 NPC 文本（npc_delta × n → turn_done），危机兜底额外发 crisis 事件。
     * npc_delta 为模型真流式增量（只含 reply 字段），turn_done.npcText 是权威全文——降级/危机退出会以它为准。
     */
    @PostMapping(value = "/simulations/{id}/turns", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter turn(@AuthenticationPrincipal AuthPrincipal p,
                           @PathVariable Long id,
                           @Valid @RequestBody TurnReq req) {
        SseEmitter emitter = new SseEmitter(60_000L);
        AtomicBoolean pipeBroken = new AtomicBoolean(false);
        SimulationService.TurnResult r;
        try {
            r = service.turn(p.userId(), id, req.userText(), delta -> {
                if (pipeBroken.get()) return;
                try {
                    emitter.send(SseEmitter.event().name("npc_delta")
                            .data(Map.of("text", delta), MediaType.APPLICATION_JSON));
                } catch (Exception e) {
                    pipeBroken.set(true);   // 客户端断开：不再外发，回合仍落库
                }
            });
        } catch (BizException e) {
            // SSE 上下文里业务失败也以事件下发，前端统一在流内处理
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("code", e.getErrorCode().code(), "msg", e.getMessage())));
                emitter.complete();
            } catch (Exception ignore) {
                emitter.completeWithError(e);
            }
            return emitter;
        }
        try {
            if (r.crisis()) {
                emitter.send(SseEmitter.event().name("crisis").data(Map.of(
                        "level", "HIGH", "hotline", "12356",
                        "message", "检测到剧情外的真实危机信号，已温和退出扮演。")) );
            }
            emitter.send(SseEmitter.event().name("turn_done").data(Map.of(
                    "turnNo", r.turnNo(), "npcEmotion", r.npcEmotion(), "tension", r.tension(),
                    "stateTag", r.stateTag() == null ? "" : r.stateTag(),
                    "npcText", r.npcText(), "emotionCue", r.emotionCue(),
                    "crisis", r.crisis(), "maxTurnsReached", r.maxTurnsReached())));
            emitter.complete();
        } catch (Exception e) {
            log.warn("turn sse failed sim={}", id, e);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /** C3 会话列表：?status=INTERRUPTED 可单独捞"上次没练完" */
    @GetMapping("/simulations")
    @SuppressWarnings("unchecked")
    public ApiResponse<SimulationListResp> list(@AuthenticationPrincipal AuthPrincipal p,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "10") int size) {
        Map<String, Object> m = service.list(p.userId(), status, page, size);
        List<SimulationListItem> items = ((List<Map<String, Object>>) m.get("items")).stream()
                .map(x -> new SimulationListItem(
                        ((Number) x.get("simulateId")).longValue(), (String) x.get("sceneCode"),
                        (String) x.get("sceneTitle"), (String) x.get("difficulty"),
                        (String) x.get("status"), ((Number) x.get("totalTurns")).intValue(),
                        (java.math.BigDecimal) x.get("avgScore"), x.get("dimensionScores"),
                        (String) x.get("startedAt"), (String) x.get("finishedAt"),
                        (String) x.get("reportId")))
                .toList();
        return ApiResponse.ok(new SimulationListResp(
                items, ((Number) m.get("page")).intValue(),
                ((Number) m.get("size")).intValue(), ((Number) m.get("total")).longValue()));
    }

    /** C3 训练历史卡：每场景 best/avg/上次四维雷达 */
    @GetMapping("/simulations/stats")
    public ApiResponse<List<Map<String, Object>>> stats(@AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.stats(p.userId()));
    }

    @PostMapping("/simulations/{id}/finish")
    public ResponseEntity<ApiResponse<TaskNoResp>> finish(
            @AuthenticationPrincipal AuthPrincipal p, @PathVariable Long id) {
        String taskNo = service.finish(p.userId(), id);
        return ResponseEntity.accepted().body(ApiResponse.ok(new TaskNoResp(taskNo)));
    }

    /** C3 中途退出：留档 INTERRUPTED（可续练/可复盘），下一轮发言自动回到进行中 */
    @PostMapping("/simulations/{id}/interrupt")
    public ApiResponse<InterruptResp> interrupt(@AuthenticationPrincipal AuthPrincipal p,
                                                @PathVariable Long id) {
        return ApiResponse.ok(new InterruptResp(service.interrupt(p.userId(), id)));
    }
}
