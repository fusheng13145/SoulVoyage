package com.soulvoyage.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.soulvoyage.domain.task.TaskInstanceEntity;

/**
 * 提交后置钩子（B-11）：领域副作用不再由传输层拼接，而是按流水线注册。
 * 在任务落库之后、虚拟线程派发之前同步执行，保证流水线启动时业务记录已提交。
 */
public interface TaskSubmissionHook {

    PipelineCode pipeline();

    void afterSubmit(long userId, JsonNode payload, TaskInstanceEntity task);
}
