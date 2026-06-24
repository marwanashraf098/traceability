package com.traceability.identity;

import com.traceability.tenancy.TenantContextFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
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

    public SecurityConfig(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService);
        TenantContextFilter tenantFilter  = new TenantContextFilter();

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
                .requestMatchers(
                    // SPA static assets — browser must be able to load the app before login
                    "/", "/index.html", "/assets/**", "/favicon.ico",
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
            .addFilterBefore(jwtFilter,   UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(tenantFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // argon2id with Spring Security defaults (19 MiB, 2 iterations, 1 thread).
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    public CorsConfigurationSource corsSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        // Dev: Vite default port. Production origins set via CORS_ALLOWED_ORIGINS env.
        cfg.setAllowedOriginPatterns(List.of(
            "http://localhost:[*]",
            "${CORS_ALLOWED_ORIGINS:}"
        ));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }
}
