package com.soulvoyage.domain.achievement.dto;

import java.util.List;

/** 成就墙读接口的响应契约。 */
public class AchievementDtos {

    public record BadgeView(String code, String name, String desc, boolean unlocked, String unlockedAt) {}

    public record WallResp(List<BadgeView> items, long unlockedCount, int totalCount) {}
}
