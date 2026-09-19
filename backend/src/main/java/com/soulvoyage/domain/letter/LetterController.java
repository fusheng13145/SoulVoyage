package com.soulvoyage.domain.letter;

import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 下篇·G6 成长来信合集端点（档案页消费；生成走周一 07:00 定时任务） */
@RestController
@RequestMapping("/api/v1/letters")
@RequiredArgsConstructor
public class LetterController {

    private final GrowthLetterService letters;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@AuthenticationPrincipal AuthPrincipal p) {
        List<Map<String, Object>> items = letters.list(p.userId());
        return ApiResponse.ok(Map.of("items", items, "total", items.size()));
    }
}
