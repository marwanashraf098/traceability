package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyLocationGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The periodic check of the Traced location in Shopify (2026-10-30, BROEK). One read-only call —
 * location(id) { isActive } on the main warehouse's linked GID — records 'active' / 'inactive' / 'deleted'
 * (ShopifyLocationState). It is the ONLY path back to 'active': when the location was inactive / deleted and
 * Shopify now says active, the updates held meanwhile are released ONCE — failed increments made due now
 * (ShopifyInventoryService.releaseHeldIncrements, then one retry pass), held stock-take pushes released and
 * enqueued, held exchange dispatches sent, and the piece / transfer sweeps run. Each sender keeps its own
 * one-sender guard, so a release overlapping the regular jobs sends nothing twice.
 *
 * Nothing changes when the state can't be read (no linked main warehouse, the store needs a reconnect, a
 * Shopify / transport failure). Runs under the caller's tenant context (TenantContext.runAs).
 */
@Service
public class ShopifyLocationCheckService {

    private static final Logger log = LoggerFactory.getLogger(ShopifyLocationCheckService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StoreRepository storeRepository;
    private final ShopifyTokenProvider tokenProvider;
    private final ShopifyLocationGateway locations;
    private final ShopifyInventoryService inventory;
    private final StockTakeShopifyPushJob stockTakePush;
    private final TransferShopifySync transfers;
    private final JobScheduler jobScheduler;

    public ShopifyLocationCheckService(JdbcTemplate jdbc, PlatformTransactionManager txm, StoreRepository storeRepository,
                                       ShopifyTokenProvider tokenProvider, ShopifyLocationGateway locations,
                                       ShopifyInventoryService inventory, StockTakeShopifyPushJob stockTakePush,
                                       TransferShopifySync transfers, JobScheduler jobScheduler) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.storeRepository = storeRepository;
        this.tokenProvider = tokenProvider;
        this.locations = locations;
        this.inventory = inventory;
        this.stockTakePush = stockTakePush;
        this.transfers = transfers;
        this.jobScheduler = jobScheduler;
    }

    /** The state after the check ('active' / 'inactive' / 'deleted'), or empty when it couldn't be read. */
    public Optional<String> check(UUID tenantId) {
        Map<String, Object> main = tx.execute(st -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, shopify_location_id FROM locations WHERE tenant_id = ? AND is_fulfillment = true " +
                "  AND shopify_sync_status = 'linked' AND shopify_location_id IS NOT NULL", tenantId);
            return rows.isEmpty() ? null : rows.get(0);
        });
        if (main == null) return Optional.empty();
        if (Boolean.TRUE.equals(tx.execute(st -> IncrementRecoveryRules.connectionLost(jdbc, tenantId)))) return Optional.empty();
        StoreRepository.Store store = storeRepository.findActiveStoreByTenant(tenantId).orElse(null);
        if (store == null) return Optional.empty();

        Optional<Boolean> active;
        try {
            String token = tokenProvider.getValidToken(store.id());
            active = locations.readLocationActive(store.shopDomain(), token, (String) main.get("shopify_location_id"));
        } catch (Exception e) {
            log.warn("Traced location check: couldn't read the location in Shopify tenant={}: {}", tenantId, e.getMessage());
            return Optional.empty();
        }
        String state = active.isEmpty() ? ShopifyLocationState.DELETED
            : active.get() ? ShopifyLocationState.ACTIVE : ShopifyLocationState.INACTIVE;
        UUID locationId = (UUID) main.get("id");
        String previous = tx.execute(st -> ShopifyLocationState.record(jdbc, tenantId, locationId, state));
        if (previous != null) {
            log.info("Traced location in Shopify is now {} (was {}) tenant={}", state, previous, tenantId);
            if (ShopifyLocationState.ACTIVE.equals(state)) release(tenantId);
        }
        return Optional.of(state);
    }

    /** Sends what was held while the location wasn't active — once; every sender re-checks the hold itself. */
    private void release(UUID tenantId) {
        int increments = inventory.releaseHeldIncrements(tenantId);
        inventory.retryDueIncrements();
        List<UUID> sessions = stockTakePush.releaseHeld(tenantId);
        for (UUID sessionId : sessions) {
            jobScheduler.enqueue(() -> stockTakePush.push(sessionId, tenantId));
        }
        int exchanges = inventory.resumeHeldExchangeDispatches(tenantId);
        int pieces = inventory.releaseHeldPieceClaims(tenantId) + inventory.sweepPieceClaims(tenantId);
        int transferCount = transfers.sweep(tenantId);
        log.info("Traced location reactivated — released held updates tenant={} increments={} stockTakePushes={} " +
            "exchangeDispatches={} pieceClaims={} transfers={}", tenantId, increments, sessions.size(), exchanges,
            pieces, transferCount);
    }

    /** For the job: runs {@link #check} for one tenant under its context, never throwing. */
    void checkQuietly(UUID tenantId) {
        try {
            TenantContext.runAs(tenantId, () -> check(tenantId));
        } catch (Exception e) {
            log.warn("Traced location check failed tenant={}: {}", tenantId, e.getMessage());
        }
    }
}
