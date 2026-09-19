package com.soulvoyage.agent.simulate;

import com.soulvoyage.agent.risk.RiskRules;

import java.util.List;
import java.util.Locale;

/**
 * NPC 导演模块（手册 §4.3）：规则层情绪状态机，维护张力值驱动 NPC 反应档位——
 * 中立 NEUTRAL → 不满 DISSATISFIED → 激化 ESCALATED / 缓和 SOFTENED。
 * 确定性、可测试：LLM 只负责人设化措辞，"NPC 多生气"由本模块裁决（也防模型自行升级冲突）。
 * 兜底：检出剧情外真实危机信号 → crisis=true，由服务层跳出剧情走温和退出。
 */
public final class NpcDirector {

    public static final int MAX_TURNS_HARD_LIMIT = 20;

    /** 高危句式回归集：与风险规则引擎同源（{@link RiskRules#CRISIS_PHRASES}），必须 100% 检出 */
    static final List<String> CRISIS_PHRASES = RiskRules.CRISIS_PHRASES;

    private static final List<String> ESCALATE_TOKENS = List.of(
            "你总是", "你每次", "你从来", "你就是", "你这人", "烦不烦", "闭嘴",
            "自私", "没素质", "垃圾", "蠢", "笨", "少来这套", "少废话", "凭什么你说了算",
            "我受够", "受够了", "懒得理你", "行行行", "都是我的错");

    private static final List<String> SOOTHE_TOKENS = List.of(
            "我注意到", "我感到", "我感觉", "我的感受", "我希望", "我们可以", "要不",
            "谢谢你", "我理解", "抱歉", "辛苦了", "我知道你", "一起想", "你的安排");

    /** 我-信息句式（非暴力沟通：观察+感受+请求），命中记 BOUNDARY_SET 关键事件 */
    private static final List<String> I_MESSAGE_TOKENS = List.of(
            "我注意到", "我感到", "我感觉", "我的感受", "我希望", "我可以", "我想");
    private static final List<String> REQUEST_TOKENS = List.of(
            "要不", "我们可以", "一起", "好不好", "行吗", "约定", "方案");

    public enum Mood { NEUTRAL, DISSATISFIED, ESCALATED, SOFTENED }

    public record Decision(Mood mood, int tension, String stateTag, boolean crisis, int intensity) {}

    private NpcDirector() {}

    /**
     * 单轮裁决：张力 = clamp(上一张力 + 难度加权(指控词×12 − 软化词×10))，档位按阈值切换。
     * MILD 激化慢缓和快；HARD 反之——同一段用户输入在不同难度下走出不同剧情。
     */
    public static Decision react(String difficulty, String userText, int prevTension) {
        String t = userText == null ? "" : userText.toLowerCase(Locale.ROOT);

        boolean crisis = RiskRules.isCrisis(userText);
        if (crisis) return new Decision(Mood.SOFTENED, Math.max(0, Math.min(prevTension, 30)),
                "CRISIS_BREAK", true, 1);

        double escWeight, sootheWeight;
        switch (difficulty) {
            case "MILD" -> { escWeight = 0.6; sootheWeight = 1.4; }
            case "HARD" -> { escWeight = 1.5; sootheWeight = 0.7; }
            default -> { escWeight = 1.0; sootheWeight = 1.0; }
        }

        long esc = ESCALATE_TOKENS.stream().filter(t::contains).count();
        long soo = SOOTHE_TOKENS.stream().filter(t::contains).count();
        int delta = (int) Math.round(esc * 12 * escWeight - soo * 10 * sootheWeight);
        // 无任何信号的自然口语缓慢升温，避免"永远谈不下去/谈不冷"两头极端
        if (delta == 0) delta = 2;
        int tension = Math.max(0, Math.min(100, prevTension + delta));

        Mood mood;
        if (tension >= 70) mood = Mood.ESCALATED;
        else if (tension >= 40) mood = Mood.DISSATISFIED;
        else if (tension <= 15) mood = Mood.SOFTENED;
        else mood = Mood.NEUTRAL;

        String tag = null;
        boolean iMessage = I_MESSAGE_TOKENS.stream().anyMatch(t::contains)
                && REQUEST_TOKENS.stream().anyMatch(t::contains) && esc == 0;
        if (iMessage) tag = "BOUNDARY_SET";
        else if (mood == Mood.ESCALATED && delta > 0) tag = "CONFLICT_UP";
        else if (mood == Mood.SOFTENED) tag = "DE_ESCALATION";
        else if (t.contains("谢谢") || t.contains("抱歉")) tag = "ACKNOWLEDGED";

        int intensity = switch (mood) {
            case ESCALATED -> 4;
            case DISSATISFIED -> 3;
            case NEUTRAL -> 2;
            case SOFTENED -> 1;
        };
        return new Decision(mood, tension, tag, false, intensity);
    }
}
