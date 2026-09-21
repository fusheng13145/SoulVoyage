package com.soulvoyage.orchestrator.sse;

import java.util.List;

/**
 * 任务事件日志（M13 拆分点）：TaskEventBus 只管"本机在线订阅者的投递与心跳"，
 * 事件的持久缓冲/断点重放/终态判定交给可替换的存储——
 * 进程内环形缓冲（默认，单机）或 Redis Stream（多节点：任何节点都能重放任何节点产出的事件）。
 */
public interface TaskEventLog {

    /** 追加一条事件，返回该任务的单调递增 id（从 1 起） */
    long append(String taskNo, String event, String json);

    /** id 严格大于 fromId 的缓冲事件快照（按 id 升序；缓冲上限 64 条） */
    List<TaskEventBus.Event> after(String taskNo, long fromId);

    /** 该任务是否已到达终态（done/error）；从未有过事件返回 false */
    boolean terminal(String taskNo);
}
