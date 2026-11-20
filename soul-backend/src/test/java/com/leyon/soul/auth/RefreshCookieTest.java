package com.leyon.soul.auth;

import com.leyon.soul.common.exception.BizException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** refresh Cookie 载体本身的单测：MockMvc 会被 CORS 先拦掉，同源校验要在这里单测 */
class RefreshCookieTest {

    private final RefreshCookie cookie =
            new RefreshCookie(false, Duration.ofDays(7), "http://localhost:5173, https://soul.example");

    @Test
    void issueWritesHardenedCookie() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        cookie.issue(res, "rt-1");
        String header = res.getHeader("Set-Cookie");
        assertTrue(header.startsWith("sv_rt=rt-1"), header);
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("SameSite=Lax"));
        assertTrue(header.contains("Path=/api/v1/auth"));
        assertTrue(header.contains("Max-Age=604800"), header);
    }

    @Test
    void secureFlagFollowsConfig() {
        RefreshCookie secureCookie =
                new RefreshCookie(true, Duration.ofDays(7), "http://localhost:5173");
        MockHttpServletResponse res = new MockHttpServletResponse();
        secureCookie.issue(res, "rt-2");
        assertTrue(res.getHeader("Set-Cookie").contains("Secure"));
    }

    @Test
    void clearExpiresCookie() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        cookie.clear(res);
        String header = res.getHeader("Set-Cookie");
        assertTrue(header.contains("Max-Age=0"), header);
        assertTrue(header.contains("HttpOnly"));
    }

    @Test
    void readFindsCookieByName() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie("other", "x"), new Cookie(RefreshCookie.NAME, "rt-3"));
        assertEquals("rt-3", RefreshCookie.read(req));
        assertNull(RefreshCookie.read(new MockHttpServletRequest()));
    }

    @Test
    void sameOriginAllowsWhitelistedAndMissingOrigin() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Origin", "http://localhost:5173");
        assertDoesNotThrow(() -> cookie.assertSameOrigin(req));

        MockHttpServletRequest noOrigin = new MockHttpServletRequest();
        assertDoesNotThrow(() -> cookie.assertSameOrigin(noOrigin));
    }

    @Test
    void sameOriginRejectsForeignOrigin() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Origin", "http://evil.example");
        BizException e = assertThrows(BizException.class, () -> cookie.assertSameOrigin(req));
        assertTrue(e.getMessage().contains("跨站"), e.getMessage());
    }

    /** 未配置白名单时也不能放行任意 Origin（fail-closed） */
    @Test
    void emptyWhitelistRejectsAnyOrigin() {
        RefreshCookie strict = new RefreshCookie(false, Duration.ofDays(7), "");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Origin", "http://localhost:5173");
        assertThrows(BizException.class, () -> strict.assertSameOrigin(req));
    }
}
