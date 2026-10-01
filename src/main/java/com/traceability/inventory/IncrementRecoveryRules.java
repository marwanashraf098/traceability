package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyGateway;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
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

    public static final List<String> INCREMENT_TRIGGERS = List.of("receiving_session", "return_inspection", "hold_exit");
    public static final String INCREMENT_TRIGGERS_SQL = "('receiving_session', 'return_inspection', 'hold_exit')";

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
        MISSING_SCOPE("Reconnect Shopify to grant inventory access", "أعد ربط Shopify لمنح صلاحية المخزون"),
        LOCATION_NOT_LINKED("Link your warehouse to a Shopify location", "اربط مستودعك بموقع في Shopify");

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
        List<String> scopes = jdbc.queryForList(
            "SELECT access_token_scopes FROM stores WHERE tenant_id = ? AND status <> 'disconnected' " +
            "ORDER BY (status = 'connected') DESC, last_sync_at DESC NULLS LAST LIMIT 1",
            String.class, tenantId);
        if (scopes.isEmpty()) return SetupProblem.NO_STORE;
        String granted = scopes.get(0);
        if (!ShopifyGateway.isScopeGranted("read_products", granted)
                || !ShopifyGateway.isScopeGranted("write_inventory", granted)) {
            return SetupProblem.MISSING_SCOPE;
        }
        Integer linked = jdbc.queryForObject(
            "SELECT COUNT(*) FROM locations WHERE tenant_id = ? AND is_fulfillment = true " +
            "  AND shopify_sync_status = 'linked' AND shopify_location_id IS NOT NULL",
            Integer.class, tenantId);
        if (linked == null || linked == 0) return SetupProblem.LOCATION_NOT_LINKED;
        return null;
    }

    /** The idempotency key for attempt n of a claim: the claim key for the original and for
     *  never_sent / ambiguous retries; "claim key + attempt number" after a rejection. */
    public static String retryKey(UUID tenantId, String triggerType, String triggerId, UUID variantId,
                                  UUID locationId, int attemptNo) {
        return ShopifyGateway.idempotencyKey(tenantId, triggerType, triggerId + ":attempt:" + attemptNo,
            variantId, locationId);
    }
}
