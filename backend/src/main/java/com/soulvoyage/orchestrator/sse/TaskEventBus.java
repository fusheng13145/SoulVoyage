package com.soulvoyage.orchestrator.sse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 任务事件总线：单节点内存版（多节点部署时切 Redis Pub/Sub，接口不变）。
 * 事件协议：task_created / step_started / middle_result / step_failed / task_status / done / error
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskEventBus {

    private final ObjectMapper mapper;
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String taskNo) {
        SseEmitter e = new SseEmitter(10 * 60 * 1000L);
        emitters.computeIfAbsent(taskNo, k -> new CopyOnWriteArrayList()).add(e);
        e.onCompletion(() -> remove(taskNo, e));
        e.onTimeout(() -> remove(taskNo, e));
        return e;
    }

    public void publish(String taskNo, String event, Map<String, Object> data) {
        List<SseEmitter> list = emitters.get(taskNo);
        if (list == null || list.isEmpty()) return;
        ObjectNode node = mapper.valueToTree(data);
        String json;
        try {
            json = mapper.writeValueAsString(node);
        } catch (Exception ex) {
            log.warn("event serialize failed: {}", event, ex);
            return;
        }
        for (SseEmitter e : list) {
            try {
                e.send(SseEmitter.event().name(event).data(json, org.springframework.http.MediaType.APPLICATION_JSON));
            } catch (IOException ex) {
                remove(taskNo, e);
            } catch (Exception ex) {
                remove(taskNo, e);
                log.warn("sse send failed", ex);
            }
        }
        if ("done".equals(event) || "error".equals(event)) {
            list.forEach(SseEmitter::complete);
            emitters.remove(taskNo);
        }
    }

    private void remove(String taskNo, SseEmitter e) {
        List<SseEmitter> list = emitters.get(taskNo);
        if (list != null) list.remove(e);
    }
}
