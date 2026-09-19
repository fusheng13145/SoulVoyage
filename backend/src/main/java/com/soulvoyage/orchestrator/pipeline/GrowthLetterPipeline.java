package com.soulvoyage.orchestrator.pipeline;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * G6 成长来信流水线（下篇·G6）：一次 LLM 调用的单步任务。
 * 素材由 GrowthLetterService 确定性组装（闭集输入），风险不在此研判——
 * 素材全部来自本周已完成研判的存量记录，来信不构成新的用户输入路径。
 */
@Component
public class GrowthLetterPipeline implements TaskDefinition {

    @Override
    public String code() { return "GROWTH_LETTER_PIPELINE"; }

    @Override
    public List<StepSpec> steps(TaskContext ctx) {
        return List.of(new StepSpec("letter", "LETTER", "growth_letter.json"));
    }
}
