package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.identity.SecurityConfig;
import com.traceability.web.SpaController;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.Test;

/**
 * Verifies SpaController routing in isolation (no DB, no Flyway).
 * Imports SecurityConfig so the real permitAll rules take effect.
 */
@WebMvcTest(SpaController.class)
@Import(SecurityConfig.class)
class SpaRoutingTest {

    @Autowired MockMvc mvc;
    @MockBean JwtService    jwtService;
    @MockBean JdbcTemplate  jdbcTemplate;   // required by SecurityConfig.filterChain()

    /** Public SPA routes listed in SecurityConfig permitAll must forward to index.html. */
    @ParameterizedTest(name = "GET {0} -> forward {1}")
    @CsvSource({
        "/privacy, /index.html",
        "/terms,   /index.html",
        "/login,   /index.html",
        "/signup,  /index.html",
    })
    void publicSpaRoutesForwardToIndex(String path, String expected) throws Exception {
        mvc.perform(get(path.trim()))
           .andExpect(status().isOk())
           .andExpect(forwardedUrl(expected.trim()));
    }

    /**
     * Authenticated app routes must serve the SPA shell (forward to index.html) on
     * browser refresh with NO Authorization header — the page is public, data is not.
     * Regression guard: these routes were missing from permitAll and returned 401 before
     * SpaController could run.
     */
    @ParameterizedTest(name = "GET {0} unauthenticated -> 200 shell (not 401)")
    @ValueSource(strings = {
        "/overview",
        "/orders",
        "/orders/a1b2c3d4-e5f6-7890-abcd-ef1234567890",
        "/catalog",
        "/receiving",
        "/fulfill",
        "/lookup",
        "/returns",
        "/exceptions",
        "/inventory",
        "/connections",
        "/onboarding",
        "/settings",
        "/users",
    })
    void authenticatedAppRoutesBrowserRefreshReturnsShell(String path) throws Exception {
        mvc.perform(get(path))
           .andExpect(status().isOk())
           .andExpect(forwardedUrl("/index.html"));
    }

    /** API data routes must remain protected: no auth → 401. Shell-serving fix must NOT open /api/**. */
    @Test
    void apiDataRouteWithoutAuthIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/orders"))
           .andExpect(status().isUnauthorized());
    }

    /** GET / with no Shopify params → standalone SPA (forward to index.html). */
    @Test
    void rootWithNoParamsForwardsToIndexHtml() throws Exception {
        mvc.perform(get("/"))
           .andExpect(status().isOk())
           .andExpect(forwardedUrl("/index.html"));
    }

    // Forward (not redirect): App Bridge CDN fires replaceState(location.href) on init.
    // A redirect to /embedded would cause App Bridge to push 'app:/embedded' to the admin
    // frame, changing the admin URL to ...apps/{handle}/embedded — which Shopify 404s.
    // With a forward the browser URL stays at / so App Bridge pushes 'app:/' → no extra path.

    /** GET /?host=... (Shopify embedded load) → forward to embedded.html, URL stays at /. */
    @Test
    void rootWithHostParamForwardsToEmbeddedHtml() throws Exception {
        mvc.perform(get("/?host=YWRtaW4uc2hvcGlmeS5jb20v&shop=test.myshopify.com&embedded=1"))
           .andExpect(status().isOk())
           .andExpect(forwardedUrl("/embedded.html"));
    }

    // shop= without host= means Shopify is initiating an install/OAuth request (no iframe yet).
    // Forwarding to embedded.html here would load App Bridge in a top-level browser context
    // where it can't communicate with any parent frame — OAuth never runs, and the merchant
    // then sees Shopify's own 404 when opening the app (app not installed).
    // Redirect to /auth/shopify/install so HMAC is verified and OAuth consent starts.

    /** GET /?shop=... without host (Shopify install flow) → redirect to /auth/shopify/install. */
    @Test
    void rootWithShopParamOnlyRedirectsToInstall() throws Exception {
        mvc.perform(get("/?shop=test.myshopify.com&hmac=abc&timestamp=123"))
           .andExpect(status().is3xxRedirection())
           .andExpect(redirectedUrl("/auth/shopify/install?shop=test.myshopify.com&hmac=abc&timestamp=123"));
    }

    // Shopify sends a POST (form submission) to the app URL when initiating install/reinstall
    // from the Partner Dashboard or admin.  Without a POST handler Spring throws
    // HttpRequestMethodNotSupportedException → 405, breaking dashboard-initiated installs.
    // CSRF is globally disabled in SecurityConfig, so Shopify's unauthenticated POST is allowed.

    /** POST /?shop=...&host=... with params in URL (our curl test) → PRG redirect to GET /. */
    @Test
    void rootPostWithHostUrlParamsRedirectsToGet() throws Exception {
        mvc.perform(post("/?shop=test.myshopify.com&host=abc123&embedded=1"))
           .andExpect(status().is3xxRedirection())
           .andExpect(redirectedUrl("/?shop=test.myshopify.com&host=abc123&embedded=1"));
    }

    // These two tests reproduce the REAL Shopify request: form body, not URL params, plus
    // Origin header from the merchant browser (admin.shopify.com or *.myshopify.com).
    // Before the CORS fix the CorsFilter rejected both with 403 — the controller was never reached.

    /** POST / form body + Shopify Origin (admin.shopify.com) → PRG redirect to GET /. */
    @Test
    void rootPostWithHostFormBodyAndShopifyOriginRedirectsToGet() throws Exception {
        mvc.perform(post("/")
                .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                .content("shop=test.myshopify.com&host=abc123&embedded=1")
                .header("Origin", "https://admin.shopify.com"))
           .andExpect(status().is3xxRedirection());
    }

    /** POST / form body + myshopify.com Origin (legacy per-store admin) → redirect to install. */
    @Test
    void rootPostInstallFormBodyMyshopifyOriginRedirectsToInstall() throws Exception {
        mvc.perform(post("/")
                .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                .content("shop=test.myshopify.com&hmac=abc&timestamp=123")
                .header("Origin", "https://test.myshopify.com"))
           .andExpect(status().is3xxRedirection())
           .andExpect(result -> {
               String loc = result.getResponse().getHeader("Location");
               if (loc == null || !loc.contains("/auth/shopify/install"))
                   throw new AssertionError("Expected redirect to /auth/shopify/install, got: " + loc);
               if (!loc.contains("shop=test.myshopify.com"))
                   throw new AssertionError("shop param missing from redirect: " + loc);
           });
    }

    /** POST /?shop=... without host with URL params (Shopify install POST) → redirect to /auth/shopify/install. */
    @Test
    void rootPostWithShopOnlyRedirectsToInstall() throws Exception {
        mvc.perform(post("/?shop=test.myshopify.com&hmac=abc&timestamp=123"))
           .andExpect(status().is3xxRedirection())
           .andExpect(redirectedUrl("/auth/shopify/install?shop=test.myshopify.com&hmac=abc&timestamp=123"));
    }

    /** /embedded must forward to embedded.html, NOT index.html. */
    @Test
    void embeddedRouteForwardsToEmbeddedHtml() throws Exception {
        mvc.perform(get("/embedded"))
           .andExpect(status().isOk())
           .andExpect(forwardedUrl("/embedded.html"));
    }

    /**
     * Static asset paths must NOT be intercepted by SpaController.
     * In @WebMvcTest there is no ResourceHttpRequestHandler, so the response
     * is 404 (no handler) — the critical check is that forwardedUrl is NOT /index.html.
     */
    @ParameterizedTest(name = "GET {0} not forwarded to index.html")
    @ValueSource(strings = {
        "/assets/index-BUe68oxv.js",
        "/assets/index-OREN78OI.css",
        "/assets/logo.svg",
        "/favicon.ico",
    })
    void assetPathsDoNotHitSpaController(String path) throws Exception {
        String forwarded = mvc.perform(get(path))
            .andReturn().getResponse().getForwardedUrl();
        if ("/index.html".equals(forwarded)) {
            throw new AssertionError(path + " was incorrectly forwarded to index.html by SpaController");
        }
    }
}
