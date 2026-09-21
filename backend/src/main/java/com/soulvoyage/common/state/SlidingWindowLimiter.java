package com.soulvoyage.common.state;

import java.time.Duration;

/**
 * 用户级滑动窗限流（技术债第 2 条的收口点）：同一口径的两套实现——
 * 进程内（默认，单机开发/测试零基础设施依赖）与 Redis（多节点共享窗口，SV_DISTRIBUTED=true 时启用）。
 * 键按"场景+用户"划分（如 companion:{userId} / voice:{userId}），换设备/换节点都在同一窗内照限。
 */
public interface SlidingWindowLimiter {

    /** 申请一次通行：窗口内未满 limit 则记一笔并返回 true，否则不记账返回 false */
    boolean tryAcquire(String key, int limit, Duration window);
}
