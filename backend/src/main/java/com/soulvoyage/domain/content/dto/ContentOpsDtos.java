package com.soulvoyage.domain.content.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** H-04 内容运营（草稿/预览/发布/回滚）响应契约。 */
public class ContentOpsDtos {

    public record DraftView(String contentType, String code, JsonNode payload, Long baseRevisionId,
                            String note, long updatedBy, String updatedAt,
                            boolean valid, List<String> errors, List<String> warnings) {}

    public record PreviewView(String contentType, String code, boolean valid, List<String> errors,
                              List<String> warnings, JsonNode normalizedPayload, String summary,
                              Long baseRevisionId, Long latestRevisionId, boolean conflict) {}

    public record PublishView(String contentType, String code, int revisionNo, long contentVersion,
                              boolean effective, List<String> warnings) {}

    public record RevisionView(long id, int revisionNo, String action, Long sourceRevisionId,
                               long publishedBy, String publishedAt, String summary) {}
}
