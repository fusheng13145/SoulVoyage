package com.leyon.soul.common.lock;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * B-05/B-06：跨节点互斥锁（Redis SET NX PX + 令牌校验释放）。
 *
 * <p>C 目录零改动：审计链/调度器的锁都在包外切面里加，被包裹代码的算法与语义不变。
 * 降级策略：Redis 不可达时退回进程内语义（调用方自身仍有 JVM 锁/单节点互斥），
 * 记 {@code sv.lock.degraded} 指标告警；开关 {@code soul.lock.enabled=false} 可直接关闭分布式锁。
 */
@Slf4j
@Component
public class DistributedLock {

    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final MeterRegistry registry;
    private final boolean enabled;

    /** 每线程持有：token 用于安全释放；fallback 标记本次为降级获取（释放时不再碰 Redis） */
    private final ThreadLocal<String> token = new ThreadLocal<>();
    private final ThreadLocal<Set<String>> fallbackKeys = ThreadLocal.withInitial(HashSet::new);

    public DistributedLock(StringRedisTemplate redis, MeterRegistry registry,
                           @Value("${soul.lock.enabled:true}") boolean enabled) {
        this.redis = redis;
        this.registry = registry;
        this.enabled = enabled;
    }

    /**
     * 尝试获取锁。
     *
     * @param wait 最长等待；ZERO 表示只试一次（调度任务用）
     * @return true=已持有（含降级获取）；false=被其他节点占用
     */
    public boolean tryLock(String key, Duration lease, Duration wait) {
        if (!enabled) {
            markFallback(key);
            return true;
        }
        String myToken = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + wait.toNanos();
        for (;;) {
            try {
                Boolean ok = redis.opsForValue().setIfAbsent(key, myToken, lease);
                if (Boolean.TRUE.equals(ok)) {
                    token.set(myToken);
                    return true;
                }
            } catch (DataAccessException e) {
                degraded(key, "acquire", e);
                markFallback(key);
                return true;
            }
            if (wait.isZero() || System.nanoTime() >= deadline) return false;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    public void unlock(String key) {
        if (fallbackKeys.get().remove(key)) return;
        String myToken = token.get();
        if (myToken == null) return;
        try {
            redis.execute(RELEASE, List.of(key), myToken);
        } catch (DataAccessException e) {
            degraded(key, "release", e);
        } finally {
            token.remove();
        }
    }

    private void markFallback(String key) {
        fallbackKeys.get().add(key);
    }

    private void degraded(String key, String phase, Exception e) {
        registry.counter("sv.lock.degraded", "lock", key, "phase", phase).increment();
        log.warn("distributed lock degraded {} ({}): {}", key, phase, e.getMessage());
    }
}
