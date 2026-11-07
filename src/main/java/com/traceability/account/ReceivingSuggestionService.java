package com.traceability.account;

import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.integrations.shopify.ShopifyVariantStockGateway;
import com.traceability.integrations.shopify.ShopifyVariantStockGateway.VariantStock;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Onboarding O4 — "Start with one product" on the guided first receiving: the store's variant with the MOST
 * Shopify stock that Traced knows (an active product in the Traced catalog). READ ONLY: at most two pages of
 * {@link ShopifyVariantStockGateway} (50 each), no write to Shopify, nothing stored, no order data read. No DB
 * transaction is open during the Shopify call.
 *
 * Answers {@code {suggestion: null}} whenever there is nothing to suggest — no connected store, the read failed
 * (logged WARN with the exception class / Shopify's error codes only), or no variant with stock maps to the
 * catalog — and the screen then just shows the product picker.
 */
@Service
public class ReceivingSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(ReceivingSuggestionService.class);
    static final int PAGE_SIZE = 50;
    static final int MAX_PAGES = 2;

    private final JdbcTemplate jdbc;
    /** Each DB read runs in its own short read-only transaction (RLS: the tenant setting applies inside one);
     *  none is open during a Shopify call. */
    private final TransactionTemplate readTx;
    private final StoreRepository stores;
    private final ShopifyTokenProvider tokens;
    private final ShopifyVariantStockGateway gateway;

    public ReceivingSuggestionService(JdbcTemplate jdbc, PlatformTransactionManager txm, StoreRepository stores, ShopifyTokenProvider tokens,
                                      ShopifyVariantStockGateway gateway) {
        this.jdbc = jdbc;
        this.readTx = new TransactionTemplate(txm);
        this.readTx.setReadOnly(true);
        this.stores = stores;
        this.tokens = tokens;
        this.gateway = gateway;
    }

    public Map<String, Object> suggest() {
        UUID tenantId = TenantContext.require();
        Map<String, Object> none = new HashMap<>();
        none.put("suggestion", null);

        StoreRepository.Store store = readTx.execute(st -> stores.findActiveStoreByTenant(tenantId).orElse(null));
        if (store == null) return none;
        List<String> main = readTx.execute(st -> jdbc.queryForList(
            "SELECT shopify_location_id FROM locations WHERE tenant_id = ? AND is_fulfillment = true " +
            "  AND shopify_sync_status = 'linked' AND shopify_location_id IS NOT NULL", String.class, tenantId));
        String mainGid = main.isEmpty() ? null : main.get(0);

        try {
            String token = tokens.getValidToken(store.id());
            String after = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                ShopifyVariantStockGateway.Page p = gateway.topVariantsByStock(store.shopDomain(), token, mainGid, after, PAGE_SIZE);
                Map<String, Object> hit = readTx.execute(st -> firstKnown(tenantId, p.variants()));
                if (hit != null) return Map.of("suggestion", hit);
                if (!p.hasNextPage() || p.endCursor() == null) break;
                // Sorted by stock, most first: once a page ends at 0 there is nothing with stock after it.
                if (!p.variants().isEmpty() && p.variants().get(p.variants().size() - 1).inventoryQuantity() <= 0) break;
                after = p.endCursor();
            }
        } catch (ShopifyVariantStockGateway.VariantStockReadException e) {
            log.warn("Receiving suggestion: Shopify read failed tenant={} reason={}", tenantId, e.reason());
        } catch (Exception e) {
            log.warn("Receiving suggestion: Shopify read failed tenant={} cause={}", tenantId, e.getClass().getSimpleName());
        }
        return none;
    }

    /** The first variant (Shopify's order, stock > 0) that is an active product in the Traced catalog. */
    private Map<String, Object> firstKnown(UUID tenantId, List<VariantStock> variants) {
        List<VariantStock> withStock = variants.stream().filter(v -> v.inventoryQuantity() > 0).toList();
        if (withStock.isEmpty()) return null;
        String[] gids = withStock.stream().map(VariantStock::variantGid).toArray(String[]::new);
        Map<String, UUID> known = new HashMap<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement(
                "SELECT v.external_id, v.id FROM variants v JOIN products p ON p.id = v.product_id " +
                "WHERE v.tenant_id = ? AND p.status = 'active' AND v.external_id = ANY(?)");
            ps.setObject(1, tenantId);
            Array arr = con.createArrayOf("text", gids);
            ps.setArray(2, arr);
            return ps;
        }, rs -> { known.putIfAbsent(rs.getString(1), (UUID) rs.getObject(2)); });
        for (VariantStock v : withStock) {
            UUID id = known.get(v.variantGid());
            if (id == null) continue;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("variantId", id);
            out.put("shopifyQuantity", v.inventoryQuantity());
            out.put("mainOnHand", v.onHandAtLocation());
            return out;
        }
        return null;
    }
}
