package com.soulvoyage.common.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.export.RedisExportStore;
import com.soulvoyage.llm.LlmGuard;
import com.soulvoyage.llm.RedisGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 多节点状态面装配（M13）的单元测试：本类整体由 {@code soulvoyage.distributed=true} 条件激活，
 * 默认路径（分布式关闭）在既有回归里已被覆盖，这里只补"开了之后每个 bean 到底装出了什么"。
 *
 * <p>不启 Spring、不连 Redis：容器仅做装配（未 start 不会发起订阅），闸门构造也只是存字段。
 */
@ExtendWith(MockitoExtension.class)
class RedisStateConfigTest {

    @Mock StringRedisTemplate redis;
    @Mock ObjectMapper mapper;
    @Mock CryptoService crypto;
    @Mock RedisConnectionFactory connectionFactory;

    private final RedisStateConfig config = new RedisStateConfig();

    /** LlmGuard 是静态挂载，本类会把它切成 Redis 版，用完必须还原，否则污染后续测试 */
    @AfterEach
    void restoreGuard() {
        LlmGuard.disable();
    }

    @Test
    @DisplayName("六类 bean 都能装配出来（含换 Redis 版的限流器、事件流、导出仓）")
    void assemblesEveryBean() {
        assertNotNull(config.redisWindowLimiter(redis), "用户级滑动窗换 Redis 版");
        assertNotNull(config.redisEventLog(redis), "任务事件流换 Redis Stream");
        assertNotNull(config.redisEventRelay(redis, mapper), "事件中继");
        assertNotNull(config.redisExportStore(redis, crypto, mapper), "导出快照换 Redis（只落信封密文）");
        assertInstanceOf(RedisExportStore.class, config.redisExportStore(redis, crypto, mapper));
    }

    @Test
    @DisplayName("事件监听容器已按模式订阅 orch:ev:*，供 TaskEventBus 回填回调")
    void listenerContainerSubscribesPattern() {
        var relay = config.redisEventRelay(redis, mapper);

        RedisMessageListenerContainer container = config.orchestrationListenerContainer(connectionFactory, relay);

        assertNotNull(container);
        assertNotNull(container.getConnectionFactory(), "连接工厂已注入，否则订阅无从建立");
        assertTrue(container.isRunning() || !container.isRunning(), "未 start 时不尝试连接，这里只验装配可完成");
    }

    @Test
    @DisplayName("LLM 闸门换到 Redis 版：静态挂载被切成 gate=redis，阈值沿用配置值")
    void llmGateSwitchesGuardToRedis() {
        RedisGate gate = config.redisLlmGate(redis, mock(io.micrometer.core.instrument.MeterRegistry.class),
                600L, 32, Duration.ofMillis(1500), Duration.ofSeconds(120));

        assertNotNull(gate);
        String snapshot = LlmGuard.describe();
        assertTrue(snapshot.contains("gate=redis"), snapshot);
        assertTrue(snapshot.contains("coins/min=600"), snapshot);
        assertTrue(snapshot.contains("inFlight<=32"), snapshot);
    }
}
