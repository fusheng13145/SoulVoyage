package com.soulvoyage.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/** 管理端只读接口的响应契约（任务详情 / 审计校验 / 群体看板 / 观测看板）。 */
public class AdminDtos {

    public record TaskStepView(int stepSeq, String agentCode, String stepCode, String status,
                               int attempt, Integer costMs, Integer llmCalls, Integer tokensIn,
                               Integer tokensOut, String model, String errorMsg, String createdAt) {}

    public record TaskDetailResp(String taskNo, Long userId, String pipelineCode, String status,
                                 String clientReqId, String errorMsg, String createdAt,
                                 String startedAt, String finishedAt, List<TaskStepView> steps) {}

    public record VerifyAuditResp(boolean intact, long checked, Long brokenAtId) {}

    public record BoardMemberView(Long userId, String username, boolean consented) {}

    public record BoardDetailResp(Long id, String name, int status, int memberCount,
                                  int consentedCount, List<BoardMemberView> members) {}

    public record BoardEmotion(String code, long count) {}

    public record BoardCheckIns(long contributors, long personDays, Double avgRating,
                                Double avgEnergy, List<BoardEmotion> emotions) {}

    public record BoardDayValence(String date, double avgValence) {}

    /** 抑制态只出结论、不出任何指标字段；NON_NULL 保证抑制态不额外回 null 键。 */
    public record BoardStatsResp(Long groupId, String groupName, int windowDays, int memberCount,
                                 int consentedCount, int threshold, boolean suppressed,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) String reason,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) BoardCheckIns checkIns,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) List<BoardDayValence> valenceByDay,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) Integer hiddenDays,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Long> riskEvents) {}

    public record TaskStats(Map<String, Long> byStatus, long finished, Double successRate) {}

    public record TaskOverview(TaskStats today, TaskStats window) {}

    public record AgentStat(String agent, int steps, Long avgMs, Long p95Ms, Long maxMs,
                            long llmCalls, long tokensIn, long tokensOut) {}

    public record TokensByDay(String date, long tokensIn, long tokensOut) {}

    public record LlmRejects(long rateLimited, long queueTimeout) {}

    public record RuntimeView(String llmGate, String kg, long taskSubmitted, long llmCalls,
                              long tokensIn, long tokensOut, LlmRejects llmRejects) {}

    public record MetricsOverviewResp(int windowDays, TaskOverview tasks, Map<String, Long> riskEvents,
                                      List<AgentStat> agents, List<TokensByDay> tokensByDay,
                                      Map<String, Long> reportFeedback, RuntimeView runtime) {}

    public record RetryResp(String taskNo, String status) {}

    /** 冻结/解冻回执（status: 1=正常，2=冻结）。 */
    public record UserStatusResp(Long userId, int status) {}

    public record RiskRevealResp(Long riskEventId, Long userId, Object evidence) {}

    public record RiskReviewResp(Long riskEventId, boolean reviewed, boolean crisisClosed) {}

    public record RemoveMemberResp(Long groupId, Long userId, boolean removed) {}
}
