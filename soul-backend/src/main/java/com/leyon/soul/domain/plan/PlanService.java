package com.leyon.soul.domain.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.soul.agent.support.Exercise;
import com.leyon.soul.agent.support.ExerciseCatalog;
import com.leyon.soul.common.api.ErrorCode;
import com.leyon.soul.common.exception.BizException;
import com.leyon.soul.common.time.BusinessCalendar;
import com.leyon.soul.crypto.CryptoService;
import com.leyon.soul.domain.crisis.CrisisState;
import com.leyon.soul.domain.emotion.EmotionTrajectoryEntity;
import com.leyon.soul.domain.emotion.EmotionTrajectoryRepository;
import com.leyon.soul.domain.notify.NotificationService;
import com.leyon.soul.domain.report.ReportEntity;
import com.leyon.soul.domain.report.ReportRepository;
import com.leyon.soul.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 下篇·G4 成长计划：把 SUPPORT 输出物化成可执行载体（growth_plan + plan_item）。
 * 危机态（CRISIS/COOLING）降复杂度不降供给：只落"grounding 单步计划"（手册 G4）。
 * 到期小结（完成率 + 完成日 vs 未完成日心情对比）用数据证明"练习真的有用"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanService {

    private static final String GROUNDING_EXERCISE = "ex_54321";
    private static final String GROUNDING_GUIDANCE =
            "此刻只做这一件：5-4-3-2-1 感官着陆，把自己稳稳放回当下。";

    /** M-07 推荐理由：单条上限（超长截断，避免把报告正文塞进卡片） */
    private static final int REASON_MAX = 80;

    /** M-07 理由禁用词：任何理由命中即丢弃——理由只解释"为什么匹配"，不做诊断、不承诺疗效 */
    private static final List<String> REASON_BANNED = List.of(
            "诊断", "治疗", "治愈", "疗效", "病因", "抑郁症", "焦虑症", "处方", "药物");

    /**
     * M-07：练习库适用标签 → 可匹配的中文情绪（与 SupportAgent.EMOTION_TO_CATEGORIES 的映射互为逆）。
     * ACUTE_STRESS / INSOMNIA 刻意不映射：那两类是对状态而非对情绪的适配，硬套情绪标签等于过度解释。
     */
    private static final Map<String, List<String>> CATEGORY_EMOTIONS = Map.ofEntries(
            Map.entry("ANXIETY", List.of("焦虑")),
            Map.entry("FEAR", List.of("恐惧")),
            Map.entry("ANGER", List.of("愤怒")),
            Map.entry("HIGH_PRESSURE", List.of("压力", "焦虑")),
            Map.entry("SADNESS", List.of("悲伤", "委屈")),
            Map.entry("LOW_MOOD", List.of("悲伤", "疲惫", "麻木")),
            Map.entry("SHAME", List.of("羞耻", "内疚")),
            Map.entry("GUILT", List.of("内疚", "羞耻")),
            Map.entry("LONELINESS", List.of("孤独")),
            Map.entry("NUMBNESS", List.of("麻木")),
            Map.entry("CONFUSED", List.of("困惑")));

    private final GrowthPlanRepository planRepo;
    private final PlanItemRepository itemRepo;
    private final ExerciseCatalog exercises;
    private final EmotionTrajectoryRepository trajRepo;
    private final ReportRepository reportRepo;
    private final UserRepository userRepo;
    private final CryptoService crypto;
    private final ObjectMapper mapper;
    private final BusinessCalendar cal;
    private final NotificationService notify;

    /** SupportAgent 落报告后调用；把 LLM 计划物化入库并返回 planId（失败由调用方吞掉，不阻断报告） */
    @Transactional
    public String materialize(long userId, Long sourceReportId, JsonNode supportResult) {
        CrisisState crisis = userRepo.findById(userId)
                .map(u -> CrisisState.parse(u.getCrisisState())).orElse(CrisisState.NORMAL);
        boolean groundingOnly = crisis == CrisisState.CRISIS || crisis == CrisisState.COOLING;

        // 同一用户只保留一个进行中计划：旧 ACTION 计划静默收口
        for (GrowthPlanEntity old : planRepo.findByUserIdAndStatusOrderByEndDateAsc(userId, "ACTION")) {
            old.setStatus("DROPPED");
            planRepo.save(old);
        }

        LocalDate start = cal.today();
        GrowthPlanEntity plan = new GrowthPlanEntity();
        plan.setUserId(userId);
        plan.setSourceReportId(sourceReportId);
        plan.setStatus("ACTION");
        plan.setStartDate(start);
        plan.setDays((short) 3);
        plan.setTitle("照顾此刻的小计划");
        plan.setEndDate(start.plusDays(2));
        plan.setItemsJson("[]");

        List<PlanItemEntity> items = new ArrayList<>();
        int days = 3;
        if (!groundingOnly) {
            days = clampDays(supportResult.path("planDays").asInt(0));
            String title = supportResult.path("planTitle").asText("");
            List<ExercisePlanSource> sources = planSources(supportResult, days);
            int seq = 0;
            for (var s : sources) {
                seq++;
                items.add(item(seq, s.exerciseId(), s.guidance(),
                        start.plusDays(Math.min(days, Math.max(1, s.day())) - 1L)));
            }
            if (!items.isEmpty()) {
                if (!title.isBlank()) {
                    plan.setTitle(title.length() > 60 ? title.substring(0, 60) : title);
                }
                plan.setDays((short) days);
                plan.setEndDate(start.plusDays(days - 1L));
                plan.setItemsJson(supportResult.toString());   // 生成时快照；权威逐日数据在 plan_item
            }
        }
        if (items.isEmpty()) {   // 危机态/模型没给可执行计划 → grounding 兜底，计划永不断供
            items.add(item(1, GROUNDING_EXERCISE, GROUNDING_GUIDANCE, start));
        }
        plan = planRepo.save(plan);
        for (PlanItemEntity it : items) {
            it.setPlanId(plan.getId());
            itemRepo.save(it);
        }
        log.info("plan materialized user={} plan={} items={} grounding={}",
                userId, plan.getId(), items.size(), groundingOnly);
        return "pl_" + plan.getId();
    }

    /** 今日计划卡：进行中期 + 逐项完成态；无进行中计划返回 null */
    public Map<String, Object> active(long userId) {
        LocalDate today = cal.today();
        var plan = planRepo.findByUserIdAndStatusOrderByEndDateAsc(userId, "ACTION").stream()
                .filter(p -> !today.isBefore(p.getStartDate()) && !today.isAfter(p.getEndDate()))
                .findFirst().orElse(null);
        return plan == null ? null : view(userId, plan, true, true, recentEmotion(userId, today));
    }

    public List<Map<String, Object>> list(long userId) {
        List<GrowthPlanEntity> plans = planRepo.findByUserIdOrderByCreatedAtDesc(userId);
        if (plans.isEmpty()) return List.of();
        // M-07：同一用户的多个计划复用一次近 7 天查询，避免列表 N+1
        RecentEmotion recent = recentEmotion(userId, cal.today());
        return plans.stream().map(p -> view(userId, p, false, false, recent)).toList();
    }

    public Map<String, Object> detail(long userId, long planId) {
        return view(userId, owned(userId, planId), false, true, recentEmotion(userId, cal.today()));
    }

    /** 用户主动放弃计划：自主权优先，不追问 */
    @Transactional
    public void drop(long userId, long planId) {
        GrowthPlanEntity p = owned(userId, planId);
        if (!"ACTION".equals(p.getStatus())) {
            throw new BizException(ErrorCode.BAD_PARAMS, "这个计划已经结束啦");
        }
        p.setStatus("DROPPED");
        planRepo.save(p);
    }

    /** H-03：应用一次计划自适应（POSTPONE / REDUCE / REPLACE）。 */
    @Transactional
    public Map<String, Object> adapt(long userId, long planId, String action) {
        GrowthPlanEntity p = owned(userId, planId);
        requireActive(p);
        String act = action == null ? "" : action.trim().toUpperCase();
        List<PlanItemEntity> all = itemRepo.findByPlanIdOrderBySeqAsc(planId);
        LocalDate today = cal.today();
        switch (act) {
            case "POSTPONE" -> postpone(all, today);
            case "REDUCE" -> reduce(all, today);
            case "REPLACE" -> replace(all, today);
            default -> throw new BizException(ErrorCode.BAD_PARAMS, "未知调整动作: " + action);
        }
        recalcSpan(p, all);
        return view(userId, p, false, true, recentEmotion(userId, cal.today()));
    }

    /** H-03「今天不做」：今日未完成项顺延到未来最近可用日；幂等。 */
    @Transactional
    public Map<String, Object> skipToday(long userId, long planId) {
        GrowthPlanEntity p = owned(userId, planId);
        requireActive(p);
        List<PlanItemEntity> all = itemRepo.findByPlanIdOrderBySeqAsc(planId);
        LocalDate today = cal.today();
        List<PlanItemEntity> moving = all.stream()
                .filter(it -> it.getDoneAt() == null && today.equals(it.getScheduledDate()))
                .sorted(Comparator.comparing(PlanItemEntity::getSeq))
                .toList();
        LocalDate cursor = today.plusDays(1);
        for (PlanItemEntity it : moving) {
            it.setScheduledDate(nextSlot(all, cursor));
            itemRepo.save(it);
        }
        recalcSpan(p, all);
        return view(userId, p, false, true, recentEmotion(userId, cal.today()));
    }

    /** C4 跟练完成回写：先做属主断言，再给计划条目盖章 */
    @Transactional
    public void markItemDone(long userId, long planId, int seq, String feedback) {
        owned(userId, planId);
        PlanItemEntity it = itemRepo.findByPlanIdAndSeq(planId, seq)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND));
        if (it.getDoneAt() == null) it.setDoneAt(Instant.now());
        if (feedback != null && !feedback.isBlank()) {
            it.setFeedback(feedback.length() > 255 ? feedback.substring(0, 255) : feedback);
        }
        itemRepo.save(it);
    }

    /**
     * 到期小结：endDate 已过且未小结的 ACTION 计划 → 完成率 + 完成日 vs 未完成日 valence 对比，
     * 落 PLAN_SUMMARY 报告（✦ 加密）+ 站内通知。由定时任务调用，测试直调（同 CrisisLifecycle 模式）。
     */
    @Transactional
    public int summarizeDue() {
        List<GrowthPlanEntity> due = planRepo.findByStatusAndEndDateBefore("ACTION", cal.today());
        int n = 0;
        for (GrowthPlanEntity p : due) {
            try {
                summarize(p);
                n++;
            } catch (Exception e) {
                log.warn("plan summary failed plan={}", p.getId(), e);
            }
        }
        return n;
    }

    private void summarize(GrowthPlanEntity p) {
        if (p.getSummaryDone() != null && p.getSummaryDone() == 1) return;
        List<PlanItemEntity> items = itemRepo.findByPlanIdOrderBySeqAsc(p.getId());
        int done = 0;
        var doneDates = new java.util.HashSet<LocalDate>();
        for (PlanItemEntity it : items) {
            if (it.getDoneAt() != null) {
                done++;
                doneDates.add(it.getScheduledDate());
            }
        }
        int total = items.size();

        // 完成日 vs 未完成日的心情均值（取当日代表点：任一轨迹点 valence）
        BigDecimal doneAvg = null, missAvg = null;
        var byDate = new HashMap<LocalDate, List<BigDecimal>>();
        for (EmotionTrajectoryEntity t : trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(
                p.getUserId(), p.getStartDate(), p.getEndDate())) {
            if (t.getValence() != null) {
                byDate.computeIfAbsent(t.getRecordDate(), k -> new ArrayList<>()).add(t.getValence());
            }
        }
        double ds = 0, ms = 0, de = 0, me = 0;
        for (LocalDate d = p.getStartDate(); !d.isAfter(p.getEndDate()); d = d.plusDays(1)) {
            List<BigDecimal> vs = byDate.get(d);
            if (vs == null || vs.isEmpty()) continue;
            double dayAvg = vs.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
            if (doneDates.contains(d)) { ds += dayAvg; de++; } else { ms += dayAvg; me++; }
        }
        if (de > 0) doneAvg = BigDecimal.valueOf(ds / de).setScale(2, RoundingMode.HALF_UP);
        if (me > 0) missAvg = BigDecimal.valueOf(ms / me).setScale(2, RoundingMode.HALF_UP);

        var content = mapper.createObjectNode();
        content.put("planId", p.getId()).put("title", p.getTitle())
                .put("days", p.getDays().intValue())
                .put("total", total).put("done", done)
                .put("completionRate", total == 0 ? 0 : Math.round(done * 100.0 / total))
                .put("doneDayAvgValence", doneAvg)
                .put("missDayAvgValence", missAvg);
        content.put("message", buildSummaryMessage(total, done, doneAvg, missAvg));

        ReportEntity r = new ReportEntity();
        r.setUserId(p.getUserId());
        r.setTaskId(0L);   // 非流水线产物：小结由到期扫描直写，0 = 无来源任务
        r.setType("PLAN_SUMMARY");
        r.setTitle("计划小结 · " + p.getTitle());
        r.setContentEnc(crypto.encryptUserField(p.getUserId(), content.toString()));
        r.setRiskLevel("LOW");
        r = reportRepo.save(r);
        content.put("reportId", "rp_" + r.getId());
        r.setContentEnc(crypto.encryptUserField(p.getUserId(), content.toString()));
        reportRepo.save(r);

        p.setSummaryDone((short) 1);
        p.setStatus(done >= total && total > 0 ? "DONE" : "DROPPED");
        planRepo.save(p);

        notify.push(p.getUserId(), "PLAN_SUMMARY", "planSummary:" + p.getId(), "plan_summary",
                Map.of("title", p.getTitle(), "done", String.valueOf(done),
                        "total", String.valueOf(total)), "/archives");
    }

    private String buildSummaryMessage(int total, int done, BigDecimal doneAvg, BigDecimal missAvg) {
        var sb = new StringBuilder();
        sb.append("这段时间的计划里，你完成了 ").append(done).append('/').append(total).append(" 项练习。");
        if (done > 0 && doneAvg != null && missAvg != null) {
            if (doneAvg.compareTo(missAvg) > 0) {
                sb.append("数据说：做了练习的日子，你的心情均值更高（").append(doneAvg).append(" vs ").append(missAvg).append("）——练习真的有用。");
            } else {
                sb.append("情绪有自己的节奏，练习的意义不一定当天显形；坚持记录本身就是在照顾自己。");
            }
        } else if (done == 0) {
            sb.append("没做完也完全没关系——计划还在，随时可以从今天的那一项重新开始。");
        }
        return sb.toString();
    }

    // ---------------- internals ----------------

    private GrowthPlanEntity owned(long userId, long planId) {
        return planRepo.findById(planId)
                .filter(p -> p.getUserId().equals(userId))
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND));
    }

    private PlanItemEntity item(int seq, String exerciseCode, String guidance, LocalDate date) {
        PlanItemEntity it = new PlanItemEntity();
        it.setSeq(seq);
        it.setExerciseCode(exerciseCode);
        it.setGuidance(guidance == null || guidance.isBlank() ? "跟着步骤做完就好。"
                : guidance.length() > 255 ? guidance.substring(0, 255) : guidance);
        it.setScheduledDate(date);
        return it;
    }

    private static int clampDays(int d) {
        if (d >= 7) return 7;
        if (d >= 5) return 5;
        return 3;
    }

    record ExercisePlanSource(int day, String exerciseId, String guidance) {}

    /** M-07：一次近 7 天查询同时服务"理由"与"自适应建议"，避免同一视图重复读轨迹 */
    private record RecentEmotion(BigDecimal avgValence, String dominantEmotion) {}

    /** 取可执行计划项：优先 planItems（LLM 排期）；缺/全非法时退回 matchedExercises 确定性摊开（每日 ≤2 项） */
    private List<ExercisePlanSource> planSources(JsonNode supportResult, int days) {
        var out = new ArrayList<ExercisePlanSource>();
        for (JsonNode n : supportResult.path("planItems")) {
            String exId = n.path("exerciseId").asText("");
            if (exercises.exists(exId)) {
                out.add(new ExercisePlanSource(n.path("day").asInt(1), exId, n.path("guidance").asText("")));
            }
        }
        if (!out.isEmpty()) return out;
        int di = 0;
        for (JsonNode m : supportResult.path("matchedExercises")) {
            String exId = m.path("exerciseId").asText("");
            if (!exercises.exists(exId)) continue;
            di++;
            out.add(new ExercisePlanSource(1 + (di - 1) / 2, exId, m.path("reason").asText("")));
        }
        return out;
    }

    private void requireActive(GrowthPlanEntity p) {
        if (!"ACTION".equals(p.getStatus()) || cal.today().isAfter(p.getEndDate())) {
            throw new BizException(ErrorCode.BAD_PARAMS, "这个计划已经结束啦");
        }
    }

    /** 逾期未完成项放到今天起最近可用日；单日容量 2，已完成项不动。 */
    private void postpone(List<PlanItemEntity> all, LocalDate today) {
        List<PlanItemEntity> overdue = all.stream()
                .filter(it -> it.getDoneAt() == null && it.getScheduledDate().isBefore(today))
                .sorted(Comparator.comparing(PlanItemEntity::getSeq))
                .toList();
        LocalDate cursor = today;
        for (PlanItemEntity it : overdue) {
            it.setScheduledDate(nextSlot(all, cursor));
            itemRepo.save(it);
        }
    }

    /** 剩余未完成项压到每天最多 1 项；至少保留 1 项。 */
    private void reduce(List<PlanItemEntity> all, LocalDate today) {
        List<PlanItemEntity> remaining = all.stream()
                .filter(it -> it.getDoneAt() == null && !it.getScheduledDate().isBefore(today))
                .sorted(Comparator.comparing(PlanItemEntity::getScheduledDate)
                        .thenComparing(PlanItemEntity::getSeq))
                .toList();
        if (remaining.size() <= 1) return;

        Map<LocalDate, List<PlanItemEntity>> byDate = new LinkedHashMap<>();
        for (PlanItemEntity it : remaining) {
            byDate.computeIfAbsent(it.getScheduledDate(), k -> new ArrayList<>()).add(it);
        }
        List<PlanItemEntity> remove = new ArrayList<>();
        for (List<PlanItemEntity> group : byDate.values()) {
            if (group.size() > 1) remove.addAll(group.subList(1, group.size()));
        }
        if (remove.isEmpty()) remove.add(remaining.get(remaining.size() - 1));
        for (PlanItemEntity it : remove) {
            itemRepo.delete(it);
            all.remove(it);
        }
    }

    /** 把最早未完成项换成练习库中时长最短、且计划内未占用的练习。 */
    private void replace(List<PlanItemEntity> all, LocalDate today) {
        PlanItemEntity target = all.stream()
                .filter(it -> it.getDoneAt() == null && !it.getScheduledDate().isBefore(today))
                .min(Comparator.comparing(PlanItemEntity::getScheduledDate)
                        .thenComparing(PlanItemEntity::getSeq))
                .orElse(null);
        if (target == null) return;

        Set<String> used = all.stream()
                .filter(it -> !it.getId().equals(target.getId()))
                .map(PlanItemEntity::getExerciseCode)
                .collect(Collectors.toSet());
        int currentMin = exercises.exists(target.getExerciseCode())
                ? exercises.require(target.getExerciseCode()).durationMin() : Integer.MAX_VALUE;
        List<Exercise> candidates = exercises.list().stream()
                .filter(e -> !e.id().equals(target.getExerciseCode()))
                .filter(e -> e.durationMin() <= currentMin)
                .sorted(Comparator.comparingInt(Exercise::durationMin).thenComparing(Exercise::id))
                .toList();
        Exercise pick = candidates.stream().filter(e -> !used.contains(e.id())).findFirst()
                .orElse(candidates.isEmpty() ? null : candidates.get(0));
        if (pick == null) return;
        target.setExerciseCode(pick.id());
        target.setGuidance("轻一点的版本：跟着步骤做完就好。");
        itemRepo.save(target);
    }

    /** 从 from 起找单日条目数 <2 的最近可用日。 */
    private LocalDate nextSlot(List<PlanItemEntity> all, LocalDate from) {
        LocalDate d = from;
        while (countOn(all, d) >= 2) {
            d = d.plusDays(1);
        }
        return d;
    }

    private long countOn(List<PlanItemEntity> all, LocalDate date) {
        return all.stream().filter(it -> date.equals(it.getScheduledDate())).count();
    }

    /** 计划跨度跟随实际排期：endDate 取最大排期日，days 同步。 */
    private void recalcSpan(GrowthPlanEntity p, List<PlanItemEntity> all) {
        LocalDate end = all.stream().map(PlanItemEntity::getScheduledDate)
                .max(Comparator.naturalOrder()).orElse(p.getEndDate());
        LocalDate today = cal.today();
        if ("ACTION".equals(p.getStatus()) && end.isBefore(today)) end = today;
        if (end.isBefore(p.getStartDate())) end = p.getStartDate();
        p.setEndDate(end);
        long days = ChronoUnit.DAYS.between(p.getStartDate(), end) + 1;
        p.setDays((short) Math.min(Math.max(days, 1), 365));
        planRepo.save(p);
    }

    /** 确定性建议：逾期 → 顺延；低完成率 → 减量；低情绪 → 替换。危机活跃态不主动建议。 */
    private Map<String, Object> adaptation(long userId, GrowthPlanEntity p,
                                           List<PlanItemEntity> all, LocalDate today,
                                           RecentEmotion recent) {
        if (!"ACTION".equals(p.getStatus()) || today.isAfter(p.getEndDate())) return null;
        boolean crisis = userRepo.findById(userId)
                .map(u -> CrisisState.parse(u.getCrisisState()).active())
                .orElse(false);
        if (crisis) return null;

        List<PlanItemEntity> undone = all.stream().filter(it -> it.getDoneAt() == null).toList();
        if (undone.isEmpty()) return null;

        long overdue = undone.stream().filter(it -> it.getScheduledDate().isBefore(today)).count();
        if (overdue > 0) {
            return adaptMap("POSTPONE", "把落下的练习顺延一下",
                    "有 " + overdue + " 项练习落在了过去，顺延到今天起，不会记为失败。",
                    "顺延到今天");
        }

        int dueTotal = (int) all.stream()
                .filter(it -> !it.getScheduledDate().isAfter(today)).count();
        int dueDone = (int) all.stream()
                .filter(it -> !it.getScheduledDate().isAfter(today) && it.getDoneAt() != null).count();
        int rate = dueTotal == 0 ? 0 : (int) Math.round(dueDone * 100.0 / dueTotal);
        long elapsed = ChronoUnit.DAYS.between(p.getStartDate(), today) + 1;
        if (undone.size() > 1 && rate < 40 && elapsed >= 2) {
            return adaptMap("REDUCE", "把计划减轻一点",
                    "最近到期完成率 " + rate + "%，可以把剩余练习压到每天最多一项。",
                    "减到每天一项");
        }

        BigDecimal avg = recent.avgValence();
        if (avg != null && avg.compareTo(new BigDecimal("-0.30")) <= 0) {
            return adaptMap("REPLACE", "换一个更轻的练习",
                    "近 7 天情绪均值偏低（" + avg.setScale(2, RoundingMode.HALF_UP).toPlainString()
                            + "），下一项可以换成更短的练习。",
                    "换轻一点");
        }
        return null;
    }

    private Map<String, Object> adaptMap(String type, String title, String reason, String actionLabel) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", type);
        m.put("title", title);
        m.put("reason", reason);
        m.put("actionLabel", actionLabel);
        return m;
    }

    /**
     * M-07：近 7 天轨迹只读一次，同时得到平均效价（自适应建议）与主导情绪（推荐理由）。
     * 主导情绪取出现次数最多者；并列时取最近一条记录的情绪，保证同输入同输出。
     */
    private RecentEmotion recentEmotion(long userId, LocalDate today) {
        List<EmotionTrajectoryEntity> rows = trajRepo
                .findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(
                        userId, today.minusDays(6), today);
        if (rows.isEmpty()) return new RecentEmotion(null, null);

        BigDecimal avg = null;
        long days = rows.stream().map(EmotionTrajectoryEntity::getRecordDate).distinct().count();
        if (days >= 2) {
            double v = rows.stream().filter(t -> t.getValence() != null)
                    .mapToDouble(t -> t.getValence().doubleValue()).average().orElse(Double.NaN);
            if (!Double.isNaN(v)) avg = BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP);
        }

        Map<String, Long> counts = new LinkedHashMap<>();
        for (EmotionTrajectoryEntity t : rows) {
            String emotion = t.getPrimaryEmotion();
            if (emotion != null && !emotion.isBlank()) counts.merge(emotion, 1L, Long::sum);
        }
        String latest = null;
        for (int i = rows.size() - 1; i >= 0; i--) {
            String emotion = rows.get(i).getPrimaryEmotion();
            if (emotion != null && !emotion.isBlank()) {
                latest = emotion;
                break;
            }
        }
        String dominant = counts.entrySet().stream()
                .max(Comparator.comparingLong(Map.Entry<String, Long>::getValue))
                .map(Map.Entry::getKey).orElse(null);
        if (latest != null && dominant != null
                && counts.get(latest).longValue() == counts.get(dominant).longValue()) {
            dominant = latest;
        }
        return new RecentEmotion(avg, dominant);
    }

    /** itemsJson 是生成时快照；解析失败按无快照处理，理由走确定性兜底。 */
    private JsonNode snapshot(GrowthPlanEntity p) {
        String json = p.getItemsJson();
        if (json == null || json.isBlank()) return mapper.createObjectNode();
        try {
            JsonNode n = mapper.readTree(json);
            return n == null ? mapper.createObjectNode() : n;
        } catch (Exception e) {
            log.debug("plan snapshot unreadable plan={}", p.getId());
            return mapper.createObjectNode();
        }
    }

    /**
     * M-07 读取时派生理由：优先用生成时快照里的推荐理由，其次按近期主导情绪与练习适用标签匹配，
     * 最后按 grounding / 普通练习兜底。恒返回 1–2 条，不调用 LLM、不做诊断。
     */
    private List<String> reasonsFor(PlanItemEntity it, JsonNode snapshot, Exercise ex,
                                    RecentEmotion recent) {
        var out = new LinkedHashSet<String>();
        String matched = matchedReason(snapshot, it.getExerciseCode());
        if (matched != null) out.add(matched);
        if (out.size() < 2) {
            String emotion = matchedEmotion(ex, recent.dominantEmotion());
            if (emotion != null) out.add("和最近常出现的「" + emotion + "」感受相关");
        }
        if (out.isEmpty()) {
            out.add(GROUNDING_EXERCISE.equals(it.getExerciseCode())
                    ? "此刻先做这一件，把注意力放回身体感受"
                    : "轻量自助练习，按自己的节奏做完就好");
        }
        return List.copyOf(out);
    }

    private String matchedReason(JsonNode snapshot, String exerciseCode) {
        for (JsonNode m : snapshot.path("matchedExercises")) {
            if (!exerciseCode.equals(m.path("exerciseId").asText(""))) continue;
            String reason = m.path("reason").asText("").replaceAll("\\s+", " ").trim();
            if (reason.isEmpty() || REASON_BANNED.stream().anyMatch(reason::contains)) continue;
            return reason.length() > REASON_MAX ? reason.substring(0, REASON_MAX) : reason;
        }
        return null;
    }

    private String matchedEmotion(Exercise ex, String dominant) {
        if (ex == null || dominant == null || dominant.isBlank()) return null;
        for (String category : ex.applyEmotions()) {
            if (CATEGORY_EMOTIONS.getOrDefault(category, List.of()).contains(dominant)) {
                return dominant;
            }
        }
        return null;
    }

    /** todayOnly：仅展开今日条目，但完成度按全计划统计；withAdaptation 仅 active/detail 计算建议 */
    private Map<String, Object> view(long userId, GrowthPlanEntity p, boolean todayOnly,
                                     boolean withAdaptation, RecentEmotion recent) {
        LocalDate today = cal.today();
        List<PlanItemEntity> all = itemRepo.findByPlanIdOrderBySeqAsc(p.getId());
        JsonNode snapshot = snapshot(p);
        int done = (int) all.stream().filter(x -> x.getDoneAt() != null).count();
        int dueTotal = (int) all.stream()
                .filter(x -> !x.getScheduledDate().isAfter(today)).count();
        int dueDone = (int) all.stream()
                .filter(x -> !x.getScheduledDate().isAfter(today) && x.getDoneAt() != null).count();
        int completionRate = dueTotal == 0 ? 0 : (int) Math.round(dueDone * 100.0 / dueTotal);
        int overdueCount = (int) all.stream()
                .filter(x -> x.getDoneAt() == null && x.getScheduledDate().isBefore(today)).count();
        int remainingCount = (int) all.stream().filter(x -> x.getDoneAt() == null).count();
        LocalDate next = all.stream().filter(x -> x.getDoneAt() == null)
                .map(PlanItemEntity::getScheduledDate).min(Comparator.naturalOrder()).orElse(null);
        boolean todaySkipped = "ACTION".equals(p.getStatus())
                && all.stream().noneMatch(x -> today.equals(x.getScheduledDate()))
                && remainingCount > 0;

        List<PlanItemEntity> shown = todayOnly
                ? all.stream().filter(x -> today.equals(x.getScheduledDate())).toList() : all;
        var list = new ArrayList<Map<String, Object>>();
        for (PlanItemEntity it : shown) {
            Map<String, Object> m = new HashMap<>();
            m.put("seq", it.getSeq());
            m.put("exerciseId", it.getExerciseCode());
            m.put("guidance", it.getGuidance());
            m.put("scheduledDate", it.getScheduledDate().toString());
            m.put("doneAt", it.getDoneAt() == null ? null : it.getDoneAt().toString());
            m.put("feedback", it.getFeedback());
            Exercise ex = exercises.exists(it.getExerciseCode())
                    ? exercises.require(it.getExerciseCode()) : null;
            m.put("exerciseName", ex == null ? it.getExerciseCode() : ex.name());
            m.put("durationMin", ex == null ? 0 : ex.durationMin());
            m.put("reasons", reasonsFor(it, snapshot, ex, recent));
            list.add(m);
        }
        Map<String, Object> out = new HashMap<>();
        out.put("planId", "pl_" + p.getId());
        out.put("title", p.getTitle());
        out.put("days", p.getDays().intValue());
        out.put("status", p.getStatus());
        out.put("startDate", p.getStartDate().toString());
        out.put("endDate", p.getEndDate().toString());
        out.put("daysLeft", today.isAfter(p.getEndDate()) ? 0
                : (int) (p.getEndDate().toEpochDay() - today.toEpochDay() + 1));
        out.put("doneCount", done);
        out.put("totalCount", all.size());
        out.put("items", list);
        out.put("adaptation", withAdaptation ? adaptation(userId, p, all, today, recent) : null);
        out.put("completionRate", completionRate);
        out.put("overdueCount", overdueCount);
        out.put("remainingCount", remainingCount);
        out.put("nextScheduledDate", next == null ? null : next.toString());
        out.put("todaySkipped", todaySkipped);
        return out;
    }
}
