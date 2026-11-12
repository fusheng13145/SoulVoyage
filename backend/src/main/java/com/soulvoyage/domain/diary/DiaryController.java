package com.soulvoyage.domain.diary;

import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.domain.diary.dto.DiaryDtos.DiaryDetailResp;
import com.soulvoyage.domain.diary.dto.DiaryDtos.DiaryListItem;
import com.soulvoyage.domain.diary.dto.DiaryDtos.DiaryListResp;
import com.soulvoyage.domain.diary.dto.DiaryDtos.DiaryUpdateResp;
import com.soulvoyage.domain.diary.dto.DiaryDtos.DraftResp;
import com.soulvoyage.domain.diary.dto.DiaryDtos.EmotionRef;
import com.soulvoyage.domain.diary.dto.DiaryDtos.ReportRef;
import com.soulvoyage.orchestrator.api.TaskDtos.TaskNoResp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
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
    public ApiResponse<DiaryListResp> list(@AuthenticationPrincipal AuthPrincipal p,
                                           @RequestParam(required = false) LocalDate from,
                                           @RequestParam(required = false) LocalDate to,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(listView(diary.list(p.userId(), from, to, q, page, size)));
    }

    @GetMapping("/{id}")
    public ApiResponse<DiaryDetailResp> detail(@AuthenticationPrincipal AuthPrincipal p,
                                               @PathVariable long id) {
        return ApiResponse.ok(detailView(diary.detail(p.userId(), id)));
    }

    @PutMapping("/{id}")
    public ApiResponse<DiaryUpdateResp> update(@AuthenticationPrincipal AuthPrincipal p,
                                               @PathVariable long id,
                                               @Valid @RequestBody DiaryEditReq req) {
        Map<String, Object> m = diary.update(p.userId(), id, req.content(), req.moodSelfRating());
        return ApiResponse.ok(new DiaryUpdateResp(
                ((Number) m.get("id")).longValue(), Boolean.TRUE.equals(m.get("staled"))));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthPrincipal p, @PathVariable long id) {
        diary.delete(p.userId(), id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/reanalyze")
    public ApiResponse<TaskNoResp> reanalyze(@AuthenticationPrincipal AuthPrincipal p,
                                             @PathVariable long id) {
        return ApiResponse.ok(new TaskNoResp(diary.reanalyze(p.userId(), id)));
    }

    @GetMapping("/draft")
    public ApiResponse<DraftResp> getDraft(@AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(new DraftResp(diary.getDraft(p.userId())));
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

    @SuppressWarnings("unchecked")
    private static DiaryListResp listView(Map<String, Object> m) {
        List<DiaryListItem> items = ((List<Map<String, Object>>) m.get("items")).stream()
                .map(x -> new DiaryListItem(
                        ((Number) x.get("id")).longValue(), (String) x.get("recordDate"),
                        ((Number) x.get("moodSelfRating")).intValue(),
                        ((Number) x.get("taskId")).longValue(),
                        (String) x.get("source"), (String) x.get("preview")))
                .toList();
        return new DiaryListResp(items, ((Number) m.get("page")).intValue(),
                ((Number) m.get("size")).intValue(), ((Number) m.get("total")).intValue());
    }

    @SuppressWarnings("unchecked")
    private static DiaryDetailResp detailView(Map<String, Object> m) {
        List<ReportRef> reports = ((List<Map<String, Object>>) m.get("reports")).stream()
                .map(x -> new ReportRef(
                        ((Number) x.get("id")).longValue(), (String) x.get("type"),
                        (String) x.get("title"), (String) x.get("riskLevel"),
                        ((Number) x.get("stale")).intValue()))
                .toList();
        List<EmotionRef> emotions = ((List<Map<String, Object>>) m.get("emotions")).stream()
                .map(x -> new EmotionRef(
                        (String) x.get("sourceType"), (String) x.get("emotion"),
                        (java.math.BigDecimal) x.get("valence"),
                        (java.math.BigDecimal) x.get("intensity"),
                        (com.fasterxml.jackson.databind.JsonNode) x.get("eventTags")))
                .toList();
        return new DiaryDetailResp(
                ((Number) m.get("id")).longValue(), (String) m.get("recordDate"),
                ((Number) m.get("moodSelfRating")).intValue(), (String) m.get("content"),
                (String) m.get("source"), ((Number) m.get("voiceDurationMs")).longValue(),
                ((Number) m.get("taskId")).longValue(), reports, emotions);
    }
}
