package com.leyon.soul.domain.export;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 进程内导出快照暂存（默认）：内存 TTL + 领取即焚，重启最坏丢一条链接；
 *  distributed=true 时由 RedisStateConfig 的 @Primary 实现接管 */
@Component
public class InMemoryExportStore implements ExportStore {

    private static final Duration TTL = Duration.ofMinutes(5);

    private record Snapshot(long userId, ObjectNode data, Instant expiresAt) {}

    private final Map<String, Snapshot> exports = new ConcurrentHashMap<>();

    @Override
    public void put(String fileId, long userId, ObjectNode data) {
        exports.put(fileId, new Snapshot(userId, data, Instant.now().plus(TTL)));
    }

    @Override
    public Taken take(String fileId, long requesterId) {
        // 一次 compute 内完成"校验 + 取走"：原实现 get 后再 remove，并发两路请求会双双 CLAIMED（一次性失效）
        int[] outcome = {0};      // 0=未命中 1=已领取 2=越权
        Snapshot[] hit = {null};
        exports.compute(fileId, (k, s) -> {
            if (s == null || s.expiresAt().isBefore(Instant.now())) {
                outcome[0] = 0;
                return null;                       // 顺手清掉过期项
            }
            if (s.userId() != requesterId) {
                outcome[0] = 2;
                return s;                          // 越权不消耗：链接仍归属主
            }
            outcome[0] = 1;
            hit[0] = s;
            return null;                           // 属主领取即焚
        });
        return switch (outcome[0]) {
            case 1 -> new Taken(Outcome.CLAIMED, hit[0].data());
            case 2 -> Taken.FOREIGN;
            default -> Taken.MISSING;
        };
    }

    /** 过期自毁：随下一次导出顺带清扫，无后台线程 */
    public void purgeExpired() {
        Instant now = Instant.now();
        exports.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }
}
