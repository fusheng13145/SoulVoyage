package com.soulvoyage.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.audit.AuditService;
import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.api.PageReq;
import com.soulvoyage.common.api.PageResp;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.crisis.CrisisService;
import com.soulvoyage.domain.risk.RiskEventEntity;
import com.soulvoyage.domain.risk.RiskEventRepository;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A2 风险复核队列（手册下篇·管理端 / §7.3 SOP）：
 * 队列只见元数据；证据解密走二次授权（管理员口令重验 + ADMIN_VIEW_RISK 审计）；
 * 复核结案可联动危机生命周期（{@link CrisisService#adminClose}）。
 */
@RestController
@RequestMapping("/api/v1/admin/risk")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') and @perms.has('admin:risk:view')")
public class AdminRiskController {

    public record RevealBody(String password) {}

    public record ReviewBody(Boolean closeCrisis) {}

    private final RiskEventRepository events;
    private final UserRepository users;
    private final CryptoService crypto;
    private final CrisisService crisis;
    private final AuditService audit;
    private final PasswordEncoder encoder;
    private final ObjectMapper mapper;

    /** 复核队列：reviewed 默认只看未复核（0），level 可选过滤 */
    @GetMapping("/queue")
    public ApiResponse<PageResp<Map<String, Object>>> queue(
            @RequestParam(required = false) Short reviewed,
            @RequestParam(required = false) String level,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        PageReq req = PageReq.of(page, size);
        var result = events.findQueue(reviewed == null ? (short) 0 : reviewed, blankToNull(level), req.toRequest());
        var items = result.getContent().stream().map(e -> {
            Map<String, Object> n = new LinkedHashMap<String, Object>();
            n.put("id", e.getId());
            n.put("userId", e.getUserId());
            n.put("level", e.getLevel());
            n.put("triggerType", e.getTriggerType());
            n.put("ruleCode", e.getRuleCode());
            n.put("actionTaken", e.getActionTaken());
            n.put("taskId", e.getTaskId());
            n.put("needsReview", e.getNeedsReview());
            n.put("reviewed", e.getReviewed());
            n.put("createdAt", e.getCreatedAt().toString());
            return n;
        }).toList();
        return ApiResponse.ok(PageResp.of(items, req, result.getTotalElements()));
    }

    /** 二次授权解密：管理员口令重验通过才回证据明文，动作（含失败）全审计 */
    @PostMapping("/{id}/reveal")
    public ApiResponse<Map<String, Object>> reveal(@AuthenticationPrincipal AuthPrincipal p,
                                                   @PathVariable Long id,
                                                   @RequestBody RevealBody body) {
        verifyAdminPassword(p, id, body.password());
        RiskEventEntity e = event(id);
        Object evidence;
        try {
            evidence = e.getEvidenceRefEnc() == null ? null
                    : mapper.readTree(crypto.decryptUserField(e.getUserId(), e.getEvidenceRefEnc()));
        } catch (Exception ex) {
            evidence = Map.of("error", "证据解密失败");
        }
        audit.record(p.userId(), "ADMIN_VIEW_RISK", "risk_event:" + id, null);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("riskEventId", id);
        resp.put("userId", e.getUserId());
        resp.put("evidence", evidence);
        return ApiResponse.ok(resp);
    }

    /** 复核结案：标记已复核；closeCrisis=true 时联动解除危机态（内部另有 ADMIN_CLOSE_CRISIS 审计） */
    @PostMapping("/{id}/review")
    public ApiResponse<Map<String, Object>> review(@AuthenticationPrincipal AuthPrincipal p,
                                                   @PathVariable Long id,
                                                   @RequestBody ReviewBody body) {
        RiskEventEntity e = event(id);
        e.setReviewed((short) 1);
        events.save(e);
        boolean closed = Boolean.TRUE.equals(body.closeCrisis());
        if (closed) crisis.adminClose(e.getUserId(), p.userId(), e.getId());
        audit.record(p.userId(), "ADMIN_REVIEW_RISK",
                "risk_event:" + id + (closed ? "+close_crisis" : ""), null);
        return ApiResponse.ok(Map.of("riskEventId", id, "reviewed", true, "crisisClosed", closed));
    }

    // ---------------- internals ----------------

    private RiskEventEntity event(Long id) {
        return events.findById(id).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "风险事件不存在"));
    }

    private void verifyAdminPassword(AuthPrincipal p, Long eventId, String password) {
        UserEntity admin = users.findById(p.userId()).orElseThrow(
                () -> new BizException(ErrorCode.UNAUTHORIZED));
        boolean ok = password != null && !password.isBlank()
                && encoder.matches(password, admin.getPasswordHash());
        if (!ok) {
            audit.record(p.userId(), "ADMIN_VIEW_RISK_DENIED", "risk_event:" + eventId, null);
            throw new BizException(ErrorCode.FORBIDDEN, "管理员口令校验失败");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
