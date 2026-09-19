package com.soulvoyage.agent.risk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 风险规则引擎（手册 §4.5 双轨之一：规则轨，拥有"一票升级"权）。
 * 与 NPC 导演共用同一份危机词表（{@link com.soulvoyage.agent.simulate.NpcDirector} 引用此处），
 * 保证训练剧情外真实危机与日记危机走同一套 100% 召回的确定性判定——不依赖 LLM 拍板。
 *
 * 分级：命中危机句式 → HIGH（立即转介）；命中持续低落/自我否定密集 → MEDIUM（升级转介展示位）。
 * 设计原则：宁可误报，不可漏报。证据只记"命中了哪条规则/定位"，不落用户原文（evidence_ref 非原文）。
 */
public final class RiskRules {

    /** 高危句式回归集：必须 100% 检出（手册 §九 测试策略，CI 卡点样例） */
    public static final List<String> CRISIS_PHRASES = List.of(
            "不想活", "活着没意思", "自杀", "活不下去", "撑不下去", "割腕", "自残",
            "结束生命", "一了百了", "消失掉算了", "跳下去");

    /** 「活着(真/好/太/没什么/没啥)…没意思」插入语气词变体——此类句式不容漏报，用正则兜底 */
    private static final Pattern CRISIS_ALIVE_HOLLOW =
            Pattern.compile("活着.{0,3}没(什么|啥)?意思");

    /** 持续低落（≥2 周表述）——命中记 LOW_MOOD_PERSISTENT，判 MEDIUM */
    static final List<String> PERSISTENT_LOW_PHRASES = List.of(
            "两周", "半个月", "一个多月", "一直很低落", "总是情绪低", "好久没开心",
            "提不起劲", "高兴不起来", "开心不起来", "高兴不起来", "每天都很绝望", "长期失眠", "整夜睡不着");

    /** 自我否定密集——命中记 SELF_DENIGRATING，判 MEDIUM */
    static final List<String> SELF_DENIGRATING_PHRASES = List.of(
            "我是废物", "我是个废物", "一无是处", "都是我的错", "我很失败", "我没用",
            "没人在乎我", "没有人爱我", "我是负担", "拖累别人", "拖累大家", "讨厌自己", "恶心自己");

    public enum Level { LOW, MEDIUM, HIGH }

    /** 一条规则命中：level=最终建议级别；triggerType 区分规则轨来源 */
    public record RuleHit(Level level, String triggerType, String ruleCode, String locator) {}

    private RiskRules() {}

    public static boolean isCrisis(String text) {
        String t = normalize(text);
        return CRISIS_PHRASES.stream().anyMatch(t::contains)
                || CRISIS_ALIVE_HOLLOW.matcher(t).find();
    }

    /**
     * 扫描一段用户文本，返回命中的规则（可能多条）。调用方按"取最高级别 + 一票升级"裁决。
     * 空文本返回空列表（无信号 ≠ 风险）。
     */
    public static List<RuleHit> scan(String text) {
        List<RuleHit> hits = new ArrayList<>();
        String t = normalize(text);
        if (t.isBlank()) return hits;

        int crisisIdx = firstHit(t, CRISIS_PHRASES);
        if (crisisIdx < 0) {
            var m = CRISIS_ALIVE_HOLLOW.matcher(t);
            if (m.find()) crisisIdx = m.start();
        }
        if (crisisIdx >= 0) {
            hits.add(new RuleHit(Level.HIGH, "KEYWORD_RULE", "RISK_CRISIS", "offset=" + crisisIdx));
            return hits; // 危机一票封顶，无需再扫中危
        }
        int lowIdx = firstHit(t, PERSISTENT_LOW_PHRASES);
        if (lowIdx >= 0) {
            hits.add(new RuleHit(Level.MEDIUM, "KEYWORD_RULE", "LOW_MOOD_PERSISTENT", "offset=" + lowIdx));
        }
        int denIdx = firstHit(t, SELF_DENIGRATING_PHRASES);
        if (denIdx >= 0) {
            hits.add(new RuleHit(Level.MEDIUM, "KEYWORD_RULE", "SELF_DENIGRATING", "offset=" + denIdx));
        }
        return hits;
    }

    /** 取若干命中里的最高级别；空命中视为 LOW */
    public static Level maxLevel(List<RuleHit> hits) {
        Level lv = Level.LOW;
        for (RuleHit h : hits) {
            if (h.level() == Level.HIGH) return Level.HIGH;
            if (h.level() == Level.MEDIUM) lv = Level.MEDIUM;
        }
        return lv;
    }

    public static Level higher(Level a, Level b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    private static int firstHit(String t, List<String> phrases) {
        for (String p : phrases) {
            int i = t.indexOf(p);
            if (i >= 0) return i;
        }
        return -1;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }
}
