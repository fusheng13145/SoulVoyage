package com.soulvoyage.orchestrator.pipeline;

import com.fasterxml.jackson.databind.JsonNode;

public record TaskContext(long taskId, long userId, boolean crisisMode, JsonNode input) {}
