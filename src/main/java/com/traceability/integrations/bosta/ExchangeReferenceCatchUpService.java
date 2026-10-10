package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.inventory.ExchangeMatchService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-off catch-up for reference linking (2026-10-05) — the rows the shared reference rule
 * (OrderReference) now links but that were decided before it existed. Three kinds, in this order:
 * <ol>
 *   <li>{@code exchange} — dashboard exchanges (return_request_id NULL) in needs_mapping / mapped /
 *       unmatched / needs_confirmation with no matched_order_id → ExchangeMatchService.matchByReference
 *       (the live rule, unchanged).</li>
 *   <li>{@code crp} — open unlinked customer-return pickups (type 25) whose reference resolves to exactly
 *       one order → re-run through the normal ingest pipeline: a 'bosta_backfill' webhook event with its
 *       own idempotency key, processed by BostaWebhookJob (verify-by-fetch, pre-connect filter,
 *       tryMatchDelivery → return leg, unlinked row resolved). No matcher-version bump, no migration.</li>
 *   <li>{@code fulfillment} — order_fulfillment_tracking rows in conflict / skipped whose tracking number is
 *       a shipment on an internal exchange order → BostaFulfillmentLinkService.attempt (the "linked via
 *       exchange EXC-…" verdict; no Bosta call on that path).</li>
 * </ol>
 * Dry run (the default) writes nothing: WOULD_MATCH / WOULD_LINK or SKIP + reason. Apply writes through
 * conditional updates, so a rerun finds nothing left to do. Never moves a piece or writes the ledger —
 * a CRP becomes a return leg, which never moves pieces (BostaWebhookJob.applyMappedState).
 *
 * Never scheduled: owner POST /api/v1/bosta/exchange-reference/catch-up?apply=…, or the ops startup
 * trigger (bosta.exchange-reference.catch-up.on-startup + .apply). One {@code EXCHANGE_REF_CATCHUP {json}}
 * line per row (tenant, kind, tracking, reference, order, verdict, reason) and a summary line.
 */
@Service
public class ExchangeReferenceCatchUpService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeReferenceCatchUpService.class);

    public record Row(String tenant, String kind, String trackingNumber, String reference,
                      String orderNumber, String verdict, String reason) {}

    private final JdbcTemplate                jdbc;
    private final TransactionTemplate         tx;
    private final ExchangeMatchService        exchangeMatch;
    private final BostaWebhookJob             webhookJob;
    private final BostaFulfillmentLinkService fulfillmentLink;
    private final ObjectMapper                mapper;

    public ExchangeReferenceCatchUpService(JdbcTemplate jdbc, PlatformTransactionManager txm,
                                           ExchangeMatchService exchangeMatch, BostaWebhookJob webhookJob,
                                           BostaFulfillmentLinkService fulfillmentLink, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.exchangeMatch = exchangeMatch;
        this.webhookJob = webhookJob;
        this.fulfillmentLink = fulfillmentLink;
        this.mapper = mapper;
    }

    @Job(name = "Exchange / CRP reference catch-up — tenant %0 (apply=%1)", retries = 0)
    public void runAndLog(UUID tenantId, boolean apply) {
        Map<String, Integer> byVerdict = new LinkedHashMap<>();
        int[] count = {0};
        String error = null;
        try {
            run(tenantId, apply, r -> {
                log.info("EXCHANGE_REF_CATCHUP {}", json(r));
                byVerdict.merge(r.kind() + ":" + r.verdict(), 1, Integer::sum);
                count[0]++;
            });
        } catch (RuntimeException e) {
            error = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            log.error("Exchange reference catch-up tenant {} stopped: {}", tenantId, error, e);
        } finally {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("tenantId", tenantId.toString());
            summary.put("apply", apply);
            summary.put("rows", count[0]);
            summary.put("verdicts", byVerdict);
            if (error != null) summary.put("error", error);
            log.info("EXCHANGE_REF_CATCHUP_SUMMARY {}", json(summary));
        }
    }

    public List<Row> run(UUID tenantId, boolean apply) {
        List<Row> out = new ArrayList<>();
        run(tenantId, apply, out::add);
        return out;
    }

    private void run(UUID tenantId, boolean apply, java.util.function.Consumer<Row> sink) {
        String tenant = TenantContext.runAs(tenantId, () -> tx.execute(s ->
            jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, tenantId)));

        // 1. Dashboard exchanges without their original order.
        List<String> exchanges = TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.queryForList(
            "SELECT tracking_number FROM exchanges WHERE tenant_id = ? AND return_request_id IS NULL " +
            "  AND matched_order_id IS NULL " +
            "  AND status IN ('needs_mapping', 'mapped', 'unmatched', 'needs_confirmation') " +
            "ORDER BY created_at, tracking_number", String.class, tenantId)));
        for (String tn : exchanges) {
            isolated(sink, tenant, "exchange", tn, () -> TenantContext.runAs(tenantId, () -> tx.execute(s -> {
                ExchangeMatchService.ReferenceMatch m = exchangeMatch.matchByReference(tenantId, tn, !apply);
                boolean found = m.verdict() == ExchangeMatchService.ReferenceVerdict.MATCHED
                    || m.verdict() == ExchangeMatchService.ReferenceVerdict.WOULD_MATCH;
                return new Row(tenant, "exchange", tn, m.reference(), m.orderNumber(),
                    found ? m.verdict().name() : "SKIP", found ? null : m.verdict().name());
            })));
        }

        // 2. Open unlinked customer-return pickups.
        List<Map<String, Object>> crps = TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.queryForList(
            "SELECT tracking_number, business_reference AS ref, raw->'shopifyInfo'->>'orderId' AS shopify_id, " +
            "       bosta_state_code, raw->>'updatedAt' AS updated_at " +
            "FROM unlinked_bosta_deliveries WHERE tenant_id = ? AND NOT resolved " +
            "  AND raw->'type'->>'code' = '25' ORDER BY tracking_number", tenantId)));
        for (Map<String, Object> c : crps) {
            String tn = (String) c.get("tracking_number");
            String ref = (String) c.get("ref");
            isolated(sink, tenant, "crp", tn, () -> crp(tenantId, tenant, tn, ref, (String) c.get("shopify_id"),
                (Integer) c.get("bosta_state_code"), (String) c.get("updated_at"), apply));
        }

        // 3. Fulfillment rows blocked by an exchange's EXC-… order.
        List<Map<String, Object>> ful = TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.queryForList(
            "SELECT ft.order_id, ft.tracking_number, o.number FROM order_fulfillment_tracking ft " +
            "JOIN merchant_orders o ON o.id = ft.order_id AND o.tenant_id = ft.tenant_id " +
            "WHERE ft.tenant_id = ? AND ft.link_status IN ('conflict', 'skipped') " +
            "  AND EXISTS (SELECT 1 FROM shipments s JOIN merchant_orders x ON x.id = s.order_id AND x.tenant_id = s.tenant_id " +
            "              WHERE s.tenant_id = ft.tenant_id AND s.tracking_number = ft.tracking_number " +
            "                AND s.order_id <> ft.order_id AND x.external_id LIKE 'internal:exchange:%') " +
            "ORDER BY ft.first_seen_at, ft.tracking_number", tenantId)));
        for (Map<String, Object> f : ful) {
            String tn = (String) f.get("tracking_number");
            String number = (String) f.get("number");
            isolated(sink, tenant, "fulfillment", tn, () -> {
                BostaFulfillmentLinkService.Result r =
                    fulfillmentLink.attempt(tenantId, (UUID) f.get("order_id"), tn, !apply);
                return new Row(tenant, "fulfillment", tn, null, number, r.verdict().name(), r.reason());
            });
        }
    }

    private Row crp(UUID tenantId, String tenant, String tn, String ref, String shopifyId,
                    Integer stateCode, String updatedAt, boolean apply) {
        List<UUID> ids = TenantContext.runAs(tenantId, () -> tx.execute(s ->
            OrderReference.resolve(jdbc, tenantId, ref, shopifyId)));
        if ((ref == null || ref.isBlank()) && shopifyId == null) return new Row(tenant, "crp", tn, ref, null, "SKIP", "no reference");
        if (ids.isEmpty()) return new Row(tenant, "crp", tn, ref, null, "SKIP", "reference matches no order");
        if (ids.size() > 1) return new Row(tenant, "crp", tn, ref, null, "SKIP", "reference matches more than one order");
        UUID orderId = ids.get(0);
        String number = TenantContext.runAs(tenantId, () -> tx.execute(s ->
            jdbc.queryForObject("SELECT number FROM merchant_orders WHERE id = ?", String.class, orderId)));
        if (!apply) return new Row(tenant, "crp", tn, ref, number, "WOULD_LINK", null);

        Long eventId = TenantContext.runAs(tenantId, () -> insertEvent(tenantId, tn, stateCode, updatedAt));
        if (eventId != null) {
            webhookJob.process(eventId, tenantId);
        }
        Map<String, Object> linked = TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.queryForList(
            "SELECT o.number FROM shipments sh JOIN merchant_orders o ON o.id = sh.order_id AND o.tenant_id = sh.tenant_id " +
            "WHERE sh.tenant_id = ? AND sh.tracking_number = ? AND sh.shipment_leg = 'return'", tenantId, tn)
            .stream().findFirst().orElse(null)));
        if (linked != null) return new Row(tenant, "crp", tn, ref, (String) linked.get("number"), "LINKED", null);
        String note = eventId == null ? "an identical catch-up event already exists"
            : TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.queryForObject(
                "SELECT coalesce(error, status::text) FROM webhook_events WHERE id = ?", String.class, eventId)));
        return new Row(tenant, "crp", tn, ref, number, "SKIP", "not linked: " + note);
    }

    /**
     * A 'bosta_backfill' event (always verified by fetch) whose updatedAt carries a catch-up marker, so
     * its idempotency key never collides with the event that recorded the delivery unlinked. ON CONFLICT
     * → null: the same catch-up event exists already.
     */
    private Long insertEvent(UUID tenantId, String tn, Integer stateCode, String bostaUpdatedAt) {
        String updatedAt = (bostaUpdatedAt == null ? "backfill-epoch" : bostaUpdatedAt) + "|reference-catch-up";
        int state = stateCode == null ? -1 : stateCode;
        ObjectNode payload = mapper.createObjectNode();
        payload.put("trackingNumber", tn);
        payload.put("state", state);
        payload.put("type", "CUSTOMER_RETURN_PICKUP");
        payload.put("updatedAt", updatedAt);
        String idemKey = BostaWebhookJob.sha256(tn + ":" + state + ":" + updatedAt);
        return tx.execute(s -> jdbc.query(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at, external_event_id) " +
            "VALUES ('bosta_backfill'::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending', now(), ?) " +
            "ON CONFLICT (source, external_event_id) WHERE external_event_id IS NOT NULL DO NOTHING " +
            "RETURNING id",
            rs -> rs.next() ? rs.getLong(1) : null,
            tenantId, payload.toString(), idemKey));
    }

    private void isolated(java.util.function.Consumer<Row> sink, String tenant, String kind, String tn,
                          java.util.function.Supplier<Row> body) {
        try {
            sink.accept(body.get());
        } catch (RuntimeException e) {
            log.warn("Exchange reference catch-up {} {} failed: {}", kind, tn, e.toString());
            sink.accept(new Row(tenant, kind, tn, null, null, "ERROR",
                e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "")));
        }
    }

    private String json(Object o) {
        try { return mapper.writeValueAsString(o); } catch (Exception e) { return String.valueOf(o); }
    }
}
