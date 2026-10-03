package com.traceability.review;

import com.traceability.integrations.bosta.SimulatedShipments;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.InventoryLedger;
import com.traceability.inventory.PackPrintBatchService;
import com.traceability.inventory.PickupSessionService;
import com.traceability.inventory.PieceStatus;
import com.traceability.inventory.TransitionContext;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.ReturnRequestLifecycle;
import com.traceability.portal.ReturnRequestService;
import com.traceability.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;

/**
 * Review mode S5 — the Shopify App Store review tenant's fixture. Seeded ONCE (ReviewTenantService
 * refuses a second run); NOT DemoSeeder: no owner pool, no BYPASSRLS, no raw piece writes. Every
 * write runs as app_user under the tenant, and every piece event goes through InventoryLedger
 * (batchReceive for creation, transition for state) or the real services that call it.
 *
 *   - placeholder store review-tracedtech.myshopify.invalid, disconnected (S1: a simulated tenant's
 *     disconnected rows never block the reviewer's own shop)
 *   - 5 products × 3 variants, external_id review-fixture:… (not Shopify gids → S4 never syncs them),
 *     images on our own domain ({shopify.app-url}/assets/review/*.webp)
 *   - one receipt, 45 pieces through InventoryLedger.batchReceive at the signup's fulfillment
 *     location (not ReceivingService.finalize — its Shopify trigger is pointless for fixture variants);
 *     batchReceive advances piece_counters past them
 *   - 13 orders #R1001–#R1013, each with its simulated forward shipment (SimulatedShipments, 777…):
 *       R1001–R1002 printed in batch #1 and packed · R1003 printed in batch #1, not packed ·
 *       R1004 with courier · R1005 delivered · R1006 delivered + approved refund return request ·
 *       R1007 on hold · R1008–R1013 ready to pick (R1008, R1011 two units)
 *
 * TenantContext.runAs does not nest (it clears on exit): each step sets the tenant itself, and the
 * services that runAs internally (PickupSessionService) are called outside any runAs.
 * The run is not one transaction (it drives services that commit on their own); a run that fails
 * midway leaves a partial fixture, which the next run refuses (FIXTURE_EXISTS) — reset it (S6).
 */
@Component
public class ReviewTenantSeeder {

    public static final String PLACEHOLDER_SHOP = "review-tracedtech.myshopify.invalid";
    static final String REFERENCE = "RR-REVW2";

    private record ProductSpec(String title, String slug, List<String> options, BigDecimal price) {}

    private static final List<ProductSpec> CATALOG = List.of(
        new ProductSpec("Classic Tee",    "classic-tee",    List.of("S", "M", "L"),                 new BigDecimal("350.00")),
        new ProductSpec("Linen Shirt",    "linen-shirt",    List.of("S", "M", "L"),                 new BigDecimal("690.00")),
        new ProductSpec("Canvas Tote",    "canvas-tote",    List.of("Black", "Natural", "Olive"),   new BigDecimal("420.00")),
        new ProductSpec("Leather Wallet", "leather-wallet", List.of("Brown", "Black", "Tan"),       new BigDecimal("560.00")),
        new ProductSpec("Water Bottle",   "water-bottle",   List.of("500 ml", "750 ml", "1 L"),     new BigDecimal("280.00")));

    private record Customer(String name, String phone, String address, String city) {}

    private static final List<Customer> CUSTOMERS = List.of(
        new Customer("Mariam Saleh",   "01012345678", "14 Talaat Harb St",     "Cairo"),
        new Customer("محمد أحمد",       "01112345678", "22 شارع التحرير",         "Giza"),
        new Customer("Youssef Adel",   "01212345678", "5 Abbas El Akkad St",   "Nasr City"),
        new Customer("نور حسن",         "01512345678", "8 شارع الجيش",            "Alexandria"),
        new Customer("Omar Khaled",    "01023456789", "31 Road 9, Maadi",      "Cairo"),
        new Customer("Salma Mostafa",  "01123456789", "10 El Merghany St",     "Heliopolis"),
        new Customer("كريم إبراهيم",    "01223456789", "3 شارع جامعة الدول",      "Mohandessin"),
        new Customer("Hana Fathy",     "01523456789", "17 Syria St",           "Alexandria"),
        new Customer("Ahmed Samir",    "01034567890", "40 Makram Ebeid St",    "Nasr City"),
        new Customer("ليلى محمود",       "01134567890", "12 شارع البطل أحمد",       "Giza"),
        new Customer("Mostafa Tarek",  "01234567890", "6 Road 233, Degla",     "Maadi"),
        new Customer("Rana Wael",      "01534567890", "25 El Thawra St",       "Heliopolis"),
        new Customer("Tamer Hassan",   "01045678901", "9 Gamal Abdel Nasser",  "Mansoura"));

    private final JdbcTemplate          jdbc;
    private final TransactionTemplate   tx;
    private final InventoryLedger       ledger;
    private final FulfillService        fulfill;
    private final PackPrintBatchService printBatches;
    private final PickupSessionService  pickups;
    private final ReturnRequestService  returnRequests;
    private final String                appUrl;

    public ReviewTenantSeeder(JdbcTemplate jdbc, PlatformTransactionManager txm, InventoryLedger ledger,
                              FulfillService fulfill, PackPrintBatchService printBatches,
                              PickupSessionService pickups, ReturnRequestService returnRequests,
                              @Value("${shopify.app-url}") String appUrl) {
        this.jdbc           = jdbc;
        this.tx             = new TransactionTemplate(txm);
        this.ledger         = ledger;
        this.fulfill        = fulfill;
        this.printBatches   = printBatches;
        this.pickups        = pickups;
        this.returnRequests = returnRequests;
        this.appUrl         = appUrl.endsWith("/") ? appUrl.substring(0, appUrl.length() - 1) : appUrl;
    }

    public Map<String, Object> seed(UUID tenant, UUID owner, UUID worker) {
        UUID location = as(tenant, () -> jdbc.queryForObject(
            // One fulfillment location per tenant (V61 unique index) — the signup's default.
            "SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment",
            UUID.class, tenant));
        UUID store = as(tenant, () -> jdbc.queryForObject(
            "INSERT INTO stores (tenant_id, platform, shop_domain, status) " +
            "VALUES (?, 'shopify', ?, 'disconnected') RETURNING id", UUID.class, tenant, PLACEHOLDER_SHOP));

        List<UUID> variants = catalog(tenant, store);
        receiveStock(tenant, location, worker, variants);

        // R1001–R1003 first, alone in the queue, so batch #1 holds exactly them.
        List<UUID> orders = new ArrayList<>();
        for (int i = 0; i < 3; i++) orders.add(order(tenant, store, i, variants, 1));
        as(tenant, () -> printBatches.print("new", "A4", "oldest", owner));
        pack(tenant, orders.get(0), worker);                     // R1001 packed
        pack(tenant, orders.get(1), worker);                     // R1002 packed (R1003 printed only)

        // R1004–R1006: packed, handed to the courier in one pickup session.
        for (int i = 3; i < 6; i++) { orders.add(order(tenant, store, i, variants, 1)); pack(tenant, orders.get(i), worker); }
        UUID pickup = pickups.openSession(tenant, worker, LocalDate.now(), null, "Review fixture");
        for (int i = 3; i < 6; i++) pickups.scan(tenant, pickup, worker, tracking(tenant, orders.get(i)));
        pickups.closeSession(tenant, pickup, worker);
        deliver(tenant, orders.get(4));                          // R1005 delivered
        deliver(tenant, orders.get(5));                          // R1006 delivered …
        UUID request = returnRequest(tenant, orders.get(5), owner);   // … + approved return request

        orders.add(order(tenant, store, 6, variants, 1));        // R1007 on hold
        as(tenant, () -> { fulfill.holdOrder(orders.get(6), owner, "Customer asked to deliver next week"); return null; });

        for (int i = 7; i < 13; i++) orders.add(order(tenant, store, i, variants, (i == 7 || i == 10) ? 2 : 1));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", tenant.toString());
        out.put("products", CATALOG.size());
        out.put("variants", variants.size());
        out.put("pieces", variants.size() * 3);
        out.put("orders", orders.size());
        out.put("pickupId", pickup.toString());
        out.put("returnRequestId", request.toString());
        return out;
    }

    // ── steps ────────────────────────────────────────────────────────────────

    private List<UUID> catalog(UUID tenant, UUID store) {
        List<UUID> variants = new ArrayList<>();
        int n = 0;
        for (ProductSpec p : CATALOG) {
            n++;
            final int pn = n;
            UUID product = as(tenant, () -> jdbc.queryForObject(
                "INSERT INTO products (tenant_id, store_id, external_id, title, status, image_url) " +
                "VALUES (?, ?, ?, ?, 'active', ?) RETURNING id", UUID.class,
                tenant, store, "review-fixture:product:" + pn, p.title(), appUrl + "/assets/review/" + p.slug() + ".webp"));
            for (String option : p.options()) {
                variants.add(as(tenant, () -> jdbc.queryForObject(
                    "INSERT INTO variants (tenant_id, product_id, external_id, title, sku, price) " +
                    "VALUES (?, ?, ?, ?, ?, ?) RETURNING id", UUID.class,
                    tenant, product, "review-fixture:variant:" + p.slug() + ":" + option, option,
                    ("RV-" + p.slug() + "-" + option).toUpperCase(Locale.ROOT).replace(' ', '-'), p.price())));
            }
        }
        return variants;
    }

    private void receiveStock(UUID tenant, UUID location, UUID worker, List<UUID> variants) {
        UUID receipt = as(tenant, () -> jdbc.queryForObject(
            "INSERT INTO receipts (tenant_id, reference, supplier_name, received_by, location_id, status, finalized_at, kind) " +
            "VALUES (?, 'REVIEW-FIXTURE', 'Review fixture', ?, ?, 'finalized', now(), 'inbound') RETURNING id",
            UUID.class, tenant, worker, location));
        List<InventoryLedger.ReceiveSpec> specs = new ArrayList<>();
        for (UUID v : variants) {
            as(tenant, () -> jdbc.update("INSERT INTO receipt_lines (tenant_id, receipt_id, variant_id, quantity) VALUES (?, ?, ?, 3)",
                tenant, receipt, v));
            for (int k = 0; k < 3; k++) specs.add(new InventoryLedger.ReceiveSpec(UlidGenerator.generate(), tenant, v, receipt, location));
        }
        as(tenant, () -> { ledger.batchReceive(specs, worker); return null; });
    }

    /** Order #R(1001+i) with {units} of one variant, COD = price × units, its simulated forward shipment. */
    private UUID order(UUID tenant, UUID store, int i, List<UUID> variants, int units) {
        Customer c = CUSTOMERS.get(i);
        UUID variant = variants.get((i * 4) % variants.size());
        return as(tenant, () -> {
            BigDecimal price = jdbc.queryForObject("SELECT price FROM variants WHERE id = ?", BigDecimal.class, variant);
            UUID order = jdbc.queryForObject(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, customer_name, customer_phone, address, " +
                "                    payment_method, cod_amount, status, placed_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, jsonb_build_object('address1', ?::text, 'city', ?::text), 'cod', ?, 'new', " +
                "        now() - make_interval(hours => ?)) RETURNING id", UUID.class,
                tenant, store, "review-fixture:order:" + (1001 + i), "#R" + (1001 + i), c.name(), c.phone(),
                c.address(), c.city(), price.multiply(BigDecimal.valueOf(units)), 40 - i * 3);
            jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?)",
                tenant, order, variant, units);
            SimulatedShipments.ensureForwardShipment(jdbc, tenant, order);
            return order;
        });
    }

    /** Pick & pack through FulfillService: lock, scan one available piece per unit, complete. */
    private void pack(UUID tenant, UUID order, UUID worker) {
        as(tenant, () -> { fulfill.lockOrder(order, worker); return null; });
        List<Map<String, Object>> lines = as(tenant, () -> jdbc.queryForList(
            "SELECT variant_id, quantity FROM order_items WHERE order_id = ? AND tenant_id = ?", order, tenant));
        for (Map<String, Object> line : lines) {
            for (int u = 0; u < ((Number) line.get("quantity")).intValue(); u++) {
                String barcode = as(tenant, () -> jdbc.queryForObject(
                    "SELECT barcode FROM pieces WHERE tenant_id = ? AND variant_id = ? AND status = 'available' " +
                    "ORDER BY short_code LIMIT 1", String.class, tenant, line.get("variant_id")));
                FulfillService.ScanResult r = as(tenant, () -> fulfill.scan(order, barcode, worker));
                if (!r.success()) throw new IllegalStateException("Review fixture: scan refused for order " + order + ": " + r);
            }
        }
        as(tenant, () -> fulfill.complete(order, worker));
    }

    /** Delivered as a courier update would leave it: pieces with_courier → delivered (ledger), shipment delivered. */
    private void deliver(UUID tenant, UUID order) {
        as(tenant, () -> {
            UUID shipment = jdbc.queryForObject(
                "SELECT id FROM shipments WHERE order_id = ? AND tenant_id = ? AND shipment_leg = 'forward' " +
                "ORDER BY created_at DESC, id DESC LIMIT 1", UUID.class, order, tenant);
            List<String> pieces = jdbc.queryForList(
                "SELECT p.id FROM pieces p JOIN allocations a ON a.piece_id = p.id AND a.tenant_id = p.tenant_id " +
                "JOIN order_items oi ON oi.id = a.order_item_id WHERE oi.order_id = ? AND p.tenant_id = ? " +
                "AND p.status = 'with_courier'", String.class, order, tenant);
            for (String piece : pieces) {
                ledger.transition(piece, PieceStatus.WITH_COURIER, PieceStatus.DELIVERED, "courier_update", null,
                    new TransitionContext(order, shipment, null, null, null));
            }
            jdbc.update("UPDATE shipments SET internal_state = 'delivered', delivered_at = now() WHERE id = ? AND tenant_id = ?",
                shipment, tenant);
            jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, provider_state) " +
                "VALUES (?, ?, 'delivered', 45)", tenant, shipment);
            return null;
        });
    }

    /** A refund request for the delivered piece — inserted as the portal would, then approved by the owner. */
    private UUID returnRequest(UUID tenant, UUID order, UUID owner) {
        UUID request = as(tenant, () -> {
            Map<String, Object> piece = jdbc.queryForMap(
                "SELECT p.id, p.variant_id FROM pieces p JOIN allocations a ON a.piece_id = p.id AND a.tenant_id = p.tenant_id " +
                "JOIN order_items oi ON oi.id = a.order_item_id WHERE oi.order_id = ? AND p.tenant_id = ? " +
                "AND p.status = 'delivered' LIMIT 1", order, tenant);
            UUID id = jdbc.queryForObject(
                "INSERT INTO return_requests (tenant_id, order_id, type, status, reference, customer_note, pickup_address_source) " +
                "VALUES (?, ?, 'refund', 'requested', ?, 'Too small — could I return it?', 'order') RETURNING id",
                UUID.class, tenant, order, REFERENCE);
            jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, piece_id, variant_id, reason_code) " +
                "VALUES (?, ?, ?, ?, 'wrong_size')", tenant, id, piece.get("id"), piece.get("variant_id"));
            new ReturnRequestLifecycle(jdbc).event(tenant, id, "requested", null, ReturnRequestLifecycle.meta("items", 1));
            return id;
        });
        as(tenant, () -> { returnRequests.approve(request, owner); return null; });
        return request;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String tracking(UUID tenant, UUID order) {
        return as(tenant, () -> jdbc.queryForObject(
            "SELECT tracking_number FROM shipments WHERE order_id = ? AND tenant_id = ? AND shipment_leg = 'forward'",
            String.class, order, tenant));
    }

    /** One step in the tenant, in its own transaction (TenantContext must be set BEFORE it begins). */
    private <T> T as(UUID tenant, Supplier<T> step) {
        return TenantContext.runAs(tenant, () -> tx.execute(s -> step.get()));
    }
}
