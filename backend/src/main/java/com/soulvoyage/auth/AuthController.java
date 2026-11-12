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
    public ApiResponse<MeResp> me(@AuthenticationPrincipal AuthPrincipal p) {
        if (p == null) throw new com.soulvoyage.common.exception.BizException(
                com.soulvoyage.common.api.ErrorCode.UNAUTHORIZED, null);
        UserEntity u = userRepo.findByIdAndDeletedAtIsNull(p.userId()).orElseThrow();
        return ApiResponse.ok(new MeResp(
                u.getId(), u.getUsername(), u.getNickname(), u.getRole(),
                u.getCrisisState(),
                u.getAgreedPolicyAt() == null ? "" : u.getAgreedPolicyAt().toString(),
                u.getPolicyVersion() == null ? "" : u.getPolicyVersion(),
                u.getStatus().intValue(),
                u.getDeletionRequestedAt() == null ? "" : u.getDeletionRequestedAt().toString(),
                // M14：只回"绑没绑"，不回 openid——那是服务端与微信之间的标识，不是给用户看的
                u.getWxOpenid() != null,
                // createdAt 由库侧默认值供给（列上 insertable=false），读回来可能是 null
                u.getCreatedAt() == null ? "" : u.getCreatedAt().toString()));
    }

    static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        return xff != null ? xff.split(",")[0].trim() : req.getRemoteAddr();
    }
}
