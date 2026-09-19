package com.soulvoyage.orchestrator.pipeline;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * UC2 人际模拟训练流水线（手册 §3.3）。
 * 多轮对话循环发生在会话服务层（WAITING_USER 长驻循环属 M4+ 引擎增强），
 * 进入调度中心的是"结束复盘"段：SIMULATE 评分 →（M4 强制追加 RISK_ARCHIVE）。
 */
@Component
public class SimulatePipeline implements TaskDefinition {

    @Override
    public String code() { return "SIMULATE_PIPELINE"; }

    @Override
    public List<StepSpec> steps(TaskContext ctx) {
        return List.of(
                new StepSpec("review", "SIMULATE", "simulate_review_result.json")
        );
    }
}
