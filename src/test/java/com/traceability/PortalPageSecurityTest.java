package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.identity.SecurityConfig;
import com.traceability.integrations.shopify.ShopifyOAuthService;
import com.traceability.web.SpaController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Returns portal Step 4e-B — the only SecurityConfig change: GET /portal.html is public.
 * The page itself comes from src/test/resources/static/portal.html (a stand-in for the
 * Vite build output, which is never committed).
 */
@WebMvcTest(SpaController.class)
@Import(SecurityConfig.class)
class PortalPageSecurityTest {

    @Autowired MockMvc mvc;
    @MockBean JwtService          jwtService;
    @MockBean JdbcTemplate        jdbcTemplate;
    @MockBean ShopifyOAuthService oauthService;

    @Test
    void portalPage_isServedUnauthenticated() throws Exception {
        mvc.perform(get("/portal.html"))
           .andExpect(status().isOk())
           .andExpect(content().string(containsString("<div id=\"root\"></div>")));
    }

    @Test
    void onlyGet_isOpened() throws Exception {
        mvc.perform(post("/portal.html")).andExpect(status().isUnauthorized());
    }

    @Test
    void unrelatedProtectedEndpoints_stillRequireAuth() throws Exception {
        mvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/return-requests")).andExpect(status().isUnauthorized());
        // Other dotted static-looking paths are not opened by the portal rule.
        mvc.perform(get("/portal2.html")).andExpect(status().isUnauthorized());
    }
}
