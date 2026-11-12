package com.leyon.soul.orchestrator.api;

import com.leyon.soul.auth.AuthPrincipal;
import com.leyon.soul.audit.AuditService;
import com.leyon.soul.common.api.ApiResponse;
import com.leyon.soul.common.api.ErrorCode;
import com.leyon.soul.common.api.PageReq;
import com.leyon.soul.common.api.PageResp;
import com.leyon.soul.common.exception.BizException;
import com.leyon.soul.domain.task.TaskInstanceEntity;
import com.leyon.soul.orchestrator.OrchestratorService;
import com.leyon.soul.orchestrator.TaskTimelineStep;
import com.leyon.soul.orchestrator.api.TaskDtos.SubmitReq;
import com.leyon.soul.orchestrator.api.TaskDtos.SubmitResp;
import com.leyon.soul.orchestrator.api.TaskDtos.TaskView;
import com.leyon.soul.orchestrator.sse.TaskEventBus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final OrchestratorService orchestrator;
    private final TaskEventBus bus;
    private final AuditService audit;

    @PostMapping
    public ResponseEntity<ApiResponse<SubmitResp>> submit(
            @AuthenticationPrincipal AuthPrincipal p, @Valid @RequestBody SubmitReq req) {
        // 领域副作用（如日记落库）由 TaskSubmissionHook 在提交服务内完成（B-11）
        TaskInstanceEntity t = orchestrator.submit(p.userId(), req.pipelineCode(),
                req.payload(), req.clientReqId());
        return ResponseEntity.accepted()
                .body(ApiResponse.ok(new SubmitResp(t.getTaskNo(), t.getStatus())));
    }

    /** C5 任务历史列表：pipelineCode/status 可选过滤（只回元信息，产物走 /tasks/{taskNo}）；O2 收敛 PageResp */
    @GetMapping
    public ApiResponse<PageResp<Map<String, Object>>> list(
            @AuthenticationPrincipal AuthPrincipal p,
            @RequestParam(required = false) String pipelineCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        PageReq req = PageReq.of(page, size);
        var result = orchestrator.listTasks(p.userId(),
                blankToNull(pipelineCode), blankToNull(status), req.toRequest());
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
        return ApiResponse.ok(PageResp.of(items, req, result.getTotalElements()));
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

    /** H-01 步骤时间线：属主可读；断线重放仍走 /stream 的 Last-Event-ID */
    @GetMapping("/{taskNo}/steps")
    public ApiResponse<List<TaskTimelineStep>> steps(@AuthenticationPrincipal AuthPrincipal p,
                                                 @PathVariable String taskNo) {
        return ApiResponse.ok(orchestrator.steps(owned(p, taskNo)));
    }

    /** H-01 失败重试：仅 FAILED 可重试；复用断点续跑（从首个失败步续接） */
    @PostMapping("/{taskNo}/retry")
    public ApiResponse<SubmitResp> retryTask(@AuthenticationPrincipal AuthPrincipal p,
                                             @PathVariable String taskNo) {
        String retried = orchestrator.retryFailed(owned(p, taskNo));
        audit.record(p.userId(), "TASK_RETRY", "task:" + retried, null);
        // 执行体异步把状态推进到 RUNNING（与 AdminTask 重试回执一致）
        return ApiResponse.ok(new SubmitResp(retried, "RUNNING"));
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
