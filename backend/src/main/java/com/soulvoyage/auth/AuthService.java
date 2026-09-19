package com.soulvoyage.auth;

import com.soulvoyage.audit.AuditService;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import com.soulvoyage.dto.AuthDtos.*;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String FAIL_PREFIX = "jwt:login-fail:";

    private final UserRepository userRepo;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;
    private final StringRedisTemplate redis;

    @Value("${soulvoyage.security.login-lock.max-fail}")
    private int maxFail;

    @Value("${soulvoyage.security.login-lock.window}")
    private Duration lockWindow;

    @Transactional
    public TokenResp register(RegisterReq req, String ip) {
        userRepo.findByUsernameAndDeletedAtIsNull(req.username()).ifPresent(u -> {
            throw new BizException(ErrorCode.USERNAME_EXISTS);
        });
        UserEntity u = new UserEntity();
        u.setUsername(req.username());
        u.setNickname(req.nickname() == null || req.nickname().isBlank() ? req.username() : req.nickname());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setAgreedPolicyAt(Instant.now());
        u = userRepo.save(u);
        audit.record(u.getId(), "REGISTER", "user:" + u.getId(), ip);
        return tokenResp(u);
    }

    /** S3：失败计数达阈值即锁定（窗口内连正确密码也拒）；S2：status=3 冷静期允许登录以撤回注销 */
    public TokenResp login(LoginReq req, String ip) {
        String failKey = FAIL_PREFIX + req.username();
        String cnt = redis.opsForValue().get(failKey);
        if (cnt != null && Integer.parseInt(cnt) >= maxFail) {
            throw new BizException(ErrorCode.LOGIN_FAILED, "失败次数过多，请稍后再试");
        }
        UserEntity u = userRepo.findByUsernameAndDeletedAtIsNull(req.username()).orElse(null);
        boolean ok = u != null && (u.getStatus() == 1 || u.getStatus() == 3)
                && encoder.matches(req.password(), u.getPasswordHash());
        if (!ok) {
            Long n = redis.opsForValue().increment(failKey);
            if (n != null && n == 1) redis.expire(failKey, lockWindow);
            audit.record(u == null ? null : u.getId(), "LOGIN_FAILED",
                    "user:" + req.username(), ip);
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }
        redis.delete(failKey);
        audit.record(u.getId(), "LOGIN", "user:" + u.getId(), ip);
        return tokenResp(u);
    }

    /** S3：refresh 轮换（旧令牌用后即废）+ 重用检测（旧令牌再现判失窃，epoch 吊销全部会话） */
    public TokenResp refresh(String refreshToken) {
        Claims c = jwt.parseForRotation(refreshToken);   // 签名/类型/epoch 校验，不查黑名单
        long userId = Long.parseLong(c.getSubject());
        if (jwt.isBlacklisted(c.getId())) {
            jwt.revokeAll(userId);
            audit.record(userId, "TOKEN_REUSE_REVOKE", "user:" + userId, null);
            throw new BizException(ErrorCode.UNAUTHORIZED, "登录状态异常，请重新登录");
        }
        jwt.blacklist(c);
        UserEntity u = userRepo.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED));
        return tokenResp(u);
    }

    public void logout(String accessToken, String refreshToken) {
        jwt.revoke(accessToken);
        if (refreshToken != null && !refreshToken.isBlank()) jwt.revoke(refreshToken);
    }

    private TokenResp tokenResp(UserEntity u) {
        return new TokenResp(jwt.issueAccessToken(u.getId(), u.getRole()),
                jwt.issueRefreshToken(u.getId(), u.getRole()),
                jwt.accessTtl().toSeconds(),
                u.getId(), u.getNickname(), u.getRole(),
                u.getStatus() == 3);
    }
}
