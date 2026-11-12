package com.soulvoyage.orchestrator;

/**
 * 用户侧步骤时间线条目（H-01）：只暴露解释"做到哪一步 / 为什么失败"所需字段。
 * 与 admin 的 TaskStepView 分开命名，避免 springdoc schema 同名合并导致管理端契约漂移。
 */
public record TaskTimelineStep(int stepSeq, String agent, String stepCode, String status,
                               int attempt, Integer costMs, String errorMsg, String createdAt) {}
