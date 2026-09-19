package com.soulvoyage.orchestrator.pipeline;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * UC1 情绪日记全链路（手册 §2.3 / §3.7）。
 * 常规：情绪感知 → 溯源推理 → 疏导干预 →（强制末步）风险研判归档。
 * 危机模式（用户已被上一轮标记）：跳过常规生成，直接走风险收口——其他 Agent 输出被转介引导替代。
 * 注：本轮"新发"危机由 RISK_ARCHIVE 规则轨当场判定并置标记，故本轮仍跑完整链路后再收口。
 */
@Component
public class DiaryPipeline implements TaskDefinition {

    @Override
    public String code() { return "DIARY_PIPELINE"; }

    @Override
    public List<StepSpec> steps(TaskContext ctx) {
        if (ctx.crisisMode()) {
            return List.of(
                    new StepSpec("emotion", "EMOTION", "emotion_result.json"),
                    new StepSpec("risk", "RISK_ARCHIVE", "archive_receipt.json"));
        }
        return List.of(
                new StepSpec("emotion", "EMOTION", "emotion_result.json"),
                new StepSpec("trace", "TRACE", "trace_result.json"),
                new StepSpec("support", "SUPPORT", "support_plan.json"),
                new StepSpec("risk", "RISK_ARCHIVE", "archive_receipt.json"));
    }
}
