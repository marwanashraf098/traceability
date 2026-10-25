package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Feature A (2026-10-25) — one-time address backfill for the Traced Main Warehouse Shopify
 * location of every connected store (locations created before 2026-10-25 carry only
 * countryCode EG). Modelled on CatalogBackfillJob: the eligible tenant list is ONE read on the
 * owner pool (approved; no new hatch), released before any Shopify call; each tenant then runs
 * inside TenantContext.runAs on the app_user pool (RLS) through
 * {@link MainLocationAddressService#apply} — the ONE fill rule — with the GENERIC address, no DB
 * transaction open during the Shopify read / edit.
 *
 * <p>Scope: the tenant's is_fulfillment location only, and only when Shopify names it
 * "Traced Main Warehouse" (any other name → left alone, not recorded, counted as
 * not_traced_main). <b>Dry run is the default</b> (traced.location-address-backfill.mode): it reads
 * every location and records the outcome it WOULD have ({@code shopify_address_dry_run = true}),
 * but never calls locationEdit.
 *
 * <p>Eligible = a tenant with a connected store whose main location has no FINAL write-mode
 * outcome yet (filled / has_address / local_pickup_on with dry_run false). One tenant failing never
 * stops the rest; any {@code failed} → the run throws at the end so JobRunr retries.
 */
@Component
public class LocationAddressBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(LocationAddressBackfillJob.class);

    static final String ELIGIBLE_TENANTS =
        "SELECT DISTINCT s.tenant_id FROM stores s " +
        "WHERE s.status = 'connected' " +
        "  AND NOT EXISTS (SELECT 1 FROM locations l WHERE l.tenant_id = s.tenant_id AND l.is_fulfillment = true " +
        "                    AND l.shopify_address_dry_run = false " +
        "                    AND l.shopify_address_outcome IN ('filled', 'has_address', 'local_pickup_on')) " +
        "ORDER BY s.tenant_id";

    /** Deterministic JobRunr id per Cairo day and mode — repeated starts on one day collapse into one job. */
    public static UUID jobIdFor(LocalDate cairoDay, boolean dryRun) {
        return UUID.nameUUIDFromBytes(("location-address-backfill-" + (dryRun ? "dry-run-" : "write-") + cairoDay)
            .getBytes(StandardCharsets.UTF_8));
    }

    public record Result(int tenants, Map<MainLocationAddressService.Outcome, Integer> counts, List<UUID> failed) {}

    private final JdbcTemplate ownerJdbc;
    private final MainLocationAddressService service;

    public LocationAddressBackfillJob(@FlywayDataSource DataSource ownerDs, MainLocationAddressService service) {
        this.ownerJdbc = new JdbcTemplate(ownerDs);
        this.service   = service;
    }

    @Job(name = "Shopify location address backfill", retries = 3)
    public void run(boolean dryRun) {
        Result r = runOnce(dryRun);
        if (!r.failed().isEmpty()) {
            throw new IllegalStateException("Location address backfill" + (dryRun ? " (dry run)" : "") + ": "
                + r.failed().size() + " of " + r.tenants() + " tenant(s) failed: " + r.failed() + " — JobRunr will retry");
        }
    }

    /** One pass. Package-visible for tests; run() is the JobRunr entry. */
    Result runOnce(boolean dryRun) {
        List<UUID> tenants = ownerJdbc.queryForList(ELIGIBLE_TENANTS, UUID.class);
        log.info("Location address backfill{}: {} eligible tenant(s)", dryRun ? " (DRY RUN)" : "", tenants.size());
        Map<MainLocationAddressService.Outcome, Integer> counts = new EnumMap<>(MainLocationAddressService.Outcome.class);
        List<UUID> failed = new ArrayList<>();
        for (UUID tenantId : tenants) {
            MainLocationAddressService.Outcome outcome;
            try {
                outcome = TenantContext.runAs(tenantId,
                    () -> service.apply(tenantId, MainLocationAddressService.GENERIC, dryRun, true)).outcome();
            } catch (Exception e) {
                outcome = MainLocationAddressService.Outcome.failed;
                log.warn("Location address backfill: tenant {} errored: {}", tenantId,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
            counts.merge(outcome, 1, Integer::sum);
            if (outcome == MainLocationAddressService.Outcome.failed) failed.add(tenantId);
        }
        log.info("Location address backfill{} done: {} tenant(s), outcomes {}", dryRun ? " (DRY RUN)" : "",
            tenants.size(), counts);
        return new Result(tenants.size(), counts, failed);
    }
}
