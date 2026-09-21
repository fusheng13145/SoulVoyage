package com.soulvoyage.orchestrator.sse;

/**
 * 事件实时中继（M13）：进程内模式不需要——发布方与订阅者在同一堆内存里；
 * Redis 模式用 pub/sub 把新事件推给所有节点，各节点只投递自己名下挂着的 SseEmitter。
 * 断点补发不依赖中继（那是 TaskEventLog 的 XRANGE 职责），中继只负责"此刻在线的那口气"。
 */
public interface EventRelay {

    /** 把一条刚落库的事件广播出去（各节点收到后走本地投递） */
    void broadcast(String taskNo, TaskEventBus.Event ev);
}
