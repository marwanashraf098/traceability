package com.traceability.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.notifications.EmailGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shopify customers/data_request (GDPR build A, V143).
 *
 * <ol>
 *   <li>{@link #record} — one customer_data_requests row per webhook event (UNIQUE webhook_event_id, so a
 *       redelivery or a re-processed event is a no-op): Shopify customer id, phone, orders_requested as
 *       order GIDs, expires_at = created + 30 days. The payload's customer email is never stored.</li>
 *   <li>{@link #notifyOwners} — "a customer data request is ready, sign in to download" to the tenant's
 *       active owners. The email carries NO customer data (no name, phone, email, customer id or order).</li>
 *   <li>{@link #export} — the JSON export, built at download time (never stored), owner-only, tenant-scoped
 *       under RLS, refused once expired (410). Scope = {@link CustomerSubject} with phone matches: orders
 *       (columns + the PII keys of raw), their return requests, the Bosta copies (shipments, exchanges,
 *       unlinked deliveries), blocklist entries for the customer's phones, and the stored Shopify webhook
 *       payloads for those orders and this customer.</li>
 * </ol>
 * Every query runs in a transaction on the given JdbcTemplate with the tenant also named explicitly;
 * with the app's TenantAwareDataSource (or a test's app_user one) RLS applies too.
 */
@Service
public class CustomerDataRequestService {

    private static final Logger log = LoggerFactory.getLogger(CustomerDataRequestService.class);
    private static final DateTimeFormatter DAY =
        DateTimeFormatter.ofPattern("d MMMM yyyy").withZone(ZoneId.of("Africa/Cairo"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EmailGateway email;
    private final ObjectMapper mapper;
    private final String appUrl;

    @Autowired
    public CustomerDataRequestService(JdbcTemplate jdbc, PlatformTransactionManager txm, EmailGateway email,
                                      ObjectMapper mapper, @Value("${shopify.app-url}") String appUrl) {
        this(jdbc, new TransactionTemplate(txm), email, mapper, appUrl);
    }

    public CustomerDataRequestService(JdbcTemplate jdbc, TransactionTemplate tx, EmailGateway email,
                                      ObjectMapper mapper, String appUrl) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.email = email;
        this.mapper = mapper;
        this.appUrl = appUrl;
    }

    private com.traceability.portal.RefundDetailsCipher refundCipher;

    /** P2: decrypts return requests' refund details for the export. */
    @Autowired(required = false)
    public void setRefundDetailsCipher(com.traceability.portal.RefundDetailsCipher refundCipher) {
        this.refundCipher = refundCipher;
    }

    public record Recorded(UUID id, boolean created) {}

    /** Persists the request for this webhook event. Caller has the tenant context set. */
    public Recorded record(UUID tenantId, UUID webhookEventId, String shopDomain, JsonNode payload) {
        String customerId = textOrNull(payload.path("customer").path("id"));
        String phone = textOrNull(payload.path("customer").path("phone"));
        String[] gids = CustomerSubject.gidsOf(payload.path("orders_requested")).toArray(new String[0]);
        return tx.execute(s -> {
            UUID id = jdbc.query(
                "INSERT INTO customer_data_requests (tenant_id, webhook_event_id, shop_domain, shopify_customer_id, " +
                "    customer_phone, orders_requested) VALUES (?, ?, ?, ?, ?, ?::text[]) " +
                "ON CONFLICT (webhook_event_id) DO NOTHING RETURNING id",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                tenantId, webhookEventId, shopDomain, customerId, phone, gids);
            if (id != null) return new Recorded(id, true);
            UUID existing = jdbc.queryForObject(
                "SELECT id FROM customer_data_requests WHERE tenant_id = ? AND webhook_event_id = ?",
                UUID.class, tenantId, webhookEventId);
            return new Recorded(existing, false);
        });
    }

    /**
     * Emails the tenant's active owners that a request is ready — once (notified_at). A failed send is
     * logged and leaves notified_at NULL; the request itself is already saved and downloadable.
     */
    public void notifyOwners(UUID tenantId, UUID requestId) {
        Map<String, Object> req = tx.execute(s -> jdbc.queryForList(
            "SELECT shop_domain, expires_at, notified_at FROM customer_data_requests WHERE tenant_id = ? AND id = ?",
            tenantId, requestId).stream().findFirst().orElse(null));
        if (req == null || req.get("notified_at") != null) return;
        List<String> owners = tx.execute(s -> jdbc.queryForList(
            "SELECT email FROM users WHERE tenant_id = ? AND role = 'owner' AND active = true AND email IS NOT NULL",
            String.class, tenantId));
        if (owners == null || owners.isEmpty()) {
            log.warn("DATA_REQUEST_NOTIFY tenant={} request={} no active owner to email", tenantId, requestId);
            return;
        }
        String subject = "A customer data request is ready · Traced";
        String body = notificationBody((String) req.get("shop_domain"),
            ((Timestamp) req.get("expires_at")).toInstant());
        int sent = 0;
        for (String to : owners) {
            try {
                email.send(to, subject, body);
                sent++;
            } catch (Exception e) {
                log.warn("DATA_REQUEST_NOTIFY tenant={} request={} send failed: {}", tenantId, requestId, e.toString());
            }
        }
        if (sent > 0) {
            tx.execute(s -> jdbc.update(
                "UPDATE customer_data_requests SET notified_at = now() WHERE tenant_id = ? AND id = ?",
                tenantId, requestId));
        }
        log.info("DATA_REQUEST_NOTIFY tenant={} request={} owners={} sent={}", tenantId, requestId, owners.size(), sent);
    }

    /** No customer data in here — only the store, where to go, and until when. */
    String notificationBody(String shopDomain, Instant expiresAt) {
        String link = appUrl + "/settings?tab=privacy";
        return "<p>Shopify sent a customer data request for your store <strong>" + escape(shopDomain) + "</strong>.</p>"
            + "<p>The export is ready in Traced. Sign in and open <strong>Settings → Privacy</strong> to download it.</p>"
            + "<p><a href=\"" + escape(link) + "\">Open Traced</a></p>"
            + "<p>It stays available until " + DAY.format(expiresAt) + ". Send it to the customer from your own "
            + "email — Traced never contacts your customers.</p>";
    }

    /** The owner's list. No phone or customer data beyond Shopify's customer id and the order count. */
    public List<Map<String, Object>> list(UUID tenantId) {
        return tx.execute(s -> jdbc.query(
            "SELECT id, shopify_customer_id, cardinality(orders_requested) AS orders_requested, status, created_at, " +
            "       expires_at, expires_at > now() AS available, downloaded_at, notified_at, pii_redacted_at " +
            "FROM customer_data_requests WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 200",
            (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getObject("id", UUID.class));
                m.put("shopifyCustomerId", rs.getString("shopify_customer_id"));
                m.put("ordersRequested", rs.getInt("orders_requested"));
                m.put("status", rs.getString("status"));
                m.put("createdAt", instant(rs.getTimestamp("created_at")));
                m.put("expiresAt", instant(rs.getTimestamp("expires_at")));
                m.put("available", rs.getBoolean("available"));
                m.put("downloadedAt", instant(rs.getTimestamp("downloaded_at")));
                m.put("notifiedAt", instant(rs.getTimestamp("notified_at")));
                m.put("redacted", rs.getTimestamp("pii_redacted_at") != null);
                return m;
            }, tenantId));
    }

    public record Export(String filename, byte[] json) {}

    /** 404 when the request isn't this tenant's (RLS), 410 once expired. Marks it downloaded. */
    public Export export(UUID tenantId, UUID requestId, UUID userId) {
        return tx.execute(s -> {
            Map<String, Object> req = jdbc.queryForList(
                "SELECT id, shop_domain, shopify_customer_id, customer_phone, orders_requested, created_at, expires_at, " +
                "       expires_at > now() AS available, pii_redacted_at " +
                "FROM customer_data_requests WHERE tenant_id = ? AND id = ?", tenantId, requestId)
                .stream().findFirst().orElse(null);
            if (req == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Data request not found");
            if (!Boolean.TRUE.equals(req.get("available"))) {
                throw new ResponseStatusException(HttpStatus.GONE, "This data request expired after 30 days");
            }
            List<String> gids = toStrings(req.get("orders_requested"));
            String customerId = (String) req.get("shopify_customer_id");
            CustomerSubject subject = CustomerSubject.resolve(jdbc, tenantId, gids, (String) req.get("customer_phone"), true);

            Map<String, Object> out = new LinkedHashMap<>();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("request_id", requestId);
            meta.put("shop_domain", req.get("shop_domain"));
            meta.put("shopify_customer_id", customerId);
            meta.put("orders_requested", gids);
            meta.put("received_at", value(req.get("created_at")));
            meta.put("expires_at", value(req.get("expires_at")));
            meta.put("customer_redacted", req.get("pii_redacted_at") != null);
            meta.put("generated_at", Instant.now().toString());
            meta.put("scope", "Orders listed in the request plus orders with the customer's phone; their return "
                + "requests; Bosta shipments, exchanges and unlinked deliveries for them; blocklist entries for the "
                + "customer's phones; stored Shopify webhook payloads for those orders and this customer.");
            out.put("request", meta);

            out.put("orders", rows(
                "SELECT o.id, o.number, o.external_id, o.placed_at, o.customer_name, o.customer_phone, o.address::text AS address, " +
                "       o.shopify_address::text AS shopify_address, " +
                "       o.pii_source, o.pii_redacted_at, " +
                "       (SELECT jsonb_object_agg(e.key, e.value)::text " +
                "          FROM jsonb_each(CASE WHEN jsonb_typeof(o.raw) = 'object' THEN o.raw ELSE '{}'::jsonb END) e " +
                "         WHERE NOT jsonb_exists(shopify_order_raw_redacted(o.raw), e.key)) AS shopify_customer_data " +
                "FROM orders o WHERE o.tenant_id = ? AND o.id = ANY(?::uuid[]) ORDER BY o.placed_at",
                List.of("address", "shopify_address", "shopify_customer_data"), tenantId, subject.orderIdArray()));

            List<Map<String, Object>> requests = rows(
                "SELECT rr.id, rr.reference, o.number AS order_number, rr.type, rr.status::text AS status, rr.created_at, " +
                "       rr.customer_email, rr.customer_note, rr.pickup_address_source, rr.pickup_city_name, " +
                "       rr.pickup_district_name, rr.custom_first_line, rr.custom_second_line, rr.custom_building_number, " +
                "       rr.custom_floor, rr.custom_apartment, rr.pii_redacted_at, " +
                "       rr.refund_method, rr.refund_details_purged_at, rr.refund_details_encrypted " +
                "FROM return_requests rr JOIN orders o ON o.id = rr.order_id AND o.tenant_id = rr.tenant_id " +
                "WHERE rr.tenant_id = ? AND rr.order_id = ANY(?::uuid[]) ORDER BY rr.created_at",
                List.of(), tenantId, subject.orderIdArray());
            // P2: the refund details the customer gave, decrypted, while they still exist.
            for (Map<String, Object> r : requests) {
                Object encrypted = r.remove("refund_details_encrypted");
                r.put("refund_details", encrypted == null || refundCipher == null ? null
                    : json(refundCipher.decrypt(tenantId, UUID.fromString((String) r.get("id")), (String) encrypted)));
            }
            out.put("return_requests", requests);

            out.put("shipments", rows(
                "SELECT s.tracking_number, o.number AS order_number, s.shipment_leg::text AS shipment_leg, " +
                "       s.internal_state::text AS internal_state, s.created_at, s.pii_redacted_at, " +
                bostaPii("s") + " AS customer_data " +
                "FROM shipments s JOIN orders o ON o.id = s.order_id AND o.tenant_id = s.tenant_id " +
                "WHERE s.tenant_id = ? AND s.order_id = ANY(?::uuid[]) ORDER BY s.created_at",
                List.of("customer_data"), tenantId, subject.orderIdArray()));

            out.put("exchanges", rows(
                "SELECT e.tracking_number, e.status::text AS status, e.created_at, e.pii_redacted_at, " +
                bostaPii("e") + " AS customer_data " +
                "FROM exchanges e WHERE e.tenant_id = ? AND e.id = ANY(?::uuid[]) ORDER BY e.created_at",
                List.of("customer_data"), tenantId, subject.exchangeIdArray()));

            Object[] unlinkedArgs = new Object[]{tenantId};
            unlinkedArgs = concat(unlinkedArgs, subject.unlinkedArgs());
            out.put("unlinked_bosta_deliveries", rows(
                "SELECT u.tracking_number, u.business_reference, u.first_seen_at, u.pii_redacted_at, " +
                bostaPii("u") + " AS customer_data " +
                "FROM unlinked_bosta_deliveries u WHERE u.tenant_id = ? AND " + CustomerSubject.UNLINKED_MATCH +
                " ORDER BY u.first_seen_at",
                List.of("customer_data"), unlinkedArgs));

            out.put("blocklist", rows(
                "SELECT phone_canonical, reason, source::text AS source, active, created_at " +
                "FROM blocklist WHERE tenant_id = ? AND phone_canonical = ANY(?::text[]) ORDER BY created_at",
                List.of(), tenantId, subject.phoneArray()));

            out.put("shopify_webhook_payloads", rows(
                "SELECT id, topic, received_at, payload_raw::text AS payload " +
                "FROM shopify_webhook_events WHERE tenant_id = ? AND (" +
                "  (topic LIKE 'orders/%' AND payload_raw ->> 'admin_graphql_api_id' = ANY(?::text[])) " +
                "  OR (topic LIKE 'customers/%' AND ?::text IS NOT NULL AND payload_raw #>> '{customer,id}' = ?::text)) " +
                "ORDER BY received_at",
                List.of("payload"), tenantId, subject.gidArray(), customerId, customerId));

            jdbc.update(
                "UPDATE customer_data_requests SET status = 'downloaded', downloaded_at = COALESCE(downloaded_at, now()), " +
                "    downloaded_by = COALESCE(downloaded_by, ?) WHERE tenant_id = ? AND id = ?",
                userId, tenantId, requestId);
            log.info("DATA_REQUEST_EXPORT tenant={} request={} by={} orders={}", tenantId, requestId, userId,
                subject.orderIds().size());

            try {
                byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(out);
                String day = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.of("Africa/Cairo"))
                    .format(((Timestamp) req.get("created_at")).toInstant());
                return new Export("traced-customer-data-request-" + day + "-" +
                    requestId.toString().substring(0, 8) + ".json", bytes);
            } catch (Exception e) {
                throw new IllegalStateException("Could not build the export", e);
            }
        });
    }

    // The customer part of a Bosta delivery raw — what bosta_raw_redacted() strips, plus the area names.
    private static String bostaPii(String alias) {
        return "jsonb_strip_nulls(jsonb_build_object(" +
            "'receiver', " + alias + ".raw -> 'receiver', 'notes', " + alias + ".raw -> 'notes', " +
            "'dropOffAddress', " + alias + ".raw -> 'dropOffAddress', 'pickupAddress', " + alias + ".raw -> 'pickupAddress', " +
            "'returnAddress', " + alias + ".raw -> 'returnAddress'))::text";
    }

    private List<Map<String, Object>> rows(String sql, List<String> jsonCols, Object... args) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(sql, args)) {
            Map<String, Object> m = new LinkedHashMap<>();
            r.forEach((k, v) -> m.put(k, jsonCols.contains(k) ? json(v) : value(v)));
            out.add(m);
        }
        return out;
    }

    private Object json(Object v) {
        if (v == null) return null;
        try { return mapper.readTree(v.toString()); }
        catch (Exception e) { return v.toString(); }
    }

    private static Object value(Object v) {
        if (v == null) return null;
        if (v instanceof Timestamp t) return t.toInstant().toString();
        if (v instanceof UUID u) return u.toString();
        if (v instanceof Array) return toStrings(v);
        return v;
    }

    private static List<String> toStrings(Object arr) {
        if (arr == null) return List.of();
        try {
            Object a = arr instanceof Array sa ? sa.getArray() : arr;
            if (a instanceof Object[] oa) return Arrays.stream(oa).map(String::valueOf).toList();
        } catch (Exception ignored) { }
        return List.of();
    }

    private static Object[] concat(Object[] a, Object[] b) {
        Object[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String instant(Timestamp t) { return t == null ? null : t.toInstant().toString(); }

    private static String textOrNull(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
