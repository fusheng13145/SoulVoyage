package com.soulvoyage.domain.achievement;

import com.soulvoyage.auth.AuthPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 成就墙端点的计数口径：unlockedCount 只认真正的 Boolean.TRUE，缺失/非布尔键都算未解锁。 */
@ExtendWith(MockitoExtension.class)
class AchievementControllerTest {

    @Mock AchievementService achievements;
    @InjectMocks AchievementController controller;

    private AuthPrincipal principal() {
        AuthPrincipal p = mock(AuthPrincipal.class);
        lenient().when(p.userId()).thenReturn(7L);
        return p;
    }

    @Test
    @DisplayName("目录与解锁数：逐项统计 unlocked=true 的条目")
    void wallCountsUnlocked() {
        when(achievements.wall(7L)).thenReturn(List.of(
                Map.of("code", "a", "unlocked", true),
                Map.of("code", "b", "unlocked", false),
                Map.of("code", "c", "unlocked", true)));

        Map<String, Object> out = controller.wall(principal()).getData();

        assertEquals(3, out.get("totalCount"));
        assertEquals(2L, out.get("unlockedCount"));
    }

    @Test
    @DisplayName("缺失 unlocked 键的条目按未解锁计，不抛 NPE")
    void wallToleratesMissingUnlockedKey() {
        Map<String, Object> noFlag = new HashMap<>();
        noFlag.put("code", "x");
        when(achievements.wall(7L)).thenReturn(List.of(noFlag));

        Map<String, Object> out = controller.wall(principal()).getData();

        assertEquals(1, out.get("totalCount"));
        assertEquals(0L, out.get("unlockedCount"));
    }

    @Test
    @DisplayName("unlocked 为非布尔真值（如字符串）时不误判为已解锁")
    void wallIgnoresTruthyNonBoolean() {
        when(achievements.wall(7L)).thenReturn(List.of(Map.of("code", "y", "unlocked", "true")));

        Map<String, Object> out = controller.wall(principal()).getData();

        assertEquals(0L, out.get("unlockedCount"), "只认 Boolean.TRUE，不做宽松真值判断");
    }

    @Test
    @DisplayName("空目录：三项分别为空列表、0、0")
    void wallWithEmptyCatalog() {
        when(achievements.wall(7L)).thenReturn(List.of());

        Map<String, Object> out = controller.wall(principal()).getData();

        assertEquals(List.of(), out.get("items"));
        assertEquals(0L, out.get("unlockedCount"));
        assertEquals(0, out.get("totalCount"));
    }

    @Test
    @DisplayName("按当前登录用户取目录，不越界")
    void wallScopedToPrincipal() {
        when(achievements.wall(7L)).thenReturn(List.of());

        controller.wall(principal());

        verify(achievements).wall(7L);
    }
}
