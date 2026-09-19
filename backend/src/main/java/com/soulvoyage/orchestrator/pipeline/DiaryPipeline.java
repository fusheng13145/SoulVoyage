package com.soulvoyage.orchestrator.pipeline;

import com.soulvoyage.domain.crisis.CrisisState;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * UC1 情绪日记全链路（手册 §2.3 / §3.7、下篇·S1）。
 * 常规：情绪感知 → 溯源推理 → 疏导干预 →（强制末步）风险研判归档。
 * 仅 CRISIS 强干预窗口内跳过常规生成（两步收口）；COOLING/REVIEW 期恢复完整链路——
 * 危机用户更需要被看见，而不是被关掉功能（修复"一次误触、永久降级"）。
 * 注：本轮"新发"危机由 RISK_ARCHIVE 规则轨当场判定并进入危机生命周期，故本轮仍跑完整链路后再收口。
 */
@Component
public class DiaryPipeline implements TaskDefinition {

    @Override
    public String code() { return "DIARY_PIPELINE"; }

    @Override
    public List<StepSpec> steps(TaskContext ctx) {
        if (ctx.crisisState() == CrisisState.CRISIS) {
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
