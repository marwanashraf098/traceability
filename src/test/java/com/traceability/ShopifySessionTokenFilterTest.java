package com.traceability;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.traceability.integrations.shopify.ShopifySessionTokenFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ShopifySessionTokenFilter.
 *
 * Tests are written directly against the filter (MockHttpServletRequest/Response + mock
 * FilterChain) — NOT via MockMvc standaloneSetup, which dispatches to the servlet even
 * when the filter does not call chain.doFilter(), masking early-termination rejections.
 *
 * The load-bearing cross-tenant isolation and authority tests live in EmbeddedIntegrationTest.
 */
class ShopifySessionTokenFilterTest {

    static final String SHOP       = "test-store.myshopify.com";
    static final String CLIENT_ID  = "test-client-id";
    // 34 bytes = 272 bits — above Nimbus HS256 minimum of 256 bits.
    static final String SECRET     = "test-shopify-secret-32-bytes-abc!!";
    static final String WRONG_SEC  = "wrong-secret-also-32-bytes-xyz!!x";

    JdbcTemplate mockJdbc;
    ShopifySessionTokenFilter filter;
    UUID tenantId;

    @BeforeEach
    void setup() {
        tenantId = UUID.randomUUID();
        mockJdbc = mock(JdbcTemplate.class);
        when(mockJdbc.queryForObject(any(String.class), eq(UUID.class), any()))
                .thenReturn(tenantId);
        filter = new ShopifySessionTokenFilter(SECRET, CLIENT_ID, mockJdbc);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    // ── Pass-through cases ────────────────────────────────────────────────────

    @Test
    void validToken_stringAud_passes() throws Exception {
        var req   = embeddedGet(makeToken(SHOP, CLIENT_ID, SECRET, 120, false));
        var res   = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .anyMatch(a -> a.getAuthority().equals("ROLE_SHOPIFY_EMBEDDED"));
    }

    @Test
    void validToken_arrayAud_passes() throws Exception {
        var req   = embeddedGet(makeToken(SHOP, CLIENT_ID, SECRET, 120, true));
        var res   = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void nonEmbeddedPath_filterSkips_noRejection() throws Exception {
        // shouldNotFilter() = true for /api/v1/orders → chain.doFilter() called, no auth set.
        var req = new MockHttpServletRequest("GET", "/api/v1/orders");
        req.addHeader("Authorization", "Bearer " + makeToken(SHOP, CLIENT_ID, SECRET, 120, false));
        var res   = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        verify(mockJdbc, never()).queryForObject(any(String.class), eq(UUID.class), any());
    }

    // ── Rejection cases — filter writes 401 and does NOT call chain ───────────

    @Test
    void noAuthorizationHeader_401() throws Exception {
        var req   = new MockHttpServletRequest("GET", "/api/v1/embedded/test");
        var res   = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void authHeaderNoBearerPrefix_401() throws Exception {
        var req   = new MockHttpServletRequest("GET", "/api/v1/embedded/test");
        req.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        var res   = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void tamperedSignature_401() throws Exception {
        String token   = makeToken(SHOP, CLIENT_ID, SECRET, 120, false);
        int lastDot    = token.lastIndexOf('.');
        String sig     = token.substring(lastDot + 1);
        String flipped = sig.substring(0, sig.length() - 1)
                + (sig.charAt(sig.length() - 1) == 'A' ? 'B' : 'A');
        String tampered = token.substring(0, lastDot + 1) + flipped;

        var res   = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(embeddedGet(tampered), res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void wrongSecret_401() throws Exception {
        var res   = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(embeddedGet(makeToken(SHOP, CLIENT_ID, WRONG_SEC, 120, false)), res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void expiredToken_401() throws Exception {
        // exp = 2 minutes ago — well past the 10s clock skew.
        var res   = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(embeddedGet(makeToken(SHOP, CLIENT_ID, SECRET, -120, false)), res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void wrongAud_stringForm_401() throws Exception {
        var res   = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(embeddedGet(makeToken(SHOP, "wrong-client", SECRET, 120, false)), res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void wrongAud_arrayForm_401() throws Exception {
        var res   = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(embeddedGet(makeToken(SHOP, "other-client", SECRET, 120, true)), res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void algNone_plainJwt_401() throws Exception {
        // PlainJWT (alg=none) — SignedJWT.parse() throws ParseException → caught → 401.
        PlainJWT plain = new PlainJWT(new JWTClaimsSet.Builder()
                .issuer("https://" + SHOP + "/admin")
                .claim("dest", "https://" + SHOP)
                .audience(CLIENT_ID)
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .build());
        var res   = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(embeddedGet(plain.serialize()), res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void algRs256_headerCheck_401() throws Exception {
        // Manually crafted JWT with RS256 header — alg check (Step 2) rejects before verify().
        String header  = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload = b64("{\"iss\":\"https://" + SHOP + "/admin\","
                + "\"dest\":\"https://" + SHOP + "\","
                + "\"aud\":\"" + CLIENT_ID + "\","
                + "\"sub\":\"gid://shopify/Staff/1\","
                + "\"exp\":" + (System.currentTimeMillis() / 1000 + 300) + "}");
        String sig = b64("fakesig");
        var res   = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(embeddedGet(header + "." + payload + "." + sig), res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void issNotMyshopify_401() throws Exception {
        var res   = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(embeddedGet(makeTokenRaw(
                "https://evil.example.com/admin", "https://" + SHOP, CLIENT_ID, SECRET, 120)),
                res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void issHostNeqDestHost_401() throws Exception {
        // iss and dest refer to different shops — cross-check must reject.
        var res   = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(embeddedGet(makeTokenRaw(
                "https://shop-a.myshopify.com/admin", "https://shop-b.myshopify.com",
                CLIENT_ID, SECRET, 120)),
                res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void destNotMyshopify_401() throws Exception {
        var res   = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(embeddedGet(makeTokenRaw(
                "https://" + SHOP + "/admin", "https://evil.example.com",
                CLIENT_ID, SECRET, 120)),
                res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void unknownShop_nullTenant_401() throws Exception {
        when(mockJdbc.queryForObject(any(String.class), eq(UUID.class), any()))
                .thenReturn(null);
        var res   = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(embeddedGet(makeToken(SHOP, CLIENT_ID, SECRET, 120, false)), res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void dbException_failClosed_401() throws Exception {
        when(mockJdbc.queryForObject(any(String.class), eq(UUID.class), any()))
                .thenThrow(new RuntimeException("DB down"));
        var res   = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(embeddedGet(makeToken(SHOP, CLIENT_ID, SECRET, 120, false)), res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void unparseable_401() throws Exception {
        var res   = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(embeddedGet("not.a.valid.jwt.at.all"), res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    // ── Token builders ────────────────────────────────────────────────────────

    /**
     * Build a well-formed Shopify session token.
     *
     * @param expOffsetSeconds positive = future exp, negative = already expired
     * @param arrayAud         true = aud as List, false = aud as plain string
     */
    public static String makeToken(String shop, String clientId, String secret,
                                   int expOffsetSeconds, boolean arrayAud) throws Exception {
        Date now = new Date();
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder()
                .issuer("https://" + shop + "/admin")
                .claim("dest", "https://" + shop)
                .subject("gid://shopify/Staff/12345")
                .issueTime(now)
                .notBeforeTime(now)
                .expirationTime(new Date(now.getTime() + expOffsetSeconds * 1000L));
        if (arrayAud) b.audience(List.of(clientId));
        else          b.audience(clientId);

        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), b.build());
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private String makeTokenRaw(String iss, String dest, String clientId,
                                String secret, int expOffsetSeconds) throws Exception {
        Date now = new Date();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(iss)
                .claim("dest", dest)
                .audience(clientId)
                .subject("gid://shopify/Staff/1")
                .issueTime(now)
                .notBeforeTime(now)
                .expirationTime(new Date(now.getTime() + expOffsetSeconds * 1000L))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private MockHttpServletRequest embeddedGet(String bearerToken) {
        var req = new MockHttpServletRequest("GET", "/api/v1/embedded/test");
        req.addHeader("Authorization", "Bearer " + bearerToken);
        return req;
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
