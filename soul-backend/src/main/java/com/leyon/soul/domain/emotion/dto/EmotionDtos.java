package com.leyon.soul.domain.emotion.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    /** 轻量打卡请求：date 为空时取业务今日。 */
    public record SelfRatingReq(String emotion, BigDecimal valence, BigDecimal intensity,
                                String note, LocalDate date) {}

    /** 轻量打卡回执：轨迹点主键 + 归属日期。 */
    public record SelfRatingResp(Long id, String date) {}

    /** H-02 周报摘要：headline 为中性描述，lines 为确定性规则生成的短句。 */
    public record WeeklyDigest(String statWeek, String headline, List<String> lines) {}

    /** H-02 波动提示：level=STEADY/WAVY/VOLATILE/INSUFFICIENT；样本不足时数值字段为 null。 */
    public record Volatility(String level, String label, String hint, int sampleDays,
                             BigDecimal stdDev, BigDecimal minValence, BigDecimal maxValence,
                             BigDecimal maxDailyDelta) {}

    /** H-02 压力源解释：what/tip 来自内容字典，缺失时使用中性兜底文案。 */
    public record StressorInsight(String name, int count, String what, String tip) {}

    /** H-02 误区解释：definition/signature/question 来自知识库，缺失时为空串。 */
    public record DistortionInsight(String kgNodeId, String name, int count, String definition,
                                    String typicalSignature, String question) {}

    /** H-02 可执行下一步：route 指向既有页面，不携带任何写操作。 */
    public record NextAction(String code, String title, String detail, String route) {}

    /** H-02 洞察总响应（只读）。 */
    public record InsightResp(String generatedFor, int weeks, boolean crisisMode,
                              List<WeeklyDigest> weekly, Volatility volatility,
                              List<StressorInsight> stressors, List<DistortionInsight> distortions,
                              List<NextAction> actions) {}
}
