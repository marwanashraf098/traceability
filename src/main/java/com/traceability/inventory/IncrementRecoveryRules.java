package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyGateway;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Failed-increment recovery (Part D) — the ONE place the recovery rules live, shared by the retry
 * job (ShopifyInventoryService.retryDueIncrements / repushFailedIncrement) and the
 * inventory_increment_sync_failed detector (ExceptionService). Change a rule here, never inline.
 *
 * Increment claims are shopify_inventory_adjustments rows of the three positive triggers. A failed
 * claim is retried automatically only when it is not legacy, has made 1–4 attempts and its
 * next_attempt_at has come; an ambiguous one only while its sent key is < 20 h old (Shopify keeps
 * idempotency keys 24 h). A tenant-level setup problem (no store, missing scope, Traced location
 * not linked) blocks every retry — no attempt is spent until it is fixed.
 *
 * A claim the initial seed made redundant is 'superseded_by_seed' (V123, set by
 * ShopifyInventoryReconcileService) — every predicate here is status = 'failed', so it is never
 * retried, never counted by an alert, and claim() never reclaims it.
 */
public final class IncrementRecoveryRules {

    private IncrementRecoveryRules() {}

    /** hold_exit left this set on 2026-10-10: it is a piece-sync claim now (PieceShopifyRules —
     *  single attempt, an ambiguous +1 is never re-sent). */
    public static final List<String> INCREMENT_TRIGGERS =
        List.of("receiving_session", "return_inspection", "stock_take_found", "transfer_return");
    public static final String INCREMENT_TRIGGERS_SQL =
        "('receiving_session', 'return_inspection', 'stock_take_found', 'transfer_return')";

    public static final int MAX_ATTEMPTS = 5;
    public static final int AMBIGUOUS_WINDOW_HOURS = 20;
    public static final int CONFIRM_AFTER_HOURS = 24;

    /** Backoff after attempt n (1-based) fails: 10 min, 1 h, 6 h, 24 h; none after the 5th. SQL over
     *  the row's attempt_count BEFORE it is incremented (Postgres evaluates SET against the old row). */
    public static final String NEXT_ATTEMPT_AFTER_FAILURE_SQL =
        "CASE attempt_count WHEN 0 THEN now() + interval '10 minutes' " +
        "                   WHEN 1 THEN now() + interval '1 hour' " +
        "                   WHEN 2 THEN now() + interval '6 hours' " +
        "                   WHEN 3 THEN now() + interval '24 hours' " +
        "                   ELSE NULL END";

    /** Failed, non-legacy increment claims (alias sia). */
    public static final String FAILED_LIVE_SQL =
        "sia.status = 'failed' AND NOT sia.legacy AND sia.trigger_type IN " + INCREMENT_TRIGGERS_SQL;

    /** Due for an automatic retry now. */
    public static final String DUE_SQL =
        FAILED_LIVE_SQL + " AND sia.attempt_count BETWEEN 1 AND " + (MAX_ATTEMPTS - 1) +
        " AND sia.next_attempt_at IS NOT NULL AND sia.next_attempt_at <= now()";

    /** An ambiguous claim whose sent key is too old to resend identically. */
    public static final String AMBIGUOUS_EXPIRED_SQL =
        "(sia.failure_class = 'ambiguous' AND sia.sent_key_first_at < now() - interval '" + AMBIGUOUS_WINDOW_HOURS + " hours')";

    /** Nothing left to try automatically: attempts exhausted, or ambiguous past the window. */
    public static final String GAVE_UP_SQL =
        FAILED_LIVE_SQL + " AND (sia.attempt_count >= " + MAX_ATTEMPTS + " OR " + AMBIGUOUS_EXPIRED_SQL + ")";

    /** Legacy backlog (failed before V121). */
    public static final String LEGACY_SQL =
        "sia.status = 'failed' AND sia.legacy AND sia.trigger_type IN " + INCREMENT_TRIGGERS_SQL;

    public enum SetupProblem {
        NO_STORE("Connect your Shopify store", "اربط متجر Shopify"),
        /** Shopify rejected the token refresh (stores.status = 'needs_reauth'): nothing can reach Shopify until the
         *  merchant reconnects — retries wait without spending an attempt, then run on their own after reconnect. */
        NEEDS_REAUTH("Reconnect Shopify — the connection was lost", "أعد ربط Shopify — انقطع الاتصال"),
        MISSING_SCOPE("Reconnect Shopify to grant inventory access", "أعد ربط Shopify لمنح صلاحية المخزون"),
        LOCATION_NOT_LINKED("Link your warehouse to a Shopify location", "اربط مستودعك بموقع في Shopify"),
        /** The Traced location was deactivated in Shopify (locations.shopify_location_state, V196): held like
         *  NEEDS_REAUTH — nothing is sent, no attempt is spent — until it is reactivated. */
        LOCATION_INACTIVE("Reactivate the Traced location in Shopify", "أعد تفعيل موقع Traced في Shopify"),
        /** Shopify has no location with the linked id any more: held until the location is recreated. */
        LOCATION_DELETED("The Traced location was deleted in Shopify — reconnect Shopify or contact Traced support to recreate it",
                         "تم حذف موقع Traced في Shopify — أعد ربط Shopify أو تواصل مع دعم Traced لإعادة إنشائه");

        public final String fixEn;
        public final String fixAr;
        SetupProblem(String fixEn, String fixAr) { this.fixEn = fixEn; this.fixAr = fixAr; }
    }

    /**
     * The tenant-level precondition every increment needs, or null when it's all in place: an active
     * store (same pick as StoreRepository.findActiveStoreByTenant) whose token has read_products and
     * write_inventory, and a fulfillment location linked to Shopify. Runs on the caller's JdbcTemplate
     * (tenant-scoped under RLS on app_user).
     */
    public static SetupProblem setupProblem(JdbcTemplate jdbc, UUID tenantId) {
        List<Map<String, Object>> stores = jdbc.queryForList(ACTIVE_STORE_SQL, tenantId);
        if (stores.isEmpty()) return SetupProblem.NO_STORE;
        if ("needs_reauth".equals(String.valueOf(stores.get(0).get("status")))) return SetupProblem.NEEDS_REAUTH;
        String granted = (String) stores.get(0).get("access_token_scopes");
        if (!ShopifyGateway.isScopeGranted("read_products", granted)
                || !ShopifyGateway.isScopeGranted("write_inventory", granted)) {
            return SetupProblem.MISSING_SCOPE;
        }
        Integer linked = jdbc.queryForObject(
            "SELECT COUNT(*) FROM locations WHERE tenant_id = ? AND is_fulfillment = true " +
            "  AND shopify_sync_status = 'linked' AND shopify_location_id IS NOT NULL",
            Integer.class, tenantId);
        if (linked == null || linked == 0) return SetupProblem.LOCATION_NOT_LINKED;
        return locationProblem(jdbc, tenantId);
    }

    /** The main warehouse's state in Shopify (V196): LOCATION_INACTIVE / LOCATION_DELETED, or null while active. */
    private static SetupProblem locationProblem(JdbcTemplate jdbc, UUID tenantId) {
        List<String> state = jdbc.queryForList(
            "SELECT shopify_location_state FROM locations WHERE tenant_id = ? AND is_fulfillment = true " +
            "  AND shopify_sync_status = 'linked'", String.class, tenantId);
        if (state.isEmpty()) return null;
        return switch (state.get(0)) {
            case "inactive" -> SetupProblem.LOCATION_INACTIVE;
            case "deleted"  -> SetupProblem.LOCATION_DELETED;
            default -> null;
        };
    }

    /**
     * Why every Shopify stock update is HELD right now, or null: the store needs a reconnect (NEEDS_REAUTH), or the
     * Traced location is deactivated / deleted in Shopify (LOCATION_INACTIVE / LOCATION_DELETED). Every sender checks
     * it before sending anything: a held claim waits — 'queued' or 'failed' with its attempts intact — and goes out
     * once the cause is gone. THE hold rule; never check one cause alone.
     */
    public static SetupProblem holdReason(JdbcTemplate jdbc, UUID tenantId) {
        if (connectionLost(jdbc, tenantId)) return SetupProblem.NEEDS_REAUTH;
        return locationProblem(jdbc, tenantId);
    }

    public static boolean held(JdbcTemplate jdbc, UUID tenantId) {
        return holdReason(jdbc, tenantId) != null;
    }

    /** The tenant's active store (same pick as StoreRepository.findActiveStoreByTenant): status + scopes. */
    private static final String ACTIVE_STORE_SQL =
        "SELECT status::text AS status, access_token_scopes FROM stores WHERE tenant_id = ? AND status <> 'disconnected' " +
        "ORDER BY (status = 'connected') DESC, last_sync_at DESC NULLS LAST LIMIT 1";

    /**
     * True while the tenant's active store needs a reconnect (Shopify rejected the token refresh). Every Shopify
     * sweep checks it before sending anything, so a claim waits — 'queued' or 'failed' with its attempts intact —
     * and goes out once the merchant reconnects (status back to 'connected').
     */
    public static boolean connectionLost(JdbcTemplate jdbc, UUID tenantId) {
        List<Map<String, Object>> stores = jdbc.queryForList(ACTIVE_STORE_SQL, tenantId);
        return !stores.isEmpty() && "needs_reauth".equals(String.valueOf(stores.get(0).get("status")));
    }

    /** The idempotency key for attempt n of a claim: the claim key for the original and for
     *  never_sent / ambiguous retries; "claim key + attempt number" after a rejection. */
    public static String retryKey(UUID tenantId, String triggerType, String triggerId, UUID variantId,
                                  UUID locationId, int attemptNo) {
        return ShopifyGateway.idempotencyKey(tenantId, triggerType, triggerId + ":attempt:" + attemptNo,
            variantId, locationId);
    }
}
