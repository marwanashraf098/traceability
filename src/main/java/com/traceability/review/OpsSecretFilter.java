package com.traceability.review;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

/**
 * Review mode S7 — runs OpsSecretGuard on every /api/v1/ops/** request BEFORE Spring MVC reads the
 * request body (a servlet filter, after the security chain, before the DispatcherServlet). An
 * unconfigured secret answers 404 and a missing / wrong X-Ops-Secret 403 without the body ever
 * being parsed — a malformed body can't turn a refusal into a 400 / 500. The controller keeps its
 * own guard.check as a second line. Answers directly (never sendError: no error dispatch).
 */
@Component
public class OpsSecretFilter extends OncePerRequestFilter {

    private final OpsSecretGuard guard;

    /**
     * Its own guard over the same property, not the OpsSecretGuard bean: servlet filters are part of
     * every @WebMvcTest slice, plain @Components are not, so depending on the bean would break each
     * slice's context.
     */
    public OpsSecretFilter(@Value("${traced.ops-secret:}") String secret) {
        this.guard = new OpsSecretGuard(secret);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/v1/ops/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            guard.check(request.getHeader(OpsSecretGuard.HEADER));
        } catch (ResponseStatusException refused) {
            response.setStatus(refused.getStatusCode().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(refused.getStatusCode().value() == 404 ? "{\"code\":\"NOT_FOUND\"}" : "{\"code\":\"FORBIDDEN\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
