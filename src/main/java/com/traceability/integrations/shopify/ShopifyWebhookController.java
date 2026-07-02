package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.security.EncryptionService;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import com.traceability.tenancy.TenantContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Receives Shopify webhooks at POST /webhooks/shopify/{type}/{action}.
 *
 * Security spine (invariant #4: persist raw → ack 200 → process async → idempotent):
 *   1. Verify HMAC-SHA256 over RAW body bytes using the global app client secret.
 *      Raw bytes means @RequestBody byte[] — NOT a typed DTO that triggers Jackson re-serialization.
 *      Re-serialization changes whitespace, key ordering etc., producing a different digest.
 *      This is the #1 Spring-Shopify webhook bug (see Part F Test 1 which proves the contract).
 *   2. Resolve tenant via resolve_tenant_by_shop_domain DEFINER hatch (no GUC required).
 *      Unknown shop → 200 ack and drop — a non-2xx causes Shopify to retry forever.
 *   3. INSERT into shopify_webhook_events under the tenant GUC. UNIQUE on webhook_id
 *      provides idempotency across Shopify retries.
 *   4. ACK 200 immediately — never block the HTTP response on processing.
 *   5. Enqueue ShopifyWebhookProcessorJob to handle the event asynchronously.
 *
 * GDPR endpoints (customers/data_request, customers/redact, shop/redact) use the same
 * spine — they are declared in the Partner Dashboard, not registered via Part A's API call.
 */
@RestController
public class ShopifyWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ShopifyWebhookController.class);

    private static final String INSERT_EVENT = """
            INSERT INTO shopify_webhook_events
                (tenant_id, topic, shop_domain, webhook_id, payload_raw)
            VALUES (?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (webhook_id) DO NOTHING
            RETURNING id
            """;

    private static final String RESOLVE_TENANT =
        "SELECT resolve_tenant_by_shop_domain(?)";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final JobScheduler jobScheduler;
    private final ShopifyWebhookProcessorJob processorJob;
    private final TransactionTemplate tx;
    private final String clientSecret;
    private final EncryptionService encryptionService;

    public ShopifyWebhookController(JdbcTemplate jdbc,
                                     ObjectMapper mapper,
                                     JobScheduler jobScheduler,
                                     ShopifyWebhookProcessorJob processorJob,
                                     PlatformTransactionManager txm,
                                     @Value("${shopify.client-secret}") String clientSecret,
                                     EncryptionService encryptionService) {
        this.jdbc              = jdbc;
        this.mapper            = mapper;
        this.jobScheduler      = jobScheduler;
        this.processorJob      = processorJob;
        this.tx                = new TransactionTemplate(txm);
        this.clientSecret      = clientSecret;
        this.encryptionService = encryptionService;
    }

    @PostMapping("/webhooks/shopify/{type}/{action}")
    public ResponseEntity<Void> receive(
            @PathVariable String type,
            @PathVariable String action,
            @RequestHeader(value = "X-Shopify-Topic",       defaultValue = "") String topicHeader,
            @RequestHeader(value = "X-Shopify-Shop-Domain", defaultValue = "") String shopDomain,
            @RequestHeader(value = "X-Shopify-Hmac-Sha256", defaultValue = "") String hmacHeader,
            @RequestHeader(value = "X-Shopify-Webhook-Id",  defaultValue = "") String webhookId,
            @RequestBody byte[] rawBody) {

        // Step 1: Resolve tenant early via DEFINER (no GUC required).
        // Must happen before Phase B so the stores query can run under proper tenant context —
        // the webhook endpoint is unauthenticated and has no TenantContext on entry.
        UUID tenantId = resolveTenant(shopDomain);

        // Step 2: Two-phase HMAC over raw bytes — before ANY JSON parsing.
        // Phase A (hot path): global client-secret — covers all OAuth stores with zero overhead.
        // Phase B (custom-app path): only if Phase A fails; uses resolved tenantId to set GUC
        //   so the stores query (RLS-gated) can find the per-store api_secret_encrypted row.
        if (!ShopifyHmacUtil.verifyWebhookBody(rawBody, clientSecret, hmacHeader)) {
            if (!verifyCustomAppWebhook(tenantId, shopDomain, rawBody, hmacHeader)) {
                log.warn("Shopify webhook HMAC mismatch for shop={} topic={}/{}", shopDomain, type, action);
                return ResponseEntity.status(401).build();
            }
        }

        // Step 3: unknown shop — ack and drop (prevents infinite Shopify retry storm).
        String topic = type + "/" + action;
        if (tenantId == null) {
            log.debug("Shopify webhook from unknown shop={} topic={} — ack and drop", shopDomain, topic);
            return ResponseEntity.ok().build();
        }

        // Step 3: parse and persist.
        JsonNode payload;
        try {
            payload = mapper.readTree(rawBody);
        } catch (IOException e) {
            log.error("Shopify webhook body is not valid JSON: shop={} topic={}", shopDomain, topic);
            return ResponseEntity.ok().build(); // Ack; don't retry a malformed body
        }

        String payloadJson = payload.toString();
        String effectiveWebhookId = webhookId.isBlank() ? generateFallbackId(topic, shopDomain, payloadJson) : webhookId;

        UUID eventId = insertEvent(tenantId, topic, shopDomain, effectiveWebhookId, payloadJson);
        if (eventId == null) {
            // ON CONFLICT DO NOTHING — already received this webhook_id.
            log.debug("Duplicate Shopify webhook id={} shop={} topic={} — ack and skip", webhookId, shopDomain, topic);
            return ResponseEntity.ok().build();
        }

        // Step 4 + 5: ACK 200 then enqueue async processor.
        // tenantId is passed so the processor can set the GUC immediately — without it
        // the first SELECT would run without a GUC, which RLS blocks to zero rows.
        final UUID finalTenantId = tenantId;
        jobScheduler.enqueue(() -> processorJob.process(eventId, finalTenantId));
        return ResponseEntity.ok().build();
    }

    // ---- helpers --------------------------------------------------------

    /**
     * Phase-B HMAC check for custom-app stores.
     *
     * Requires tenantId (resolved before this call via DEFINER) so the stores query can run
     * inside a transaction with TenantContext set. Without this, the webhook's unauthenticated
     * request has no GUC, RLS returns 0 rows from stores, and the lookup silently returns null
     * — causing Phase B to be skipped even when the store is present.
     *
     * Returns false if tenantId is null, the store is not custom_app, or HMAC doesn't match.
     */
    private boolean verifyCustomAppWebhook(UUID tenantId, String shopDomain, byte[] rawBody, String hmacHeader) {
        if (shopDomain == null || shopDomain.isBlank()) return false;
        if (tenantId == null) return false;
        try {
            // Run inside TenantContext.runAs + tx.execute so TenantAwareConnection fires
            // SET LOCAL app.current_tenant before the stores query, satisfying RLS.
            String encryptedSecret = TenantContext.runAs(tenantId, () ->
                tx.execute(status ->
                    jdbc.query(
                        "SELECT api_secret_encrypted FROM stores " +
                        "WHERE shop_domain = ? AND connection_type = 'custom_app' AND api_secret_encrypted IS NOT NULL",
                        rs -> rs.next() ? rs.getString(1) : null,
                        shopDomain)));

            if (encryptedSecret == null) return false;
            String perStoreSecret = encryptionService.decrypt(encryptedSecret);
            return ShopifyHmacUtil.verifyWebhookBody(rawBody, perStoreSecret, hmacHeader);
        } catch (Exception e) {
            log.warn("Error verifying custom-app webhook HMAC for shop={}: {}", shopDomain, e.getMessage());
            return false;
        }
    }

    private UUID resolveTenant(String shopDomain) {
        if (shopDomain.isBlank()) return null;
        try {
            return jdbc.queryForObject(RESOLVE_TENANT, UUID.class, shopDomain);
        } catch (Exception e) {
            return null;
        }
    }

    private UUID insertEvent(UUID tenantId, String topic, String shopDomain,
                              String webhookId, String payloadJson) {
        com.traceability.tenancy.TenantContext.set(tenantId);
        try {
            return tx.execute(s ->
                jdbc.query(INSERT_EVENT,
                    rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                    tenantId, topic, shopDomain, webhookId, payloadJson));
        } finally {
            com.traceability.tenancy.TenantContext.clear();
        }
    }

    private static String generateFallbackId(String topic, String shopDomain, String body) {
        // Only triggered when Shopify omits X-Shopify-Webhook-Id (shouldn't happen in production).
        return "fallback:" + topic + ":" + shopDomain + ":" + body.hashCode();
    }
}
