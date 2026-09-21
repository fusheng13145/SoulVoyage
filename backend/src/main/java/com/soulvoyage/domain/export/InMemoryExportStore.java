package com.soulvoyage.domain.export;

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
        Snapshot s = exports.get(fileId);
        if (s == null || s.expiresAt().isBefore(Instant.now())) {
            exports.remove(fileId);
            return Taken.MISSING;
        }
        if (s.userId() != requesterId) return Taken.FOREIGN;   // 越权不消耗：链接仍归属主
        exports.remove(fileId);                                // 一次性：属主领取即焚
        return new Taken(Outcome.CLAIMED, s.data());
    }

    /** 过期自毁：随下一次导出顺带清扫，无后台线程 */
    public void purgeExpired() {
        Instant now = Instant.now();
        exports.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }
}
