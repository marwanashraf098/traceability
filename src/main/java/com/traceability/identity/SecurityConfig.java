package com.traceability.identity;

import com.traceability.tenancy.TenantContextFilter;
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
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/v1/auth/signup",
                    "/api/v1/auth/login",
                    "/api/v1/auth/refresh",
                    "/api/v1/health"
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
}
