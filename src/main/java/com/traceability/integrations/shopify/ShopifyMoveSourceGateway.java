package com.traceability.integrations.shopify;

import java.util.List;
import java.util.Map;

/**
 * Receiving move M1 (approved by Marawan 2026-11-12) — READ ONLY: the merchant's Shopify locations, and the stock levels
 * at ONE location the caller names (the move's source). Never pinned to the Traced Main Warehouse: the location is
 * always a parameter. Scopes read_locations + read_inventory + read_products (write_* imply them) — no scope change.
 * There is no write here and none may be added (ReceivingMoveNoMutationGuardTest); the move's write is M2's
 * pushLocationMove, a separate named method.
 */
public interface ShopifyMoveSourceGateway {

    /** One Shopify location as Shopify answered it (unfiltered: the caller decides what may be a source). */
    record ShopifyLocation(String gid, String name, boolean active, boolean appManaged) {}

    /**
     * One variant's level at the asked location. {@code level == false}: Shopify has no ACTIVE level there (no level,
     * or an inactive one — the item isn't stocked at that location); the quantities are then null.
     */
    record Level(String variantGid, boolean level, Integer available, Integer committed, Integer onHand) {}

    /**
     * Every location of the shop, all pages (250 per page), inactive and fulfillment-service ("legacy") locations left
     * out by Shopify, app-managed ones flagged by {@code fulfillmentService}.
     *
     * @throws MoveSourceReadException on any GraphQL error (codes only) or an answer without data
     */
    List<ShopifyLocation> listLocations(String shopDomain, String token);

    /**
     * available / committed / on_hand at {@code locationGid} for each variant GID (250 per call). A variant Shopify
     * doesn't know is missing from the map.
     */
    Map<String, Level> fetchLocationLevels(String shopDomain, String token, String locationGid, List<String> variantGids);

    /** How many distinct products have stock available (> 0) at {@code locationGid} — all pages. */
    int countProductsWithStock(String shopDomain, String token, String locationGid);

    /** Carries only Shopify's error codes (extensions.code) — never a message or a body. */
    class MoveSourceReadException extends RuntimeException {
        private final String reason;

        public MoveSourceReadException(String reason) {
            super("move source read failed: " + reason);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
