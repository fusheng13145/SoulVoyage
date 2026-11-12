package com.leyon.soul.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.soul.common.api.ErrorCode;
import com.leyon.soul.common.exception.BizException;
import com.leyon.soul.common.util.Ulid;
import com.leyon.soul.domain.task.TaskInstanceEntity;
import com.leyon.soul.domain.task.TaskInstanceRepository;
import com.leyon.soul.orchestrator.pipeline.PipelineRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 任务提交服务（B-03）：幂等、并发上限、落库、task_created 与派发。
 * 编排引擎的"入口"职责从执行循环中剥离，Controller 只与门面交互。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskSubmissionService {

    private final PipelineRegistry pipelines;
    private final TaskInstanceRepository taskRepo;
    private final TaskEventPublisher events;
    private final TaskDispatcher dispatcher;
    private final TaskExecutionService execution;
    private final MeterRegistry registry;
    private final ObjectMapper mapper;
    /** 延迟解析：钩子实现位于领域层，直接注入会与 DiaryService→OrchestratorService 形成构造环 */
    private final ObjectProvider<TaskSubmissionHook> hooks;

    /** 提交任务：幂等 + 并发上限（单用户 2 个活跃任务）；流水线与入参在提交时即校验 */
    public TaskInstanceEntity submit(long userId, String pipelineCode, JsonNode input, String clientReqId) {
        return submit(userId, pipelineCode, input, clientReqId, SubmissionOrigin.EXTERNAL);
    }

    public TaskInstanceEntity submit(long userId, String pipelineCode, JsonNode input,
                                     String clientReqId, SubmissionOrigin origin) {
        PipelineCode code = PipelineCode.from(pipelineCode);
        pipelines.require(code).validatePayload(input);

        if (clientReqId != null && !clientReqId.isBlank()) {
            var exist = taskRepo.findByUserIdAndClientReqId(userId, clientReqId);
            if (exist.isPresent()) return exist.get();
        }
        if (taskRepo.countActive(userId) >= 2) {
            throw new BizException(ErrorCode.TASK_CONCURRENCY_LIMIT);
        }

        TaskInstanceEntity t = new TaskInstanceEntity();
        t.setTaskNo(Ulid.next());
        t.setUserId(userId);
        t.setPipelineCode(code.name());
        t.setClientReqId(blankToNull(clientReqId));
        final TaskInstanceEntity saved = taskRepo.save(t);
        registry.counter("sv.task.submit", "pipeline", code.name()).increment();

        events.created(saved);
        JsonNode inputCopy = input == null ? mapper.createObjectNode() : input;
        // 领域副作用先落库（如日记行），再派发执行：流水线启动时业务记录已可见；
        // 内部重分析复用既有记录，不得再次生成（否则同一篇日记会出现两行）
        if (origin == SubmissionOrigin.EXTERNAL) {
            hooks.orderedStream()
                    .filter(hook -> hook.pipeline() == code)
                    .forEach(hook -> hook.afterSubmit(userId, inputCopy, saved));
        }
        dispatcher.dispatch(() -> execution.run(saved.getId(), inputCopy));
        return saved;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
