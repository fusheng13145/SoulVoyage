package com.soulvoyage.auth;

import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class JwtService {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";
    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";
    /** S3：epoch 吊销——记一个时刻，iat 早于该时刻的令牌全部失效（注销/改密/重用检测时用） */
    private static final String EPOCH_PREFIX = "jwt:epoch:";

    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final StringRedisTemplate redis;

    public JwtService(@Value("${soulvoyage.security.jwt.secret}") String secret,
                      @Value("${soulvoyage.security.jwt.access-ttl}") Duration accessTtl,
                      @Value("${soulvoyage.security.jwt.refresh-ttl}") Duration refreshTtl,
                      StringRedisTemplate redis) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("SV_JWT_SECRET must be >= 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
        this.redis = redis;
    }

    public String issueAccessToken(long userId, String role) {
        return build(userId, role, TYPE_ACCESS, accessTtl);
    }

    public String issueRefreshToken(long userId, String role) {
        return build(userId, role, TYPE_REFRESH, refreshTtl);
    }

    public Duration accessTtl() { return accessTtl; }

    private String build(long userId, String role, String type, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .claims(Map.of("role", role, "typ", type, "iatms", now.toEpochMilli()))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** 校验并返回 Claims（含黑名单+epoch 检查）；失败抛 BizException */
    public Claims verify(String token, String expectType) {
        Claims c = parse(token, expectType);
        if (Boolean.TRUE.equals(redis.hasKey(BLACKLIST_PREFIX + c.getId()))) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "token 已失效");
        }
        return c;
    }

    /** refresh 轮换入口：只查签名/类型/epoch，不查黑名单——由调用方做重用检测 */
    public Claims parseForRotation(String refreshToken) {
        return parse(refreshToken, TYPE_REFRESH);
    }

    private Claims parse(String token, String expectType) {
        Claims c;
        try {
            c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (Exception e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, null);
        }
        if (!expectType.equals(c.get("typ", String.class))) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "token 类型不符");
        }
        // iatms：自填毫秒级签发时刻，消除标准 iat 秒级精度造成的"同秒错杀"窗口；旧令牌无此 claim 时退回 iat
        Object iatms = c.get("iatms");
        long issuedMillis = iatms instanceof Number n ? n.longValue()
                : (c.getIssuedAt() == null ? 0L : c.getIssuedAt().getTime());
        if (issuedMillis <= epochMillis(Long.parseLong(c.getSubject()))) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "登录状态已吊销，请重新登录");
        }
        return c;
    }

    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(BLACKLIST_PREFIX + jti));
    }

    /** 轮换：旧 refresh 用后即拉黑 */
    public void blacklist(Claims used) {
        Duration remain = Duration.between(Instant.now(), used.getExpiration().toInstant());
        if (remain.toSeconds() > 0) {
            redis.opsForValue().set(BLACKLIST_PREFIX + used.getId(), "1", remain);
        }
    }

    /** 全部吊销：以当前时刻为 epoch，iat 不晚于该时刻的令牌全部失效（注销/改密/重用检测时用） */
    public void revokeAll(long userId) {
        redis.opsForValue().set(EPOCH_PREFIX + userId,
                String.valueOf(Instant.now().toEpochMilli()), refreshTtl.plusDays(1));
    }

    private long epochMillis(long userId) {
        String v = redis.opsForValue().get(EPOCH_PREFIX + userId);
        return v == null ? 0L : Long.parseLong(v);
    }

    /** 登出：拉黑 access + refresh 直至自然过期 */
    public void revoke(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            blacklist(c);
        } catch (Exception ignored) {
            // 无效 token 无需拉黑
        }
    }
}
