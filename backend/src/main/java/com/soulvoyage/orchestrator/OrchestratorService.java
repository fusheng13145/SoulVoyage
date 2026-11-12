package com.soulvoyage.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.domain.task.TaskInstanceEntity;
import com.soulvoyage.domain.task.TaskInstanceRepository;
import com.soulvoyage.domain.task.TaskStepLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 调度中心门面（手册 §3）：对上层保留稳定入口，内部按提交/执行/步骤三层协作。
 * Agent 之间不直接互调；所有中间结果经编排层中转、Schema 校验、加密落库（append-only）。
 */
@Service
@RequiredArgsConstructor
public class OrchestratorService {

    private final TaskSubmissionService submission;
    private final TaskExecutionService execution;
    private final TaskInstanceRepository taskRepo;
    private final TaskStepLogRepository stepLogs;
    private final TaskMessageCodec codec;

    public TaskInstanceEntity submit(long userId, String pipelineCode, JsonNode input, String clientReqId) {
        return submission.submit(userId, pipelineCode, input, clientReqId, SubmissionOrigin.EXTERNAL);
    }

    /** 内部重分析/重跑：不触发业务后置钩子（复用既有业务记录） */
    public TaskInstanceEntity submitInternal(long userId, String pipelineCode, JsonNode input, String clientReqId) {
        return submission.submit(userId, pipelineCode, input, clientReqId, SubmissionOrigin.INTERNAL);
    }

    /** A1 手动重跑：FAILED 任务从首个失败步续接 */
    public TaskInstanceEntity retry(TaskInstanceEntity t) {
        return execution.retry(t);
    }

    public TaskInstanceEntity byNo(String taskNo) {
        return taskRepo.findByTaskNo(taskNo)
                .orElseThrow(() -> new BizException(ErrorCode.TASK_NOT_FOUND));
    }

    /** H-01 用户任务列表：Controller 不再直接依赖 Repository（B-18） */
    public Page<TaskInstanceEntity> listTasks(long userId, String pipelineCode, String status,
                                              Pageable pageable) {
        return taskRepo.findUserTasks(userId, pipelineCode, status, pageable);
    }

    /** H-01 用户重试：仅 FAILED；返回任务号，避免 Controller 触碰领域实体细节 */
    public String retryFailed(TaskInstanceEntity t) {
        if (TaskStatus.of(t.getStatus()) != TaskStatus.FAILED) {
            throw new BizException(ErrorCode.BAD_PARAMS, "只有失败的任务可以重试");
        }
        execution.retry(t);
        return t.getTaskNo();
    }

    /** 结果查询：解密回传（仅资源属主可访问，属主断言在 Controller） */
    public JsonNode finalPayload(TaskInstanceEntity t) {
        return codec.finalPayload(t);
    }

    /** H-01 步骤时间线：属主断言在 Controller；升序返回，失败原因随步骤可见 */
    public List<TaskTimelineStep> steps(TaskInstanceEntity t) {
        return stepLogs.findByTaskIdOrderByStepSeqAscIdAsc(t.getId()).stream()
                .map(s -> new TaskTimelineStep(
                        s.getStepSeq(), s.getAgentCode(), s.getStepCode(), s.getStatus(),
                        s.getAttempt() == null ? 1 : s.getAttempt().intValue(), s.getCostMs(),
                        s.getErrorMsg(),
                        s.getCreatedAt() == null ? "" : s.getCreatedAt().toString()))
                .toList();
    }

    public void cancel(TaskInstanceEntity t) {
        execution.cancel(t);
    }
}
