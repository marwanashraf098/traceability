package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyDeliveryProfileGateway;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Legacy shipping (delivery profiles) — is the Traced Main Warehouse in the shop's default ("General") delivery
 * profile? READ ONLY.
 *
 * <p>L1b-2 (approved by Marawan 2026-11-16): Traced NEVER writes a delivery profile — no create / update / remove
 * mutation anywhere (DeliveryProfileMutationGuardTest; Shopify's market-driven shipping compatibility form). The
 * first-link join, Turn back on's join and the manual "Activate fulfillment" are gone: the owner adds the location to
 * the shipping profile in Shopify (instruction + a deep link to the profile whose id this read stores). This read is
 * what legacy readiness needs (TracedShippingReadiness) — first link, reconnect, Turn back on, the hourly check and
 * "Check again" call it, only on a store whose shipping model is confirmed LEGACY.
 */
@Service
public class ShopifyFulfillmentActivationService {

    private static final Logger log = LoggerFactory.getLogger(ShopifyFulfillmentActivationService.class);

    public static final String STATUS_ACTIVATED     = "activated";
    public static final String STATUS_NOT_ACTIVATED = "not_activated";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ShopifyDeliveryProfileGateway deliveryProfiles;
    private final ShopifyTokenProvider tokenProvider;
    private final StoreRepository storeRepository;

    public ShopifyFulfillmentActivationService(JdbcTemplate jdbc, PlatformTransactionManager txm,
                                                ShopifyDeliveryProfileGateway deliveryProfiles,
                                                ShopifyTokenProvider tokenProvider,
                                                StoreRepository storeRepository) {
        this.jdbc             = jdbc;
        this.tx               = new TransactionTemplate(txm);
        this.deliveryProfiles = deliveryProfiles;
        this.tokenProvider    = tokenProvider;
        this.storeRepository  = storeRepository;
    }

    private record Context(UUID storeId, String shopDomain, String scopes, UUID locationId, String locationGid) {}

    /** The connected store and the linked is_fulfillment row, or empty. Short transaction, no Shopify call. */
    private Optional<Context> resolveContext(UUID tenantId) {
        return tx.execute(s -> {
            StoreRepository.Store store = storeRepository.findActiveStoreByTenant(tenantId).orElse(null);
            if (store == null) return Optional.<Context>empty();
            List<Context> rows = jdbc.query(
                "SELECT id, shopify_location_id FROM locations " +
                "WHERE tenant_id = ? AND is_fulfillment = true AND shopify_sync_status = 'linked' " +
                "  AND shopify_location_id IS NOT NULL LIMIT 1",
                (rs, i) -> new Context(store.id(), store.shopDomain(), store.accessTokenScopes(),
                    rs.getObject(1, UUID.class), rs.getString(2)),
                tenantId);
            return rows.isEmpty() ? Optional.<Context>empty() : Optional.of(rows.get(0));
        });
    }

    /**
     * READ ONLY: is the Traced Main Warehouse in the default delivery profile's location group? Records 'activated' /
     * 'not_activated' (activated_at kept, so a location that was in the profile and was taken out reads as removed),
     * shopify_delivery_profile_checked_at and the profile's id (the owner's deep link). No transaction is open during
     * the Shopify call. Never writes to Shopify, never throws; a failed read changes nothing. Empty = unknown.
     */
    public Optional<Boolean> readMembership(UUID tenantId) {
        try {
            Optional<Context> found = resolveContext(tenantId);
            if (found == null || found.isEmpty()) return Optional.empty();
            Context ctx = found.get();
            // No shipping scope (write_shipping implies read_shipping) → Shopify would refuse the read: no call.
            if (!ShopifyGateway.isScopeGranted("read_shipping", ctx.scopes())) return Optional.empty();
            String token = tokenProvider.getValidToken(ctx.storeId());
            Optional<ShopifyDeliveryProfileGateway.LocationGroupInfo> group =
                deliveryProfiles.findDefaultProfileLocationGroup(ctx.shopDomain(), token);
            if (group.isEmpty()) return Optional.empty();
            boolean member = group.get().memberLocationGids().contains(ctx.locationGid());
            String profileId = group.get().deliveryProfileId();
            tx.execute(s -> jdbc.update(
                "UPDATE locations SET shopify_delivery_profile_status = ?, " +
                "  shopify_delivery_profile_error = NULL, shopify_delivery_profile_checked_at = now(), " +
                "  shopify_delivery_profile_id = ?, " +
                "  shopify_delivery_profile_activated_at = CASE WHEN ? THEN COALESCE(shopify_delivery_profile_activated_at, now()) " +
                "                                         ELSE shopify_delivery_profile_activated_at END " +
                "WHERE id = ? AND tenant_id = ?",
                member ? STATUS_ACTIVATED : STATUS_NOT_ACTIVATED, profileId, member, ctx.locationId(), tenantId));
            return Optional.of(member);
        } catch (Exception e) {
            log.warn("Delivery profile read failed: tenant={} error={}", tenantId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
