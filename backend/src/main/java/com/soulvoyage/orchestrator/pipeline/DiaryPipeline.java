package com.soulvoyage.orchestrator.pipeline;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * UC1 情绪日记全链路（M1 阶段仅含情绪感知步骤；M2 插入 TRACE、M4 插入 SUPPORT + 强制 RISK_ARCHIVE）。
 * 动态分支示例：危机模式下跳过常规步骤（M4 完整实现）。
 */
@Component
public class DiaryPipeline implements TaskDefinition {

    @Override
    public String code() { return "DIARY_PIPELINE"; }

    @Override
    public List<StepSpec> steps(TaskContext ctx) {
        return List.of(
                new StepSpec("emotion", "EMOTION", "emotion_result.json")
        );
    }
}
