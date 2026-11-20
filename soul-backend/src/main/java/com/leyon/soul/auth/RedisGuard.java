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

    /** 降级窗口：最近一次失败后的 60s 内认为"吊销状态不可信"（供 fail-closed 策略判定） */
    private static final long DEGRADED_WINDOW_MS = 60_000L;

    private final StringRedisTemplate redis;
    private final MeterRegistry registry;

    /** 最近一次降级的截止时刻（volatile：过滤器线程与业务线程跨线程读） */
    private volatile long degradedUntil = 0L;

    /** 是否处于"刚刚故障过"的窗口内：写入/管理端据此 fail-closed（普通读接口仍降级放行） */
    public boolean degradedRecently() {
        return System.currentTimeMillis() < degradedUntil;
    }

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
        degradedUntil = System.currentTimeMillis() + DEGRADED_WINDOW_MS;
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
