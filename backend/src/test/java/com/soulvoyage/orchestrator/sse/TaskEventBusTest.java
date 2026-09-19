package com.soulvoyage.orchestrator.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 下篇·S4 事件总线回归：单调事件 id、64 条环形缓冲、Last-Event-ID 断点补发、终态标记 */
class TaskEventBusTest {

    private final TaskEventBus bus = new TaskEventBus(new ObjectMapper());

    @Test
    void idsMonotonicAndRingEvictsOldest() {
        for (int i = 1; i <= 70; i++) {
            bus.publish("T-RING", "task_status", Map.of("status", "RUNNING", "i", i));
        }
        List<TaskEventBus.Event> all = bus.replayFrom("T-RING", 0);
        assertEquals(64, all.size());
        assertEquals(7, all.get(0).id());          // 1-6 已被环出
        assertEquals(70, all.get(all.size() - 1).id());
        // Last-Event-ID 断点续传：只补发更大 id
        assertEquals(List.of(69L, 70L),
                bus.replayFrom("T-RING", 68).stream().map(TaskEventBus.Event::id).toList());
        assertTrue(bus.replayFrom("T-RING", 70).isEmpty());
    }

    @Test
    void bufferingContinuesWithoutSubscribersAndTerminalOnDone() {
        bus.publish("T-LATE", "step_started", Map.of("agent", "EMOTION", "stepSeq", 1));
        bus.publish("T-LATE", "middle_result", Map.of("agent", "EMOTION"));
        bus.publish("T-LATE", "done", Map.of("status", "SUCCESS"));
        assertTrue(bus.isTerminal("T-LATE"));
        assertEquals(List.of("step_started", "middle_result", "done"),
                bus.replayFrom("T-LATE", 0).stream().map(TaskEventBus.Event::name).toList());
        assertFalse(bus.isTerminal("T-UNKNOWN"));
        assertTrue(bus.replayFrom("T-UNKNOWN", 0).isEmpty());
    }
}
