package com.traceability;

import com.traceability.web.ShopifyEntryDiagFilter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ShopifyEntryDiagFilter logs requests to / and /auth/shopify/** — secrets in them (query
 * parameters id_token, hmac, session, code, state, signature; headers Authorization and
 * Cookie) must be "[redacted]" in the raw query string, the parameter list and the headers.
 */
@ExtendWith(OutputCaptureExtension.class)
class ShopifyEntryDiagFilterRedactionTest {

    private static final String[] SECRETS = {
        "SECRET-ID-TOKEN", "SECRET-HMAC", "SECRET-SESSION", "SECRET-CODE", "SECRET-STATE",
        "SECRET-SIGNATURE", "SECRET-BEARER", "SECRET-COOKIE", "SECRET-FORM-CODE",
    };

    @Test
    void secretsNeverReachTheLog_otherValuesStillDo(CapturedOutput output) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/auth/shopify/callback");
        req.setQueryString("shop=diag-shop.myshopify.com&id_token=SECRET-ID-TOKEN&hmac=SECRET-HMAC"
            + "&session=SECRET-SESSION&code=SECRET-CODE&state=SECRET-STATE&signature=SECRET-SIGNATURE"
            + "&timestamp=1700000000&host=YWRtaW4uc2hvcGlmeS5jb20");
        req.addParameter("shop", "diag-shop.myshopify.com");
        req.addParameter("id_token", "SECRET-ID-TOKEN");
        req.addParameter("hmac", "SECRET-HMAC");
        req.addParameter("session", "SECRET-SESSION");
        req.addParameter("code", "SECRET-CODE", "SECRET-FORM-CODE");
        req.addParameter("state", "SECRET-STATE");
        req.addParameter("signature", "SECRET-SIGNATURE");
        req.addParameter("timestamp", "1700000000");
        req.addHeader("Authorization", "Bearer SECRET-BEARER");
        req.setCookies(new Cookie("traced_refresh", "SECRET-COOKIE"));
        req.addHeader("Cookie", "traced_refresh=SECRET-COOKIE");
        req.addHeader("X-Shopify-Shop-Domain", "diag-shop.myshopify.com");

        new ShopifyEntryDiagFilter().doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        String log = output.getAll();
        assertThat(log).contains("[SHOPIFY-DIAG]");
        for (String secret : SECRETS) {
            assertThat(log).as(secret).doesNotContain(secret);
        }
        assertThat(log)
            .contains("id_token=[redacted]").contains("hmac=[redacted]").contains("session=[redacted]")
            .contains("code=[redacted]").contains("state=[redacted]").contains("signature=[redacted]")
            .contains("Authorization: [redacted]").contains("Cookie: [redacted]")
            // Non-sensitive values are still logged for diagnosis.
            .contains("shop=diag-shop.myshopify.com").contains("timestamp=1700000000")
            .contains("X-Shopify-Shop-Domain: diag-shop.myshopify.com");
    }

    @Test
    void encodedParameterNamesAreRedactedToo(CapturedOutput output) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/");
        req.setQueryString("id%5Ftoken=SECRET-ID-TOKEN&shop=x.myshopify.com");
        req.addParameter("id_token", "SECRET-ID-TOKEN");
        new ShopifyEntryDiagFilter().doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(output.getAll()).contains("[SHOPIFY-DIAG]").doesNotContain("SECRET-ID-TOKEN");
    }
}
