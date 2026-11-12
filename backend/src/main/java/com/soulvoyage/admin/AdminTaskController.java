package com.soulvoyage.admin;

import com.soulvoyage.admin.dto.AdminDtos.RetryResp;
import com.soulvoyage.admin.dto.AdminDtos.TaskDetailResp;
import com.soulvoyage.admin.dto.AdminDtos.TaskStepView;
import com.soulvoyage.audit.AuditService;
import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.common.api.PageReq;
import com.soulvoyage.common.api.PageResp;
import com.soulvoyage.domain.task.TaskInstanceEntity;
import com.soulvoyage.domain.task.TaskInstanceRepository;
import com.soulvoyage.domain.task.TaskStepLogRepository;
import com.soulvoyage.orchestrator.OrchestratorService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A1 任务监控（手册下篇·管理端）：全局任务列表 / 步骤时间线 / 失败任务手动重跑。
 * 只回元信息与步骤日志，不解密任何用户内容（产物查看属 A2 二次授权范畴）。
 */
@RestController
@RequestMapping("/api/v1/admin/tasks")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') and @perms.has('admin:task')")
public class AdminTaskController {

    private final TaskInstanceRepository tasks;
    private final TaskStepLogRepository steps;
    private final OrchestratorService orchestrator;
    private final AuditService audit;

    @GetMapping
    public ApiResponse<PageResp<Map<String, Object>>> list(
            @RequestParam(required = false) String pipelineCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        PageReq req = PageReq.of(page, size);
        var result = tasks.findAllTasks(blankToNull(pipelineCode), blankToNull(status), req.toRequest());
        var items = result.getContent().stream().map(t -> {
            Map<String, Object> n = new LinkedHashMap<String, Object>();
            n.put("taskNo", t.getTaskNo());
            n.put("userId", t.getUserId());
            n.put("pipelineCode", t.getPipelineCode());
            n.put("status", t.getStatus());
            n.put("createdAt", t.getCreatedAt() == null ? "" : t.getCreatedAt().toString());
            n.put("finishedAt", t.getFinishedAt() == null ? "" : t.getFinishedAt().toString());
            n.put("errorMsg", t.getErrorMsg());
            return n;
        }).toList();
        return ApiResponse.ok(PageResp.of(items, req, result.getTotalElements()));
    }

    /** 任务详情 + 步骤时间线（含成本回填字段），全状态可见 */
    @GetMapping("/{taskNo}")
    public ApiResponse<TaskDetailResp> detail(@PathVariable String taskNo) {
        TaskInstanceEntity t = orchestrator.byNo(taskNo);
        return ApiResponse.ok(new TaskDetailResp(
                t.getTaskNo(), t.getUserId(), t.getPipelineCode(), t.getStatus(),
                t.getClientReqId(), t.getErrorMsg(),
                t.getCreatedAt() == null ? "" : t.getCreatedAt().toString(),
                t.getStartedAt() == null ? "" : t.getStartedAt().toString(),
                t.getFinishedAt() == null ? "" : t.getFinishedAt().toString(),
                steps.findByTaskIdOrderByStepSeqAscIdAsc(t.getId()).stream()
                        .map(s -> new TaskStepView(
                                s.getStepSeq(), s.getAgentCode(), s.getStepCode(), s.getStatus(),
                                s.getAttempt().intValue(), s.getCostMs(),
                                s.getLlmCalls() == null ? null : s.getLlmCalls().intValue(),
                                s.getTokensIn(), s.getTokensOut(), s.getModel(), s.getErrorMsg(),
                                s.getCreatedAt() == null ? "" : s.getCreatedAt().toString()))
                        .toList()));
    }

    /** 手动重跑：仅 FAILED 任务，从首个失败步续跑复用已落库中间产物；动作全审计 */
    @PostMapping("/{taskNo}/retry")
    public ApiResponse<RetryResp> retry(@AuthenticationPrincipal AuthPrincipal p,
                                        @PathVariable String taskNo) {
        TaskInstanceEntity t = orchestrator.byNo(taskNo);
        orchestrator.retry(t);
        audit.record(p.userId(), "ADMIN_RETRY", "task:" + t.getTaskNo(), null);
        return ApiResponse.ok(new RetryResp(t.getTaskNo(), "RUNNING"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
