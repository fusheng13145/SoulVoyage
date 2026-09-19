package com.soulvoyage.agent.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** 自助练习卡（闭集，规则层内容不由 LLM 生成——安全兜底；手册 §4.4）。与 deploy/sql/schema.sql 种子同源。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Exercise(
        String id,
        String code,
        Long dbId,               // 对应 exercise_library 种子自增 id（打卡落库外键）
        String name,
        List<String> applyEmotions,
        List<Step> steps,
        int durationMin) {

    public record Step(String step, String desc) {}
}
