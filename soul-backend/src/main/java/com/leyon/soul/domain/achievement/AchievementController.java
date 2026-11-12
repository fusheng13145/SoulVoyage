package com.leyon.soul.domain.achievement;

import com.leyon.soul.auth.AuthPrincipal;
import com.leyon.soul.common.api.ApiResponse;
import com.leyon.soul.domain.achievement.dto.AchievementDtos.BadgeView;
import com.leyon.soul.domain.achievement.dto.AchievementDtos.WallResp;
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
    public ApiResponse<WallResp> wall(@AuthenticationPrincipal AuthPrincipal p) {
        List<Map<String, Object>> rows = achievements.wall(p.userId());
        List<BadgeView> list = rows.stream()
                .map(m -> new BadgeView(
                        (String) m.get("code"), (String) m.get("name"), (String) m.get("desc"),
                        Boolean.TRUE.equals(m.get("unlocked")), (String) m.get("unlockedAt")))
                .toList();
        long unlocked = list.stream().filter(BadgeView::unlocked).count();
        return ApiResponse.ok(new WallResp(list, unlocked, list.size()));
    }
}
