package com.traceability.review;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Check A (2026-10-10) — the Lookup-adjust repair endpoint sits under /api/v1/ops/, so the existing ops
 * gate covers it before Spring MVC: no TRACED_OPS_SECRET → 404, missing / wrong X-Ops-Secret → 403
 * (constant-time compare, OpsSecretGuard), right secret → through.
 */
class OpsRepairEndpointFilterTest {

    private static final String PATH = "/api/v1/ops/repair/lookup-adjust-2026-10-10/e785e5e4-2c5c-428e-afdd-d26d90754229";

    private MockHttpServletResponse call(String configured, String header) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", PATH);
        req.setQueryString("apply=true");
        if (header != null) req.addHeader(OpsSecretGuard.HEADER, header);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        new OpsSecretFilter(configured).doFilter(req, res, chain);
        lastChain = chain;
        return res;
    }

    private FilterChain lastChain;

    @Test
    void unsetSecret_404_neverReachesTheRepair() throws Exception {
        assertThat(call("", "anything").getStatus()).isEqualTo(404);
        verifyNoInteractions(lastChain);
    }

    @Test
    void missingOrWrongSecret_403_neverReachesTheRepair() throws Exception {
        assertThat(call("s3cret", null).getStatus()).isEqualTo(403);
        verifyNoInteractions(lastChain);
        assertThat(call("s3cret", "s3crex").getStatus()).isEqualTo(403);
        verifyNoInteractions(lastChain);
    }

    @Test
    void rightSecret_passesThrough() throws Exception {
        call("s3cret", "s3cret");
        verify(lastChain, times(1)).doFilter(any(), any());
    }
}
