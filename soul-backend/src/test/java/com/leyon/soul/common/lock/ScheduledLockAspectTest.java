package com.leyon.soul.common.lock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** B-06：调度切面——抢到锁才执行，未抢到安静跳过并计数。 */
class ScheduledLockAspectTest {

    private final DistributedLock lock = mock(DistributedLock.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    private final MethodSignature sig = mock(MethodSignature.class);

    private ScheduledLockAspect aspect() {
        when(pjp.getSignature()).thenReturn(sig);
        when(sig.getDeclaringType()).thenReturn(DailyJob.class);
        when(sig.getName()).thenReturn("sweep");
        return new ScheduledLockAspect(lock, registry);
    }

    static class DailyJob {
        void sweep() {}
    }

    @Test
    void skipsWhenAnotherNodeHoldsTheLock() throws Throwable {
        when(lock.tryLock(eq("lock:sched:DailyJob.sweep"), any(), eq(Duration.ZERO))).thenReturn(false);

        assertNull(aspect().around(pjp));
        verify(pjp, never()).proceed();
        assertEquals(1.0, registry.get("sv.sched.skipped").tag("job", "DailyJob.sweep").counter().count());
    }

    @Test
    void proceedsAndReleasesWhenLockAcquired() throws Throwable {
        when(lock.tryLock(eq("lock:sched:DailyJob.sweep"), any(), eq(Duration.ZERO))).thenReturn(true);
        when(pjp.proceed()).thenReturn("done");

        assertEquals("done", aspect().around(pjp));
        verify(lock).unlock("lock:sched:DailyJob.sweep");
        assertTrue(registry.find("sv.sched.skipped").counters().isEmpty());
    }
}
