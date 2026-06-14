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

    // All piece_status values in display order
    private static final List<String> ALL_STATUSES = List.of(
        "available", "reserved", "packed", "awaiting_pickup",
        "with_courier", "delivered", "return_in_transit",
        "return_pending_inspection", "damaged", "lost", "destroyed"
    );

    public CatalogController(JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.tx   = new TransactionTemplate(txm);
    }

    public record VariantRow(
        String id, String title, String sku, BigDecimal price,
        Map<String, Long> pieceCounts) {}

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

            // All variant piece counts for this tenant in one query.
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

                    variants.add(new VariantRow(
                        varId.toString(),
                        (String) vr.get("title"),
                        (String) vr.get("sku"),
                        (BigDecimal) vr.get("price"),
                        pieceCounts
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
