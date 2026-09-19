package com.soulvoyage.agent.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 风险规则引擎（手册 §4.5 双轨之一：规则轨，拥有"一票升级"权）。
 * 词表外置于 classpath {@code risk/rules.json}（下篇·S1：从 Java 硬编码迁出，支持热加载 {@link #reload()}），
 * 与 NPC 导演共用同一份危机词表，保证训练剧情外真实危机与日记危机走同一套确定性判定——不依赖 LLM 拍板。
 *
 * 分级：命中危机句式 → HIGH（立即转介）；命中持续低落/自我否定密集 → MEDIUM。
 * 否定/引文防护（S1）：危机命中点前 window 字符内含否定/引述词（"不/别/朋友说…"）→ 降为 MEDIUM
 * 并标记 needsReview（一票升级权保留给未降级命中；降级命中进复核队列而非丢弃——宁可多查，不可漏查）。
 * 设计原则：宁可误报，不可漏报。证据只记"命中了哪条规则/定位"，不落用户原文。
 */
public final class RiskRules {

    public enum Level { LOW, MEDIUM, HIGH }

    /** 一条规则命中；needsReview=否定/引文降级或建议人工复核 */
    public record RuleHit(Level level, String triggerType, String ruleCode, String locator,
                          boolean needsReview) {}

    /** 规则集（rules.json 反序列化形态） */
    record Rules(List<String> crisisPhrases, List<Pattern> crisisPatterns,
                 List<String> persistentLowPhrases, List<String> selfDenigratingPhrases,
                 int negationWindow, List<String> negationTokens) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RULES_RESOURCE = "/risk/rules.json";

    private static volatile Rules rules = load();

    private RiskRules() {}

    /** 危机词表（100% 召回回归集消费此列表；CI 卡点样例） */
    public static List<String> crisisPhrases() {
        return rules.crisisPhrases();
    }

    /** 规则热加载（A3/N4 内容管理端点接入前，先提供编程入口） */
    public static synchronized void reload() {
        rules = load();
    }

    public static boolean isCrisis(String text) {
        for (RuleHit h : scan(text)) {
            if (h.level() == Level.HIGH) return true;
        }
        return false;
    }

    /**
     * 扫描一段用户文本，返回命中的规则（可能多条）。调用方按"取最高级别 + 一票升级"裁决。
     * 空文本返回空列表（无信号 ≠ 风险）。
     */
    public static List<RuleHit> scan(String text) {
        List<RuleHit> hits = new ArrayList<>();
        String t = normalize(text);
        if (t.isBlank()) return hits;
        Rules r = rules;

        int crisisIdx = firstHit(t, r.crisisPhrases());
        if (crisisIdx < 0) {
            for (Pattern p : r.crisisPatterns()) {
                var m = p.matcher(t);
                if (m.find()) { crisisIdx = m.start(); break; }
            }
        }
        if (crisisIdx >= 0) {
            if (negated(t, crisisIdx, r)) {
                // 否定/引述语境：降 MEDIUM + 进复核队列，不丢弃信号
                hits.add(new RuleHit(Level.MEDIUM, "KEYWORD_RULE_NEGATED", "RISK_CRISIS",
                        "offset=" + crisisIdx, true));
            } else {
                hits.add(new RuleHit(Level.HIGH, "KEYWORD_RULE", "RISK_CRISIS",
                        "offset=" + crisisIdx, false));
                return hits; // 危机一票封顶，无需再扫中危
            }
        }
        int lowIdx = firstHit(t, r.persistentLowPhrases());
        if (lowIdx >= 0) {
            hits.add(new RuleHit(Level.MEDIUM, "KEYWORD_RULE", "LOW_MOOD_PERSISTENT",
                    "offset=" + lowIdx, false));
        }
        int denIdx = firstHit(t, r.selfDenigratingPhrases());
        if (denIdx >= 0) {
            hits.add(new RuleHit(Level.MEDIUM, "KEYWORD_RULE", "SELF_DENIGRATING",
                    "offset=" + denIdx, false));
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

    // ---------------- 内部 ----------------

    private static boolean negated(String t, int hitIndex, Rules r) {
        if (hitIndex <= 0 || r.negationTokens().isEmpty()) return false;
        int from = Math.max(0, hitIndex - Math.max(1, r.negationWindow()));
        String window = t.substring(from, hitIndex);
        for (String tok : r.negationTokens()) {
            int i = window.lastIndexOf(tok);
            if (i < 0) continue;
            // 小句切分：否定词与命中点之间隔标点即不算否定语境——否则"什么都没有了，活着没意思"会被漏报
            String between = window.substring(i + tok.length());
            if (containsPunctuation(between)) continue;
            return true;
        }
        return false;
    }

    private static boolean containsPunctuation(String s) {
        for (int i = 0; i < s.length(); i++) {
            if ("，。！？；：、,.!?;: \n\t".indexOf(s.charAt(i)) >= 0) return true;
        }
        return false;
    }

    private static int firstHit(String t, List<String> phrases) {
        int best = -1;
        for (String p : phrases) {
            int i = t.indexOf(p);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    private static Rules load() {
        try (InputStream in = RiskRules.class.getResourceAsStream(RULES_RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing classpath resource " + RULES_RESOURCE);
            JsonNode j = MAPPER.readTree(in);
            var pats = new ArrayList<Pattern>();
            for (JsonNode p : j.path("crisisPatterns")) pats.add(Pattern.compile(p.asText()));
            return new Rules(
                    toStringList(j.path("crisisPhrases")),
                    List.copyOf(pats),
                    toStringList(j.path("persistentLowPhrases")),
                    toStringList(j.path("selfDenigratingPhrases")),
                    j.path("negation").path("window").asInt(6),
                    toStringList(j.path("negation").path("tokens")));
        } catch (Exception e) {
            throw new IllegalStateException("risk rules.json load failed (fail fast, 不静默降级)", e);
        }
    }

    private static List<String> toStringList(JsonNode arr) {
        var out = new ArrayList<String>();
        for (JsonNode n : arr) {
            String s = n.asText("");
            if (!s.isBlank()) out.add(s);
        }
        return List.copyOf(out);
    }
}
