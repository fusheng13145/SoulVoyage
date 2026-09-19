package com.soulvoyage.agent.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.soulvoyage.orchestrator.pipeline.StepSpec;
import com.soulvoyage.orchestrator.pipeline.TaskContext;
import com.soulvoyage.orchestrator.pipeline.TaskDefinition;
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
    public String code() { return "COMPANION_PIPELINE"; }

    @Override
    public List<StepSpec> steps(TaskContext ctx) {
        JsonNode input = ctx.input();
        boolean wantTrace = input != null && input.path("wantTrace").asBoolean(false);
        List<StepSpec> steps = new ArrayList<>();
        steps.add(new StepSpec("digest", "COMPANION", "companion_digest.json"));
        steps.add(new StepSpec("emotion", "EMOTION", "emotion_result.json"));
        if (wantTrace) {
            steps.add(new StepSpec("insight", "TRACE", "trace_result.json"));
        }
        steps.add(new StepSpec("risk", "RISK_ARCHIVE", "archive_receipt.json"));
        return steps;
    }
}
