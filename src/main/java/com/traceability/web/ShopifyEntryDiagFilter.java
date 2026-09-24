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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Temporary diagnostic filter — logs every detail of requests hitting / and /auth/shopify/**
 * so we can compare what Shopify actually sends vs our curl tests.
 *
 * REMOVE after the Shopify install flow is confirmed working end-to-end.
 * TODO: remove after App Store approval.
 *
 * Secrets are redacted before logging: the query/body parameters id_token, hmac, session,
 * code, state and signature (in both the raw query string and the parameter list) and the
 * Authorization and Cookie headers are written as "[redacted]".
 *
 * Runs at HIGHEST_PRECEDENCE + 1 (after CorrelationIdFilter adds requestId to MDC)
 * so every log line carries the correlation ID.  com.traceability is already at DEBUG
 * in application.yml — no config change needed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ShopifyEntryDiagFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ShopifyEntryDiagFilter.class);

    static final String REDACTED = "[redacted]";
    static final Set<String> REDACTED_PARAMS = Set.of("id_token", "hmac", "session", "code", "state", "signature");
    static final Set<String> REDACTED_HEADERS = Set.of("authorization", "cookie");
    private static final Pattern QUERY_PAIR = Pattern.compile("([^&=]+)=([^&]*)");

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
        if (qs != null) sb.append("?").append(redactQuery(qs));

        sb.append("\n  RemoteAddr : ").append(req.getRemoteAddr());
        sb.append("\n  ContentType: ").append(req.getContentType());

        // All headers (critical: Host, X-Forwarded-*, CF-*)
        sb.append("\n  --- Headers ---");
        for (String name : Collections.list(req.getHeaderNames())) {
            String value = REDACTED_HEADERS.contains(name.toLowerCase(Locale.ROOT)) ? REDACTED : req.getHeader(name);
            sb.append("\n    ").append(name).append(": ").append(value);
        }

        // All params — getParameterMap() merges URL query string + form body
        Map<String, String[]> params = req.getParameterMap();
        if (!params.isEmpty()) {
            sb.append("\n  --- Params (URL + body merged) ---");
            params.forEach((k, vals) ->
                sb.append("\n    ").append(k).append("=")
                  .append(REDACTED_PARAMS.contains(k) ? REDACTED : String.join(", ", vals)));
        } else {
            sb.append("\n  --- Params: (none) ---");
        }

        sb.append("\n[SHOPIFY-DIAG] =========================================================");
        log.debug("{}", sb);
    }

    /** Replaces the value of every sensitive parameter in a raw query string. */
    static String redactQuery(String qs) {
        Matcher m = QUERY_PAIR.matcher(qs);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = java.net.URLDecoder.decode(m.group(1), java.nio.charset.StandardCharsets.UTF_8);
            String replacement = REDACTED_PARAMS.contains(key) ? m.group(1) + "=" + REDACTED : m.group(0);
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }
}
