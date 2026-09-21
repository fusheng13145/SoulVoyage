package com.soulvoyage.common.state;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Redis 滑动窗（多节点共享）：ZSET 成员=本次请求的随机 id、score=到达毫秒，
 * 一条 Lua 里"修剪过期 → 计数 → 记账 → 设 TTL"原子完成——两个节点同时打也不会双双放行。
 * 时钟取调用方 now：Redis 脚本里不读系统时间（可确定性复制），毫秒偏差远小于 60s 窗口，可忽略。
 */
public class RedisWindowLimiter implements SlidingWindowLimiter {

    private static final String PREFIX = "rl:";

    private static final RedisScript<Long> WINDOW = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member = ARGV[4]
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
            if redis.call('ZCARD', key) < limit then
              redis.call('ZADD', key, now, member)
              redis.call('PEXPIRE', key, window)
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisWindowLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        Long ok = redis.execute(WINDOW, List.of(PREFIX + key),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(window.toMillis()),
                String.valueOf(limit),
                UUID.randomUUID().toString());
        return ok != null && ok == 1L;
    }
}
