package com.soulvoyage.asr;

import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 开发期 Mock 转写器：与 MockLlmClient 同一立场——让链路在无密钥时也可跑、可回放、可断言。
 * 输出刻意是一句"像人话"的样例文本（不含高危句式），因此语音路径的危机拦截测试必须
 * 显式替换 AsrClient，而不是指望这里碰出危机词。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "soulvoyage.asr.provider", havingValue = "mock", matchIfMissing = true)
public class MockAsrClient implements AsrClient {

    public static final String SAMPLE_TRANSCRIPT =
            "今天下午去图书馆写了一会儿作业，晚上和室友约了饭，整体还算平静，就是躺下以后有点睡不着。";
    public static final String MODEL = "mock-asr-v1";

    @Override
    public AsrResult doTranscribe(AsrRequest req) {
        long t0 = System.currentTimeMillis();
        sleep(120);   // 模拟上行与解码耗时，便于观察前端"转写中"的等待体验
        if (req.audio() == null || req.audio().length == 0) {
            throw new BizException(ErrorCode.ASR_FAILED, "音频为空，没能听清");
        }
        return new AsrResult(SAMPLE_TRANSCRIPT, MODEL, System.currentTimeMillis() - t0);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
