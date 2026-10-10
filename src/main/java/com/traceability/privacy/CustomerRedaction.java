package com.traceability.privacy;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collection;
import java.util.UUID;

/**
 * customers/redact and shop/redact — every store of customer PII Traced holds (GDPR build A, V143).
 *
 * <ul>
 *   <li>orders: customer_name / customer_phone / address / shopify_address (V144) / pii_source cleared, raw stripped by
 *       {@code shopify_order_raw_redacted()}, pii_redacted_at stamped (UPSERT_ORDER then never refills them);</li>
 *   <li>return requests on those orders: email, note, typed pickup address (the pickup AREA snapshot —
 *       city / district names — stays, as before);</li>
 *   <li>shipments, exchanges, unlinked Bosta deliveries: pii_redacted_at stamped — the V143 triggers strip
 *       raw with {@code bosta_raw_redacted()} now and on every later write;</li>
 *   <li>stored Shopify webhooks: order payloads stripped; the customer's email / phone removed from
 *       customers/* payloads (customer id and orders_to_redact stay, as the audit trail);</li>
 *   <li>customer_data_requests of that customer: phone cleared, pii_redacted_at stamped;</li>
 *   <li>blocklist (customers/redact only): rows for the customer's canonical phones ({@link CustomerSubject})
 *       get the free-text reason replaced by {@link #REDACTED_REASON}; phone_canonical, source, created_by,
 *       created_at and active stay, so the block keeps working.</li>
 * </ul>
 * Never touched: piece_events (INSERT-only, no customer PII), return_request_events (no PII copies),
 * Bosta webhook_events (status payloads, no PII).
 *
 * Not a bean — built on the caller's JdbcTemplate so it runs inside the caller's transaction, under
 * app_user + RLS (same pattern as ReturnRequestLifecycle). Call it inside one transaction.
 */
public final class CustomerRedaction {

    private final JdbcTemplate jdbc;

    public CustomerRedaction(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** blocklist.reason is NOT NULL — a fixed marker instead of NULL. */
    public static final String REDACTED_REASON = "[redacted]";

    public record Result(int orders, int returnRequests, int shipments, int exchanges, int unlinked,
                         int webhookEvents, int dataRequests, int blocklist) {}

    /** customers/redact: orders_to_redact (as GIDs) plus the customer-keyed stores. */
    public Result redactCustomer(UUID tenantId, Collection<String> orderGids, String shopifyCustomerId,
                                 String payloadPhone) {
        int orders = 0, requests = 0, shipments = 0, exchanges = 0, unlinked = 0, events = 0, blocked = 0;
        if (!orderGids.isEmpty() || (payloadPhone != null && !payloadPhone.isBlank())) {
            // Resolve BEFORE clearing — the orders' phones and numbers find the unlinked Bosta copies and
            // the customer's blocklist rows.
            CustomerSubject s = CustomerSubject.resolve(jdbc, tenantId, orderGids, payloadPhone, false);
            blocked = jdbc.update(
                "UPDATE blocklist SET reason = ? WHERE tenant_id = ? AND phone_canonical = ANY(?::text[]) AND reason <> ?",
                REDACTED_REASON, tenantId, s.phoneArray(), REDACTED_REASON);
            if (!orderGids.isEmpty()) {
                unlinked  = jdbc.update(
                    "UPDATE unlinked_bosta_deliveries u SET pii_redacted_at = COALESCE(u.pii_redacted_at, now()) " +
                    "WHERE u.tenant_id = ? AND " + CustomerSubject.UNLINKED_MATCH,
                    prepend(tenantId, s.unlinkedArgs()));
                orders    = jdbc.update(REDACT_ORDERS + " WHERE tenant_id = ? AND id = ANY(?::uuid[])",
                    tenantId, s.orderIdArray());
                requests  = jdbc.update(REDACT_REQUESTS + " AND order_id = ANY(?::uuid[])",
                    tenantId, s.orderIdArray());
                // P3: the customer's return photos of these orders (claimed or not) lose their bytes.
                new com.traceability.portal.ReturnPhotos(jdbc).redactOrders(tenantId, java.util.List.of(s.orderIdArray()));
                shipments = jdbc.update(
                    "UPDATE shipments SET pii_redacted_at = COALESCE(pii_redacted_at, now()) " +
                    "WHERE tenant_id = ? AND order_id = ANY(?::uuid[])", tenantId, s.orderIdArray());
                exchanges = jdbc.update(
                    "UPDATE exchanges SET pii_redacted_at = COALESCE(pii_redacted_at, now()) " +
                    "WHERE tenant_id = ? AND id = ANY(?::uuid[])", tenantId, s.exchangeIdArray());
                events    = jdbc.update(
                    "UPDATE shopify_webhook_events SET payload_raw = shopify_order_raw_redacted(payload_raw) " +
                    "WHERE tenant_id = ? AND topic LIKE 'orders/%' AND payload_raw ->> 'admin_graphql_api_id' = ANY(?::text[])",
                    tenantId, s.gidArray());
            }
        }
        int dataRequests = 0;
        if (shopifyCustomerId != null && !shopifyCustomerId.isBlank()) {
            events += jdbc.update(
                "UPDATE shopify_webhook_events SET payload_raw = payload_raw #- '{customer,email}' #- '{customer,phone}' " +
                "WHERE tenant_id = ? AND topic LIKE 'customers/%' AND payload_raw #>> '{customer,id}' = ?",
                tenantId, shopifyCustomerId);
            dataRequests = jdbc.update(
                "UPDATE customer_data_requests SET customer_phone = NULL, pii_redacted_at = COALESCE(pii_redacted_at, now()) " +
                "WHERE tenant_id = ? AND shopify_customer_id = ?", tenantId, shopifyCustomerId);
        }
        return new Result(orders, requests, shipments, exchanges, unlinked, events, dataRequests, blocked);
    }

    /** shop/redact (~48 h after uninstall): every customer of the tenant. */
    public Result redactShop(UUID tenantId) {
        int orders    = jdbc.update(REDACT_ORDERS + " WHERE tenant_id = ?", tenantId);
        int requests  = jdbc.update(REDACT_REQUESTS, tenantId);
        new com.traceability.portal.ReturnPhotos(jdbc).redactTenant(tenantId);   // P3: every return photo
        int shipments = jdbc.update(
            "UPDATE shipments SET pii_redacted_at = COALESCE(pii_redacted_at, now()) WHERE tenant_id = ?", tenantId);
        int exchanges = jdbc.update(
            "UPDATE exchanges SET pii_redacted_at = COALESCE(pii_redacted_at, now()) WHERE tenant_id = ?", tenantId);
        int unlinked  = jdbc.update(
            "UPDATE unlinked_bosta_deliveries SET pii_redacted_at = COALESCE(pii_redacted_at, now()) WHERE tenant_id = ?",
            tenantId);
        int events = jdbc.update(
            "UPDATE shopify_webhook_events SET payload_raw = shopify_order_raw_redacted(payload_raw) " +
            "WHERE tenant_id = ? AND topic LIKE 'orders/%'", tenantId)
            + jdbc.update(
            "UPDATE shopify_webhook_events SET payload_raw = payload_raw #- '{customer,email}' #- '{customer,phone}' " +
            "WHERE tenant_id = ? AND topic LIKE 'customers/%'", tenantId);
        int dataRequests = jdbc.update(
            "UPDATE customer_data_requests SET customer_phone = NULL, pii_redacted_at = COALESCE(pii_redacted_at, now()) " +
            "WHERE tenant_id = ?", tenantId);
        return new Result(orders, requests, shipments, exchanges, unlinked, events, dataRequests, 0);
    }

    // Orders: the same column list the pre-V143 handlers cleared; raw through the shared V143 function.
    private static final String REDACT_ORDERS = """
            UPDATE orders
            SET customer_name   = NULL,
                customer_phone  = NULL,
                address         = NULL,
                shopify_address = NULL,
                pii_source      = NULL,
                pii_redacted_at = now(),
                raw             = shopify_order_raw_redacted(raw)
            """;

    // A return request's own PII (portal email, note, V117 typed pickup address, P2 refund details and
    // their hint — the method stays; P3 photos lose their bytes through ReturnPhotos). The area snapshot
    // (pickup_city_* / pickup_district_*), items and history stay — PortalCustomAddressTest asserts it.
    private static final String REDACT_REQUESTS = """
            UPDATE return_requests
            SET customer_email = NULL,
                customer_note = NULL,
                custom_first_line = NULL,
                custom_second_line = NULL,
                custom_building_number = NULL,
                custom_floor = NULL,
                custom_apartment = NULL,
                refund_details_encrypted = NULL,
                refund_details_hint = NULL,
                refund_details_purged_at = COALESCE(refund_details_purged_at, now()),
                pii_redacted_at = now()
            WHERE tenant_id = ?
              AND pii_redacted_at IS NULL
            """;

    private static Object[] prepend(Object first, Object[] rest) {
        Object[] out = new Object[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }
}
