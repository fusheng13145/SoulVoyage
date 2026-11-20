package com.leyon.soul.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.soul.common.api.ApiResponse;
import com.leyon.soul.common.api.ErrorCode;
import com.leyon.soul.common.exception.BizException;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Bearer 解析 + Redis 降级闸门。
 *
 * <p>2026-11-20 审查 04 项：原实现里 Redis 读失败会把"已吊销"当成"没吊销"，
 * 于是故障窗口内旧令牌仍可造成实际副作用。现在对**写操作与管理端读** fail-closed（503/5002），
 * 普通读接口保持降级（鉴权不因缓存抖动整体失败），判定规则见 {@link DegradationPolicy}。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;
    private final RedisGuard guard;
    private final ObjectMapper objectMapper;

    @Value("${soul.security.fail-closed-on-redis-degraded:true}")
    private boolean failClosedOnDegraded;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest req,
                                    @NonNull HttpServletResponse res,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims c = jwt.verify(header.substring(7), JwtService.TYPE_ACCESS);
                AuthPrincipal p = new AuthPrincipal(Long.parseLong(c.getSubject()),
                        c.get("role", String.class));
                var auth = new UsernamePasswordAuthenticationToken(p, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + p.role())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (BizException e) {
                SecurityContextHolder.clearContext();
                // 交由受保护路径的 entry point 返回 1001
            }
        }

        if (failClosedOnDegraded
                && SecurityContextHolder.getContext().getAuthentication() != null
                && guard.degradedRecently()
                && DegradationPolicy.requiresTrustedRevocation(req.getMethod(), req.getRequestURI())) {
            res.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(res.getWriter(), ApiResponse.fail(ErrorCode.DEPENDENCY_UNAVAILABLE, null));
            return;
        }

        chain.doFilter(req, res);
    }
}
