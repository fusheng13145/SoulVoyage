package com.soulvoyage.domain.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.crypto.CryptoService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.Predicate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 复盘报告查询（手册 §8.2）：列表只回元数据，正文详情按属主解密返回 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private static final Set<String> RATINGS = Set.of("USEFUL", "UNSURE", "UNHELPFUL");

    private final ReportRepository reportRepo;
    private final CryptoService crypto;
    private final ObjectMapper mapper;
    private final com.soulvoyage.common.time.BusinessCalendar cal;

    public record ReportMeta(Long id, String type, String title, String riskLevel, Boolean starred,
                            String feedback, String createdAt) {}

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@AuthenticationPrincipal AuthPrincipal p,
                                                 @RequestParam(required = false) String type,
                                                 @RequestParam(required = false) String riskLevel,
                                                 @RequestParam(required = false) Boolean starred,
                                                 @RequestParam(required = false) LocalDate from,
                                                 @RequestParam(required = false) LocalDate to,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "10") int size) {
        long uid = p.userId();
        // C2 扩展过滤：from/to 按用户本地日界（O1 口径）折算成 Instant 区间
        Specification<ReportEntity> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("userId"), uid));
            ps.add(cb.isNull(root.get("deletedAt")));
            if (type != null && !type.isBlank()) ps.add(cb.equal(root.get("type"), type));
            if (riskLevel != null && !riskLevel.isBlank()) ps.add(cb.equal(root.get("riskLevel"), riskLevel));
            if (starred != null) ps.add(cb.equal(root.get("starred"), (short) (starred ? 1 : 0)));
            if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"),
                    from.atStartOfDay(cal.zone()).toInstant()));
            if (to != null) ps.add(cb.lessThan(root.get("createdAt"), to.plusDays(1).atStartOfDay(cal.zone()).toInstant()));
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<ReportEntity> result = reportRepo.findAll(spec,
                PageRequest.of(page, Math.min(size, 50), Sort.by(Sort.Direction.DESC, "createdAt")));
        List<ReportMeta> items = result.getContent().stream()
                .map(r -> new ReportMeta(r.getId(), r.getType(), r.getTitle(), r.getRiskLevel(),
                        r.getStarred() != 0, r.getFeedback(),
                        r.getCreatedAt() == null ? null : r.getCreatedAt().toString()))
                .toList();
        return ApiResponse.ok(Map.of(
                "items", items, "page", result.getNumber(), "size", result.getSize(),
                "total", result.getTotalElements()));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@AuthenticationPrincipal AuthPrincipal p,
                                                   @PathVariable Long id) throws Exception {
        ReportEntity r = ownReport(p, id);
        String json = crypto.decryptUserField(p.userId(), r.getContentEnc());
        return ApiResponse.ok(Map.of(
                "id", r.getId(), "type", r.getType(), "title", r.getTitle(),
                "riskLevel", r.getRiskLevel(),
                "starred", r.getStarred() != 0,
                "feedback", r.getFeedback() == null ? "" : r.getFeedback(),
                "feedbackNote", r.getFeedbackNoteEnc() == null ? ""
                        : crypto.decryptUserField(p.userId(), r.getFeedbackNoteEnc()),
                "feedbackAt", r.getFeedbackAt() == null ? "" : r.getFeedbackAt().toString(),
                "createdAt", r.getCreatedAt() == null ? "" : r.getCreatedAt().toString(),
                "content", mapper.readTree(json)));
    }

    // ---------- L2 报表批注：收藏与反馈，只影响本账号 ----------

    public record StarReq(@NotNull Boolean starred) {}

    /** rating 留空 = 撤销评价 */
    public record FeedbackReq(String rating, @Size(max = 200) String note) {}

    @PostMapping("/{id}/star")
    public ApiResponse<Map<String, Object>> star(@AuthenticationPrincipal AuthPrincipal p,
                                                 @PathVariable Long id,
                                                 @Valid @RequestBody StarReq req) {
        ReportEntity r = ownReport(p, id);
        r.setStarred((short) (Boolean.TRUE.equals(req.starred()) ? 1 : 0));
        reportRepo.save(r);
        return ApiResponse.ok(Map.of("id", r.getId(), "starred", r.getStarred() != 0));
    }

    @PostMapping("/{id}/feedback")
    public ApiResponse<Map<String, Object>> feedback(@AuthenticationPrincipal AuthPrincipal p,
                                                     @PathVariable Long id,
                                                     @Valid @RequestBody FeedbackReq req) {
        ReportEntity r = ownReport(p, id);
        String rating = req.rating() == null || req.rating().isBlank()
                ? null : req.rating().trim().toUpperCase();
        if (rating != null && !RATINGS.contains(rating)) {
            throw new BizException(ErrorCode.BAD_PARAMS, "评价取值不合法");
        }
        r.setFeedback(rating);
        r.setFeedbackNoteEnc(rating == null || req.note() == null || req.note().isBlank()
                ? null : crypto.encryptUserField(p.userId(), req.note().trim()));
        r.setFeedbackAt(rating == null ? null : Instant.now());
        reportRepo.save(r);
        return ApiResponse.ok(Map.of("id", r.getId(), "feedback", rating == null ? "" : rating));
    }

    /** 属主校验：他人报告与已删报告同样 404，不泄露存在性 */
    private ReportEntity ownReport(AuthPrincipal p, Long id) {
        return reportRepo.findByIdAndUserIdAndDeletedAtIsNull(id, p.userId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND));
    }
}
