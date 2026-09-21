package com.soulvoyage.llm;

import java.time.Duration;

/**
 * LlmGuard 的两层判据抽象（M13）：进程内实现保持"0=该层关闭、单测零基础设施"的既有立场；
 * Redis 实现让令牌桶与在途闸门跨节点共享（多节点可行性，技术债第 2 条）。
 * 异常与指标仍归 {@link LlmGuard}——Gate 只回答"放不放行"。
 */
public interface LlmGate {

    /** 令牌桶扣一枚（rate<=0 恒 true） */
    boolean tryCoin();

    /** 在途槽位：满则排队到 wait，仍等不到返回 false（inflight<=0 恒 true 且不占额度） */
    boolean tryEnter(Duration wait) throws InterruptedException;

    /** 归还 tryEnter 占用的槽位（未占用时不得调用） */
    void release();

    /** 实现名（in-process / redis），describe() 用——看板要能看出闸门到底跑在哪 */
    String name();
}
