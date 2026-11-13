package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyDeliveryProfileGateway;
import com.traceability.integrations.shopify.ShopifyLocationGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * L1 (2026-11-12) — a ONE-OFF, READ-ONLY audit of the Traced Main Warehouse's online-selling settings for existing
 * tenants: is the location active in Shopify, does it fulfil online orders, is it in the default delivery profile's
 * location group? Never scheduled, no endpoint, NO auto-change (Marawan's decision: existing tenants are flagged,
 * never flipped).
 *
 * <p>Runs once at startup when {@code TRACED_LOCATION_SETTINGS_AUDIT_TENANTS} lists tenant ids (comma-separated; any
 * entry that isn't a tenant id → nothing runs). Per tenant, under {@code TenantContext.runAs} (app_user + RLS): one
 * read-only DB transaction, then two Shopify READS with no transaction open
 * ({@link ShopifyLocationGateway#readOnlineFulfillment}, {@link ShopifyDeliveryProfileGateway#findDefaultProfileLocationGroup}).
 * Nothing is written to Shopify or to the DB (the only possible DB write is the normal token refresh inside
 * ShopifyTokenProvider). A failed read is reported, never retried.
 *
 * <p>Output: log lines only, no customer data — {@code LOCATION_SETTINGS_AUDIT} per tenant and
 * {@code LOCATION_SETTINGS_AUDIT_DONE}. Guarded by LocationSettingsAuditReadOnlyGuardTest.
 */
@Component
public class LocationSettingsAuditJob {

    private static final Logger log = LoggerFactory.getLogger(LocationSettingsAuditJob.class);

    static final String MAIN_SQL =
        "SELECT shopify_location_id, shopify_delivery_profile_status, shopify_online_fulfillment_status FROM locations " +
        "WHERE tenant_id = ? AND is_fulfillment AND shopify_sync_status = 'linked' AND shopify_location_id IS NOT NULL";

    /** One tenant's answer. Values: "true" / "false" / "unknown" / a short failure reason. */
    public record Report(UUID tenantId, String shop, String skipped, String active, String fulfillsOnline,
                         String inDefaultProfile, String tracedProfileStatus, String tracedOnlineStatus) {
        boolean flagged() {
            return skipped == null && ("false".equals(active) || "false".equals(fulfillsOnline)
                || "false".equals(inDefaultProfile));
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate readOnly;
    private final ShopifyLocationGateway locations;
    private final ShopifyDeliveryProfileGateway profiles;
    private final ShopifyTokenProvider tokens;
    private final StoreRepository stores;
    private final String tenants;

    public LocationSettingsAuditJob(JdbcTemplate jdbc, PlatformTransactionManager txm,
                                    ShopifyLocationGateway locations, ShopifyDeliveryProfileGateway profiles,
                                    ShopifyTokenProvider tokens, StoreRepository stores,
                                    @Value("${traced.location-settings-audit.tenants:}") String tenants) {
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(txm);
        this.readOnly.setReadOnly(true);
        this.locations = locations;
        this.profiles = profiles;
        this.tokens = tokens;
        this.stores = stores;
        this.tenants = tenants == null ? "" : tenants.trim();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (tenants.isEmpty()) return;
        List<UUID> ids = parse(tenants);
        if (ids == null) return;
        Thread t = new Thread(() -> {
            try {
                run(ids);
            } catch (RuntimeException e) {
                log.warn("LOCATION_SETTINGS_AUDIT stopped ({})", e.getClass().getSimpleName());
            }
        }, "location-settings-audit");
        t.setDaemon(true);
        t.start();
    }

    /** The listed tenant ids, or null (logged) when any entry isn't a tenant id — then nothing runs. */
    static List<UUID> parse(String csv) {
        List<UUID> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            if (s.isBlank()) continue;
            try {
                UUID id = UUID.fromString(s.trim());
                if (!out.contains(id)) out.add(id);
            } catch (IllegalArgumentException e) {
                log.error("LOCATION_SETTINGS_AUDIT not run — '{}' in TRACED_LOCATION_SETTINGS_AUDIT_TENANTS is not a tenant id",
                    s.trim());
                return null;
            }
        }
        return out;
    }

    /** Audits exactly these tenants, in order; logs and returns one report each. */
    public List<Report> run(List<UUID> tenantIds) {
        log.info("LOCATION_SETTINGS_AUDIT start tenants={} (read-only)", tenantIds.size());
        List<Report> out = new ArrayList<>();
        int flagged = 0;
        for (UUID tenantId : tenantIds) {
            Report r;
            try {
                r = audit(tenantId);
            } catch (RuntimeException e) {
                r = new Report(tenantId, null, "error:" + e.getClass().getSimpleName(), null, null, null, null, null);
            }
            if (r.flagged()) flagged++;
            log.info(line(r));
            out.add(r);
        }
        log.info("LOCATION_SETTINGS_AUDIT_DONE tenants={} flagged={}", out.size(), flagged);
        return out;
    }

    static String line(Report r) {
        return "LOCATION_SETTINGS_AUDIT tenant=" + r.tenantId() + " shop=" + handle(r.shop())
            + (r.skipped() != null ? " skipped=" + r.skipped()
               : " active=" + r.active() + " fulfills_online=" + r.fulfillsOnline()
                 + " in_default_profile=" + r.inDefaultProfile()
                 + " traced_profile_status=" + r.tracedProfileStatus()
                 + " traced_online_status=" + r.tracedOnlineStatus()
                 + " flagged=" + r.flagged());
    }

    private static String handle(String shop) {
        if (shop == null) return "-";
        return shop.endsWith(".myshopify.com") ? shop.substring(0, shop.length() - ".myshopify.com".length()) : shop;
    }

    Report audit(UUID tenantId) {
        record Setup(UUID storeId, String shop, String gid, String profileStatus, String onlineStatus, String skipped) {}
        Setup s = TenantContext.runAs(tenantId, () -> readOnly.execute(st -> {
            Optional<StoreRepository.Store> store = stores.findActiveStoreByTenant(tenantId);
            if (store.isEmpty() || !"connected".equals(store.get().status())) {
                return new Setup(null, null, null, null, null, "no_connected_store");
            }
            List<Map<String, Object>> rows = jdbc.queryForList(MAIN_SQL, tenantId);
            if (rows.isEmpty()) {
                return new Setup(store.get().id(), store.get().shopDomain(), null, null, null, "main_not_linked");
            }
            Map<String, Object> m = rows.get(0);
            return new Setup(store.get().id(), store.get().shopDomain(), (String) m.get("shopify_location_id"),
                (String) m.get("shopify_delivery_profile_status"), (String) m.get("shopify_online_fulfillment_status"), null);
        }));
        if (s.skipped() != null) {
            return new Report(tenantId, s.shop(), s.skipped(), null, null, null, null, null);
        }
        String token;
        try {
            token = TenantContext.runAs(tenantId, () -> tokens.getValidToken(s.storeId()));
        } catch (RuntimeException e) {
            return new Report(tenantId, s.shop(), "token:" + e.getClass().getSimpleName(), null, null, null, null, null);
        }
        String active;
        String online;
        try {
            Optional<ShopifyLocationGateway.OnlineFulfillment> o = locations.readOnlineFulfillment(s.shop(), token, s.gid());
            active = o.map(x -> String.valueOf(x.active())).orElse("location_gone");
            online = o.map(x -> String.valueOf(x.fulfillsOnlineOrders())).orElse("location_gone");
        } catch (RuntimeException e) {
            active = online = "read_failed:" + e.getClass().getSimpleName();
        }
        String inProfile;
        try {
            inProfile = profiles.findDefaultProfileLocationGroup(s.shop(), token)
                .map(g -> String.valueOf(g.memberLocationGids().contains(s.gid())))
                .orElse("no_default_profile");
        } catch (RuntimeException e) {
            inProfile = "read_failed:" + e.getClass().getSimpleName();
        }
        return new Report(tenantId, s.shop(), null, active, online, inProfile, s.profileStatus(), s.onlineStatus());
    }
}
