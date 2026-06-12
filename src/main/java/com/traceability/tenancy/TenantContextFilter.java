package com.traceability.tenancy;

import com.traceability.identity.CustomUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads the authenticated principal set by {@code JwtAuthenticationFilter} and
 * populates {@link TenantContext} for the duration of the request.
 *
 * This filter runs after JWT validation (registered via SecurityConfig).
 * For unauthenticated requests (login, signup, health) the principal is absent,
 * TenantContext stays null, and any @Transactional call will run with no GUC set —
 * RLS will return zero rows, which is the correct safe default.
 *
 * The finally block guarantees TenantContext is cleared before the thread returns
 * to the pool, so no tenant state leaks between requests.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof CustomUserDetails details) {
                TenantContext.set(details.tenantId());
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
