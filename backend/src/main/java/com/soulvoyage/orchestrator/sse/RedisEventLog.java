package com.soulvoyage.orchestrator.sse;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Redis Stream 版事件日志（M13，§5.2 设计的 orch:events:{taskNo} MAXLEN 64 / TTL 24h）：
 * 事件在任意节点产出、在任意节点重放——SSE 断线后打到另一台也能从 Last-Event-ID 续传。
 * id 仍用自增号（INCR orch:seq:{taskNo}）而不是 Stream 自带的 ms-seq：
 * 前端把 Last-Event-ID 当不透明字符串回传，但"单调小整数"让环形修剪与断点比较都保持直觉。
 */
public class RedisEventLog implements TaskEventLog {

    static final int BUFFER_SIZE = 64;
    private static final Duration KEEP = Duration.ofHours(24);

    private final StringRedisTemplate redis;

    public RedisEventLog(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long append(String taskNo, String event, String json) {
        Long id = redis.opsForValue().increment(seqKey(taskNo));
        redis.expire(seqKey(taskNo), KEEP);
        long seq = id == null ? 0L : id;
        String key = streamKey(taskNo);
        redis.opsForStream().add(StreamRecords.newRecord().in(key)
                .ofMap(java.util.Map.of("id", Long.toString(seq), "event", event, "json", json)));
        redis.opsForStream().trim(key, BUFFER_SIZE);   // XTRIM MAXLEN：近似 64 条环形语义
        redis.expire(key, KEEP);
        return seq;
    }

    @Override
    public List<TaskEventBus.Event> after(String taskNo, long fromId) {
        return rangeAll(taskNo).stream()
                .map(RedisEventLog::toEvent)
                .filter(ev -> ev.id() > fromId)
                .toList();
    }

    @Override
    public boolean terminal(String taskNo) {
        List<MapRecord<String, Object, Object>> last = redis.<Object, Object>opsForStream()
                .reverseRange(streamKey(taskNo), Range.unbounded(), Limit.limit().count(1));
        return !last.isEmpty() && isTerminal(last.get(0));
    }

    // ---------------- internals ----------------

    private List<MapRecord<String, Object, Object>> rangeAll(String taskNo) {
        return redis.<Object, Object>opsForStream().range(streamKey(taskNo), Range.unbounded());
    }

    private static TaskEventBus.Event toEvent(MapRecord<String, Object, Object> r) {
        return new TaskEventBus.Event(
                Long.parseLong(str(r.getValue().get("id"))),
                str(r.getValue().get("event")),
                str(r.getValue().get("json")));
    }

    private static boolean isTerminal(MapRecord<String, Object, Object> r) {
        String name = str(r.getValue().get("event"));
        return "done".equals(name) || "error".equals(name);
    }

    private static String str(Object o) {
        return o instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(o);
    }

    private static String seqKey(String taskNo) {
        return "orch:seq:" + taskNo;
    }

    private static String streamKey(String taskNo) {
        return "orch:events:" + taskNo;
    }
}
