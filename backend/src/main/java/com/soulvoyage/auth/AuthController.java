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
        return ApiResponse.ok(Map.of(
                "userId", u.getId(), "username", u.getUsername(), "nickname", u.getNickname(),
                "role", u.getRole(), "crisisFlag", u.getCrisisFlag(), "createdAt", u.getCreatedAt()));
    }

    static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        return xff != null ? xff.split(",")[0].trim() : req.getRemoteAddr();
    }
}
