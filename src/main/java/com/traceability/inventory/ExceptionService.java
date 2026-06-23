package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Exceptions center (FR-15.3): aggregates 8 exception detectors into one
 * prioritised list, sorted CRITICAL→HIGH→MEDIUM→LOW then oldest-first within
 * each severity tier.
 *
 * Aggregation: per-type queries run separately and merged in Java.
 * A single UNION would require all 8 queries to emit identical columns,
 * and the stuck-shipment resolved_at>last_synced_at staleness check is
 * awkward to express generically. Per-type Java merge is readable,
 * independently testable, and trivially extensible.
 *
 * Resolution suppression: each detector has a NOT EXISTS sub-query against
 * exception_resolutions keyed on (exception_type, subject_key).
 * Stuck shipments use an additional resolved_at > last_synced_at guard so
 * a post-ack Bosta sync reactivates the exception.
 */
@Service
public class ExceptionService {

    private static final Map<String, Integer> SEVERITY_ORDER = Map.of(
        "CRITICAL", 0,
        "HIGH",     1,
        "MEDIUM",   2,
        "LOW",      3
    );

    private final JdbcTemplate jdbc;

    public ExceptionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public Map<String, Object> listExceptions(String typeFilter, String severityFilter,
                                               int page, int size) {
        UUID tenantId = TenantContext.require();

        // Per-tenant config
        Map<String, Object> cfg = jdbc.queryForMap(
            "SELECT never_received_window_days, stuck_shipment_days " +
            "FROM tenants WHERE id = ?", tenantId);
        int neverReceivedDays = ((Number) cfg.get("never_received_window_days")).intValue();
        int stuckDays         = ((Number) cfg.get("stuck_shipment_days")).intValue();

        // Collect all open exceptions
        List<Map<String, Object>> all = new ArrayList<>();
        all.addAll(detectLost(tenantId));
        all.addAll(detectNeverReceived(tenantId, neverReceivedDays));
        all.addAll(detectUnmatched(tenantId));
        all.addAll(detectBlocked(tenantId));
        all.addAll(detectStuck(tenantId, stuckDays));
        all.addAll(detectUnexpectedReturn(tenantId));
        all.addAll(detectDeliveryLimbo(tenantId));
        all.addAll(detectNdr(tenantId));
        all.addAll(detectGuidedUnpack(tenantId));
        all.addAll(detectMissingAwb(tenantId));
        all.addAll(detectShopifyCancelVsInflight(tenantId));
        all.addAll(detectMissingProviderId(tenantId));
        all.addAll(detectHighAttempts(tenantId));

        // Enrich with descriptions and action hints
        all.forEach(this::enrich);

        // Optional filters
        if (typeFilter != null && !typeFilter.isBlank()) {
            all = all.stream()
                .filter(e -> typeFilter.equals(e.get("type")))
                .collect(java.util.stream.Collectors.toList());
        }
        if (severityFilter != null && !severityFilter.isBlank()) {
            all = all.stream()
                .filter(e -> severityFilter.equals(e.get("severity")))
                .collect(java.util.stream.Collectors.toList());
        }

        // Sort: severity asc (CRITICAL first), then occurredAt asc (oldest first)
        Instant epoch = Instant.EPOCH;
        all.sort(Comparator
            .comparingInt((Map<String, Object> e) ->
                SEVERITY_ORDER.getOrDefault((String) e.get("severity"), 99))
            .thenComparing(e -> toInstant(e.get("occurred_at"), epoch)));

        // Enrich with ageSeconds
        Instant now = Instant.now();
        all.forEach(e -> e.put("ageSeconds",
            Duration.between(toInstant(e.get("occurred_at"), now), now).getSeconds()));

        // Paginate
        int total  = all.size();
        int from   = Math.min(page * size, total);
        int to     = Math.min(from + size, total);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("page",  page);
        result.put("size",  size);
        result.put("items", all.subList(from, to));
        return result;
    }

    public void resolve(String exceptionType, String subjectKey, UUID resolvedBy, String note) {
        UUID tenantId = TenantContext.require();
        jdbc.update(
            "INSERT INTO exception_resolutions " +
            "(tenant_id, exception_type, subject_key, resolved_by, note) " +
            "VALUES (?, ?, ?, ?, ?)",
            tenantId, exceptionType, subjectKey, resolvedBy, note);
    }

    public List<Map<String, Object>> listResolutions(int page, int size) {
        UUID tenantId = TenantContext.require();
        return jdbc.queryForList(
            "SELECT er.id, er.exception_type, er.subject_key, er.resolved_at, er.note, " +
            "       u.name AS resolved_by_name " +
            "FROM exception_resolutions er " +
            "JOIN users u ON u.id = er.resolved_by " +
            "WHERE er.tenant_id = ? " +
            "ORDER BY er.resolved_at DESC LIMIT ? OFFSET ?",
            tenantId, size, (long) page * size);
    }

    // ── Detectors ─────────────────────────────────────────────────────────────

    private List<Map<String, Object>> detectLost(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'lost' AS type, 'CRITICAL' AS severity, 'piece' AS subject_type, " +
            "       p.id AS piece_id, p.barcode, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       s.id AS shipment_id, s.tracking_number, " +
            "       COALESCE(p.last_event_at, p.created_at) AS occurred_at, " +
            "       'lost:piece:' || p.id AS subject_key " +
            "FROM pieces p " +
            "LEFT JOIN orders o ON o.id = p.current_order_id " +
            "LEFT JOIN shipments s ON s.order_id = o.id " +
            "WHERE p.status = 'lost'::piece_status " +
            "  AND p.tenant_id = ? " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id = p.tenant_id " +
            "        AND er.exception_type = 'lost' " +
            "        AND er.subject_key = 'lost:piece:' || p.id) ",
            tid);
    }

    private List<Map<String, Object>> detectNeverReceived(UUID tid, int windowDays) {
        return jdbc.queryForList(
            "SELECT 'never_received' AS type, 'HIGH' AS severity, 'piece' AS subject_type, " +
            "       p.id AS piece_id, p.barcode, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       s.tracking_number, s.returned_at AS occurred_at, " +
            "       'never_received:piece:' || p.id AS subject_key " +
            "FROM shipments s " +
            "JOIN orders o ON o.id = s.order_id AND o.tenant_id = ? " +
            "JOIN order_items oi ON oi.order_id = o.id " +
            "JOIN allocations a  ON a.order_item_id = oi.id " +
            "                    AND a.status IN ('packed','active') " +
            "JOIN pieces p ON p.id = a.piece_id AND p.tenant_id = ? " +
            "WHERE s.internal_state = 'returned' " +
            "  AND s.returned_at IS NOT NULL " +
            "  AND s.returned_at < now() - (interval '1 day' * ?) " +
            "  AND s.tenant_id = ? " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM piece_events pe " +
            "      WHERE pe.piece_id = p.id AND pe.event_type = 'return_received' " +
            "        AND pe.tenant_id = ?) " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id = p.tenant_id " +
            "        AND er.exception_type = 'never_received' " +
            "        AND er.subject_key = 'never_received:piece:' || p.id) ",
            tid, tid, windowDays, tid, tid);
    }

    private List<Map<String, Object>> detectUnmatched(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'unmatched_delivery' AS type, 'MEDIUM' AS severity, 'delivery' AS subject_type, " +
            "       u.id AS unlinked_id, u.tracking_number, u.business_reference, " +
            "       u.bosta_state_code, u.first_seen_at AS occurred_at, " +
            "       'unmatched:' || u.id AS subject_key " +
            "FROM unlinked_bosta_deliveries u " +
            "WHERE u.tenant_id = ? AND u.resolved = false " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id = u.tenant_id " +
            "        AND er.exception_type = 'unmatched_delivery' " +
            "        AND er.subject_key = 'unmatched:' || u.id) ",
            tid);
    }

    private List<Map<String, Object>> detectBlocked(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'blocked_customer' AS type, 'LOW' AS severity, 'order' AS subject_type, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       o.customer_name, o.hold_reason, o.created_at AS occurred_at, " +
            "       'blocked:' || o.id AS subject_key " +
            "FROM orders o " +
            "WHERE o.tenant_id = ? AND o.on_hold = true " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id = o.tenant_id " +
            "        AND er.exception_type = 'blocked_customer' " +
            "        AND er.subject_key = 'blocked:' || o.id) ",
            tid);
    }

    private List<Map<String, Object>> detectStuck(UUID tid, int stuckDays) {
        return jdbc.queryForList(
            "SELECT 'stuck_shipment' AS type, 'HIGH' AS severity, 'shipment' AS subject_type, " +
            "       s.id AS shipment_id, s.tracking_number, " +
            "       s.internal_state::text AS shipment_state, " +
            "       s.number_of_attempts, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       COALESCE(s.last_synced_at, s.created_at) AS occurred_at, " +
            "       'stuck:shipment:' || s.id AS subject_key " +
            "FROM shipments s " +
            "JOIN orders o ON o.id = s.order_id AND o.tenant_id = ? " +
            "WHERE s.internal_state NOT IN (" +
            "      'delivered'::shipment_internal_state," +
            "      'returned'::shipment_internal_state," +
            "      'lost'::shipment_internal_state," +
            "      'terminated'::shipment_internal_state," +
            "      'cancelled'::shipment_internal_state) " +
            "  AND COALESCE(s.last_synced_at, s.created_at) < now() - (interval '1 day' * ?) " +
            "  AND s.tenant_id = ? " +
            // A resolved_at newer than last_synced_at means the operator ack'd the
            // latest known state — suppress.  If Bosta syncs AFTER the ack,
            // resolved_at < new last_synced_at and the NOT EXISTS finds no valid row.
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id = s.tenant_id " +
            "        AND er.exception_type = 'stuck_shipment' " +
            "        AND er.subject_key = 'stuck:shipment:' || s.id " +
            "        AND er.resolved_at > COALESCE(s.last_synced_at, s.created_at)) ",
            tid, stuckDays, tid);
    }

    private List<Map<String, Object>> detectUnexpectedReturn(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'unexpected_return' AS type, 'HIGH' AS severity, 'piece' AS subject_type, " +
            "       p.id AS piece_id, p.barcode, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       pe.occurred_at, " +
            "       'unexpected_return:' || p.id AS subject_key " +
            "FROM pieces p " +
            "JOIN piece_events pe ON pe.piece_id = p.id " +
            "    AND pe.event_type = 'return_received' " +
            "    AND pe.from_status IN ('with_courier'::piece_status, 'awaiting_pickup'::piece_status) " +
            "LEFT JOIN orders o ON o.id = p.current_order_id " +
            "WHERE p.tenant_id = ? " +
            "  AND p.status = 'return_pending_inspection'::piece_status " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id = p.tenant_id " +
            "        AND er.exception_type = 'unexpected_return' " +
            "        AND er.subject_key = 'unexpected_return:' || p.id) ",
            tid);
    }

    private List<Map<String, Object>> detectDeliveryLimbo(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'delivery_limbo' AS type, 'HIGH' AS severity, 'shipment' AS subject_type, " +
            "       s.id AS shipment_id, s.tracking_number, " +
            "       s.number_of_attempts, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       COALESCE(s.last_synced_at, s.created_at) AS occurred_at, " +
            "       'delivery_limbo:shipment:' || s.id AS subject_key " +
            "FROM shipments s " +
            "JOIN orders o ON o.id = s.order_id AND o.tenant_id = ? " +
            "WHERE s.provider_state = 103 " +
            "  AND s.tenant_id = ? " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id = s.tenant_id " +
            "        AND er.exception_type = 'delivery_limbo' " +
            "        AND er.subject_key = 'delivery_limbo:shipment:' || s.id) ",
            tid, tid);
    }

    private List<Map<String, Object>> detectNdr(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'ndr_failed' AS type, " +
            "       CASE WHEN nc.severity = 'critical' THEN 'CRITICAL' ELSE 'MEDIUM' END AS severity, " +
            "       'shipment' AS subject_type, " +
            "       s.id AS shipment_id, s.tracking_number, " +
            "       s.number_of_attempts, " +
            "       CASE WHEN s.raw->>'exceptionCode' IS NOT NULL " +
            "            THEN (s.raw->>'exceptionCode')::integer END AS ndr_code, " +
            "       nc.description AS ndr_description, " +
            "       nc.category AS ndr_category, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       COALESCE(s.last_synced_at, s.created_at) AS occurred_at, " +
            "       'ndr:shipment:' || s.id AS subject_key " +
            "FROM shipments s " +
            "JOIN orders o ON o.id = s.order_id AND o.tenant_id = ? " +
            "LEFT JOIN ndr_codes nc " +
            "    ON nc.code = CASE WHEN s.raw->>'exceptionCode' IS NOT NULL " +
            "                      THEN (s.raw->>'exceptionCode')::integer END " +
            "WHERE s.provider_state = 47 " +
            "  AND s.internal_state = 'exception'::shipment_internal_state " +
            "  AND s.tenant_id = ? " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id = s.tenant_id " +
            "        AND er.exception_type = 'ndr_failed' " +
            "        AND er.subject_key = 'ndr:shipment:' || s.id) ",
            tid, tid);
    }

    private List<Map<String, Object>> detectGuidedUnpack(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'guided_unpack' AS type, 'HIGH' AS severity, 'order' AS subject_type, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       o.cancel_requested_at AS occurred_at, " +
            "       'guided_unpack:order:' || o.id AS subject_key " +
            "FROM orders o " +
            "WHERE o.tenant_id = ? " +
            "  AND o.cancel_requested_at IS NOT NULL " +
            "  AND o.status IN ('packed'::order_status, 'self_pickup_pending'::order_status)",
            tid);
    }

    private List<Map<String, Object>> detectShopifyCancelVsInflight(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'shopify_cancel_vs_inflight' AS type, 'HIGH' AS severity, 'order' AS subject_type, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       s.tracking_number, " +
            "       o.shopify_cancel_requested_at AS occurred_at, " +
            "       'shopify_cancel_vs_inflight:order:' || o.id AS subject_key " +
            "FROM orders o " +
            "LEFT JOIN shipments s ON s.order_id = o.id AND s.tenant_id = o.tenant_id " +
            "WHERE o.tenant_id = ? " +
            "  AND o.shopify_cancel_requested_at IS NOT NULL " +
            "  AND o.status = 'awaiting_pickup'::order_status " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id = o.tenant_id " +
            "        AND er.exception_type = 'shopify_cancel_vs_inflight' " +
            "        AND er.subject_key = 'shopify_cancel_vs_inflight:order:' || o.id) ",
            tid);
    }

    private List<Map<String, Object>> detectMissingAwb(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'missing_awb' AS type, 'MEDIUM' AS severity, 'shipment' AS subject_type, " +
            "       s.id AS shipment_id, s.tracking_number, " +
            "       s.awb_print_failed_reason AS failed_reason, " +
            "       s.awb_print_failed_at AS occurred_at, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       'missing_awb:shipment:' || s.id AS subject_key " +
            "FROM shipments s " +
            "JOIN orders o ON o.id = s.order_id AND o.tenant_id = ? " +
            "WHERE s.awb_print_failed_reason IS NOT NULL " +
            "  AND s.tenant_id = ? " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id = s.tenant_id " +
            "        AND er.exception_type = 'missing_awb' " +
            "        AND er.subject_key = 'missing_awb:shipment:' || s.id " +
            "        AND er.resolved_at > COALESCE(s.awb_print_failed_at, s.created_at)) ",
            tid, tid);
    }

    private List<Map<String, Object>> detectHighAttempts(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'high_attempts' AS type, 'MEDIUM' AS severity, 'shipment' AS subject_type, " +
            "       s.id AS shipment_id, s.tracking_number, " +
            "       s.number_of_attempts, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       COALESCE(s.last_synced_at, s.created_at) AS occurred_at, " +
            "       'high_attempts:shipment:' || s.id AS subject_key " +
            "FROM shipments s " +
            "JOIN orders o ON o.id = s.order_id AND o.tenant_id = ? " +
            "WHERE s.number_of_attempts >= 2 " +
            "  AND s.internal_state NOT IN ( " +
            "      'delivered'::shipment_internal_state," +
            "      'returned'::shipment_internal_state," +
            "      'lost'::shipment_internal_state," +
            "      'terminated'::shipment_internal_state," +
            "      'cancelled'::shipment_internal_state) " +
            "  AND s.tenant_id = ? " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id = s.tenant_id " +
            "        AND er.exception_type = 'high_attempts' " +
            "        AND er.subject_key = 'high_attempts:shipment:' || s.id) ",
            tid, tid);
    }

    private List<Map<String, Object>> detectMissingProviderId(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'missing_provider_id' AS type, 'MEDIUM' AS severity, 'shipment' AS subject_type, " +
            "       s.id AS shipment_id, s.tracking_number, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       s.created_at AS occurred_at, " +
            "       'missing_provider_id:shipment:' || s.id AS subject_key " +
            "FROM shipments s " +
            "JOIN orders o ON o.id = s.order_id " +
            "WHERE s.tenant_id = ? " +
            "  AND s.provider_id_fetch_failed = true " +
            "  AND s.provider_delivery_id IS NULL " +
            "  AND s.internal_state NOT IN ( " +
            "      'delivered'::shipment_internal_state, " +
            "      'returned'::shipment_internal_state, " +
            "      'lost'::shipment_internal_state, " +
            "      'terminated'::shipment_internal_state, " +
            "      'cancelled'::shipment_internal_state) " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id = s.tenant_id " +
            "        AND er.exception_type = 'missing_provider_id' " +
            "        AND er.subject_key = 'missing_provider_id:shipment:' || s.id) ",
            tid);
    }

    // ── Enrichment ────────────────────────────────────────────────────────────

    private void enrich(Map<String, Object> item) {
        String type = (String) item.get("type");
        switch (type) {
            case "lost" -> {
                String b = str(item, "barcode");
                item.put("descriptionEn", "Piece " + b + " is marked as lost by the courier");
                item.put("descriptionAr", "القطعة " + b + " مُسجَّلة كمفقودة لدى شركة الشحن");
                item.put("suggestedAction", "confirm_write_off");
                item.put("actionUrl", ordersUrl(item));
            }
            case "never_received" -> {
                String b = str(item, "barcode");
                item.put("descriptionEn", "Piece " + b + " from a returned shipment was never scanned back in");
                item.put("descriptionAr", "القطعة " + b + " من شحنة مرتجعة لم يتم استلامها في المستودع");
                item.put("suggestedAction", "intake_or_write_off");
                item.put("actionUrl", "/returns");
            }
            case "unmatched_delivery" -> {
                String t = str(item, "tracking_number");
                item.put("descriptionEn", "Bosta delivery " + t + " could not be matched to an order");
                item.put("descriptionAr", "شحنة بوسطة " + t + " لم يتم ربطها بطلب");
                item.put("suggestedAction", "manual_link");
                item.put("actionUrl", "/shipments/unlinked");
            }
            case "blocked_customer" -> {
                String n = str(item, "order_number");
                String r = str(item, "hold_reason");
                String suffix = (r != null && !r.isBlank()) ? ": " + r : "";
                item.put("descriptionEn", "Order " + n + " is on hold" + suffix);
                item.put("descriptionAr", "الطلب " + n + " معلَّق" + suffix);
                item.put("suggestedAction", "review_and_release");
                item.put("actionUrl", ordersUrl(item));
            }
            case "stuck_shipment" -> {
                String t = str(item, "tracking_number");
                String st = str(item, "shipment_state");
                item.put("descriptionEn", "Shipment " + t + " stuck in '" + st + "' — no courier update");
                item.put("descriptionAr", "الشحنة " + t + " متوقفة في حالة '" + st + "' بدون تحديث");
                item.put("suggestedAction", "contact_courier");
                item.put("actionUrl", ordersUrl(item));
            }
            case "unexpected_return" -> {
                String b = str(item, "barcode");
                item.put("descriptionEn", "Piece " + b + " was physically returned with no prior RTO state from courier");
                item.put("descriptionAr", "القطعة " + b + " أُرجعت فعلياً دون حالة إرجاع من شركة الشحن");
                item.put("suggestedAction", "inspect_and_resolve");
                item.put("actionUrl", "/returns");
            }
            case "delivery_limbo" -> {
                String t = str(item, "tracking_number");
                Object att = item.get("number_of_attempts");
                item.put("descriptionEn", "Return delivery " + t + " failed " + att + "× — awaiting action at Bosta hub");
                item.put("descriptionAr", "فشل إرجاع الشحنة " + t + " " + att + " مرات — في انتظار الإجراء");
                item.put("suggestedAction", "retry_or_cancel_return");
                item.put("actionUrl", ordersUrl(item));
            }
            case "ndr_failed" -> {
                String t = str(item, "tracking_number");
                String d = str(item, "ndr_description");
                String desc = (d != null && !d.isBlank()) ? ": " + d : "";
                item.put("descriptionEn", "Failed delivery attempt for " + t + desc);
                item.put("descriptionAr", "فشل محاولة توصيل " + t + desc);
                item.put("suggestedAction", "contact_customer");
                item.put("actionUrl", ordersUrl(item));
            }
            case "guided_unpack" -> {
                String n = str(item, "order_number");
                item.put("descriptionEn", "Order " + n + " cancelled — pieces are packed and must be physically unpacked");
                item.put("descriptionAr", "الطلب " + n + " ملغى — القطع معبَّأة وتحتاج إلى فك التعبئة يدوياً");
                item.put("suggestedAction", "unpack_pieces");
                Object oid = item.get("order_id");
                item.put("actionUrl", oid != null ? "/fulfill/" + oid : "/fulfill");
            }
            case "missing_awb" -> {
                String t = str(item, "tracking_number");
                String r = str(item, "failed_reason");
                item.put("descriptionEn", "AWB could not be printed for shipment " + t + ": " + r);
                item.put("descriptionAr", "تعذّر طباعة بوليصة الشحن للشحنة " + t + ": " + r);
                item.put("suggestedAction", "retry_awb_print");
                item.put("actionUrl", "/shipments/" + item.get("shipment_id"));
            }
            case "missing_provider_id" -> {
                String t = str(item, "tracking_number");
                String n = str(item, "order_number");
                item.put("descriptionEn",
                    "Bosta internal ID could not be fetched for shipment " + t + " (order " + n + ") — delivery cancellation unavailable");
                item.put("descriptionAr",
                    "تعذّر جلب المعرّف الداخلي من بوسطة للشحنة " + t + " (الطلب " + n + ") — إلغاء التوصيل غير متاح");
                item.put("suggestedAction", "retry_provider_id_fetch");
                item.put("actionUrl", "/shipments/" + item.get("shipment_id"));
            }
            case "high_attempts" -> {
                String t = str(item, "tracking_number");
                Object att = item.get("number_of_attempts");
                item.put("descriptionEn",
                    "Shipment " + t + " has had " + att + " delivery attempt(s) — customer may be unreachable");
                item.put("descriptionAr",
                    "تمّت " + att + " محاولات توصيل للشحنة " + t + " — قد يتعذّر الوصول للعميل");
                item.put("suggestedAction", "contact_customer");
                item.put("actionUrl", ordersUrl(item));
            }
            case "shopify_cancel_vs_inflight" -> {
                String n = str(item, "order_number");
                String t = str(item, "tracking_number");
                String shipSuffix = (t != null) ? " (AWB: " + t + ")" : "";
                item.put("descriptionEn",
                    "Shopify cancelled order " + n + shipSuffix +
                    " but the parcel is still in-flight with the courier");
                item.put("descriptionAr",
                    "أُلغي الطلب " + n + shipSuffix +
                    " في شوبيفاي ولكن الشحنة لا تزال مع مندوب التوصيل");
                item.put("suggestedAction",
                    "Shopify cancelled but parcel is in-flight — convert to self-pickup, " +
                    "cancel via guided flow, or let it RTO.");
                item.put("actionUrl", ordersUrl(item));
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Instant toInstant(Object val, Instant fallback) {
        if (val instanceof Timestamp ts) return ts.toInstant();
        if (val instanceof Instant i)    return i;
        return fallback;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    private static String ordersUrl(Map<String, Object> item) {
        Object oid = item.get("order_id");
        return oid != null ? "/orders/" + oid : "/orders";
    }
}
