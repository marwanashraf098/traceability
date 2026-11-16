package com.traceability;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1b-2 (approved by Marawan 2026-11-16) — Traced NEVER writes a merchant delivery profile: no deliveryProfileCreate,
 * deliveryProfileUpdate or deliveryProfileRemove anywhere in the codebase (Shopify's market-driven shipping
 * compatibility form asks exactly this; on a market-driven store such a write "succeeds" and changes nothing live).
 * Legacy readiness comes from the default-profile membership READ; the owner adds the location in Shopify.
 *
 * <p>Scans every source Traced ships or runs — src/main (Java, application config), frontend/src, scripts, extensions
 * and the root config files — for the three mutation names and for the profile-update input fields that only such a
 * write carries (locationGroupsToUpdate / locationGroupsToCreate / locationsToAdd), in code AND in comments, case
 * insensitive. Left out: applied Flyway migrations (history — Flyway checksums them; V67 describes the old activation)
 * and test sources (this guard names the strings).
 *
 *   g1 none of them appears anywhere scanned
 *   g2 the scan really reads the code roots (positive control: it finds the delivery-profile READ query)
 */
class DeliveryProfileMutationGuardTest {

    static final Pattern FORBIDDEN = Pattern.compile(
        "deliveryprofile(create|update|remove)|locationgroupsto(update|create)|locationstoadd");

    static final List<Path> ROOTS = List.of(
        Path.of("src/main"), Path.of("frontend/src"), Path.of("scripts"), Path.of("extensions"));

    static final List<String> SOURCE_EXTENSIONS = List.of(
        ".java", ".kt", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".json", ".yml", ".yaml", ".properties", ".toml",
        ".graphql", ".gql", ".sql", ".sh", ".py", ".html", ".liquid");

    static boolean scanned(Path p) {
        String s = p.toString().replace('\\', '/');
        if (s.contains("/node_modules/") || s.contains("/dist/") || s.contains("/target/")) return false;
        if (s.startsWith("src/main/resources/db/migration/")) return false;                 // applied history
        if (s.contains("/__tests__/") || s.contains("/test/") || s.matches(".*\\.(test|spec)\\.[jt]sx?$")) return false;
        return SOURCE_EXTENSIONS.stream().anyMatch(s::endsWith);
    }

    static List<Path> files() throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path root : ROOTS) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> s = Files.walk(root)) {
                s.filter(Files::isRegularFile).filter(DeliveryProfileMutationGuardTest::scanned).forEach(out::add);
            }
        }
        try (Stream<Path> s = Files.list(Path.of("."))) {
            s.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".toml")).forEach(out::add);
        }
        return out;
    }

    @Test
    void g1_noDeliveryProfileMutationAnywhere() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path p : files()) {
            String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            var m = FORBIDDEN.matcher(text);
            while (m.find()) hits.add(p + ": " + m.group());
        }
        assertThat(hits).as("delivery-profile writes are not allowed (L1b-2)").isEmpty();
    }

    @Test
    void g2_positiveControl_theScanReadsTheCode() throws IOException {
        List<Path> files = files();
        assertThat(files).anyMatch(p -> p.endsWith("ShopifyDeliveryProfileGatewayImpl.java"));
        assertThat(files).anyMatch(p -> p.toString().replace('\\', '/').startsWith("frontend/src/"));
        String impl = Files.readString(Path.of(
            "src/main/java/com/traceability/integrations/shopify/ShopifyDeliveryProfileGatewayImpl.java"));
        assertThat(impl).contains("deliveryProfiles(first:");                  // the READ is still there
    }
}
