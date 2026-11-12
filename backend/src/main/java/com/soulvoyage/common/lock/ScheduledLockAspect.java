package com.soulvoyage.common.lock;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * B-06：@Scheduled 跨节点互斥。
 *
 * <p>统一拦截所有调度方法（危机巡检/提醒/漫聊封段/成长信/内容快照），
 * 不在 domain/crisis/** 等 C 目录加任何代码。租约 30 分钟覆盖单次巡检；
 * 未抢到锁直接跳过本次触发（单节点行为与原来一致：锁永远可得）。
 */
@Aspect
@Component
@RequiredArgsConstructor
public class ScheduledLockAspect {

    private static final Duration LEASE = Duration.ofMinutes(30);

    private final DistributedLock lock;
    private final MeterRegistry registry;

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        String job = sig.getDeclaringType().getSimpleName() + "." + sig.getName();
        String key = "lock:sched:" + job;
        if (!lock.tryLock(key, LEASE, Duration.ZERO)) {
            registry.counter("sv.sched.skipped", "job", job).increment();
            return null;   // 其他节点已在执行：本节点安静跳过
        }
        try {
            return pjp.proceed();
        } finally {
            lock.unlock(key);
        }
    }
}
