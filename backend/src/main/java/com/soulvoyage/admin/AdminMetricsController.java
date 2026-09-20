package com.soulvoyage.admin;

import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.common.time.BusinessCalendar;
import com.soulvoyage.domain.risk.RiskEventRepository;
import com.soulvoyage.domain.task.TaskInstanceRepository;
import com.soulvoyage.domain.task.TaskStepLogRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * O3 观测与成本看板（手册 A1/成本视图）：DB 聚合为事实源（可回放、跨重启），
 * registry 现值为补充（进程内实时计数）。全部时间按业务时区切日。
 */
@RestController
@RequestMapping("/api/v1/admin/metrics")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') and @perms.has('admin:task')")
public class AdminMetricsController {

    private final TaskInstanceRepository taskRepo;
    private final TaskStepLogRepository stepRepo;
    private final RiskEventRepository riskRepo;
    private final MeterRegistry registry;
    private final BusinessCalendar cal;

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview(@RequestParam(defaultValue = "7") int days) {
        int span = Math.min(Math.max(days, 1), 90);
        ZoneId zone = cal.zone();
        Instant windowFrom = LocalDate.now(zone).minusDays(span - 1L).atStartOfDay(zone).toInstant();
        Instant todayFrom = LocalDate.now(zone).atStartOfDay(zone).toInstant();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("windowDays", span);
        resp.put("tasks", Map.of(
                "today", taskStats(taskRepo.countByStatusSince(todayFrom)),
                "window", taskStats(taskRepo.countByStatusSince(windowFrom))));
        resp.put("riskEvents", riskStats(riskRepo.countByLevelSince(windowFrom)));

        List<Object[]> rows = stepRepo.costRowsSince(windowFrom);
        resp.put("agents", agentStats(rows));
        resp.put("tokensByDay", tokensByDay(rows, zone));
        resp.put("runtime", runtimeSnapshot());
        return ApiResponse.ok(resp);
    }

    // ---------------- DB 聚合 ----------------

    private Map<String, Object> taskStats(List<Object[]> statusCounts) {
        Map<String, Long> byStatus = new TreeMap<>();
        for (Object[] r : statusCounts) byStatus.put((String) r[0], ((Number) r[1]).longValue());
        long finished = byStatus.getOrDefault("SUCCESS", 0L)
                + byStatus.getOrDefault("PARTIAL_SUCCESS", 0L)
                + byStatus.getOrDefault("FAILED", 0L);
        long success = byStatus.getOrDefault("SUCCESS", 0L)
                + byStatus.getOrDefault("PARTIAL_SUCCESS", 0L);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("byStatus", byStatus);
        m.put("finished", finished);
        m.put("successRate", finished == 0 ? null : Math.round(success * 10000.0 / finished) / 100.0);
        return m;
    }

    private Map<String, Long> riskStats(List<Object[]> levelCounts) {
        Map<String, Long> m = new TreeMap<>();
        for (Object[] r : levelCounts) m.put((String) r[0], ((Number) r[1]).longValue());
        return m;
    }

    /** 按 Agent 分组：步骤数、成功数、costMs avg/p95/max、LLM 调用与 token 合计 */
    private List<Map<String, Object>> agentStats(List<Object[]> rows) {
        Map<String, List<Object[]>> byAgent = new TreeMap<>();
        for (Object[] r : rows) byAgent.computeIfAbsent((String) r[0], k -> new ArrayList<>()).add(r);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : byAgent.entrySet()) {
            List<Integer> costs = new ArrayList<>();
            long llmCalls = 0, tokensIn = 0, tokensOut = 0;
            for (Object[] r : e.getValue()) {
                if (r[1] != null) costs.add((Integer) r[1]);
                llmCalls += num(r[2]);
                tokensIn += num(r[3]);
                tokensOut += num(r[4]);
            }
            costs.sort(Comparator.naturalOrder());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("agent", e.getKey());
            m.put("steps", e.getValue().size());
            m.put("avgMs", costs.isEmpty() ? null : Math.round(costs.stream().mapToInt(Integer::intValue).average().orElse(0)));
            m.put("p95Ms", costs.isEmpty() ? null : costs.get(Math.max(0, (int) Math.ceil(costs.size() * 0.95) - 1)));
            m.put("maxMs", costs.isEmpty() ? null : costs.get(costs.size() - 1));
            m.put("llmCalls", llmCalls);
            m.put("tokensIn", tokensIn);
            m.put("tokensOut", tokensOut);
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> tokensByDay(List<Object[]> rows, ZoneId zone) {
        Map<LocalDate, long[]> byDay = new TreeMap<>();
        for (Object[] r : rows) {
            if (num(r[2]) == 0) continue;   // 无 LLM 调用的步骤不计成本
            LocalDate day = ((Instant) r[5]).atZone(zone).toLocalDate();
            long[] acc = byDay.computeIfAbsent(day, k -> new long[2]);
            acc[0] += num(r[3]);
            acc[1] += num(r[4]);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        byDay.forEach((d, acc) -> out.add(Map.of("date", d.toString(), "tokensIn", acc[0], "tokensOut", acc[1])));
        return out;
    }

    // ---------------- registry 现值（进程内，重启清零） ----------------

    private Map<String, Object> runtimeSnapshot() {
        return Map.of(
                "taskSubmitted", sumCounters("sv.task.submit"),
                "llmCalls", (long) registry.find("sv.llm.call").timers().stream()
                        .mapToDouble(io.micrometer.core.instrument.Timer::count).sum(),
                "tokensIn", sumCountersTagged("sv.llm.tokens", "in"),
                "tokensOut", sumCountersTagged("sv.llm.tokens", "out"));
    }

    private long sumCounters(String name) {
        return (long) registry.find(name).counters().stream().mapToDouble(Counter::count).sum();
    }

    private long sumCountersTagged(String name, String dir) {
        return (long) registry.find(name).tag("dir", dir).counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }
}
