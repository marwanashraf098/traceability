package com.traceability.integrations.shopify;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shopify-native returns S5a (approved 2026-11-07) — the Shopify returns WRITE surface stays exactly what was
 * approved: Traced creates the return, processes units NOT_RESTOCKED, removes units that never came back, closes or
 * cancels it. It never restocks in Shopify, never refunds through Shopify, never adds an exchange line (S5b: return
 * only), never sends a fee (S5c) and never notifies the customer.
 */
class ShopifyReturnsWriteGuardTest {

    static final Path MAIN = Path.of("src/main/java");
    static final String IMPL = "com/traceability/integrations/shopify/ShopifyReturnsWriteGatewayImpl.java";

    /** A returns mutation in GraphQL text: its root field followed by an argument ("name(arg:"). */
    static final Pattern RETURN_MUTATION = Pattern.compile(
        "\\b(returnCreate|returnProcess|returnClose|returnCancel|removeFromReturn|returnRequest|returnApproveRequest|" +
        "returnDeclineRequest|returnReopen|reverseFulfillmentOrderDispose|reverseDeliveryCreateWithShipping|" +
        "returnRefund|refundCreate)\\s*\\(\\s*[a-zA-Z]+\\s*:");

    static Map<String, String> sources() throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(MAIN)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                out.put(MAIN.relativize(p).toString().replace('\\', '/'), Files.readString(p));
            }
        }
        return out;
    }

    @Test
    void g1_returnsMutations_onlyInTheWriteGateway_andOnlyTheApprovedOnes() throws IOException {
        Set<String> found = new TreeSet<>();
        for (Map.Entry<String, String> e : sources().entrySet()) {
            Matcher m = RETURN_MUTATION.matcher(e.getValue());
            while (m.find()) {
                assertThat(e.getKey()).as("returns mutation %s outside the write gateway", m.group(1)).isEqualTo(IMPL);
                found.add(m.group(1));
            }
        }
        assertThat(found).containsExactlyInAnyOrder("returnCreate", "returnProcess", "removeFromReturn", "returnClose",
            "returnCancel");
    }

    @Test
    void g2_writeGateway_neverRestocks_refunds_addsExchangeLines_fees_orNotifies() throws IOException {
        String impl = Files.readString(MAIN.resolve(IMPL));
        String withoutNotRestocked = impl.replace("NOT_RESTOCKED", "");
        assertThat(withoutNotRestocked).doesNotContain("RESTOCKED");
        for (String banned : List.of("financialTransfer", "refundShipping", "exchangeLineItems", "restockingFee",
                "returnShippingFee", "refundDuties", "issueRefund", "\"notifyCustomer\", true")) {
            assertThat(impl).as(banned).doesNotContain(banned);
        }
        assertThat(impl).contains("put(\"notifyCustomer\", false)");
    }

    @Test
    void g3_oneAttemptTransport_andWriteMethods_haveOneCallerEach() throws IOException {
        Map<String, String> src = sources();
        for (Map.Entry<String, String> e : src.entrySet()) {
            if (e.getValue().contains("executeReturnsWriteOnce(")) {
                assertThat(e.getKey()).isIn(IMPL, "com/traceability/integrations/shopify/ShopifyGateway.java",
                    "com/traceability/integrations/shopify/ShopifyHttpGateway.java");
            }
            for (String call : List.of(".returnCreate(", ".returnProcessNotRestocked(", ".removeFromReturn(", ".returnClose(",
                    ".returnCancel(")) {
                if (e.getValue().contains(call)) {
                    assertThat(e.getKey()).as(call).isEqualTo("com/traceability/portal/ShopifyReturnSyncService.java");
                }
            }
        }
    }

    @Test
    void g4_writeGateway_interface_hasNoOtherWrite() {
        Set<String> names = new TreeSet<>();
        for (Method m : ShopifyReturnsWriteGateway.class.getDeclaredMethods()) names.add(m.getName());
        assertThat(names).containsExactlyInAnyOrder("returnableFulfillments", "orderReturnNotes", "returnCreate",
            "returnProcessNotRestocked", "removeFromReturn", "returnClose", "returnCancel");
    }
}
