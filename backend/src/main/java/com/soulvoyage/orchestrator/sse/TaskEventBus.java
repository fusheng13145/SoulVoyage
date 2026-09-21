package com.soulvoyage.orchestrator.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 任务事件总线（下篇·S4 可靠流）：本机在线订阅者的投递、心跳与清理在这里；
 * 事件的持久缓冲/断点重放/终态判定委托 {@link TaskEventLog}（默认进程内环形缓冲，
 * M13 起 SV_DISTRIBUTED=true 换 Redis Stream——事件在任意节点产出、任意节点重放），
 * 跨节点实时投递委托 {@link EventRelay}（Redis pub/sub）。
 * 事件协议：task_created / step_started / step_retry / middle_result / step_failed / task_status / done / error
 * 可靠性：每条事件带任务内单调 id 并进入 64 条环形缓冲（无人订阅也缓存）；
 * 重连携带 Last-Event-ID 即可从断点续传（补发与实时流交叠处按 id 去重）；
 * 20s 心跳注释帧防代理空闲断链；本地终态流保留 10 分钟供迟到订阅。
 */
@Slf4j
@Component
public class TaskEventBus {

    private static final long TERMINAL_KEEP_MS = 10 * 60 * 1000L;
    private static final long HEARTBEAT_SECONDS = 20;

    private final ObjectMapper mapper;
    private final TaskEventLog logstore;
    private final EventRelay relay;    // null=单节点直投（进程内模式）
    private final Map<String, Stream> streams = new ConcurrentHashMap<>();
    private ScheduledExecutorService heartbeat;

    public record Event(long id, String name, String json) {}

    private static final class Stream {
        final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
        final Map<SseEmitter, AtomicLong> seen = new ConcurrentHashMap<>();   // 补发×实时交叠去重
        volatile long lastActive = System.currentTimeMillis();
    }

    /** 测试/单机直构：进程内日志 + 无中继，与 M5–M12 行为逐字一致 */
    public TaskEventBus(ObjectMapper mapper) {
        this(mapper, new InMemoryEventLog(), null);
    }

    @Autowired
    public TaskEventBus(ObjectMapper mapper, ObjectProvider<TaskEventLog> logP, ObjectProvider<EventRelay> relayP) {
        this(mapper, logP.getIfAvailable(InMemoryEventLog::new), relayP.getIfAvailable());
    }

    TaskEventBus(ObjectMapper mapper, TaskEventLog logstore, EventRelay relay) {
        this.mapper = mapper;
        this.logstore = logstore;
        this.relay = relay;
    }

    @PostConstruct
    void start() {
        if (relay instanceof RedisEventRelay r) r.setLocalDeliver(this::deliverLocal);
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(this::tick, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (heartbeat != null) heartbeat.shutdownNow();
    }

    /** 订阅任务事件流；lastEventId 之后的缓冲事件先补发，再挂接实时流（交叠按 id 去重） */
    public SseEmitter subscribe(String taskNo, String lastEventId) {
        SseEmitter e = new SseEmitter(10 * 60 * 1000L);
        Stream s = streams.computeIfAbsent(taskNo, k -> new Stream());
        long from = parseId(lastEventId);
        s.seen.put(e, new AtomicLong(from));
        s.emitters.add(e);
        s.lastActive = System.currentTimeMillis();
        for (Event ev : logstore.after(taskNo, from)) {
            if (!deliverLocal(taskNo, ev)) break;    // 客户端已断开
        }
        if (logstore.terminal(taskNo)) {
            remove(taskNo, e);
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
        long id = logstore.append(taskNo, event, json);
        Event ev = new Event(id, event, json);
        if (relay != null) relay.broadcast(taskNo, ev);   // 含本节点在内的所有订阅者经中继回收
        else deliverLocal(taskNo, ev);
    }

    /** 本地投递（中继回调入口）：只发给本机挂着的 emitter，按 id 去重、已发过即跳 */
    public boolean deliverLocal(String taskNo, Event ev) {
        Stream s = streams.get(taskNo);
        if (s == null) return true;
        boolean broken = false;
        for (SseEmitter e : s.emitters) {
            AtomicLong last = s.seen.get(e);
            if (last == null) continue;
            boolean mine = false;
            while (true) {
                long prev = last.get();
                if (ev.id() <= prev) break;               // 补发与实时交叠：谁先推进 id 谁投递
                if (last.compareAndSet(prev, ev.id())) { mine = true; break; }
            }
            if (mine && !send(e, ev)) {
                remove(taskNo, e);
                broken = true;
            }
        }
        return !broken;
    }

    private boolean send(SseEmitter e, Event ev) {
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
            if (s.emitters.isEmpty() && now - s.lastActive > TERMINAL_KEEP_MS) {
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
        if (s != null) {
            s.emitters.remove(e);
            s.seen.remove(e);
        }
    }

    /** 测试/诊断：id 严格大于 fromId 的缓冲事件快照 */
    List<TaskEventBus.Event> replayFrom(String taskNo, long fromId) {
        return logstore.after(taskNo, fromId);
    }

    boolean isTerminal(String taskNo) {
        return logstore.terminal(taskNo);
    }
}
