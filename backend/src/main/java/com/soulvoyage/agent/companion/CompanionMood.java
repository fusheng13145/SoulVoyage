package com.soulvoyage.agent.companion;

import java.util.List;

/**
 * 情绪响应策略（下篇·C0）：替代 NpcDirector 的张力状态机——没有"剧情冲突"要经营，
 * 只有"跟随用户效价"：负面时降能量匹配（先接住再陪伴），稳定后温和拉升，禁止强行正能量。
 * 纯规则、确定性、可回归（与危机词表同层，不经 LLM 拍板）。
 */
public final class CompanionMood {

    public enum Strategy { FOLLOW, LOW_ENERGY, WARM_UP }

    private static final String[] NEGATIVE_HINTS = {
            "烦", "气", "累", "难过", "伤心", "哭", "焦虑", "压力", "紧张", "害怕", "怕",
            "孤单", "一个人", "没人", "委屈", "崩", "撑不住", "睡不着", "失眠", "烦死"
    };
    private static final String[] POSITIVE_HINTS = {
            "开心", "高兴", "不错", "顺利", "好消息", "谢谢", "感谢", "喜欢", "期待",
            "终于", "搞定", "通过了", "太好了", "哈哈"
    };

    private CompanionMood() {}

    /** 效价探测：-1 负面 / 0 中性 / +1 偏正 */
    public static int probe(String text) {
        if (text == null) return 0;
        for (String k : NEGATIVE_HINTS) if (text.contains(k)) return -1;
        for (String k : POSITIVE_HINTS) if (text.contains(k)) return +1;
        return 0;
    }

    /**
     * 当轮策略：负面即接住（LOW_ENERGY）；此前低落且本轮转暖 → 温和拉升（WARM_UP）；其余跟随（FOLLOW）。
     * recentTags 取最近几轮的 mood_tag（会话无独立导演快照列，策略状态就沉淀在逐轮记录里）。
     */
    public static Strategy decide(String userText, List<String> recentTags) {
        int now = probe(userText);
        if (now < 0) return Strategy.LOW_ENERGY;
        boolean wasLow = !recentTags.isEmpty()
                && recentTags.stream().skip(Math.max(0, recentTags.size() - 3L)).anyMatch("LOW_ENERGY"::equals);
        if (wasLow && now > 0) return Strategy.WARM_UP;
        return Strategy.FOLLOW;
    }

    public static String directive(Strategy s) {
        return switch (s) {
            case LOW_ENERGY -> "TA 现在情绪偏负面：先接住感受（复读关键词、不追问原因），回复能量放低、更短更轻，不给建议不打气。";
            case WARM_UP -> "TA 刚才低过、现在有点回暖：可以温和地跟着高兴一下，轻轻问一句发生了什么变化，不要突然亢奋。";
            case FOLLOW -> "正常跟随：保持好奇，顺着 TA 的话题聊，可以用一个开放式小问题延续话题。";
        };
    }
}
