package com.traceability.review;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Review mode S7 — OpsSecretFilter answers before anything reads the request body: an unset secret →
 * 404, a missing / wrong header → 403, the chain (and so the DispatcherServlet's body parsing) never
 * runs; the right header passes; any other path is untouched.
 */
class OpsSecretFilterTest {

    private static MockHttpServletRequest opsRequest(String header) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/ops/review-tenant");
        req.setContentType("application/json");
        req.setContent("{not json".getBytes());
        if (header != null) req.addHeader(OpsSecretGuard.HEADER, header);
        return req;
    }

    @Test
    void unsetSecret_404_chainNeverRuns() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse res = new MockHttpServletResponse();
        new OpsSecretFilter("").doFilter(opsRequest("anything"), res, chain);
        assertThat(res.getStatus()).isEqualTo(404);
        verifyNoInteractions(chain);
    }

    @Test
    void missingOrWrongHeader_403_chainNeverRuns() throws Exception {
        for (String header : new String[]{ null, "wrong" }) {
            FilterChain chain = mock(FilterChain.class);
            MockHttpServletResponse res = new MockHttpServletResponse();
            new OpsSecretFilter("s3cret-value").doFilter(opsRequest(header), res, chain);
            assertThat(res.getStatus()).as(String.valueOf(header)).isEqualTo(403);
            verifyNoInteractions(chain);
        }
    }

    @Test
    void rightHeader_passes() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = opsRequest("s3cret-value");
        MockHttpServletResponse res = new MockHttpServletResponse();
        new OpsSecretFilter("s3cret-value").doFilter(req, res, chain);
        verify(chain).doFilter(req, res);
    }

    @Test
    void otherPaths_untouched() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/me");
        MockHttpServletResponse res = new MockHttpServletResponse();
        new OpsSecretFilter("").doFilter(req, res, chain);
        verify(chain).doFilter(req, res);
    }
}
