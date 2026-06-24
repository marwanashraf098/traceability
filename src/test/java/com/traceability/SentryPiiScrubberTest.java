package com.traceability;

import com.traceability.web.SentryConfig;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the Sentry BeforeSendCallback — no Spring context, no network.
 *
 *   sp1 — PII fields are stripped from event extras
 *   sp2 — Non-PII fields in extras are preserved
 *   sp3 — PII fields accidentally set as tags are stripped
 *   sp4 — tenant_id from MDC is attached as tag
 *   sp5 — No MDC tenant_id → tag not set (no NPE)
 *   sp6 — Event with no extras/tags passes through without NPE
 */
class SentryPiiScrubberTest {

    private SentryOptions.BeforeSendCallback hook;
    private final Hint hint = new Hint();

    @BeforeEach
    void setUp() {
        hook = new SentryConfig().sentryBeforeSend();
        MDC.clear();
    }

    // ── sp1: PII stripped from extras ────────────────────────────────────────

    @Test
    void sp1_piiFieldsRemovedFromExtras() {
        SentryEvent event = new SentryEvent();
        Map<String, Object> extras = new HashMap<>();
        extras.put("phone",         "+201099887766");
        extras.put("customer_name", "Ahmed Ali");
        extras.put("email",         "ahmed@example.com");
        extras.put("address",       "123 Cairo St");
        extras.put("receiver",      Map.of("phone", "+201000000000"));
        event.setExtras(extras);

        SentryEvent result = hook.execute(event, hint);

        assertThat(result).isNotNull();
        Map<String, Object> out = result.getExtras();
        assertThat(out).doesNotContainKey("phone")
                       .doesNotContainKey("customer_name")
                       .doesNotContainKey("email")
                       .doesNotContainKey("address")
                       .doesNotContainKey("receiver");
    }

    // ── sp2: Non-PII extras preserved ────────────────────────────────────────

    @Test
    void sp2_nonPiiFieldsPreservedInExtras() {
        SentryEvent event = new SentryEvent();
        Map<String, Object> extras = new HashMap<>();
        extras.put("order_id",   "ORD-123");
        extras.put("tracking",   "AWB-ABC");
        extras.put("cod_amount", 250);
        event.setExtras(extras);

        SentryEvent result = hook.execute(event, hint);

        assertThat(result).isNotNull();
        Map<String, Object> out = result.getExtras();
        assertThat(out).containsEntry("order_id",   "ORD-123")
                       .containsEntry("tracking",   "AWB-ABC")
                       .containsEntry("cod_amount", 250);
    }

    // ── sp3: PII stripped from tags ───────────────────────────────────────────

    @Test
    void sp3_piiFieldsRemovedFromTags() {
        SentryEvent event = new SentryEvent();
        event.setTag("phone",       "+201099887766");
        event.setTag("fullName",    "Ahmed Ali");
        event.setTag("tenant_id",   "3fa85f64-5717-4562-b3fc-2c963f66afa6");
        event.setTag("environment", "production");

        SentryEvent result = hook.execute(event, hint);

        assertThat(result).isNotNull();
        Map<String, String> tags = result.getTags();
        assertThat(tags).doesNotContainKey("phone")
                        .doesNotContainKey("fullName")
                        .containsKey("tenant_id")
                        .containsKey("environment");
    }

    // ── sp4: tenant_id attached from MDC ─────────────────────────────────────

    @Test
    void sp4_tenantIdFromMdcAttachedAsTag() {
        MDC.put("tenantId", "3fa85f64-5717-4562-b3fc-2c963f66afa6");
        SentryEvent event = new SentryEvent();

        SentryEvent result = hook.execute(event, hint);

        assertThat(result).isNotNull();
        assertThat(result.getTags())
            .containsEntry("tenant_id", "3fa85f64-5717-4562-b3fc-2c963f66afa6");
    }

    // ── sp5: no MDC tenant_id → tag absent, no NPE ───────────────────────────

    @Test
    void sp5_noMdcTenantId_tagAbsentNoNpe() {
        SentryEvent event = new SentryEvent();

        SentryEvent result = hook.execute(event, hint);

        assertThat(result).isNotNull();
        Map<String, String> tags = result.getTags();
        if (tags != null) {
            assertThat(tags).doesNotContainKey("tenant_id");
        }
    }

    // ── sp6: null extras/tags → no NPE ───────────────────────────────────────

    @Test
    void sp6_nullExtrasAndTags_noNpe() {
        SentryEvent event = new SentryEvent();
        // extras and tags are null by default — no explicit setup

        assertThatCode(() -> hook.execute(event, hint)).doesNotThrowAnyException();
    }
}
