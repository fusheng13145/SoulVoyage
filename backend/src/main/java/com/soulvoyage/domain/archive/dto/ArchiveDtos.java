package com.soulvoyage.domain.archive.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 导出票据响应契约：fileId 限时一次性领取；mode 仅档案导出携带。 */
public class ArchiveDtos {

    public record ExportTicketResp(String fileId, int expiresInSeconds,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) String mode,
                                   String note) {}
}
