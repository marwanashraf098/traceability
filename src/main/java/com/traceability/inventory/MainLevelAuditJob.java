package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyGateway;
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

import java.util.*;

/**
 * Part D P3 (2026-11-08) — a ONE-OFF, READ-ONLY audit of hidden stock at the Traced Main location: for every variant
 * where Traced holds available pieces, is its inventory level at Main active in Shopify, and if not, how much is
 * sitting on the inactive (hidden) level? Never scheduled, no endpoint.
 *
 * <p>Runs once at startup when {@code TRACED_MAIN_LEVEL_AUDIT_TENANTS} lists tenant ids (comma-separated; any entry
 * that isn't a tenant id → nothing runs). The tenant list is supplied by the operator — no cross-tenant read, no new
 * hatch. Per tenant, under {@code TenantContext.runAs} (app_user + RLS): one read-only DB transaction, then Shopify
 * reads with NO transaction open — {@link ShopifyGateway#auditMainLevels} (inventoryLevels includeInactive), ≤ 10
 * items per request, a pause between requests. Nothing is written to Shopify or to the DB (the only possible DB write
 * is the normal token refresh inside ShopifyTokenProvider — approved). A failed read is counted, never retried.
 *
 * <p>Output: log lines only — counts, SKUs and Traced ids, never customer data:
 * {@code MAIN_LEVEL_AUDIT} (per tenant), {@code MAIN_LEVEL_AUDIT_ROW} (each variant that is not active at Main),
 * {@code MAIN_LEVEL_AUDIT_DONE}. Guarded by MainLevelAuditReadOnlyGuardTest.
 */
@Component
public class MainLevelAuditJob {

    private static final Logger log = LoggerFactory.getLogger(MainLevelAuditJob.class);

    /** The variants an audit looks at: available pieces anywhere, split at / away from the main warehouse. */
    static final String VARIANTS_SQL =
        "SELECT v.id, v.sku, v.shopify_inventory_item_id, " +
        "       COUNT(*) FILTER (WHERE p.current_location_id = ?)                AS traced_available_main, " +
        "       COUNT(*) FILTER (WHERE p.current_location_id IS DISTINCT FROM ?) AS traced_available_other " +
        "FROM pieces p JOIN variants v ON v.id = p.variant_id AND v.tenant_id = p.tenant_id " +
        "WHERE p.tenant_id = ? AND p.status = 'available' AND v.external_id LIKE 'gid://shopify/%' " +
        "GROUP BY v.id, v.sku, v.shopify_inventory_item_id ORDER BY v.sku NULLS LAST, v.id";

    static final String MAIN_SQL =
        "SELECT id, shopify_location_id FROM locations WHERE tenant_id = ? AND is_fulfillment " +
        "  AND shopify_sync_status = 'linked' AND shopify_location_id IS NOT NULL";

    public record Row(String cls, UUID variantId, String sku, long tracedMain, long tracedOther,
                      Integer hiddenAvailable, Integer hiddenOnHand) {}

    public record Report(UUID tenantId, String shop, String skipped, Map<String, Integer> counts,
                         long hiddenAvailable, long hiddenOnHand, List<Row> rows) {}

    static final List<String> CLASSES =
        List.of("active", "inactive", "missing", "unknown", "item_gone", "no_item_id", "read_failed");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate readOnly;
    private final ShopifyGateway shopify;
    private final ShopifyTokenProvider tokens;
    private final StoreRepository stores;
    private final String tenants;
    private final long delayMs;

    public MainLevelAuditJob(JdbcTemplate jdbc, PlatformTransactionManager txm, ShopifyGateway shopify,
                             ShopifyTokenProvider tokens, StoreRepository stores,
                             @Value("${traced.main-level-audit.tenants:}") String tenants,
                             @Value("${traced.main-level-audit.delay-ms:1000}") long delayMs) {
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(txm);
        this.readOnly.setReadOnly(true);
        this.shopify = shopify;
        this.tokens = tokens;
        this.stores = stores;
        this.tenants = tenants == null ? "" : tenants.trim();
        this.delayMs = delayMs;
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
                log.warn("MAIN_LEVEL_AUDIT stopped ({})", e.getClass().getSimpleName());
            }
        }, "main-level-audit");
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
                log.error("MAIN_LEVEL_AUDIT not run — '{}' in TRACED_MAIN_LEVEL_AUDIT_TENANTS is not a tenant id", s.trim());
                return null;
            }
        }
        return out;
    }

    /** Audits exactly these tenants, in order; logs and returns one report each. */
    public List<Report> run(List<UUID> tenantIds) {
        log.info("MAIN_LEVEL_AUDIT start tenants={} (read-only)", tenantIds.size());
        List<Report> out = new ArrayList<>();
        for (UUID tenantId : tenantIds) {
            Report r;
            try {
                r = audit(tenantId);
            } catch (RuntimeException e) {
                r = new Report(tenantId, null, "error:" + e.getClass().getSimpleName(), zero(), 0, 0, List.of());
            }
            for (String line : lines(r)) log.info(line);
            out.add(r);
        }
        log.info("MAIN_LEVEL_AUDIT_DONE tenants={}", out.size());
        return out;
    }

    Report audit(UUID tenantId) {
        record Setup(String shop, String token, String mainGid, List<Map<String, Object>> variants, String skipped) {}
        Setup s = TenantContext.runAs(tenantId, () -> {
            Optional<StoreRepository.Store> store = stores.findActiveStoreByTenant(tenantId);
            if (store.isEmpty() || !"connected".equals(store.get().status())) {
                return new Setup(null, null, null, List.of(), "no_connected_store");
            }
            String shop = store.get().shopDomain();
            if (!ShopifyGateway.isScopeGranted("read_inventory", store.get().accessTokenScopes())) {
                return new Setup(shop, null, null, List.of(), "missing_scope");
            }
            Setup db = readOnly.execute(st -> {
                List<Map<String, Object>> main = jdbc.queryForList(MAIN_SQL, tenantId);
                if (main.isEmpty()) return new Setup(shop, null, null, List.of(), "main_not_linked");
                UUID mainId = (UUID) main.get(0).get("id");
                return new Setup(shop, null, (String) main.get(0).get("shopify_location_id"),
                    jdbc.queryForList(VARIANTS_SQL, mainId, mainId, tenantId), null);
            });
            if (db.skipped() != null) return db;
            return new Setup(shop, tokens.getValidToken(store.get().id()), db.mainGid(), db.variants(), null);
        });
        if (s.skipped() != null) return new Report(tenantId, handle(s.shop()), s.skipped(), zero(), 0, 0, List.of());

        Map<String, Integer> counts = zero();
        List<Row> rows = new ArrayList<>();
        Map<String, Map<String, Object>> byItem = new LinkedHashMap<>();
        for (Map<String, Object> v : s.variants()) {
            String item = (String) v.get("shopify_inventory_item_id");
            if (item == null || item.isBlank()) {
                counts.merge("no_item_id", 1, Integer::sum);
                rows.add(row("no_item_id", v, null, null));
            } else {
                byItem.put(item, v);
            }
        }
        long hiddenAvailable = 0, hiddenOnHand = 0;
        List<String> items = new ArrayList<>(byItem.keySet());
        for (int i = 0; i < items.size(); i += ShopifyGateway.MAIN_LEVEL_AUDIT_BATCH) {
            List<String> batch = items.subList(i, Math.min(items.size(), i + ShopifyGateway.MAIN_LEVEL_AUDIT_BATCH));
            if (i > 0) pause();
            List<ShopifyGateway.MainLevelAudit> answer;
            try {
                answer = shopify.auditMainLevels(s.shop(), s.token(), s.mainGid(), batch);
            } catch (RuntimeException e) {
                log.warn("MAIN_LEVEL_AUDIT tenant={} read failed for {} items ({})", tenantId, batch.size(),
                    e.getClass().getSimpleName());
                for (String item : batch) {
                    counts.merge("read_failed", 1, Integer::sum);
                    rows.add(row("read_failed", byItem.get(item), null, null));
                }
                continue;
            }
            for (ShopifyGateway.MainLevelAudit a : answer) {
                Map<String, Object> v = byItem.get(a.inventoryItemGid());
                if (v == null) continue;
                counts.merge(a.state(), 1, Integer::sum);
                if ("active".equals(a.state())) continue;
                if ("inactive".equals(a.state())) {
                    hiddenAvailable += a.available() == null ? 0 : a.available();
                    hiddenOnHand += a.onHand() == null ? 0 : a.onHand();
                }
                rows.add(row(a.state(), v, a.available(), a.onHand()));
            }
        }
        return new Report(tenantId, handle(s.shop()), null, counts, hiddenAvailable, hiddenOnHand, rows);
    }

    /** The log lines of one report — counts, SKUs and Traced ids only. */
    static List<String> lines(Report r) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder("MAIN_LEVEL_AUDIT tenant=").append(r.tenantId())
            .append(" shop=").append(r.shop() == null ? "-" : r.shop());
        if (r.skipped() != null) {
            out.add(sb.append(" skipped=").append(r.skipped()).toString());
            return out;
        }
        int checked = r.counts().values().stream().mapToInt(Integer::intValue).sum();
        sb.append(" checked=").append(checked);
        for (String c : CLASSES) sb.append(' ').append(c).append('=').append(r.counts().getOrDefault(c, 0));
        sb.append(" hidden_available=").append(r.hiddenAvailable()).append(" hidden_on_hand=").append(r.hiddenOnHand());
        out.add(sb.toString());
        for (Row row : r.rows()) {
            out.add("MAIN_LEVEL_AUDIT_ROW tenant=" + r.tenantId() + " class=" + row.cls()
                + " sku=" + (row.sku() == null || row.sku().isBlank() ? "-" : row.sku().replaceAll("\\s+", "_"))
                + " variant=" + row.variantId()
                + " traced_available_main=" + row.tracedMain() + " traced_available_other=" + row.tracedOther()
                + " shopify_active=" + ("active".equals(row.cls()))
                + " hidden_available=" + (row.hiddenAvailable() == null ? "-" : row.hiddenAvailable())
                + " hidden_on_hand=" + (row.hiddenOnHand() == null ? "-" : row.hiddenOnHand()));
        }
        return out;
    }

    private static Row row(String cls, Map<String, Object> v, Integer available, Integer onHand) {
        boolean hidden = "inactive".equals(cls);
        return new Row(cls, (UUID) v.get("id"), (String) v.get("sku"),
            ((Number) v.get("traced_available_main")).longValue(), ((Number) v.get("traced_available_other")).longValue(),
            hidden ? available : null, hidden ? onHand : null);
    }

    private static Map<String, Integer> zero() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String c : CLASSES) m.put(c, 0);
        return m;
    }

    /** The shop's handle (no ".myshopify.com"). */
    private static String handle(String shop) {
        return shop == null ? null : shop.replaceFirst("\\.myshopify\\.com$", "");
    }

    private void pause() {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
    }
}
