package com.leyon.soul.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.soul.dto.AuthDtos.RegisterReq;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 2026-11-20 审查 04 项：refresh 改 HttpOnly Cookie 的契约回归。
 *
 * <p>要点：浏览器请求拿不到 body 里的 refresh；只拿到 Set-Cookie，且带 HttpOnly/SameSite。
 * 原生小程序（X-Client-Type=miniprogram 且无 Origin）仍走 body；浏览器加同样头也拿不到（因为带 Origin）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthCookieTest {

    private static final String PWD = "SvFixture!2026";

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired ObjectMapper mapper;

    private String user;

    @BeforeEach
    void setUp() {
        user = "ck" + System.nanoTime();
        auth.register(new RegisterReq(user, PWD, "cookie"), "ck-ip");
    }

    private String loginBody() {
        return "{\"username\":\"" + user + "\",\"password\":\"" + PWD + "\"}";
    }

    @Test
    void browserLoginStoresRefreshInHttpOnlyCookieOnly() throws Exception {
        var res = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody())
                        .header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andReturn().getResponse();

        String setCookie = res.getHeader("Set-Cookie");
        assertNotNull(setCookie, "浏览器登录必须下发 refresh Cookie");
        assertTrue(setCookie.startsWith("sv_rt="), setCookie);
        assertTrue(setCookie.contains("HttpOnly"), "Cookie 必须 HttpOnly：" + setCookie);
        assertTrue(setCookie.contains("SameSite=Lax"), "Cookie 必须 SameSite=Lax：" + setCookie);
        assertTrue(setCookie.contains("Path=/api/v1/auth"), "Cookie 路径必须收窄：" + setCookie);
    }

    @Test
    void nativeClientStillGetsBodyToken() throws Exception {
        var res = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody())
                        .header("X-Client-Type", "miniprogram"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andReturn().getResponse();

        assertNull(res.getHeader("Set-Cookie"), "原生客户端不需要 Cookie");
    }

    /** 关键负向用例：浏览器 XSS 想用同一个头"降级"拿 body 令牌，因带 Origin 必须失败 */
    @Test
    void browserCannotFakeNativeClientToGetBodyToken() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody())
                        .header("X-Client-Type", "miniprogram")
                        .header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist());
    }

    @Test
    void refreshWorksFromCookieAndRotates() throws Exception {
        String setCookie = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody()))
                .andReturn().getResponse().getHeader("Set-Cookie");
        String rt = cookieValue(setCookie);

        var res = mvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(RefreshCookie.NAME, rt))
                        .header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andReturn().getResponse();

        String rotated = res.getHeader("Set-Cookie");
        assertNotNull(rotated);
        assertNotEquals(rt, cookieValue(rotated), "refresh 必须轮换");
    }

    /**
     * 跨站带 Cookie 刷新必须被拒。两道防线都可能先命中：CORS 处理器（空体 "Invalid CORS request"）
     * 或 {@link RefreshCookie#assertSameOrigin}（1002）。这里只锁"必须 403 且不放行"，
     * 具体由谁拦由 {@code RefreshCookieTest} 单独覆盖。
     */
    @Test
    void refreshFromCookieRejectsForeignOrigin() throws Exception {
        String setCookie = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody()))
                .andReturn().getResponse().getHeader("Set-Cookie");

        mvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(RefreshCookie.NAME, cookieValue(setCookie)))
                        .header("Origin", "http://evil.example"))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutClearsCookie() throws Exception {
        String setCookie = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody()))
                .andReturn().getResponse().getHeader("Set-Cookie");

        var res = mvc.perform(post("/api/v1/auth/logout")
                        .cookie(new Cookie(RefreshCookie.NAME, cookieValue(setCookie)))
                        .header("Authorization", "Bearer " + accessOf()))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        String cleared = res.getHeader("Set-Cookie");
        assertNotNull(cleared);
        assertTrue(cleared.contains("Max-Age=0") || cleared.contains("max-age=0"), cleared);
    }

    private String accessOf() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody()))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = mapper.readTree(body);
        return node.path("data").path("accessToken").asText();
    }

    private static String cookieValue(String setCookie) {
        String v = setCookie.substring(setCookie.indexOf('=') + 1);
        int semi = v.indexOf(';');
        return semi < 0 ? v : v.substring(0, semi);
    }
}
