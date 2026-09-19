package com.soulvoyage.llm;

/** 统一推理接口：屏蔽供应商差异，集中重试/超时/统计（手册 §2.2） */
public interface LlmClient {

    LlmResponse chat(LlmRequest req);

    record LlmRequest(String template, String system, String user, int maxTokens) {}

    record LlmResponse(String content, String model, int tokensIn, int tokensOut, long costMs) {}
}
