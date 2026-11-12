package com.soulvoyage.domain.emotion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.common.time.BusinessCalendar;
import com.soulvoyage.domain.content.ContentStore;
import com.soulvoyage.domain.crisis.CrisisState;
import com.soulvoyage.domain.emotion.dto.EmotionDtos.DistortionInsight;
import com.soulvoyage.domain.emotion.dto.EmotionDtos.InsightResp;
import com.soulvoyage.domain.emotion.dto.EmotionDtos.NextAction;
import com.soulvoyage.domain.emotion.dto.EmotionDtos.StressorInsight;
import com.soulvoyage.domain.emotion.dto.EmotionDtos.Volatility;
import com.soulvoyage.domain.emotion.dto.EmotionDtos.WeeklyDigest;
import com.soulvoyage.domain.profile.EmotionProfileEntity;
import com.soulvoyage.domain.profile.EmotionProfileRepository;
import com.soulvoyage.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * H-02 情绪洞察：确定性规则引擎（只读）。
 * 输入仅来自用户自己的周画像/轨迹与内容知识库；输出描述性文案与自助动作，不做诊断。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmotionInsightService {

    private static final int MAX_WEEKS = 12;
    private static final int VOLATILITY_DAYS = 14;
    private static final double STEADY_SD = 0.15;
    private static final double WAVY_SD = 0.30;
    private static final BigDecimal DELTA_EPS = new BigDecimal("0.05");

    private final EmotionProfileRepository profileRepo;
    private final EmotionTrajectoryRepository trajRepo;
    private final UserRepository userRepo;
    private final ContentStore content;
    private final BusinessCalendar cal;
    private final ObjectMapper mapper;

    public InsightResp insight(long userId, int weeksParam) {
        int weeks = Math.min(Math.max(weeksParam, 1), MAX_WEEKS);
        LocalDate today = cal.today();
        List<EmotionProfileEntity> profiles = profileRepo.findByUserIdOrderByStatWeekDesc(
                userId, PageRequest.of(0, weeks));

        LocalDate oldestWeekStart = profiles.stream()
                .map(p -> weekStart(p.getStatWeek()))
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(today.minusDays(weeks * 7L - 1));
        LocalDate rangeStart = oldestWeekStart.isBefore(today.minusDays(weeks * 7L - 1))
                ? oldestWeekStart : today.minusDays(weeks * 7L - 1);
        List<EmotionTrajectoryEntity> rows = trajRepo
                .findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(userId, rangeStart, today);

        Map<LocalDate, List<EmotionTrajectoryEntity>> byDate = rows.stream()
                .collect(Collectors.groupingBy(EmotionTrajectoryEntity::getRecordDate));
        Map<String, List<EmotionTrajectoryEntity>> byWeek = rows.stream()
                .collect(Collectors.groupingBy(t -> statWeek(t.getRecordDate())));

        List<WeeklyDigest> weekly = buildWeekly(profiles, byWeek, today, weeks);
        Volatility volatility = buildVolatility(byDate, today);
        List<StressorInsight> stressors = buildStressors(profiles);
        List<DistortionInsight> distortions = buildDistortions(profiles);
        boolean crisisMode = userRepo.findById(userId)
                .map(u -> CrisisState.parse(u.getCrisisState()).active())
                .orElse(false);
        List<NextAction> actions = buildActions(crisisMode, distortions, stressors, volatility, byDate, today);

        return new InsightResp(today.toString(), weeks, crisisMode, weekly, volatility,
                stressors, distortions, actions);
    }

    // ---------------- 周报摘要 ----------------

    private record WeekData(String statWeek, BigDecimal avgValence, JsonNode stressors,
                            JsonNode distortions, int count, boolean hasProfile) {}

    private List<WeeklyDigest> buildWeekly(List<EmotionProfileEntity> profiles,
                                           Map<String, List<EmotionTrajectoryEntity>> byWeek,
                                           LocalDate today, int weeks) {
        LocalDate currentWeekStart = today.with(WeekFields.ISO.dayOfWeek(), 1);
        LocalDate earliest = currentWeekStart.minusWeeks(weeks - 1L);
        Map<String, WeekData> data = new java.util.TreeMap<>();

        for (EmotionProfileEntity p : profiles) {
            LocalDate ws = weekStart(p.getStatWeek());
            if (ws == null || ws.isBefore(earliest)) continue;
            data.put(p.getStatWeek(), new WeekData(
                    p.getStatWeek(),
                    p.getAvgValence(),
                    parse(p.getStressorTopJson()),
                    parse(p.getDistortionTopJson()),
                    byWeek.getOrDefault(p.getStatWeek(), List.of()).size(),
                    true));
        }
        // 只有打卡/日记轨迹、尚未生成周画像的周，也用轨迹合成一条摘要
        for (Map.Entry<String, List<EmotionTrajectoryEntity>> e : byWeek.entrySet()) {
            LocalDate ws = weekStart(e.getKey());
            if (ws == null || ws.isBefore(earliest) || data.containsKey(e.getKey())) continue;
            double avg = e.getValue().stream()
                    .mapToDouble(t -> t.getValence().doubleValue()).average().orElse(0);
            data.put(e.getKey(), new WeekData(
                    e.getKey(),
                    BigDecimal.valueOf(avg).setScale(3, RoundingMode.HALF_UP),
                    mapper.createArrayNode(),
                    mapper.createArrayNode(),
                    e.getValue().size(),
                    false));
        }

        List<WeekData> asc = new ArrayList<>(data.values());
        asc.sort(Comparator.comparing(WeekData::statWeek));
        List<WeeklyDigest> out = new ArrayList<>();
        for (int i = 0; i < asc.size(); i++) {
            WeekData p = asc.get(i);
            WeekData prev = i > 0 ? asc.get(i - 1) : null;
            List<String> lines = new ArrayList<>();

            lines.add(p.count() == 0
                    ? "这一周没有新的情绪记录。"
                    : "这一周留下 " + p.count() + " 条情绪记录。");

            BigDecimal avg = p.avgValence();
            if (avg != null) {
                BigDecimal prevAvg = prev == null ? null : prev.avgValence();
                LocalDate curStart = weekStart(p.statWeek());
                LocalDate prevStart = prev == null ? null : weekStart(prev.statWeek());
                boolean adjacent = curStart != null && prevStart != null
                        && prevStart.plusWeeks(1).equals(curStart);
                if (prevAvg == null || !adjacent) {
                    lines.add("平均效价 " + fmt(avg) + "。");
                } else {
                    BigDecimal delta = avg.subtract(prevAvg);
                    if (delta.abs().compareTo(DELTA_EPS) < 0) {
                        lines.add("平均效价 " + fmt(avg) + "，与前一周接近。");
                    } else if (delta.signum() > 0) {
                        lines.add("平均效价 " + fmt(avg) + "，比前一周高 " + fmt(delta) + "。");
                    } else {
                        lines.add("平均效价 " + fmt(avg) + "，比前一周低 " + fmt(delta.abs()) + "。");
                    }
                }
            }

            String topStressor = firstName(p.stressors());
            if (topStressor != null) {
                lines.add("最常出现的压力源是「" + topStressor + "」（" + firstCount(p.stressors()) + " 次）。");
            }
            String topDistortion = firstName(p.distortions());
            if (topDistortion != null) {
                lines.add("思维误区线索中「" + topDistortion + "」出现 " + firstCount(p.distortions()) + " 次。");
            }
            if (topStressor == null && topDistortion == null) {
                lines.add(p.hasProfile()
                        ? "这一周没有识别到压力源或思维误区线索，继续按自己的节奏记录即可。"
                        : "这一周还没有溯源报告，压力源与误区线索会在完成一次日记梳理后出现。");
            }
            out.add(new WeeklyDigest(p.statWeek(), headline(avg), List.copyOf(lines)));
        }
        Collections.reverse(out);
        return List.copyOf(out);
    }

    private String headline(BigDecimal avg) {
        if (avg == null) return "还在积累记录的一周";
        if (avg.compareTo(new BigDecimal("0.15")) >= 0) return "偏明亮的一周";
        if (avg.compareTo(new BigDecimal("-0.15")) <= 0) return "有些低沉的一周";
        return "相对平稳的一周";
    }

    // ---------------- 波动提示 ----------------

    private Volatility buildVolatility(Map<LocalDate, List<EmotionTrajectoryEntity>> byDate, LocalDate today) {
        LocalDate from = today.minusDays(VOLATILITY_DAYS - 1L);
        List<Map.Entry<LocalDate, Double>> daily = byDate.entrySet().stream()
                .filter(e -> !e.getKey().isBefore(from) && !e.getKey().isAfter(today))
                .sorted(Map.Entry.comparingByKey())
                .map(e -> Map.entry(e.getKey(), e.getValue().stream()
                        .mapToDouble(t -> t.getValence().doubleValue()).average().orElse(0)))
                .toList();

        int n = daily.size();
        if (n < 5) {
            return new Volatility("INSUFFICIENT", "记录不足",
                    "近 14 天只有 " + n + " 天记录，样本还不足以判断波动。再记录几天，这里会更稳。",
                    n, null, null, null, null);
        }

        double mean = daily.stream().mapToDouble(Map.Entry::getValue).average().orElse(0);
        double variance = daily.stream()
                .mapToDouble(e -> Math.pow(e.getValue() - mean, 2)).sum() / n;
        double sd = Math.sqrt(variance);
        double min = daily.stream().mapToDouble(Map.Entry::getValue).min().orElse(0);
        double max = daily.stream().mapToDouble(Map.Entry::getValue).max().orElse(0);

        BigDecimal maxDelta = null;
        for (int i = 1; i < n; i++) {
            long gap = ChronoUnit.DAYS.between(daily.get(i - 1).getKey(), daily.get(i).getKey());
            if (gap > 3) continue;
            BigDecimal d = BigDecimal.valueOf(Math.abs(daily.get(i).getValue() - daily.get(i - 1).getValue()));
            if (maxDelta == null || d.compareTo(maxDelta) > 0) maxDelta = d;
        }

        String level;
        String label;
        String hint;
        if (sd < STEADY_SD) {
            level = "STEADY";
            label = "平稳";
            hint = "近 14 天的情绪整体平稳，效价大多落在 " + fmt(bd(min)) + " 到 " + fmt(bd(max))
                    + " 之间。稳定本身也是一种资源。";
        } else if (sd < WAVY_SD) {
            level = "WAVY";
            label = "有起伏";
            hint = "近 14 天的情绪有起有落（效价标准差 " + fmt(bd(sd)) + maxDeltaText(maxDelta)
                    + "）。起伏不等于退步，它常常只是生活在发生。";
        } else {
            level = "VOLATILE";
            label = "起伏较大";
            hint = "近 14 天的情绪起伏较明显（效价标准差 " + fmt(bd(sd)) + maxDeltaText(maxDelta)
                    + "）。如果这种波动让你感到疲惫，可以先固定睡眠与记录节奏；持续难受时，可以联系学校心理中心或拨打 12356。";
        }
        return new Volatility(level, label, hint, n, bd(sd), bd(min), bd(max),
                maxDelta == null ? null : bd(maxDelta.doubleValue()));
    }

    private String maxDeltaText(BigDecimal maxDelta) {
        return maxDelta == null ? "" : "，最大单日变化 " + fmt(maxDelta);
    }

    // ---------------- 压力源 / 误区解释 ----------------

    private List<StressorInsight> buildStressors(List<EmotionProfileEntity> profiles) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (EmotionProfileEntity p : profiles) {
            for (JsonNode s : parse(p.getStressorTopJson())) {
                String name = s.path("name").asText("");
                if (!name.isBlank()) counts.merge(name, s.path("count").asInt(0), Integer::sum);
            }
        }
        Map<String, ContentStore.StressorEntry> defs = content.stressorDefinitions();
        return counts.entrySet().stream()
                .sorted((a, b) -> {
                    int c = Integer.compare(b.getValue(), a.getValue());
                    return c != 0 ? c : a.getKey().compareTo(b.getKey());
                })
                .limit(3)
                .map(e -> {
                    ContentStore.StressorEntry d = defs.get(e.getKey());
                    String what = d != null && !d.what().isBlank()
                            ? d.what() : "这是最近反复出现的压力来源。";
                    String tip = d != null && !d.tip().isBlank()
                            ? d.tip() : "挑一个最小的可控动作，今天先推进一点。";
                    return new StressorInsight(e.getKey(), e.getValue(), what, tip);
                })
                .toList();
    }

    private List<DistortionInsight> buildDistortions(List<EmotionProfileEntity> profiles) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (EmotionProfileEntity p : profiles) {
            for (JsonNode d : parse(p.getDistortionTopJson())) {
                String name = d.path("name").asText("");
                if (!name.isBlank()) counts.merge(name, d.path("count").asInt(0), Integer::sum);
            }
        }
        Map<String, ContentStore.DistortionEntry> byName = content.distortions().stream()
                .collect(Collectors.toMap(ContentStore.DistortionEntry::name, d -> d, (a, b) -> a));
        return counts.entrySet().stream()
                .sorted((a, b) -> {
                    int c = Integer.compare(b.getValue(), a.getValue());
                    return c != 0 ? c : a.getKey().compareTo(b.getKey());
                })
                .limit(3)
                .map(e -> {
                    ContentStore.DistortionEntry d = byName.get(e.getKey());
                    return new DistortionInsight(
                            d == null ? "" : d.kgNodeId(),
                            e.getKey(),
                            e.getValue(),
                            d == null ? "" : d.definition(),
                            d == null ? "" : d.typicalSignature(),
                            d == null || d.socraticTemplates().isEmpty() ? "" : d.socraticTemplates().get(0));
                })
                .toList();
    }

    // ---------------- 下一步 ----------------

    private List<NextAction> buildActions(boolean crisisMode, List<DistortionInsight> distortions,
                                          List<StressorInsight> stressors, Volatility volatility,
                                          Map<LocalDate, List<EmotionTrajectoryEntity>> byDate,
                                          LocalDate today) {
        if (crisisMode) {
            return List.of(new NextAction("CARE_RESOURCES", "先看支持资源",
                    "此刻不必完成任何练习，先照顾好自己。", "/crisis"));
        }
        List<NextAction> out = new ArrayList<>();
        if (!distortions.isEmpty()) {
            DistortionInsight d = distortions.get(0);
            out.add(new NextAction("CBT_WRITE", "把一条思维误区线索写下来",
                    "用认知书写三栏表，把「" + d.name() + "」的自动想法写下来，再写一个更平衡的版本。",
                    "/practice"));
        }
        if (!stressors.isEmpty()) {
            StressorInsight s = stressors.get(0);
            out.add(new NextAction("STRESSOR_STEP", "把压力缩成今天的一小步",
                    "从「" + s.name() + "」里挑一件 10 分钟内能推进的事。", "/today"));
        }
        if (("WAVY".equals(volatility.level()) || "VOLATILE".equals(volatility.level())) && out.size() < 3) {
            out.add(new NextAction("BREATH_ANCHOR", "给情绪一个稳定的锚点",
                    "试试 4-7-8 呼吸，或固定一个睡前记录时间。", "/practice"));
        }
        int recent = (int) byDate.keySet().stream()
                .filter(d -> !d.isBefore(today.minusDays(6)))
                .count();
        if (recent == 0 && out.size() < 3) {
            out.add(new NextAction("DIARY", "写一篇情绪日记",
                    "不用写得好，只写发生了什么、你当时在想什么。", "/diaries/write"));
        }
        if (out.isEmpty()) {
            out.add(new NextAction("SELF_RATING", "给今天的情绪起个名字",
                    "一次打卡就够，曲线会慢慢有材料。", "/today"));
        }
        return List.copyOf(out);
    }

    // ---------------- 工具 ----------------

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) return mapper.createArrayNode();
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createArrayNode();
        }
    }

    private String firstName(JsonNode arr) {
        for (JsonNode n : arr) {
            String name = n.path("name").asText("");
            if (!name.isBlank()) return name;
        }
        return null;
    }

    private int firstCount(JsonNode arr) {
        for (JsonNode n : arr) {
            if (!n.path("name").asText("").isBlank()) return n.path("count").asInt(0);
        }
        return 0;
    }

    private String statWeek(LocalDate date) {
        WeekFields wf = WeekFields.ISO;
        return date.get(wf.weekBasedYear()) + "-W" + String.format("%02d", date.get(wf.weekOfWeekBasedYear()));
    }

    private LocalDate weekStart(String statWeek) {
        if (statWeek == null || statWeek.length() < 8 || !statWeek.contains("-W")) return null;
        try {
            int year = Integer.parseInt(statWeek.substring(0, 4));
            int week = Integer.parseInt(statWeek.substring(6));
            WeekFields wf = WeekFields.ISO;
            return LocalDate.of(year, 1, 4)
                    .with(wf.weekOfWeekBasedYear(), week)
                    .with(wf.dayOfWeek(), 1);
        } catch (Exception e) {
            return null;
        }
    }

    private String fmt(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP);
    }
}
