package com.traceability.places;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Warehouse places never touch Shopify (placing / moving a piece or a place is not a stock change). Like
 * NamedDecrementSetGuardTest this is a source-text scan of the places package — no file in it names the Shopify
 * gateway, ShopifyInventoryService or anything under integrations.shopify — plus a reflection check that no places
 * bean takes a Shopify-package dependency (nothing to call it through). Same known limits as that test: it guards
 * the ordinary, direct way such a call would creep in, not deliberate evasion.
 */
class PlacesShopifyGuardTest {

    private static final Path PLACES = Paths.get("src/main/java/com/traceability/places");
    private static final List<String> FORBIDDEN = List.of(
        "integrations.shopify", "ShopifyGateway", "ShopifyInventoryService", "ShopifyHttpGateway",
        "TransferShopifySync", "PieceShopifyRules", "ShopifyTokenProvider");

    /** S2 — the put-away writer and its refusal live outside the package; they're scanned too. */
    private static final List<Path> OUTSIDE = List.of(
        Paths.get("src/main/java/com/traceability/inventory/PlaceChangeException.java"));

    @Test
    void placesPackage_namesNoShopifyCode() throws IOException {
        List<Path> files;
        try (Stream<Path> paths = Files.walk(PLACES)) {
            files = new ArrayList<>(paths.filter(p -> p.toString().endsWith(".java")).toList());
        }
        assertThat(files).as("the places package exists where this scan looks").isNotEmpty();
        assertThat(files.stream().map(f -> f.getFileName().toString()))
            .as("S2's put-away code is in the scan").contains("PutAwayService.java", "PutAwayController.java", "PlacePaths.java");
        for (Path f : OUTSIDE) assertThat(Files.exists(f)).as(f.toString()).isTrue();
        files.addAll(OUTSIDE);
        List<String> hits = new ArrayList<>();
        for (Path f : files) {
            String src = Files.readString(f);
            for (String token : FORBIDDEN) if (src.contains(token)) hits.add(f.getFileName() + ": " + token);
        }
        assertThat(hits).as("places code must never reach Shopify").isEmpty();
    }

    /**
     * S2 — InventoryLedger.recordPlaceChange (the one place writer) names no Shopify code either: the method's own
     * source text, cut from the ledger file (the ledger as a whole has no Shopify dependency — checked below).
     */
    @Test
    void recordPlaceChange_namesNoShopifyCode() throws IOException {
        String src = Files.readString(Paths.get("src/main/java/com/traceability/inventory/InventoryLedger.java"));
        int start = src.indexOf("// ---- recordPlaceChange");
        int end = src.indexOf("/** Specification for one piece to be created via batchReceive. */");
        assertThat(start).as("recordPlaceChange section found").isPositive();
        assertThat(end).isGreaterThan(start);
        String method = src.substring(start, end);
        assertThat(method).contains("public PlaceChange recordPlaceChange(");
        for (String token : FORBIDDEN) assertThat(method).as("recordPlaceChange: " + token).doesNotContain(token);
        for (Constructor<?> ctor : com.traceability.inventory.InventoryLedger.class.getDeclaredConstructors()) {
            for (Class<?> p : ctor.getParameterTypes()) assertThat(p.getName()).doesNotContain("hopify");
        }
    }

    @Test
    void placesBeans_takeNoShopifyDependency() {
        for (Class<?> c : List.of(PlacesService.class, PlacesController.class, PutAwayService.class, PutAwayController.class)) {
            for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                for (Class<?> p : ctor.getParameterTypes()) {
                    assertThat(p.getName()).as(c.getSimpleName() + " constructor").doesNotContain("shopify").doesNotContain("Shopify");
                }
            }
            for (Field f : c.getDeclaredFields()) {
                assertThat(f.getType().getName()).as(c.getSimpleName() + "." + f.getName())
                    .doesNotContain("shopify").doesNotContain("Shopify");
            }
        }
    }
}
