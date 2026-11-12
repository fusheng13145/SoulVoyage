package com.leyon.soul.agent.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.leyon.soul.orchestrator.AgentCode;
import com.leyon.soul.orchestrator.PipelineCode;
import com.leyon.soul.orchestrator.pipeline.PipelinePayloads;
import com.leyon.soul.orchestrator.pipeline.StepSpec;
import com.leyon.soul.orchestrator.pipeline.TaskContext;
import com.leyon.soul.orchestrator.pipeline.TaskDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * COMPANION_PIPELINE（下篇·C0，异步消化非每轮）：
 * 摘要提取(COMPANION) → 情绪感知 EMOTION(source_type=CHAT) → [显著负面时 TRACE 轻量洞察] → 风险归档(强制末步)。
 * wantTrace 由封段服务层规则判好塞进提交入参（流水线步骤只在启动时求值一次，见手册 §3.3 注记）。
 */
@Component
public class CompanionPipeline implements TaskDefinition {

    @Override
    public PipelineCode code() { return PipelineCode.COMPANION_PIPELINE; }

    @Override
    public void validatePayload(JsonNode payload) {
        PipelinePayloads.requirePositiveLong(PipelinePayloads.requireObject(payload), "sessionId");
    }

    @Override
    public List<StepSpec> steps(TaskContext ctx) {
        JsonNode input = ctx.input();
        boolean wantTrace = input != null && input.path("wantTrace").asBoolean(false);
        List<StepSpec> steps = new ArrayList<>();
        steps.add(new StepSpec("digest", AgentCode.COMPANION, "companion_digest.json"));
        steps.add(new StepSpec("emotion", AgentCode.EMOTION, "emotion_result.json"));
        if (wantTrace) {
            steps.add(new StepSpec("insight", AgentCode.TRACE, "trace_result.json"));
        }
        steps.add(new StepSpec("risk", AgentCode.RISK_ARCHIVE, "archive_receipt.json"));
        return steps;
    }
}
