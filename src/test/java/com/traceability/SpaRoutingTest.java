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
