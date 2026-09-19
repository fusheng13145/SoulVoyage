package com.soulvoyage.domain.archive;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.common.time.BusinessCalendar;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * UC5 成长档案接口（手册 §6.5）：周报 + 限时一次性导出。
 * PDF 渲染按手册风险预案降级为前端打印样式页：导出端点返回快照 JSON，链接 5 分钟 + 领取即焚。
 */
@RestController
@RequestMapping("/api/v1/archive")
@RequiredArgsConstructor
public class ArchiveController {

    private final ArchiveService archive;
    private final BusinessCalendar cal;

    @GetMapping("/summary")
    public ApiResponse<ObjectNode> summary(@AuthenticationPrincipal AuthPrincipal p,
                                           @RequestParam(required = false) LocalDate week) {
        return ApiResponse.ok(archive.weeklySummary(p.userId(), week == null ? cal.today() : week));
    }

    @PostMapping("/export")
    public ApiResponse<Map<String, Object>> export(@AuthenticationPrincipal AuthPrincipal p,
                                                   HttpServletRequest req) {
        String fileId = archive.createExport(p.userId(), req.getRemoteAddr());
        return ApiResponse.ok(Map.of(
                "fileId", fileId,
                "expiresInSeconds", 300,
                "mode", "PRINT_PAGE",     // 前端打印样式页（PDF 降级方案）
                "note", "链接限时 5 分钟且仅可领取一次"));
    }

    @GetMapping("/export/{fileId}")
    public ApiResponse<ObjectNode> download(@AuthenticationPrincipal AuthPrincipal p,
                                            @PathVariable String fileId,
                                            HttpServletRequest req) {
        return ApiResponse.ok(archive.downloadExport(p.userId(), fileId, req.getRemoteAddr()));
    }
}
