package com.soulvoyage.agent.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 自助练习卡（闭集，规则层内容不由 LLM 生成——安全兜底；手册 §4.4）。
 * M8 统一编码：id 即 exercise_library.code（ex_*），不再有 EX_* 大写码与自增 dbId 双轨。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Exercise(
        String id,
        String name,
        List<String> applyEmotions,
        List<Step> steps,
        int durationMin) {

    public record Step(String step, String desc) {}
}
