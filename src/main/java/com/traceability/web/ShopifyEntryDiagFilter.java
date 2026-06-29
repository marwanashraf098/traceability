package com.traceability.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 * Temporary diagnostic filter — logs every detail of requests hitting / and /auth/shopify/**
 * so we can compare what Shopify actually sends vs our curl tests.
 *
 * REMOVE after the Shopify install flow is confirmed working end-to-end.
 *
 * Runs at HIGHEST_PRECEDENCE + 1 (after CorrelationIdFilter adds requestId to MDC)
 * so every log line carries the correlation ID.  com.traceability is already at DEBUG
 * in application.yml — no config change needed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ShopifyEntryDiagFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ShopifyEntryDiagFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !"/".equals(path) && !path.startsWith("/auth/shopify");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            logRequest(request);
        } catch (Exception e) {
            log.warn("[SHOPIFY-DIAG] failed to log request: {}", e.getMessage());
        }

        chain.doFilter(request, response);

        log.debug("[SHOPIFY-DIAG] response status={}", response.getStatus());
    }

    private void logRequest(HttpServletRequest req) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("\n[SHOPIFY-DIAG] ==================== INCOMING REQUEST ====================");
        sb.append("\n  ").append(req.getMethod()).append(" ").append(req.getRequestURI());

        String qs = req.getQueryString();
        if (qs != null) sb.append("?").append(qs);

        sb.append("\n  RemoteAddr : ").append(req.getRemoteAddr());
        sb.append("\n  ContentType: ").append(req.getContentType());

        // All headers (critical: Host, X-Forwarded-*, CF-*)
        sb.append("\n  --- Headers ---");
        for (String name : Collections.list(req.getHeaderNames())) {
            sb.append("\n    ").append(name).append(": ").append(req.getHeader(name));
        }

        // All params — getParameterMap() merges URL query string + form body
        Map<String, String[]> params = req.getParameterMap();
        if (!params.isEmpty()) {
            sb.append("\n  --- Params (URL + body merged) ---");
            params.forEach((k, vals) ->
                sb.append("\n    ").append(k).append("=").append(String.join(", ", vals)));
        } else {
            sb.append("\n  --- Params: (none) ---");
        }

        sb.append("\n[SHOPIFY-DIAG] =========================================================");
        log.debug("{}", sb);
    }
}
