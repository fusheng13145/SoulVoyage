package com.leyon.soul.auth;

import com.leyon.soul.common.api.ApiResponse;
import com.leyon.soul.common.api.ErrorCode;
import com.leyon.soul.common.exception.BizException;
import com.leyon.soul.common.util.ClientIpResolver;
import com.leyon.soul.dto.AuthDtos.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

/**
 * 认证入口（Web / 小程序共用）。
 *
 * <p>令牌投递策略（2026-11-20 审查 04 项）：
 * <ul>
 *   <li>**浏览器端**：refresh 只下发到 `HttpOnly` Cookie（{@link RefreshCookie}），响应体里的
 *       `refreshToken` 一律置空——XSS 拿不到长效凭证；access 由前端放内存，30 分钟后自动续期。</li>
 *   <li>**原生小程序端**：没有浏览器页面、也就没有 XSS 面，仍走响应体令牌（它需要自己存到
 *       `uni.setStorageSync`）。判定条件是 **`X-Client-Type: miniprogram` 且无 `Origin` 头**：
 *       浏览器发起的请求（含同源 XSS 发起的）必然带 Origin，因此无法用这个头"降级"拿到 body 令牌。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService auth;
    private final ClientIpResolver clientIpResolver;
    private final RefreshCookie refreshCookie;

    @PostMapping("/register")
    public ApiResponse<TokenResp> register(@Valid @RequestBody RegisterReq req,
                                           HttpServletRequest http, HttpServletResponse res) {
        return ApiResponse.ok(shape(auth.register(req, clientIp(http)), http, res));
    }

    @PostMapping("/login")
    public ApiResponse<TokenResp> login(@Valid @RequestBody LoginReq req,
                                        HttpServletRequest http, HttpServletResponse res) {
        return ApiResponse.ok(shape(auth.login(req, clientIp(http)), http, res));
    }

    /**
     * 续期：优先取请求体（原生客户端），其次取 HttpOnly Cookie（浏览器）。
     * 走 Cookie 时必须过同源校验——防 SameSite 失效场景下的跨站静默续期。
     */
    @PostMapping("/refresh")
    public ApiResponse<TokenResp> refresh(@RequestBody(required = false) RefreshReq req,
                                          HttpServletRequest http, HttpServletResponse res) {
        String fromBody = req == null || req.refreshToken() == null ? null : req.refreshToken().trim();
        String token = (fromBody == null || fromBody.isBlank()) ? RefreshCookie.read(http) : fromBody;
        if (token == null || token.isBlank()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "缺少续期凭证，请重新登录");
        }
        if (fromBody == null || fromBody.isBlank()) {
            refreshCookie.assertSameOrigin(http);   // 只有 Cookie 路径需要 CSRF 同源校验
        }
        return ApiResponse.ok(shape(auth.refresh(token, clientIp(http)), http, res));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestBody(required = false) RefreshReq req,
                                    HttpServletRequest http, HttpServletResponse res) {
        String refresh = req == null ? null : req.refreshToken();
        if (refresh == null || refresh.isBlank()) refresh = RefreshCookie.read(http);
        auth.logout(authorization == null ? null : authorization.replaceFirst("Bearer ", ""), refresh);
        refreshCookie.clear(res);   // 服务端排障失败也先让浏览器丢掉凭证
        return ApiResponse.ok(null);
    }

    @GetMapping("/me")
    public ApiResponse<MeResp> me(@AuthenticationPrincipal AuthPrincipal p) {
        // p 不会为 null：/auth/me 不在 permitAll 名单里，anyRequest().authenticated() 已先行拦截。
        // 档案组装下沉到 AuthService：ArchitectureTest 明确禁止 Controller 直接依赖 *Repository。
        return ApiResponse.ok(auth.me(p.userId()));
    }

    /** 浏览器：refresh 进 HttpOnly Cookie、body 置空；原生客户端：原样返回 body 令牌 */
    private TokenResp shape(TokenResp t, HttpServletRequest req, HttpServletResponse res) {
        if (isNativeClient(req)) {
            return t;
        }
        refreshCookie.issue(res, t.refreshToken());
        return new TokenResp(t.accessToken(), null, t.expiresIn(),
                t.userId(), t.nickname(), t.role(), t.deletionPending());
    }

    private static boolean isNativeClient(HttpServletRequest req) {
        String clientType = req.getHeader("X-Client-Type");
        String origin = req.getHeader("Origin");
        return clientType != null && "miniprogram".equals(clientType.toLowerCase(Locale.ROOT))
                && (origin == null || origin.isBlank());
    }

    /** 统一走 ClientIpResolver：只在请求来自内网/代理时才采信 X-Real-IP / XFF 末段，防伪造换计数器 */
    String clientIp(HttpServletRequest req) {
        return clientIpResolver.resolve(req);
    }
}
