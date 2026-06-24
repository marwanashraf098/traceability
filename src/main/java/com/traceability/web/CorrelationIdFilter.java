package com.traceability.web;

import io.sentry.Sentry;
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
 * Generates or propagates a correlation ID for every HTTP request.
 *
 * The ID is taken from the incoming {@code X-Request-Id} header when present
 * (so an upstream proxy or API gateway can supply it), or generated as a UUID
 * otherwise. It is:
 *   - placed in SLF4J MDC as {@code requestId} (appears in log lines)
 *   - echoed back on the response as {@code X-Request-Id}
 *   - attached to the Sentry scope as tag {@code request_id} (no-op when disabled)
 *
 * MDC is always cleared in the {@code finally} block — it does not leak between
 * requests even when a downstream filter or controller throws.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_KEY           = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().replace("-", "");
        }

        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        if (Sentry.isEnabled()) {
            final String rid = requestId;
            Sentry.configureScope(scope -> scope.setTag("request_id", rid));
        }

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
