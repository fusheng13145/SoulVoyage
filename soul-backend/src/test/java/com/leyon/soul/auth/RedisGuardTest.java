package com.leyon.soul.auth;

import com.leyon.soul.common.api.ErrorCode;
import com.leyon.soul.common.exception.BizException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * B-16：Redis 故障时的认证降级契约——读放行、尽力写不阻断、必须写失败即 5002。
 */
class RedisGuardTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RedisGuard guard = new RedisGuard(redis, registry);

    @Test
    void readDegradesToNullAndCountsMetric() {
        when(redis.hasKey("k")).thenThrow(new RedisConnectionFailureException("down"));

        assertNull(guard.read("jwt.blacklist", () -> redis.hasKey("k")));
        assertEquals(1.0, registry.get("sv.redis.degraded")
                .tag("op", "jwt.blacklist").tag("mode", "read").counter().count());
    }

    @Test
    void bestEffortWriteIsSwallowed() {
        assertDoesNotThrow(() -> guard.writeBestEffort("login.fail.count", () -> {
            throw new RedisConnectionFailureException("down");
        }));
        assertEquals(1.0, registry.get("sv.redis.degraded")
                .tag("op", "login.fail.count").tag("mode", "write").counter().count());
    }

    @Test
    void requiredWriteFailsClosedWith503() {
        BizException e = assertThrows(BizException.class, () ->
                guard.writeRequired("jwt.epoch", () -> {
                    throw new RedisConnectionFailureException("down");
                }));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, e.getErrorCode());
        assertEquals(1.0, registry.get("sv.redis.degraded")
                .tag("op", "jwt.epoch").tag("mode", "required").counter().count());
    }

    @Test
    void healthyPathDoesNotRecordDegradation() {
        when(redis.hasKey("k")).thenReturn(true);
        assertEquals(Boolean.TRUE, guard.read("jwt.blacklist", () -> redis.hasKey("k")));
        assertTrue(registry.find("sv.redis.degraded").counters().isEmpty());
    }
}
