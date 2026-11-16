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
 *   v4 the delivery-profile membership read runs ONLY behind a confirmed LEGACY model, and the Markets read only on a
 *      MARKET_DRIVEN one — both in TracedLocationOnlineService.readShipping, the one place (L1b-2: no join exists)
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
        int reads = 0;
        java.util.regex.Matcher j = java.util.regex.Pattern.compile("activation\\.readMembership\\(").matcher(online);
        while (j.find()) reads++;
        assertThat(reads).as("one membership read, in readShipping").isEqualTo(1);
        assertThat(online).contains("if (model.isEmpty()) return \"SKIPPED_MODEL_UNKNOWN\";")
            .contains("if (model.get() == Model.LEGACY) { return activation.readMembership(tenantId)")
            .contains("return readMarketCoverage(tenantId);");
        int shipping = online.indexOf("String readShipping(");
        assertThat(online.indexOf("if (model.isEmpty())", shipping)).isLessThan(online.indexOf("activation.readMembership(", shipping));
        assertThat(online.indexOf("activation.readMembership(", shipping)).isLessThan(online.indexOf("return readMarketCoverage(", shipping));
        // The hourly pass never edits a location: reads only.
        int hourly = online.indexOf("public void refreshQuietly(");
        String hourlyBody = online.substring(hourly, online.indexOf("public void confirmShippingRates(", hourly));
        assertThat(hourlyBody).doesNotContain("ensureOnline");
        Set<String> callers = new TreeSet<>();
        for (Path p : sources()) {
            String src = Files.readString(p);
            if (src.contains(".readMembership(") || src.contains(".readMarketCoverage(")) callers.add(p.getFileName().toString());
        }
        assertThat(callers).containsExactly("TracedLocationOnlineService.java");
        assertThat(Files.readString(ACTIVATION)).doesNotContain("joinWithReadBack").doesNotContain("public ActivationResult activate(");
    }
}
