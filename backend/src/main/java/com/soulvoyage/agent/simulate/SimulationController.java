package com.soulvoyage.agent.simulate;

import com.fasterxml.jackson.databind.JsonNode;
import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.common.exception.BizException;
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
    public ApiResponse<Map<String, Object>> open(@AuthenticationPrincipal AuthPrincipal p,
                                                 @Valid @RequestBody OpenReq req) {
        var o = service.open(p.userId(), req.sceneCode(), req.difficulty());
        return ApiResponse.ok(Map.of(
                "simulateId", o.simulateId(), "sceneCode", o.sceneCode(), "title", o.title(),
                "background", o.background(), "npcName", o.npcName(), "relation", o.relation(),
                "difficulty", req.difficulty() == null || req.difficulty().isBlank() ? "NORMAL" : req.difficulty(),
                "openingLine", o.openingLine(), "maxTurns", o.maxTurns(),
                "goalDimensions", o.goalDimensions()));
    }

    @GetMapping("/simulations/{id}")
    public ApiResponse<JsonNode> transcript(@AuthenticationPrincipal AuthPrincipal p,
                                            @PathVariable Long id) throws Exception {
        return ApiResponse.ok(service.transcript(p.userId(), id));
    }

    /** 逐轮：SSE 流式回 NPC 文本（npc_delta × n → turn_done），危机兜底额外发 crisis 事件 */
    @PostMapping(value = "/simulations/{id}/turns", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter turn(@AuthenticationPrincipal AuthPrincipal p,
                           @PathVariable Long id,
                           @Valid @RequestBody TurnReq req) {
        SseEmitter emitter = new SseEmitter(60_000L);
        SimulationService.TurnResult r;
        try {
            r = service.turn(p.userId(), id, req.userText());
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
            for (String chunk : splitForStream(r.npcText())) {
                emitter.send(SseEmitter.event().name("npc_delta")
                        .data(Map.of("text", chunk), MediaType.APPLICATION_JSON));
                Thread.sleep(40);   // 打字机节奏；接真实流式模型后替换为 token 回调
            }
            if (r.crisis()) {
                emitter.send(SseEmitter.event().name("crisis").data(Map.of(
                        "level", "HIGH", "hotline", "12356",
                        "message", "检测到剧情外的真实危机信号，已温和退出扮演。")) );
            }
            emitter.send(SseEmitter.event().name("turn_done").data(Map.of(
                    "turnNo", r.turnNo(), "npcEmotion", r.npcEmotion(), "tension", r.tension(),
                    "stateTag", r.stateTag() == null ? "" : r.stateTag(),
                    "crisis", r.crisis(), "maxTurnsReached", r.maxTurnsReached())));
            emitter.complete();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            emitter.completeWithError(ie);
        } catch (Exception e) {
            log.warn("turn sse failed sim={}", id, e);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /** C3 会话列表：?status=INTERRUPTED 可单独捞"上次没练完" */
    @GetMapping("/simulations")
    public ApiResponse<Map<String, Object>> list(@AuthenticationPrincipal AuthPrincipal p,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(service.list(p.userId(), status, page, size));
    }

    /** C3 训练历史卡：每场景 best/avg/上次四维雷达 */
    @GetMapping("/simulations/stats")
    public ApiResponse<List<Map<String, Object>>> stats(@AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.stats(p.userId()));
    }

    @PostMapping("/simulations/{id}/finish")
    public ResponseEntity<ApiResponse<Map<String, String>>> finish(
            @AuthenticationPrincipal AuthPrincipal p, @PathVariable Long id) {
        String taskNo = service.finish(p.userId(), id);
        return ResponseEntity.accepted().body(ApiResponse.ok(Map.of("taskNo", taskNo)));
    }

    /** C3 中途退出：留档 INTERRUPTED（可续练/可复盘），下一轮发言自动回到进行中 */
    @PostMapping("/simulations/{id}/interrupt")
    public ApiResponse<Map<String, String>> interrupt(@AuthenticationPrincipal AuthPrincipal p,
                                                      @PathVariable Long id) {
        return ApiResponse.ok(Map.of("status", service.interrupt(p.userId(), id)));
    }

    static List<String> splitForStream(String text) {
        List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < text.length(); i += 12) {
            out.add(text.substring(i, Math.min(text.length(), i + 12)));
        }
        return out;
    }
}
