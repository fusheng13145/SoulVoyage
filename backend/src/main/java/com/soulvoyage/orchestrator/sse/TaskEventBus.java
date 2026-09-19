package com.soulvoyage.orchestrator.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 任务事件总线（下篇·S4 可靠流）：单节点内存版，多节点部署时切 Redis Stream（接口不变，技术债）。
 * 事件协议：task_created / step_started / step_retry / middle_result / step_failed / task_status / done / error
 * 可靠性：每条事件带单调 id 并进入 64 条环形缓冲（无人订阅也缓存）；
 * 重连携带 Last-Event-ID 即可从断点续传；20s 心跳注释帧防止代理空闲断链；终态流保留 10 分钟供迟到订阅。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskEventBus {

    private static final int BUFFER_SIZE = 64;
    private static final long TERMINAL_KEEP_MS = 10 * 60 * 1000L;
    private static final long HEARTBEAT_SECONDS = 20;

    private final ObjectMapper mapper;
    private final Map<String, Stream> streams = new ConcurrentHashMap<>();
    private ScheduledExecutorService heartbeat;

    record Event(long id, String name, String json) {}

    private static final class Stream {
        final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
        final ArrayDeque<Event> buffer = new ArrayDeque<>();
        long seq;
        volatile long lastActive = System.currentTimeMillis();
        volatile boolean terminal;
    }

    @PostConstruct
    void startHeartbeat() {
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(this::tick, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stopHeartbeat() {
        if (heartbeat != null) heartbeat.shutdownNow();
    }

    /** 订阅任务事件流；lastEventId 之后的缓冲事件先补发，再挂接实时流 */
    public SseEmitter subscribe(String taskNo, String lastEventId) {
        SseEmitter e = new SseEmitter(10 * 60 * 1000L);
        Stream s = streams.computeIfAbsent(taskNo, k -> new Stream());
        long from = parseId(lastEventId);
        boolean ended;
        synchronized (s) {
            for (Event ev : s.buffer) {
                if (ev.id() > from && !deliver(e, ev)) break;
            }
            ended = s.terminal;
            if (!ended) {
                s.emitters.add(e);
                s.lastActive = System.currentTimeMillis();
            }
        }
        if (ended) {
            e.complete();
            return e;
        }
        e.onCompletion(() -> remove(taskNo, e));
        e.onTimeout(() -> remove(taskNo, e));
        e.onError(x -> remove(taskNo, e));
        return e;
    }

    public void publish(String taskNo, String event, Map<String, Object> data) {
        String json;
        try {
            json = mapper.writeValueAsString(mapper.valueToTree(data));
        } catch (Exception ex) {
            log.warn("event serialize failed: {}", event, ex);
            return;
        }
        Stream s = streams.computeIfAbsent(taskNo, k -> new Stream());
        List<SseEmitter> targets;
        Event ev;
        boolean terminal = "done".equals(event) || "error".equals(event);
        synchronized (s) {
            ev = new Event(++s.seq, event, json);
            s.buffer.addLast(ev);
            while (s.buffer.size() > BUFFER_SIZE) s.buffer.removeFirst();
            s.lastActive = System.currentTimeMillis();
            if (terminal) s.terminal = true;
            targets = List.copyOf(s.emitters);
        }
        for (SseEmitter e : targets) {
            if (!deliver(e, ev)) remove(taskNo, e);
        }
        if (terminal) targets.forEach(SseEmitter::complete);
    }

    private boolean deliver(SseEmitter e, Event ev) {
        try {
            e.send(SseEmitter.event().id(String.valueOf(ev.id())).name(ev.name())
                    .data(ev.json(), MediaType.APPLICATION_JSON));
            return true;
        } catch (Exception ex) {
            return false;   // 客户端已断开，交给 remove/心跳清理
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        streams.forEach((taskNo, s) -> {
            for (SseEmitter e : s.emitters) {
                try {
                    e.send(SseEmitter.event().comment("ping"));
                } catch (Exception ex) {
                    remove(taskNo, e);
                }
            }
            if ((s.terminal || s.emitters.isEmpty()) && now - s.lastActive > TERMINAL_KEEP_MS) {
                streams.remove(taskNo, s);
            }
        });
    }

    private long parseId(String lastEventId) {
        try {
            return lastEventId == null ? 0L : Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void remove(String taskNo, SseEmitter e) {
        Stream s = streams.get(taskNo);
        if (s != null) s.emitters.remove(e);
    }

    /** 测试/诊断：id 严格大于 fromId 的缓冲事件快照 */
    List<Event> replayFrom(String taskNo, long fromId) {
        Stream s = streams.get(taskNo);
        if (s == null) return List.of();
        synchronized (s) {
            return s.buffer.stream().filter(ev -> ev.id() > fromId).toList();
        }
    }

    boolean isTerminal(String taskNo) {
        Stream s = streams.get(taskNo);
        return s != null && s.terminal;
    }
}
