package com.traceability.integrations.shopify;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.traceability.identity.CustomUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Authenticates embedded Shopify App Bridge requests via session tokens.
 *
 * Session tokens are short-lived HS256 JWTs signed with the app's Shopify client secret
 * (NOT the Traced JWT secret). Claims: iss=https://{shop}/admin, dest=https://{shop},
 * aud=clientId, sub=userGID, exp/nbf/iat, jti.
 *
 * Security invariants:
 *   1. alg is checked BEFORE verify() — rejects alg=none and non-HS256 up front.
 *   2. Signature is verified BEFORE any claim is trusted.
 *   3. All rejection paths write 401 directly (setStatus, NOT sendError) and return
 *      without calling chain.doFilter() — no fallthrough to an authenticated state.
 *   4. shop domain → resolve_tenant_by_shop_domain SECURITY DEFINER → tenant_id.
 *      Returns null for unknown shops → 401. Fail closed on any DB exception.
 *   5. Only fires for /api/v1/embedded/** (shouldNotFilter guard).
 *   6. If SecurityContext is already set (e.g. Traced JWT), passes through — the
 *      EmbeddedController's @PreAuthorize("hasRole('SHOPIFY_EMBEDDED')") then gates it (403).
 *
 * Filter order in SecurityConfig:
 *   JwtAuthenticationFilter → ShopifySessionTokenFilter → TenantContextFilter
 */
public class ShopifySessionTokenFilter extends OncePerRequestFilter {

    private static final Pattern SHOP_DOMAIN_RE =
            Pattern.compile("^[a-z0-9][a-z0-9-]*\\.myshopify\\.com$");
    private static final int CLOCK_SKEW_SECONDS = 10;

    private final byte[] clientSecretBytes;
    private final String clientId;
    private final JdbcTemplate jdbc;

    public ShopifySessionTokenFilter(String clientSecret, String clientId, JdbcTemplate jdbc) {
        this.clientSecretBytes = clientSecret.getBytes(StandardCharsets.UTF_8);
        this.clientId          = clientId;
        this.jdbc              = jdbc;
    }

    /** Only applies to the embedded API namespace. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/embedded/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        // Already authenticated (e.g. Traced JWT) — pass through.
        // EmbeddedController's @PreAuthorize handles authority gating → 403.
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            reject(response);
            return;
        }

        try {
            String rawToken = header.substring(7);

            // Step 1 — parse (structural only, no claims trusted yet).
            // SignedJWT.parse() throws ParseException for alg=none (PlainJWT) tokens.
            SignedJWT jwt = SignedJWT.parse(rawToken);

            // Step 2 — algorithm check BEFORE verify(). Rejects non-HS256 (alg=none caught at Step 1).
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
                reject(response);
                return;
            }

            // Step 3 — signature verification with the Shopify client secret.
            // MACVerifier only performs HMAC operations; cannot accept RS256.
            MACVerifier verifier = new MACVerifier(clientSecretBytes);
            if (!jwt.verify(verifier)) {
                reject(response);
                return;
            }

            // Step 4 — claims are now trusted.
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            long nowMs  = System.currentTimeMillis();
            long skewMs = CLOCK_SKEW_SECONDS * 1000L;

            Date exp = claims.getExpirationTime();
            if (exp == null || exp.getTime() < nowMs - skewMs) {
                reject(response);
                return;
            }

            Date nbf = claims.getNotBeforeTime();
            if (nbf != null && nbf.getTime() > nowMs + skewMs) {
                reject(response);
                return;
            }

            Date iat = claims.getIssueTime();
            if (iat != null && iat.getTime() > nowMs + skewMs) {
                reject(response);
                return;
            }

            // aud — Nimbus always normalises aud to List<String> for both string and array forms.
            List<String> aud = claims.getAudience();
            if (aud == null || !aud.contains(clientId)) {
                reject(response);
                return;
            }

            // iss — must be https://{shop}.myshopify.com/admin
            String issHost = extractIssDomain(claims.getIssuer());
            if (issHost == null) {
                reject(response);
                return;
            }

            // dest — must be https://{shop}.myshopify.com
            String destHost = extractDestDomain((String) claims.getClaim("dest"));
            if (destHost == null) {
                reject(response);
                return;
            }

            // iss host must equal dest host — reject split-shop tokens.
            if (!issHost.equals(destHost)) {
                reject(response);
                return;
            }

            // shop domain → tenant (SECURITY DEFINER, no GUC needed).
            UUID tenantId;
            try {
                tenantId = jdbc.queryForObject(
                        "SELECT resolve_tenant_by_shop_domain(?)", UUID.class, destHost);
            } catch (Exception e) {
                reject(response);   // fail closed on any DB error
                return;
            }
            if (tenantId == null) {
                reject(response);   // unknown shop — no default tenant fallback
                return;
            }

            // Synthetic userId from Shopify GID — deterministic, never written to DB.
            String sub = claims.getSubject();
            byte[] subBytes = (sub != null ? sub : "shopify-unknown-sub")
                    .getBytes(StandardCharsets.UTF_8);
            UUID syntheticUserId = UUID.nameUUIDFromBytes(subBytes);

            CustomUserDetails principal =
                    new CustomUserDetails(syntheticUserId, tenantId, "SHOPIFY_EMBEDDED");
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    principal, null, principal.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);

            chain.doFilter(request, response);

        } catch (Exception e) {
            // Catch-all: parse errors, JOSE exceptions, etc. → fail closed.
            reject(response);
        }
    }

    private String extractIssDomain(String iss) {
        if (iss == null) return null;
        try {
            URI uri = URI.create(iss);
            String host = uri.getHost();
            if (host == null || !SHOP_DOMAIN_RE.matcher(host).matches()) return null;
            if (!"/admin".equals(uri.getPath())) return null;
            return host;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractDestDomain(String dest) {
        if (dest == null) return null;
        try {
            URI uri = URI.create(dest);
            String host = uri.getHost();
            if (host == null || !SHOP_DOMAIN_RE.matcher(host).matches()) return null;
            return host;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Writes a 401 directly — NOT via sendError() to avoid the ERROR-dispatch chain
     * documented in CLAUDE.md (sendError → ERROR dispatch → SecurityContext cleared → second 401).
     */
    private void reject(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) return;
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Unauthorized\"}");
    }
}
