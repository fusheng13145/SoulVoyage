package com.leyon.soul.orchestrator.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.leyon.soul.domain.crisis.CrisisState;
import com.leyon.soul.orchestrator.AgentCode;
import com.leyon.soul.orchestrator.PipelineCode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * C4 认知书写微闭环流水线（下篇·C4）：三栏文本 → EMOTION → TRACE 苏格拉底追问 →（强制末步）RISK_ARCHIVE。
 * 与 DIARY_PIPELINE 同构但不产疏导方案/不进日记本——书写→反思的闭环以"追问"为终点。
 */
@Component
public class CognitivePipeline implements TaskDefinition {

    @Override
    public PipelineCode code() { return PipelineCode.COGNITIVE_PIPELINE; }

    @Override
    public void validatePayload(JsonNode payload) {
        PipelinePayloads.requireTextIfPresent(PipelinePayloads.requireObject(payload), "diaryText");
    }

    @Override
    public List<StepSpec> steps(TaskContext ctx) {
        if (ctx.crisisState() == CrisisState.CRISIS) {
            return List.of(
                    new StepSpec("emotion", AgentCode.EMOTION, "emotion_result.json"),
                    new StepSpec("risk", AgentCode.RISK_ARCHIVE, "archive_receipt.json"));
        }
        return List.of(
                new StepSpec("emotion", AgentCode.EMOTION, "emotion_result.json"),
                new StepSpec("trace", AgentCode.TRACE, "trace_result.json"),
                new StepSpec("risk", AgentCode.RISK_ARCHIVE, "archive_receipt.json"));
    }
}
