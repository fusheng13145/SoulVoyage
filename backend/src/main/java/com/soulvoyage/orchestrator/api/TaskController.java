package com.soulvoyage.orchestrator.api;

import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.domain.diary.DiaryService;
import com.soulvoyage.domain.task.TaskInstanceEntity;
import com.soulvoyage.orchestrator.OrchestratorService;
import com.soulvoyage.orchestrator.api.TaskDtos.SubmitReq;
import com.soulvoyage.orchestrator.api.TaskDtos.TaskView;
import com.soulvoyage.orchestrator.sse.TaskEventBus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final OrchestratorService orchestrator;
    private final TaskEventBus bus;
    private final com.soulvoyage.domain.diary.DiaryService diary;
    private final com.soulvoyage.domain.task.TaskInstanceRepository tasks;

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, String>>> submit(
            @AuthenticationPrincipal AuthPrincipal p, @Valid @RequestBody SubmitReq req) {
        TaskInstanceEntity t = orchestrator.submit(p.userId(), req.pipelineCode(),
                req.payload(), req.clientReqId());
        // 下篇·C1：日记流水线提交即同步落库（clientReqId 幂等重放不会重复写）
        if ("DIARY_PIPELINE".equals(req.pipelineCode()) && req.payload() != null) {
            diary.attachTask(p.userId(), req.payload(), t);
        }
        return ResponseEntity.accepted()
                .body(ApiResponse.ok(Map.of("taskNo", t.getTaskNo(), "status", t.getStatus())));
    }

    /** C5 任务历史列表：pipelineCode/status 可选过滤（只回元信息，产物走 /tasks/{taskNo}） */
    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @AuthenticationPrincipal AuthPrincipal p,
            @RequestParam(required = false) String pipelineCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        var result = tasks.findUserTasks(p.userId(),
                blankToNull(pipelineCode), blankToNull(status),
                org.springframework.data.domain.PageRequest.of(page, Math.min(Math.max(size, 1), 50)));
        var items = result.getContent().stream().map(t -> {
            Map<String, Object> n = new java.util.LinkedHashMap<String, Object>();
            n.put("taskNo", t.getTaskNo());
            n.put("pipelineCode", t.getPipelineCode());
            n.put("status", t.getStatus());
            n.put("createdAt", t.getCreatedAt() == null ? "" : t.getCreatedAt().toString());
            n.put("finishedAt", t.getFinishedAt() == null ? "" : t.getFinishedAt().toString());
            n.put("errorMsg", t.getErrorMsg());
            return n;
        }).toList();
        return ApiResponse.ok(Map.of("items", items, "page", result.getNumber(),
                "size", result.getSize(), "total", result.getTotalElements()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    @GetMapping("/{taskNo}")
    public ApiResponse<TaskView> get(@AuthenticationPrincipal AuthPrincipal p, @PathVariable String taskNo) {
        TaskInstanceEntity t = owned(p, taskNo);
        return ApiResponse.ok(new TaskView(t.getTaskNo(), t.getPipelineCode(), t.getStatus(),
                orchestrator.finalPayload(t), t.getErrorMsg(),
                t.getCreatedAt() == null ? null : t.getCreatedAt().toString()));
    }

    @GetMapping("/{taskNo}/stream")
    public SseEmitter stream(@AuthenticationPrincipal AuthPrincipal p, @PathVariable String taskNo,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        owned(p, taskNo);
        return bus.subscribe(taskNo, lastEventId);
    }

    @PostMapping("/{taskNo}/cancel")
    public ApiResponse<Void> cancel(@AuthenticationPrincipal AuthPrincipal p, @PathVariable String taskNo) {
        orchestrator.cancel(owned(p, taskNo));
        return ApiResponse.ok(null);
    }

    /** 资源属主断言（防越权 IDOR，手册 §7.2） */
    private TaskInstanceEntity owned(AuthPrincipal p, String taskNo) {
        TaskInstanceEntity t = orchestrator.byNo(taskNo);
        if (!t.getUserId().equals(p.userId())) throw new BizException(ErrorCode.FORBIDDEN);
        return t;
    }
}
