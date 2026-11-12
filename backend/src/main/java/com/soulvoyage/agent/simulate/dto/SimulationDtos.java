package com.soulvoyage.agent.simulate.dto;

import java.math.BigDecimal;
import java.util.List;

/** 模拟训练会话列表读接口的响应契约（dimensionScores 为动态维度分映射）。 */
public class SimulationDtos {

    public record SimulationListItem(Long simulateId, String sceneCode, String sceneTitle,
                                     String difficulty, String status, int totalTurns,
                                     BigDecimal avgScore, Object dimensionScores,
                                     String startedAt, String finishedAt, String reportId) {}

    public record SimulationListResp(List<SimulationListItem> items, int page, int size, long total) {}

    /** 开场回执（simulateId 为字符串编号，难度缺省 NORMAL）。 */
    public record OpenSceneResp(long simulateId, String sceneCode, String title, String background,
                                String npcName, String relation, String difficulty,
                                String openingLine, int maxTurns, List<String> goalDimensions) {}

    /** 中途退出回执：返回留档后的会话状态。 */
    public record InterruptResp(String status) {}
}
