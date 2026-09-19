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
                .claims(Map.of("role", role, "typ", type))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** 校验并返回 Claims；失败抛 BizException */
    public Claims verify(String token, String expectType) {
        Claims c;
        try {
            c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (Exception e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, null);
        }
        if (!expectType.equals(c.get("typ", String.class))) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "token 类型不符");
        }
        if (Boolean.TRUE.equals(redis.hasKey(BLACKLIST_PREFIX + c.getId()))) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "token 已失效");
        }
        return c;
    }

    /** 登出：拉黑 access + refresh 直至自然过期 */
    public void revoke(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            Duration remain = Duration.between(Instant.now(), c.getExpiration().toInstant());
            if (remain.toSeconds() > 0) {
                redis.opsForValue().set(BLACKLIST_PREFIX + c.getId(), "1", remain);
            }
        } catch (Exception ignored) {
            // 无效 token 无需拉黑
        }
    }
}
