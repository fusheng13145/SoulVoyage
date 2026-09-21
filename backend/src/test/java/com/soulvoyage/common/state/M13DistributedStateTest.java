package com.soulvoyage.common.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.export.ExportStore;
import com.soulvoyage.domain.export.RedisExportStore;
import com.soulvoyage.llm.LlmGuard;
import com.soulvoyage.llm.RedisGate;
import com.soulvoyage.orchestrator.sse.RedisEventLog;
import com.soulvoyage.orchestrator.sse.RedisEventRelay;
import com.soulvoyage.orchestrator.sse.TaskEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M13 跨节点状态回归（技术债第 2 条兑现）：这里手工搭出**两份互相不认识的对象实例**
 * 冒充集群里的两个节点——它们唯一的共同点就是同一条 Redis。
 * 于是"共享"是被真的验证的，不是被 mock 出来的：桶只有一份、窗只有一份、
 * 事件在 A 产出 B 能重放、导出快照 A 生成 B 能领取且**字节里查不到明文**。
 * 默认（distributed=false）的进程内口径由既有回归继续守着，两类语义各自有钉。
 */
@SpringBootTest
@ActiveProfiles("test")
class M13DistributedStateTest {

    @Autowired StringRedisTemplate redis;
    @Autowired RedisConnectionFactory cf;
    @Autowired ObjectMapper mapper;
    @Autowired CryptoService crypto;

    @AfterEach
    void reset() {
        LlmGuard.disable();
        redis.delete(List.of("llm:bucket:global", "llm:inflight"));
    }

    private long freshUser() {
        return 900_000_000L + System.nanoTime() % 100_000_000L;
    }

    // ---------------- ① LLM 闸门 ----------------

    @Test
    void tokenBucketIsGlobalAcrossNodesNotPerNode() {
        redis.delete("llm:bucket:global");
        RedisGate nodeA = new RedisGate(redis, 3, 0, Duration.ofSeconds(30));
        RedisGate nodeB = new RedisGate(redis, 3, 0, Duration.ofSeconds(30));

        assertTrue(nodeA.tryCoin(), "第 1 枚");
        assertTrue(nodeB.tryCoin(), "第 2 枚——换个节点也要算进同一个桶");
        assertTrue(nodeA.tryCoin(), "第 3 枚");
        assertFalse(nodeB.tryCoin(), "全局 3 枚用完就该拒，而不是每节点各 3 枚");
        assertFalse(nodeA.tryCoin());
    }

    @Test
    void inflightSlotOfDeadNodeIsReclaimedByLease() throws Exception {
        redis.delete("llm:inflight");
        RedisGate nodeA = new RedisGate(redis, 0, 1, Duration.ofMillis(400));
        RedisGate nodeB = new RedisGate(redis, 0, 1, Duration.ofMillis(400));

        assertTrue(nodeA.tryEnter(Duration.ofSeconds(1)), "A 先占住在途位");
        // A 崩溃：不 release（进程内版这会永久占位，多节点最怕的就是这个）
        long start = System.nanoTime();
        assertTrue(nodeB.tryEnter(Duration.ofSeconds(3)), "租约到点后 B 应能接管槽位");
        long waitedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(waitedMs >= 300, "应等到租约过期（实测 " + waitedMs + "ms）");
        nodeB.release();
        assertTrue(nodeA.tryEnter(Duration.ofSeconds(1)), "释放后槽位可再用");
        nodeA.release();
        assertEquals(0L, redis.opsForZSet().size("llm:inflight"), "正常收尾后不应残留占用");
    }

    @Test
    void llmGuardRunsOnRedisGateWithSameErrorCodes() {
        redis.delete("llm:bucket:global");
        RedisGate gate = new RedisGate(redis, 1, 0, Duration.ofSeconds(30));
        LlmGuard.configure(1, 0, Duration.ZERO, null, gate);
        assertEquals("ok", LlmGuard.admit(() -> "ok"));
        BizException e = assertThrows(BizException.class, () -> LlmGuard.admit(() -> "ok"));
        assertEquals(4002, e.getErrorCode().code(), "换闸门不换错误码：仍是「模型繁忙」");
        assertTrue(LlmGuard.describe().startsWith("gate=redis"), LlmGuard.describe());
    }

    // ---------------- ② 用户级滑动窗 ----------------

    @Test
    void slidingWindowIsSharedAcrossNodes() {
        String key = "companion:" + freshUser();
        RedisWindowLimiter nodeA = new RedisWindowLimiter(redis);
        RedisWindowLimiter nodeB = new RedisWindowLimiter(redis);
        for (int i = 0; i < 2; i++) {
            assertTrue(nodeA.tryAcquire(key, 4, Duration.ofSeconds(60)));
            assertTrue(nodeB.tryAcquire(key, 4, Duration.ofSeconds(60)));
        }
        assertFalse(nodeA.tryAcquire(key, 4, Duration.ofSeconds(60)),
                "两节点各发 2 次已用满 4 次配额——窗是共享的，不该各自数各自的");
        assertFalse(nodeB.tryAcquire(key, 4, Duration.ofSeconds(60)));
        assertTrue(nodeB.tryAcquire(key + ":other", 4, Duration.ofSeconds(60)), "别的用户不受影响");
        redis.delete("rl:" + key);
        redis.delete("rl:" + key + ":other");
    }

    // ---------------- ③ 任务事件流 ----------------

    @Test
    void eventsProducedOnOneNodeReplayOnAnother() {
        String taskNo = "T-M13-" + System.nanoTime();
        RedisEventLog nodeA = new RedisEventLog(redis);
        RedisEventLog nodeB = new RedisEventLog(redis);

        long first = nodeA.append(taskNo, "task_created", "{\"a\":1}");
        for (int i = 0; i < 70; i++) {
            nodeA.append(taskNo, "middle_result", "{\"i\":" + i + "}");
        }
        long last = nodeB.append(taskNo, "done", "{\"status\":\"SUCCESS\"}");
        assertTrue(last > first);

        List<TaskEventBus.Event> tail = nodeB.after(taskNo, 0);
        assertEquals(64, tail.size(), "MAXLEN 64：环形语义在 Redis 侧同样成立");
        assertEquals("middle_result", tail.get(0).name(), "最旧的几条应已被环挤出");
        assertEquals("done", tail.get(tail.size() - 1).name());
        assertTrue(nodeB.terminal(taskNo), "done 之后视为终态，任意节点都要认");
        assertFalse(nodeB.terminal(taskNo + "-none"), "没跑过的任务不是终态");
        assertEquals(List.of(last), nodeB.after(taskNo, last - 1).stream()
                .map(TaskEventBus.Event::id).toList());
        redis.delete("orch:seq:" + taskNo);
        redis.delete("orch:events:" + taskNo);
    }

    @Test
    void relayFansEventsOutToSubscribingNode() throws Exception {
        String taskNo = "T-RELAY-" + System.nanoTime();
        RedisEventRelay publisher = new RedisEventRelay(redis, mapper);
        RedisEventRelay subscriber = new RedisEventRelay(redis, mapper);
        var latch = new CountDownLatch(1);
        var gotId = new AtomicLong();
        subscriber.setLocalDeliver((tn, ev) -> {
            assertEquals(taskNo, tn);
            gotId.set(ev.id());
            latch.countDown();
        });
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(cf);
        container.addMessageListener(subscriber, new PatternTopic(RedisEventRelay.CHANNEL_PREFIX + "*"));
        container.afterPropertiesSet();
        container.start();
        try {
            publisher.broadcast(taskNo, new TaskEventBus.Event(42L, "step_started", "{\"agent\":\"EMOTION\"}"));
            assertTrue(latch.await(5, TimeUnit.SECONDS), "另一节点应经 pub/sub 收到事件");
            assertEquals(42L, gotId.get());
        } finally {
            container.stop();
        }
    }

    // ---------------- ④ 导出快照 ----------------

    @Test
    void exportSnapshotCrossNodeClaimNeverLeavesPlaintext() {
        long owner = freshUser();
        long stranger = freshUser();
        String fileId = "F" + System.nanoTime();
        String secret = "深夜写给自己的话-" + System.nanoTime();
        RedisExportStore nodeA = new RedisExportStore(redis, crypto, mapper);
        RedisExportStore nodeB = new RedisExportStore(redis, crypto, mapper);

        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("nickname", "小屿");
        var diaries = mapper.createArrayNode();
        diaries.addObject().put("content", secret);
        snapshot.set("diaries", diaries);        nodeA.put(fileId, owner, snapshot);

        String raw = redis.opsForValue().get("export:snapshot:" + fileId);
        assertNotNull(raw, "快照应已落到共享的 Redis");
        assertFalse(raw.contains(secret), "隐私红线：Redis 里只允许密文，明文从不出进程");
        assertFalse(raw.contains("小屿"));

        assertEquals(ExportStore.Outcome.FOREIGN, nodeB.take(fileId, stranger).outcome(),
                "别人抢先访问只得到 403，不许消耗属主的链接");
        ExportStore.Taken t = nodeB.take(fileId, owner);
        assertEquals(ExportStore.Outcome.CLAIMED, t.outcome(), "A 生成、B 领取：多节点导出成立");
        assertEquals(secret, t.data().path("diaries").get(0).path("content").asText());
        assertEquals(ExportStore.Outcome.MISSING, nodeA.take(fileId, owner).outcome(), "领取即焚");
        assertNull(redis.opsForValue().get("export:snapshot:" + fileId));
    }

    @Test
    void exportSnapshotExpiresOnItsOwn() throws Exception {
        long owner = freshUser();
        String fileId = "F" + System.nanoTime();
        RedisExportStore nodeA = new RedisExportStore(redis, crypto, mapper);
        nodeA.put(fileId, owner, mapper.createObjectNode().put("nickname", "t"));
        Long ttl = redis.getExpire("export:snapshot:" + fileId);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 300, "限时 5 分钟由 Redis TTL 兜底，实测 " + ttl + "s");
        redis.delete("export:snapshot:" + fileId);
    }
}
