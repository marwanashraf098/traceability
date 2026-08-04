package com.traceability.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

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

    public CatalogController(JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.tx   = new TransactionTemplate(txm);
    }

    public record VariantRow(
        String id, String title, String sku, BigDecimal price,
        Map<String, Long> pieceCounts, long committed, long available) {}

    public record ProductRow(
        String id, String title, String status,
        List<VariantRow> variants) {}

    public record CatalogResponse(List<ProductRow> products) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    public CatalogResponse list() {
        return tx.execute(txs -> {
            // Products ordered by title — explicit tenant filter is defense-in-depth on top of RLS.
            List<Map<String, Object>> productRows = jdbc.queryForList(
                "SELECT id, title, status FROM products " +
                "WHERE tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid " +
                "ORDER BY title");

            // All variant piece counts for this tenant in one query. Tenant-wide,
            // unscoped by location — the existing Total / Stock breakdown display.
            Map<UUID, Map<String, Long>> counts = new HashMap<>();
            jdbc.query(
                """
                SELECT p.variant_id, p.status::text AS status_text, COUNT(*) AS cnt
                FROM pieces p
                WHERE p.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
                GROUP BY p.variant_id, p.status
                """,
                rs -> {
                    UUID varId = rs.getObject("variant_id", UUID.class);
                    counts.computeIfAbsent(varId, k -> new HashMap<>())
                          .put(rs.getString("status_text"), rs.getLong("cnt"));
                });

            // committed(V): order_items.quantity summed over orders in the
            // pre-courier, non-cancelled window (ordered -> handed to courier,
            // inclusive — packed/awaiting_pickup are still in our custody and
            // still owed). Derived on read, no stored counter. order_items
            // carries no location column — inherently tenant+variant scoped.
            Map<UUID, Long> committedByVariant = new HashMap<>();
            jdbc.query(
                """
                SELECT oi.variant_id, SUM(oi.quantity) AS committed
                FROM order_items oi
                JOIN orders o ON o.id = oi.order_id
                WHERE oi.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
                  AND o.status IN ('new','confirmed','ready_to_pick',
                                    'picking','packed','awaiting_pickup')
                GROUP BY oi.variant_id
                """,
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> committedByVariant.put(
                    rs.getObject("variant_id", UUID.class), rs.getLong("committed")));

            // on_hand(V): pieces physically present at a fulfillment location
            // (is_fulfillment=true). Scoped by location, unlike the Total/breakdown
            // counts above — will diverge from the tenant-wide badges once a
            // second, non-fulfillment location exists.
            Map<UUID, Long> onHandByVariant = new HashMap<>();
            jdbc.query(
                """
                SELECT p.variant_id, COUNT(*) AS on_hand
                FROM pieces p
                WHERE p.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
                  AND p.status IN ('available','reserved','packed','awaiting_pickup')
                  AND p.current_location_id IN (
                      SELECT id FROM locations
                      WHERE tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
                        AND is_fulfillment = true
                  )
                GROUP BY p.variant_id
                """,
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> onHandByVariant.put(
                    rs.getObject("variant_id", UUID.class), rs.getLong("on_hand")));

            List<ProductRow> products = new ArrayList<>();
            for (Map<String, Object> pr : productRows) {
                UUID productId = (UUID) pr.get("id");

                List<Map<String, Object>> variantRows = jdbc.queryForList(
                    """
                    SELECT id, title, sku, price FROM variants
                    WHERE product_id = ?
                      AND tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
                    ORDER BY title
                    """, productId);

                List<VariantRow> variants = new ArrayList<>();
                for (Map<String, Object> vr : variantRows) {
                    UUID varId = (UUID) vr.get("id");
                    Map<String, Long> statusMap = counts.getOrDefault(varId, Map.of());

                    // Build the counts map with 0 defaults for all statuses
                    Map<String, Long> pieceCounts = new LinkedHashMap<>();
                    long total = 0;
                    for (String s : ALL_STATUSES) {
                        long c = statusMap.getOrDefault(s, 0L);
                        pieceCounts.put(s, c);
                        total += c;
                    }
                    pieceCounts.put("total", total);

                    long committed = committedByVariant.getOrDefault(varId, 0L);
                    long onHand    = onHandByVariant.getOrDefault(varId, 0L);
                    long available = onHand - committed;

                    variants.add(new VariantRow(
                        varId.toString(),
                        (String) vr.get("title"),
                        (String) vr.get("sku"),
                        (BigDecimal) vr.get("price"),
                        pieceCounts,
                        committed,
                        available
                    ));
                }

                products.add(new ProductRow(
                    productId.toString(),
                    (String) pr.get("title"),
                    (String) pr.get("status"),
                    variants
                ));
            }

            return new CatalogResponse(products);
        });
    }
}
