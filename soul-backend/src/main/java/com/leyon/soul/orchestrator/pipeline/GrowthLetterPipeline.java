package com.leyon.soul.orchestrator.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.leyon.soul.orchestrator.AgentCode;
import com.leyon.soul.orchestrator.PipelineCode;
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
    public PipelineCode code() { return PipelineCode.GROWTH_LETTER_PIPELINE; }

    @Override
    public void validatePayload(JsonNode payload) {
        // 契约：{ statWeek, material: { dataPoints, ... } }，素材量必须为正
        JsonNode p = PipelinePayloads.requireObject(payload);
        PipelinePayloads.requireNonBlankText(p, "statWeek");
        PipelinePayloads.requirePositiveInt(PipelinePayloads.requireObjectField(p, "material"), "dataPoints");
    }

    @Override
    public List<StepSpec> steps(TaskContext ctx) {
        return List.of(new StepSpec("letter", AgentCode.LETTER, "growth_letter.json"));
    }
}
