package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Pick &amp; Pack S2 — database side of batch waybill printing. Every method runs in its own
 * transaction (called through the Spring proxy from {@link PackPrintBatchService}), so the
 * Bosta calls the service makes in between are never inside a transaction.
 */
@Component
public class PackPrintBatchStore {

    /** One waybill that can be printed: an order and its latest forward shipment. */
    public record Candidate(UUID orderId, String orderNumber, UUID shipmentId, String trackingNumber) {}

    /** One printed waybill, in batch (PDF) order. */
    public record PrintedItem(UUID orderId, UUID shipmentId, String trackingNumber) {}

    public record RecordedBatch(UUID batchId, int batchNo) {}

    private final JdbcTemplate jdbc;
    private final int          lookbackDays;

    public PackPrintBatchStore(JdbcTemplate jdbc,
                               @Value("${shopify.import.lookback-days:30}") int lookbackDays) {
        this.jdbc         = jdbc;
        this.lookbackDays = lookbackDays;
    }

    /**
     * "Ready to print" = the Pick &amp; Pack queue's set ({@link FulfillService#PICKABLE_ORDERS_FILTER},
     * incl. the lookback window) minus self-pickup, whose latest forward shipment is 'created'
     * and has a tracking number. scope 'new' additionally drops shipments already in a batch.
     *
     * The {@code fs} LATERAL picks the latest forward shipment with the same ordering as the
     * gate's own {@code latest_shipment} LATERAL (created_at DESC, id DESC), so both name the
     * same row; it is joined before the filter because the filter carries its own WHERE.
     *
     * Sort: o.created_at then o.id, both in the chosen direction — deterministic.
     */
    @Transactional(readOnly = true)
    public List<Candidate> candidates(String scope, String sort) {
        UUID tenantId = TenantContext.require();
        String dir = "newest".equals(sort) ? "DESC" : "ASC";
        String sql =
            "SELECT o.id AS order_id, o.number, fs.id AS shipment_id, fs.tracking_number " +
            "FROM orders o " +
            "JOIN LATERAL ( " +
            "    SELECT id, tracking_number, internal_state " +
            "    FROM shipments " +
            "    WHERE order_id = o.id AND tenant_id = o.tenant_id " +
            "      AND shipment_leg = 'forward' " +
            // UUIDv4 is not time-ordered — order by created_at, never id (see CLAUDE.md invariant)
            "    ORDER BY created_at DESC, id DESC " +
            "    LIMIT 1 " +
            ") fs ON true " +
            FulfillService.PICKABLE_ORDERS_FILTER +
            "  AND o.is_self_pickup = false " +
            "  AND fs.internal_state = 'created' " +
            "  AND fs.tracking_number IS NOT NULL " +
            ("new".equals(scope)
                ? "  AND NOT EXISTS (SELECT 1 FROM pack_print_batch_items bi " +
                  "                  WHERE bi.shipment_id = fs.id AND bi.tenant_id = o.tenant_id) "
                : "") +
            "ORDER BY o.created_at " + dir + ", o.id " + dir;
        return jdbc.query(sql,
            (rs, i) -> new Candidate(
                rs.getObject("order_id", UUID.class),
                rs.getString("number"),
                rs.getObject("shipment_id", UUID.class),
                rs.getString("tracking_number")),
            tenantId, lookbackDays);
    }

    /** The paper the print dialog preselects: the store's Bosta awb_format, else A4 (BostaAwbService's own fallback). */
    @Transactional(readOnly = true)
    public String defaultPaper() {
        UUID tenantId = TenantContext.require();
        List<String> formats = jdbc.queryForList(
            "SELECT awb_format FROM courier_accounts " +
            "WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active' LIMIT 1",
            String.class, tenantId);
        String f = formats.isEmpty() ? null : formats.get(0);
        return "A6".equals(f) ? "A6" : "A4";
    }

    /**
     * Writes one batch and its items in one transaction, after the PDF is built.
     *
     * batch_no: a per-tenant pg_advisory_xact_lock serializes allocation, so two prints
     * committing at the same time in one tenant take MAX+1 one after the other and never
     * collide (the lock is held only for this short write, never across Bosta calls).
     * UNIQUE (tenant_id, batch_no) is the backstop. Lock key is namespaced so it can't
     * contend with the reconcile / activation tenant locks.
     */
    @Transactional
    public RecordedBatch record(UUID actorUserId, String paper, String sort, String scope,
                                boolean orderGuaranteed, List<PrintedItem> items) {
        UUID tenantId = TenantContext.require();
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
            try (var ps = con.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)")) {
                ps.setString(1, "pack_print_batch:" + tenantId);
                ps.execute();
            }
            return null;
        });

        Integer batchNo = jdbc.queryForObject(
            "SELECT COALESCE(MAX(batch_no), 0) + 1 FROM pack_print_batches WHERE tenant_id = ?",
            Integer.class, tenantId);
        UUID batchId = jdbc.queryForObject(
            "INSERT INTO pack_print_batches " +
            "(tenant_id, batch_no, printed_by, paper, sort, scope, waybill_count, order_guaranteed) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
            UUID.class, tenantId, batchNo, actorUserId, paper, sort, scope, items.size(), orderGuaranteed);

        // position = 1-based place in the PDF (the list is already in PDF order).
        List<Object[]> rows = new java.util.ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            PrintedItem it = items.get(i);
            rows.add(new Object[]{batchId, tenantId, it.orderId(), it.shipmentId(), it.trackingNumber(), i + 1});
        }
        jdbc.batchUpdate(
            "INSERT INTO pack_print_batch_items " +
            "(batch_id, tenant_id, order_id, shipment_id, tracking_number, position) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            rows);
        return new RecordedBatch(batchId, batchNo);
    }
}
