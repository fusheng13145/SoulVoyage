package com.soulvoyage.domain.letter;

import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.domain.letter.dto.LetterDtos.LetterItem;
import com.soulvoyage.domain.letter.dto.LetterDtos.LetterListResp;
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
    public ApiResponse<LetterListResp> list(@AuthenticationPrincipal AuthPrincipal p) {
        List<LetterItem> items = letters.list(p.userId()).stream()
                .map(m -> new LetterItem(
                        (String) m.get("id"), (String) m.get("statWeek"),
                        (String) m.get("createdAt"), (String) m.get("letter"),
                        (String) m.get("weekGlow")))
                .toList();
        return ApiResponse.ok(new LetterListResp(items, items.size()));
    }
}
