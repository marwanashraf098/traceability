package com.traceability.identity;

import com.traceability.integrations.shopify.ShopifySessionTokenFilter;
import com.traceability.tenancy.TenantContextFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Stateless JWT security.
 *
 * Filter order per request:
 *   JwtAuthenticationFilter   → validates Bearer token, populates SecurityContextHolder
 *   TenantContextFilter        → reads tenant_id from principal, sets TenantContext
 *   (all other Spring Security filters)
 *   (controllers / services / repositories)
 *
 * Role matrix is enforced via @PreAuthorize on individual endpoints.
 * Roles: OWNER > MANAGER > WORKER. Add @PreAuthorize("hasRole('OWNER')") etc.
 * as endpoints are built in subsequent days.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtService jwtService;
    // Resolved at startup from the CORS_ALLOWED_ORIGINS env var.
    // The old corsSource() passed the literal "${CORS_ALLOWED_ORIGINS:}" string directly to
    // setAllowedOriginPatterns() — Spring @Value is not evaluated inside List.of() literals,
    // so the unresolved placeholder was used as a pattern that never matched any origin.
    private final String corsAllowedOrigins;

    public SecurityConfig(JwtService jwtService,
                          @Value("${CORS_ALLOWED_ORIGINS:}") String corsAllowedOrigins) {
        this.jwtService         = jwtService;
        this.corsAllowedOrigins = corsAllowedOrigins;
    }

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            JdbcTemplate jdbc,
            @Value("${shopify.client-secret}") String shopifyClientSecret,
            @Value("${shopify.client-id}")     String shopifyClientId) throws Exception {

        JwtAuthenticationFilter   jwtFilter     = new JwtAuthenticationFilter(jwtService);
        ShopifySessionTokenFilter shopifyFilter =
                new ShopifySessionTokenFilter(shopifyClientSecret, shopifyClientId, jdbc);
        TenantContextFilter       tenantFilter  = new TenantContextFilter();

        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(c -> c.configurationSource(corsSource()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                // Use setStatus (not sendError) to avoid Servlet error-dispatch,
                // which would clear the SecurityContext and return 401 instead of 403.
                // Same root cause as ApiExceptionHandler (see CLAUDE.md).
                .accessDeniedHandler((req, res, denied) ->
                        res.setStatus(HttpServletResponse.SC_FORBIDDEN)))
            .authorizeHttpRequests(auth -> auth
                // Spring Security 6.1+ applies the filter chain to FORWARD and ERROR
                // dispatcher types by default (shouldFilterAllDispatcherTypes = true).
                // Permit them unconditionally so that:
                //   FORWARD — SpaController's "forward:/index.html" is never re-checked
                //   ERROR   — sendError(404) dispatching to /error can't become a 401
                // This is the correct Spring Security 6 fix for the sendError→ERROR→401
                // pattern documented in CLAUDE.md (same class of bug as ResponseStatusException).
                .dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR).permitAll()
                .requestMatchers(
                    // SPA shell — browser must load the app before any login or API call.
                    // Data is protected at /api/** level; serving the shell is always public.
                    "/", "/index.html", "/assets/**", "/favicon.ico",
                    // Embedded Shopify App Bridge shell — served in Shopify admin iframe.
                    // The shell itself is public; API calls from the shell are authenticated
                    // by ShopifySessionTokenFilter via Authorization: Bearer <session-token>.
                    "/embedded", "/embedded.html",
                    // Public marketing + legal pages (Shopify review requires direct access).
                    // SpaController forwards these to /index.html; Spring Security evaluates
                    // the original path before forwarding, so they must be listed here.
                    "/privacy", "/terms",
                    // App SPA routes that must be reachable on direct navigation / refresh.
                    "/login", "/signup",
                    // Auth endpoints
                    "/api/v1/auth/signup",
                    "/api/v1/auth/login",
                    "/api/v1/auth/refresh",
                    "/api/v1/health",
                    // Actuator health — Docker healthcheck calls this with no token.
                    // Only /health is exposed (see management config in application.yml).
                    "/actuator/health",
                    // Webhook receivers: authenticated by secret header / HMAC, not JWT
                    "/api/v1/webhooks/bosta",
                    // Shopify webhooks: authenticated by HMAC-SHA256 over raw body + app client secret.
                    // Includes orders/*, products/*, app/uninstalled and GDPR compliance endpoints.
                    "/webhooks/shopify/**",
                    // Shopify OAuth: callback is authenticated by HMAC+state, not JWT.
                    // Install is the Path-2 merchant-initiated entry point (also HMAC-only).
                    // Initiate (/api/v1/shopify/oauth/initiate) stays under anyRequest().authenticated().
                    "/auth/shopify/install",
                    "/auth/shopify/callback",
                    // Magic-link consume: authenticated by the token hash + consume_magic_link DEFINER.
                    "/auth/magic"
                ).permitAll()
                .anyRequest().authenticated())
            // Filter order: JWT auth → Shopify session-token auth → tenant GUC setter.
            .addFilterBefore(jwtFilter,     UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(shopifyFilter,  JwtAuthenticationFilter.class)
            .addFilterAfter(tenantFilter,   ShopifySessionTokenFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // argon2id with Spring Security defaults (19 MiB, 2 iterations, 1 thread).
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    public CorsConfigurationSource corsSource() {
        // Shopify admin origin patterns — required for the install/embedded-open flow.
        // When the merchant's browser navigates to our app from the Shopify admin
        // (https://admin.shopify.com) or from a store admin (https://{store}.myshopify.com),
        // the browser sends "Origin: <admin-host>" on the form POST.  Spring Security's
        // CorsFilter rejects non-matching origins before the request reaches the controller,
        // returning 403 (which nginx surfaces as 500 after Spring's error dispatch).
        // Both the new admin URL (admin.shopify.com) and the legacy per-store admin URL
        // (*.myshopify.com) must be listed — Shopify uses both depending on store age.
        List<String> patterns = new ArrayList<>(List.of(
            "http://localhost:[*]",           // dev — Vite dev server
            "https://admin.shopify.com",      // Shopify admin (new unified URL)
            "https://*.myshopify.com"         // per-store admin (legacy + dev stores)
        ));
        // Optional: additional origin from env (e.g. a custom domain fronting the SPA).
        if (!corsAllowedOrigins.isBlank()) {
            patterns.add(corsAllowedOrigins);
        }

        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOriginPatterns(patterns);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }
}
