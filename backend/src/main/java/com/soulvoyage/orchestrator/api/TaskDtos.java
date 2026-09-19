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
}
