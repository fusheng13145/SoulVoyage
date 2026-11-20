package com.leyon.soul;

import com.leyon.soul.auth.AuthService;
import com.leyon.soul.common.api.ErrorCode;
import com.leyon.soul.common.exception.BizException;
import com.leyon.soul.dto.AuthDtos.LoginReq;
import com.leyon.soul.dto.AuthDtos.RegisterReq;
import com.leyon.soul.dto.AuthDtos.TokenResp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 入口限流回归（2026-11-20 安全审查 S3 补强）。
 *
 * <p>用独立的小阈值上下文验证三件事：
 * ① 注册按 IP 限流（造号口子不能无限用）；② refresh 按 IP 限流；③ 单账号跨 IP 失败累计（换 IP 也绕不过）。
 * 阈值用 @TestPropertySource 压低，避免和主测试上下文的 488 个用例互相影响。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "soul.security.register-limit.per-hour=3",
        "soul.security.register-limit.per-day=10",
        "soul.security.refresh-limit.per-minute=1",
        "soul.security.login-lock.account-max-fail=3",
        "soul.security.login-lock.account-hot-delay=200ms"
})
class AuthRateLimitTest {

    private static final String PWD = "SvFixture!2026";

    @Autowired AuthService auth;

    private static String uniq(String prefix) {
        return prefix + System.nanoTime();
    }

    @Test
    void registerIsLimitedPerIp() {
        String ip = "198.51.100." + (System.nanoTime() % 200 + 1);
        for (int i = 0; i < 3; i++) {
            assertNotNull(auth.register(new RegisterReq(uniq("rl"), PWD, "限流"), ip).accessToken());
        }
        BizException e = assertThrows(BizException.class,
                () -> auth.register(new RegisterReq(uniq("rl"), PWD, "限流"), ip));
        assertEquals(ErrorCode.RATE_LIMITED, e.getErrorCode());
        // 换 IP 不受影响：说明限流确实按 IP 维度，而不是把注册整体关掉
        assertNotNull(auth.register(new RegisterReq(uniq("rl"), PWD, "限流"), "203.0.113.44").accessToken());
    }

    @Test
    void refreshIsLimitedPerIp() {
        String ip = "198.51.100.9";
        TokenResp t = auth.register(new RegisterReq(uniq("rlr"), PWD, "限流"), "203.0.113.45");
        assertNotNull(auth.refresh(t.refreshToken(), ip).accessToken());
        BizException e = assertThrows(BizException.class, () -> auth.refresh(t.refreshToken(), ip));
        assertEquals(ErrorCode.RATE_LIMITED, e.getErrorCode());
    }

    /**
     * 2026-11-20 取舍变更：账号维度不再硬锁（硬锁可被"多 IP 刷满"用来锁死他人账号），
     * 改为"热账号减速"——正确口令仍可登录，但每次尝试被固定延迟。
     */
    @Test
    void accountHotSlowsDownButStillAllowsOwner() {
        String user = uniq("rla");
        auth.register(new RegisterReq(user, PWD, "限流"), "203.0.113.46");
        for (int i = 0; i < 3; i++) {
            final String failIp = "198.51.100." + (10 + i);
            assertThrows(BizException.class,
                    () -> auth.login(new LoginReq(user, "WrongPwd!9"), failIp));
        }
        long t0 = System.nanoTime();
        TokenResp ok = auth.login(new LoginReq(user, PWD), "198.51.100.99");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertNotNull(ok.accessToken(), "热账号不应把本人挡在门外");
        assertTrue(elapsedMs >= 180, "应触发减速（>=180ms），实际 " + elapsedMs + "ms");
    }
}
