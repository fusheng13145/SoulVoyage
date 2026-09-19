package com.soulvoyage.orchestrator.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.soulvoyage.domain.crisis.CrisisState;

/** 调度中心派发给流水线的执行上下文；crisisState 决定动态分支（下篇·S1） */
public record TaskContext(long taskId, long userId, CrisisState crisisState, JsonNode input) {}
