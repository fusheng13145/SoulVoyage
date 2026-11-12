package com.leyon.soul.domain.diary.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

/** 日记本读接口的响应契约（列表只回预览，详情回原文与关联元数据）。 */
public class DiaryDtos {

    public record DiaryListItem(Long id, String recordDate, int moodSelfRating, long taskId,
                                String source, String preview) {}

    public record DiaryListResp(List<DiaryListItem> items, int page, int size, int total) {}

    public record ReportRef(Long id, String type, String title, String riskLevel, int stale) {}

    public record EmotionRef(String sourceType, String emotion, BigDecimal valence,
                             BigDecimal intensity, JsonNode eventTags) {}

    public record DiaryDetailResp(Long id, String recordDate, int moodSelfRating, String content,
                                  String source, long voiceDurationMs, long taskId,
                                  List<ReportRef> reports, List<EmotionRef> emotions) {}

    public record DraftResp(String content) {}

    /** 编辑回执：staled 表示旧分析已因文本变更而失效。 */
    public record DiaryUpdateResp(Long id, boolean staled) {}

    /** 语音转写回执（音频不落盘，只回文本与用量）。 */
    public record VoiceTranscriptionResp(String text, String model, long costMs, long durationMs) {}
}
