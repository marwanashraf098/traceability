package com.traceability.inventory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyLocationGateway;
import com.traceability.integrations.shopify.ShopifyLocationGateway.LocationAddress;
import com.traceability.integrations.shopify.ShopifyLocationGateway.LocationAddressState;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Feature A (2026-10-25) — the address on the Traced Main Warehouse's Shopify location.
 *
 * <p><b>Fill rule (the ONE definition):</b> Traced writes an address only when the location's
 * current Shopify address is EMPTY (address1, city and provinceCode all blank) or still EXACTLY
 * equals the address Traced last wrote ({@code locations.shopify_address_written}). Anything else
 * is the merchant's → {@code has_address}, never overwritten. When the address to write is the
 * {@link #GENERIC} one and local pickup is on at that location, nothing is written
 * ({@code local_pickup_on} — a generic address would reach customers at checkout).
 *
 * <p><b>Location target:</b> only the tenant's {@code is_fulfillment} location's
 * {@code shopify_location_id} — the Traced Main Warehouse GID — is ever read or edited. The edit
 * sends {@code address} only (never name / fulfillsOnlineOrders) and touches no inventory.
 *
 * <p>No DB transaction is open during a Shopify call. Every outcome is recorded on the location
 * row (V184): filled, has_address, missing_scope, not_linked, failed, local_pickup_on (+ dry_run).
 */
@Service
public class MainLocationAddressService {

    private static final Logger log = LoggerFactory.getLogger(MainLocationAddressService.class);

    /** The generic address (approved by Marawan 2026-10-25). */
    public static final LocationAddress GENERIC =
        new LocationAddress("Main warehouse", null, "Cairo", "C", "11511", "EG");

    /** Lower-case names = the stored values (V184 CHECK), except NOT_TRACED_MAIN which is never stored. */
    public enum Outcome {
        filled, has_address, missing_scope, not_linked, failed, local_pickup_on,
        /** Backfill only: the linked Shopify location isn't named "Traced Main Warehouse" — left alone, not recorded. */
        not_traced_main
    }

    /** {@code wrote} = a locationEdit was sent and accepted in this call. */
    public record Result(Outcome outcome, boolean wrote, boolean dryRun, String detail) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ShopifyLocationGateway gateway;
    private final ShopifyTokenProvider tokenProvider;
    private final StoreRepository storeRepository;
    private final JobScheduler jobScheduler;
    private final ObjectMapper mapper;

    public MainLocationAddressService(JdbcTemplate jdbc, PlatformTransactionManager txm,
                                      ShopifyLocationGateway gateway, ShopifyTokenProvider tokenProvider,
                                      StoreRepository storeRepository, JobScheduler jobScheduler,
                                      ObjectMapper mapper) {
        this.jdbc            = jdbc;
        this.tx              = new TransactionTemplate(txm);
        this.gateway         = gateway;
        this.tokenProvider   = tokenProvider;
        this.storeRepository = storeRepository;
        this.jobScheduler    = jobScheduler;
        this.mapper          = mapper;
    }

    // ── Entry point for feature B ─────────────────────────────────────────────

    /**
     * Sets the address of this tenant's Traced Main Warehouse Shopify location to {@code address},
     * under the fill rule. Runs AFTER the caller's transaction commits (immediately when none is
     * open) as a JobRunr job ({@link MainLocationAddressJob#apply}); a {@code failed} outcome makes
     * the job throw, so JobRunr retries it (the edit is safe to repeat). A rollback means no job.
     * Never blocks or fails the caller. The tenant's location must already be linked to Shopify,
     * else the outcome is {@code not_linked} (no retry) — linking later creates the location with
     * {@link MainLocationAddressSource}'s address.
     */
    public void setMainLocationAddressAfterCommit(UUID tenantId, LocationAddress address) {
        if (tenantId == null || address == null) throw new IllegalArgumentException("tenantId and address are required");
        Runnable enqueue = () -> enqueue(tenantId, address);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { enqueue.run(); }
            });
        } else {
            enqueue.run();
        }
    }

    private void enqueue(UUID tenantId, LocationAddress a) {
        String a1 = a.address1(), a2 = a.address2(), city = a.city(), prov = a.provinceCode(),
               zip = a.zip(), cc = a.countryCode();
        jobScheduler.<MainLocationAddressJob>enqueue(job -> job.apply(tenantId, a1, a2, city, prov, zip, cc));
    }

    // ── The fill rule ─────────────────────────────────────────────────────────

    /**
     * Applies the fill rule to the tenant's main location once. Caller sets the tenant context
     * (TenantContext.runAs) and must hold no open transaction around it.
     *
     * @param desired           the address to write
     * @param dryRun            decide and record the outcome, but never call locationEdit
     * @param requireTracedName skip (not recorded) a location whose Shopify name isn't
     *                          "Traced Main Warehouse" — the backfill's scope rule
     */
    public Result apply(UUID tenantId, LocationAddress desired, boolean dryRun, boolean requireTracedName) {
        UUID ctx = TenantContext.require();
        if (!ctx.equals(tenantId)) throw new IllegalStateException("tenant context mismatch");

        List<Map<String, Object>> rows = tx.execute(s -> jdbc.queryForList(
            "SELECT id, shopify_location_id, shopify_address_written::text AS written " +
            "FROM locations WHERE tenant_id = ? AND is_fulfillment = true LIMIT 1", tenantId));
        if (rows == null || rows.isEmpty()) {
            return new Result(Outcome.not_linked, false, dryRun, "no main warehouse location");
        }
        UUID locationId = (UUID) rows.get(0).get("id");
        String gid      = (String) rows.get(0).get("shopify_location_id");
        LocationAddress written = parse((String) rows.get(0).get("written"));

        if (gid == null) {
            return record(locationId, tenantId, Outcome.not_linked, dryRun, null, null);
        }
        Optional<StoreRepository.Store> store = storeRepository.findActiveStoreByTenant(tenantId);
        if (store.isEmpty()) {
            return record(locationId, tenantId, Outcome.not_linked, dryRun, null, "no connected store");
        }
        if (!ShopifyGateway.isScopeGranted("write_locations", store.get().accessTokenScopes())) {
            return record(locationId, tenantId, Outcome.missing_scope, dryRun, null, null);
        }

        String token;
        Optional<LocationAddressState> state;
        try {
            token = tokenProvider.getValidToken(store.get().id());
            state = gateway.readLocationAddress(store.get().shopDomain(), token, gid);
        } catch (RuntimeException e) {
            return record(locationId, tenantId, Outcome.failed, dryRun, null, "read: " + message(e));
        }
        if (state == null || state.isEmpty()) {
            return record(locationId, tenantId, Outcome.failed, dryRun, null, "location not found in Shopify");
        }
        if (requireTracedName && !ShopifyLocationProvisioningService.SHOPIFY_LOCATION_NAME.equals(state.get().name())) {
            log.info("Location address: tenant {} main location is named '{}' in Shopify, not '{}' — left alone",
                tenantId, state.get().name(), ShopifyLocationProvisioningService.SHOPIFY_LOCATION_NAME);
            return new Result(Outcome.not_traced_main, false, dryRun, state.get().name());
        }

        LocationAddress current = state.get().address();
        if (current.equals(desired)) {
            // Already showing what we want: ours if we wrote it, else the merchant typed the same thing.
            Outcome o = desired.equals(written) ? Outcome.filled : Outcome.has_address;
            return record(locationId, tenantId, o, dryRun, null, null);
        }
        boolean ours = current.isEmpty() || current.equals(written);
        if (!ours) {
            return record(locationId, tenantId, Outcome.has_address, dryRun, null, null);
        }
        if (desired.equals(GENERIC) && state.get().localPickupEnabled()) {
            return record(locationId, tenantId, Outcome.local_pickup_on, dryRun, null, null);
        }
        if (dryRun) {
            return record(locationId, tenantId, Outcome.filled, true, null, null);
        }
        try {
            gateway.editLocationAddress(store.get().shopDomain(), token, gid, desired);
        } catch (RuntimeException e) {
            return record(locationId, tenantId, Outcome.failed, false, null, "edit: " + message(e));
        }
        log.info("Location address filled: tenant {} location {}", tenantId, locationId);
        return record(locationId, tenantId, Outcome.filled, false, desired, null);
    }

    /**
     * Records that Traced just CREATED the location with {@code address} (locationAdd) — so the
     * fill rule later treats it as ours. Joins the caller's transaction / connection.
     */
    void recordCreatedWith(UUID locationId, UUID tenantId, LocationAddress address) {
        jdbc.update(
            "UPDATE locations SET shopify_address_written = ?::jsonb, shopify_address_written_at = now(), " +
            "  shopify_address_outcome = 'filled', shopify_address_dry_run = false, " +
            "  shopify_address_checked_at = now(), shopify_address_error = NULL " +
            "WHERE id = ? AND tenant_id = ?",
            toJson(address), locationId, tenantId);
    }

    private Result record(UUID locationId, UUID tenantId, Outcome outcome, boolean dryRun,
                          LocationAddress wrote, String error) {
        tx.execute(s -> {
            if (wrote != null) {
                jdbc.update(
                    "UPDATE locations SET shopify_address_written = ?::jsonb, shopify_address_written_at = now(), " +
                    "  shopify_address_outcome = ?, shopify_address_dry_run = ?, " +
                    "  shopify_address_checked_at = now(), shopify_address_error = NULL " +
                    "WHERE id = ? AND tenant_id = ?",
                    toJson(wrote), outcome.name(), dryRun, locationId, tenantId);
            } else {
                jdbc.update(
                    "UPDATE locations SET shopify_address_outcome = ?, shopify_address_dry_run = ?, " +
                    "  shopify_address_checked_at = now(), shopify_address_error = ? " +
                    "WHERE id = ? AND tenant_id = ?",
                    outcome.name(), dryRun, outcome == Outcome.failed ? error : null, locationId, tenantId);
            }
            return null;
        });
        if (outcome == Outcome.failed) {
            log.warn("Location address {}: tenant {} location {} — {}", dryRun ? "dry run failed" : "failed",
                tenantId, locationId, error);
        }
        return new Result(outcome, wrote != null, dryRun, error);
    }

    String toJson(LocationAddress a) {
        ObjectNode n = mapper.createObjectNode();
        n.put("address1", a.address1());
        n.put("address2", a.address2());
        n.put("city", a.city());
        n.put("provinceCode", a.provinceCode());
        n.put("zip", a.zip());
        n.put("countryCode", a.countryCode());
        return n.toString();
    }

    private LocationAddress parse(String json) {
        if (json == null) return null;
        try {
            JsonNode n = mapper.readTree(json);
            return new LocationAddress(t(n, "address1"), t(n, "address2"), t(n, "city"),
                t(n, "provinceCode"), t(n, "zip"), t(n, "countryCode"));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String t(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String message(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
