package com.traceability.integrations.bosta;

import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * AWB label printing via Bosta mass-awb endpoint.
 *
 * Pre-filters shipments before calling Bosta:
 *   UNLINKED            — shipment has no order_id (only matched deliveries get AWBs)
 *   NON_PRINTABLE_STATE — terminal states: delivered, returned, lost, terminated, cancelled
 *   NON_PRINTABLE_TYPE  — Bosta delivery type CRP or CASH_COLLECTION
 *
 * Exclusions are written to shipments.awb_print_failed_reason so the exceptions
 * center can surface them as missing_awb exceptions.
 *
 * Batching: ≤50 tracking numbers per Bosta call (their inline-PDF limit).
 * Results from multiple batches are returned as a list of base64 strings so
 * the caller can print each in sequence.
 */
@Service
public class BostaAwbService {

    private static final Logger log = LoggerFactory.getLogger(BostaAwbService.class);
    static final int BATCH_SIZE = 50;

    // Shipment internal states that are terminal / already-done — no AWB reprinting needed.
    // Note: "awaiting_pickup" is an order_status, NOT a shipment_internal_state; shipments
    // remain in 'created' while the order awaits courier pickup.
    private static final Set<String> NON_PRINTABLE_STATES = Set.of(
        "delivered", "returned", "returning", "lost", "terminated", "cancelled");

    private static final Set<String> NON_PRINTABLE_TYPES = Set.of("CRP", "CASH_COLLECTION");

    private final JdbcTemplate      jdbc;
    private final TransactionTemplate tx;
    private final BostaGateway       bostaGateway;
    private final EncryptionService  encryptionService;

    public BostaAwbService(JdbcTemplate jdbc,
                            PlatformTransactionManager txm,
                            BostaGateway bostaGateway,
                            EncryptionService encryptionService) {
        this.jdbc             = jdbc;
        this.tx               = new TransactionTemplate(txm);
        this.bostaGateway     = bostaGateway;
        this.encryptionService = encryptionService;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public record AwbException(String trackingNumber, String reason) {}

    /**
     * Result of a print request.
     *
     * pdfBase64List: one entry per successful batch (usually 1 for pilot ≤50 shipments).
     * emailMessage:  non-null if Bosta returned an email-path response for any batch.
     * exceptions:    tracking numbers excluded from printing + reason codes.
     */
    public record AwbBatchResult(
        List<String> pdfBase64List,
        String emailMessage,
        List<AwbException> exceptions
    ) {}

    /**
     * Print AWB labels for the given shipment IDs.
     *
     * formatOverride / langOverride: if null, falls back to tenant's awb_format / awb_lang
     * from courier_accounts.
     */
    public AwbBatchResult printAwb(UUID tenantId, List<UUID> shipmentIds,
                                    String formatOverride, String langOverride) {

        // 1. Load tenant's API key + label settings
        Map<String, Object> account = TenantContext.runAs(tenantId, () ->
            tx.execute(s -> jdbc.query(
                "SELECT api_key_encrypted, awb_format, awb_lang " +
                "FROM courier_accounts " +
                "WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active' LIMIT 1",
                rs -> rs.next()
                    ? Map.<String, Object>of(
                        "api_key_encrypted", rs.getString("api_key_encrypted"),
                        "awb_format",        rs.getString("awb_format"),
                        "awb_lang",          rs.getString("awb_lang"))
                    : null,
                tenantId)));

        if (account == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No active Bosta account");
        }

        String apiKey = encryptionService.decrypt((String) account.get("api_key_encrypted"));
        String format = formatOverride != null ? formatOverride : (String) account.get("awb_format");
        String lang   = langOverride   != null ? langOverride   : (String) account.get("awb_lang");
        if (format == null) format = "A4";
        if (lang   == null) lang   = "ar";

        // 2. Load shipments (RLS enforced by TenantContext)
        final List<UUID> ids = shipmentIds;
        if (ids.isEmpty()) return new AwbBatchResult(List.of(), null, List.of());

        String placeholders = ids.stream().map(id -> "?::uuid").collect(Collectors.joining(","));
        Object[] params = Stream.concat(ids.stream(), Stream.of(tenantId)).toArray();

        List<Map<String, Object>> rows = TenantContext.runAs(tenantId, () ->
            tx.execute(s -> jdbc.queryForList(
                "SELECT id, tracking_number, internal_state::text AS internal_state, " +
                "       order_id, raw->>'type' AS delivery_type " +
                "FROM shipments WHERE id IN (" + placeholders + ") AND tenant_id = ?",
                params)));

        // 3. Pre-filter: separate printable from non-printable
        List<String>       printable  = new ArrayList<>();
        List<AwbException> exceptions = new ArrayList<>();

        // Build an id→tracking map for Bosta-rejection lookups later
        Map<String, UUID> trackingToId = new HashMap<>();

        for (Map<String, Object> row : rows) {
            UUID   id       = (UUID)   row.get("id");
            String tracking = (String) row.get("tracking_number");
            String state    = (String) row.get("internal_state");
            String type     = (String) row.get("delivery_type");
            Object orderId  =          row.get("order_id");

            String exclusionReason = null;

            if (orderId == null) {
                exclusionReason = "UNLINKED";
            } else if (state != null && NON_PRINTABLE_STATES.contains(state)) {
                exclusionReason = "NON_PRINTABLE_STATE:" + state;
            } else if (type != null && NON_PRINTABLE_TYPES.contains(type)) {
                exclusionReason = "NON_PRINTABLE_TYPE:" + type;
            }

            if (exclusionReason != null) {
                exceptions.add(new AwbException(tracking, exclusionReason));
                markFailed(tenantId, id, exclusionReason);
                log.debug("AWB excluded: tracking={} reason={}", tracking, exclusionReason);
            } else if (tracking != null) {
                printable.add(tracking);
                if (id != null) trackingToId.put(tracking, id);
            }
        }

        if (printable.isEmpty()) {
            return new AwbBatchResult(List.of(), null, exceptions);
        }

        // 4. Batch into ≤50 chunks, call Bosta for each
        List<String> pdfBase64List = new ArrayList<>();
        String emailMessage = null;

        for (int i = 0; i < printable.size(); i += BATCH_SIZE) {
            List<String> chunk = printable.subList(i, Math.min(i + BATCH_SIZE, printable.size()));
            try {
                AwbPrintResult result = bostaGateway.printMassAwb(apiKey, chunk, format, lang);
                if (result.isInline()) {
                    pdfBase64List.add(Base64.getEncoder().encodeToString(result.pdfBytes()));
                } else {
                    // Bosta went async — surface message, treat chunk as un-printable
                    emailMessage = result.emailMessage();
                    log.info("mass-awb returned email-path for chunk of {} trackings", chunk.size());
                }
            } catch (BostaException e) {
                // Bosta rejected the entire chunk — route each to missing-AWB exception.
                // TODO (gate-c FR-7.8): if rejection reason indicates a blocked consignee,
                //   raise blocked_customer exception + offer to add phone to blocklist
                //   (source=bosta_rejected). Deferred — wire when Mode-A / AWB-create
                //   hits Bosta and we can reliably extract the rejection cause code.
                String rejectedReason = "BOSTA_REJECTED:" + truncate(e.getMessage(), 120);
                log.warn("mass-awb rejected chunk of {} trackings: {}", chunk.size(), e.getMessage());
                for (String tn : chunk) {
                    exceptions.add(new AwbException(tn, rejectedReason));
                    UUID sid = trackingToId.get(tn);
                    if (sid != null) markFailed(tenantId, sid, rejectedReason);
                }
            }
        }

        return new AwbBatchResult(pdfBase64List, emailMessage, exceptions);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void markFailed(UUID tenantId, UUID shipmentId, String reason) {
        TenantContext.runAs(tenantId, () ->
            tx.execute(s -> {
                jdbc.update(
                    "UPDATE shipments " +
                    "SET awb_print_failed_reason = ?, awb_print_failed_at = now() " +
                    "WHERE id = ? AND tenant_id = ?",
                    reason, shipmentId, tenantId);
                return null;
            }));
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "…";
    }
}
