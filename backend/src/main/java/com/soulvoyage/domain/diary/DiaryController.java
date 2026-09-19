package com.soulvoyage.domain.diary;

import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/** 下篇·C1 日记本端点（首次分析仍走 POST /tasks，此处负责回看/编辑/删除/重分析/草稿） */
@RestController
@RequestMapping("/api/v1/diaries")
@RequiredArgsConstructor
public class DiaryController {

    public record DraftReq(@NotBlank @Size(max = 5000) String content) {}

    public record DiaryEditReq(@NotBlank @Size(max = 5000) String content, Short moodSelfRating) {}

    private final DiaryService diary;
    @GetMapping
    public ApiResponse<Map<String, Object>> list(@AuthenticationPrincipal AuthPrincipal p,
                                                 @RequestParam(required = false) LocalDate from,
                                                 @RequestParam(required = false) LocalDate to,
                                                 @RequestParam(required = false) String q,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(diary.list(p.userId(), from, to, q, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@AuthenticationPrincipal AuthPrincipal p,
                                                   @PathVariable long id) {
        return ApiResponse.ok(diary.detail(p.userId(), id));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@AuthenticationPrincipal AuthPrincipal p,
                                                    @PathVariable long id,
                                                    @Valid @RequestBody DiaryEditReq req) {
        return ApiResponse.ok(diary.update(p.userId(), id, req.content(), req.moodSelfRating()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthPrincipal p, @PathVariable long id) {
        diary.delete(p.userId(), id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/reanalyze")
    public ApiResponse<Map<String, String>> reanalyze(@AuthenticationPrincipal AuthPrincipal p,
                                                       @PathVariable long id) {
        return ApiResponse.ok(Map.of("taskNo", diary.reanalyze(p.userId(), id)));
    }

    @GetMapping("/draft")
    public ApiResponse<Map<String, Object>> getDraft(@AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(Map.of("content", diary.getDraft(p.userId())));
    }

    @PutMapping("/draft")
    public ApiResponse<Void> saveDraft(@AuthenticationPrincipal AuthPrincipal p,
                                        @Valid @RequestBody DraftReq req) {
        diary.saveDraft(p.userId(), req.content());
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/draft")
    public ApiResponse<Void> clearDraft(@AuthenticationPrincipal AuthPrincipal p) {
        diary.clearDraft(p.userId());
        return ApiResponse.ok(null);
    }
}
