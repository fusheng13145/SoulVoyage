package com.soulvoyage.orchestrator.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/** Agent 间结构化消息（手册 §3.4）。大对象不进消息体，只放 contextRef。 */
public record AgentMessage(
        String messageNo,
        long taskId,
        int stepSeq,
        String from,
        String to,
        MsgType type,
        JsonNode payload,
        String contextRef,
        Trace trace
) {
    public enum MsgType { REQUEST, MIDDLE_RESULT, FINAL, ERROR }

    public record Trace(int llmCalls, int tokensIn, int tokensOut, long costMs, String model) {}

    public static AgentMessage of(long taskId, int stepSeq, String from, String to,
                                  MsgType type, JsonNode payload) {
        return new AgentMessage(null, taskId, stepSeq, from, to, type, payload, null, null);
    }
}
