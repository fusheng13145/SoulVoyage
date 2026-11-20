package com.leyon.soul.common.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 客户端 IP 解析回归（2026-11-20 安全审查 B-07）。
 *
 * <p>缺陷原状：直接取 X-Forwarded-For 第一段（nginx $proxy_add_x_forwarded_for 会把客户端自填值放最前），
 * 攻击者换一个 XFF 就换一个登录失败计数器，5 次锁定被绕过（已用真实请求复现换 XFF 后正确口令仍 200）。
 */
class ClientIpResolverTest {

    private static MockHttpServletRequest req(String remote, String realIp, String xff) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(remote);
        if (realIp != null) r.addHeader("X-Real-IP", realIp);
        if (xff != null) r.addHeader("X-Forwarded-For", xff);
        return r;
    }

    @Test
    void publicDirectClientCannotSpoofHeaders() {
        ClientIpResolver r = new ClientIpResolver(true);
        // 公网直连：任何代理头都可能是客户端自己写的
        assertEquals("203.0.113.9", r.resolve(req("203.0.113.9", "1.2.3.4", "1.2.3.4")));
        assertEquals("203.0.113.9", r.resolve(req("203.0.113.9", null, "1.2.3.4, 5.6.7.8")));
    }

    @Test
    void behindProxyUsesRealIpNotFirstForwardedSegment() {
        ClientIpResolver r = new ClientIpResolver(true);
        // nginx 同机：remoteAddr 是 127.0.0.1，客户端伪造的 XFF 首段不得生效
        assertEquals("198.51.100.7", r.resolve(req("127.0.0.1", "198.51.100.7", "6.6.6.6, 198.51.100.7")));
    }

    @Test
    void fallsBackToLastForwardedSegmentWhenNoRealIp() {
        ClientIpResolver r = new ClientIpResolver(true);
        assertEquals("198.51.100.7", r.resolve(req("172.18.0.5", null, "6.6.6.6, 198.51.100.7")));
    }

    @Test
    void garbageHeaderFallsBackToRemoteAddr() {
        ClientIpResolver r = new ClientIpResolver(true);
        // 非 IP 字面量（含大小写字母/长度异常）不采信，避免把任意字符串塞进 Redis key 或审计日志
        assertEquals("10.0.0.8", r.resolve(req("10.0.0.8", "not-an-ip; drop table", null)));
        assertEquals("10.0.0.8", r.resolve(req("10.0.0.8", "1.2.3.4:5678", null)));
        assertEquals("10.0.0.8", r.resolve(req("10.0.0.8", "999.1.1.1", null)));
    }

    @Test
    void trustDisabledAlwaysUsesRemoteAddr() {
        ClientIpResolver r = new ClientIpResolver(false);
        assertEquals("10.0.0.8", r.resolve(req("10.0.0.8", "198.51.100.7", "198.51.100.7")));
    }

    @Test
    void privateRangesRecognised() {
        assertTrue(ClientIpResolver.isLocalOrPrivate("127.0.0.1"));
        assertTrue(ClientIpResolver.isLocalOrPrivate("::1"));
        assertTrue(ClientIpResolver.isLocalOrPrivate("192.168.31.7"));
        assertTrue(ClientIpResolver.isLocalOrPrivate("172.16.0.1"));
        assertTrue(ClientIpResolver.isLocalOrPrivate("172.31.255.254"));
        assertTrue(ClientIpResolver.isLocalOrPrivate("fd00::1"));
        assertFalse(ClientIpResolver.isLocalOrPrivate("172.32.0.1"));
        assertFalse(ClientIpResolver.isLocalOrPrivate("8.8.8.8"));
    }

    @Test
    void ipv6LiteralAccepted() {
        assertTrue(ClientIpResolver.looksLikeIp("2001:db8::1"));
        assertFalse(ClientIpResolver.looksLikeIp("2001:db8::1::2::3"));
        assertFalse(ClientIpResolver.looksLikeIp(""));
        assertFalse(ClientIpResolver.looksLikeIp(null));
    }
}
