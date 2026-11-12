package com.leyon.soul.orchestrator.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.leyon.soul.orchestrator.AgentCode;
import com.leyon.soul.orchestrator.PipelineCode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * UC2 人际模拟训练流水线（手册 §3.3）。
 * 多轮对话循环发生在会话服务层（WAITING_USER 长驻循环属后续引擎增强），
 * 进入调度中心的是"结束复盘 + 风险收口"：SIMULATE 评分 →（强制末步）RISK_ARCHIVE。
 * 剧情外真实危机由 NpcDirector 借同一危机词表当场置 crisis_flag，RISK_ARCHIVE 读轮次归档为 SIMULATE_BREAKOUT/HIGH。
 */
@Component
public class SimulatePipeline implements TaskDefinition {

    @Override
    public PipelineCode code() { return PipelineCode.SIMULATE_PIPELINE; }

    @Override
    public void validatePayload(JsonNode payload) {
        PipelinePayloads.requirePositiveLong(PipelinePayloads.requireObject(payload), "simulateId");
    }

    @Override
    public List<StepSpec> steps(TaskContext ctx) {
        return List.of(
                new StepSpec("review", AgentCode.SIMULATE, "simulate_review_result.json"),
                new StepSpec("risk", AgentCode.RISK_ARCHIVE, "archive_receipt.json"));
    }
}
