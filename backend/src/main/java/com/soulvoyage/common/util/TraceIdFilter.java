package com.soulvoyage.common.util;

import jakarta.servlet.Filter;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 为每个请求生成 traceId，贯穿 HTTP → Task → Step → LLM（手册 §9 可观测） */
@Component
@Order(1)
public class TraceIdFilter implements Filter {

    @Override
    public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res,
                         jakarta.servlet.FilterChain chain) throws IOException, jakarta.servlet.ServletException {
        MDC.put("traceId", Ulid.next().substring(0, 12));
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove("traceId");
        }
    }
}
