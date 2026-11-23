package com.traceability.bazar;

import com.traceability.inventory.MainReconcileQueue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * U1a (2026-11-22, prod test20) — a Keep-mode bazar ('leave' — "Keep showing online") writes nothing to Shopify for its
 * sales, returns, damaged, lost or close (BazarOnlineStock), so no claim row exists and no claim trigger queues the
 * variant for Main reconcile: the store's Shopify Main count stayed high until the 4-hourly sweep. Every such change
 * queues its variants through {@link MainReconcileQueue#onTracedChange} (after the caller's transaction commits), so
 * the next queued run (≈ 1 min) sees the difference and the normal two-run rule writes it (≈ 11–12 min).
 *
 * ONE rule for "this bazar's changes queue": its transfer's snapshot {@code shopify_sync_mode = 'leave'}. A Remove-mode
 * bazar queues nothing here (unchanged: its pieces left Shopify at send). Scan-back good and bring-back by count are
 * not queued (they don't change the target). Never throws into the caller; no Shopify call.
 */
@Component
public class BazarKeepModeQueue {

    static final String SALE = "bazar:sale", UNDO = "bazar:undo", RETURN = "bazar:return",
        LOST = "bazar:lost", CLOSE = "bazar:close";

    private final JdbcTemplate jdbc;
    private final MainReconcileQueue queue;

    public BazarKeepModeQueue(JdbcTemplate jdbc, MainReconcileQueue queue) {
        this.jdbc = jdbc;
        this.queue = queue;
    }

    /** True when the transfer's online-stock snapshot is Keep ('leave'). Runs in the caller's transaction. */
    boolean keep(UUID tenantId, UUID transferId) {
        if (transferId == null) return false;
        List<String> mode = jdbc.queryForList(
            "SELECT shopify_sync_mode FROM transfers WHERE id = ? AND tenant_id = ?", String.class, transferId, tenantId);
        return !mode.isEmpty() && BazarOnlineStock.LEAVE.equals(mode.get(0));
    }

    /** These pieces changed at a bazar: queue their variants when the bazar is Keep. */
    void pieces(UUID tenantId, UUID transferId, Collection<String> pieceIds, String source) {
        if (pieceIds == null || pieceIds.isEmpty() || !keep(tenantId, transferId)) return;
        List<UUID> variants = jdbc.queryForList(
            "SELECT DISTINCT variant_id FROM pieces WHERE tenant_id = ? AND id = ANY(?) AND variant_id IS NOT NULL",
            UUID.class, tenantId, pieceIds.toArray(new String[0]));
        queue.onTracedChange(tenantId, variants, source);
    }

    /** The transfer closed: queue every variant it carried (the catch-all) when the bazar is Keep. */
    void transfer(UUID tenantId, UUID transferId, String source) {
        if (!keep(tenantId, transferId)) return;
        List<UUID> variants = jdbc.queryForList(
            "SELECT DISTINCT p.variant_id FROM transfer_pieces tp JOIN pieces p ON p.id = tp.piece_id AND p.tenant_id = tp.tenant_id " +
            "WHERE tp.transfer_id = ? AND tp.tenant_id = ? AND p.variant_id IS NOT NULL", UUID.class, transferId, tenantId);
        queue.onTracedChange(tenantId, variants, source);
    }
}
