package com.soulvoyage.agent.simulate;

import java.util.List;
import java.util.Map;

/**
 * 人际训练场景卡（手册 §4.3）。M3 与 KG 同策略：classpath resources/scenes/scenes.json 为唯一事实源，
 * deploy/sql/seed_scenes.sql 同源导 MySQL（管理端热更新在后续迭代切表读取）。
 */
public record SceneCard(
        String code,
        String title,
        String description,
        String npcName,
        String relation,
        List<String> difficulties,
        Persona persona,
        List<String> goalDimensions,
        int maxTurns,
        Map<String, String> openingLines
) {
    public record Persona(String motivation, String bottomLine, String triggers, String style) {}

    public boolean supports(String difficulty) {
        return difficulties.contains(difficulty);
    }

    public String openingLine(String difficulty) {
        String line = openingLines.get(difficulty);
        return line != null ? line : openingLines.values().iterator().next();
    }
}
