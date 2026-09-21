package com.soulvoyage.asr;

import com.soulvoyage.llm.LlmGuard;

/**
 * M11 语音日记：语音转写网关抽象，与 {@link com.soulvoyage.llm.LlmClient} 同构
 * （Mock 回放器 + OpenAI 兼容真协议，切供应商只差配置项）。
 * 音频字节只在这里过一遍手——接口没有任何"存下来"的位置，转写完成即随请求作用域消失。
 */
public interface AsrClient {

    record AsrRequest(byte[] audio, String contentType, long durationMs) {}

    record AsrResult(String text, String model, long costMs) {}

    /**
     * 唯一出入口：与聊天/推理共用 {@link LlmGuard} 一道闸门——语音也是外部模型配额，
     * 不另开一条不限流的旁路。
     */
    default AsrResult transcribe(AsrRequest req) {
        return LlmGuard.admit(() -> doTranscribe(req));
    }

    AsrResult doTranscribe(AsrRequest req);
}
