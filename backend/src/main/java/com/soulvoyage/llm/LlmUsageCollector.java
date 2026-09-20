package com.soulvoyage.llm;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * O3 成本归集：LlmClient 每次应答记入当前线程累加器，调度中心在步骤日志落库时 drain。
 * 任务在专属虚拟线程内串行执行步骤，ThreadLocal 生命周期与步骤一一对应，不跨任务串数。
 */
public final class LlmUsageCollector {

    public static final class Accum {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicLong tokensIn = new AtomicLong();
        final AtomicLong tokensOut = new AtomicLong();
        volatile String model;

        void add(int in, int out, String model) {
            calls.incrementAndGet();
            tokensIn.addAndGet(in);
            tokensOut.addAndGet(out);
            if (this.model == null) this.model = model;
        }

        void merge(Accum o) {
            calls.addAndGet(o.calls.get());
            tokensIn.addAndGet(o.tokensIn.get());
            tokensOut.addAndGet(o.tokensOut.get());
            if (model == null) model = o.model;
        }

        public int calls() { return calls.get(); }
        public int tokensIn() { return (int) tokensIn.get(); }
        public int tokensOut() { return (int) tokensOut.get(); }
        public String model() { return model; }
    }

    private static final ThreadLocal<Accum> TL = new ThreadLocal<>();

    /** 每次应答的全量观测钩子（MeterRegistry 等在启动时注入；null 时静默） */
    private static volatile java.util.function.Consumer<LlmClient.LlmResponse> hook = null;

    public static void setHook(java.util.function.Consumer<LlmClient.LlmResponse> h) {
        hook = h;
    }

    private LlmUsageCollector() {}

    public static void begin() {
        TL.set(new Accum());
    }

    /** LlmClient 实现应答前调用；未 begin 时静默忽略（直连调用/测试不受影响） */
    public static void record(LlmClient.LlmResponse resp) {
        Accum a = TL.get();
        if (a != null) a.add(resp.tokensIn(), resp.tokensOut(), resp.model());
        java.util.function.Consumer<LlmClient.LlmResponse> h = hook;
        if (h != null) {
            try {
                h.accept(resp);
            } catch (Exception ignore) {
                // 观测失败绝不影响推理链路
            }
        }
    }

    /** 取走并清空当前累加器（幂等，可多次调用） */
    public static Accum drain() {
        Accum a = TL.get();
        TL.set(new Accum());
        return a == null ? new Accum() : a;
    }

    public static void clear() {
        TL.remove();
    }
}
