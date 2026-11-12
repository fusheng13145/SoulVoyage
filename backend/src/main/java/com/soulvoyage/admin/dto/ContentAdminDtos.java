package com.soulvoyage.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** 内容管理端只读接口的响应契约（G9 对账 / A3 预览）。 */
public class ContentAdminDtos {

    public record KgAuditNode(String label, String kind, int expect, int actual,
                              List<String> missing, List<String> extra, List<String> reserved) {}

    public record KgAuditRelation(String type, long expect, long actual) {}

    /** 图不可用时只回 available=false + reason；NON_NULL 保持与既有 JSON 同构。 */
    public record KgGraphAuditResp(boolean available,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) String reason,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) Long contentVersion,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) Long syncedVersion,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) Boolean consistent,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) List<KgAuditNode> nodes,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) List<KgAuditRelation> relations,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) String note) {}

    public record PreviewResp(String template, String system, String output, String model,
                              int tokensIn, int tokensOut, long costMs) {}

    /** 内容 upsert 回执：写成功即 bump 内容版本并立即生效；H-04 追加 revisionNo。 */
    public record ContentWriteResp(String code, long contentVersion, boolean effective, int revisionNo) {}

    public record ContentVersionResp(long contentVersion) {}
}
