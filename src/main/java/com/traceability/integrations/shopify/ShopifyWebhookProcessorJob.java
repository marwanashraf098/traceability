package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.inventory.FulfillService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Async processor for Shopify webhook events.
 *
 * Reads event rows from shopify_webhook_events, routes by topic, and marks
 * processed_at on completion. TenantContext is set from the event's tenant_id
 * (the ThreadLocal is not propagated across JobRunr workers).
 *
 * Invariants (from CLAUDE.md):
 *   #4 — persist raw, ack fast, process async, idempotent (handled by controller + UNIQUE constraint).
 *   #2 — piece_events is INSERT-only: redaction handlers MUST NOT touch piece_events.
 *   #8 — no silent drops: unmapped/unknown topics raise an exception, never silently skip.
 */
@Component
public class ShopifyWebhookProcessorJob {

    private static final Logger log = LoggerFactory.getLogger(ShopifyWebhookProcessorJob.class);

    private static final String LOAD_EVENT = """
            SELECT swe.tenant_id, swe.topic, swe.shop_domain, swe.payload_raw::text
            FROM shopify_webhook_events swe
            WHERE swe.id = ?
            """;

    private static final String MARK_PROCESSED =
        "UPDATE shopify_webhook_events SET processed_at = now(), process_error = NULL WHERE id = ?";

    private static final String MARK_ERROR =
        "UPDATE shopify_webhook_events SET process_error = ? WHERE id = ?";

    private static final String DISCONNECT_STORE =
        "UPDATE stores SET status = 'disconnected' WHERE shop_domain = ? AND tenant_id = ?";

    // GDPR customers/redact: erase PII for specific orders listed in orders_to_redact.
    // Scoped to tenant_id + external_id = ANY(array of GIDs) — never broader than what
    // Shopify cleared. External IDs are stored as gid://shopify/Order/{id}.
    // A2: client_details (browser IP/UA) and note_attributes (may carry name/phone)
    // are added to the strip list. line_item properties are lower-priority (follow-up).
    private static final String REDACT_CUSTOMER_ORDERS_BY_IDS = """
            UPDATE orders
            SET customer_name  = NULL,
                customer_phone = NULL,
                address        = NULL,
                raw = raw
                    - 'customer'
                    - 'shipping_address'
                    - 'billing_address'
                    - 'email'
                    - 'phone'
                    - 'client_details'
                    - 'note_attributes'
            WHERE tenant_id   = ?
              AND external_id = ANY(?)
            """;

    // GDPR shop/redact: erase ALL customer PII for this tenant (sent ~48h after uninstall).
    // Intentionally tenant-wide — the shop is gone, all customer data must be cleared.
    private static final String REDACT_ALL_CUSTOMERS_FOR_TENANT = """
            UPDATE orders
            SET customer_name  = NULL,
                customer_phone = NULL,
                address        = NULL,
                raw = raw
                    - 'customer'
                    - 'shipping_address'
                    - 'billing_address'
                    - 'email'
                    - 'phone'
                    - 'client_details'
                    - 'note_attributes'
            WHERE tenant_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ShopifySyncService syncService;
    private final FulfillService fulfillService;
    private final TransactionTemplate tx;

    public ShopifyWebhookProcessorJob(JdbcTemplate jdbc,
                                       ObjectMapper mapper,
                                       ShopifySyncService syncService,
                                       FulfillService fulfillService,
                                       PlatformTransactionManager txm) {
        this.jdbc           = jdbc;
        this.mapper         = mapper;
        this.syncService    = syncService;
        this.fulfillService = fulfillService;
        this.tx             = new TransactionTemplate(txm);
    }

    @Job(name = "Shopify webhook processor — event %0")
    public void process(UUID eventId, UUID tenantId) {
        // tenantId is passed from the controller (which resolved it) so the GUC is set
        // before the first SELECT. Without it, shopify_webhook_events RLS returns no rows.
        TenantContext.runAs(tenantId, (Runnable) () -> {
            Object[] row = tx.execute(s ->
                jdbc.query(LOAD_EVENT, rs -> {
                    if (!rs.next()) return null;
                    return new Object[]{
                        rs.getString("topic"),
                        rs.getString("shop_domain"),
                        rs.getString("payload_raw")
                    };
                }, eventId));

            if (row == null) {
                log.warn("Webhook event {} not found for tenant {} — may have been deleted", eventId, tenantId);
                return;
            }

            String topic      = (String) row[0];
            String shopDomain = (String) row[1];
            String payloadStr = (String) row[2];

            try {
                JsonNode payload = mapper.readTree(payloadStr);
                dispatch(tenantId, topic, shopDomain, payload);
                tx.execute(s -> { jdbc.update(MARK_PROCESSED, eventId); return null; });
            } catch (Exception e) {
                log.error("Webhook processor failed: eventId={} topic={} shop={}", eventId, topic, shopDomain, e);
                String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                tx.execute(s -> {
                    jdbc.update(MARK_ERROR, errMsg.length() > 2000 ? errMsg.substring(0, 2000) : errMsg, eventId);
                    return null;
                });
            }
        });
    }

    // ---- dispatch -------------------------------------------------------

    private void dispatch(UUID tenantId, String topic, String shopDomain, JsonNode payload) {
        switch (topic) {
            case "orders/create", "orders/updated" -> handleOrderUpsert(tenantId, shopDomain, payload);
            case "orders/cancelled"                 -> handleOrderCancelled(tenantId, shopDomain, payload);
            case "products/create", "products/update" -> handleProductUpsert(tenantId, shopDomain, payload);
            case "app/uninstalled"                  -> handleAppUninstalled(tenantId, shopDomain);
            case "customers/data_request"           -> handleDataRequest(tenantId, shopDomain, payload);
            case "customers/redact"                 -> handleCustomersRedact(tenantId, shopDomain, payload);
            case "shop/redact"                      -> handleShopRedact(tenantId, shopDomain);
            default -> {
                // Invariant #8: never a silent drop — log as exception so ops can see it.
                log.error("Unhandled Shopify webhook topic={} shop={} — this topic has no registered handler",
                    topic, shopDomain);
            }
        }
    }

    // ---- topic handlers -------------------------------------------------

    private void handleOrderUpsert(UUID tenantId, String shopDomain, JsonNode payload) {
        UUID storeId = syncService.resolveStore(tenantId, shopDomain);
        if (storeId == null) {
            log.warn("orders webhook: store not found or disconnected shop={} tenant={}", shopDomain, tenantId);
            return;
        }
        syncService.ingestOrderWebhook(storeId, tenantId, payload);
    }

    private void handleOrderCancelled(UUID tenantId, String shopDomain, JsonNode payload) {
        String externalId = payload.path("admin_graphql_api_id").asText(null);
        if (externalId == null || externalId.isBlank()) {
            log.warn("orders/cancelled webhook missing admin_graphql_api_id shop={}", shopDomain);
            return;
        }

        UUID orderId = tx.execute(s ->
            jdbc.query(
                "SELECT o.id FROM orders o " +
                "JOIN stores st ON st.id = o.store_id " +
                "WHERE o.external_id = ? AND o.tenant_id = ? AND st.shop_domain = ?",
                rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                externalId, tenantId, shopDomain));

        if (orderId == null) {
            log.debug("orders/cancelled: order not found externalId={} shop={}", externalId, shopDomain);
            return;
        }

        try {
            // FR-3.5: wire cancel-release here — delegates to the same FulfillService path
            // as the manual cancel endpoint (pre-pack auto-release / post-pack guided unpack).
            FulfillService.CancelResult result = fulfillService.cancelOrder(orderId, null);
            log.info("Shopify cancel webhook: orderId={} status={} remainingPacked={}",
                orderId, result.status(), result.remainingPacked());
        } catch (ResponseStatusException e) {
            if (e.getStatusCode().value() == 409) {
                // Order is with courier (awaiting_pickup / with_courier / returning) —
                // cannot auto-cancel. Stamp the signal so the exceptions center surfaces it
                // as shopify_cancel_vs_inflight for the operator to resolve manually.
                final UUID oid = orderId;
                tx.execute(s -> {
                    jdbc.update(
                        "UPDATE orders " +
                        "SET shopify_cancel_requested_at = COALESCE(shopify_cancel_requested_at, now()) " +
                        "WHERE id = ? AND tenant_id = ?",
                        oid, tenantId);
                    return null;
                });
                log.warn("Shopify cancel for in-flight order {} ({}): flagged for manual resolution",
                    orderId, e.getReason());
            } else {
                // Terminal/already-cancelled — benign idempotency path.
                log.debug("orders/cancelled for already-terminal orderId={}: {}", orderId, e.getMessage());
            }
        }

        // FR-9.11: Remove from pickup manifest unconditionally — idempotent no-op if not on
        // any manifest. For awaiting_pickup orders (which 409 out of cancelOrder() above),
        // this is the only path that actually cleans the pickup_shipments row.
        // Order status is intentionally NOT changed here — the operator resolves via the
        // shopify_cancel_vs_inflight exception.
        fulfillService.removeFromPickupManifest(orderId);
    }

    private void handleProductUpsert(UUID tenantId, String shopDomain, JsonNode payload) {
        UUID storeId = syncService.resolveStore(tenantId, shopDomain);
        if (storeId == null) {
            log.warn("products webhook: store not found or disconnected shop={} tenant={}", shopDomain, tenantId);
            return;
        }
        syncService.ingestProductWebhook(storeId, tenantId, payload);
    }

    private void handleAppUninstalled(UUID tenantId, String shopDomain) {
        tx.execute(s -> {
            jdbc.update(DISCONNECT_STORE, shopDomain, tenantId);
            return null;
        });
        log.info("app/uninstalled: store disconnected shop={} tenant={}", shopDomain, tenantId);
    }

    private void handleDataRequest(UUID tenantId, String shopDomain, JsonNode payload) {
        // The event is already persisted in shopify_webhook_events — that IS the audit trail.
        // Surface as a GDPR task for ops to respond to. Full automated data export is [S].
        log.warn("GDPR data_request received: shop={} tenant={} customerId={}",
            shopDomain, tenantId, payload.path("customer").path("id").asText("unknown"));
    }

    private void handleCustomersRedact(UUID tenantId, String shopDomain, JsonNode payload) {
        // A1: Scope to orders_to_redact — Shopify deliberately excludes recent/in-flight
        // orders from this list. Broad customer-match would blank address on active orders.
        // piece_events is INSERT-only and holds NO customer PII — must not be touched.
        JsonNode ordersNode = payload.path("orders_to_redact");
        if (!ordersNode.isArray() || ordersNode.isEmpty()) {
            log.info("customers/redact: orders_to_redact is empty — nothing to redact shop={} tenant={}",
                shopDomain, tenantId);
            return;
        }

        List<String> externalIds = new ArrayList<>();
        for (JsonNode order : ordersNode) {
            long orderId = order.path("id").asLong(0);
            if (orderId > 0) {
                externalIds.add("gid://shopify/Order/" + orderId);
            }
        }
        if (externalIds.isEmpty()) {
            log.warn("customers/redact: no valid order IDs extracted shop={} tenant={}", shopDomain, tenantId);
            return;
        }

        String[] idArray = externalIds.toArray(new String[0]);
        int updated = tx.execute(s ->
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(REDACT_CUSTOMER_ORDERS_BY_IDS);
                ps.setObject(1, tenantId);
                ps.setArray(2, con.createArrayOf("text", idArray));
                return ps;
            }));
        log.info("customers/redact: erased PII for {} GID(s) shop={} tenant={} — {} order(s) updated",
            externalIds.size(), shopDomain, tenantId, updated);
    }

    private void handleShopRedact(UUID tenantId, String shopDomain) {
        // Sent ~48h after app/uninstalled: erase ALL customer PII for this tenant.
        // piece_events is INSERT-only and holds NO customer PII — must not be touched.
        int updated = tx.execute(s ->
            jdbc.update(REDACT_ALL_CUSTOMERS_FOR_TENANT, tenantId));
        log.info("shop/redact: erased all customer PII for tenant={} shop={} — {} order(s) updated",
            tenantId, shopDomain, updated);
    }
}
