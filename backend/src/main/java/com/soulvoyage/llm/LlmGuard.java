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
 * M13 起判据本身抽到 {@link LlmGate}：默认进程内（0=关闭，单测与本机开发不被基础设施挡住），
 * SV_DISTRIBUTED=true 时换 {@link RedisGate}——桶与槽位跨节点共享，全局 QPS 才是真"全局"。
 * 静态挂载与 {@link LlmUsageCollector} 同构：LLM 客户端不是每个调用方都能注入依赖的位置。
 */
public final class LlmGuard {

    /** 关闭态：默认不限流（单元测试与本机开发不该被基础设施挡住） */
    private static volatile LlmGuard CURRENT =
            new LlmGuard(0, 0, Duration.ZERO, null, new InProcessGate(0, 0));

    private final long coinsPerMinute;
    private final int maxInFlight;
    private final Duration queueWait;
    private final LlmGate gate;
    private final Counter[] rejects;        // [rate_limited, queued_out]

    private LlmGuard(long coinsPerMinute, int maxInFlight, Duration queueWait,
                     MeterRegistry reg, LlmGate gate) {
        this.coinsPerMinute = coinsPerMinute;
        this.maxInFlight = maxInFlight;
        this.queueWait = queueWait;
        this.gate = gate;
        this.rejects = reg == null ? new Counter[0] : new Counter[]{
                reg.counter("sv.llm.reject", "reason", "rate_limited"),
                reg.counter("sv.llm.reject", "reason", "queue_timeout")};
    }

    /** 由配置装配（0=该层关闭）；重启或测试可反复调用，切换即时生效。默认进程内闸门 */
    public static void configure(long coinsPerMinute, int maxInFlight, Duration queueWait, MeterRegistry reg) {
        CURRENT = new LlmGuard(coinsPerMinute, maxInFlight, queueWait, reg,
                new InProcessGate(coinsPerMinute, maxInFlight));
    }

    /** 分布式装配（M13）：同一套阈值，判据换成跨节点共享的 Redis 闸门 */
    public static void configure(long coinsPerMinute, int maxInFlight, Duration queueWait,
                                 MeterRegistry reg, LlmGate gate) {
        CURRENT = new LlmGuard(coinsPerMinute, maxInFlight, queueWait, reg, gate);
    }

    public static void disable() {
        configure(0, 0, Duration.ZERO, null);
    }

    /** 一次推理调用的准入：通过则执行，被拒则抛 BizException（4002/4001） */
    public static <T> T admit(Supplier<T> call) {
        LlmGuard g = CURRENT;
        boolean slotTaken = false;
        try {
            if (!g.gate.tryCoin()) throw g.rateLimited();
            slotTaken = g.gate.tryEnter(g.queueWait);
            if (!slotTaken) throw g.queueTimeout();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw g.queueTimeout();
        }
        try {
            return call.get();
        } finally {
            if (slotTaken) g.gate.release();
        }
    }

    private BizException rateLimited() {
        if (rejects.length > 0) rejects[0].increment();
        return new BizException(ErrorCode.LLM_RATE_LIMIT, "模型繁忙，稍后再发一条");
    }

    private BizException queueTimeout() {
        if (rejects.length > 1) rejects[1].increment();
        return new BizException(ErrorCode.LLM_TIMEOUT, "前面排队的人有点多，过一会儿再试");
    }

    /** 现值快照：管理端看板/健康检查读它，确认闸门到底在不在工作、跑在哪一侧 */
    public static String describe() {
        LlmGuard g = CURRENT;
        return "gate=" + g.gate.name() + ", coins/min=" + g.coinsPerMinute
                + ", inFlight<=" + g.maxInFlight + ", queueWait=" + g.queueWait;
    }
}
