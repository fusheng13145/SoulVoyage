package com.soulvoyage.orchestrator.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

public class TaskDtos {

    public record SubmitReq(
            @NotBlank String pipelineCode,
            JsonNode payload,
            String clientReqId
    ) {}

    public record TaskView(String taskNo, String pipelineCode, String status,
                           JsonNode payload, String errorMsg, String createdAt) {}

    /** 提交回执：taskNo + 初始状态（202 Accepted 语义）。 */
    public record SubmitResp(String taskNo, String status) {}

    /** 只回任务编号的写接口回执（重分析/认知书写/模拟收尾等）。 */
    public record TaskNoResp(String taskNo) {}
}
