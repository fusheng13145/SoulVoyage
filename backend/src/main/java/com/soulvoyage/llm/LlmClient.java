package com.soulvoyage.llm;

/** 统一推理接口：屏蔽供应商差异，集中重试/超时/统计（手册 §2.2） */
public interface LlmClient {

    /** O3：唯一出口统一记用量（调度中心 begin/drain 归集到 task_step_log 成本字段） */
    default LlmResponse chat(LlmRequest req) {
        LlmResponse resp = doChat(req);
        LlmUsageCollector.record(resp);
        return resp;
    }

    LlmResponse doChat(LlmRequest req);

    record LlmRequest(String template, String system, String user, int maxTokens) {}

    record LlmResponse(String content, String model, int tokensIn, int tokensOut, long costMs) {}
}
