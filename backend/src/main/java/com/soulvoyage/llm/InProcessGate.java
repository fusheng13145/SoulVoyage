package com.soulvoyage.llm;

import java.time.Duration;

/** 进程内闸门（M10 既有口径）：监视器锁补币扣币 + wait/notify 排队，0 表示该层关闭 */
public class InProcessGate implements LlmGate {

    private final long coinsPerMinute;
    private final int maxInFlight;

    private double coins;
    private long lastRefillNanos;
    private int inFlight;

    public InProcessGate(long coinsPerMinute, int maxInFlight) {
        this.coinsPerMinute = coinsPerMinute;
        this.maxInFlight = maxInFlight;
        this.coins = coinsPerMinute;
        this.lastRefillNanos = System.nanoTime();
    }

    @Override
    public synchronized boolean tryCoin() {
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

    @Override
    public synchronized boolean tryEnter(Duration wait) throws InterruptedException {
        if (maxInFlight <= 0) return true;
        long deadline = System.nanoTime() + wait.toNanos();
        while (inFlight >= maxInFlight) {
            long left = deadline - System.nanoTime();
            if (left <= 0) return false;
            wait(left / 1_000_000, (int) (left % 1_000_000));
        }
        inFlight++;
        return true;
    }

    @Override
    public synchronized void release() {
        if (maxInFlight <= 0) return;
        inFlight--;
        notifyAll();
    }

    @Override
    public String name() {
        return "in-process";
    }
}
