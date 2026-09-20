package com.soulvoyage.agent.simulate;

import java.util.List;
import java.util.Map;

/**
 * 人际训练场景卡（手册 §4.3）。M8 起 DB（scene_card）为运行时真源，
 * classpath scenes/scenes.json 仅作 ContentDataSeeder 初始种子（N4 热更新）。
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
        Map<String, String> openingLines,
        List<String> tags,
        List<String> recommendedFor
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
