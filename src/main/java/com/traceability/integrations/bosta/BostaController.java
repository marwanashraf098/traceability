package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.CustomUserDetails;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class BostaController {

    private static final Logger log = LoggerFactory.getLogger(BostaController.class);

    private final BostaGateway      bostaGateway;
    private final EncryptionService encryptionService;
    private final JdbcTemplate      jdbc;
    private final ObjectMapper      mapper;
    private final JobScheduler      jobScheduler;
    private final BostaWebhookJob   webhookJob;
    private final TransactionTemplate tx;

    public BostaController(BostaGateway bostaGateway,
                            EncryptionService encryptionService,
                            JdbcTemplate jdbc,
                            ObjectMapper mapper,
                            JobScheduler jobScheduler,
                            BostaWebhookJob webhookJob,
                            PlatformTransactionManager txm) {
        this.bostaGateway      = bostaGateway;
        this.encryptionService = encryptionService;
        this.jdbc              = jdbc;
        this.mapper            = mapper;
        this.jobScheduler      = jobScheduler;
        this.webhookJob        = webhookJob;
        this.tx                = new TransactionTemplate(txm);
    }

    // ---- Request / response records ----------------------------------------

    public record BostaConnectRequest(String apiKey) {}

    /**
     * webhookSecret: 64-char hex string (32 raw bytes). Shown ONCE — never retrievable.
     * Caller must configure this as the Authorization value in Bosta's webhook settings.
     */
    public record BostaConnectResponse(String accountId, String webhookSecret) {}

    // ---- POST /api/v1/bosta/connect ----------------------------------------

    /**
     * Validates the Bosta API key, encrypts it, and persists a courier_accounts row.
     * Generates a 32-byte CSPRNG webhook secret; returns the raw hex once — only the
     * SHA-256 hash is stored. Mode B only — no delivery creation in scope.
     */
    @PostMapping("/bosta/connect")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('OWNER')")
    public BostaConnectResponse connect(
            @RequestBody BostaConnectRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {

        String businessName = bostaGateway.fetchBusinessProfile(req.apiKey());
        log.info("Bosta connect: tenant={} business={}", principal.tenantId(), businessName);

        // Generate CSPRNG webhook secret: 32 bytes → 64 hex chars
        byte[] rawBytes = new byte[32];
        new SecureRandom().nextBytes(rawBytes);
        String rawHex      = HexFormat.of().formatHex(rawBytes);     // returned to caller once
        String storedHash  = sha256Hex(rawHex);                       // stored in DB

        String encryptedApiKey = encryptionService.encrypt(req.apiKey());
        UUID tenantId = principal.tenantId();

        UUID accountId = TenantContext.runAs(tenantId, () ->
            tx.execute(s -> {
                // UPSERT: re-connecting replaces the API key and regenerates the webhook secret
                UUID id = jdbc.query("""
                    INSERT INTO courier_accounts
                        (tenant_id, provider, api_key_encrypted, webhook_secret, status)
                    VALUES (?, 'bosta', ?, ?, 'active')
                    ON CONFLICT DO NOTHING
                    RETURNING id
                    """,
                    rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                    tenantId, encryptedApiKey, storedHash);

                if (id == null) {
                    // Row existed (ON CONFLICT DO NOTHING returned nothing) — update it
                    id = jdbc.query("""
                        UPDATE courier_accounts
                        SET api_key_encrypted = ?, webhook_secret = ?, status = 'active'
                        WHERE tenant_id = ? AND provider = 'bosta'
                        RETURNING id
                        """,
                        rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                        encryptedApiKey, storedHash, tenantId);
                }
                return id;
            }));

        return new BostaConnectResponse(accountId.toString(), rawHex);
    }

    // ---- POST /api/v1/webhooks/bosta (public — no JWT) ----------------------

    /**
     * Receives Bosta delivery state webhooks.
     *
     * Security:
     *   - Authorization: Bearer {rawWebhookSecret} in request header
     *   - Secret resolved to tenant via resolve_tenant_by_webhook_secret() SECURITY DEFINER
     *   - No HMAC from Bosta — verify-by-fetch is the authenticity guarantee
     *
     * Idempotency:
     *   - Raw payload persisted immediately; 200 returned before any processing
     *   - Processing is async via BostaWebhookJob
     *   - external_event_id (SHA-256 of trackingNumber+state+timestamp) deduplicates redeliveries
     *
     * This endpoint is in SecurityConfig.permitAll() — JWT filter is bypassed.
     */
    @PostMapping("/webhooks/bosta")
    public ResponseEntity<Void> bostaWebhook(
            @RequestBody(required = false) JsonNode payload,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        // 1. Extract secret from Authorization header
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String rawSecret = authHeader.substring(7);

        // 2. Resolve tenant via SECURITY DEFINER function (no GUC set yet — that's the point)
        UUID tenantId = jdbc.query(
            "SELECT resolve_tenant_by_webhook_secret(?)",
            rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            rawSecret);

        if (tenantId == null) {
            log.warn("Bosta webhook: unknown secret (first 8 chars: {}...)",
                rawSecret.length() >= 8 ? rawSecret.substring(0, 8) : rawSecret);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // 3. Persist raw payload BEFORE any processing — return 200 immediately
        if (payload == null) {
            return ResponseEntity.ok().build();
        }

        String payloadJson;
        try { payloadJson = mapper.writeValueAsString(payload); }
        catch (Exception e) { return ResponseEntity.badRequest().build(); }

        final String fPayload = payloadJson;
        final UUID fTenantId  = tenantId;

        long webhookEventId = TenantContext.runAs(fTenantId, () ->
            tx.execute(s -> {
                Long id = jdbc.query("""
                    INSERT INTO webhook_events
                        (source, tenant_id, topic, payload, status, received_at)
                    VALUES ('bosta', ?, 'delivery_update', ?::jsonb, 'pending', now())
                    RETURNING id
                    """,
                    rs -> rs.next() ? rs.getLong("id") : null,
                    fTenantId, fPayload);
                if (id == null) throw new RuntimeException("webhook_events INSERT returned no id");
                return id;
            }));

        // 4. Enqueue async processing (fire-and-forget; 200 already implicit)
        jobScheduler.enqueue(() -> webhookJob.process(webhookEventId, fTenantId));

        return ResponseEntity.ok().build();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
