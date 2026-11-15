package com.traceability.integrations.shopify;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1b-1 (Marawan 2026-11-15, Q3) — 2026-07 is for the shipping-model read ONLY; every inventory / location write path
 * stays on the pinned 2026-04.
 *
 *   v1 the version literal "2026-07" (quoted; dates in comments don't count) appears in src/main/java only in ShopifyShippingModelGateway, and nothing else references
 *      its API_VERSION constant
 *   v2 ShopifyShippingModelGateway sends no mutation (read-only) and builds its URL from API_VERSION
 *   v3 the pinned version stays 2026-04 (application.yml default) and ShopifyHttpGateway — every inventory / location
 *      write — builds every URL from its configured apiVersion, never a literal version
 *   v4 the delivery-profile join / membership read run ONLY behind a confirmed LEGACY model
 */
class ShippingModelVersionGuardTest {

    static final Path MAIN = Path.of("src/main/java");
    static final Path MODEL = Path.of("src/main/java/com/traceability/integrations/shopify/ShopifyShippingModelGateway.java");
    static final Path HTTP = Path.of("src/main/java/com/traceability/integrations/shopify/ShopifyHttpGateway.java");
    static final Path ONLINE = Path.of("src/main/java/com/traceability/inventory/TracedLocationOnlineService.java");
    static final Path ACTIVATION = Path.of("src/main/java/com/traceability/inventory/ShopifyFulfillmentActivationService.java");

    static List<Path> sources() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void v1_2026_07_onlyInTheShippingModelGateway() throws IOException {
        Set<String> literal = new TreeSet<>();
        Set<String> constant = new TreeSet<>();
        for (Path p : sources()) {
            String src = Files.readString(p);
            if (src.contains("\"2026-07\"")) literal.add(p.getFileName().toString());   // the version literal, not a date
            if (!p.equals(MODEL) && src.contains("ShopifyShippingModelGateway.API_VERSION")) constant.add(p.getFileName().toString());
        }
        assertThat(literal).containsExactly("ShopifyShippingModelGateway.java");
        assertThat(constant).isEmpty();
    }

    @Test
    void v2_gateway_readOnly_andOnItsOwnVersion() throws IOException {
        String src = Files.readString(MODEL);
        assertThat(src).contains("\"/admin/api/\" + API_VERSION + \"/graphql.json\"");
        String code = src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        assertThat(code.toLowerCase()).doesNotContain("mutation");
        assertThat(code).doesNotContain("Retry").doesNotContain("executeGraphQL");
    }

    @Test
    void v3_everythingElse_staysOnThePinnedVersion() throws IOException {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yml).contains("api-version: ${SHOPIFY_API_VERSION:2026-04}");
        String http = Files.readString(HTTP);
        assertThat(http).doesNotContain("\"2026-").doesNotContain("ShopifyShippingModelGateway");
        int urls = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"/admin/api/\" \\+ (\\w+) \\+").matcher(http);
        while (m.find()) { urls++; assertThat(m.group(1)).isEqualTo("apiVersion"); }
        assertThat(urls).isPositive();
    }

    @Test
    void v4_profileCalls_onlyBehindLegacy() throws IOException {
        String online = Files.readString(ONLINE).replaceAll("\\s+", " ");
        int joins = 0;
        java.util.regex.Matcher j = java.util.regex.Pattern.compile("activation\\.(joinWithReadBack|readMembership)\\(").matcher(online);
        while (j.find()) joins++;
        assertThat(joins).isEqualTo(3);                                          // first link, reconnect, Turn back on
        assertThat(online).contains("model.orElse(null) == Model.LEGACY ? activation.joinWithReadBack(tenantId, null, \"first_link\")")
            .contains("model.orElse(null) != Model.LEGACY ? skipped(model) : activation.readMembership(tenantId)")
            .contains("model.orElse(null) == Model.LEGACY ? activation.joinWithReadBack(tenantId, actorUserId, \"turn_back_on\")");
        Set<String> callers = new TreeSet<>();
        for (Path p : sources()) {
            String src = Files.readString(p);
            if (src.contains(".joinWithReadBack(") || src.contains(".readMembership(")) callers.add(p.getFileName().toString());
        }
        assertThat(callers).containsExactly("TracedLocationOnlineService.java");
        // The manual Activate refuses a market-driven store before its first delivery-profile call.
        String act = Files.readString(ACTIVATION);
        int activate = act.indexOf("public ActivationResult activate(");
        int refuse = act.indexOf("MARKET_DRIVEN_SHIPPING", activate);
        int firstProfileCall = act.indexOf("deliveryProfiles.", activate);
        assertThat(refuse).isPositive().isLessThan(firstProfileCall);
    }
}
