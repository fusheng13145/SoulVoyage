package com.leyon.soul;

import com.leyon.soul.common.exception.BizException;
import com.leyon.soul.domain.checkin.CheckInService;
import com.leyon.soul.domain.checkin.MoodCheckInRepository;
import com.leyon.soul.domain.task.TaskInstanceRepository;
import com.leyon.soul.domain.user.UserRepository;
import com.leyon.soul.dto.AuthDtos.RegisterReq;
import com.leyon.soul.auth.AuthService;
import com.leyon.soul.orchestrator.PipelineCode;
import com.leyon.soul.orchestrator.TaskGateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发闸门回归（2026-11-20 并发审查的两处实测缺陷）。
 *
 * <p>证据（修复前的真实 HTTP 实测）：60 路并发 POST /tasks → 4 个 202（上限本应 2）；
 * 10 路并发同日 POST /mood-check-ins → 3 个 200 + 7 个 HTTP 500（唯一键冲突未转幂等）。
 * 修复手段是"同用户行锁 + 单事务"（{@code UserRepository#lockById}），这里在服务层复现同一场景并断言不再穿透。
 */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrencyGuardTest {

    private static final String PWD = "SvFixture!2026";

    @Autowired AuthService auth;
    @Autowired UserRepository users;
    @Autowired TaskGateService gate;
    @Autowired TaskInstanceRepository tasks;
    @Autowired CheckInService checkIn;
    @Autowired MoodCheckInRepository checkIns;

    private long newUser() {
        String u = "conc" + System.nanoTime();
        return auth.register(new RegisterReq(u, PWD, "并发"), "conc-ip").userId();
    }

    @Test
    void taskGateHoldsLimitUnderConcurrency() throws Exception {
        long uid = newUser();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                fs.add(pool.submit(() -> {
                    try {
                        start.await();
                        gate.create(uid, PipelineCode.COGNITIVE_PIPELINE, null);
                        ok.incrementAndGet();
                    } catch (BizException e) {
                        rejected.incrementAndGet();
                    } catch (Exception e) {
                        // 事务竞争导致的其它异常也算"未穿透"，但记录下来便于排查
                        rejected.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : fs) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, ok.get(), "并发下创建数超出上限（说明 count→save 仍非原子）");
        assertEquals(threads - 2, rejected.get());
        assertEquals(2, tasks.countActive(uid));
    }

    @Test
    void sameDayCheckInIsIdempotentUnderConcurrency() throws Exception {
        long uid = newUser();
        LocalDate today = LocalDate.now();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        List<String> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                fs.add(pool.submit(() -> {
                    try {
                        start.await();
                        checkIn.checkIn(uid, today,
                                new CheckInService.CheckInReq("CALM", 3, 3, "并发打卡 " + idx));
                        ok.incrementAndGet();
                    } catch (Exception e) {
                        errors.add(e.getClass().getSimpleName() + ":" + e.getMessage());
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : fs) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(threads, ok.get(), "同日并发打卡应全部幂等成功，实际失败：" + errors);
        assertTrue(checkIns.findByUserIdAndCheckDate(uid, today).isPresent());
    }
}
