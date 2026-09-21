package com.soulvoyage.orchestrator.sse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 进程内环形缓冲（既有 M5 口径）：无人订阅也缓存，64 条封顶，终态粘住供迟到订阅判断 */
public class InMemoryEventLog implements TaskEventLog {

    static final int BUFFER_SIZE = 64;

    private static final class Buf {
        final ArrayDeque<TaskEventBus.Event> buffer = new ArrayDeque<>();
        long seq;
        boolean terminal;
    }

    private final Map<String, Buf> bufs = new ConcurrentHashMap<>();

    @Override
    public long append(String taskNo, String event, String json) {
        Buf b = bufs.computeIfAbsent(taskNo, k -> new Buf());
        synchronized (b) {
            long id = ++b.seq;
            b.buffer.addLast(new TaskEventBus.Event(id, event, json));
            while (b.buffer.size() > BUFFER_SIZE) b.buffer.removeFirst();
            if ("done".equals(event) || "error".equals(event)) b.terminal = true;
            return id;
        }
    }

    @Override
    public List<TaskEventBus.Event> after(String taskNo, long fromId) {
        Buf b = bufs.get(taskNo);
        if (b == null) return List.of();
        synchronized (b) {
            List<TaskEventBus.Event> out = new ArrayList<>();
            for (TaskEventBus.Event ev : b.buffer) if (ev.id() > fromId) out.add(ev);
            return out;
        }
    }

    @Override
    public boolean terminal(String taskNo) {
        Buf b = bufs.get(taskNo);
        return b != null && b.terminal;
    }
}
