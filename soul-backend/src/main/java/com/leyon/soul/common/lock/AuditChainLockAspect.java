package com.leyon.soul.common.lock;

import com.leyon.soul.common.api.ErrorCode;
import com.leyon.soul.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * B-05：审计哈希链跨节点互斥。
 *
 * <p>只包裹 {@code AuditService.record} 的调用边界，不改 audit/** 的算法、字段与 JVM 锁，
 * 因此历史链校验口径完全不变。拿不到锁时明确 5002（审计不可静默丢失）；
 * Redis 故障由 {@link DistributedLock} 降级为进程内互斥并告警。
 */
@Aspect
@Component
@RequiredArgsConstructor
public class AuditChainLockAspect {

    /** 审计单次写入很短：租约 5s 足够；最多等 2s，避免登录链路被长时间挂起 */
    private static final String KEY = "lock:audit:chain";
    private static final Duration LEASE = Duration.ofSeconds(5);
    private static final Duration WAIT = Duration.ofSeconds(2);

    private final DistributedLock lock;

    @Around("execution(* com.leyon.soul.audit.AuditService.record(..))")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        if (!lock.tryLock(KEY, LEASE, WAIT)) {
            throw new BizException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        try {
            return pjp.proceed();
        } finally {
            lock.unlock(KEY);
        }
    }
}
