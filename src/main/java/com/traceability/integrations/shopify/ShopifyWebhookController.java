package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.inventory.FulfillService;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Receives Shopify webhooks at POST /api/v1/webhooks/shopify.
 *
 * Handles orders/cancelled: applies the same pre-pack/post-pack cancellation
 * logic as the manual cancel endpoint. HMAC-SHA256 validation is performed
 * when the store has a webhook_secret configured; skipped in dev mode when unset.
 */
@RestController
@RequestMapping("/api/v1/webhooks/shopify")
public class ShopifyWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ShopifyWebhookController.class);

    private final JdbcTemplate        jdbc;
    private final FulfillService       fulfillSvc;
    private final ObjectMapper         mapper;
    private final TransactionTemplate  tx;

    public ShopifyWebhookController(JdbcTemplate jdbc,
                                     FulfillService fulfillSvc,
                                     ObjectMapper mapper,
                                     PlatformTransactionManager txm) {
        this.jdbc       = jdbc;
        this.fulfillSvc = fulfillSvc;
        this.mapper     = mapper;
        this.tx         = new TransactionTemplate(txm);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void receive(
            @RequestHeader(value = "X-Shopify-Topic",      defaultValue = "") String topic,
            @RequestHeader(value = "X-Shopify-Shop-Domain",defaultValue = "") String shopDomain,
            @RequestHeader(value = "X-Shopify-Hmac-Sha256",defaultValue = "") String hmac,
            @RequestHeader(value = "X-Shopify-Webhook-Id", defaultValue = "") String webhookId,
            @RequestBody byte[] rawBody) {

        if (shopDomain.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing X-Shopify-Shop-Domain");
        }

        // Resolve tenant — SECURITY DEFINER bypasses RLS for shop domain lookup
        UUID tenantId = tx.execute(s ->
            jdbc.query(
                "SELECT resolve_tenant_by_shop_domain(?)",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                shopDomain));
        if (tenantId == null) {
            log.warn("Shopify webhook from unknown shop domain: {}", shopDomain);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown shop");
        }

        // HMAC validation: verify if the store has a webhook_secret configured
        TenantContext.set(tenantId);
        try {
            String secret = tx.execute(s ->
                jdbc.query(
                    "SELECT webhook_secret FROM stores WHERE shop_domain = ? AND tenant_id = ?",
                    rs -> rs.next() ? rs.getString("webhook_secret") : null,
                    shopDomain, tenantId));
            if (secret != null && !secret.isBlank()) {
                if (!verifyHmac(rawBody, secret, hmac)) {
                    log.warn("Shopify webhook HMAC mismatch for shop {}", shopDomain);
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid HMAC");
                }
            }

            // Dedup: skip if already processed
            if (!webhookId.isBlank()) {
                Integer existing = tx.execute(s ->
                    jdbc.queryForObject(
                        "SELECT COUNT(*) FROM webhook_events " +
                        "WHERE source = 'shopify' AND external_event_id = ?",
                        Integer.class, webhookId));
                if (existing != null && existing > 0) {
                    log.debug("Duplicate Shopify webhook {} — skipping", webhookId);
                    return;
                }
            }

            // Persist to webhook_events
            String bodyStr = new String(rawBody, StandardCharsets.UTF_8);
            tx.execute(s -> {
                jdbc.update(
                    "INSERT INTO webhook_events " +
                    "(source, tenant_id, topic, external_event_id, payload, status) " +
                    "VALUES ('shopify', ?, ?, ?, ?::jsonb, 'processed')",
                    tenantId, topic, webhookId.isBlank() ? null : webhookId, bodyStr);
                return null;
            });

            if ("orders/cancelled".equals(topic)) {
                handleOrderCancelled(tenantId, bodyStr, shopDomain);
            }

        } finally {
            TenantContext.clear();
        }
    }

    private void handleOrderCancelled(UUID tenantId, String bodyStr, String shopDomain) {
        try {
            JsonNode payload = mapper.readTree(bodyStr);
            String externalId = payload.path("admin_graphql_api_id").asText(null);
            if (externalId == null || externalId.isBlank()) {
                log.warn("orders/cancelled webhook missing admin_graphql_api_id for shop {}", shopDomain);
                return;
            }

            // Resolve order by external_id + store
            UUID orderId = tx.execute(s ->
                jdbc.query(
                    "SELECT o.id FROM orders o " +
                    "JOIN stores st ON st.id = o.store_id " +
                    "WHERE o.external_id = ? AND o.tenant_id = ? AND st.shop_domain = ?",
                    rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                    externalId, tenantId, shopDomain));

            if (orderId == null) {
                log.debug("orders/cancelled for unknown externalId={} shop={}", externalId, shopDomain);
                return;
            }

            TenantContext.set(tenantId);
            try {
                FulfillService.CancelResult result = fulfillSvc.cancelOrder(orderId, null);
                log.info("Shopify cancel webhook: order {} → status={} remainingPacked={}",
                    orderId, result.status(), result.remainingPacked());
            } catch (ResponseStatusException e) {
                // Already cancelled / delivered / terminal — benign
                log.debug("orders/cancelled for already-terminal order {}: {}", orderId, e.getMessage());
            } finally {
                TenantContext.clear();
            }

        } catch (Exception e) {
            log.error("Failed to process orders/cancelled webhook for shop {}: {}", shopDomain, e.getMessage(), e);
        }
    }

    private static boolean verifyHmac(byte[] body, String secret, String expected) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String computed = Base64.getEncoder().encodeToString(mac.doFinal(body));
            return computed.equals(expected);
        } catch (Exception e) {
            return false;
        }
    }
}
