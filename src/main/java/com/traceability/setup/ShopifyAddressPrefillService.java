package com.traceability.setup;

import com.traceability.integrations.shopify.ShopifyLocationListGateway;
import com.traceability.integrations.shopify.ShopifyLocationListGateway.ShopifyLocationAddress;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * Onboarding O2b — the merchant's own Shopify locations, offered to pre-fill the setup's warehouse address. READ ONLY:
 * one Shopify call ({@link ShopifyLocationListGateway}), nothing written anywhere (Shopify or the database), no order
 * data read. Only while the main warehouse has no complete address (SetupStatus.ADDRESS_COMPLETE_SQL) — otherwise
 * no Shopify call at all.
 *
 * Offered: active, non-legacy locations, never Traced's own "Traced Main Warehouse" (by its linked id, and by name
 * for a store whose main warehouse isn't linked yet). Order: locations that fulfil online orders first, then Shopify's
 * own order (Shopify exposes no "most recent activity" for a location). Only the address fields are returned.
 *
 * Any failure (no connected store, the token, a Shopify error or timeout) → an empty list, logged as a WARN with the
 * exception class / Shopify's error codes only. No DB transaction is open during the Shopify call.
 */
@Service
public class ShopifyAddressPrefillService {

    private static final Logger log = LoggerFactory.getLogger(ShopifyAddressPrefillService.class);
    static final String TRACED_LOCATION_NAME = "Traced Main Warehouse";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate readTx;
    private final StoreRepository stores;
    private final ShopifyTokenProvider tokens;
    private final ShopifyLocationListGateway gateway;

    public ShopifyAddressPrefillService(JdbcTemplate jdbc, PlatformTransactionManager txm, StoreRepository stores,
                                        ShopifyTokenProvider tokens, ShopifyLocationListGateway gateway) {
        this.jdbc = jdbc;
        this.readTx = new TransactionTemplate(txm);
        this.readTx.setReadOnly(true);
        this.stores = stores;
        this.tokens = tokens;
        this.gateway = gateway;
    }

    public Map<String, Object> locations() {
        UUID tenantId = TenantContext.require();
        Map<String, Object> none = Map.of("locations", List.of());

        record Ctx(StoreRepository.Store store, String tracedGid, boolean complete) {}
        Ctx ctx = readTx.execute(st -> {
            Boolean complete = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM locations WHERE tenant_id = ? AND is_fulfillment AND "
                    + SetupStatus.ADDRESS_COMPLETE_SQL + ")", Boolean.class, tenantId);
            List<String> gid = jdbc.queryForList(
                "SELECT shopify_location_id FROM locations WHERE tenant_id = ? AND is_fulfillment " +
                "AND shopify_location_id IS NOT NULL", String.class, tenantId);
            return new Ctx(stores.findActiveStoreByTenant(tenantId).orElse(null),
                gid.isEmpty() ? null : gid.get(0), Boolean.TRUE.equals(complete));
        });
        if (ctx == null || ctx.complete() || ctx.store() == null) return none;

        List<ShopifyLocationAddress> all;
        try {
            String token = tokens.getValidToken(ctx.store().id());
            all = gateway.activeLocations(ctx.store().shopDomain(), token);
        } catch (ShopifyLocationListGateway.LocationListReadException e) {
            log.warn("Address prefill: Shopify locations read failed tenant={} reason={}", tenantId, e.reason());
            return none;
        } catch (Exception e) {
            log.warn("Address prefill: Shopify locations read failed tenant={} cause={}", tenantId, e.getClass().getSimpleName());
            return none;
        }

        List<Map<String, Object>> out = new ArrayList<>();
        all.stream()
            .filter(ShopifyLocationAddress::active)
            .filter(l -> !l.id().equals(ctx.tracedGid()))
            .filter(l -> l.name() == null || !TRACED_LOCATION_NAME.equalsIgnoreCase(l.name().trim()))
            .filter(l -> l.address1() != null || l.city() != null || l.provinceCode() != null || l.province() != null)
            .sorted(Comparator.comparing((ShopifyLocationAddress l) -> !l.fulfillsOnlineOrders()))
            .forEach(l -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", l.id());
                m.put("name", l.name());
                m.put("fulfillsOnlineOrders", l.fulfillsOnlineOrders());
                m.put("address1", l.address1());
                m.put("address2", l.address2());
                m.put("city", l.city());
                m.put("province", l.province());
                m.put("provinceCode", l.provinceCode());
                m.put("countryCode", l.countryCode());
                m.put("zip", l.zip());
                m.put("phone", l.phone());
                out.add(m);
            });
        return Map.of("locations", out);
    }
}
