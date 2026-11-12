package com.soulvoyage.domain.content;

import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.domain.content.dto.ReadingDtos.FavoriteToggleResp;
import com.soulvoyage.domain.content.dto.ReadingDtos.TodayResp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** N3 每日一读：今日卡片（纯规则供给）+ 收藏。登录态 + userId 取自 JWT，天然属主隔离。 */
@RestController
@RequestMapping("/api/v1/readings")
@RequiredArgsConstructor
public class ReadingController {

    private final ReadingService service;

    public record FavoriteReq(@NotBlank String type, @NotBlank String refCode) {}

    @GetMapping("/today")
    @SuppressWarnings("unchecked")
    public ApiResponse<TodayResp> today(@AuthenticationPrincipal AuthPrincipal p) {
        Map<String, Object> m = service.today(p.userId());
        return ApiResponse.ok(new TodayResp(
                (String) m.get("kgNodeId"), (String) m.get("title"), (String) m.get("summary"),
                (String) m.get("microAction"), (List<String>) m.get("aboutTags"),
                ((Number) m.get("readingSec")).intValue(), (String) m.get("date"),
                Boolean.TRUE.equals(m.get("favorited")), Boolean.TRUE.equals(m.get("firstToday"))));
    }

    @PostMapping("/favorites")
    public ApiResponse<FavoriteToggleResp> toggleFavorite(@AuthenticationPrincipal AuthPrincipal p,
                                                          @Valid @RequestBody FavoriteReq req) {
        boolean on = service.toggleFavorite(p.userId(), req.type(), req.refCode());
        return ApiResponse.ok(new FavoriteToggleResp(req.type(), req.refCode(), on));
    }

    @GetMapping("/favorites")
    public ApiResponse<List<Map<String, Object>>> favorites(@AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.favorites(p.userId()));
    }
}
