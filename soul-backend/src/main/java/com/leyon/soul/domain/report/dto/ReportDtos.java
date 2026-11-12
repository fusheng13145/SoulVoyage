package com.leyon.soul.domain.report.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** 复盘报告读接口的响应契约（列表只回元数据，正文按属主解密后随详情返回）。 */
public class ReportDtos {

    public record ReportMeta(Long id, String type, String title, String riskLevel, Boolean starred,
                             String feedback, String createdAt) {}

    public record ReportListResp(List<ReportMeta> items, int page, int size, long total) {}

    public record ReportDetailResp(Long id, String type, String title, String riskLevel, boolean starred,
                                   String feedback, String feedbackNote, String feedbackAt,
                                   String createdAt, JsonNode content) {}

    public record StarResp(Long id, boolean starred) {}

    /** rating 为空串表示撤销评价。 */
    public record FeedbackResp(Long id, String feedback) {}
}
