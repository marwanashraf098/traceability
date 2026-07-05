package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.CustomUserDetails;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.JobId;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class BostaController {

    private static final Logger log = LoggerFactory.getLogger(BostaController.class);

    private final BostaGateway        bostaGateway;
    private final EncryptionService   encryptionService;
    private final JdbcTemplate        jdbc;
    private final ObjectMapper        mapper;
    private final JobScheduler        jobScheduler;
    private final BostaWebhookJob     webhookJob;
    private final BostaBackfillJob    backfillJob;
    private final TransactionTemplate tx;
    private final BostaAwbService     awbService;
    private final BostaPickupService  pickupService;
    private final int                 defaultBackfillMaxPages;

    public BostaController(BostaGateway bostaGateway,
                            EncryptionService encryptionService,
                            JdbcTemplate jdbc,
                            ObjectMapper mapper,
                            JobScheduler jobScheduler,
                            BostaWebhookJob webhookJob,
                            BostaBackfillJob backfillJob,
                            PlatformTransactionManager txm,
                            BostaAwbService awbService,
                            BostaPickupService pickupService,
                            @Value("${bosta.backfill.max-pages:20}") int defaultBackfillMaxPages) {
        this.bostaGateway           = bostaGateway;
        this.encryptionService      = encryptionService;
        this.jdbc                   = jdbc;
        this.mapper                 = mapper;
        this.jobScheduler           = jobScheduler;
        this.webhookJob             = webhookJob;
        this.backfillJob            = backfillJob;
        this.tx                     = new TransactionTemplate(txm);
        this.awbService             = awbService;
        this.pickupService          = pickupService;
        this.defaultBackfillMaxPages = defaultBackfillMaxPages;
    }

    // ---- Request / response records ----------------------------------------

    public record BostaConnectRequest(String apiKey) {}
    public record SyncRequest(@Nullable Integer maxPages) {}
    public record BostaSettingsRequest(
        @Nullable String pickupMode,
        @Nullable String pickupBusinessLocationId,
        @Nullable JsonNode contactPerson,
        @Nullable String awbFormat,
        @Nullable String awbLang) {}
    public record AwbPrintRequest(
        List<UUID> shipmentIds,
        @Nullable String format,
        @Nullable String lang) {}
    public record PickupScheduleRequest(String scheduledDate) {}

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

        // Trigger one-time backfill for historical deliveries (async, fire-and-forget).
        // Runs after the account is persisted so the job can find the api_key_encrypted row.
        final UUID backfillTenantId = tenantId;
        jobScheduler.enqueue(() -> backfillJob.run(backfillTenantId, defaultBackfillMaxPages));

        return new BostaConnectResponse(accountId.toString(), rawHex);
    }

    // ---- POST /api/v1/bosta/sync (OWNER — manual re-sync) -------------------

    /**
     * Enqueues a backfill job on demand. Safe to re-run — backfill is idempotent
     * because the idem key dedup in BostaWebhookJob deduplicates repeated events for
     * the same (trackingNumber, state, updatedAt) tuple.
     */
    @PostMapping("/bosta/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('OWNER')")
    public Map<String, String> syncDeliveries(
            @RequestBody(required = false) SyncRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {

        UUID tenantId = principal.tenantId();
        int maxPages = (req != null && req.maxPages() != null && req.maxPages() > 0)
            ? req.maxPages() : defaultBackfillMaxPages;
        JobId jobId = jobScheduler.enqueue(() -> backfillJob.run(tenantId, maxPages));
        Map<String, String> resp = new LinkedHashMap<>();
        resp.put("jobId",   jobId != null ? jobId.asUUID().toString() : "enqueued");
        resp.put("message", "Backfill enqueued — " + maxPages + " pages max");
        return resp;
    }

    // ---- GET /api/v1/bosta/sync/status (OWNER) ------------------------------

    /**
     * Returns the last backfill run metadata: when it ran, how many deliveries were
     * seen and how many webhook_events rows were created.
     */
    @GetMapping("/bosta/sync/status")
    @PreAuthorize("hasRole('OWNER')")
    public Map<String, Object> syncStatus(@AuthenticationPrincipal CustomUserDetails principal) {
        UUID tenantId = principal.tenantId();
        return TenantContext.runAs(tenantId, () ->
            tx.execute(s -> jdbc.query(
                "SELECT last_backfill_at, last_backfill_total, last_backfill_enqueued " +
                "FROM courier_accounts " +
                "WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active' LIMIT 1",
                rs -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    if (!rs.next()) {
                        result.put("lastBackfillAt",       null);
                        result.put("lastBackfillTotal",    0);
                        result.put("lastBackfillEnqueued", 0);
                        return result;
                    }
                    var ts = rs.getTimestamp("last_backfill_at");
                    result.put("lastBackfillAt",       ts != null ? ts.toInstant().toString() : null);
                    result.put("lastBackfillTotal",    rs.getInt("last_backfill_total"));
                    result.put("lastBackfillEnqueued", rs.getInt("last_backfill_enqueued"));
                    return result;
                },
                tenantId)));
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
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request) {

        // TEMPORARY diagnostic — log every hit before any auth so we can tell if Bosta
        // is reaching the app at all (vs. being blocked at Cloudflare/nginx).
        // Remove once the webhook delivery path is confirmed working end-to-end.
        log.warn("[BOSTA-WH-HIT] method={} path={} hasAuth={} remoteAddr={} userAgent={}",
            request.getMethod(), request.getRequestURI(), authHeader != null,
            request.getRemoteAddr(), request.getHeader("User-Agent"));

        // 1. Extract secret from Authorization header.
        // Bosta may send "Bearer {secret}" or the raw secret depending on how the
        // webhook "Authorization header" field was configured in the Bosta dashboard.
        // Strip one "Bearer " prefix if present (case-insensitive, tolerates extra
        // whitespace); accept the raw secret without any prefix so either form works.
        if (authHeader == null || authHeader.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String rawSecret = authHeader.trim();
        if (rawSecret.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            rawSecret = rawSecret.substring(7).stripLeading();
        }
        if (rawSecret.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

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

    // ---- PUT /api/v1/bosta/settings ----------------------------------------

    /**
     * Updates Bosta-specific tenant settings on the active courier_accounts row.
     * All fields are optional; only non-null fields are updated (COALESCE pattern).
     * Intended to be called after /bosta/connect to configure pickup and label preferences.
     * Onboarding UI must collect: pickupBusinessLocationId, contactPerson, pickupMode.
     */
    @PutMapping("/bosta/settings")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('OWNER')")
    public void updateSettings(
            @RequestBody BostaSettingsRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {

        UUID tenantId = principal.tenantId();
        String contactPersonJson = null;
        if (req.contactPerson() != null) {
            try { contactPersonJson = mapper.writeValueAsString(req.contactPerson()); }
            catch (Exception e) { throw new RuntimeException("Failed to serialize contactPerson", e); }
        }
        final String cpJson = contactPersonJson;

        TenantContext.runAs(tenantId, () -> tx.execute(s -> {
            jdbc.update("""
                UPDATE courier_accounts SET
                    pickup_mode                 = COALESCE(?, pickup_mode),
                    pickup_business_location_id = COALESCE(?, pickup_business_location_id),
                    contact_person              = COALESCE(?::jsonb, contact_person),
                    awb_format                  = COALESCE(?, awb_format),
                    awb_lang                    = COALESCE(?, awb_lang)
                WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active'
                """,
                req.pickupMode(), req.pickupBusinessLocationId(),
                cpJson, req.awbFormat(), req.awbLang(),
                tenantId);
            return null;
        }));
    }

    // ---- POST /api/v1/bosta/awb/print --------------------------------------

    /**
     * Print AWB labels for the given shipment IDs.
     *
     * Pre-filters non-printable shipments (terminal state, unlinked, CRP/CASH_COLLECTION type)
     * and routes them to the missing-AWB exception. Calls Bosta mass-awb in ≤50-item batches.
     *
     * Response:
     *   pdfBase64List  — one base64-encoded PDF per batch (usually 1 for pilot ≤50 shipments)
     *   emailMessage   — set if Bosta returned the async email-path response
     *   exceptions     — tracking numbers excluded from printing + reason codes
     */
    @PostMapping("/bosta/awb/print")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','WORKER')")
    public BostaAwbService.AwbBatchResult printAwb(
            @RequestBody AwbPrintRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {

        if (req.shipmentIds() == null || req.shipmentIds().isEmpty()) {
            return new BostaAwbService.AwbBatchResult(List.of(), null, List.of());
        }
        return awbService.printAwb(
            principal.tenantId(), req.shipmentIds(), req.format(), req.lang());
    }

    // ---- POST /api/v1/bosta/pickup/schedule --------------------------------

    /**
     * Schedule a pickup for the given date and return the internal manifest.
     *
     * pickup_mode = BOSTA_MANAGED: skips createPickup; only generates manifest.
     * pickup_mode = TRACED_MANAGED: calls Bosta createPickup, handles already-exists gracefully.
     *
     * Always returns a manifest of today's awaiting_pickup shipments with COD.
     */
    @PostMapping("/bosta/pickup/schedule")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public BostaPickupService.PickupManifest schedulePickup(
            @RequestBody PickupScheduleRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {

        LocalDate date;
        try { date = LocalDate.parse(req.scheduledDate()); }
        catch (Exception e) {
            throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.BAD_REQUEST, "scheduledDate must be YYYY-MM-DD");
        }
        return pickupService.schedulePickup(principal.tenantId(), date);
    }

    // ---- GET /api/v1/bosta/pickup/manifest/{pickupId} ----------------------

    /** Re-fetch the manifest for a previously scheduled pickup. */
    @GetMapping("/bosta/pickup/manifest/{pickupId}")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','WORKER')")
    public BostaPickupService.PickupManifest getManifest(
            @PathVariable UUID pickupId,
            @AuthenticationPrincipal CustomUserDetails principal) {

        return pickupService.getManifest(principal.tenantId(), pickupId);
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
