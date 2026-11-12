package com.soulvoyage.agent.companion.dto;

import java.util.List;

/** 漫聊历史读接口的响应契约。 */
public class CompanionDtos {

    public record HistorySession(Long sessionId, String chatDate, Integer segmentNo,
                                 String status, Integer turns) {}

    public record HistoryResp(int page, int size, long total, List<HistorySession> sessions) {}

    /** 「这句别分析」回执。 */
    public record NoAnalyzeResp(Long turnId, boolean noAnalyze) {}

    /** 收段回执：taskNo 为空串表示无可消化内容（不伪造任务）。 */
    public record EndResp(Long sessionId, String taskNo) {}
}
