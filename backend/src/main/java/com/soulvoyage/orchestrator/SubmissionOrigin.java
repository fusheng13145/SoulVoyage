package com.soulvoyage.orchestrator;

/**
 * 提交来源（B-11）：外部/领域新业务提交会触发业务后置钩子（如日记落库）；
 * 内部重分析/重跑复用已有业务记录，不得再次生成。
 */
public enum SubmissionOrigin {
    EXTERNAL,
    INTERNAL
}
