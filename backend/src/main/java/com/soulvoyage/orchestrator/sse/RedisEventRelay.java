package com.soulvoyage.orchestrator.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

/**
 * Redis pub/sub 版中继（M13）：channel 按任务分片（orch:ev:{taskNo}），
 * 消息体只带 {id,event,json}——订阅端拿到后交回 TaskEventBus 的本地投递。
 * 发布失败不吞事件：事件已在 Stream 里，重连补发兜底，实时性降级为"下一次断点续传"。
 */
@Slf4j
public class RedisEventRelay implements EventRelay, MessageListener {

    public static final String CHANNEL_PREFIX = "orch:ev:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    /** 由装配处回填（bus → relay 与 relay → bus 互相引用，setter 打破环） */
    private volatile BiConsumer<String, TaskEventBus.Event> localDeliver;

    public RedisEventRelay(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public void setLocalDeliver(BiConsumer<String, TaskEventBus.Event> handler) {
        this.localDeliver = handler;
    }

    @Override
    public void broadcast(String taskNo, TaskEventBus.Event ev) {
        try {
            ObjectNode n = mapper.createObjectNode();
            n.put("id", ev.id());
            n.put("event", ev.name());
            n.put("json", ev.json());
            redis.convertAndSend(CHANNEL_PREFIX + taskNo, mapper.writeValueAsString(n));
        } catch (Exception e) {
            log.warn("relay broadcast failed task={} id={}", taskNo, ev.id(), e);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        BiConsumer<String, TaskEventBus.Event> handler = localDeliver;
        if (handler == null) return;
        try {
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            String taskNo = channel.substring(CHANNEL_PREFIX.length());
            var n = mapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            handler.accept(taskNo,
                    new TaskEventBus.Event(n.get("id").asLong(), n.get("event").asText(), n.get("json").asText()));
        } catch (Exception e) {
            log.warn("relay receive failed", e);
        }
    }
}
