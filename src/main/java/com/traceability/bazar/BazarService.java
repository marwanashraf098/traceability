package com.traceability.bazar;

import com.traceability.inventory.TransferService;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bazar POS B1 — a bazar event, its ONE linked send-out-and-back transfer and its per-event prices.
 *
 * <ul>
 *   <li>The event's stock goes out on a round_trip transfer of type 'bazar', main warehouse → a location of type
 *       'event' (Traced-only, never a Shopify location). Scan-out and Mark sent are TransferService's own paths
 *       ({@code scanOutForBazar} / {@code markSentForBazar} — no copy of the logic); Mark sent claims the existing
 *       {@code pushTransferOut} −N at the Traced Main Warehouse. Every generic Transfers action refuses the transfer
 *       (BAZAR_TRANSFER_LOCKED).</li>
 *   <li>Shopify mode is always 'remove' in v1: an event location in 'leave' mode is refused at create and again,
 *       under a share lock on the location row, at Mark sent (so the send-time snapshot is 'remove').</li>
 *   <li>Prices ({@link BazarPricing}) are Traced-only — no Shopify write of any kind here.</li>
 *   <li>Start: draft → live, only once the transfer is sent; at most one live event per location.</li>
 * </ul>
 * Every method needs {@code tenants.bazar_enabled} (404 BAZAR_NOT_ENABLED when off).
 */
@Service
public class BazarService {

    static final Set<String> PAYMENT_METHODS = Set.of("cash", "card", "instapay");
    static final Set<String> SELLER_ROLES    = Set.of("owner", "manager", "worker");

    private final JdbcTemplate    jdbc;
    private final TransferService transfers;

    private final BazarCatalogService catalogs;

    public BazarService(JdbcTemplate jdbc, TransferService transfers, BazarCatalogService catalogs) {
        this.jdbc      = jdbc;
        this.transfers = transfers;
        this.catalogs  = catalogs;
    }

    // ── Create ───────────────────────────────────────────────────────────────

    /** Exactly one of {@code locationId} (reuse an 'event' location) / {@code locationName} (create one). */
    public record CreateEvent(String name, LocalDate startsOn, LocalDate endsOn,
                              UUID locationId, String locationName,
                              List<String> paymentMethods, List<String> sellerRoles,
                              String shopifySyncMode, BigDecimal discountPct, BigDecimal roundTo) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> create(CreateEvent req, UUID actorUserId) {
        UUID tenantId = requireEnabled();

        if (req.shopifySyncMode() != null && !"remove".equals(req.shopifySyncMode())) {
            throw leaveMode();
        }
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty() || name.length() > 80) {
            throw invalid("Give the bazar a name (up to 80 characters).", "اكتب اسمًا للبازار (حتى 80 حرفًا).");
        }
        if (req.startsOn() == null || req.endsOn() == null) {
            throw invalid("Choose the bazar's start and end dates.", "اختر تاريخ بداية ونهاية البازار.");
        }
        if (req.endsOn().isBefore(req.startsOn())) {
            throw invalid("The end date can't be before the start date.", "لا يمكن أن يكون تاريخ النهاية قبل تاريخ البداية.");
        }
        String[] methods = subset(req.paymentMethods(), List.of("cash"), PAYMENT_METHODS,
            "Payment methods must be cash, card or instapay.", "طرق الدفع يجب أن تكون نقدًا أو بطاقة أو إنستاباي.");
        String[] roles = subset(req.sellerRoles(), List.of("owner", "manager"), SELLER_ROLES,
            "Seller roles must be owner, manager or worker.", "أدوار البائعين يجب أن تكون مالك أو مدير أو عامل.");
        validateRule(req.discountPct(), req.roundTo());

        UUID locationId = resolveLocation(tenantId, req.locationId(), req.locationName());
        UUID transferId = transfers.createBazarTransfer(locationId, actorUserId);

        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO bazar_events (id, tenant_id, name, starts_on, ends_on, location_id, transfer_id, " +
            "  payment_methods, seller_roles, discount_pct, round_to, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?::text[], ?::text[], ?, ?, ?)",
            id, tenantId, name, Date.valueOf(req.startsOn()), Date.valueOf(req.endsOn()), locationId, transferId,
            pgArray(methods), pgArray(roles), req.discountPct(), req.roundTo(), actorUserId);
        return detail(tenantId, id);
    }

    private UUID resolveLocation(UUID tenantId, UUID locationId, String locationName) {
        boolean hasName = locationName != null && !locationName.isBlank();
        if ((locationId == null) == !hasName) {
            throw invalid("Choose an existing bazar location or name a new one.",
                "اختر موقع بازار موجودًا أو اكتب اسم موقع جديد.");
        }
        if (locationId != null) {
            // FOR SHARE: the Settings mode switch can't flip it to 'leave' under this create.
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT type::text AS type, is_fulfillment, shopify_location_id, shopify_sync_mode " +
                "FROM locations WHERE id = ? AND tenant_id = ? FOR SHARE", locationId, tenantId);
            if (rows.isEmpty()) {
                throw new BazarException(BazarException.Code.BAZAR_LOCATION_NOT_FOUND,
                    "Location not found.", "لم يتم العثور على الموقع.", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Map<String, Object> l = rows.get(0);
            if (!"event".equals(l.get("type")) || Boolean.TRUE.equals(l.get("is_fulfillment"))
                    || l.get("shopify_location_id") != null) {
                throw new BazarException(BazarException.Code.BAZAR_LOCATION_NOT_EVENT,
                    "Pick a bazar (event) location, not a warehouse or shop.",
                    "اختر موقع بازار (فعالية)، وليس مستودعًا أو متجرًا.", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            if (!"remove".equals(l.get("shopify_sync_mode"))) {
                throw leaveMode();
            }
            return locationId;
        }
        String name = locationName.trim();
        if (name.length() < 3) {
            throw invalid("Location name must be at least 3 characters.", "يجب أن يتكون اسم الموقع من 3 أحرف على الأقل.");
        }
        Integer taken = jdbc.queryForObject(
            "SELECT COUNT(*) FROM locations WHERE tenant_id = ? AND lower(trim(name)) = lower(?)",
            Integer.class, tenantId, name);
        if (taken != null && taken > 0) {
            throw new BazarException(BazarException.Code.BAZAR_LOCATION_NAME_TAKEN,
                "A location with this name already exists — pick it instead, or use another name.",
                "يوجد موقع بهذا الاسم بالفعل — اختره أو استخدم اسمًا آخر.", HttpStatus.CONFLICT);
        }
        // Traced-only: never a Shopify location (only the fulfillment location ever syncs — LocationController).
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_sync_mode) " +
            "VALUES (?, ?, ?, 'event'::location_type, false, false, 'remove')", id, tenantId, name);
        return id;
    }

    // ── Send out (TransferService's own paths) ───────────────────────────────

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferService.ScanOutResult scanOut(UUID eventId, String barcode, UUID actorUserId, boolean viaPhone) {
        UUID tenantId = requireEnabled();
        Map<String, Object> ev = event(tenantId, eventId, false);
        if ("ended".equals(ev.get("status"))) throw ended();
        UUID transferId = (UUID) ev.get("transfer_id");
        TransferService.ScanOutResult r = transfers.scanOutForBazar(transferId, barcode, actorUserId, viaPhone);
        if (r.success()) {
            ensurePrice(tenantId, ev, r.variantId());
        }
        return r;
    }

    /**
     * B7d — send {@code quantity} pieces of a size without scanning them (stock with or without a barcode). Traced
     * picks them at the main warehouse, oldest first, all or nothing (TransferService.scanOutByCountForBazar). The
     * same {@code clientRequestId} again returns the first answer and sends nothing more.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferService.CountResult sendByCount(UUID eventId, UUID variantId, Integer quantity,
                                                   String clientRequestId, UUID actorUserId) {
        UUID tenantId = requireEnabled();
        String crid = requestId(clientRequestId);
        Map<String, Object> ev = event(tenantId, eventId, false);
        if ("ended".equals(ev.get("status"))) throw ended();
        if (variantId == null) throw invalid("Choose a product and size.", "اختر منتجًا ومقاسًا.");
        requireVariant(tenantId, variantId);
        TransferService.CountResult r = transfers.scanOutByCountForBazar((UUID) ev.get("transfer_id"), variantId,
            quantity == null ? 0 : quantity, actorUserId, crid);
        if (!r.success()) throw countRefused(r);
        ensurePrice(tenantId, ev, r.variantId());
        return r;
    }

    /**
     * B7d — take pieces back off the send before Mark sent: one scanned piece by its code (exactly one of
     * {@code code}), or {@code quantity} pieces of a size, the newest added first. Each piece is back available at
     * the main warehouse, nothing reaches Shopify. The same {@code clientRequestId} again changes nothing more.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferService.CountResult removeFromSend(UUID eventId, String code, UUID variantId, Integer quantity,
                                                      String clientRequestId, UUID actorUserId) {
        UUID tenantId = requireEnabled();
        String crid = requestId(clientRequestId);
        Map<String, Object> ev = event(tenantId, eventId, false);
        if ("ended".equals(ev.get("status"))) throw ended();
        boolean byCode = code != null && !code.isBlank();
        if (byCode == (variantId != null)) {
            throw invalid("Remove one scanned piece, or a number of one size.", "احذف قطعة ممسوحة واحدة، أو عددًا من مقاس واحد.");
        }
        UUID transferId = (UUID) ev.get("transfer_id");
        TransferService.CountResult r = byCode
            ? transfers.removeScannedFromSendForBazar(transferId, code, actorUserId, crid)
            : transfers.removeCountFromSendForBazar(transferId, variantId, quantity == null ? 0 : quantity, actorUserId, crid);
        if (!r.success()) throw countRefused(r);
        return r;
    }

    private static String requestId(String clientRequestId) {
        String crid = clientRequestId == null ? "" : clientRequestId.trim();
        if (crid.isEmpty() || crid.length() > 100) {
            throw invalid("Missing request id (client_request_id).", "معرّف الطلب مفقود (client_request_id).");
        }
        return crid;
    }

    private static BazarException countRefused(TransferService.CountResult r) {
        return switch (r.code()) {
            case "NOT_ENOUGH_AT_MAIN" -> new BazarException(BazarException.Code.BAZAR_NOT_ENOUGH_AT_MAIN,
                r.messageEn(), r.messageAr(), HttpStatus.CONFLICT, Map.of("available", r.available()));
            case "NOT_ON_THIS_SEND" -> new BazarException(BazarException.Code.BAZAR_NOT_ON_SEND,
                r.messageEn(), r.messageAr(), HttpStatus.CONFLICT, Map.of("on_send", r.available()));
            case "TRANSFER_NOT_OPEN" -> new BazarException(BazarException.Code.BAZAR_SEND_CLOSED,
                r.messageEn(), r.messageAr(), HttpStatus.CONFLICT);
            case "INVALID_QUANTITY" -> invalid(r.messageEn(), r.messageAr());
            default -> new BazarException(BazarException.Code.BAZAR_EVENT_NOT_FOUND,
                "Bazar not found.", "لم يتم العثور على البازار.", HttpStatus.NOT_FOUND);
        };
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> markSent(UUID eventId, UUID actorUserId) {
        UUID tenantId = requireEnabled();
        Map<String, Object> ev = event(tenantId, eventId, true);
        if (!"draft".equals(ev.get("status"))) throw notDraft();
        // v1 is remove-only: hold the location's mode under a share lock until the send (and its snapshot) commits.
        String mode = jdbc.queryForObject(
            "SELECT shopify_sync_mode FROM locations WHERE id = ? AND tenant_id = ? FOR SHARE",
            String.class, ev.get("location_id"), tenantId);
        if (!"remove".equals(mode)) throw leaveMode();
        transfers.markSentForBazar((UUID) ev.get("transfer_id"), actorUserId);
        return detail(tenantId, eventId);
    }

    // ── Start ────────────────────────────────────────────────────────────────

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> start(UUID eventId, UUID actorUserId) {
        UUID tenantId = requireEnabled();
        Map<String, Object> ev = event(tenantId, eventId, true);
        if (!"draft".equals(ev.get("status"))) throw notDraft();
        String transferStatus = jdbc.queryForObject(
            "SELECT status FROM transfers WHERE id = ? AND tenant_id = ? FOR SHARE",
            String.class, ev.get("transfer_id"), tenantId);
        if (!"sent".equals(transferStatus)) {
            throw new BazarException(BazarException.Code.BAZAR_TRANSFER_NOT_SENT,
                "Send the stock to the bazar (Mark as sent) before starting it.",
                "أرسل البضاعة إلى البازار (تأكيد الإرسال) قبل بدء البازار.", HttpStatus.CONFLICT);
        }
        // The location row lock serialises two starts at one location; the partial UNIQUE index is the backstop.
        jdbc.queryForList("SELECT id FROM locations WHERE id = ? AND tenant_id = ? FOR UPDATE",
            ev.get("location_id"), tenantId);
        Integer live = jdbc.queryForObject(
            "SELECT COUNT(*) FROM bazar_events WHERE tenant_id = ? AND location_id = ? AND status = 'live'",
            Integer.class, tenantId, ev.get("location_id"));
        if (live != null && live > 0) {
            throw new BazarException(BazarException.Code.BAZAR_LOCATION_HAS_LIVE_EVENT,
                "Another bazar is already live at this location. End it first.",
                "يوجد بازار آخر جارٍ في هذا الموقع. أنهِه أولًا.", HttpStatus.CONFLICT);
        }
        jdbc.update(
            "UPDATE bazar_events SET status = 'live', started_at = now(), started_by = ?, updated_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND status = 'draft'", actorUserId, eventId, tenantId);
        // B7a: the price sheet's product numbers, first by product title then colour (stable for the event).
        catalogs.assignAtStart(tenantId, eventId);
        return detail(tenantId, eventId);
    }

    // ── Prices ───────────────────────────────────────────────────────────────

    /** Sets the event's bulk rule (both null = no rule) and recomputes every non-override price. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<Map<String, Object>> setBulkRule(UUID eventId, BigDecimal discountPct, BigDecimal roundTo,
                                                 UUID actorUserId) {
        UUID tenantId = requireEnabled();
        Map<String, Object> ev = event(tenantId, eventId, true);
        if ("ended".equals(ev.get("status"))) throw ended();
        validateRule(discountPct, roundTo);
        jdbc.update("UPDATE bazar_events SET discount_pct = ?, round_to = ?, updated_at = now() " +
            "WHERE id = ? AND tenant_id = ?", discountPct, roundTo, eventId, tenantId);
        ev.put("discount_pct", discountPct);
        ev.put("round_to", roundTo);
        List<Map<String, Object>> variants = jdbc.queryForList(
            "SELECT v.id, v.price FROM variants v WHERE v.tenant_id = ? AND v.id IN (" + eventVariantsSql() + ")",
            tenantId, eventId, tenantId, eventId, tenantId);
        for (Map<String, Object> v : variants) {
            BazarPricing.Priced p = BazarPricing.forVariant((BigDecimal) v.get("price"), discountPct, roundTo);
            if (p == null) continue;
            jdbc.update(
                "INSERT INTO bazar_event_prices (tenant_id, event_id, variant_id, price, source, updated_by) " +
                "VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (event_id, variant_id) DO UPDATE SET price = EXCLUDED.price, source = EXCLUDED.source, " +
                "  updated_at = now(), updated_by = EXCLUDED.updated_by " +
                "WHERE bazar_event_prices.source <> 'override'",
                tenantId, eventId, v.get("id"), p.price(), p.source(), actorUserId);
        }
        return prices(tenantId, eventId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<Map<String, Object>> setOverride(UUID eventId, UUID variantId, BigDecimal price, UUID actorUserId) {
        UUID tenantId = requireEnabled();
        Map<String, Object> ev = event(tenantId, eventId, true);
        if ("ended".equals(ev.get("status"))) throw ended();
        if (price == null || price.signum() < 0 || price.compareTo(new BigDecimal("99999999")) > 0) {
            throw invalid("Enter a price of 0 or more.", "أدخل سعرًا 0 أو أكثر.");
        }
        // B3 (approved 2026-11-17): bazar prices are whole pounds — no piasters.
        if (!isWhole(price)) {
            throw invalid("Use whole pounds — no piasters (for example 1840, not 1840.97).",
                "استخدم جنيهات كاملة — بدون قروش (مثلًا 1840 وليس 1840.97).");
        }
        requireVariant(tenantId, variantId);
        jdbc.update(
            "INSERT INTO bazar_event_prices (tenant_id, event_id, variant_id, price, source, updated_by) " +
            "VALUES (?, ?, ?, ?, 'override', ?) " +
            "ON CONFLICT (event_id, variant_id) DO UPDATE SET price = EXCLUDED.price, source = 'override', " +
            "  updated_at = now(), updated_by = EXCLUDED.updated_by",
            tenantId, eventId, variantId, price, actorUserId);
        return prices(tenantId, eventId);
    }

    /** Back to the rule (or the Shopify price). Refused when the variant has no Shopify price to fall back to. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<Map<String, Object>> clearOverride(UUID eventId, UUID variantId, UUID actorUserId) {
        UUID tenantId = requireEnabled();
        Map<String, Object> ev = event(tenantId, eventId, true);
        if ("ended".equals(ev.get("status"))) throw ended();
        BigDecimal base = requireVariant(tenantId, variantId);
        BazarPricing.Priced p = BazarPricing.forVariant(base,
            (BigDecimal) ev.get("discount_pct"), (BigDecimal) ev.get("round_to"));
        if (p == null) {
            throw new BazarException(BazarException.Code.BAZAR_NO_DEFAULT_PRICE,
                "This product has no Shopify price, so it needs its own bazar price.",
                "هذا المنتج ليس له سعر في Shopify، لذلك يحتاج إلى سعر خاص بالبازار.", HttpStatus.CONFLICT);
        }
        jdbc.update(
            "UPDATE bazar_event_prices SET price = ?, source = ?, updated_at = now(), updated_by = ? " +
            "WHERE event_id = ? AND variant_id = ? AND tenant_id = ? AND source = 'override'",
            p.price(), p.source(), actorUserId, eventId, variantId, tenantId);
        return prices(tenantId, eventId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPrices(UUID eventId) {
        UUID tenantId = requireEnabled();
        event(tenantId, eventId, false);
        return prices(tenantId, eventId);
    }

    /** A variant that joins the event (first scan-out of it) gets its non-override price row. */
    private void ensurePrice(UUID tenantId, Map<String, Object> ev, UUID variantId) {
        List<BigDecimal> base = jdbc.queryForList(
            "SELECT price FROM variants WHERE id = ? AND tenant_id = ?", BigDecimal.class, variantId, tenantId);
        if (base.isEmpty()) return;
        BazarPricing.Priced p = BazarPricing.forVariant(base.get(0),
            (BigDecimal) ev.get("discount_pct"), (BigDecimal) ev.get("round_to"));
        if (p == null) return;   // no Shopify price: listed with no price until someone sets one
        jdbc.update(
            "INSERT INTO bazar_event_prices (tenant_id, event_id, variant_id, price, source) VALUES (?, ?, ?, ?, ?) " +
            "ON CONFLICT (event_id, variant_id) DO NOTHING",
            tenantId, ev.get("id"), variantId, p.price(), p.source());
    }

    /** Variants on the event: on its transfer, or with a price row. Params: event id, tenant, event id, tenant. */
    static String eventVariantsSql() {
        return "SELECT tl.variant_id FROM transfer_lines tl JOIN bazar_events e ON e.transfer_id = tl.transfer_id " +
               "WHERE e.id = ? AND tl.tenant_id = ? " +
               "UNION SELECT bp.variant_id FROM bazar_event_prices bp WHERE bp.event_id = ? AND bp.tenant_id = ?";
    }

    private List<Map<String, Object>> prices(UUID tenantId, UUID eventId) {
        return jdbc.queryForList(
            "SELECT v.id AS variant_id, v.sku, v.title AS variant_title, pr.title AS product_title, " +
            "       v.price AS shopify_price, bp.price, bp.source " +
            "FROM variants v JOIN products pr ON pr.id = v.product_id " +
            "LEFT JOIN bazar_event_prices bp ON bp.variant_id = v.id AND bp.event_id = ? AND bp.tenant_id = v.tenant_id " +
            "WHERE v.tenant_id = ? AND v.id IN (" + eventVariantsSql() + ") " +
            "ORDER BY pr.title ASC, v.title ASC, v.id ASC",
            eventId, tenantId, eventId, tenantId, eventId, tenantId);
    }

    private BigDecimal requireVariant(UUID tenantId, UUID variantId) {
        List<Map<String, Object>> v = jdbc.queryForList(
            "SELECT price FROM variants WHERE id = ? AND tenant_id = ?", variantId, tenantId);
        if (v.isEmpty()) {
            throw new BazarException(BazarException.Code.BAZAR_VARIANT_NOT_FOUND,
                "Product not found.", "لم يتم العثور على المنتج.", HttpStatus.NOT_FOUND);
        }
        return (BigDecimal) v.get(0).get("price");
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        UUID tenantId = requireEnabled();
        return jdbc.queryForList(
            "SELECT e.id, e.name, e.starts_on, e.ends_on, e.status, e.location_id, l.name AS location_name, " +
            "       e.transfer_id, t.status AS transfer_status, e.created_at " +
            "FROM bazar_events e JOIN locations l ON l.id = e.location_id JOIN transfers t ON t.id = e.transfer_id " +
            "WHERE e.tenant_id = ? ORDER BY e.created_at DESC, e.id DESC", tenantId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(UUID eventId) {
        UUID tenantId = requireEnabled();
        event(tenantId, eventId, false);
        return detail(tenantId, eventId);
    }

    private Map<String, Object> detail(UUID tenantId, UUID eventId) {
        Map<String, Object> out = new LinkedHashMap<>(jdbc.queryForMap(
            "SELECT e.id, e.name, e.starts_on, e.ends_on, e.status, e.location_id, l.name AS location_name, " +
            "       e.transfer_id, t.status AS transfer_status, t.sent_at AS transfer_sent_at, " +
            "       t.shopify_sync_mode AS transfer_shopify_sync_mode, " +
            "       e.payment_methods, e.seller_roles, e.discount_pct, e.round_to, " +
            "       e.created_by, e.created_at, e.started_at, e.started_by, e.ended_at " +
            "FROM bazar_events e JOIN locations l ON l.id = e.location_id JOIN transfers t ON t.id = e.transfer_id " +
            "WHERE e.id = ? AND e.tenant_id = ?", eventId, tenantId));
        out.put("payment_methods", toList(out.get("payment_methods")));
        out.put("seller_roles", toList(out.get("seller_roles")));
        // Counts from the transfer's pieces (sold = pieces sold at the bazar, B2 — transfer_pieces outcome 'sold').
        Map<String, Object> c = jdbc.queryForMap(
            "SELECT COUNT(*) AS sent, " +
            "       COUNT(*) FILTER (WHERE outcome IS NULL) AS at_bazar, " +
            "       COUNT(*) FILTER (WHERE outcome = 'sold') AS sold, " +
            "       COUNT(*) FILTER (WHERE outcome IN ('returned_good', 'condemned')) AS back, " +
            "       COUNT(*) FILTER (WHERE outcome = 'lost') AS lost " +
            "FROM transfer_pieces WHERE transfer_id = ? AND tenant_id = ?", out.get("transfer_id"), tenantId);
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String k : List.of("sent", "at_bazar", "sold", "back", "lost")) {
            counts.put(k, ((Number) c.get(k)).intValue());
        }
        out.put("counts", counts);
        // B2: today's (Africa/Cairo) takings by payment method.
        @SuppressWarnings("unchecked")
        List<String> methods = (List<String>) out.get("payment_methods");
        out.put("today", BazarSaleService.today(jdbc, tenantId, eventId, methods));
        // B3: the whole event — money that moved (payments − refunds) and revenue (sales − returns).
        out.put("totals", BazarSaleService.summary(jdbc, tenantId, eventId, methods, false));
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID requireEnabled() {
        UUID tenantId = TenantContext.require();
        if (!Bazar.enabled(jdbc, tenantId)) {
            throw new BazarException(BazarException.Code.BAZAR_NOT_ENABLED,
                "Not found.", "غير موجود.", HttpStatus.NOT_FOUND);
        }
        return tenantId;
    }

    private Map<String, Object> event(UUID tenantId, UUID eventId, boolean forUpdate) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, status, location_id, transfer_id, discount_pct, round_to FROM bazar_events " +
            "WHERE id = ? AND tenant_id = ?" + (forUpdate ? " FOR UPDATE" : ""), eventId, tenantId);
        if (rows.isEmpty()) {
            throw new BazarException(BazarException.Code.BAZAR_EVENT_NOT_FOUND,
                "Bazar not found.", "لم يتم العثور على البازار.", HttpStatus.NOT_FOUND);
        }
        return new LinkedHashMap<>(rows.get(0));
    }

    private static void validateRule(BigDecimal discountPct, BigDecimal roundTo) {
        if (discountPct != null && (discountPct.signum() <= 0 || discountPct.compareTo(BigDecimal.valueOf(100)) >= 0
                || discountPct.scale() > 2)) {
            throw invalid("The discount must be more than 0% and less than 100%.",
                "يجب أن يكون الخصم أكثر من 0% وأقل من 100%.");
        }
        if (roundTo != null && (roundTo.compareTo(BigDecimal.ONE) < 0 || !isWhole(roundTo))) {
            throw invalid("Round to a whole number of pounds, 1 or more.",
                "قرّب إلى عدد صحيح من الجنيهات، 1 أو أكثر.");
        }
    }

    /** 1840 / 1840.00 → true; 1840.97 / 0.5 → false. */
    static boolean isWhole(BigDecimal v) {
        return v.signum() == 0 || v.stripTrailingZeros().scale() <= 0;
    }

    private static String[] subset(List<String> given, List<String> dflt, Set<String> allowed, String en, String ar) {
        List<String> v = given == null ? dflt : given;
        if (v.isEmpty() || !allowed.containsAll(v)) throw invalid(en, ar);
        return v.stream().distinct().toArray(String[]::new);
    }

    private static String pgArray(String[] values) {
        return "{" + String.join(",", values) + "}";   // values are from a fixed allow-list, no quoting needed
    }

    private static List<String> toList(Object pgArray) {
        try {
            if (pgArray instanceof java.sql.Array a) return List.of((String[]) a.getArray());
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return List.of();
    }

    private static BazarException invalid(String en, String ar) {
        return new BazarException(BazarException.Code.BAZAR_INVALID_INPUT, en, ar, HttpStatus.BAD_REQUEST);
    }

    private static BazarException leaveMode() {
        return new BazarException(BazarException.Code.BAZAR_LEAVE_MODE_UNSUPPORTED,
            "A bazar always takes its stock out of your Shopify count while it's away. " +
            "\"Leave Shopify unchanged\" isn't available for bazars yet.",
            "البازار يخصم بضاعته دائمًا من عدد Shopify أثناء غيابها. خيار \"عدم تغيير Shopify\" غير متاح للبازارات بعد.",
            HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private static BazarException notDraft() {
        return new BazarException(BazarException.Code.BAZAR_EVENT_NOT_DRAFT,
            "This bazar has already started.", "هذا البازار بدأ بالفعل.", HttpStatus.CONFLICT);
    }

    private static BazarException ended() {
        return new BazarException(BazarException.Code.BAZAR_EVENT_ENDED,
            "This bazar has ended.", "انتهى هذا البازار.", HttpStatus.CONFLICT);
    }
}
