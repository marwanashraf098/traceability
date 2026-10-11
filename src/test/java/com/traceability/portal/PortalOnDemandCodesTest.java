package com.traceability.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P4c — the lookup API's stable codes for the portal (P4d renders them): TOO_OLD {date} and UNAVAILABLE
 * stay 404 (neither says the typed order exists), BUSY is 503 with Retry-After: 60, and every other miss
 * is today's generic 404 with only the message.
 */
class PortalOnDemandCodesTest {

    private ResponseEntity<Map<String, Object>> answer(PortalService.LookupResult r) {
        PortalService portal = mock(PortalService.class);
        when(portal.lookup(any(), any(), any(), any())).thenReturn(Optional.of(r));
        PortalController c = new PortalController(portal, new ObjectMapper(), null);
        return c.lookup("thesnouts", new PortalController.LookupRequest("#1047", "01012345678"), new MockHttpServletRequest());
    }

    @Test
    void tooOld_404_withCodeAndDate() {
        ResponseEntity<Map<String, Object>> r = answer(new PortalService.LookupResult(PortalService.Outcome.TOO_OLD, Map.of("date", "2026-08-12")));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).containsEntry("code", "TOO_OLD").containsEntry("date", "2026-08-12").containsKey("message");
    }

    @Test
    void unavailable_404_withCode() {
        ResponseEntity<Map<String, Object>> r = answer(new PortalService.LookupResult(PortalService.Outcome.UNAVAILABLE, null));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).containsEntry("code", "UNAVAILABLE").doesNotContainKey("date");
    }

    @Test
    void busy_503_retryAfter60() {
        ResponseEntity<Map<String, Object>> r = answer(new PortalService.LookupResult(PortalService.Outcome.BUSY, null));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(r.getHeaders().getFirst("Retry-After")).isEqualTo("60");
        assertThat(r.getBody()).containsEntry("code", "BUSY");
    }

    @Test
    void everyOtherMiss_isTheGenericNotFound_unchanged() {
        ResponseEntity<Map<String, Object>> r = answer(new PortalService.LookupResult(PortalService.Outcome.NOT_FOUND, null));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).containsOnlyKeys("message")
            .containsEntry("message", "We couldn't find a returnable order with those details.");
    }
}
