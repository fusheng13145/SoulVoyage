package com.leyon.soul.common.lock;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-05/B-06 多节点语义（真实 Redis）：两个独立锁实例共享同一 key，
 * 后到者必须跳过；持有者释放后，后到者才可获取。等价于两实例并发调度时"只执行一次"。
 */
@SpringBootTest
@ActiveProfiles("test")
class DistributedLockRedisTest {

    @Autowired StringRedisTemplate redis;
    @Autowired MeterRegistry registry;

    @Test
    void secondNodeSkipsUntilFirstReleases() {
        String key = "lock:test:" + System.nanoTime();
        DistributedLock nodeA = new DistributedLock(redis, registry, true);
        DistributedLock nodeB = new DistributedLock(redis, registry, true);

        assertTrue(nodeA.tryLock(key, Duration.ofSeconds(10), Duration.ZERO), "首节点应拿到锁");
        assertFalse(nodeB.tryLock(key, Duration.ofSeconds(10), Duration.ZERO), "第二节点必须跳过");

        nodeA.unlock(key);
        assertTrue(nodeB.tryLock(key, Duration.ofSeconds(10), Duration.ZERO), "释放后应可再次获取");
        nodeB.unlock(key);
    }
}
