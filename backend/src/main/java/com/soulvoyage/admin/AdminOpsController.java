package com.soulvoyage.admin;

import com.soulvoyage.audit.AuditService;
import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.common.api.PageReq;
import com.soulvoyage.common.api.PageResp;
import com.soulvoyage.crypto.DataKeyRepository;
import com.soulvoyage.domain.export.ExportRecordEntity;
import com.soulvoyage.domain.export.ExportRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A5 审计与密钥（手册下篇·管理端）：哈希链校验、导出留痕查询、数据密钥版本看板。
 * 全部只读——密钥与审计的写路径只有系统自身。
 */
@RestController
@RequestMapping("/api/v1/admin/ops")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') and @perms.has('admin:audit')")
public class AdminOpsController {

    private static final Map<Short, String> KEY_STATUS = Map.of(
            (short) 1, "ACTIVE", (short) 2, "DECRYPT_ONLY", (short) 3, "DESTROYED");

    private final AuditService audit;
    private final com.soulvoyage.audit.AuditLogRepository auditLogs;
    private final DataKeyRepository dataKeys;
    private final ExportRecordRepository exports;

    /** A5 审计查询：userId/action 过滤分页，只回链上既有字段 */
    @GetMapping("/audit")
    public ApiResponse<PageResp<Map<String, Object>>> audit(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageReq req = PageReq.of(page, size);
        var result = auditLogs.findForAdmin(userId, blankToNull(action), req.toRequest());
        var items = result.getContent().stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", a.getId());
            m.put("userId", a.getUserId());
            m.put("action", a.getAction());
            m.put("target", a.getTarget());
            m.put("ip", a.getIp());
            m.put("createdAt", a.getCreatedAt() == null ? "" : a.getCreatedAt().toString());
            return m;
        }).toList();
        return ApiResponse.ok(PageResp.of(items, req, result.getTotalElements()));
    }

    /** 审计链校验：重放全链重算 hash，只回结论不回内容 */
    @PostMapping("/audit/verify")
    public ApiResponse<Map<String, Object>> verifyAudit(@AuthenticationPrincipal AuthPrincipal p) {
        AuditService.VerifyResult r = audit.verifyChain();
        audit.record(p.userId(), "ADMIN_AUDIT_VERIFY", "audit_log:chain", null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("intact", r.intact());
        m.put("checked", r.checked());
        m.put("brokenAtId", r.brokenAtId());
        return ApiResponse.ok(m);
    }

    /** 密钥版本看板：按状态聚合的 DEK 计数与最大版本号（1=启用/2=仅解密/3=已销毁） */
    @GetMapping("/keys")
    public ApiResponse<List<Map<String, Object>>> keys() {
        return ApiResponse.ok(dataKeys.statsByStatus().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("status", KEY_STATUS.getOrDefault(((Number) r[0]).shortValue(), String.valueOf(r[0])));
            m.put("keys", ((Number) r[1]).longValue());
            m.put("maxVersion", r[2] == null ? null : ((Number) r[2]).intValue());
            m.put("owners", ((Number) r[3]).longValue());
            return m;
        }).toList());
    }

    /** 导出留痕：谁在何时生成/领取过什么快照（fileRef 为一次性编号，内容不落库） */
    @GetMapping("/export-records")
    public ApiResponse<PageResp<Map<String, Object>>> exportRecords(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        PageReq req = PageReq.of(page, size);
        var result = exports.findAllByOrderByCreatedAtDesc(req.toRequest());
        var items = result.getContent().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", e.getId());
            m.put("userId", e.getUserId());
            m.put("kind", e.getKind());
            m.put("status", e.getStatus());
            m.put("fileRef", e.getFileRef());
            m.put("createdAt", e.getCreatedAt().toString());
            m.put("claimedAt", e.getClaimedAt() == null ? "" : e.getClaimedAt().toString());
            return m;
        }).toList();
        return ApiResponse.ok(PageResp.of(items, req, result.getTotalElements()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
