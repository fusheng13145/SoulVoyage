package com.soulvoyage.orchestrator.agent;

import com.soulvoyage.orchestrator.pipeline.StepSpec;

/** Agent 运行时句柄：由调度中心注入，Agent 不回写任务状态 */
public record AgentRuntime(long taskId, long userId, String taskNo, StepSpec spec) {
}
