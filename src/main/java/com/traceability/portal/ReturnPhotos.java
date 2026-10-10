package com.traceability.portal;

import com.traceability.assets.BinaryAssetStore;
import com.traceability.assets.PostgresBinaryAssetStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Returns portal P3 — the ONE place return_request_photos is written after upload: claiming
 * (submission), unclaimed expiry, the 90-day retention purge and GDPR redaction. Not a bean —
 * every caller builds it from its own JdbcTemplate, so it joins the caller's transaction and
 * tenant (RLS); every statement also binds tenant_id. Photo bytes are portal_assets rows (kind
 * 'photo') reached only through {@link BinaryAssetStore}; "removing the bytes" = deleting the
 * asset (the photo's asset_id goes NULL) and stamping redacted_at + redaction_reason.
 */
public final class ReturnPhotos {

    /** An upload not claimed by a submission within this long is deleted. */
    public static final int UNCLAIMED_MINUTES = 60;
    /** Photo bytes are removed this many days after the request ended (P2's "ended" definition). */
    public static final int RETENTION_DAYS = 90;
    public static final int MAX_PER_LINE = 3;

    private final JdbcTemplate jdbc;
    private final BinaryAssetStore store;

    public ReturnPhotos(JdbcTemplate jdbc) {
        this(jdbc, new PostgresBinaryAssetStore(jdbc));
    }

    public ReturnPhotos(JdbcTemplate jdbc, BinaryAssetStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    /**
     * Claims these uploads for a request line: only photos of THIS order, unclaimed, still holding
     * bytes and younger than {@link #UNCLAIMED_MINUTES}. Returns how many were claimed — the caller
     * rejects the submission unless it equals ids.size().
     */
    public int claim(UUID tenantId, UUID orderId, UUID requestId, UUID itemId, Collection<UUID> ids) {
        if (ids.isEmpty()) return 0;
        return jdbc.update(
            "UPDATE return_request_photos SET request_id = ?, item_id = ?, claimed_at = now() " +
            "WHERE tenant_id = ? AND order_id = ? AND id = ANY(?::uuid[]) AND request_id IS NULL " +
            "  AND asset_id IS NOT NULL AND created_at > now() - (interval '1 minute' * ?)",
            requestId, itemId, tenantId, orderId, ids.toArray(new UUID[0]), UNCLAIMED_MINUTES);
    }

    /** A request's photos (oldest first): id, item_id, width, height, available, redacted_at, redaction_reason. */
    public List<Map<String, Object>> forRequest(UUID tenantId, UUID requestId) {
        return jdbc.queryForList(
            "SELECT id, item_id, width, height, (asset_id IS NOT NULL) AS available, redacted_at, redaction_reason " +
            "FROM return_request_photos WHERE tenant_id = ? AND request_id = ? ORDER BY created_at, id",
            tenantId, requestId);
    }

    /** Deletes uploads nobody claimed within {@link #UNCLAIMED_MINUTES} — rows and bytes. */
    public int expireUnclaimed(UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, asset_id FROM return_request_photos WHERE tenant_id = ? AND request_id IS NULL " +
            "  AND created_at < now() - (interval '1 minute' * ?)", tenantId, UNCLAIMED_MINUTES);
        if (rows.isEmpty()) return 0;
        List<UUID> ids = new ArrayList<>(), assets = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            ids.add((UUID) r.get("id"));
            if (r.get("asset_id") != null) assets.add((UUID) r.get("asset_id"));
        }
        int n = jdbc.update("DELETE FROM return_request_photos WHERE tenant_id = ? AND id = ANY(?::uuid[]) " +
            "AND request_id IS NULL", tenantId, ids.toArray(new UUID[0]));
        store.delete(tenantId, assets);
        return n;
    }

    /** Removes the bytes of claimed photos {@link #RETENTION_DAYS} days after their request ended. */
    public int purgeEnded(UUID tenantId) {
        return removeBytes(tenantId,
            "SELECT p.id, p.asset_id FROM return_request_photos p " +
            "JOIN return_requests rr ON rr.id = p.request_id AND rr.tenant_id = p.tenant_id " +
            "WHERE p.tenant_id = ? AND p.asset_id IS NOT NULL " +
            "  AND " + RefundDetailsPurgeService.ENDED_AT_SQL + " < now() - (interval '1 day' * ?)",
            "retention", tenantId, RETENTION_DAYS);
    }

    /** customers/redact: every photo (claimed or not) of these orders. */
    public int redactOrders(UUID tenantId, Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) return 0;
        return removeBytes(tenantId,
            "SELECT id, asset_id FROM return_request_photos WHERE tenant_id = ? AND order_id = ANY(?::uuid[]) " +
            "  AND asset_id IS NOT NULL",
            "privacy", tenantId, orderIds.toArray(new UUID[0]));
    }

    /** shop/redact: every photo of the tenant. */
    public int redactTenant(UUID tenantId) {
        return removeBytes(tenantId,
            "SELECT id, asset_id FROM return_request_photos WHERE tenant_id = ? AND asset_id IS NOT NULL",
            "privacy", tenantId);
    }

    private int removeBytes(UUID tenantId, String selectSql, String reason, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(selectSql, args);
        if (rows.isEmpty()) return 0;
        List<UUID> ids = new ArrayList<>(), assets = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            ids.add((UUID) r.get("id"));
            assets.add((UUID) r.get("asset_id"));
        }
        int n = jdbc.update(
            "UPDATE return_request_photos SET asset_id = NULL, redacted_at = now(), redaction_reason = ? " +
            "WHERE tenant_id = ? AND id = ANY(?::uuid[])", reason, tenantId, ids.toArray(new UUID[0]));
        store.delete(tenantId, assets);
        return n;
    }
}
