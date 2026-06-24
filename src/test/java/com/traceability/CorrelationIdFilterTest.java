package com.traceability;

import com.traceability.web.CorrelationIdFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for CorrelationIdFilter — no Spring context needed.
 * Calls the public doFilter() entry point (doFilterInternal is protected).
 *
 *   cf1 — X-Request-Id header present: used as-is, set in MDC + response header
 *   cf2 — No header: auto-generated UUID placed in MDC + response header
 *   cf3 — MDC cleared after filter completes normally
 *   cf4 — MDC cleared even when a downstream filter throws
 */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    // ── cf1: existing X-Request-Id is propagated ──────────────────────────────

    @Test
    void cf1_existingRequestId_usedVerbatim() throws Exception {
        MockHttpServletRequest  req  = new MockHttpServletRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        req.addHeader(CorrelationIdFilter.REQUEST_ID_HEADER, "trace-abc-123");

        AtomicReference<String> capturedMdc = new AtomicReference<>();
        filter.doFilter(req, resp, (rq, rs) -> capturedMdc.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

        assertThat(capturedMdc.get()).isEqualTo("trace-abc-123");
        assertThat(resp.getHeader(CorrelationIdFilter.REQUEST_ID_HEADER)).isEqualTo("trace-abc-123");
    }

    // ── cf2: absent header → UUID generated ──────────────────────────────────

    @Test
    void cf2_noHeader_uuidGeneratedAndSet() throws Exception {
        MockHttpServletRequest  req  = new MockHttpServletRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        AtomicReference<String> capturedMdc = new AtomicReference<>();
        filter.doFilter(req, resp, (rq, rs) -> capturedMdc.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

        assertThat(capturedMdc.get()).isNotNull().isNotBlank().hasSizeGreaterThan(8);
        assertThat(resp.getHeader(CorrelationIdFilter.REQUEST_ID_HEADER))
            .isEqualTo(capturedMdc.get());
    }

    // ── cf3: MDC cleared after normal completion ──────────────────────────────

    @Test
    void cf3_mdcClearedAfterNormalCompletion() throws Exception {
        MockHttpServletRequest  req  = new MockHttpServletRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        req.addHeader(CorrelationIdFilter.REQUEST_ID_HEADER, "clean-up-test");

        filter.doFilter(req, resp, new MockFilterChain());

        // MDC must be empty after the filter returns — no cross-request leak.
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    // ── cf4: MDC cleared even when downstream throws ──────────────────────────

    @Test
    void cf4_mdcClearedEvenOnDownstreamException() {
        MockHttpServletRequest  req  = new MockHttpServletRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        req.addHeader(CorrelationIdFilter.REQUEST_ID_HEADER, "exception-test");

        FilterChain throwingChain = (rq, rs) -> { throw new RuntimeException("downstream failure"); };

        assertThatThrownBy(() -> filter.doFilter(req, resp, throwingChain))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("downstream failure");

        // MDC cleared via finally block — no requestId left dangling.
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
