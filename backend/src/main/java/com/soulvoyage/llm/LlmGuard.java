package com.soulvoyage.llm;

import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 手册 §九「LLM 是瓶颈：队列削峰 + 令牌桶限流」的落地点：所有推理出入口（chat/stream）共用一道闸门。
 * 两层判据：①令牌桶按分钟补币，币用完直接拒（4002，成本护栏）；②在途并发超上限时先短暂排队，
 * 排队仍等不到就拒（4001，宁可让用户看到"稍后再试"也不要让请求把线程池和上游配额拖死）。
 * 供应商无关——Mock 同样受限，好让这条链路在回归里是被真的走到的，而不是接真模型那天才第一次通电。
 * 静态挂载与 {@link LlmUsageCollector} 同构：LLM 客户端不是每个调用方都能注入依赖的位置。
 */
public final class LlmGuard {

    /** 关闭态：默认不限流（单元测试与本机开发不该被基础设施挡住） */
    private static volatile LlmGuard CURRENT = new LlmGuard(0, 0, Duration.ZERO, null);

    private final long coinsPerMinute;
    private final int maxInFlight;
    private final Duration queueWait;
    private final Counter[] rejects;        // [rate_limited, queued_out]

    private double coins;
    private long lastRefillNanos;
    private int inFlight;

    private LlmGuard(long coinsPerMinute, int maxInFlight, Duration queueWait, MeterRegistry reg) {
        this.coinsPerMinute = coinsPerMinute;
        this.maxInFlight = maxInFlight;
        this.queueWait = queueWait;
        this.coins = coinsPerMinute;
        this.lastRefillNanos = System.nanoTime();
        this.rejects = reg == null ? new Counter[0] : new Counter[]{
                reg.counter("sv.llm.reject", "reason", "rate_limited"),
                reg.counter("sv.llm.reject", "reason", "queue_timeout")};
    }

    /** 由配置装配（0=该层关闭）；重启或测试可反复调用，切换即时生效 */
    public static void configure(long coinsPerMinute, int maxInFlight, Duration queueWait, MeterRegistry reg) {
        CURRENT = new LlmGuard(coinsPerMinute, maxInFlight, queueWait, reg);
    }

    public static void disable() {
        configure(0, 0, Duration.ZERO, null);
    }

    /** 一次推理调用的准入：通过则执行，被拒则抛 BizException（4002/4001） */
    public static <T> T admit(Supplier<T> call) {
        LlmGuard g = CURRENT;
        boolean slotTaken = false;
        try {
            synchronized (g) {
                if (!g.tryCoin()) throw g.rateLimited();
                slotTaken = g.tryEnter();   // wait() 期间会释放监视器，排队不会把别人挡在门外
            }
            if (!slotTaken) throw g.queueTimeout();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw g.queueTimeout();
        }
        try {
            return call.get();
        } finally {
            if (slotTaken) {
                synchronized (g) {
                    g.inFlight--;
                    g.notifyAll();
                }
            }
        }
    }

    /** 补币 + 扣一枚；桶关闭（0）恒放行 */
    private boolean tryCoin() {
        if (coinsPerMinute <= 0) return true;
        long now = System.nanoTime();
        double gained = (now - lastRefillNanos) / 600_000_000_000.0 * coinsPerMinute;
        if (gained > 0) {
            coins = Math.min(coinsPerMinute, coins + gained);
            lastRefillNanos = now;
        }
        if (coins < 1) return false;
        coins -= 1;
        return true;
    }

    /** 在途并发闸门：满了就排队到 queueWait，仍不满则放弃（返回 false 由上层抛 4001） */
    private boolean tryEnter() throws InterruptedException {
        if (maxInFlight <= 0) return true;
        long deadline = System.nanoTime() + queueWait.toNanos();
        while (inFlight >= maxInFlight) {
            long left = deadline - System.nanoTime();
            if (left <= 0) return false;
            wait(left / 1_000_000, (int) (left % 1_000_000));
        }
        inFlight++;
        return true;
    }

    private BizException rateLimited() {
        if (rejects.length > 0) rejects[0].increment();
        return new BizException(ErrorCode.LLM_RATE_LIMIT, "模型繁忙，稍后再发一条");
    }

    private BizException queueTimeout() {
        if (rejects.length > 1) rejects[1].increment();
        return new BizException(ErrorCode.LLM_TIMEOUT, "前面排队的人有点多，过一会儿再试");
    }

    /** 现值快照：管理端看板/健康检查读它，确认闸门到底在不在工作 */
    public static String describe() {
        LlmGuard g = CURRENT;
        return "coins/min=" + g.coinsPerMinute + ", inFlight<=" + g.maxInFlight
                + ", queueWait=" + g.queueWait;
    }
}
