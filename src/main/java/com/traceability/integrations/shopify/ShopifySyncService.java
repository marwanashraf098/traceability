package com.traceability.integrations.shopify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.inventory.BlocklistService;
import com.traceability.security.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Orchestrates Shopify store connect, catalog import (products + variants),
 * and 90-day order import.
 *
 * Transaction strategy: each product and each order is upserted in its own
 * transaction via TransactionTemplate (not @Transactional, which would require
 * self-invocation through a proxy). This keeps per-row failures isolated —
 * a bad order doesn't roll back 500 preceding ones.
 *
 * Tenant context: TenantContext is set by TenantContextFilter before these methods
 * are called. TransactionTemplate uses the primary TenantAwareDataSource, which
 * fires SET LOCAL app.current_tenant at transaction start. All RLS policies
 * therefore see the correct tenant automatically.
 */
@Service
public class ShopifySyncService {

    private static final Logger log = LoggerFactory.getLogger(ShopifySyncService.class);

    // ---- SQL ------------------------------------------------------------

    // DEV-ONLY connect path: sets access_token_expires_at far in the future so ShopifyTokenProvider
    // treats the admin token as fresh. In production all stores are installed via OAuth which sets
    // a real 1-hour expiry + rotating refresh token.
    private static final String UPSERT_STORE = """
            INSERT INTO stores (tenant_id, shop_domain, platform, access_token_encrypted,
                                access_token_expires_at, status, import_status)
            VALUES (?, ?, 'shopify', ?, now() + interval '876000 hours', 'connected', 'pending')
            ON CONFLICT (shop_domain) DO UPDATE SET
                access_token_encrypted  = EXCLUDED.access_token_encrypted,
                access_token_expires_at = EXCLUDED.access_token_expires_at,
                status                  = 'connected',
                import_status           = 'pending'
            WHERE stores.tenant_id = EXCLUDED.tenant_id
            RETURNING id
            """;

    // Custom-app connect path: stores connection_type='custom_app' and encrypts the per-store API secret.
    // Uses the same 100-year expiry trick as UPSERT_STORE so ShopifyTokenProvider.isFresh()=true.
    private static final String UPSERT_STORE_CUSTOM_APP = """
            INSERT INTO stores (tenant_id, shop_domain, platform, access_token_encrypted,
                                access_token_expires_at, status, import_status,
                                connection_type, api_secret_encrypted)
            VALUES (?, ?, 'shopify', ?, now() + interval '876000 hours', 'connected', 'pending',
                    'custom_app', ?)
            ON CONFLICT (shop_domain) DO UPDATE SET
                access_token_encrypted  = EXCLUDED.access_token_encrypted,
                access_token_expires_at = EXCLUDED.access_token_expires_at,
                api_secret_encrypted    = EXCLUDED.api_secret_encrypted,
                status                  = 'connected',
                import_status           = 'pending',
                connection_type         = 'custom_app'
            WHERE stores.tenant_id = EXCLUDED.tenant_id
            RETURNING id
            """;

    // CC connect path: stores connection_type='custom_app_cc', encrypts clientId (for re-exchange)
    // and clientSecret (also used as webhook signing key = api_secret_encrypted).
    // access_token_expires_at is set to now() + expiresInSeconds (not the 100-year trick)
    // so ShopifyTokenProvider knows it needs to re-exchange when expired.
    private static final String UPSERT_STORE_CUSTOM_APP_CC = """
            INSERT INTO stores (tenant_id, shop_domain, platform, access_token_encrypted,
                                access_token_expires_at, status, import_status,
                                connection_type, api_secret_encrypted, client_id_encrypted,
                                refresh_token_encrypted, refresh_token_expires_at)
            VALUES (?, ?, 'shopify', ?, ?, 'connected', 'pending',
                    'custom_app_cc', ?, ?, NULL, NULL)
            ON CONFLICT (shop_domain) DO UPDATE SET
                access_token_encrypted   = EXCLUDED.access_token_encrypted,
                access_token_expires_at  = EXCLUDED.access_token_expires_at,
                api_secret_encrypted     = EXCLUDED.api_secret_encrypted,
                client_id_encrypted      = EXCLUDED.client_id_encrypted,
                refresh_token_encrypted  = NULL,
                refresh_token_expires_at = NULL,
                status                   = 'connected',
                import_status            = 'pending',
                connection_type          = 'custom_app_cc'
            WHERE stores.tenant_id = EXCLUDED.tenant_id
            RETURNING id
            """;

    private static final String UPSERT_PRODUCT = """
            INSERT INTO products (tenant_id, store_id, external_id, title, status, raw)
            VALUES (?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (store_id, external_id) DO UPDATE SET
                title  = EXCLUDED.title,
                status = EXCLUDED.status,
                raw    = EXCLUDED.raw
            RETURNING id
            """;

    // variant conflict target is (product_id, external_id) — the real UNIQUE constraint from V1.
    // product_id is our internal UUID, resolved from the product upsert RETURNING within the
    // same transaction, so FK is always satisfied before variants are inserted.
    private static final String UPSERT_VARIANT = """
            INSERT INTO variants (tenant_id, product_id, external_id, sku, title, price, raw)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (product_id, external_id) DO UPDATE SET
                sku   = EXCLUDED.sku,
                title = EXCLUDED.title,
                price = EXCLUDED.price,
                raw   = EXCLUDED.raw
            """;

    // status, on_hold, and hold_reason are intentionally omitted from the SET list:
    // re-importing must never downgrade a picked/packed order back to 'new' or clear
    // an operator-set hold. Post-pack field changes (cod_amount edits, address corrections)
    // arrive via D6 webhooks — the importer does not mutate shipped orders' COD.
    private static final String UPSERT_ORDER = """
            INSERT INTO orders
                (tenant_id, store_id, external_id, number, customer_name, customer_phone,
                 address, payment_method, cod_amount, placed_at, raw)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::order_payment_method, ?, ?, ?::jsonb)
            ON CONFLICT (store_id, external_id) DO UPDATE SET
                customer_name  = EXCLUDED.customer_name,
                customer_phone = EXCLUDED.customer_phone,
                address        = EXCLUDED.address,
                payment_method = EXCLUDED.payment_method,
                cod_amount     = EXCLUDED.cod_amount,
                placed_at      = EXCLUDED.placed_at,
                raw            = EXCLUDED.raw
            RETURNING id
            """;

    // Partial unique index: ON CONFLICT target must match V4's index predicate exactly.
    // Hard-deletes of removed Shopify lines are a D6 webhook concern, not the importer's.
    private static final String UPSERT_ORDER_ITEM = """
            INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id, raw)
            VALUES (?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (order_id, external_id) WHERE external_id IS NOT NULL
            DO UPDATE SET
                quantity = EXCLUDED.quantity,
                raw      = EXCLUDED.raw
            """;

    private static final String LOOKUP_VARIANT =
            "SELECT v.id FROM variants v JOIN products p ON v.product_id = p.id " +
            "WHERE p.store_id = ? AND v.external_id = ?";

    private static final String FLAG_ORDER_UNMAPPED =
            "UPDATE orders SET on_hold = true, hold_reason = 'unmapped_variant_on_import' " +
            "WHERE id = ? AND on_hold = false";

    private static final String UPDATE_STORE_SYNC =
            "UPDATE stores SET last_sync_at = now() WHERE id = ?";

    // ---- constructor ----------------------------------------------------

    private final JdbcTemplate jdbc;
    private final ShopifyGateway shopifyGateway;
    private final EncryptionService encryptionService;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final BlocklistService blocklist;

    public ShopifySyncService(JdbcTemplate jdbc,
                               ShopifyGateway shopifyGateway,
                               EncryptionService encryptionService,
                               ObjectMapper mapper,
                               PlatformTransactionManager txm,
                               BlocklistService blocklist) {
        this.jdbc              = jdbc;
        this.shopifyGateway    = shopifyGateway;
        this.encryptionService = encryptionService;
        this.mapper            = mapper;
        this.tx                = new TransactionTemplate(txm);
        this.blocklist         = blocklist;
    }

    // ---- public API -----------------------------------------------------

    public record ImportResult(int products, int variants, int orders, int flaggedOrders) {}
    public record ConnectResult(UUID storeId, String shopName) {}

    /**
     * Validates credentials, stores the encrypted token, sets import_status='pending'.
     * Returns immediately — the actual import runs as a background JobRunr job.
     */
    public ConnectResult connect(UUID tenantId, String shopDomain, String rawToken) {
        String shopName = shopifyGateway.validateShop(shopDomain, rawToken);
        String encrypted = encryptionService.encrypt(rawToken);

        UUID storeId = tx.execute(status -> {
            UUID id = jdbc.query(UPSERT_STORE, rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                    tenantId, shopDomain, encrypted);
            if (id == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Shop domain is already connected to a different account");
            }
            return id;
        });

        return new ConnectResult(storeId, shopName);
    }

    /**
     * Validates credentials, stores encrypted tokens for a custom-app (DEV/pilot) connection.
     * Sets connection_type='custom_app' and stores the per-store API secret for webhook HMAC.
     *
     * Rotating-token guard: permanent custom-app admin tokens start with "shpat_".
     * Tokens from the token-exchange flow (expiring) have a different prefix.
     * Returns 400 if the token does not look like a permanent admin token.
     */
    public ConnectResult connectCustomApp(UUID tenantId, String shopDomain, String rawToken, String rawApiSecret) {
        String shopName = shopifyGateway.validateShop(shopDomain, rawToken);
        // Rotating/expiring token guard: permanent custom-app admin tokens start with "shpat_".
        // Expiring tokens from the token-exchange flow have a different prefix.
        if (!rawToken.startsWith("shpat_")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "This appears to be a rotating/expiring token (expected a permanent admin token " +
                "starting with 'shpat_'). Please create a non-rotating custom app in Partner " +
                "Dashboard and use its Admin API access token.");
        }
        String encryptedToken  = encryptionService.encrypt(rawToken);
        String encryptedSecret = encryptionService.encrypt(rawApiSecret);

        UUID storeId = tx.execute(status -> {
            UUID id = jdbc.query(UPSERT_STORE_CUSTOM_APP,
                rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                tenantId, shopDomain, encryptedToken, encryptedSecret);
            if (id == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Shop domain is already connected to a different account");
            }
            return id;
        });

        return new ConnectResult(storeId, shopName);
    }

    /**
     * Stores a client-credentials custom-app connection (connection_type='custom_app_cc').
     * Unlike the legacy custom_app path, the access token has a real expiry (~24h) and
     * will be re-exchanged by ShopifyTokenProvider when it expires.
     *
     * @param tenantId        the tenant to associate the store with
     * @param shopDomain      the myshopify.com domain
     * @param clientId        the Dev Dashboard Client ID (for re-exchange)
     * @param clientSecret    the Dev Dashboard Client Secret (also the webhook signing key)
     * @param accessToken     the plaintext access token from the CC exchange
     * @param expiresInSeconds token lifetime reported by Shopify (typically 86399)
     */
    public ConnectResult connectCustomAppCC(UUID tenantId, String shopDomain,
                                            String clientId, String clientSecret,
                                            String accessToken, long expiresInSeconds) {
        String encryptedToken    = encryptionService.encrypt(accessToken);
        String encryptedClientId = encryptionService.encrypt(clientId);
        String encryptedSecret   = encryptionService.encrypt(clientSecret);

        java.sql.Timestamp expiresAt = java.sql.Timestamp.from(
            java.time.Instant.now().plusSeconds(expiresInSeconds));

        UUID storeId = tx.execute(status -> {
            UUID id = jdbc.query(UPSERT_STORE_CUSTOM_APP_CC,
                rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                tenantId, shopDomain, encryptedToken, expiresAt,
                encryptedSecret, encryptedClientId);
            if (id == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Shop domain is already connected to a different account");
            }
            return id;
        });

        return new ConnectResult(storeId, shopDomain);
    }

    private static final String LOOKUP_STORE =
        "SELECT id FROM stores WHERE shop_domain = ? AND tenant_id = ? AND status = 'connected'";

    /**
     * Ingests an order from the GraphQL import format (ShopifyGateway.Order) without
     * a prior existence check. Called by ShopifyReconcileJob after the job confirms the
     * order is absent locally — reuses the exact same SQL path as the initial full import.
     */
    public void ingestMissingOrder(UUID storeId, UUID tenantId, ShopifyGateway.Order order) {
        upsertOrder(storeId, tenantId, order);
    }

    /** Runs catalog + order import for an already-connected store. Called by ShopifyImportJob. */
    public ImportResult runImport(UUID storeId, UUID tenantId, String shopDomain, String rawToken) {
        int[] catalogCounts = importCatalog(storeId, tenantId, shopDomain, rawToken);
        int[] orderCounts   = importOrders(storeId, tenantId, shopDomain, rawToken, 90);
        tx.execute(s -> { jdbc.update(UPDATE_STORE_SYNC, storeId); return null; });
        return new ImportResult(catalogCounts[0], catalogCounts[1], orderCounts[0], orderCounts[1]);
    }

    // ---- webhook ingestion (REST payload format) -----------------------

    /**
     * Resolves the store for a shop domain under the given tenant. Returns null if not found
     * or disconnected — callers should log and skip rather than throw.
     */
    public UUID resolveStore(UUID tenantId, String shopDomain) {
        return tx.execute(s ->
            jdbc.query(LOOKUP_STORE,
                rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                shopDomain, tenantId));
    }

    /**
     * Upserts a single order from a Shopify REST webhook payload (orders/create, orders/updated).
     * Reuses the same SQL and variant-lookup logic as the full import pipeline.
     * The payload's admin_graphql_api_id is used as the external_id (GID form) for consistency
     * with the GraphQL import.
     */
    public void ingestOrderWebhook(UUID storeId, UUID tenantId, JsonNode payload) {
        String gid = payload.path("admin_graphql_api_id").asText(null);
        if (gid == null || gid.isBlank()) {
            log.warn("orders webhook missing admin_graphql_api_id, store={}", storeId);
            return;
        }

        String name = payload.path("name").asText("#?");

        // Customer PII — null until PCD approval; raw column preserves full payload.
        String customerName  = null;
        String customerPhone = null;
        JsonNode shippingAddr = null;

        String displayFinancialStatus = payload.path("financial_status").asText(null);
        java.util.List<String> gateways = new java.util.ArrayList<>();
        for (JsonNode gw : payload.path("payment_gateway_names")) gateways.add(gw.asText());

        String priceStr = payload.path("current_total_price").asText(null);
        BigDecimal totalPrice = priceStr != null ? new BigDecimal(priceStr) : BigDecimal.ZERO;

        Instant createdAt;
        try {
            createdAt = Instant.parse(payload.path("created_at").asText());
        } catch (Exception e) {
            createdAt = Instant.now();
        }

        String paymentMethod = inferPaymentMethod(displayFinancialStatus, gateways);
        BigDecimal codAmount = "cod".equals(paymentMethod) ? totalPrice : null;

        final String finalGid = gid;
        final Instant finalCreatedAt = createdAt;
        final String finalPaymentMethod = paymentMethod;
        final BigDecimal finalCodAmount = codAmount;

        Boolean flagged = tx.execute(s -> {
            UUID orderId = jdbc.query(UPSERT_ORDER,
                rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                tenantId, storeId, finalGid, name, customerName, customerPhone,
                toJson(shippingAddr), finalPaymentMethod, finalCodAmount,
                java.sql.Timestamp.from(finalCreatedAt), toJson(payload));
            if (orderId == null) return false;

            boolean needsHold = false;
            for (JsonNode line : payload.path("line_items")) {
                long variantRestId = line.path("variant_id").asLong(0);
                if (variantRestId == 0) {
                    needsHold = true;
                    continue;
                }
                // Variant GID from webhook REST line item
                String variantGid = "gid://shopify/ProductVariant/" + variantRestId;
                UUID variantId = jdbc.query(LOOKUP_VARIANT,
                    rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                    storeId, variantGid);
                if (variantId == null) {
                    log.warn("Webhook order {} line references unknown variant GID {} — flagging order",
                        name, variantGid);
                    needsHold = true;
                    continue;
                }
                int qty = line.path("quantity").asInt(1);
                String lineGid = line.has("admin_graphql_api_id")
                    ? line.path("admin_graphql_api_id").asText()
                    : "gid://shopify/LineItem/" + line.path("id").asLong();
                jdbc.update(UPSERT_ORDER_ITEM, tenantId, orderId, variantId, qty, lineGid, toJson(line));
            }
            if (needsHold) jdbc.update(FLAG_ORDER_UNMAPPED, orderId);

            // FR-7.8a: blocklist gate — runs only when phone is available (pre-PCD: null → skipped)
            blocklist.checkAndHoldIfBlocked(orderId, customerPhone, tenantId);

            return needsHold;
        });
        log.debug("Webhook order upsert: gid={} store={} flagged={}", gid, storeId, flagged);
    }

    /**
     * Upserts a product + variants from a Shopify REST webhook payload (products/create, products/update).
     */
    public void ingestProductWebhook(UUID storeId, UUID tenantId, JsonNode payload) {
        String gid = payload.path("admin_graphql_api_id").asText(null);
        if (gid == null || gid.isBlank()) {
            log.warn("products webhook missing admin_graphql_api_id, store={}", storeId);
            return;
        }
        String title  = payload.path("title").asText("");
        String status = payload.path("status").asText("active").toLowerCase();

        tx.execute(s -> {
            UUID productId = jdbc.query(UPSERT_PRODUCT,
                rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                tenantId, storeId, gid, title, status, toJson(payload));
            if (productId == null) return null;

            for (JsonNode v : payload.path("variants")) {
                String variantGid = v.path("admin_graphql_api_id").asText(null);
                if (variantGid == null) {
                    variantGid = "gid://shopify/ProductVariant/" + v.path("id").asLong();
                }
                String sku   = v.path("sku").asText(null);
                String vTitle = v.path("title").asText("");
                String priceStr = v.path("price").asText(null);
                BigDecimal price = priceStr != null ? new BigDecimal(priceStr) : null;
                jdbc.update(UPSERT_VARIANT, tenantId, productId, variantGid, sku, vTitle, price, toJson(v));
            }
            return null;
        });
        log.debug("Webhook product upsert: gid={} store={}", gid, storeId);
    }

    // ---- catalog import -------------------------------------------------

    private int[] importCatalog(UUID storeId, UUID tenantId, String shopDomain, String rawToken) {
        int products = 0, variants = 0;
        String cursor = null;
        do {
            ShopifyGateway.ProductPage page = shopifyGateway.fetchProductsPage(shopDomain, rawToken, cursor);
            for (ShopifyGateway.Product p : page.products()) {
                int[] counts = upsertProduct(storeId, tenantId, p);
                products++;
                variants += counts[0];
            }
            cursor = page.hasNextPage() ? page.endCursor() : null;
        } while (cursor != null);

        log.info("Catalog import complete: {} products, {} variants for store {}", products, variants, storeId);
        return new int[]{products, variants};
    }

    private int[] upsertProduct(UUID storeId, UUID tenantId, ShopifyGateway.Product p) {
        return tx.execute(status -> {
            UUID productId = jdbc.query(UPSERT_PRODUCT, rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                    tenantId, storeId, p.gid(), p.title(), p.status(), toJson(p));
            if (productId == null) throw new ShopifyException("Product upsert returned no ID for GID " + p.gid());

            int variantCount = 0;
            for (ShopifyGateway.Variant v : p.variants()) {
                jdbc.update(UPSERT_VARIANT,
                        tenantId, productId, v.gid(), v.sku(), v.title(), v.price(), toJson(v));
                variantCount++;
            }
            return new int[]{variantCount};
        });
    }

    // ---- order import ---------------------------------------------------

    private int[] importOrders(UUID storeId, UUID tenantId, String shopDomain, String rawToken, int daysBack) {
        String createdAfter = Instant.now().minus(daysBack, ChronoUnit.DAYS).toString();
        int orders = 0, flagged = 0;
        String cursor = null;
        do {
            ShopifyGateway.OrderPage page = shopifyGateway.fetchOrdersPage(shopDomain, rawToken, cursor, createdAfter);
            for (ShopifyGateway.Order o : page.orders()) {
                boolean wasFlagged = upsertOrder(storeId, tenantId, o);
                orders++;
                if (wasFlagged) flagged++;
            }
            cursor = page.hasNextPage() ? page.endCursor() : null;
        } while (cursor != null);

        log.info("Order import complete: {} orders ({} flagged) for store {}", orders, flagged, storeId);
        return new int[]{orders, flagged};
    }

    private boolean upsertOrder(UUID storeId, UUID tenantId, ShopifyGateway.Order o) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            String paymentMethod = inferPaymentMethod(o.displayFinancialStatus(), o.paymentGateways());
            BigDecimal codAmount = "cod".equals(paymentMethod) ? o.totalPrice() : null;

            UUID orderId = jdbc.query(UPSERT_ORDER, rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                    tenantId, storeId, o.gid(), o.name(), o.customerName(), o.customerPhone(),
                    toJson(o.shippingAddress()), paymentMethod, codAmount,
                    java.sql.Timestamp.from(o.createdAt()), toJson(o.raw()));
            if (orderId == null) throw new ShopifyException("Order upsert returned no ID for GID " + o.gid());

            boolean needsHold = false;
            for (ShopifyGateway.LineItem line : o.lineItems()) {
                if (line.variantGid() == null) {
                    log.warn("Order {} line {} has no variant GID — skipping", o.name(), line.gid());
                    needsHold = true;
                    continue;
                }
                UUID variantId = jdbc.query(LOOKUP_VARIANT,
                        rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                        storeId, line.variantGid());
                if (variantId == null) {
                    log.warn("Order {} line {} references unmapped variant {} — skipping line, flagging order",
                            o.name(), line.gid(), line.variantGid());
                    needsHold = true;
                    continue;
                }
                jdbc.update(UPSERT_ORDER_ITEM,
                        tenantId, orderId, variantId, line.quantity(), line.gid(), toJson(line));
            }

            if (needsHold) {
                jdbc.update(FLAG_ORDER_UNMAPPED, orderId);
            }

            // FR-7.8a: blocklist gate — runs only when phone is available (pre-PCD: null → skipped)
            blocklist.checkAndHoldIfBlocked(orderId, o.customerPhone(), tenantId);

            return needsHold;
        }));
    }

    // ---- helpers --------------------------------------------------------

    /**
     * COD detection mapping (documented per requirements):
     *   Primary signal — displayFinancialStatus (Shopify 2024-04+ replaced financialStatus):
     *     "Pending"  → COD (Shopify sets pending for all unconfirmed COD orders)
     *     "Paid" / "Authorized" / "Partially paid" → prepaid
     *   Secondary signal — paymentGatewayNames:
     *     Contains "cash" or "cod" (case-insensitive) → COD override
     *     Catches stores where COD orders may have displayFinancialStatus "Authorized"
     *     (e.g. a COD gateway that pre-authorises on order placement).
     *   Default: prepaid.
     */
    private static String inferPaymentMethod(String displayFinancialStatus, java.util.List<String> gateways) {
        if ("pending".equalsIgnoreCase(displayFinancialStatus)) return "cod";
        boolean codGateway = gateways.stream()
                .anyMatch(g -> g.toLowerCase().contains("cash") || g.toLowerCase().contains("cod"));
        return codGateway ? "cod" : "prepaid";
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            if (obj instanceof JsonNode n) return mapper.writeValueAsString(n);
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new ShopifyException("JSON serialization failed", e);
        }
    }
}
