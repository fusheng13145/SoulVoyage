package com.leyon.soul.admin;

import com.leyon.soul.admin.dto.AdminDtos.AgentStat;
import com.leyon.soul.admin.dto.AdminDtos.CostBudget;
import com.leyon.soul.admin.dto.AdminDtos.FailedTaskView;
import com.leyon.soul.admin.dto.AdminDtos.FailureAlert;
import com.leyon.soul.admin.dto.AdminDtos.GuardrailsResp;
import com.leyon.soul.admin.dto.AdminDtos.LlmRejects;
import com.leyon.soul.admin.dto.AdminDtos.MetricsOverviewResp;
import com.leyon.soul.admin.dto.AdminDtos.QualityItem;
import com.leyon.soul.admin.dto.AdminDtos.QualitySample;
import com.leyon.soul.admin.dto.AdminDtos.RuntimeView;
import com.leyon.soul.admin.dto.AdminDtos.SlowRequestView;
import com.leyon.soul.admin.dto.AdminDtos.TaskOverview;
import com.leyon.soul.admin.dto.AdminDtos.TaskStats;
import com.leyon.soul.admin.dto.AdminDtos.TokensByDay;
import com.leyon.soul.common.observability.SlowRequestRecorder;
import com.leyon.soul.common.time.BusinessCalendar;
import com.leyon.soul.domain.report.ReportEntity;
import com.leyon.soul.domain.report.ReportRepository;
import com.leyon.soul.domain.risk.RiskEventRepository;
import com.leyon.soul.domain.task.TaskInstanceEntity;
import com.leyon.soul.domain.task.TaskInstanceRepository;
import com.leyon.soul.domain.task.TaskStepLogRepository;
import com.leyon.soul.kg.Neo4jKgService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * O3/H-05 观测聚合（只读）：DB 为事实源，registry 为进程内补充。
 * 控制器只做路由；本服务不写业务表、不解密报告正文、不引入外部依赖。
 */
@Service
public class AdminMetricsService {

    private final TaskInstanceRepository taskRepo;
    private final TaskStepLogRepository stepRepo;
    private final RiskEventRepository riskRepo;
    private final ReportRepository reportRepo;
    private final MeterRegistry registry;
    private final BusinessCalendar cal;
    private final ObjectProvider<Neo4jKgService> neo4jKg;
    private final SlowRequestRecorder slowRequests;
    private final long dailyTokenBudget;
    private final double priceInPer1k;
    private final double priceOutPer1k;
    private final double dailyCostBudget;
    private final String currency;
    private final int alertPercent;

    public AdminMetricsService(TaskInstanceRepository taskRepo,
                               TaskStepLogRepository stepRepo,
                               RiskEventRepository riskRepo,
                               ReportRepository reportRepo,
                               MeterRegistry registry,
                               BusinessCalendar cal,
                               ObjectProvider<Neo4jKgService> neo4jKg,
                               SlowRequestRecorder slowRequests,
                               @Value("${soul.llm.budget.daily-tokens:2000000}") long dailyTokenBudget,
                               @Value("${soul.llm.budget.price-in-per-1k:0.001}") double priceInPer1k,
                               @Value("${soul.llm.budget.price-out-per-1k:0.002}") double priceOutPer1k,
                               @Value("${soul.llm.budget.daily-cost:10.0}") double dailyCostBudget,
                               @Value("${soul.llm.budget.currency:CNY}") String currency,
                               @Value("${soul.llm.budget.alert-percent:80}") int alertPercent) {
        this.taskRepo = taskRepo;
        this.stepRepo = stepRepo;
        this.riskRepo = riskRepo;
        this.reportRepo = reportRepo;
        this.registry = registry;
        this.cal = cal;
        this.neo4jKg = neo4jKg;
        this.slowRequests = slowRequests;
        this.dailyTokenBudget = dailyTokenBudget;
        this.priceInPer1k = priceInPer1k;
        this.priceOutPer1k = priceOutPer1k;
        this.dailyCostBudget = dailyCostBudget;
        this.currency = currency;
        this.alertPercent = alertPercent;
    }

    public MetricsOverviewResp overview(int days) {
        int span = Math.min(Math.max(days, 1), 90);
        ZoneId zone = cal.zone();
        Instant windowFrom = LocalDate.now(zone).minusDays(span - 1L).atStartOfDay(zone).toInstant();
        Instant todayFrom = LocalDate.now(zone).atStartOfDay(zone).toInstant();

        List<Object[]> rows = stepRepo.costRowsSince(windowFrom);
        return new MetricsOverviewResp(
                span,
                new TaskOverview(taskStats(taskRepo.countByStatusSince(todayFrom)),
                        taskStats(taskRepo.countByStatusSince(windowFrom))),
                riskStats(riskRepo.countByLevelSince(windowFrom)),
                agentStats(rows),
                tokensByDay(rows, zone),
                feedbackStats(reportRepo.countFeedbackSince(windowFrom)),
                runtimeSnapshot());
    }

    public GuardrailsResp guardrails(int topN) {
        Instant now = Instant.now();
        Instant dayAgo = now.minus(24, ChronoUnit.HOURS);
        return new GuardrailsResp(
                now.toString(),
                failureAlert(dayAgo),
                costBudget(dayAgo),
                qualitySample(now.minus(7, ChronoUnit.DAYS)),
                slowRequests.top(Math.min(Math.max(topN, 1), 50), dayAgo).stream()
                        .map(e -> new SlowRequestView(e.method(), e.path(), e.status(),
                                e.durationMs(), e.at().toString()))
                        .toList());
    }

    private FailureAlert failureAlert(Instant from) {
        Map<String, Long> byStatus = new TreeMap<>();
        for (Object[] r : taskRepo.countByStatusSince(from)) {
            byStatus.put((String) r[0], ((Number) r[1]).longValue());
        }
        long failed = byStatus.getOrDefault("FAILED", 0L);
        long finished = byStatus.getOrDefault("SUCCESS", 0L)
                + byStatus.getOrDefault("PARTIAL_SUCCESS", 0L) + failed;
        Double rate = finished == 0 ? null : round2(failed * 100.0 / finished);
        String level = rate != null && rate >= 20 && failed >= 3 ? "CRITICAL"
                : failed >= 1 || (rate != null && rate >= 10) ? "WARN" : "OK";
        String message = switch (level) {
            case "CRITICAL" -> "24h 内失败 " + failed + " 个，失败率 " + rate + "%，建议立即排查。";
            case "WARN" -> "24h 内有失败任务（" + failed + " 个），请关注失败原因。";
            default -> "24h 内任务整体稳定。";
        };
        List<FailedTaskView> recent = taskRepo
                .findFailedSince(from, PageRequest.of(0, 5)).stream()
                .map(this::failedTask)
                .toList();
        return new FailureAlert(level, message, failed, finished, rate, recent);
    }

    private FailedTaskView failedTask(TaskInstanceEntity t) {
        return new FailedTaskView(t.getTaskNo(), t.getPipelineCode(), t.getErrorMsg(),
                t.getCreatedAt() == null ? null : t.getCreatedAt().toString());
    }

    private CostBudget costBudget(Instant from) {
        long tokensIn = 0, tokensOut = 0;
        for (Object[] r : stepRepo.costRowsSince(from)) {
            tokensIn += num(r[3]);
            tokensOut += num(r[4]);
        }
        long total = tokensIn + tokensOut;
        BigDecimal cost = BigDecimal.valueOf(tokensIn / 1000.0 * priceInPer1k
                        + tokensOut / 1000.0 * priceOutPer1k)
                .setScale(4, RoundingMode.HALF_UP);
        double tokenPct = dailyTokenBudget <= 0 ? 0 : total * 100.0 / dailyTokenBudget;
        double costPct = dailyCostBudget <= 0 ? 0 : cost.doubleValue() * 100.0 / dailyCostBudget;
        double used = round1(Math.max(tokenPct, costPct));
        String level = used >= 100 ? "OVER" : used >= alertPercent ? "WARN" : "OK";
        String message = switch (level) {
            case "OVER" -> "24h 用量已超出预算，请检查高消耗 Agent 与异常调用。";
            case "WARN" -> "24h 用量已达预算 " + used + "%，建议提前关注。";
            default -> "24h Token 与成本均在预算内。";
        };
        return new CostBudget(24, tokensIn, tokensOut, total, dailyTokenBudget,
                cost, dailyCostBudget, currency, used, level, message);
    }

    private QualitySample qualitySample(Instant from) {
        List<ReportEntity> reports = reportRepo
                .findByDeletedAtIsNullAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                        from, PageRequest.of(0, 20))
                .getContent();
        int useful = 0, unsure = 0, unhelpful = 0, rated = 0;
        for (ReportEntity r : reports) {
            String fb = r.getFeedback();
            if (fb == null) continue;
            rated++;
            if ("USEFUL".equals(fb)) useful++;
            else if ("UNSURE".equals(fb)) unsure++;
            else if ("UNHELPFUL".equals(fb)) unhelpful++;
        }
        Double unhelpfulRate = rated == 0 ? null : round1(unhelpful * 100.0 / rated);
        String level = rated < 5 ? "INSUFFICIENT"
                : unhelpfulRate != null && unhelpfulRate >= 30 ? "WARN" : "OK";
        String message = switch (level) {
            case "INSUFFICIENT" -> "已评样本不足 5 份，暂不判定内容质量。";
            case "WARN" -> "差评率 " + unhelpfulRate + "%，建议抽样复核相关报告。";
            default -> "差评率 " + unhelpfulRate + "%，质量整体稳定。";
        };
        List<QualityItem> items = reports.stream()
                .map(this::qualityItem)
                .sorted(Comparator.comparing(QualityItem::needsReview).reversed()
                        .thenComparing(QualityItem::createdAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(10)
                .toList();
        return new QualitySample(reports.size(), rated, useful, unsure, unhelpful,
                unhelpfulRate, level, message, items);
    }

    private QualityItem qualityItem(ReportEntity r) {
        return new QualityItem(r.getId(), r.getType(), r.getTitle(), r.getFeedback(),
                r.getRiskLevel(), r.getCreatedAt() == null ? null : r.getCreatedAt().toString(),
                "UNHELPFUL".equals(r.getFeedback()));
    }

    // ---------------- overview helpers ----------------

    private Map<String, Long> feedbackStats(List<Object[]> rows) {
        Map<String, Long> m = new TreeMap<>();
        for (Object[] r : rows) m.put((String) r[0], ((Number) r[1]).longValue());
        return m;
    }

    private TaskStats taskStats(List<Object[]> statusCounts) {
        Map<String, Long> byStatus = new TreeMap<>();
        for (Object[] r : statusCounts) byStatus.put((String) r[0], ((Number) r[1]).longValue());
        long finished = byStatus.getOrDefault("SUCCESS", 0L)
                + byStatus.getOrDefault("PARTIAL_SUCCESS", 0L)
                + byStatus.getOrDefault("FAILED", 0L);
        long success = byStatus.getOrDefault("SUCCESS", 0L)
                + byStatus.getOrDefault("PARTIAL_SUCCESS", 0L);
        return new TaskStats(byStatus, finished,
                finished == 0 ? null : Math.round(success * 10000.0 / finished) / 100.0);
    }

    private Map<String, Long> riskStats(List<Object[]> levelCounts) {
        Map<String, Long> m = new TreeMap<>();
        for (Object[] r : levelCounts) m.put((String) r[0], ((Number) r[1]).longValue());
        return m;
    }

    private List<AgentStat> agentStats(List<Object[]> rows) {
        Map<String, List<Object[]>> byAgent = new TreeMap<>();
        for (Object[] r : rows) byAgent.computeIfAbsent((String) r[0], k -> new ArrayList<>()).add(r);
        List<AgentStat> out = new ArrayList<>();
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
            out.add(new AgentStat(
                    e.getKey(), e.getValue().size(),
                    costs.isEmpty() ? null : Math.round(costs.stream().mapToInt(Integer::intValue).average().orElse(0)),
                    costs.isEmpty() ? null : (long) costs.get(Math.max(0, (int) Math.ceil(costs.size() * 0.95) - 1)),
                    costs.isEmpty() ? null : (long) costs.get(costs.size() - 1),
                    llmCalls, tokensIn, tokensOut));
        }
        return out;
    }

    private List<TokensByDay> tokensByDay(List<Object[]> rows, ZoneId zone) {
        Map<LocalDate, long[]> byDay = new TreeMap<>();
        for (Object[] r : rows) {
            if (num(r[2]) == 0) continue;
            LocalDate day = ((Instant) r[5]).atZone(zone).toLocalDate();
            long[] acc = byDay.computeIfAbsent(day, k -> new long[2]);
            acc[0] += num(r[3]);
            acc[1] += num(r[4]);
        }
        List<TokensByDay> out = new ArrayList<>();
        byDay.forEach((d, acc) -> out.add(new TokensByDay(d.toString(), acc[0], acc[1])));
        return out;
    }

    private RuntimeView runtimeSnapshot() {
        Neo4jKgService graph = neo4jKg.getIfAvailable();
        return new RuntimeView(
                com.leyon.soul.llm.LlmGuard.describe(),
                graph == null ? "kg=db(kg_node)" : graph.describe(),
                sumCounters("sv.task.submit"),
                (long) registry.find("sv.llm.call").timers().stream()
                        .mapToDouble(io.micrometer.core.instrument.Timer::count).sum(),
                sumCountersTagged("sv.llm.tokens", "dir", "in"),
                sumCountersTagged("sv.llm.tokens", "dir", "out"),
                new LlmRejects(
                        sumCountersTagged("sv.llm.reject", "reason", "rate_limited"),
                        sumCountersTagged("sv.llm.reject", "reason", "queue_timeout")));
    }

    private long sumCounters(String name) {
        return (long) registry.find(name).counters().stream().mapToDouble(Counter::count).sum();
    }

    private long sumCountersTagged(String name, String tag, String value) {
        return (long) registry.find(name).tag(tag, value).counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
