package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Phase A — Inventory screen rebuild backend. Five read endpoints for the new three-tab
 * Inventory screen (stock by location, stock breakdown, movement ledger). Every endpoint
 * that surfaces committed/available/on_hand calls {@link VariantStockService} — no second
 * derivation path (see that class's own doc comment for the Phase 0 committed-inventory fix
 * this builds on).
 *
 * LOCATION SCOPING (resolves the Phase 0 flag): committed is order demand and has no
 * location until a piece is scanned/allocated. So {@link #stock} and {@link #breakdown(UUID)}
 * behave differently depending on whether {@code locationId} is set:
 *   - absent (All locations): committed/available/on_hand are exactly what
 *     {@link VariantStockService} computes — tenant-wide, netted against allocations.
 *   - present: committed is NOT shown (null) — it has no location to be scoped to.
 *     on_hand at that location = COUNT(pieces status='available' at THAT location);
 *     available at that scope = the same number (no committed subtraction, since
 *     committed isn't location-scoped). The response shape makes this explicit — the
 *     frontend renders a dash for a null committed, never a fake 0.
 */
@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryStockController {

    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");

    private final JdbcTemplate         jdbc;
    private final TransactionTemplate  tx;
    private final VariantStockService  stockService;
    private final Clock                clock;

    public InventoryStockController(JdbcTemplate jdbc, PlatformTransactionManager txm,
                                     VariantStockService stockService, Clock clock) {
        this.jdbc         = jdbc;
        this.tx           = new TransactionTemplate(txm);
        this.stockService = stockService;
        this.clock        = clock;
    }

    // ── keyset cursor codec — shared by /stock, /pieces, /movements ────────────
    // Opaque to the client: base64(part1  part2 ...). Not a JWT/signed token —
    // it only encodes the last row's own sort key, same trust boundary as any other
    // query param (the WHERE clause it feeds is tenant-scoped regardless).

    private static String encodeCursor(String... parts) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(String.join("", parts).getBytes(StandardCharsets.UTF_8));
    }

    private static String[] decodeCursor(String cursor) {
        try {
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                .split("", -1);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. GET /inventory/stock — Tab 1, grouped product → variants
    // ═══════════════════════════════════════════════════════════════════════

    public record StockVariant(
        String id, String title, String sku, BigDecimal price,
        long onHand, Long committed, long available, String shopifySync) {}

    public record StockProduct(
        String id, String title, String imageUrl,
        long onHand, Long committed, long available,
        List<StockVariant> variants) {}

    public record StockPage(List<StockProduct> items, String nextCursor) {}

    @GetMapping("/stock")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    public StockPage stock(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(defaultValue = "false") boolean lowStockOnly,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {

        int pageSize = Math.min(Math.max(size, 1), 100);

        return tx.execute(txs -> {
            UUID tenantId = TenantContext.require();

            if (locationId != null) {
                Integer locBelongs = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM locations WHERE id = ? AND tenant_id = ?",
                    Integer.class, locationId, tenantId);
                if (locBelongs == null || locBelongs == 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown locationId");
                }
            }

            Integer threshold = jdbc.queryForObject(
                "SELECT low_stock_threshold FROM tenants WHERE id = ?", Integer.class, tenantId);

            String cursorTitle = null;
            UUID   cursorId    = null;
            if (cursor != null && !cursor.isBlank()) {
                String[] parts = decodeCursor(cursor);
                if (parts.length != 2) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor");
                cursorTitle = parts[0];
                cursorId    = UUID.fromString(parts[1]);
            }
            String qLike = (q != null && !q.isBlank()) ? "%" + q.trim() + "%" : null;

            // ── product page (keyset on title ASC, id ASC — products/variants carry no
            //    created_at column; alphabetical browse is also the correct UX for a
            //    stock list, unlike the newest-first ledger/piece-drill endpoints below) ──
            List<Object> params = new ArrayList<>();
            StringBuilder availLocPredicate = new StringBuilder();
            params.add(tenantId);
            if (locationId != null) {
                availLocPredicate.append("AND p.current_location_id = ?");
                params.add(locationId);
            } else {
                availLocPredicate.append(
                    "AND p.current_location_id IN (SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment = true)");
                params.add(tenantId);
            }
            params.add(tenantId); // pr.tenant_id

            StringBuilder qPredicate = new StringBuilder();
            if (qLike != null) {
                qPredicate.append("AND (pr.title ILIKE ? OR v.title ILIKE ? OR v.sku ILIKE ?)");
                params.add(qLike); params.add(qLike); params.add(qLike);
            }
            StringBuilder lowStockPredicate = new StringBuilder();
            if (lowStockOnly) {
                lowStockPredicate.append("AND COALESCE(a.available_count, 0) < ?");
                params.add(threshold);
            }
            StringBuilder cursorPredicate = new StringBuilder();
            if (cursor != null && !cursor.isBlank()) {
                cursorPredicate.append("AND (pr.title, pr.id) > (?, ?)");
                params.add(cursorTitle); params.add(cursorId);
            }
            params.add(pageSize + 1);

            String sql = "WITH avail AS (" +
                "SELECT p.variant_id, COUNT(*) AS available_count FROM pieces p " +
                "WHERE p.tenant_id = ? AND p.status = 'available'::piece_status " + availLocPredicate + " " +
                "GROUP BY p.variant_id) " +
                "SELECT DISTINCT pr.id, pr.title, pr.image_url FROM products pr " +
                "JOIN variants v ON v.product_id = pr.id " +
                "LEFT JOIN avail a ON a.variant_id = v.id " +
                "WHERE pr.tenant_id = ? " + qPredicate + " " + lowStockPredicate + " " + cursorPredicate + " " +
                "ORDER BY pr.title ASC, pr.id ASC LIMIT ?";

            List<Map<String, Object>> productRows = jdbc.queryForList(sql, params.toArray());

            boolean hasMore = productRows.size() > pageSize;
            if (hasMore) productRows = productRows.subList(0, pageSize);

            String nextCursor = null;
            if (hasMore && !productRows.isEmpty()) {
                Map<String, Object> last = productRows.get(productRows.size() - 1);
                nextCursor = encodeCursor((String) last.get("title"), last.get("id").toString());
            }

            if (productRows.isEmpty()) return new StockPage(List.of(), null);

            UUID[] productIds = productRows.stream()
                .map(r -> (UUID) r.get("id")).toArray(UUID[]::new);

            // Variants for this page of products.
            List<Map<String, Object>> variantRows = jdbc.query(con -> {
                var ps = con.prepareStatement(
                    "SELECT id, product_id, title, sku, price FROM variants " +
                    "WHERE product_id = ANY(?) AND tenant_id = ? ORDER BY title ASC");
                ps.setArray(1, con.createArrayOf("uuid", productIds));
                ps.setObject(2, tenantId);
                return ps;
            }, (rs, i) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("id", rs.getObject("id", UUID.class));
                m.put("product_id", rs.getObject("product_id", UUID.class));
                m.put("title", rs.getString("title"));
                m.put("sku", rs.getString("sku"));
                m.put("price", rs.getBigDecimal("price"));
                return m;
            });

            UUID[] variantIds = variantRows.stream()
                .map(r -> (UUID) r.get("id")).toArray(UUID[]::new);

            // Stock numbers: tenant-wide via VariantStockService when no locationId,
            // else a location-scoped on-hand-only lookup (V78 index) with committed=null.
            Map<UUID, VariantStockService.VariantStock> tenantWide =
                locationId == null ? stockService.computeAll() : Map.of();

            Map<UUID, Long> locationOnHand = new HashMap<>();
            if (locationId != null && variantIds.length > 0) {
                jdbc.query(con -> {
                    var ps = con.prepareStatement(
                        "SELECT variant_id, COUNT(*) AS on_hand FROM pieces " +
                        "WHERE tenant_id = ? AND current_location_id = ? " +
                        "  AND status = 'available'::piece_status AND variant_id = ANY(?) " +
                        "GROUP BY variant_id");
                    ps.setObject(1, tenantId);
                    ps.setObject(2, locationId);
                    ps.setArray(3, con.createArrayOf("uuid", variantIds));
                    return ps;
                }, rs -> {
                    locationOnHand.put(rs.getObject("variant_id", UUID.class), rs.getLong("on_hand"));
                });
            }

            // Shopify-write sync health per variant, from shopify_inventory_adjustments.status.
            Map<UUID, String> syncByVariant = new HashMap<>();
            if (variantIds.length > 0) {
                jdbc.query(con -> {
                    var ps = con.prepareStatement(
                        "SELECT variant_id, " +
                        "       BOOL_OR(status = 'failed') AS any_failed, " +
                        "       BOOL_OR(status IN ('pending','shadow')) AS any_pending " +
                        "FROM shopify_inventory_adjustments " +
                        "WHERE tenant_id = ? AND variant_id = ANY(?) " +
                        "GROUP BY variant_id");
                    ps.setObject(1, tenantId);
                    ps.setArray(2, con.createArrayOf("uuid", variantIds));
                    return ps;
                }, rs -> {
                    UUID vid = rs.getObject("variant_id", UUID.class);
                    // Priority (spec lists all three but not the mixed-status case):
                    // failed worst-cases first, then pending/shadow, else every row is
                    // 'applied' (the only remaining status) -> synced.
                    String status = rs.getBoolean("any_failed")  ? "failed"
                                   : rs.getBoolean("any_pending") ? "pending"
                                   : "synced";
                    syncByVariant.put(vid, status);
                });
            }

            Map<UUID, List<Map<String, Object>>> variantsByProduct = new LinkedHashMap<>();
            for (Map<String, Object> vr : variantRows) {
                variantsByProduct.computeIfAbsent((UUID) vr.get("product_id"), k -> new ArrayList<>()).add(vr);
            }

            List<StockProduct> out = new ArrayList<>();
            for (Map<String, Object> pr : productRows) {
                UUID productId = (UUID) pr.get("id");
                List<Map<String, Object>> prVariants = variantsByProduct.getOrDefault(productId, List.of());

                List<StockVariant> variants = new ArrayList<>();
                long sumOnHand = 0;
                long sumAvailable = 0;
                Long sumCommitted = locationId == null ? 0L : null;

                for (Map<String, Object> vr : prVariants) {
                    UUID varId = (UUID) vr.get("id");
                    long onHand;
                    Long committed;
                    long available;
                    if (locationId != null) {
                        onHand    = locationOnHand.getOrDefault(varId, 0L);
                        committed = null;
                        available = onHand;
                    } else {
                        var stock = stockService.forVariant(tenantWide, varId);
                        onHand    = stock.onHand();
                        committed = stock.committed();
                        available = stock.available();
                    }
                    sumOnHand    += onHand;
                    sumAvailable += available;
                    if (sumCommitted != null) sumCommitted += committed;

                    variants.add(new StockVariant(
                        varId.toString(), (String) vr.get("title"), (String) vr.get("sku"),
                        (BigDecimal) vr.get("price"), onHand, committed, available,
                        syncByVariant.getOrDefault(varId, "none")));
                }

                out.add(new StockProduct(
                    productId.toString(), (String) pr.get("title"), (String) pr.get("image_url"),
                    sumOnHand, sumCommitted, sumAvailable, variants));
            }

            return new StockPage(out, nextCursor);
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. GET /inventory/variants/{variantId}/breakdown — drawer fetch
    // ═══════════════════════════════════════════════════════════════════════

    public record LocationStock(String locationId, String locationName, long available, long onHand) {}

    public record VariantMovement(
        String id, String triggerType, Integer delta, String status,
        String locationName, String createdAt, String appliedAt) {}

    public record VariantBreakdown(
        String variantId, long onHand, Long committed, long available,
        List<LocationStock> locations, List<VariantMovement> recentMovements) {}

    private static final int RECENT_MOVEMENTS_LIMIT = 10;

    @GetMapping("/variants/{variantId}/breakdown")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    public VariantBreakdown breakdown(@PathVariable UUID variantId) {
        return tx.execute(txs -> {
            UUID tenantId = TenantContext.require();

            Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM variants WHERE id = ? AND tenant_id = ?",
                Integer.class, variantId, tenantId);
            if (exists == null || exists == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Variant not found");
            }

            // Variant-total committed/available/on_hand — tenant-wide, from the one
            // shared derivation. Not location-scoped (see class doc comment).
            var totals = stockService.forVariant(stockService.computeAll(), variantId);

            // location × {available, onHand} — every tenant location, zero-filled.
            // available == onHand at this scope; committed isn't location-scoped, shown
            // once above instead (per the Phase A location-scoping rule).
            List<LocationStock> locations = jdbc.query(
                """
                SELECT l.id, l.name,
                       COALESCE(cnt.on_hand, 0) AS on_hand
                FROM locations l
                LEFT JOIN (
                    SELECT current_location_id, COUNT(*) AS on_hand
                    FROM pieces
                    WHERE tenant_id = ? AND variant_id = ? AND status = 'available'::piece_status
                    GROUP BY current_location_id
                ) cnt ON cnt.current_location_id = l.id
                WHERE l.tenant_id = ?
                ORDER BY l.name ASC
                """,
                (rs, i) -> {
                    long onHand = rs.getLong("on_hand");
                    return new LocationStock(rs.getString("id"), rs.getString("name"), onHand, onHand);
                },
                tenantId, variantId, tenantId);

            // N most-recent Shopify-write movements for this variant. Sourced from
            // shopify_inventory_adjustments only — stock_take_shopify_syncs is
            // session-grain (not per-variant) here too, same fast-follow deferral as
            // GET /inventory/movements (see that endpoint's doc comment).
            List<VariantMovement> movements = jdbc.query(
                """
                SELECT a.id::text AS id, a.trigger_type, a.delta, a.status,
                       l.name AS location_name, a.created_at, a.applied_at
                FROM shopify_inventory_adjustments a
                JOIN locations l ON l.id = a.location_id
                WHERE a.tenant_id = ? AND a.variant_id = ?
                ORDER BY a.created_at DESC, a.id DESC
                LIMIT ?
                """,
                (rs, i) -> new VariantMovement(
                    rs.getString("id"), rs.getString("trigger_type"),
                    (Integer) rs.getObject("delta"), rs.getString("status"),
                    rs.getString("location_name"),
                    rs.getTimestamp("created_at").toInstant().toString(),
                    rs.getTimestamp("applied_at") != null ? rs.getTimestamp("applied_at").toInstant().toString() : null),
                tenantId, variantId, RECENT_MOVEMENTS_LIMIT);

            return new VariantBreakdown(
                variantId.toString(), totals.onHand(), totals.committed(), totals.available(),
                locations, movements);
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. GET /inventory/breakdown — Tab 2, phase → status counts
    // ═══════════════════════════════════════════════════════════════════════

    public record PhaseCounts(
        long inWarehouse, long onTheWayOut, long delivered, long comingBack, long problem) {}

    @GetMapping("/breakdown")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    public PhaseCounts breakdown() {
        return tx.execute(txs -> {
            UUID tenantId = TenantContext.require();

            Map<String, Long> raw = new HashMap<>();
            jdbc.query(
                """
                SELECT status::text AS s, COUNT(*) AS c FROM pieces
                WHERE tenant_id = ?
                  AND status IN ('available','reserved','packed','awaiting_pickup','with_courier',
                                  'return_in_transit','return_pending_inspection','damaged','lost')
                GROUP BY status
                """,
                (RowCallbackHandler) rs -> raw.put(rs.getString("s"), rs.getLong("c")),
                tenantId);

            // Delivered is windowed — last 30 Africa/Cairo calendar days — everything else
            // above is a live point-in-time status count. Cairo-pinned Clock bean (AppConfig),
            // same convention as OverviewService: bucket boundary computed in Java, bound as
            // a single Instant param, not an `AT TIME ZONE` expression in SQL.
            LocalDate today = LocalDate.now(clock);
            Instant lowerBound = today.minusDays(29).atStartOfDay(CAIRO).toInstant();
            Long delivered = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM pieces p
                WHERE p.tenant_id = ? AND p.status = 'delivered'::piece_status
                  AND EXISTS (
                      SELECT 1 FROM piece_events pe
                      WHERE pe.tenant_id = p.tenant_id AND pe.piece_id = p.id
                        AND pe.to_status = 'delivered'::piece_status
                        AND pe.occurred_at >= ?
                  )
                """,
                Long.class, tenantId, Timestamp.from(lowerBound));

            long g = raw.getOrDefault("available", 0L) + raw.getOrDefault("reserved", 0L);
            long out = raw.getOrDefault("packed", 0L) + raw.getOrDefault("awaiting_pickup", 0L)
                     + raw.getOrDefault("with_courier", 0L);
            long back = raw.getOrDefault("return_in_transit", 0L) + raw.getOrDefault("return_pending_inspection", 0L);
            long problem = raw.getOrDefault("damaged", 0L) + raw.getOrDefault("lost", 0L);

            return new PhaseCounts(g, out, delivered != null ? delivered : 0L, back, problem);
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. GET /inventory/pieces — piece drill under one status
    // ═══════════════════════════════════════════════════════════════════════

    public record StockPieceRow(
        String id, String barcode, String variantTitle, String sku, String productTitle,
        String orderNumber, String trackingNumber, String locationName, String lastEventAt) {}

    public record StockPiecePage(List<StockPieceRow> items, String nextCursor) {}

    @GetMapping("/pieces")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    public StockPiecePage pieces(
            @RequestParam String status,
            @RequestParam(required = false) UUID variantId,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size) {

        try { PieceStatus.fromDb(status); }
        catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid status: " + status);
        }
        int pageSize = Math.min(Math.max(size, 1), 100);

        return tx.execute(txs -> {
            UUID tenantId = TenantContext.require();

            List<Object> params = new ArrayList<>();
            StringBuilder where = new StringBuilder(
                "WHERE p.tenant_id = ? AND p.status = ?::piece_status ");
            params.add(tenantId);
            params.add(status);

            if (variantId != null) {
                where.append("AND p.variant_id = ? ");
                params.add(variantId);
            }
            if (locationId != null) {
                where.append("AND p.current_location_id = ? ");
                params.add(locationId);
            }
            if (cursor != null && !cursor.isBlank()) {
                String[] parts = decodeCursor(cursor);
                if (parts.length != 2) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor");
                where.append("AND (p.created_at, p.id) < (?, ?) ");
                params.add(Timestamp.from(Instant.parse(parts[0])));
                params.add(parts[1]);
            }
            params.add(pageSize + 1);

            String sql = """
                SELECT p.id, p.barcode, p.created_at,
                       v.title AS variant_title, v.sku, pr.title AS product_title,
                       o.number AS order_number, s.tracking_number,
                       l.name AS location_name, p.last_event_at
                FROM pieces p
                JOIN variants v ON v.id = p.variant_id
                JOIN products pr ON pr.id = v.product_id
                LEFT JOIN orders o ON o.id = p.current_order_id
                LEFT JOIN locations l ON l.id = p.current_location_id
                LEFT JOIN LATERAL (
                    SELECT tracking_number FROM shipments
                    WHERE order_id = o.id AND tenant_id = o.tenant_id AND shipment_leg = 'forward'
                    ORDER BY created_at DESC, id DESC LIMIT 1
                ) s ON o.id IS NOT NULL
                """ + where + "ORDER BY p.created_at DESC, p.id DESC LIMIT ?";

            List<Map<String, Object>> rows = jdbc.queryForList(sql, params.toArray());

            boolean hasMore = rows.size() > pageSize;
            if (hasMore) rows = rows.subList(0, pageSize);

            String nextCursor = null;
            if (hasMore && !rows.isEmpty()) {
                Map<String, Object> last = rows.get(rows.size() - 1);
                Timestamp createdAt = (Timestamp) last.get("created_at");
                nextCursor = encodeCursor(createdAt.toInstant().toString(), (String) last.get("id"));
            }

            List<StockPieceRow> items = rows.stream().map(r -> new StockPieceRow(
                (String) r.get("id"), (String) r.get("barcode"),
                (String) r.get("variant_title"), (String) r.get("sku"), (String) r.get("product_title"),
                (String) r.get("order_number"), (String) r.get("tracking_number"),
                (String) r.get("location_name"),
                r.get("last_event_at") != null ? ((Timestamp) r.get("last_event_at")).toInstant().toString() : null
            )).toList();

            return new StockPiecePage(items, nextCursor);
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. GET /inventory/movements — Tab 3, unified adjustment ledger
    // ═══════════════════════════════════════════════════════════════════════

    public record MovementRow(
        String id, String source, String triggerType,
        String variantId, String sku, String variantTitle, String productTitle,
        String locationName, Integer delta, String syncStatus,
        String createdAt, String appliedAt) {}

    public record MovementPage(List<MovementRow> items, String nextCursor) {}

    @GetMapping("/movements")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    public MovementPage movements(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size) {

        int pageSize = Math.min(Math.max(size, 1), 100);

        return tx.execute(txs -> {
            UUID tenantId = TenantContext.require();

            Timestamp cursorCreatedAt = null;
            String    cursorSortKey   = null;
            if (cursor != null && !cursor.isBlank()) {
                String[] parts = decodeCursor(cursor);
                if (parts.length != 2) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor");
                cursorCreatedAt = Timestamp.from(Instant.parse(parts[0]));
                cursorSortKey   = parts[1];
            }

            // UNION of the two Shopify-write logs. NOT derived from piece_events, and does
            // NOT include order pack/ship movements — Shopify self-decrements at order/
            // fulfillment time on its own side; a sync cell here would be false (Traced
            // never pushes that decrement). shopify_inventory_adjustments is per-variant
            // (delta already correctly signed — 0 for damage_move moves, positive for the
            // increment triggers). stock_take_shopify_syncs is session-grain — per-variant
            // payload decomposition is a fast-follow, so it surfaces as ONE row per session
            // with variant fields null and delta = the negated sum of the session's
            // write-off magnitudes (payload.deltas values are positive piece counts; the
            // Shopify effect is a decrement, hence the negation).
            String sql = """
                WITH combined AS (
                    SELECT a.id::text AS id, 'adjustment' AS source, a.trigger_type,
                           a.variant_id::text AS variant_id, v.sku, v.title AS variant_title,
                           pr.title AS product_title, l.name AS location_name,
                           a.delta AS delta, a.status AS sync_status,
                           a.created_at, a.applied_at,
                           'a:' || a.id::text AS sort_key
                    FROM shopify_inventory_adjustments a
                    JOIN variants  v  ON v.id = a.variant_id
                    JOIN products  pr ON pr.id = v.product_id
                    JOIN locations l  ON l.id = a.location_id
                    WHERE a.tenant_id = ?

                    UNION ALL

                    SELECT s.id::text AS id, 'stock_take' AS source, NULL AS trigger_type,
                           NULL AS variant_id, NULL AS sku, NULL AS variant_title,
                           NULL AS product_title,
                           loc.name AS location_name,
                           -- ::int cast is load-bearing, not decorative: SUM(int) always
                           -- returns bigint in Postgres, and a UNION widens BOTH arms'
                           -- column type to the wider one — without this cast, the
                           -- adjustment side's genuinely-int a.delta would silently become
                           -- bigint too, breaking the (Integer) cast in the Java row mapper.
                           (-COALESCE((SELECT SUM(value::int) FROM jsonb_each_text(s.payload->'deltas')), 0))::int AS delta,
                           s.status AS sync_status,
                           s.created_at, s.pushed_at AS applied_at,
                           's:' || s.id::text AS sort_key
                    FROM stock_take_shopify_syncs s
                    LEFT JOIN locations loc ON loc.id = NULLIF(s.payload->>'locationId', '')::uuid
                    WHERE s.tenant_id = ?
                )
                SELECT * FROM combined
                """ +
                (cursor != null && !cursor.isBlank()
                    ? "WHERE (created_at, sort_key) < (?, ?) "
                    : "") +
                "ORDER BY created_at DESC, sort_key DESC LIMIT ?";

            List<Object> params = new ArrayList<>();
            params.add(tenantId);
            params.add(tenantId);
            if (cursor != null && !cursor.isBlank()) {
                params.add(cursorCreatedAt);
                params.add(cursorSortKey);
            }
            params.add(pageSize + 1);

            List<Map<String, Object>> rows = jdbc.queryForList(sql, params.toArray());

            boolean hasMore = rows.size() > pageSize;
            if (hasMore) rows = rows.subList(0, pageSize);

            String nextCursor = null;
            if (hasMore && !rows.isEmpty()) {
                Map<String, Object> last = rows.get(rows.size() - 1);
                Timestamp createdAt = (Timestamp) last.get("created_at");
                nextCursor = encodeCursor(createdAt.toInstant().toString(), (String) last.get("sort_key"));
            }

            // Normalize each source's own status vocabulary to one sync-status cell:
            // adjustment: shadow/pending -> pending, applied -> synced, failed -> failed.
            // stock_take: pending -> pending, pushed -> synced, failed/failed_ambiguous -> failed.
            List<MovementRow> items = rows.stream().map(r -> {
                String rawStatus = (String) r.get("sync_status");
                String syncStatus = switch (rawStatus) {
                    case "applied", "pushed" -> "synced";
                    case "shadow", "pending" -> "pending";
                    case "failed", "failed_ambiguous" -> "failed";
                    default -> rawStatus;
                };
                Timestamp createdAt = (Timestamp) r.get("created_at");
                Timestamp appliedAt = (Timestamp) r.get("applied_at");
                return new MovementRow(
                    (String) r.get("id"), (String) r.get("source"), (String) r.get("trigger_type"),
                    (String) r.get("variant_id"), (String) r.get("sku"), (String) r.get("variant_title"),
                    (String) r.get("product_title"), (String) r.get("location_name"),
                    (Integer) r.get("delta"), syncStatus,
                    createdAt.toInstant().toString(),
                    appliedAt != null ? appliedAt.toInstant().toString() : null);
            }).toList();

            return new MovementPage(items, nextCursor);
        });
    }
}
