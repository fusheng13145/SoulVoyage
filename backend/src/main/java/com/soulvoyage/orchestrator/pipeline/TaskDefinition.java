package com.soulvoyage.orchestrator.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.soulvoyage.orchestrator.PipelineCode;

import java.util.List;

/** 声明式流水线定义：新增业务 = 注册一个 TaskDefinition，不改引擎（手册 §3.3） */
public interface TaskDefinition {
    PipelineCode code();

    List<StepSpec> steps(TaskContext ctx);

    /** 提交时校验入参结构（B-04）：默认不校验，由各流水线按需覆盖。 */
    default void validatePayload(JsonNode payload) {}
}
