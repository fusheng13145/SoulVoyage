package com.leyon.soul.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-20 审查 04 项：Redis 降级时"哪些请求必须 fail-closed"的规则回归 */
class DegradationPolicyTest {

    @Test
    void writesRequireTrustedRevocation() {
        assertTrue(DegradationPolicy.requiresTrustedRevocation("POST", "/api/v1/diaries"));
        assertTrue(DegradationPolicy.requiresTrustedRevocation("PUT", "/api/v1/diaries/9"));
        assertTrue(DegradationPolicy.requiresTrustedRevocation("DELETE", "/api/v1/plans/1"));
        assertTrue(DegradationPolicy.requiresTrustedRevocation("PATCH", "/api/v1/account/password"));
    }

    @Test
    void plainReadsStayAvailable() {
        assertFalse(DegradationPolicy.requiresTrustedRevocation("GET", "/api/v1/diaries"));
        assertFalse(DegradationPolicy.requiresTrustedRevocation("HEAD", "/api/v1/tasks"));
        assertFalse(DegradationPolicy.requiresTrustedRevocation("OPTIONS", "/api/v1/tasks"));
    }

    @Test
    void adminReadsFailClosed() {
        assertTrue(DegradationPolicy.requiresTrustedRevocation("GET", "/api/v1/admin/users"));
        assertTrue(DegradationPolicy.requiresTrustedRevocation("GET", "/api/v1/admin/ops/audit"));
    }

    @Test
    void authEndpointsAndProbesAreExempt() {
        // 登录/刷新自己用 writeRequired 给出明确 503，不在这里重复拦
        assertFalse(DegradationPolicy.requiresTrustedRevocation("POST", "/api/v1/auth/refresh"));
        assertFalse(DegradationPolicy.requiresTrustedRevocation("POST", "/api/v1/auth/logout"));
        assertFalse(DegradationPolicy.requiresTrustedRevocation("GET", "/actuator/health"));
        assertFalse(DegradationPolicy.requiresTrustedRevocation("GET", "/error"));
    }
}
