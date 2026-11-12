package com.soulvoyage.domain.emotion.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

/** 情绪轨迹/周画像读接口的响应契约（字段名与既有 JSON 完全一致）。 */
public class EmotionDtos {

    public record TrajectoryPoint(String date, String sourceType, String emotion,
                                  BigDecimal valence, BigDecimal intensity, JsonNode eventTags) {}

    public record TrajectoryResp(String from, String to, List<TrajectoryPoint> points) {}

    /** avgValence 历史语义为 "" 或数值，保持 Object 不收敛，避免空值口径漂移。 */
    public record ProfileWeek(String statWeek, Object avgValence, String riskLevel,
                              JsonNode stressorTop, JsonNode distortionTop) {}

    public record ProfileResp(boolean crisisMode, String crisisState, List<ProfileWeek> weeks) {}

    /** 轻量打卡回执：轨迹点主键 + 归属日期。 */
    public record SelfRatingResp(Long id, String date) {}
}
