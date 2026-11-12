package com.leyon.soul.common.observability;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** H-05 慢请求环形缓冲：阈值、排序与上限。 */
class SlowRequestRecorderTest {

    @Test
    void ignoresBelowThresholdAndSortsByDuration() {
        SlowRequestRecorder r = new SlowRequestRecorder();
        r.record("GET", "/a", 200, 100, 500);
        r.record("GET", "/b", 200, 800, 500);
        r.record("POST", "/c", 500, 1200, 500);

        assertEquals(2, r.size());
        var top = r.top(10, Instant.now().minusSeconds(60));
        assertEquals("/c", top.get(0).path());
        assertEquals("/b", top.get(1).path());
    }

    @Test
    void capsAtFiveHundredEntries() {
        SlowRequestRecorder r = new SlowRequestRecorder();
        for (int i = 0; i < 510; i++) {
            r.record("GET", "/slow/" + i, 200, 600, 500);
        }
        assertEquals(500, r.size());
        assertTrue(r.top(500, Instant.now().minusSeconds(60)).size() <= 500);
    }
}
