package com.traceability.catalog;

import com.traceability.inventory.VariantStockService;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final VariantStockService stockService;

    // All piece_status values in display order. Widened for FR-22 (out_on_transfer, sold) —
    // this list drives ONLY the raw per-status pieceCounts breakdown + its "total" key
    // (a display figure — confirmed no consumer sums it as a sellable/on-hand number;
    // committed/on_hand/available above are computed from separate, independent SQL).
    // Before the fix, an out_on_transfer or sold piece was invisible in the breakdown and
    // silently missing from "total": pieceCounts no longer summed to the true piece count.
    private static final List<String> ALL_STATUSES = List.of(
        "available", "reserved", "packed", "awaiting_pickup",
        "with_courier", "delivered", "return_in_transit",
        "return_pending_inspection", "damaged", "lost", "destroyed",
        "out_on_transfer", "sold"
    );

    public CatalogController(JdbcTemplate jdbc, PlatformTransactionManager txm,
                              VariantStockService stockService) {
        this.jdbc = jdbc;
        this.tx   = new TransactionTemplate(txm);
        this.stockService = stockService;
    }

    public record VariantRow(
        String id, String title, String sku, BigDecimal price,
        Map<String, Long> pieceCounts, long committed, long available) {}

    public record ProductRow(
        String id, String title, String status, String imageUrl,
        List<VariantRow> variants) {}

    public record CatalogResponse(List<ProductRow> products, String nextCursor) {}

    private static final int MAX_LIMIT = 100;

    /**
     * Products with their variants and stock numbers.
     *
     * Filters (all server-side, all optional): q — title or variant SKU contains (case-insensitive);
     * status — one or more product statuses (repeat the param or comma-separate; absent = every
     * status); variantIds — only the products holding these variants (the Receiving grid's
     * "selected" summary uses it to load products that aren't on the loaded page).
     *
     * Pagination: keyset on (title ASC, id ASC) — products carry no created_at column, and title is
     * the order this list (and the Inventory Stock tab) has always had. With limit, at most limit
     * products come back and nextCursor continues the list; without limit every match comes back in
     * one response and nextCursor is null (the pre-pagination contract).
     *
     * Query count is constant in the page size: products, one variants query for all of them, one
     * piece-count query, and VariantStockService's two — never one query per product.
     */
    /** The whole catalog, unfiltered and unpaged — the pre-pagination call (in-process callers). */
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    public CatalogResponse list() {
        return list(null, null, null, null, null);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    public CatalogResponse list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) List<UUID> variantIds,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        List<String> statuses = splitCsv(status);
        Integer pageSize = limit == null ? null : Math.min(Math.max(limit, 1), MAX_LIMIT);
        String[] after = cursor == null || cursor.isBlank() ? null : decodeCursor(cursor);

        return tx.execute(txs -> {
            UUID tenantId = TenantContext.require();
            List<Object> params = new ArrayList<>();
            StringBuilder sql = new StringBuilder(
                "SELECT pr.id, pr.title, pr.status, pr.image_url FROM products pr WHERE pr.tenant_id = ?");
            params.add(tenantId);
            if (!statuses.isEmpty()) {
                sql.append(" AND pr.status = ANY(?)");
                params.add(statuses.toArray(String[]::new));
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim() + "%";
                sql.append(" AND (pr.title ILIKE ? OR EXISTS (SELECT 1 FROM variants v " +
                           "WHERE v.product_id = pr.id AND v.sku ILIKE ?))");
                params.add(like);
                params.add(like);
            }
            if (variantIds != null && !variantIds.isEmpty()) {
                sql.append(" AND EXISTS (SELECT 1 FROM variants v WHERE v.product_id = pr.id AND v.id = ANY(?))");
                params.add(variantIds.toArray(UUID[]::new));
            }
            if (after != null) {
                sql.append(" AND (pr.title, pr.id) > (?, ?)");
                params.add(after[0]);
                params.add(UUID.fromString(after[1]));
            }
            sql.append(" ORDER BY pr.title ASC, pr.id ASC");
            if (pageSize != null) {
                sql.append(" LIMIT ?");
                params.add(pageSize + 1);
            }
            List<Map<String, Object>> productRows = jdbc.query(con -> {
                var ps = con.prepareStatement(sql.toString());
                for (int i = 0; i < params.size(); i++) {
                    Object p = params.get(i);
                    if (p instanceof String[] arr) ps.setArray(i + 1, con.createArrayOf("text", arr));
                    else if (p instanceof UUID[] arr) ps.setArray(i + 1, con.createArrayOf("uuid", arr));
                    else ps.setObject(i + 1, p);
                }
                return ps;
            }, new ColumnMapRowMapper());

            String nextCursor = null;
            if (pageSize != null && productRows.size() > pageSize) {
                productRows = productRows.subList(0, pageSize);
                Map<String, Object> last = productRows.get(productRows.size() - 1);
                nextCursor = encodeCursor((String) last.get("title"), last.get("id").toString());
            }
            if (productRows.isEmpty()) return new CatalogResponse(List.of(), null);

            UUID[] productIds = productRows.stream().map(r -> (UUID) r.get("id")).toArray(UUID[]::new);

            // Every variant of this page's products in ONE query (was one query per product).
            Map<UUID, List<Map<String, Object>>> variantsByProduct = new HashMap<>();
            List<UUID> pageVariantIds = new ArrayList<>();
            jdbc.query(con -> {
                var ps = con.prepareStatement(
                    "SELECT id, product_id, title, sku, price FROM variants " +
                    "WHERE product_id = ANY(?) AND tenant_id = ? ORDER BY product_id, title");
                ps.setArray(1, con.createArrayOf("uuid", productIds));
                ps.setObject(2, tenantId);
                return ps;
            }, (RowCallbackHandler) rs -> {
                Map<String, Object> m = new HashMap<>();
                UUID id = rs.getObject("id", UUID.class);
                m.put("id", id);
                m.put("title", rs.getString("title"));
                m.put("sku", rs.getString("sku"));
                m.put("price", rs.getBigDecimal("price"));
                pageVariantIds.add(id);
                variantsByProduct.computeIfAbsent(rs.getObject("product_id", UUID.class), k -> new ArrayList<>()).add(m);
            });

            // Piece counts per variant and status, for this page's variants only. Tenant-wide,
            // unscoped by location — the existing Total / Stock breakdown display.
            Map<UUID, Map<String, Long>> counts = new HashMap<>();
            if (!pageVariantIds.isEmpty()) {
                jdbc.query(con -> {
                    var ps = con.prepareStatement(
                        "SELECT p.variant_id, p.status::text AS status_text, COUNT(*) AS cnt FROM pieces p " +
                        "WHERE p.tenant_id = ? AND p.variant_id = ANY(?) GROUP BY p.variant_id, p.status");
                    ps.setObject(1, tenantId);
                    ps.setArray(2, con.createArrayOf("uuid", pageVariantIds.toArray()));
                    return ps;
                }, (RowCallbackHandler) rs -> counts
                    .computeIfAbsent(rs.getObject("variant_id", UUID.class), k -> new HashMap<>())
                    .put(rs.getString("status_text"), rs.getLong("cnt")));
            }

            // committed/available/on_hand: derived by the ONE shared service — see
            // VariantStockService's own doc comment for the PHASE 0 committed-inventory
            // fix this replaced (the old orders.status-keyed formula that never advanced
            // past 'awaiting_pickup'). Do not re-derive these three numbers here.
            Map<UUID, VariantStockService.VariantStock> stockByVariant = stockService.computeAll();

            List<ProductRow> products = new ArrayList<>();
            for (Map<String, Object> pr : productRows) {
                UUID productId = (UUID) pr.get("id");

                List<VariantRow> variants = new ArrayList<>();
                for (Map<String, Object> vr : variantsByProduct.getOrDefault(productId, List.of())) {
                    UUID varId = (UUID) vr.get("id");
                    Map<String, Long> statusMap = counts.getOrDefault(varId, Map.of());

                    // Build the counts map with 0 defaults for all statuses
                    Map<String, Long> pieceCounts = new LinkedHashMap<>();
                    long total = 0;
                    for (String st : ALL_STATUSES) {
                        long c = statusMap.getOrDefault(st, 0L);
                        pieceCounts.put(st, c);
                        total += c;
                    }
                    pieceCounts.put("total", total);

                    VariantStockService.VariantStock stock =
                        stockService.forVariant(stockByVariant, varId);

                    variants.add(new VariantRow(
                        varId.toString(),
                        (String) vr.get("title"),
                        (String) vr.get("sku"),
                        (BigDecimal) vr.get("price"),
                        pieceCounts,
                        stock.committed(),
                        stock.available()
                    ));
                }

                products.add(new ProductRow(
                    productId.toString(),
                    (String) pr.get("title"),
                    (String) pr.get("status"),
                    (String) pr.get("image_url"),
                    variants
                ));
            }

            return new CatalogResponse(products, nextCursor);
        });
    }

    private static List<String> splitCsv(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values == null) return out;
        for (String v : values) {
            for (String part : v.split(",")) {
                String t = part.trim().toLowerCase();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return out;
    }

    private static String encodeCursor(String title, String id) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString((title + "\u0000" + id).getBytes(StandardCharsets.UTF_8));
    }

    private static String[] decodeCursor(String cursor) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\u0000", -1);
            if (parts.length != 2) throw new IllegalArgumentException("parts");
            UUID.fromString(parts[1]);
            return parts;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor");
        }
    }
}
