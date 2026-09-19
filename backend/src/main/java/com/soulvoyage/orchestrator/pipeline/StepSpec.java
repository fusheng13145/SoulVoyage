package com.soulvoyage.orchestrator.pipeline;

/** 流水线中的一个步骤：绑定 Agent 与其输出契约（classpath schema 文件名） */
public record StepSpec(String stepCode, String agentCode, String outputSchema) {}
