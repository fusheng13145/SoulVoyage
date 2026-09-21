package com.soulvoyage.common.state;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 单节点内存版滑动窗（M13 前的既有口径，保留为默认实现：本机开发与测试不依赖 Redis）；
 *  distributed=true 时由 RedisStateConfig 的 @Primary 实现接管 */
@Component
public class InProcessWindowLimiter implements SlidingWindowLimiter {

    private final Map<String, Deque<Instant>> windows = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        Deque<Instant> q = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            Instant now = Instant.now();
            while (!q.isEmpty() && Duration.between(q.peekFirst(), now).compareTo(window) >= 0) q.pollFirst();
            if (q.size() >= limit) return false;
            q.addLast(now);
            return true;
        }
    }
}
