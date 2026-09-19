package com.soulvoyage.orchestrator.pipeline;

import java.util.List;

/** 声明式流水线定义：新增业务 = 注册一个 TaskDefinition，不改引擎（手册 §3.3） */
public interface TaskDefinition {
    String code();
    List<StepSpec> steps(TaskContext ctx);
}
