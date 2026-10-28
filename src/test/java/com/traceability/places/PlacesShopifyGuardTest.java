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

    @Test
    void placesPackage_namesNoShopifyCode() throws IOException {
        List<Path> files;
        try (Stream<Path> paths = Files.walk(PLACES)) {
            files = paths.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(files).as("the places package exists where this scan looks").isNotEmpty();
        List<String> hits = new ArrayList<>();
        for (Path f : files) {
            String src = Files.readString(f);
            for (String token : FORBIDDEN) if (src.contains(token)) hits.add(f.getFileName() + ": " + token);
        }
        assertThat(hits).as("places code must never reach Shopify").isEmpty();
    }

    @Test
    void placesBeans_takeNoShopifyDependency() {
        for (Class<?> c : List.of(PlacesService.class, PlacesController.class)) {
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
