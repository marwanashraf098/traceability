package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * Shopify-native returns S5a — the ONLY Shopify returns WRITE surface (approved 2026-11-07). Called only by
 * portal.ShopifyReturnSyncService. Every write is ONE attempt ({@link ShopifyGateway#executeReturnsWriteOnce}).
 *
 * <p>What it can never send (ShopifyReturnsWriteGuardTest): a RESTOCKED disposition (Traced is the one that restocks,
 * after the worker grades — a processed unit is always NOT_RESTOCKED), a financialTransfer or refundShipping (Traced
 * never refunds through Shopify), exchange line items (S5b creates exchanges as a return only), a customer
 * notification. Queries validated against Admin GraphQL 2026-04 (2026-11-07).
 */
public interface ShopifyReturnsWriteGateway {

    /** One returnable unit pool: a FulfillmentLineItem of a LineItem, how many units of it can still be returned. */
    record Returnable(String fulfillmentLineItemGid, String lineItemGid, int quantity, Instant fulfilledAt) {}

    /** The order's returnable fulfillment line items, oldest fulfillment first; {@code truncated} when Shopify had more. */
    record Returnables(List<Returnable> lines, boolean truncated) {}

    /** One return line to create. {@code note} ≤ 255 characters, never customer text. */
    record CreateLine(String fulfillmentLineItemGid, int quantity, String note) {}

    /** READ. Throws ShopifyReturnsAccessDeniedException / ShopifyException like the S1 reads. */
    Returnables returnableFulfillments(String shopDomain, String token, String orderGid);

    /** READ: the order's returns (first 20) with their line notes — to find a return carrying a request's marker. */
    JsonNode orderReturnNotes(String shopDomain, String token, String orderGid);

    /** returnCreate (status OPEN), no fee, no exchange line, no notification. */
    ReturnsWriteResponse returnCreate(String shopDomain, String token, String orderGid, Instant requestedAt,
                                      List<CreateLine> lines);

    /**
     * returnProcess for {@code quantity} units of one return line, disposed NOT_RESTOCKED (the only disposition this
     * gateway can send) — at {@code locationGid} when given, else with no location. No refund, no notification.
     */
    ReturnsWriteResponse returnProcessNotRestocked(String shopDomain, String token, String returnGid, String returnLineGid,
                                                   String rfoLineGid, int quantity, String locationGid);

    /** removeFromReturn: {@code quantity} unprocessed units of one return line (they never came back). */
    ReturnsWriteResponse removeFromReturn(String shopDomain, String token, String returnGid, String returnLineGid, int quantity);

    ReturnsWriteResponse returnClose(String shopDomain, String token, String returnGid);

    ReturnsWriteResponse returnCancel(String shopDomain, String token, String returnGid);
}
