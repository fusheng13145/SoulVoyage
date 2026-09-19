package com.soulvoyage.domain.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.crypto.CryptoService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 复盘报告查询（手册 §8.2）：列表只回元数据，正文详情按属主解密返回 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportRepository reportRepo;
    private final CryptoService crypto;
    private final ObjectMapper mapper;

    public record ReportMeta(Long id, String type, String title, String riskLevel, String createdAt) {}

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@AuthenticationPrincipal AuthPrincipal p,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "10") int size) {
        Page<ReportEntity> result = reportRepo.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                p.userId(), PageRequest.of(page, Math.min(size, 50)));
        List<ReportMeta> items = result.getContent().stream()
                .map(r -> new ReportMeta(r.getId(), r.getType(), r.getTitle(), r.getRiskLevel(),
                        r.getCreatedAt() == null ? null : r.getCreatedAt().toString()))
                .toList();
        return ApiResponse.ok(Map.of(
                "items", items, "page", result.getNumber(), "size", result.getSize(),
                "total", result.getTotalElements()));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@AuthenticationPrincipal AuthPrincipal p,
                                                   @PathVariable Long id) throws Exception {
        ReportEntity r = reportRepo.findByIdAndUserIdAndDeletedAtIsNull(id, p.userId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND));
        String json = crypto.decryptUserField(p.userId(), r.getContentEnc());
        return ApiResponse.ok(Map.of(
                "id", r.getId(), "type", r.getType(), "title", r.getTitle(),
                "riskLevel", r.getRiskLevel(),
                "createdAt", r.getCreatedAt() == null ? "" : r.getCreatedAt().toString(),
                "content", mapper.readTree(json)));
    }
}
