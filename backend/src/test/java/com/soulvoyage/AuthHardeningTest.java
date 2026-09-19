package com.soulvoyage;

import com.soulvoyage.account.AccountService;
import com.soulvoyage.auth.AuthService;
import com.soulvoyage.auth.JwtService;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.dto.AuthDtos.LoginReq;
import com.soulvoyage.dto.AuthDtos.RegisterReq;
import com.soulvoyage.dto.AuthDtos.TokenResp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 下篇·S3 认证加固回归：登录失败锁定、refresh 轮换 + 重用检测（epoch 全端吊销）、
 * 注销/改密即时下线（epoch），均为服务层断言（HTTP 状态码映射在 GlobalExceptionHandler）。
 */
@SpringBootTest
@ActiveProfiles("test")
class AuthHardeningTest {

    private static final String PWD = "Passw0rd!2026";

    @Autowired AuthService auth;
    @Autowired JwtService jwt;
    @Autowired AccountService account;
    @Autowired StringRedisTemplate redis;
    @Value("${soulvoyage.security.login-lock.max-fail}") int maxFail;

    private String newUser() {
        return "auth" + System.nanoTime();
    }

    @Test
    void repeatedLoginFailuresLockAccount() throws Exception {
        String u = newUser();
        auth.register(new RegisterReq(u, PWD, "t"), "test");
        for (int i = 0; i < maxFail; i++) {
            assertThrows(BizException.class, () -> auth.login(new LoginReq(u, "WrongPwd!9"), "test"));
        }
        // 窗口内即使密码正确也拒绝（锁定生效）
        BizException locked = assertThrows(BizException.class, () -> auth.login(new LoginReq(u, PWD), "test"));
        assertTrue(locked.getMessage().contains("稍后再试"));
        redis.delete("jwt:login-fail:" + u);   // 清理，避免影响其他用例
        assertNotNull(auth.login(new LoginReq(u, PWD), "test").accessToken());
    }

    @Test
    void refreshRotationAndReuseRevokesAllSessions() {
        String u = newUser();
        TokenResp t1 = auth.register(new RegisterReq(u, PWD, "t"), "test");
        jwt.verify(t1.accessToken(), JwtService.TYPE_ACCESS);   // 基线可用

        TokenResp t2 = auth.refresh(t1.refreshToken());          // 轮换：旧 refresh 作废
        assertThrows(BizException.class, () -> auth.refresh(t1.refreshToken()));  // 重用 → 判失窃
        assertThrows(BizException.class, () -> auth.refresh(t2.refreshToken()));
        // 重用检测触发 epoch 吊销：此前签发的所有 access 全部失效
        assertThrows(BizException.class, () -> jwt.verify(t1.accessToken(), JwtService.TYPE_ACCESS));
        assertThrows(BizException.class, () -> jwt.verify(t2.accessToken(), JwtService.TYPE_ACCESS));
    }

    @Test
    void wrongTypeTokenRejected() {
        TokenResp t = auth.register(new RegisterReq(newUser(), PWD, "t"), "test");
        assertThrows(BizException.class, () -> jwt.verify(t.refreshToken(), JwtService.TYPE_ACCESS));
        assertThrows(BizException.class, () -> jwt.verify(t.accessToken(), JwtService.TYPE_REFRESH));
    }

    @Test
    void deletionRequestAndPasswordChangeRevokeLiveSessions() {
        String u = newUser();
        TokenResp t = auth.register(new RegisterReq(u, PWD, "t"), "test");
        long uid = t.userId();

        assertThrows(BizException.class, () -> account.requestDeletion(uid, "WrongPwd!9", "test"));
        jwt.verify(t.accessToken(), JwtService.TYPE_ACCESS);     // 复核失败不应误吊销

        account.requestDeletion(uid, PWD, "test");
        assertThrows(BizException.class, () -> jwt.verify(t.accessToken(), JwtService.TYPE_ACCESS));
        assertThrows(BizException.class, () -> jwt.verify(t.refreshToken(), JwtService.TYPE_REFRESH));

        // 冷静期内允许登录以撤回（S2×S3 联动）
        TokenResp t2 = auth.login(new LoginReq(u, PWD), "test");
        assertTrue(t2.deletionPending());
        account.cancelDeletion(uid, "test");

        account.changePassword(uid, PWD, "NewPassw0rd!2026", "test");
        assertThrows(BizException.class, () -> jwt.verify(t2.accessToken(), JwtService.TYPE_ACCESS));
    }
}
