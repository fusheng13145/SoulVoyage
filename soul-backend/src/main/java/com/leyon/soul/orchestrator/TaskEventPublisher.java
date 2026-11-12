package com.leyon.soul.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.leyon.soul.domain.task.TaskInstanceEntity;
import com.leyon.soul.orchestrator.sse.TaskEventBus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * SSE 事件唯一出口（B-03）：事件名与载荷键集中在此，避免拆分后各处拼字符串导致流契约漂移。
 * 协议：task_created / step_started / step_retry / middle_result / step_failed / task_status / done / error
 */
@Component
@RequiredArgsConstructor
public class TaskEventPublisher {

    private final TaskEventBus bus;

    public void created(TaskInstanceEntity t) {
        bus.publish(t.getTaskNo(), "task_created", Map.of("taskNo", t.getTaskNo(), "status", t.getStatus()));
    }

    public void status(String taskNo, TaskStatus status) {
        bus.publish(taskNo, "task_status", Map.of("status", status.name()));
    }

    public void stepStarted(String taskNo, int stepSeq, AgentCode agent) {
        bus.publish(taskNo, "step_started", Map.of("stepSeq", stepSeq, "agent", agent.name()));
    }

    public void stepRetry(String taskNo, int stepSeq, AgentCode agent, int attempt) {
        bus.publish(taskNo, "step_retry",
                Map.of("stepSeq", stepSeq, "agent", agent.name(), "attempt", attempt));
    }

    public void middleResult(String taskNo, int stepSeq, AgentCode agent, JsonNode payload) {
        bus.publish(taskNo, "middle_result",
                Map.of("stepSeq", stepSeq, "agent", agent.name(), "payload", payload));
    }

    public void stepFailed(String taskNo, int stepSeq, AgentCode agent, String reason) {
        bus.publish(taskNo, "step_failed",
                Map.of("stepSeq", stepSeq, "agent", agent.name(), "reason", reason));
    }

    /** 取消等无结果场景：done 不带 payload（与原实现一致）。 */
    public void done(String taskNo, TaskStatus status) {
        bus.publish(taskNo, "done", Map.of("status", status.name()));
    }

    public void done(String taskNo, TaskStatus status, JsonNode payload) {
        bus.publish(taskNo, "done", Map.of("status", status.name(), "payload", payload));
    }

    public void error(String taskNo) {
        bus.publish(taskNo, "error", Map.of("message", "任务执行失败"));
    }
}
