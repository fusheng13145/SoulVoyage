package com.soulvoyage.llm;

import java.util.function.Supplier;

/** 统一推理接口：屏蔽供应商差异，集中重试/超时/统计（手册 §2.2） */
public interface LlmClient {

    /** O3：唯一出口统一记用量（调度中心 begin/drain 归集到 task_step_log 成本字段）；准入走 §九 的令牌桶闸门 */
    default LlmResponse chat(LlmRequest req) {
        LlmResponse resp = admit(() -> doChat(req));
        LlmUsageCollector.record(resp);
        return resp;
    }

    /** 流式出口：token 边到边交出去，返回值仍是完整响应（用量归集与 chat 同源） */
    default LlmResponse stream(LlmRequest req, TokenSink sink) {
        LlmResponse resp = admit(() -> doStream(req, sink));
        LlmUsageCollector.record(resp);
        return resp;
    }

    LlmResponse doChat(LlmRequest req);

    /**
     * 默认非流式实现：整段一次给出。支持真流式的供应商覆写此方法，
     * 覆写方只需保证 sink 在返回前被调用过，调用方（回合流）不感知差异。
     */
    default LlmResponse doStream(LlmRequest req, TokenSink sink) {
        LlmResponse resp = doChat(req);
        sink.onDelta(resp.content());
        sink.onComplete(resp.content());
        return resp;
    }

    private static LlmResponse admit(Supplier<LlmResponse> call) {
        return LlmGuard.admit(call);
    }

    /** 原始增量回调：收到的是模型吐出的裸文本片段（含 JSON 结构与围栏），由调用方决定外发口径 */
    @FunctionalInterface
    interface TokenSink {
        void onDelta(String delta);

        /** 全部增量交付完毕后调用一次（默认无操作，供需要收尾的实现覆写） */
        default void onComplete(String fullContent) {}
    }

    record LlmRequest(String template, String system, String user, int maxTokens) {}

    record LlmResponse(String content, String model, int tokensIn, int tokensOut, long costMs) {}
}
