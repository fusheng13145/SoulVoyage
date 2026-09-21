package com.soulvoyage.auth;

import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import com.soulvoyage.dto.AuthDtos.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService auth;
    private final UserRepository userRepo;

    @PostMapping("/register")
    public ApiResponse<TokenResp> register(@Valid @RequestBody RegisterReq req, HttpServletRequest http) {
        return ApiResponse.ok(auth.register(req, clientIp(http)));
    }

    @PostMapping("/login")
    public ApiResponse<TokenResp> login(@Valid @RequestBody LoginReq req, HttpServletRequest http) {
        return ApiResponse.ok(auth.login(req, clientIp(http)));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResp> refresh(@Valid @RequestBody RefreshReq req) {
        return ApiResponse.ok(auth.refresh(req.refreshToken()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader("Authorization") String authorization,
                                    @RequestBody(required = false) RefreshReq req) {
        auth.logout(authorization.replaceFirst("Bearer ", ""),
                req == null ? null : req.refreshToken());
        return ApiResponse.ok(null);
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(@AuthenticationPrincipal AuthPrincipal p) {
        if (p == null) throw new com.soulvoyage.common.exception.BizException(
                com.soulvoyage.common.api.ErrorCode.UNAUTHORIZED, null);
        UserEntity u = userRepo.findByIdAndDeletedAtIsNull(p.userId()).orElseThrow();
        return ApiResponse.ok(Map.ofEntries(
                Map.entry("userId", u.getId()), Map.entry("username", u.getUsername()),
                Map.entry("nickname", u.getNickname()), Map.entry("role", u.getRole()),
                Map.entry("crisisState", u.getCrisisState()),
                Map.entry("agreedPolicyAt", u.getAgreedPolicyAt() == null ? "" : u.getAgreedPolicyAt().toString()),
                Map.entry("policyVersion", u.getPolicyVersion() == null ? "" : u.getPolicyVersion()),
                Map.entry("status", u.getStatus().intValue()),
                Map.entry("deletionRequestedAt",
                        u.getDeletionRequestedAt() == null ? "" : u.getDeletionRequestedAt().toString()),
                // M14：只回"绑没绑"，不回 openid——那是服务端与微信之间的标识，不是给用户看的
                Map.entry("wechatBound", u.getWxOpenid() != null),
                // createdAt 由库侧默认值供给（列上 insertable=false），读回来可能是 null：
                // Map.entry 不接受 null 值，这里不兜住就是整个 /me 报 500
                Map.entry("createdAt", u.getCreatedAt() == null ? "" : u.getCreatedAt().toString())));
    }

    static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        return xff != null ? xff.split(",")[0].trim() : req.getRemoteAddr();
    }
}
