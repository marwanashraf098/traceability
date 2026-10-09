package com.traceability.analytics;

import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

/**
 * Analytics slice 10 — one READ pass of a tenant's Shopify cost and stock into the read-only
 * columns (V157). Runs under the tenant (app_user + RLS; callers wrap it in TenantContext.runAs).
 *
 *   probe   → which fields Shopify allows (ShopifyInventoryReader.probe); a denied / failing field is
 *             'access_denied' / 'error' on analytics_inventory_sync and is not read — costed stays 0,
 *             the stock trust keeps its previous source;
 *   read    → bulk export, else paged;
 *   cost    → EGP only (another currency: shopify_cost_flag 'non_egp:<code>', nothing stored);
 *             cost_source 'manual' — or any cost without a source — is NEVER overwritten;
 *   stock   → variant_shopify_levels per location, stock_available_shopify (all locations) and
 *             stock_available_shopify_traced (the Traced Main Warehouse location).
 *
 * NEVER throws: every failure ends as a status + last_error and one log line per run, so it can't
 * break another job; no retry loop (the next daily pass or the owner's "run now" is the retry).
 */
@Service
public class AnalyticsInventorySyncService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsInventorySyncService.class);

    static final String EGP = "EGP";

    public record Status(UUID tenantId, Instant requestedAt, Instant startedAt, Instant finishedAt, String trigger,
                         String mode, long variantsSeen, long costWritten, long costKeptManual, long costNonEgp,
                         long stockWritten, String costStatus, String stockStatus, String shopCurrency,
                         String lastError, long variantsTotal, long variantsCosted, long variantsStockSynced,
                         Instant nextRunAllowedAt) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ShopifyInventoryReader reader;
    private final ShopifyTokenProvider tokens;

    public AnalyticsInventorySyncService(JdbcTemplate jdbc, PlatformTransactionManager txm, ShopifyInventoryReader reader,
                                         ShopifyTokenProvider tokens) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.reader = reader;
        this.tokens = tokens;
    }

    /** One pass for the current tenant. Never throws. */
    public void run(String trigger) {
        UUID tid = TenantContext.require();
        long started = System.nanoTime();
        Outcome out = new Outcome();
        try {
            runInner(tid, trigger, out);
        } finally {
            try {
                Long costed = tx.execute(s -> jdbc.queryForObject(
                    "SELECT COUNT(*) FROM variants WHERE tenant_id = ? AND unit_cost IS NOT NULL", Long.class, tid));
                out.costed = costed == null ? 0 : costed;
            } catch (RuntimeException ignored) {
                // the count is for the log line only
            }
            log.info("Inventory sync {} for {}: cost {}, stock {}, {} variants read ({}), {} costed, {} non-EGP, {} ms{}",
                trigger, out.shop == null ? tid : out.shop, out.costStatus, out.stockStatus, out.variantsRead,
                out.mode == null ? "nothing read" : out.mode, out.costed, out.nonEgp,
                (System.nanoTime() - started) / 1_000_000, out.error == null ? "" : " — " + out.error);
        }
    }

    /** What one pass did, for its single log line. */
    static final class Outcome {
        String shop, mode, error, costStatus = "never", stockStatus = "never";
        long variantsRead, costed, nonEgp;
    }

    private void runInner(UUID tid, String trigger, Outcome out) {
        String shop = null;
        String costStatus = "error", stockStatus = "error";
        try {
            Map<String, Object> store = tx.execute(s -> jdbc.query(
                "SELECT id, shop_domain FROM stores WHERE tenant_id = ? AND status = 'connected' ORDER BY id LIMIT 1",
                rs -> rs.next() ? Map.<String, Object>of("id", rs.getObject("id", UUID.class), "shop", rs.getString("shop_domain")) : null,
                tid));
            if (store == null) {
                out.error = "no connected Shopify store";
                finish(tid, trigger, null, 0, 0, 0, 0, 0, "never", "never", null, out.error);
                return;
            }
            shop = (String) store.get("shop");
            out.shop = shop;
            tx.executeWithoutResult(s -> jdbc.update(
                "INSERT INTO analytics_inventory_sync (tenant_id, started_at, trigger_kind, updated_at) VALUES (?, now(), ?, now()) " +
                "ON CONFLICT (tenant_id) DO UPDATE SET started_at = now(), trigger_kind = EXCLUDED.trigger_kind, updated_at = now()",
                tid, trigger));
            String token = tokens.getValidToken((UUID) store.get("id"));

            ShopifyInventoryReader.Probe probe = reader.probe(shop, token);
            costStatus = status(probe.cost());
            stockStatus = status(probe.stock());
            out.costStatus = costStatus;
            out.stockStatus = stockStatus;
            out.error = probe.error();
            if (probe.cost() != ShopifyInventoryReader.FieldStatus.OK && probe.stock() != ShopifyInventoryReader.FieldStatus.OK) {
                finish(tid, trigger, null, 0, 0, 0, 0, 0, costStatus, stockStatus, probe.currency(), probe.error());
                return;
            }
            boolean readCost = probe.cost() == ShopifyInventoryReader.FieldStatus.OK;
            boolean readStock = probe.stock() == ShopifyInventoryReader.FieldStatus.OK;
            ShopifyInventoryReader.Read read = reader.read(shop, token, readCost, readStock);
            String currency = probe.currency();
            for (ShopifyInventoryReader.Item it : read.items()) if (currency == null && it.currency() != null) currency = it.currency();
            final String shopCurrency = currency;
            long[] counts = tx.execute(s -> apply(tid, read.items(), readCost, readStock));
            finish(tid, trigger, read.mode(), read.items().size(), counts[0], counts[1], counts[2], counts[3],
                costStatus, stockStatus, shopCurrency, probe.error());
            out.mode = read.mode();
            out.variantsRead = read.items().size();
            out.nonEgp = counts[2];
        } catch (Throwable e) {
            String msg = ShopifyInventoryReader.message(e);
            out.error = msg;
            String cs = "ok".equals(costStatus) || "access_denied".equals(costStatus) ? costStatus : "error";
            String ss = "ok".equals(stockStatus) || "access_denied".equals(stockStatus) ? stockStatus : "error";
            out.costStatus = cs;
            out.stockStatus = ss;
            try {
                finish(tid, trigger, null, 0, 0, 0, 0, 0, cs, ss, null, msg);
            } catch (Throwable ignored) {
                // the status write itself failed — nothing more to do; never throw into the caller
            }
        }
    }

    static String status(ShopifyInventoryReader.FieldStatus s) {
        return switch (s) {
            case OK -> "ok";
            case ACCESS_DENIED -> "access_denied";
            case ERROR -> "error";
        };
    }

    /** Writes what was read. Returns {cost written, cost kept manual, cost non-EGP, stock written}. */
    long[] apply(UUID tid, List<ShopifyInventoryReader.Item> items, boolean readCost, boolean readStock) {
        Map<String, Object[]> variants = new HashMap<>();       // numeric variant id → {id, unit_cost, cost_source}
        jdbc.query("SELECT id, external_id, unit_cost, cost_source FROM variants WHERE tenant_id = ?", rs -> {
            String num = ShopifyInventoryReader.numeric(rs.getString("external_id"));
            if (num != null) variants.put(num, new Object[] {rs.getObject("id", UUID.class), rs.getBigDecimal("unit_cost"), rs.getString("cost_source")});
        }, tid);
        String traced = ShopifyInventoryReader.numeric(jdbc.query(
            "SELECT shopify_location_id FROM locations WHERE tenant_id = ? AND is_fulfillment = true LIMIT 1",
            rs -> rs.next() ? rs.getString(1) : null, tid));
        long written = 0, manual = 0, nonEgp = 0, stock = 0;
        List<Object[]> costRows = new ArrayList<>(), flagRows = new ArrayList<>(), stockRows = new ArrayList<>(), levelRows = new ArrayList<>();
        List<Object[]> levelDeletes = new ArrayList<>(), itemRows = new ArrayList<>();
        for (ShopifyInventoryReader.Item it : items) {
            Object[] v = variants.get(it.variantId());
            if (v == null) continue;
            UUID vid = (UUID) v[0];
            if (it.inventoryItemId() != null) itemRows.add(new Object[] {it.inventoryItemId(), vid, it.inventoryItemId()});
            if (readCost) {
                boolean isManual = v[1] != null && !"shopify".equals(v[2]);
                if (it.currency() != null && !EGP.equalsIgnoreCase(it.currency())) {
                    flagRows.add(new Object[] {"non_egp:" + it.currency(), vid});
                    nonEgp++;
                } else if (isManual) {
                    manual++;
                } else {
                    costRows.add(new Object[] {it.cost(), it.cost() == null ? null : "shopify", vid});
                    if (it.cost() != null) written++;
                }
            }
            if (readStock) {
                levelDeletes.add(new Object[] {vid});
                int total = 0;
                for (Map.Entry<String, Integer> l : it.levels().entrySet()) {
                    if (l.getValue() == null || l.getKey() == null) continue;
                    levelRows.add(new Object[] {tid, vid, l.getKey(), l.getValue()});
                    total += l.getValue();
                }
                Integer atTraced = traced == null ? null : it.levels().getOrDefault(traced, 0);
                stockRows.add(new Object[] {total, atTraced, vid});
                stock++;
            }
        }
        jdbc.batchUpdate("UPDATE variants SET unit_cost = ?, cost_source = ?, cost_synced_at = now(), shopify_cost_flag = NULL " +
                         "WHERE id = ? AND (cost_source = 'shopify' OR unit_cost IS NULL)", costRows);
        jdbc.batchUpdate("UPDATE variants SET shopify_cost_flag = ?, cost_synced_at = now() WHERE id = ?", flagRows);
        jdbc.batchUpdate("UPDATE variants SET stock_inventory_item_id = ? WHERE id = ? AND stock_inventory_item_id IS DISTINCT FROM ?",
                         itemRows);
        jdbc.batchUpdate("DELETE FROM variant_shopify_levels WHERE variant_id = ?", levelDeletes);
        jdbc.batchUpdate("INSERT INTO variant_shopify_levels (tenant_id, variant_id, location_id, available) VALUES (?, ?, ?, ?)", levelRows);
        jdbc.batchUpdate("UPDATE variants SET stock_available_shopify = ?, stock_available_shopify_traced = ?, stock_synced_at = now() " +
                         "WHERE id = ?", stockRows);
        return new long[] {written, manual, nonEgp, stock};
    }

    private void finish(UUID tid, String trigger, String mode, long seen, long written, long manual, long nonEgp, long stock,
                        String costStatus, String stockStatus, String currency, String error) {
        tx.executeWithoutResult(s -> jdbc.update(
            "INSERT INTO analytics_inventory_sync (tenant_id, started_at, finished_at, trigger_kind, mode, variants_seen, cost_written, " +
            "  cost_kept_manual, cost_non_egp, stock_written, cost_status, stock_status, shop_currency, last_error, updated_at) " +
            "VALUES (?, now(), now(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()) " +
            "ON CONFLICT (tenant_id) DO UPDATE SET finished_at = now(), trigger_kind = EXCLUDED.trigger_kind, mode = EXCLUDED.mode, " +
            "  variants_seen = EXCLUDED.variants_seen, cost_written = EXCLUDED.cost_written, cost_kept_manual = EXCLUDED.cost_kept_manual, " +
            "  cost_non_egp = EXCLUDED.cost_non_egp, stock_written = EXCLUDED.stock_written, cost_status = EXCLUDED.cost_status, " +
            "  stock_status = EXCLUDED.stock_status, " +
            "  shop_currency = COALESCE(EXCLUDED.shop_currency, analytics_inventory_sync.shop_currency), " +
            "  last_error = EXCLUDED.last_error, updated_at = now()",
            tid, trigger, mode, seen, written, manual, nonEgp, stock, costStatus, stockStatus, currency, error));
    }

    // ── run now (rate-limited) + status ─────────────────────────────────────

    static final int RUN_EVERY_MINUTES = 10;

    /** Claims a manual run: true when the last request is older than 10 minutes (or there is none). */
    @Transactional
    public boolean claimManualRun() {
        UUID tid = TenantContext.require();
        Integer n = jdbc.query(
            "INSERT INTO analytics_inventory_sync (tenant_id, requested_at, updated_at) VALUES (?, now(), now()) " +
            "ON CONFLICT (tenant_id) DO UPDATE SET requested_at = now(), updated_at = now() " +
            "WHERE analytics_inventory_sync.requested_at IS NULL " +
            "   OR analytics_inventory_sync.requested_at < now() - make_interval(mins => ?) " +
            "RETURNING 1",
            rs -> rs.next() ? 1 : 0, tid, RUN_EVERY_MINUTES);
        return n != null && n == 1;
    }

    @Transactional(readOnly = true)
    public Status status() {
        UUID tid = TenantContext.require();
        Map<String, Object> v = jdbc.queryForMap(
            "SELECT COUNT(*) AS total, COUNT(*) FILTER (WHERE unit_cost IS NOT NULL) AS costed, " +
            "       COUNT(*) FILTER (WHERE stock_synced_at IS NOT NULL) AS stock_synced FROM variants WHERE tenant_id = ?", tid);
        List<Status> s = jdbc.query("SELECT * FROM analytics_inventory_sync WHERE tenant_id = ?", (rs, i) -> {
            Instant req = OrderFacts.instant(rs.getTimestamp("requested_at"));
            return new Status(tid, req, OrderFacts.instant(rs.getTimestamp("started_at")),
                OrderFacts.instant(rs.getTimestamp("finished_at")), rs.getString("trigger_kind"), rs.getString("mode"),
                rs.getLong("variants_seen"), rs.getLong("cost_written"), rs.getLong("cost_kept_manual"),
                rs.getLong("cost_non_egp"), rs.getLong("stock_written"), rs.getString("cost_status"), rs.getString("stock_status"),
                rs.getString("shop_currency"), rs.getString("last_error"), num(v, "total"), num(v, "costed"), num(v, "stock_synced"),
                req == null ? null : req.plusSeconds(RUN_EVERY_MINUTES * 60L));
        }, tid);
        if (!s.isEmpty()) return s.get(0);
        return new Status(tid, null, null, null, null, null, 0, 0, 0, 0, 0, "never", "never", null, null,
            num(v, "total"), num(v, "costed"), num(v, "stock_synced"), null);
    }

    private static long num(Map<String, Object> m, String k) {
        return ((Number) m.get(k)).longValue();
    }
}
