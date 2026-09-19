package com.soulvoyage.orchestrator.pipeline;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * UC1 情绪日记全链路（M4 追加 SUPPORT + 强制 RISK_ARCHIVE 步骤）。
 * 动态分支示例：危机模式下跳过常规步骤（M4 完整实现）。
 */
@Component
public class DiaryPipeline implements TaskDefinition {

    @Override
    public String code() { return "DIARY_PIPELINE"; }

    @Override
    public List<StepSpec> steps(TaskContext ctx) {
        return List.of(
                new StepSpec("emotion", "EMOTION", "emotion_result.json"),
                new StepSpec("trace", "TRACE", "trace_result.json")
        );
    }
}
