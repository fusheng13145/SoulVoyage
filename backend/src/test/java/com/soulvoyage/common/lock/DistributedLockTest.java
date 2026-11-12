package com.soulvoyage.common.lock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * B-05/B-06 锁契约：抢到才执行、占用即跳过、Redis 故障降级为进程内语义并告警、可整体关闭。
 */
class DistributedLockTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
    }

    @Test
    void acquireThenReleaseWithToken() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);
        DistributedLock lock = new DistributedLock(redis, registry, true);

        assertTrue(lock.tryLock("lock:test", Duration.ofSeconds(5), Duration.ZERO));
        lock.unlock("lock:test");

        verify(ops).setIfAbsent(eq("lock:test"), anyString(), eq(Duration.ofSeconds(5)));
        verify(redis).execute(any(RedisScript.class), eq(List.of("lock:test")), any());
    }

    @Test
    void occupiedLockSkipsImmediatelyWhenWaitIsZero() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        DistributedLock lock = new DistributedLock(redis, registry, true);

        assertFalse(lock.tryLock("lock:test", Duration.ofSeconds(5), Duration.ZERO));
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void redisFailureDegradesToInProcessSemanticsAndCountsMetric() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("down"));
        DistributedLock lock = new DistributedLock(redis, registry, true);

        assertTrue(lock.tryLock("lock:audit:chain", Duration.ofSeconds(5), Duration.ZERO));
        assertDoesNotThrow(() -> lock.unlock("lock:audit:chain"));   // 降级获取不再碰 Redis
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any());
        assertEquals(1.0, registry.get("sv.lock.degraded")
                .tag("lock", "lock:audit:chain").tag("phase", "acquire").counter().count());
    }

    @Test
    void disabledSwitchBypassesRedisEntirely() {
        DistributedLock lock = new DistributedLock(redis, registry, false);

        assertTrue(lock.tryLock("lock:sched:X.sweep", Duration.ofMinutes(30), Duration.ZERO));
        lock.unlock("lock:sched:X.sweep");
        verifyNoInteractions(redis);
    }
}
