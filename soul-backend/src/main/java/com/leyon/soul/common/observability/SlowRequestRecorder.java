package com.leyon.soul.common.observability;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * H-05 慢请求记录：仅保留超过阈值的 /api/v1 请求（不含 query/body/身份），
 * 内存环形缓冲上限 500 条，重启清零；对外只提供只读 TopN。
 */
@Component
public class SlowRequestRecorder {

    private static final int MAX_ENTRIES = 500;
    private final ConcurrentLinkedDeque<Entry> entries = new ConcurrentLinkedDeque<>();

    public record Entry(String method, String path, int status, long durationMs, Instant at) {}

    public void record(String method, String path, int status, long durationMs, long thresholdMs) {
        if (durationMs < thresholdMs) return;
        entries.addFirst(new Entry(method, path, status, durationMs, Instant.now()));
        while (entries.size() > MAX_ENTRIES) entries.pollLast();
    }

    public List<Entry> top(int n, Instant since) {
        return entries.stream()
                .filter(e -> !e.at().isBefore(since))
                .sorted(Comparator.comparingLong(Entry::durationMs).reversed())
                .limit(Math.max(0, n))
                .toList();
    }

    public int size() {
        return entries.size();
    }
}
