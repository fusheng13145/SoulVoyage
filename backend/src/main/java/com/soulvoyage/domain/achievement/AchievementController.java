package com.soulvoyage.domain.achievement;

import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 下篇·G3 成就墙端点：目录 + 解锁状态（无排行榜，只和自己比） */
@RestController
@RequestMapping("/api/v1/achievements")
@RequiredArgsConstructor
public class AchievementController {

    private final AchievementService achievements;

    @GetMapping
    public ApiResponse<Map<String, Object>> wall(@AuthenticationPrincipal AuthPrincipal p) {
        List<Map<String, Object>> list = achievements.wall(p.userId());
        long unlocked = list.stream().filter(m -> Boolean.TRUE.equals(m.get("unlocked"))).count();
        return ApiResponse.ok(Map.of("items", list, "unlockedCount", unlocked,
                "totalCount", list.size()));
    }
}
