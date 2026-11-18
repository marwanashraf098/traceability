package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyMoveSourceGateway.ShopifyLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Receiving move M1 test (a): the "Moving from" list is the merchant's own ACTIVE locations — never the Traced Main
 * Warehouse's GID, never an inactive one, never a fulfillment-service / app-managed one.
 *
 * Revert-checked (2026-11-18): dropping the Traced-GID filter in ReceivingMoveService.eligibleSources → a1 red (test g);
 * dropping the appManaged filter → a2 red.
 */
class ReceivingMoveSourcesTest {

    static final String MAIN = "gid://shopify/Location/100";

    @Test
    void a1_tracedMainAndInactive_excluded() {
        List<ReceivingMoveService.Source> out = ReceivingMoveService.eligibleSources(List.of(
            new ShopifyLocation(MAIN, "Traced Main Warehouse", true, false),
            new ShopifyLocation("gid://shopify/Location/101", "Shop floor", true, false),
            new ShopifyLocation("gid://shopify/Location/102", "Old store", false, false)), MAIN);
        assertThat(out).extracting(ReceivingMoveService.Source::id).containsExactly("gid://shopify/Location/101");
    }

    @Test
    void a2_fulfillmentServiceLocation_excluded() {
        List<ReceivingMoveService.Source> out = ReceivingMoveService.eligibleSources(List.of(
            new ShopifyLocation("gid://shopify/Location/201", "Snow City Warehouse", true, true),
            new ShopifyLocation("gid://shopify/Location/202", "Downtown", true, false)), MAIN);
        assertThat(out).extracting(ReceivingMoveService.Source::name).containsExactly("Downtown");
    }

    @Test
    void a3_sortedByName() {
        List<ReceivingMoveService.Source> out = ReceivingMoveService.eligibleSources(List.of(
            new ShopifyLocation("gid://shopify/Location/3", "zamalek", true, false),
            new ShopifyLocation("gid://shopify/Location/4", "Abbas", true, false)), MAIN);
        assertThat(out).extracting(ReceivingMoveService.Source::name).containsExactly("Abbas", "zamalek");
    }
}
