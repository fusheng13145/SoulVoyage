package com.soulvoyage.admin;

import com.soulvoyage.audit.AuditService;
import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.auth.JwtService;
import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.api.PageReq;
import com.soulvoyage.common.api.PageResp;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A4 用户支持（手册下篇·管理端）：查询只见元数据（手机号等密文永不回传）、
 * 冻结/解冻（冻结即吊销全部令牌，登录闸门 status 1|3 天然拒绝）、危机看板与冷静期视图。
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') and @perms.has('admin:user')")
public class AdminSupportController {

    private final UserRepository users;
    private final JwtService jwt;
    private final AuditService audit;
    private final com.soulvoyage.domain.crisis.CrisisLifecycleRepository lifecycle;

    @GetMapping
    public ApiResponse<PageResp<Map<String, Object>>> list(
            @RequestParam(required = false) Short status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        PageReq req = PageReq.of(page, size);
        String kw = status == null && (q == null || q.isBlank()) ? null : blankToNull(q);
        var result = users.findForAdmin(status, kw == null ? null : kw.trim(), req.toRequest());
        return ApiResponse.ok(PageResp.of(result.getContent().stream().map(this::meta).toList(),
                req, result.getTotalElements()));
    }

    /** 危机看板（S1 状态机全景）：CRISIS/COOLING/REVIEW 用户按进入时间排序，附最近迁移轨迹 */
    @GetMapping("/crisis-board")
    public ApiResponse<List<Map<String, Object>>> crisisBoard() {
        return ApiResponse.ok(users.findByCrisisStateNotOrderByCrisisStartedAtAsc("NORMAL")
                .stream().map(u -> {
                    Map<String, Object> m = meta(u);
                    m.put("crisisState", u.getCrisisState());
                    m.put("crisisStartedAt", str(u.getCrisisStartedAt()));
                    m.put("crisisEndsAt", str(u.getCrisisEndsAt()));
                    m.put("transitions", lifecycle.findByUserIdOrderByCreatedAtDescIdDesc(u.getId())
                            .stream().limit(6).map(t -> Map.of(
                                    "from", str(t.getFromState()), "to", str(t.getToState()),
                                    "reason", str(t.getReason()), "at", str(t.getCreatedAt()))).toList());
                    return m;
                }).toList());
    }

    @PostMapping("/{id}/freeze")
    public ApiResponse<Map<String, Object>> freeze(@AuthenticationPrincipal AuthPrincipal p,
                                                   @PathVariable Long id) {
        UserEntity u = managed(p, id);
        if (u.getStatus() != 1) throw new BizException(ErrorCode.BAD_PARAMS, "仅正常用户可冻结");
        u.setStatus((short) 2);
        users.save(u);
        jwt.revokeAll(id);   // 冻结即时断会话：旧令牌全部拉黑
        audit.record(p.userId(), "ADMIN_FREEZE", "user:" + id, null);
        return ApiResponse.ok(Map.of("userId", id, "status", 2));
    }

    @PostMapping("/{id}/unfreeze")
    public ApiResponse<Map<String, Object>> unfreeze(@AuthenticationPrincipal AuthPrincipal p,
                                                     @PathVariable Long id) {
        UserEntity u = user(id);
        if (u.getStatus() != 2) throw new BizException(ErrorCode.BAD_PARAMS, "仅冻结用户可解冻");
        u.setStatus((short) 1);
        users.save(u);
        audit.record(p.userId(), "ADMIN_UNFREEZE", "user:" + id, null);
        return ApiResponse.ok(Map.of("userId", id, "status", 1));
    }

    // ---------------- internals ----------------

    private UserEntity managed(AuthPrincipal p, Long id) {
        if (p.userId() == id) throw new BizException(ErrorCode.BAD_PARAMS, "不能冻结自己的账号");
        return user(id);
    }

    private UserEntity user(Long id) {
        return users.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "用户不存在"));
    }

    /** 元数据视图：不含 password_hash/phone_enc 等敏感列；status=3 行即注销冷静期待处理清单 */
    private Map<String, Object> meta(UserEntity u) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("nickname", u.getNickname());
        m.put("role", u.getRole());
        m.put("status", u.getStatus());
        m.put("createdAt", str(u.getCreatedAt()));
        m.put("deletionRequestedAt", str(u.getDeletionRequestedAt()));
        return m;
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
