package com.traceability.integrations.shopify;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Part D fix P1 (2026-11-08) — source guard on the increment adjust's transport. executeGraphQL() / postGraphQL()
 * wrap a Resilience4j retry that silently re-sends a timed-out body; with changeFromQuantity set, a re-send of an
 * adjust that landed answers STALE (Move S0) and used to double the +N. So:
 *   t1 — sendAdjust and its transport postAdjustOnce never call executeGraphQL / postGraphQL / Retry;
 *   t2 — the recovery-check flag is true ONLY from resendInventoryAdjustment (adjustInventoryQuantities passes false).
 */
class AdjustTransportGuardTest {

    private static final Path SOURCE =
        Path.of("src/main/java/com/traceability/integrations/shopify/ShopifyHttpGateway.java");

    @Test
    void t1_sendAdjust_neverUsesTheRetryingTransport() throws Exception {
        String src = Files.readString(SOURCE);
        for (String method : new String[] {"private void sendAdjust(", "private JsonNode postAdjustOnce("}) {
            String body = methodBody(src, method);
            assertThat(body).as(method).doesNotContain("executeGraphQL(").doesNotContain("postGraphQL(")
                .doesNotContain("Retry.").doesNotContain("retry");
        }
        assertThat(methodBody(src, "private void sendAdjust(")).contains("postAdjustOnce(");
        assertThat(methodBody(src, "private JsonNode postAdjustOnce(")).contains("restClient.post()");
    }

    @Test
    void t2_recoveryCheckFlag_onlyFromResend() throws Exception {
        String src = Files.readString(SOURCE);
        assertThat(methodBody(src, "public void adjustInventoryQuantities(")).contains("changeFromQuantity, false);");
        assertThat(methodBody(src, "public void resendInventoryAdjustment(")).contains("changeFromQuantity, true);");
        assertThat(src.split("sendAdjust\\(", -1).length - 1)
            .as("sendAdjust: its declaration + exactly the two callers").isEqualTo(3);
    }

    /** The text from the method's signature to its matching closing brace. */
    private static String methodBody(String src, String signature) {
        int start = src.indexOf(signature);
        assertThat(start).as("found " + signature).isGreaterThanOrEqualTo(0);
        int open = src.indexOf('{', src.indexOf(')', start));
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(start, i + 1);
        }
        throw new AssertionError("unbalanced " + signature);
    }
}
