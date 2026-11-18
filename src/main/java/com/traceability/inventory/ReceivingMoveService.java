package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyMoveSourceGateway;
import com.traceability.integrations.shopify.ShopifyMoveSourceGateway.Level;
import com.traceability.integrations.shopify.ShopifyMoveSourceGateway.MoveSourceReadException;
import com.traceability.integrations.shopify.ShopifyMoveSourceGateway.ShopifyLocation;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Receiving move M1 (approved by Marawan 2026-11-12; M1 go 2026-11-18) — "Moving from my Shopify location": a receiving
 * session at the main warehouse whose stock comes from one of the merchant's own Shopify locations. M1 is READ ONLY
 * towards Shopify: the source list, the live levels at the source, the per-line choice, the §6 preview and the frozen
 * "X of Y" progress. The write (pushLocationMove, the named write) and finalizing a move session arrive in M2 — until
 * then {@link ReceivingService#finalize} refuses a move session (MOVE_FINALIZE_NOT_AVAILABLE).
 *
 * <p>Rules (never re-derive them):
 * <ul>
 *   <li>Source = the merchant's own ACTIVE locations, all pages, minus the Traced Main Warehouse's GID and minus
 *       fulfillment-service / app-managed locations ({@link #eligibleSources}).</li>
 *   <li>Refused before anything: the store isn't ready for Shopify stock updates (no store, reconnect needed, scopes,
 *       main not linked / inactive / deleted — IncrementRecoveryRules.setupProblem); a location other than the main
 *       warehouse; a return-linked line (ReceivingService.addLine).</li>
 *   <li>The MOVE GATE: a session is created only on a VERIFIED Ready of Traced Main's shipping from a FRESH read
 *       ({@link TracedLocationOnlineService#freshShippingForMove}) — the owner's "I've added it" never opens it.</li>
 *   <li>Levels are read live at the session's SOURCE GID — never the main warehouse's.</li>
 *   <li>Per line {@code source_choice} 'keep' (default) | 'zero' (explicit only, per line or "Set all to 0"); per
 *       variant the effective choice is 'zero' only when EVERY line of the variant chose it.</li>
 *   <li>Progress Y is frozen at the first move session from that source (receiving_move_progress, once).</li>
 * </ul>
 * No DB transaction is open during a Shopify call.
 */
@Service
public class ReceivingMoveService {

    private static final Logger log = LoggerFactory.getLogger(ReceivingMoveService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ReceivingService receiving;
    private final ShopifyMoveSourceGateway gateway;
    private final ShopifyTokenProvider tokens;
    private final StoreRepository stores;
    private final TracedLocationOnlineService online;

    public ReceivingMoveService(JdbcTemplate jdbc, PlatformTransactionManager txm, ReceivingService receiving,
                                ShopifyMoveSourceGateway gateway, ShopifyTokenProvider tokens, StoreRepository stores,
                                TracedLocationOnlineService online) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.receiving = receiving;
        this.gateway = gateway;
        this.tokens = tokens;
        this.stores = stores;
        this.online = online;
    }

    // ── Sources ──────────────────────────────────────────────────────────────────────────────────────────────────

    /** A location the merchant may move stock from. */
    public record Source(String id, String name) {}

    /**
     * THE source rule: active, not app-managed (fulfillmentService null), not the Traced Main Warehouse's GID. Shopify
     * already leaves inactive and legacy fulfillment-service locations out of the read; this filters again.
     */
    static List<Source> eligibleSources(List<ShopifyLocation> all, String mainGid) {
        List<Source> out = new ArrayList<>();
        for (ShopifyLocation l : all) {
            if (!l.active() || l.appManaged()) continue;
            if (l.gid().equals(mainGid)) continue;
            out.add(new Source(l.gid(), l.name()));
        }
        out.sort(Comparator.comparing(Source::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Source::id));
        return out;
    }

    /** GET /receiving/move-sources — the "Moving from" list, read live (no cache). */
    public List<Source> listSources() {
        UUID tenantId = TenantContext.require();
        Ctx c = ctx(tenantId);
        return readSources(c);
    }

    private List<Source> readSources(Ctx c) {
        try {
            return eligibleSources(gateway.listLocations(c.shop(), c.token()), c.mainGid());
        } catch (MoveSourceReadException e) {
            log.warn("Receiving move: locations read failed tenant={} reason={}", c.tenantId(), e.reason());
            throw ReceivingMoveException.readFailed();
        }
    }

    // ── Create ───────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * POST /receiving/move-sessions. In this order: the store ready for Shopify stock updates → the main warehouse →
     * the source is one of the merchant's own active locations (a fresh list) → the MOVE GATE (a fresh shipping read,
     * verified Ready) → Y read once per source (when not frozen yet) → ONE transaction: the ordinary session
     * (ReceivingService.createSession — every mode, its own rules), its source, and Y frozen (first time only).
     */
    public UUID createSession(UUID actorUserId, UUID locationId, String reference, String supplierName, String note,
                              String mode, UUID labelRunId, String sourceLocationId) {
        UUID tenantId = TenantContext.require();
        // M1 (Marawan 2026-11-18): print-and-stick only until count-only / factory moves are tested — refused before
        // any Shopify read.
        if (mode != null && !ReceivingService.MODE_PRINT_STICK.equals(mode)) throw ReceivingMoveException.modeNotAvailable();
        if (sourceLocationId == null || sourceLocationId.isBlank()) throw ReceivingMoveException.sourceNotEligible();
        Ctx c = ctx(tenantId);
        if (!isMainWarehouse(tenantId, locationId)) throw ReceivingMoveException.mainWarehouseOnly();
        Source source = readSources(c).stream().filter(s -> s.id().equals(sourceLocationId)).findFirst()
            .orElseThrow(ReceivingMoveException::sourceNotEligible);

        TracedLocationOnlineService.MoveGate gate = online.freshShippingForMove(tenantId);
        if (!gate.verified()) throw shippingNotReady(gate);

        Integer frozen = tx.execute(s -> jdbc.query(
            "SELECT total_products FROM receiving_move_progress WHERE tenant_id = ? AND source_location_gid = ?",
            rs -> rs.next() ? rs.getInt(1) : null, tenantId, source.id()));
        Integer total = frozen;
        if (total == null) {
            try {
                total = gateway.countProductsWithStock(c.shop(), c.token(), source.id());
            } catch (MoveSourceReadException e) {
                log.warn("Receiving move: source stock read failed tenant={} reason={}", tenantId, e.reason());
                throw ReceivingMoveException.readFailed();
            }
        }
        int y = total;
        return tx.execute(s -> {
            UUID id = receiving.createSession(actorUserId, locationId, reference, supplierName, note, mode, labelRunId);
            jdbc.update("UPDATE receipts SET move_source_location_gid = ?, move_source_location_name = ? " +
                "WHERE id = ? AND tenant_id = ?", source.id(), source.name(), id, tenantId);
            jdbc.update("INSERT INTO receiving_move_progress (tenant_id, source_location_gid, total_products, frozen_by_receipt_id) " +
                "VALUES (?, ?, ?, ?) ON CONFLICT (tenant_id, source_location_gid) DO NOTHING", tenantId, source.id(), y, id);
            return id;
        });
    }

    /** 409 MOVE_SHIPPING_NOT_READY — the connection card's instruction + its "Open the location in Shopify" link. */
    static ReceivingMoveException shippingNotReady(TracedLocationOnlineService.MoveGate gate) {
        String en, ar;
        if ("market_driven".equals(gate.shippingModel())) {
            en = "Make sure Traced Main Warehouse is added to your shipping rates in Shopify";
            ar = "تأكّد من إضافة «Traced Main Warehouse» إلى أسعار الشحن في Shopify";
        } else if ("activate_needed".equals(gate.readiness()) || "profile_removed".equals(gate.readiness())) {
            en = "Add Traced Main Warehouse to your shipping profile in Shopify";
            ar = "أضِف «Traced Main Warehouse» إلى ملف الشحن في Shopify";
        } else {
            en = "Traced couldn't confirm yet that Traced Main Warehouse can ship online orders";
            ar = "لم يتمكن Traced بعد من التأكد من أن موقع Traced Main Warehouse يمكنه شحن الطلبات الأونلاين";
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("readiness", gate.readiness());
        details.put("shippingModel", gate.shippingModel());
        String url = locationUrl(gate.shopDomain(), gate.locationNumber());
        if (url != null) details.put("locationUrl", url);
        return new ReceivingMoveException(ReceivingMoveException.Code.MOVE_SHIPPING_NOT_READY, HttpStatus.CONFLICT,
            "Moving stock needs Traced Main Warehouse to ship online orders. " + en + ".",
            "نقل المخزون يحتاج أن يشحن «Traced Main Warehouse» الطلبات الأونلاين. " + ar + ".", details);
    }

    /** Same link as the connection card (frontend tracedLocationAdminUrls): numeric id + plain myshopify handle only. */
    static String locationUrl(String shopDomain, String locationNumber) {
        if (shopDomain == null || locationNumber == null || !locationNumber.matches("\\d+")) return null;
        String handle = shopDomain.replaceFirst("(?i)\\.myshopify\\.com$", "");
        if (!handle.matches("(?i)[a-z0-9][a-z0-9-]*")) return null;
        return "https://admin.shopify.com/store/" + handle + "/settings/locations/" + locationNumber;
    }

    // ── Levels + preview ─────────────────────────────────────────────────────────────────────────────────────────

    /** One variant of the session: q summed over its lines, its effective choice, its line ids. */
    record VariantLines(UUID variantId, String variantGid, String title, int quantity, String choice, List<UUID> lineIds) {}

    /** GET /sessions/{id}/move/levels — live levels at the SOURCE, one row per variant. */
    public Map<String, Object> levels(UUID sessionId) {
        return read(sessionId, false);
    }

    /** GET /sessions/{id}/move/preview — the §6 math per variant on a fresh read, plus totals. */
    public Map<String, Object> preview(UUID sessionId) {
        return read(sessionId, true);
    }

    private Map<String, Object> read(UUID sessionId, boolean withPlan) {
        UUID tenantId = TenantContext.require();
        Ctx c = ctx(tenantId);
        MoveSession m = moveSession(tenantId, sessionId);
        if (m.sourceGid().equals(c.mainGid())) throw ReceivingMoveException.sourceNotEligible();
        List<VariantLines> variants = tx.execute(s -> variants(tenantId, sessionId));
        List<String> gids = variants.stream().map(VariantLines::variantGid).filter(g -> g != null).distinct().toList();
        Map<String, Level> levels;
        try {
            levels = gids.isEmpty() ? Map.of() : gateway.fetchLocationLevels(c.shop(), c.token(), m.sourceGid(), gids);
        } catch (MoveSourceReadException e) {
            log.warn("Receiving move: source levels read failed tenant={} reason={}", tenantId, e.reason());
            throw ReceivingMoveException.readFailed();
        }
        Instant readAt = Instant.now();
        List<Map<String, Object>> rows = new ArrayList<>();
        int mainTotal = 0, sourceTotal = 0, shopifyTotal = 0;
        for (VariantLines v : variants) {
            Level level = v.variantGid() == null ? null : levels.get(v.variantGid());
            boolean hasLevel = level != null && level.level();
            Integer available = hasLevel ? level.available() : null;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("variantId", v.variantId());
            row.put("title", v.title());
            row.put("lineIds", v.lineIds());
            row.put("quantity", v.quantity());
            row.put("choice", v.choice());
            row.put("inShopify", v.variantGid() != null);
            row.put("level", hasLevel);
            row.put("available", available);
            row.put("committed", hasLevel ? level.committed() : null);
            row.put("onHand", hasLevel ? level.onHand() : null);
            if (withPlan) {
                ReceivingMovePlan.Line p = ReceivingMovePlan.line(v.quantity(), available, v.choice());
                row.put("case", p.kase().name().toLowerCase());
                row.put("mainDelta", p.mainDelta());
                row.put("sourceDelta", p.sourceDelta());
                row.put("sourceAfter", p.sourceAfter());
                row.put("totalChange", p.totalChange());
                mainTotal += p.mainDelta();
                sourceTotal += p.sourceDelta();
                shopifyTotal += p.totalChange();
            }
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("source", Map.of("id", m.sourceGid(), "name", m.sourceName()));
        out.put("readAt", readAt.toString());
        out.put("lines", rows);
        if (withPlan) out.put("totals", Map.of("mainDelta", mainTotal, "sourceDelta", sourceTotal, "totalChange", shopifyTotal));
        return out;
    }

    /** The session's lines per variant, effective choice 'zero' only when every line of the variant chose it. */
    private List<VariantLines> variants(UUID tenantId, UUID sessionId) {
        return jdbc.query(
            "SELECT rl.variant_id, v.external_id, " +
            "       concat_ws(' — ', p.title, NULLIF(v.title, 'Default Title')) AS title, " +
            "       SUM(rl.quantity)::int AS quantity, " +
            "       CASE WHEN bool_and(rl.source_choice = 'zero') THEN 'zero' ELSE 'keep' END AS choice, " +
            "       array_agg(rl.id ORDER BY rl.created_at, rl.id) AS line_ids " +
            "FROM receipt_lines rl JOIN variants v ON v.id = rl.variant_id AND v.tenant_id = rl.tenant_id " +
            "JOIN products p ON p.id = v.product_id AND p.tenant_id = v.tenant_id " +
            "WHERE rl.receipt_id = ? AND rl.tenant_id = ? " +
            "GROUP BY rl.variant_id, v.external_id, p.title, v.title ORDER BY min(rl.created_at), rl.variant_id",
            (rs, i) -> {
                String ext = rs.getString("external_id");
                List<UUID> ids = new ArrayList<>();
                for (Object o : (Object[]) rs.getArray("line_ids").getArray()) ids.add((UUID) o);
                return new VariantLines(rs.getObject("variant_id", UUID.class),
                    ext != null && ext.startsWith("gid://shopify/ProductVariant/") ? ext : null,
                    rs.getString("title"), rs.getInt("quantity"), rs.getString("choice"), ids);
            }, sessionId, tenantId);
    }

    // ── Choice ───────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * PUT /sessions/{id}/lines/{lineId}/source-choice — the line's leftover rule. Applied to every line of the same
     * variant in the session (the source has ONE available per item). 'zero' only ever from this explicit call or the
     * bulk one.
     */
    public void setLineChoice(UUID sessionId, UUID lineId, String choice) {
        UUID tenantId = TenantContext.require();
        String ch = choice(choice);
        tx.executeWithoutResult(s -> {
            MoveSession m = moveSession(tenantId, sessionId);
            requireOpen(m);
            int n = jdbc.update(
                "UPDATE receipt_lines SET source_choice = ? WHERE receipt_id = ? AND tenant_id = ? AND variant_id = " +
                "  (SELECT variant_id FROM receipt_lines WHERE id = ? AND receipt_id = ? AND tenant_id = ?)",
                ch, sessionId, tenantId, lineId, sessionId, tenantId);
            if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receiving line not found");
        });
    }

    /** POST /sessions/{id}/move/source-choice — "Set all to 0" ('zero') or back to "Keep them there" ('keep'). */
    public int setAllChoice(UUID sessionId, String choice) {
        UUID tenantId = TenantContext.require();
        String ch = choice(choice);
        Integer n = tx.execute(s -> {
            MoveSession m = moveSession(tenantId, sessionId);
            requireOpen(m);
            return jdbc.update("UPDATE receipt_lines SET source_choice = ? WHERE receipt_id = ? AND tenant_id = ?",
                ch, sessionId, tenantId);
        });
        return n == null ? 0 : n;
    }

    private static String choice(String c) {
        if (ReceivingMovePlan.KEEP.equals(c) || ReceivingMovePlan.ZERO.equals(c)) return c;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "choice must be 'keep' or 'zero'");
    }

    // ── Progress ─────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * GET /sessions/{id}/move/progress — "X of Y" for the session's source: Y frozen at the first move session from it;
     * X = distinct products received in FINALIZED move sessions from that source (M1 finalizes none, so 0 until M2).
     */
    public Map<String, Object> progress(UUID sessionId) {
        UUID tenantId = TenantContext.require();
        return tx.execute(s -> {
            MoveSession m = moveSession(tenantId, sessionId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("source", Map.of("id", m.sourceGid(), "name", m.sourceName()));
            List<Map<String, Object>> frozen = jdbc.queryForList(
                "SELECT total_products, frozen_at FROM receiving_move_progress WHERE tenant_id = ? AND source_location_gid = ?",
                tenantId, m.sourceGid());
            out.put("total", frozen.isEmpty() ? null : frozen.get(0).get("total_products"));
            out.put("frozenAt", frozen.isEmpty() ? null : String.valueOf(frozen.get(0).get("frozen_at")));
            out.put("moved", jdbc.queryForObject(
                "SELECT COUNT(DISTINCT v.product_id) FROM receipts r " +
                "JOIN receipt_lines rl ON rl.receipt_id = r.id AND rl.tenant_id = r.tenant_id " +
                "JOIN variants v ON v.id = rl.variant_id AND v.tenant_id = rl.tenant_id " +
                "WHERE r.tenant_id = ? AND r.move_source_location_gid = ? AND r.status = 'finalized' AND r.kind = 'inbound'",
                Integer.class, tenantId, m.sourceGid()));
            return out;
        });
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    record MoveSession(UUID id, String status, String sourceGid, String sourceName) {}

    private MoveSession moveSession(UUID tenantId, UUID sessionId) {
        List<MoveSession> rows = tx.execute(s -> jdbc.query(
            "SELECT id, status, move_source_location_gid, move_source_location_name FROM receipts " +
            "WHERE id = ? AND tenant_id = ? AND kind = 'inbound'",
            (rs, i) -> new MoveSession(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)),
            sessionId, tenantId));
        if (rows == null || rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receiving session not found");
        if (rows.get(0).sourceGid() == null) throw ReceivingMoveException.notAMoveSession();
        return rows.get(0);
    }

    private static void requireOpen(MoveSession m) {
        if (!"open".equals(m.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Session is already finalized and cannot be modified");
        }
    }

    private boolean isMainWarehouse(UUID tenantId, UUID locationId) {
        if (locationId == null) return false;
        return Boolean.TRUE.equals(tx.execute(s -> jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM locations WHERE id = ? AND tenant_id = ? AND is_fulfillment)",
            Boolean.class, locationId, tenantId)));
    }

    record Ctx(UUID tenantId, String shop, String token, String mainGid) {}

    /**
     * The store, its token and the Traced Main Warehouse's GID — refused (409 MOVE_SHOPIFY_NOT_READY) while the store
     * isn't ready for Shopify stock updates: no store, reconnect needed, scopes missing, main not linked, main inactive
     * or deleted (IncrementRecoveryRules.setupProblem — the same rule as every stock sender).
     */
    private Ctx ctx(UUID tenantId) {
        IncrementRecoveryRules.SetupProblem p = tx.execute(s -> IncrementRecoveryRules.setupProblem(jdbc, tenantId));
        if (p != null) throw ReceivingMoveException.setup(p);
        String mainGid = tx.execute(s -> jdbc.queryForObject(
            "SELECT shopify_location_id FROM locations WHERE tenant_id = ? AND is_fulfillment = true " +
            "  AND shopify_sync_status = 'linked' AND shopify_location_id IS NOT NULL LIMIT 1", String.class, tenantId));
        Optional<StoreRepository.Store> store = stores.findActiveStoreByTenant(tenantId);
        if (store.isEmpty()) throw ReceivingMoveException.setup(IncrementRecoveryRules.SetupProblem.NO_STORE);
        String token;
        try {
            token = tokens.getValidToken(store.get().id());
        } catch (RuntimeException e) {
            log.warn("Receiving move: token unavailable tenant={} error={}", tenantId, e.getClass().getSimpleName());
            throw ReceivingMoveException.readFailed();
        }
        return new Ctx(tenantId, store.get().shopDomain(), token, mainGid);
    }
}
