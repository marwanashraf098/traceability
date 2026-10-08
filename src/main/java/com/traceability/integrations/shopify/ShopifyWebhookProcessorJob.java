package com.traceability.integrations.shopify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.ShopifyCatalogActivationService;
import com.traceability.privacy.CustomerDataRequestService;
import com.traceability.privacy.CustomerRedaction;
import com.traceability.privacy.CustomerSubject;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import org.springframework.web.server.ResponseStatusException;

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
 *
 * Retry and ordering (2026-10-04, V136):
 *   - an event already processed is a no-op (the sweeper / a JobRunr retry / the re-process may run it again);
 *   - ordering safety, before applying orders/create, orders/updated, products/create, products/update:
 *     if a LATER event for the same order / product has been processed, or Traced's stored copy is
 *     strictly newer (raw.updated_at), the event is marked superseded (processed_at + superseded_at) and
 *     NOT applied — an old payload never overwrites newer data;
 *   - a transient DB failure (no connection) is rethrown: JobRunr retries the job (3 times, seconds apart);
 *   - any other failure is stored (process_error) with retry_count + 1 and next_retry_at on a backoff
 *     (1, 5, 15, 60, 240 min) for ShopifyWebhookRetrySweeper — after shopify.webhook.retry.max-attempts the
 *     error stays and next_retry_at is cleared.
 */
@Component
public class ShopifyWebhookProcessorJob {

    private static final Logger log = LoggerFactory.getLogger(ShopifyWebhookProcessorJob.class);

    private static final String LOAD_EVENT = """
            SELECT swe.tenant_id, swe.topic, swe.shop_domain, swe.payload_raw::text, swe.received_at,
                   swe.processed_at, swe.retry_count
            FROM shopify_webhook_events swe
            WHERE swe.id = ?
            """;

    private static final String MARK_PROCESSED =
        "UPDATE shopify_webhook_events SET processed_at = now(), process_error = NULL, next_retry_at = NULL WHERE id = ?";

    private static final String MARK_SUPERSEDED =
        "UPDATE shopify_webhook_events SET processed_at = now(), superseded_at = now(), process_error = NULL, " +
        "next_retry_at = NULL WHERE id = ?";

    /** Failure: the error, one more attempt counted, and the next sweeper attempt (NULL once out of attempts). */
    private static final String MARK_ERROR =
        "UPDATE shopify_webhook_events SET process_error = ?, retry_count = retry_count + 1, " +
        "  next_retry_at = CASE WHEN retry_count + 1 >= ? THEN NULL " +
        "                       ELSE now() + (? * INTERVAL '1 minute') END " +
        "WHERE id = ?";

    /** Backoff in minutes after the n-th failure (n = retry_count before this failure). */
    static final int[] RETRY_BACKOFF_MINUTES = {1, 5, 15, 60, 240};

    /** Topics whose handler upserts the resource from the payload — the ones ordering safety covers. */
    static final java.util.Set<String> ORDER_UPSERT_TOPICS = java.util.Set.of("orders/create", "orders/updated");
    static final java.util.Set<String> PRODUCT_UPSERT_TOPICS = java.util.Set.of("products/create", "products/update");

    private static final String DISCONNECT_STORE =
        "UPDATE stores SET status = 'disconnected' WHERE shop_domain = ? AND tenant_id = ?";

    // GDPR (customers/redact, shop/redact, customers/data_request): the SQL lives in the privacy package —
    // CustomerRedaction (every store of customer PII, V143) and CustomerDataRequestService.

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ShopifySyncService syncService;
    private final FulfillService fulfillService;
    private final ShopifyCatalogActivationService activationService;
    private final TransactionTemplate tx;
    private final FulfillmentTrackingCapture fulfillmentTracking;
    private final CustomerDataRequestService dataRequests;
    private int maxAttempts = 5;

    @org.springframework.beans.factory.annotation.Autowired
    public void setMaxAttempts(@org.springframework.beans.factory.annotation.Value("${shopify.webhook.retry.max-attempts:5}") int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public ShopifyWebhookProcessorJob(JdbcTemplate jdbc,
                                       ObjectMapper mapper,
                                       ShopifySyncService syncService,
                                       FulfillService fulfillService,
                                       ShopifyCatalogActivationService activationService,
                                       PlatformTransactionManager txm,
                                       FulfillmentTrackingCapture fulfillmentTracking,
                                       CustomerDataRequestService dataRequests,
                                       com.traceability.analytics.AnalyticsInventoryWebhookHandler inventoryReads) {
        this.jdbc           = jdbc;
        this.mapper         = mapper;
        this.syncService    = syncService;
        this.fulfillService = fulfillService;
        this.activationService = activationService;
        this.tx             = new TransactionTemplate(txm);
        this.fulfillmentTracking = fulfillmentTracking;
        this.dataRequests   = dataRequests;
        this.inventoryReads = inventoryReads;
    }

    /** Analytics slice 10: inventory_items/update + inventory_levels/update refresh READ columns only. */
    private final com.traceability.analytics.AnalyticsInventoryWebhookHandler inventoryReads;

    @Job(name = "Shopify webhook processor — event %0", retries = 3)
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
                        rs.getString("payload_raw"),
                        rs.getTimestamp("received_at"),
                        rs.getTimestamp("processed_at")
                    };
                }, eventId));

            if (row == null) {
                log.warn("Webhook event {} not found for tenant {} — may have been deleted", eventId, tenantId);
                return;
            }

            String topic      = (String) row[0];
            String shopDomain = (String) row[1];
            String payloadStr = (String) row[2];
            java.sql.Timestamp receivedAt = (java.sql.Timestamp) row[3];
            if (row[4] != null) {
                log.debug("Webhook event {} already processed — nothing to do", eventId);
                return;
            }

            try {
                JsonNode payload = mapper.readTree(payloadStr);
                String superseded = supersededReason(tenantId, eventId, topic, payload, receivedAt);
                if (superseded != null) {
                    tx.execute(s -> { jdbc.update(MARK_SUPERSEDED, eventId); return null; });
                    log.info("Webhook event {} ({} shop={}) superseded — not applied: {}", eventId, topic, shopDomain, superseded);
                    return;
                }
                dispatch(tenantId, eventId, topic, shopDomain, payload);
                tx.execute(s -> { jdbc.update(MARK_PROCESSED, eventId); return null; });
            } catch (Exception e) {
                if (isTransientDbFailure(e)) {
                    // No connection: let JobRunr retry the whole job in a few seconds. If that runs out,
                    // the row stays unprocessed and ShopifyWebhookRetrySweeper picks it up.
                    log.warn("Webhook processor: no DB connection for event {} ({}) — JobRunr will retry: {}",
                        eventId, topic, e.toString());
                    throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
                }
                log.error("Webhook processor failed: eventId={} topic={} shop={}", eventId, topic, shopDomain, e);
                String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                String stored = errMsg.length() > 2000 ? errMsg.substring(0, 2000) : errMsg;
                tx.execute(s -> {
                    Integer done = jdbc.queryForObject("SELECT retry_count FROM shopify_webhook_events WHERE id = ?",
                        Integer.class, eventId);
                    int n = done == null ? 0 : done;
                    int backoff = RETRY_BACKOFF_MINUTES[Math.min(n, RETRY_BACKOFF_MINUTES.length - 1)];
                    jdbc.update(MARK_ERROR, stored, maxAttempts, backoff, eventId);
                    return null;
                });
            }
        });
    }

    /**
     * Ordering safety: why this order / product event must not be applied, or null. Only for the upsert
     * topics. "Later" is received_at; "newer" is a strictly greater updated_at in Traced's stored raw.
     */
    String supersededReason(UUID tenantId, UUID eventId, String topic, JsonNode payload, java.sql.Timestamp receivedAt) {
        boolean order = ORDER_UPSERT_TOPICS.contains(topic);
        boolean product = PRODUCT_UPSERT_TOPICS.contains(topic);
        if (!order && !product) return null;
        String gid = payload.path("admin_graphql_api_id").asText(null);
        if (gid == null || gid.isBlank()) return null;
        java.util.Set<String> family = order ? ORDER_UPSERT_TOPICS : PRODUCT_UPSERT_TOPICS;
        String later = tx.execute(s -> jdbc.query(
            "SELECT topic || ' received ' || received_at FROM shopify_webhook_events " +
            "WHERE tenant_id = ? AND id <> ? AND topic = ANY(?) AND payload_raw->>'admin_graphql_api_id' = ? " +
            "  AND received_at > ? AND processed_at IS NOT NULL AND process_error IS NULL AND superseded_at IS NULL " +
            "ORDER BY received_at DESC LIMIT 1",
            rs -> rs.next() ? rs.getString(1) : null,
            tenantId, eventId, family.toArray(new String[0]), gid, receivedAt));
        if (later != null) return "a later event for " + gid + " was already applied (" + later + ")";
        String updatedAt = payload.path("updated_at").asText(null);
        if (updatedAt == null || updatedAt.isBlank()) return null;
        String table = order ? "orders" : "products";
        try {
            Boolean newer = tx.execute(s -> jdbc.query(
                "SELECT (raw->>'updated_at')::timestamptz > ?::timestamptz FROM " + table +
                " WHERE tenant_id = ? AND external_id = ? AND raw->>'updated_at' IS NOT NULL",
                rs -> rs.next() ? rs.getBoolean(1) : null, updatedAt, tenantId, gid));
            if (Boolean.TRUE.equals(newer)) return "Traced already holds a newer " + gid + " (stored updated_at > " + updatedAt + ")";
        } catch (org.springframework.dao.DataAccessException e) {
            log.debug("Ordering check: unreadable updated_at for {} — applying: {}", gid, e.getMessage());
        }
        return null;
    }

    /** No DB connection (pool exhausted / DB unreachable) anywhere in the cause chain. */
    static boolean isTransientDbFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLTransientConnectionException
                || t instanceof org.springframework.jdbc.CannotGetJdbcConnectionException
                || t instanceof org.springframework.transaction.CannotCreateTransactionException) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }

    // ---- dispatch -------------------------------------------------------

    private void dispatch(UUID tenantId, UUID eventId, String topic, String shopDomain, JsonNode payload) {
        switch (topic) {
            case "orders/create"  -> handleOrderUpsert(tenantId, shopDomain, payload);
            case "orders/updated" -> handleOrderUpdated(tenantId, shopDomain, payload);
            case "orders/cancelled"                 -> handleOrderCancelled(tenantId, shopDomain, payload);
            case "products/create", "products/update" -> handleProductUpsert(tenantId, shopDomain, payload);
            case "app/uninstalled"                  -> handleAppUninstalled(tenantId, shopDomain);
            case "customers/data_request"           -> handleDataRequest(tenantId, eventId, shopDomain, payload);
            case "customers/redact"                 -> handleCustomersRedact(tenantId, shopDomain, payload);
            case "shop/redact"                      -> handleShopRedact(tenantId, shopDomain);
            case "inventory_levels/update"          -> inventoryReads.onInventoryLevelUpdate(payload);
            case "inventory_items/update"           -> inventoryReads.onInventoryItemUpdate(payload);
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

    /**
     * FR-3.6: orders/updated — detect line-item edits and route by order status.
     *
     * Diff is computed against the pre-edit local order_items (before ingestOrderWebhook
     * upserts them). Routing:
     *   new/confirmed/ready_to_pick → line items updated, no release, no exception.
     *   picking → release allocations for removed/reduced lines; raise exception.
     *   packed/self_pickup_pending/awaiting_pickup/with_courier/returning → no touch; exception.
     *   terminal → line items updated, no exception.
     */
    private void handleOrderUpdated(UUID tenantId, String shopDomain, JsonNode payload) {
        UUID storeId = syncService.resolveStore(tenantId, shopDomain);
        if (storeId == null) {
            log.warn("orders/updated: store not found or disconnected shop={} tenant={}", shopDomain, tenantId);
            return;
        }

        String externalId = payload.path("admin_graphql_api_id").asText(null);
        if (externalId == null || externalId.isBlank()) {
            log.warn("orders/updated missing admin_graphql_api_id shop={}", shopDomain);
            syncService.ingestOrderWebhook(storeId, tenantId, payload);
            return;
        }

        // Load pre-edit order state (status + line items)
        Map<String, Object> preEdit = tx.execute(s ->
            jdbc.query(
                "SELECT o.id, o.status::text AS status FROM orders o " +
                "WHERE o.tenant_id = ? AND o.external_id = ? LIMIT 1",
                rs -> rs.next()
                    ? Map.of("id", rs.getObject("id", UUID.class),
                             "status", rs.getString("status"))
                    : null,
                tenantId, externalId));

        if (preEdit == null) {
            // Order not known locally yet — treat as create
            syncService.ingestOrderWebhook(storeId, tenantId, payload);
            captureFulfillmentTracking(storeId, externalId, payload);
            return;
        }

        UUID   orderId     = (UUID)   preEdit.get("id");
        String orderStatus = (String) preEdit.get("status");

        // Load pre-edit local order_items: externalId → qty
        Map<String, Integer> localQty = tx.execute(s ->
            jdbc.query(
                "SELECT external_id, quantity FROM order_items " +
                "WHERE order_id = ? AND tenant_id = ? AND external_id IS NOT NULL",
                rs -> {
                    Map<String, Integer> m = new LinkedHashMap<>();
                    while (rs.next()) m.put(rs.getString("external_id"), rs.getInt("quantity"));
                    return m;
                },
                orderId, tenantId));

        // Build incoming line-item map: externalId → qty
        Map<String, Integer> incomingQty = new LinkedHashMap<>();
        for (JsonNode line : payload.path("line_items")) {
            String lineGid = line.has("admin_graphql_api_id")
                ? line.path("admin_graphql_api_id").asText()
                : "gid://shopify/LineItem/" + line.path("id").asLong();
            incomingQty.put(lineGid, line.path("quantity").asInt(1));
        }

        LineDiff diff = computeLineDiff(localQty != null ? localQty : Map.of(), incomingQty);

        if (!diff.isEmpty()) {
            String diffJson = serializeDiff(diff);
            final UUID fOrderId = orderId;
            final String fStatus = orderStatus;
            tx.execute(s -> {
                fulfillService.handleShopifyLineItemEdit(
                    fOrderId, fStatus, diffJson,
                    diff.removed(), diff.releaseCountByExternalId());
                return null;
            });
        }

        // Always upsert the order + line items (metadata, raw JSON etc.)
        syncService.ingestOrderWebhook(storeId, tenantId, payload);
        captureFulfillmentTracking(storeId, externalId, payload);
    }

    /**
     * V129 — store the fulfillments' tracking numbers (store only; nothing reads them yet).
     * A failure here never fails the order update itself, which has already been applied.
     */
    private void captureFulfillmentTracking(UUID storeId, String externalId, JsonNode payload) {
        try {
            fulfillmentTracking.capture(storeId, externalId, payload);
        } catch (RuntimeException e) {
            log.warn("orders/updated: fulfillment tracking capture failed for {} — {}", externalId, e.toString());
        }
    }

    // ── Line-item diff helpers ────────────────────────────────────────────────

    record LineDiff(
        List<String>         removed,
        Map<String, int[]>   reduced,    // externalId → [oldQty, newQty]
        List<String>         added,
        Map<String, int[]>   increased   // externalId → [oldQty, newQty]
    ) {
        boolean isEmpty() {
            return removed.isEmpty() && reduced.isEmpty() && added.isEmpty() && increased.isEmpty();
        }
        /** Returns map: externalId → count to release (old - new) for reduced-qty items. */
        Map<String, Integer> releaseCountByExternalId() {
            Map<String, Integer> m = new LinkedHashMap<>();
            for (Map.Entry<String, int[]> e : reduced.entrySet()) {
                m.put(e.getKey(), e.getValue()[0] - e.getValue()[1]);
            }
            return m;
        }
    }

    private LineDiff computeLineDiff(Map<String, Integer> local, Map<String, Integer> incoming) {
        List<String>         removed   = new ArrayList<>();
        Map<String, int[]>   reduced   = new LinkedHashMap<>();
        List<String>         added     = new ArrayList<>();
        Map<String, int[]>   increased = new LinkedHashMap<>();

        for (Map.Entry<String, Integer> e : local.entrySet()) {
            String extId  = e.getKey();
            int    oldQty = e.getValue();
            if (!incoming.containsKey(extId)) {
                removed.add(extId);
            } else {
                int newQty = incoming.get(extId);
                if (newQty < oldQty) reduced.put(extId, new int[]{oldQty, newQty});
                if (newQty > oldQty) increased.put(extId, new int[]{oldQty, newQty});
            }
        }
        for (String extId : incoming.keySet()) {
            if (!local.containsKey(extId)) added.add(extId);
        }
        return new LineDiff(removed, reduced, added, increased);
    }

    private String serializeDiff(LineDiff diff) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("removed",   diff.removed());
            m.put("reduced",   diff.reduced());
            m.put("added",     diff.added());
            m.put("increased", diff.increased());
            return mapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            return "{}";
        }
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
        List<UUID> newVariants = syncService.ingestProductWebhook(storeId, tenantId, payload);
        // A variant added in Shopify after connect is activated at the Traced location right away
        // when its product is ACTIVE (the same policy as the connect import) — after the upsert has
        // committed; a failure is logged and never fails the product upsert. A draft / archived
        // product's new variants are activated lazily, before their first increment
        // (ShopifyInventoryService.applyIncrementAdjustment).
        boolean productActive = "active".equals(payload.path("status").asText("active").toLowerCase());
        if (!newVariants.isEmpty() && productActive) {
            try {
                ShopifyCatalogActivationService.ActivationOutcome outcome = activationService.activateVariants(newVariants);
                if (outcome.failed() > 0) {
                    log.warn("products webhook: activation failed for {} of {} new variant(s) shop={}: {}",
                        outcome.failed(), outcome.total(), shopDomain, outcome.failures());
                }
            } catch (Exception e) {
                log.warn("products webhook: could not activate {} new variant(s) at the Traced location shop={}: {}",
                    newVariants.size(), shopDomain, e.getMessage());
            }
        }
    }

    private void handleAppUninstalled(UUID tenantId, String shopDomain) {
        tx.execute(s -> {
            jdbc.update(DISCONNECT_STORE, shopDomain, tenantId);
            return null;
        });
        log.info("app/uninstalled: store disconnected shop={} tenant={}", shopDomain, tenantId);
    }

    /**
     * customers/data_request (GDPR build A): records the request (one per event — a re-process is a no-op),
     * then emails the tenant's owners that it's ready to download in Settings → Privacy. The email holds
     * no customer data; the export is built at download time.
     */
    private void handleDataRequest(UUID tenantId, UUID eventId, String shopDomain, JsonNode payload) {
        CustomerDataRequestService.Recorded r = dataRequests.record(tenantId, eventId, shopDomain, payload);
        log.info("GDPR data_request recorded: shop={} tenant={} request={} new={}", shopDomain, tenantId, r.id(), r.created());
        dataRequests.notifyOwners(tenantId, r.id());
    }

    private void handleCustomersRedact(UUID tenantId, String shopDomain, JsonNode payload) {
        // A1: orders are scoped to orders_to_redact — Shopify deliberately excludes recent/in-flight
        // orders from this list. Broad customer-match would blank address on active orders.
        // piece_events is INSERT-only and holds NO customer PII — must not be touched.
        List<String> gids = CustomerSubject.gidsOf(payload.path("orders_to_redact"));
        if (gids.isEmpty()) {
            log.info("customers/redact: orders_to_redact is empty — no orders to redact shop={} tenant={}",
                shopDomain, tenantId);
        }
        String customerId = payload.path("customer").path("id").asText(null);
        String phone = payload.path("customer").path("phone").asText(null);
        CustomerRedaction.Result r = tx.execute(s ->
            new CustomerRedaction(jdbc).redactCustomer(tenantId, gids, customerId, phone));
        log.info("customers/redact: shop={} tenant={} gids={} — orders={} requests={} shipments={} exchanges={} " +
                 "unlinked={} webhookEvents={} dataRequests={} blocklist={}", shopDomain, tenantId, gids.size(), r.orders(),
            r.returnRequests(), r.shipments(), r.exchanges(), r.unlinked(), r.webhookEvents(), r.dataRequests(), r.blocklist());
    }

    private void handleShopRedact(UUID tenantId, String shopDomain) {
        // Sent ~48h after app/uninstalled: erase ALL customer PII for this tenant.
        // piece_events is INSERT-only and holds NO customer PII — must not be touched.
        CustomerRedaction.Result r = tx.execute(s -> new CustomerRedaction(jdbc).redactShop(tenantId));
        log.info("shop/redact: erased all customer PII for tenant={} shop={} — orders={} requests={} shipments={} " +
                 "exchanges={} unlinked={} webhookEvents={} dataRequests={}", tenantId, shopDomain, r.orders(),
            r.returnRequests(), r.shipments(), r.exchanges(), r.unlinked(), r.webhookEvents(), r.dataRequests());
    }
}
