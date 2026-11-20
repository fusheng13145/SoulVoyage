package com.leyon.soul.auth;

import com.leyon.soul.common.api.ErrorCode;
import com.leyon.soul.common.exception.BizException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;

/**
 * refresh 令牌的 HttpOnly Cookie 载体（2026-11-20 安全审查 04 项修复）。
 *
 * <p>动机：原来 access + refresh 都放在 `localStorage`，一次 XSS 就能把 7 天长效的 refresh 整包带走。
 * 现在 refresh 只走 `HttpOnly; SameSite=Lax; Path=/api/v1/auth` 的 Cookie，JS 读不到；
 * access 由前端放**内存**（不再落 localStorage），泄漏窗口从 7 天压到 30 分钟且无法持久化。
 *
 * <p>为什么 Path 限死在 `/api/v1/auth`：refresh 只需发给 refresh/logout 两个端点，
 * 收窄路径能把这个凭证从其余所有业务请求里彻底摘出去，顺带缩小 CSRF 面。
 *
 * <p>小程序端（无浏览器页面、无 XSS 面）仍走 body 令牌，见 {@code AuthController#shape} 的判定。
 */
@Component
public class RefreshCookie {

    public static final String NAME = "sv_rt";
    private static final String PATH = "/api/v1/auth";

    private final boolean secure;
    private final Duration ttl;
    private final String[] allowedOrigins;

    public RefreshCookie(@Value("${soul.security.refresh-cookie.secure:false}") boolean secure,
                         @Value("${soul.security.jwt.refresh-ttl}") Duration ttl,
                         @Value("${soul.security.cors-allowed-origins}") String corsOrigins) {
        this.secure = secure;
        this.ttl = ttl;
        this.allowedOrigins = Arrays.stream(corsOrigins.split(",")).map(String::trim).toArray(String[]::new);
    }

    /** 下发/轮换：每次 refresh 都换新 Cookie，旧值由服务端的黑名单机制作废 */
    public void issue(HttpServletResponse res, String refreshToken) {
        res.addHeader("Set-Cookie", ResponseCookie.from(NAME, refreshToken)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(PATH)
                .maxAge(ttl)
                .build().toString());
    }

    /** 登出：即使服务端吊销失败，也要让浏览器立刻丢掉这份凭证 */
    public void clear(HttpServletResponse res) {
        res.addHeader("Set-Cookie", ResponseCookie.from(NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(PATH)
                .maxAge(0)
                .build().toString());
    }

    /** 从 Cookie 读 refresh（兼容 body 传参的小程序/老客户端） */
    public static String read(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (NAME.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return c.getValue();
            }
        }
        return null;
    }

    /**
     * 带 Cookie 的 refresh 必须过同源校验：SameSite=Lax 已经挡住跨站 POST，
     * 这里再加一道 Origin 白名单，防"Lax 在旧浏览器/特殊场景失效"时被跨站静默续期。
     * 无 Origin（原生客户端、服务端调用）放行——它们本来就没有浏览器自动带 Cookie 的问题。
     */
    public void assertSameOrigin(HttpServletRequest req) {
        String origin = req.getHeader("Origin");
        if (origin == null || origin.isBlank()) return;
        boolean ok = Arrays.stream(allowedOrigins).anyMatch(origin::equals);
        if (!ok) {
            throw new BizException(ErrorCode.FORBIDDEN, "跨站刷新被拒绝");
        }
    }
}
