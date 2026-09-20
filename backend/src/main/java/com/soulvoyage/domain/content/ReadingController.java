package com.soulvoyage.domain.content;

import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
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
    public ApiResponse<Map<String, Object>> today(@AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.today(p.userId()));
    }

    @PostMapping("/favorites")
    public ApiResponse<Map<String, Object>> toggleFavorite(@AuthenticationPrincipal AuthPrincipal p,
                                                           @Valid @RequestBody FavoriteReq req) {
        boolean on = service.toggleFavorite(p.userId(), req.type(), req.refCode());
        return ApiResponse.ok(Map.of("type", req.type(), "refCode", req.refCode(), "favorited", on));
    }

    @GetMapping("/favorites")
    public ApiResponse<List<Map<String, Object>>> favorites(@AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.favorites(p.userId()));
    }
}
