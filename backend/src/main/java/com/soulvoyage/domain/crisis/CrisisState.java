package com.soulvoyage.domain.crisis;

/**
 * 危机生命周期状态机（手册 下篇·S1）：
 * NORMAL →(HIGH判定) CRISIS(强干预，日记链路两步) →(冷却期届满) COOLING(链路恢复完整，常驻求助横幅)
 * →(连续2自然周画像达标且无新风险事件) NORMAL；COOLING 期间再次 HIGH → 重新计时；
 * 本轮危机链累计 2 次进入 → 冷却届满后转 REVIEW，强制管理员复核结案。
 */
public enum CrisisState {
    NORMAL,
    CRISIS,
    COOLING,
    REVIEW;

    public static CrisisState parse(String s) {
        if (s == null) return NORMAL;
        try {
            return valueOf(s.toUpperCase());
        } catch (Exception e) {
            return NORMAL;
        }
    }

    /** 是否处于危机期（含冷却/复核）——前端横幅判据 */
    public boolean active() {
        return this != NORMAL;
    }
}
