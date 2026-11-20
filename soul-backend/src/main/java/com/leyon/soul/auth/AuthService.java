package com.leyon.soul.auth;

import com.leyon.soul.audit.AuditService;
import com.leyon.soul.common.api.ErrorCode;
import com.leyon.soul.common.exception.BizException;
import com.leyon.soul.common.state.SlidingWindowLimiter;
import com.leyon.soul.domain.user.UserEntity;
import com.leyon.soul.domain.user.UserRepository;
import com.leyon.soul.dto.AuthDtos.*;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String FAIL_PREFIX = "jwt:login-fail:";
    /** 单账号跨 IP 的总失败计数（换 IP 绕不过的那层） */
    private static final String ACCOUNT_FAIL_PREFIX = "jwt:login-fail-account:";
    private static final String REGISTER_LIMIT_PREFIX = "jwt:register:";
    private static final String REFRESH_LIMIT_PREFIX = "jwt:refresh:";

    private final UserRepository userRepo;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;
    private final StringRedisTemplate redis;
    private final RedisGuard guard;
    private final SlidingWindowLimiter windowLimiter;

    @Value("${soul.security.login-lock.max-fail}")
    private int maxFail;

    @Value("${soul.security.login-lock.window}")
    private Duration lockWindow;

    /** 单账号跨 IP 失败上限：远超正常人手误，又能在换 IP 爆破时兜底 */
    @Value("${soul.security.login-lock.account-max-fail:50}")
    private int accountMaxFail;

    @Value("${soul.security.register-limit.per-hour:5}")
    private int registerPerHour;

    @Value("${soul.security.register-limit.per-day:20}")
    private int registerPerDay;

    @Value("${soul.security.refresh-limit.per-minute:30}")
    private int refreshPerMinute;

    /** 热账号每次登录尝试的固定延迟（替代硬锁，见 authenticate 内的取舍说明） */
    @Value("${soul.security.login-lock.account-hot-delay:500ms}")
    private Duration accountHotDelay;


    @Transactional
    public TokenResp register(RegisterReq req, String ip) {
        return tokenResp(registerUser(req, ip));
    }

    /** 建号本体（M14 拆出）：小程序端"用微信绑定票注册新账号"要的是同一个校验与留痕，不另立一套 */
    @Transactional
    public UserEntity registerUser(RegisterReq req, String ip) {
        // 限流放在 registerUser 里而不是 register 外面：微信建号也走这里，否则那条口子完全没上限
        admitRegister(ip);
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
        log.info("register ok userId={} username={} ip={}", u.getId(), u.getUsername(), ip);
        return u;
    }

    public TokenResp login(LoginReq req, String ip) {
        return tokenResp(authenticate(req, ip));
    }

    /** S3：失败计数达阈值即锁定（窗口内连正确密码也拒）；S2：status=3 冷静期允许登录以撤回注销 */
    public UserEntity authenticate(LoginReq req, String ip) {
        // B-07：计数按 username+IP 双维度——攻击者无法用单一用户名锁死他人账号
        String failKey = FAIL_PREFIX + req.username() + ":" + ip;
        String cnt = guard.read("login.fail.count", () -> redis.opsForValue().get(failKey));
        if (cnt != null && Integer.parseInt(cnt) >= maxFail) {
            log.warn("login blocked by lockout username={} ip={} fails={}", req.username(), ip, cnt);
            throw new BizException(ErrorCode.LOGIN_FAILED, "失败次数过多，请稍后再试");
        }
        // 跨 IP 兜底：username+IP 维度挡不住"换 IP 继续爆"，这里再按单账号累计。
        // 2026-11-20 审查取舍：**不再硬锁**——硬锁会被攻击者用 10 个 IP 刷满后把受害者本人挡在门外（轻量 DoS）。
        // 改为"热账号减速"：达到阈值后每次尝试固定延迟，爆破速率被压到约 2 次/秒，
        // 而受害者拿着正确口令仍能进（只是慢 0.5 秒），空口令猜中也没意义——因为减速发生在校验之前。
        String accKey = ACCOUNT_FAIL_PREFIX + req.username();
        String acc = guard.read("login.fail.account", () -> redis.opsForValue().get(accKey));
        if (acc != null && parseCount(acc) >= accountMaxFail) {
            slowDownHotAccount(req.username(), ip, acc);
        }
        UserEntity u = userRepo.findByUsernameAndDeletedAtIsNull(req.username()).orElse(null);
        boolean ok = u != null && (u.getStatus() == 1 || u.getStatus() == 3)
                && encoder.matches(req.password(), u.getPasswordHash());
        if (!ok) {
            // B-16：Redis 故障时降级为"不锁定"（登录不被缓存拖死），由 sv.redis.degraded 指标告警
            guard.writeBestEffort("login.fail.count", () -> {
                Long n = redis.opsForValue().increment(failKey);
                if (n != null && n == 1) redis.expire(failKey, lockWindow);
            });
            guard.writeBestEffort("login.fail.account", () -> {
                Long n = redis.opsForValue().increment(accKey);
                if (n != null && n == 1) redis.expire(accKey, lockWindow);
            });
            audit.record(u == null ? null : u.getId(), "LOGIN_FAILED",
                    "user:" + req.username(), ip);
            log.warn("login failed userId={} username={} ip={} reason={}", u == null ? "-" : u.getId(),
                    req.username(), ip, u == null ? "NO_USER" : "BAD_PASSWORD_OR_STATUS");
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }
        guard.writeBestEffort("login.fail.count", () -> redis.delete(failKey));
        guard.writeBestEffort("login.fail.account", () -> redis.delete(accKey));
        audit.record(u.getId(), "LOGIN", "user:" + u.getId(), ip);
        log.info("login ok userId={} role={} ip={}", u.getId(), u.getRole(), ip);
        return u;
    }

    /** 兼容旧签名（AuthHardeningTest 直接调用）：内部调用不带 IP，仅做令牌轮换语义校验 */
    public TokenResp refresh(String refreshToken) {
        return refresh(refreshToken, null);
    }

    /** S3：refresh 轮换（旧令牌用后即废）+ 重用检测（旧令牌再现判失窃，epoch 吊销全部会话） */
    public TokenResp refresh(String refreshToken, String ip) {
        admitRefresh(ip);
        Claims c = jwt.parseForRotation(refreshToken);   // 签名/类型/epoch 校验，不查黑名单
        long userId = Long.parseLong(c.getSubject());
        if (jwt.isBlacklisted(c.getId())) {
            jwt.revokeAll(userId);
            audit.record(userId, "TOKEN_REUSE_REVOKE", "user:" + userId, ip);
            log.warn("refresh token reused userId={} -> all sessions revoked ip={}", userId, ip);
            throw new BizException(ErrorCode.UNAUTHORIZED, "登录状态异常，请重新登录");
        }
        jwt.blacklist(c);
        UserEntity u = userRepo.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED));
        return tokenResp(u);
    }

    private void admitRegister(String ip) {
        if (ip == null || ip.isBlank()) return;   // 内部无 IP 语义的调用不参与入口限流
        if (!limitOk(REGISTER_LIMIT_PREFIX + "h:" + ip, registerPerHour, Duration.ofHours(1), ip, "register")) {
            throw new BizException(ErrorCode.RATE_LIMITED, "注册太频繁了，请稍后再试");
        }
        if (!limitOk(REGISTER_LIMIT_PREFIX + "d:" + ip, registerPerDay, Duration.ofDays(1), ip, "register")) {
            throw new BizException(ErrorCode.RATE_LIMITED, "注册太频繁了，请稍后再试");
        }
    }

    private void admitRefresh(String ip) {
        if (ip == null || ip.isBlank()) return;
        if (!limitOk(REFRESH_LIMIT_PREFIX + ip, refreshPerMinute, Duration.ofMinutes(1), ip, "refresh")) {
            throw new BizException(ErrorCode.RATE_LIMITED, "操作太频繁，请稍后再试");
        }
    }

    /**
     * 计数读取/写入都可能撞上 Redis 故障：这里明确选择 fail-open（放行 + 告警日志），
     * 因为限流是"附加防线"，让它把注册/刷新整体打挂得不偿失；真正的鉴权仍由 JWT 与 DB 把关。
     */
    private boolean limitOk(String key, int limit, Duration window, String ip, String tag) {
        try {
            boolean ok = windowLimiter.tryAcquire(key, limit, window);
            if (!ok) log.warn("{} rate limited ip={} limit={}/{}", tag, ip, limit, window);
            return ok;
        } catch (DataAccessException e) {
            log.warn("{} limiter degraded (redis down) ip={}: {}", tag, ip, e.getMessage());
            return true;
        }
    }

    private static int parseCount(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 热账号减速：固定延迟而非拒绝。
     * 用虚拟线程时 Thread.sleep 只占用载体很短时间，不会拖垮请求线程池；
     * 若中途被中断则恢复中断标记并提前返回（不吞掉中断语义）。
     */
    private void slowDownHotAccount(String username, String ip, String fails) {
        long ms = Math.max(0, accountHotDelay.toMillis());
        if (ms == 0) return;
        log.warn("login slowed (account hot) username={} ip={} fails={} delay={}ms", username, ip, fails, ms);
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void logout(String accessToken, String refreshToken) {
        jwt.logout(accessToken, refreshToken);
    }

    /**
     * 当前用户档案（/auth/me 的组装逻辑）。
     * 放在服务层而不是 Controller：ArchitectureTest 禁止 Controller 依赖任何 *Repository，
     * 这里顺手把这条老违规一并清掉（它原来是冻结基线里的既有条目）。
     */
    public MeResp me(long userId) {
        UserEntity u = userRepo.findByIdAndDeletedAtIsNull(userId).orElseThrow();
        return new MeResp(
                u.getId(), u.getUsername(), u.getNickname(), u.getRole(),
                u.getCrisisState(),
                u.getAgreedPolicyAt() == null ? "" : u.getAgreedPolicyAt().toString(),
                u.getPolicyVersion() == null ? "" : u.getPolicyVersion(),
                u.getStatus().intValue(),
                u.getDeletionRequestedAt() == null ? "" : u.getDeletionRequestedAt().toString(),
                // M14：只回"绑没绑"，不回 openid——那是服务端与微信之间的标识，不是给用户看的
                u.getWxOpenid() != null,
                // createdAt 由库侧默认值供给（列上 insertable=false），读回来可能是 null
                u.getCreatedAt() == null ? "" : u.getCreatedAt().toString());
    }

    public TokenResp tokenResp(UserEntity u) {
        return new TokenResp(jwt.issueAccessToken(u.getId(), u.getRole()),
                jwt.issueRefreshToken(u.getId(), u.getRole()),
                jwt.accessTtl().toSeconds(),
                u.getId(), u.getNickname(), u.getRole(),
                u.getStatus() == 3);
    }
}
