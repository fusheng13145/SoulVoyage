package com.leyon.soul.domain.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 导出"领取即焚"的一次性回归（2026-11-20 并发审查）。
 *
 * <p>缺陷原状：{@code take} 先 {@code get} 再 {@code remove}，两个并发请求会双双拿到 CLAIMED，
 * 一次性链接被用两次。Redis 版用 Lua 已经原子，只有进程内实现有这个洞。
 */
class InMemoryExportStoreTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void concurrentTakeYieldsExactlyOneClaim() throws Exception {
        InMemoryExportStore store = new InMemoryExportStore();
        int rounds = 40;
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int r = 0; r < rounds; r++) {
                String id = "exp-" + r;
                store.put(id, 7L, mapper.createObjectNode().put("round", r));
                CountDownLatch start = new CountDownLatch(1);
                List<java.util.concurrent.Future<ExportStore.Taken>> fs = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    fs.add(pool.submit(() -> {
                        start.await();
                        return store.take(id, 7L);
                    }));
                }
                start.countDown();
                int claimed = 0;
                for (var f : fs) {
                    var t = f.get(10, TimeUnit.SECONDS);
                    if (t.outcome() == ExportStore.Outcome.CLAIMED) claimed++;
                }
                assertEquals(1, claimed, "第 " + r + " 轮出现 " + claimed + " 次领取，一次性语义被击穿");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void foreignTakeDoesNotConsume() {
        InMemoryExportStore store = new InMemoryExportStore();
        store.put("x1", 7L, mapper.createObjectNode());
        assertEquals(ExportStore.Outcome.FOREIGN, store.take("x1", 8L).outcome());
        assertEquals(ExportStore.Outcome.CLAIMED, store.take("x1", 7L).outcome());
        assertEquals(ExportStore.Outcome.MISSING, store.take("x1", 7L).outcome());
    }

    @Test
    void unknownIdIsMissing() {
        InMemoryExportStore store = new InMemoryExportStore();
        assertEquals(ExportStore.Outcome.MISSING, store.take("never-put", 1L).outcome());
    }
}
