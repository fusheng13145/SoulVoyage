package com.soulvoyage.agent.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.domain.companion.CompanionSessionEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 树洞漫聊接口（下篇·C0，下篇·八）：打开/续聊、逐轮 SSE（含危机事件）、这句别分析、收段、历史回放。
 * SSE 事件协议与 simulate 同构（ai_delta → [crisis] → turn_done），前端 sse.postSse 直接复用。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/companion")
@RequiredArgsConstructor
public class CompanionController {

    private final CompanionService service;

    public record TurnReq(String userText) {}

    /** 今日活跃会话（续聊胶囊）；无活跃段返回 data=null */
    @GetMapping("/active")
    public ApiResponse<CompanionService.SessionView> active(@AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.activeView(p.userId()));
    }

    /** 打开/续聊：同段直接续，静默超窗或跨日自动开新段 */
    @PostMapping("/sessions")
    public ApiResponse<CompanionService.SessionView> open(@AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.openOrReuse(p.userId()));
    }

    @GetMapping("/sessions")
    public ApiResponse<Map<String, Object>> history(@AuthenticationPrincipal AuthPrincipal p,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        Page<CompanionSessionEntity> ps = service.history(p.userId(),
                PageRequest.of(page, Math.min(size, 50)));
        return ApiResponse.ok(Map.of(
                "page", ps.getNumber(), "size", ps.getSize(), "total", ps.getTotalElements(),
                "sessions", ps.getContent().stream().map(s -> Map.<String, Object>of(
                        "sessionId", s.getId(), "chatDate", s.getChatDate().toString(),
                        "segmentNo", s.getSegmentNo(), "status", s.getStatus(), "turns", s.getTurns()
                )).toList()));
    }

    @GetMapping("/sessions/{id}")
    public ApiResponse<JsonNode> transcript(@AuthenticationPrincipal AuthPrincipal p,
                                            @PathVariable Long id) throws Exception {
        return ApiResponse.ok(service.transcript(p.userId(), id));
    }

    /**
     * 逐轮：SSE 流式（ai_delta × n →（危机时）crisis → turn_done）。
     * ai_delta 是模型边生成边转发的真流式增量，只含 reply 字段文本；turn_done.aiText 是该轮权威全文
     * （危机话术、防依赖引导、校验失败降级都可能与增量拼接结果不同，客户端以它收尾）。
     */
    @PostMapping(value = "/sessions/{id}/turns", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter turn(@AuthenticationPrincipal AuthPrincipal p,
                           @PathVariable Long id,
                           @RequestBody TurnReq req) {
        SseEmitter emitter = new SseEmitter(60_000L);
        AtomicBoolean pipeBroken = new AtomicBoolean(false);
        CompanionService.TurnResult r;
        try {
            r = service.turn(p.userId(), id, req.userText(), delta -> {
                if (pipeBroken.get()) return;
                try {
                    emitter.send(SseEmitter.event().name("ai_delta")
                            .data(Map.of("text", delta), MediaType.APPLICATION_JSON));
                } catch (Exception e) {
                    pipeBroken.set(true);   // 客户端已走：停止外发，回合照常落库（成本不白花）
                }
            });
        } catch (BizException e) {
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
                        "message", "检测到危机信号，已为你放上求助资源。你不需要一个人扛。")));
            }
            emitter.send(SseEmitter.event().name("turn_done").data(Map.of(
                    "turnId", r.turnId(), "turnNo", r.turnNo(), "moodTag", r.moodTag(),
                    "aiText", r.aiText(), "crisis", r.crisis(), "guidanceShown", r.guidanceShown(),
                    "remainingToday", r.remainingToday())));
            emitter.complete();
        } catch (Exception e) {
            log.warn("companion turn sse failed session={}", id, e);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /** 「这句别分析」：下一次管道消化排除该轮（用户自主权，C0） */
    @PostMapping("/turns/{turnId}/no-analyze")
    public ApiResponse<Map<String, Object>> noAnalyze(@AuthenticationPrincipal AuthPrincipal p,
                                                      @PathVariable Long turnId) {
        service.markNoAnalyze(p.userId(), turnId);
        return ApiResponse.ok(Map.of("turnId", turnId, "noAnalyze", true));
    }

    /** 收段：封口 + 交 COMPANION_PIPELINE，202 语义返回 taskNo；无可消化内容时为空串（不伪造任务） */
    @PostMapping("/sessions/{id}/end")
    public ResponseEntity<ApiResponse<Map<String, Object>>> end(@AuthenticationPrincipal AuthPrincipal p,
                                                                @PathVariable Long id) {
        String taskNo = service.end(p.userId(), id);
        return ResponseEntity.accepted().body(ApiResponse.ok(Map.of(
                "sessionId", id, "taskNo", taskNo == null ? "" : taskNo)));
    }
}
