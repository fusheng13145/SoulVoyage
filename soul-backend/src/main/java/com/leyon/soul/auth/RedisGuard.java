package com.leyon.soul.auth;

import com.leyon.soul.common.api.ErrorCode;
import com.leyon.soul.common.exception.BizException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * B-16：认证链路的 Redis 降级边界。
 *
 * <p>读操作（黑名单/epoch/失败计数）故障时放行并记 {@code sv.redis.degraded}：鉴权不因缓存不可用整体 500，
 * 代价是吊销状态在故障窗口内不可见（告警指标可观测）。
 * 写操作分两档：失败计数属"尽力而为"（登录不应被缓存拖死）；
 * refresh 轮换/吊销/登出属"不可半写"（无法记录吊销就明确 503，避免旧令牌被重复使用）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisGuard {

    private final StringRedisTemplate redis;
    private final MeterRegistry registry;

    /** 降级读：故障返回 null，由调用方按"无记录"处理 */
    public <T> T read(String op, Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (DataAccessException e) {
            degraded(op, "read", e);
            return null;
        }
    }

    /** 尽力写：故障只告警，不影响主流程 */
    public void writeBestEffort(String op, Runnable action) {
        try {
            action.run();
        } catch (DataAccessException e) {
            degraded(op, "write", e);
        }
    }

    /** 必须成功的写：故障抛 5002/503，让客户端明确重试而不是产生半吊销状态 */
    public <T> T writeRequired(String op, Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (DataAccessException e) {
            degraded(op, "required", e);
            throw new BizException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private void degraded(String op, String mode, Exception e) {
        registry.counter("sv.redis.degraded", "op", op, "mode", mode).increment();
        log.warn("redis {} degraded ({}): {}", mode, op, e.getMessage());
    }

    /** 仅供测试/诊断：是否可连接（不抛异常） */
    public boolean ping() {
        return read("jwt.ping", () -> {
            redis.hasKey("jwt:ping");
            return Boolean.TRUE;
        }) != null;
    }
}
