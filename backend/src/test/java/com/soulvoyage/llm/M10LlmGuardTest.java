package com.soulvoyage.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.common.exception.GlobalExceptionHandler;
import com.soulvoyage.orchestrator.agent.LlmUnavailableException;
import com.soulvoyage.orchestrator.agent.OutputInvalidException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 手册 §九 的限流承诺真路径：令牌桶→4002、在途排队超时→4001、输出校验失败→4003（HTTP 语义就地核对）。
 * 闸门是静态装配的（LLM 客户端不是随处可注入的位置），故每个用例结束后复位为不限流。
 */
class M10LlmGuardTest {

    private final LlmClient.LlmRequest req = new LlmClient.LlmRequest("t", "s", "u", 10);
    private final AtomicInteger calls = new AtomicInteger();

    private final LlmClient client = r -> {
        calls.incrementAndGet();
        return new LlmClient.LlmResponse("{\"reply\":\"好\"}", "stub", 1, 1, 0);
    };

    @AfterEach
    void resetGate() {
        LlmGuard.disable();
    }

    @Test
    void tokenBucketRejectsBeyondQuotaWith4002() {
        LlmGuard.configure(2, 0, Duration.ZERO, null);   // 每分钟 2 枚、不排并发
        assertNotNull(client.chat(req));
        assertNotNull(client.chat(req));
        BizException e = assertThrows(BizException.class, () -> client.chat(req));
        assertEquals(4002, e.getErrorCode().code(), "币用完应以「请求过于频繁」挡住，而不是继续打上游");
        assertEquals(2, calls.get(), "被拒的调用不该真打到模型");
    }

    @Test
    void inFlightCapTimesOutWith4001() throws Exception {
        LlmGuard.configure(0, 1, Duration.ofMillis(80), null);   // 只给 1 个在途位、排队 80ms
        var entered = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        LlmClient holder = r -> {
            entered.countDown();
            try {
                released.await(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new LlmClient.LlmResponse("{}", "stub", 1, 1, 0);
        };
        var worker = new Thread(() -> holder.chat(req));
        worker.setDaemon(true);
        worker.start();
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS), "占位调用未进入");
        BizException e = assertThrows(BizException.class, () -> client.chat(req));
        assertEquals(4001, e.getErrorCode().code(), "排队超时应报「模型服务超时/稍后再试」");
        released.countDown();
        worker.join(2000);
        assertEquals(0, released.getCount(), "占位调用应已放行收尾");
    }

    @Test
    void streamingPathIsAlsoGuarded() {
        LlmGuard.configure(1, 0, Duration.ZERO, null);
        assertNotNull(client.stream(req, delta -> { }));
        BizException e = assertThrows(BizException.class, () -> client.stream(req, delta -> { }));
        assertEquals(4002, e.getErrorCode().code(), "真流式与整段式共用一道闸门");
    }

    @Test
    void validatorFailureAndHandlerCodesLineUp() {
        var validator = new OutputValidator(new ObjectMapper());
        assertThrows(OutputInvalidException.class, () -> validator.validate("companion_reply.json", "不是 JSON"));
        assertThrows(OutputInvalidException.class,
                () -> validator.validate("companion_reply.json", "{\"reply\":\"你患了抑郁症\",\"moodTag\":\"FOLLOW\"}"));

        var h = new GlobalExceptionHandler();
        assertEquals(4003, h.llmOutputInvalid(new OutputInvalidException("x")).getBody().getCode());
        assertEquals(4001, h.llmDown(new LlmUnavailableException("x")).getBody().getCode());
    }
}
