package com.meterbill.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * ClassName:RequestTraceFilter
 * Package:com.meterbill.common.web
 * Description:
 *
 * @Author:lyp
 * @Create:2026/10/10 - 14:25
 * @Version: v1.0
 *
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();

        MDC.put(MDC_KEY,requestId);
        response.setHeader(HEADER,requestId);

        try {
            filterChain.doFilter(request,response);
        }finally {
            MDC.remove(MDC_KEY);
        }
    }
}
