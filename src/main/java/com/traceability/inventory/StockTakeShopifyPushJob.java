package com.traceability.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.integrations.shopify.ShopifyAmbiguousException;
import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * FR-21 Step 5, Phase B — the live Shopify write-off push.
 *
 * Mandatory transaction shape (same lesson as StockTakeReconciliationService.resolve()'s
 * srt7 fix, one level up): the HTTP call to Shopify runs with NO DB transaction around it.
 * Every DB read/write here is its own short, independently committed
 * TransactionTemplate.execute() call — the claim load, the precondition reads, and the final
 * markResult() write are three separate commits, with the actual push() call to
 * ShopifyGateway sitting in between them, untransacted.
 *
 * Retry rule (non-idempotent decrement): a definitive ShopifyException (Shopify responded,
 * even with rejection) is rethrown so JobRunr's own retry policy retries it — nothing was
 * applied, so retrying is safe. A ShopifyAmbiguousException (no confirmed response) is
 * swallowed after recording 'failed_ambiguous' — NOT rethrown, so JobRunr does not
 * auto-retry an outcome we cannot safely assume didn't already apply.
 */
@Component
public class StockTakeShopifyPushJob {

    private static final Logger log = LoggerFactory.getLogger(StockTakeShopifyPushJob.class);

    private final JdbcTemplate         jdbc;
    private final TransactionTemplate  tx;
    private final ShopifyGateway       shopify;
    private final ShopifyTokenProvider tokenProvider;
    private final ObjectMapper         mapper;

    public StockTakeShopifyPushJob(JdbcTemplate jdbc, PlatformTransactionManager txm,
                                   ShopifyGateway shopify, ShopifyTokenProvider tokenProvider,
                                   ObjectMapper mapper) {
        this.jdbc          = jdbc;
        this.tx            = new TransactionTemplate(txm);
        this.shopify       = shopify;
        this.tokenProvider = tokenProvider;
        this.mapper        = mapper;
    }

    @Job(name = "Stock-take Shopify push — session %0")
    public void push(UUID sessionId, UUID tenantId) {
        TenantContext.runAs(tenantId, () -> {
            // ── committed read #1: load the claim ────────────────────────────
            ClaimRow claim = tx.execute(s -> loadClaim(sessionId, tenantId));
            if (claim == null) {
                log.warn("stock-take push: no claim row session={} tenant={}", sessionId, tenantId);
                return;
            }
            if ("pushed".equals(claim.status()) || "failed_ambiguous".equals(claim.status())) {
                // 'pushed' is terminal (success). 'failed_ambiguous' requires a HUMAN to
                // verify against Shopify before anything re-pushes — a JobRunr auto-retry
                // must never silently re-attempt it. 'pending' (first attempt) and 'failed'
                // (a definitive rejection JobRunr is retrying) both fall through and proceed.
                log.debug("stock-take push: claim already resolved status={} session={} — no-op",
                    claim.status(), sessionId);
                return;
            }
            if (claim.deltasByVariant().isEmpty()) {
                // Nothing to push — Phase A already handles the zero-delta case by marking
                // the claim 'pushed' directly and never enqueueing this job, but guard here
                // too in case of a stray/duplicate enqueue.
                markResult(sessionId, tenantId, "pushed", null);
                return;
            }

            // ── committed read #2: resolve preconditions ─────────────────────
            Preconditions p = tx.execute(s -> resolvePreconditions(tenantId, claim));

            if (p.error() != null) {
                markResult(sessionId, tenantId, "failed", p.error());
                // Definitive — nothing was ever sent to Shopify. Safe to retry.
                throw new IllegalStateException("stock-take push precondition failed: " + p.error());
            }

            // ── THE HTTP CALL — no DB transaction around this ────────────────
            String referenceDocumentUri = "traced://stock-take/" + sessionId;
            String idempotencyKey = ShopifyGateway.idempotencyKey(
                tenantId, "stock_take_finalize", sessionId.toString(), null, p.locationGid());

            try {
                shopify.pushStockTakeWriteOff(p.shopDomain(), p.token(), p.deltas(),
                    p.locationGid(), referenceDocumentUri, idempotencyKey);
                markResult(sessionId, tenantId, "pushed", null);
            } catch (ShopifyAmbiguousException e) {
                markResult(sessionId, tenantId, "failed_ambiguous", e.getMessage());
                log.error("stock-take push AMBIGUOUS session={} referenceDocumentUri={} — " +
                    "manual verification required, NOT auto-retrying: {}",
                    sessionId, referenceDocumentUri, e.getMessage());
                // Deliberately no rethrow — see class doc.
            } catch (ShopifyException e) {
                markResult(sessionId, tenantId, "failed", e.getMessage());
                throw e; // definitive — safe for JobRunr to retry.
            }
        });
    }

    // ── committed reads ───────────────────────────────────────────────────────

    record ClaimRow(String status, UUID locationId, Map<UUID, Integer> deltasByVariant) {}

    private ClaimRow loadClaim(UUID sessionId, UUID tenantId) {
        return jdbc.query(
            "SELECT status, payload FROM stock_take_shopify_syncs WHERE session_id = ? AND tenant_id = ?",
            rs -> {
                if (!rs.next()) return null;
                String status = rs.getString("status");
                String payloadJson = rs.getString("payload");
                UUID locationId = null;
                Map<UUID, Integer> deltas = new LinkedHashMap<>();
                try {
                    JsonNode payload = mapper.readTree(payloadJson);
                    String locStr = payload.path("locationId").asText(null);
                    if (locStr != null) locationId = UUID.fromString(locStr);
                    Iterator<Map.Entry<String, JsonNode>> fields = payload.path("deltas").fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> field = fields.next();
                        deltas.put(UUID.fromString(field.getKey()), field.getValue().asInt());
                    }
                } catch (Exception e) {
                    log.error("stock-take push: failed to parse claim payload session={}", sessionId, e);
                }
                return new ClaimRow(status, locationId, deltas);
            },
            sessionId, tenantId);
    }

    /**
     * Everything needed to call Shopify, or the combined error if any precondition failed.
     * locationGid is read off the SAME location row validated by the linked-status check —
     * the location-target invariant (FR-17 v2) applies here too: no path resolves one
     * location's eligibility and uses a different location's GID.
     */
    private record Preconditions(String shopDomain, String token, String locationGid,
                                 List<ShopifyGateway.InventoryDelta> deltas, String error) {}

    private Preconditions resolvePreconditions(UUID tenantId, ClaimRow claim) {
        Map<String, Object> locRow = jdbc.query(
            "SELECT shopify_location_id, shopify_sync_status FROM locations WHERE id = ? AND tenant_id = ?",
            rs -> rs.next() ? Map.of(
                "shopify_location_id", rs.getString(1) != null ? rs.getString(1) : "",
                "shopify_sync_status",  rs.getString(2) != null ? rs.getString(2) : "") : null,
            claim.locationId(), tenantId);

        if (locRow == null) {
            return err("Location not found: " + claim.locationId());
        }
        if (!"linked".equals(locRow.get("shopify_sync_status"))) {
            return err("Location not linked to Shopify (status=" + locRow.get("shopify_sync_status") + ")");
        }
        String locationGid = (String) locRow.get("shopify_location_id");

        record StoreSnap(UUID id, String shopDomain, String grantedScopes, String connectionType) {}
        // ORDER BY last_sync_at DESC NULLS LAST — same fix as Step 0.5's resolvePreconditions();
        // a tenant is schema-legal to have more than one stores row, so a bare LIMIT 1 would be
        // nondeterministic. This is a new call site — built correctly from the start.
        StoreSnap store = jdbc.query(
            "SELECT id, shop_domain, access_token_scopes, connection_type FROM stores " +
            "WHERE tenant_id = ? ORDER BY last_sync_at DESC NULLS LAST LIMIT 1",
            rs -> rs.next() ? new StoreSnap(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)) : null,
            tenantId);

        if (store == null) {
            return err("No store found for tenant");
        }
        if (!ShopifyGateway.isScopeGranted("write_inventory", store.grantedScopes())) {
            return err(ShopifyGateway.scopeGrantMessage(
                store.connectionType(), "write_inventory", store.grantedScopes()));
        }

        String token = tokenProvider.getValidToken(store.id());

        List<ShopifyGateway.InventoryDelta> deltas = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : claim.deltasByVariant().entrySet()) {
            String variantGid = jdbc.query(
                "SELECT external_id FROM variants WHERE id = ? AND tenant_id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                entry.getKey(), tenantId);
            if (variantGid == null || variantGid.isBlank()) {
                return err("Variant has no Shopify GID: " + entry.getKey());
            }
            String inventoryItemGid;
            try {
                inventoryItemGid = shopify.resolveInventoryItemId(store.shopDomain(), token, variantGid);
            } catch (ShopifyException e) {
                return err("Failed to resolve inventoryItem for variant " + entry.getKey() + ": " + e.getMessage());
            }
            // Negative delta: the count of pieces this session wrote off for this variant.
            deltas.add(new ShopifyGateway.InventoryDelta(inventoryItemGid, -entry.getValue()));
        }

        return new Preconditions(store.shopDomain(), token, locationGid, deltas, null);
    }

    private Preconditions err(String message) {
        return new Preconditions(null, null, null, null, message);
    }

    // ── committed write ───────────────────────────────────────────────────────

    private void markResult(UUID sessionId, UUID tenantId, String status, String error) {
        tx.execute(s -> {
            jdbc.update(
                "UPDATE stock_take_shopify_syncs SET status = ?, error = ?, " +
                "  pushed_at = CASE WHEN ? = 'pushed' THEN now() ELSE pushed_at END " +
                "WHERE session_id = ? AND tenant_id = ?",
                status, error, status, sessionId, tenantId);
            return null;
        });
        if (!"pushed".equals(status)) {
            log.warn("stock-take push recorded as {}: session={} error={}", status, sessionId, error);
        }
    }
}
