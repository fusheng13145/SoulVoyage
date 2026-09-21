package com.soulvoyage.llm;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Redis 分布式闸门（M13，§3.6 设计口径"Redis+Lua"到此兑现）：
 * ① 令牌桶落 hash（coins/ts），一条 Lua 里"按流逝时间补币→封顶→扣一枚"原子完成——
 *    全局 QPS 上限从此是"整个集群 600/min"，不再"每节点各 600"；
 * ② 在途并发落 ZSET（成员=本次占用 uuid、score=租约到期毫秒）：崩溃的节点不用 release，
 *    租约到点自动被下一次进入者修剪掉——多节点最怕的"槽位被死节点永久占住"由租约自愈。
 * 排队语义与进程内版一致：queue-wait 内轮询重试，仍拿不到就 4001。
 */
public class RedisGate implements LlmGate {

    private static final String BUCKET_KEY = "llm:bucket:global";
    private static final String INFLIGHT_KEY = "llm:inflight";
    private static final long POLL_INTERVAL_MS = 25;

    private static final RedisScript<Long> COIN = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local rate = tonumber(ARGV[1])
            local now = tonumber(ARGV[2])
            local coins = tonumber(redis.call('HGET', key, 'coins'))
            local ts = tonumber(redis.call('HGET', key, 'ts'))
            if coins == nil then coins = rate end
            if ts == nil or ts > now then ts = now end
            coins = math.min(rate, coins + (now - ts) / 60000 * rate)
            local ok = 0
            if coins >= 1 then coins = coins - 1 ok = 1 end
            redis.call('HSET', key, 'coins', coins, 'ts', now)
            redis.call('EXPIRE', key, 3600)
            return ok
            """, Long.class);

    private static final RedisScript<Long> ENTER = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local max = tonumber(ARGV[2])
            local now = tonumber(ARGV[1])
            local lease = tonumber(ARGV[3])
            redis.call('ZREMRANGEBYSCORE', key, 0, now)
            if redis.call('ZCARD', key) < max then
              redis.call('ZADD', key, now + lease, ARGV[4])
              redis.call('PEXPIRE', key, lease * 2)
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final long coinsPerMinute;
    private final int maxInFlight;
    private final Duration lease;

    private final ThreadLocal<String> slot = new ThreadLocal<>();

    public RedisGate(StringRedisTemplate redis, long coinsPerMinute, int maxInFlight, Duration lease) {
        this.redis = redis;
        this.coinsPerMinute = coinsPerMinute;
        this.maxInFlight = maxInFlight;
        this.lease = lease;
    }

    @Override
    public boolean tryCoin() {
        if (coinsPerMinute <= 0) return true;
        Long ok = redis.execute(COIN, List.of(BUCKET_KEY),
                String.valueOf(coinsPerMinute), String.valueOf(System.currentTimeMillis()));
        return ok != null && ok == 1L;
    }

    @Override
    public boolean tryEnter(Duration wait) throws InterruptedException {
        if (maxInFlight <= 0) return true;
        long deadline = System.nanoTime() + wait.toNanos();
        do {
            String member = UUID.randomUUID().toString();
            Long ok = redis.execute(ENTER, List.of(INFLIGHT_KEY),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(maxInFlight),
                    String.valueOf(lease.toMillis()),
                    member);
            if (ok != null && ok == 1L) {
                slot.set(member);
                return true;
            }
            long leftMs = (deadline - System.nanoTime()) / 1_000_000;
            if (leftMs <= 0) return false;
            Thread.sleep(Math.min(POLL_INTERVAL_MS, leftMs));
        } while (System.nanoTime() < deadline);
        return false;
    }

    @Override
    public void release() {
        if (maxInFlight <= 0) return;
        String member = slot.get();
        slot.remove();
        if (member != null) redis.opsForZSet().remove(INFLIGHT_KEY, member);
    }

    @Override
    public String name() {
        return "redis";
    }
}
