package com.soulvoyage.common.lock;

import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** B-05：审计链锁——拿不到锁必须明确 5002（审计不可静默丢失），拿到则执行并释放。 */
class AuditChainLockAspectTest {

    private final DistributedLock lock = mock(DistributedLock.class);
    private final ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);

    @Test
    void contentionFailsLoudly() {
        when(lock.tryLock(eq("lock:audit:chain"), any(), any())).thenReturn(false);

        BizException e = assertThrows(BizException.class,
                () -> new AuditChainLockAspect(lock).around(pjp));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, e.getErrorCode());
        verifyNoInteractions(pjp);
        verify(lock, never()).unlock(anyString());
    }

    @Test
    void proceedsAndReleasesWhenLockAcquired() throws Throwable {
        when(lock.tryLock(eq("lock:audit:chain"), any(), any())).thenReturn(true);
        when(pjp.proceed()).thenReturn("ok");

        assertEquals("ok", new AuditChainLockAspect(lock).around(pjp));
        verify(lock).unlock("lock:audit:chain");
    }
}
