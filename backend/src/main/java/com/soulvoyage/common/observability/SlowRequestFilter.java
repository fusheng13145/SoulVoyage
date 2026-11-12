package com.soulvoyage.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * H-05 慢请求采样过滤器：只测 /api/v1 请求耗时并交给记录器；
 * 不读 body、不读 query，避免把用户内容或参数带进可观测数据。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class SlowRequestFilter extends OncePerRequestFilter {

    private final SlowRequestRecorder recorder;
    private final long thresholdMs;

    public SlowRequestFilter(SlowRequestRecorder recorder,
                             @Value("${soulvoyage.observability.slow-request-ms:500}") long thresholdMs) {
        this.recorder = recorder;
        this.thresholdMs = thresholdMs;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/v1/")
                || uri.startsWith("/api/v1/admin/metrics/guardrails");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            recorder.record(request.getMethod(), request.getRequestURI(), response.getStatus(), ms, thresholdMs);
        }
    }
}
