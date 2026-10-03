package com.traceability.integrations.bosta;

import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 * Batching: ≤49 tracking numbers per Bosta call — from 50 up, Bosta stops returning the PDF
 * inline and emails the labels instead (see BATCH_SIZE).
 * Results from multiple batches are returned as a list of base64 strings so
 * the caller can print each in sequence.
 */
@Service
public class BostaAwbService {

    private static final Logger log = LoggerFactory.getLogger(BostaAwbService.class);
    /** Bosta mass-awb answers with an inline PDF only below 50 tracking numbers; from 50 up it
     *  emails the labels instead. 49 keeps every request on the inline path, for every caller. */
    static final int BATCH_SIZE = 49;

    // Shipment internal states that are terminal / already-done — no AWB reprinting needed.
    // Note: "awaiting_pickup" is an order_status, NOT a shipment_internal_state; shipments
    // remain in 'created' while the order awaits courier pickup.
    private static final Set<String> NON_PRINTABLE_STATES = Set.of(
        "delivered", "returned", "returning", "lost", "terminated", "cancelled");

    // CRP (type.code=25) is excluded from AWB printing — it is a customer-return pickup,
    // not a forward delivery, and has no printable label.
    // TODO: CASH_COLLECTION exclusion is also broken (same raw->>'type' SQL bug) — defer,
    //       not in FR-12.6 scope. The code field for CASH_COLLECTION is not yet confirmed.
    private static final int CRP_TYPE_CODE = 25;

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
     * pdfBase64List: one entry per successful batch (≤{@link #BATCH_SIZE} shipments each).
     * emailMessage:  non-null if Bosta returned an email-path response for any batch.
     * exceptions:    tracking numbers excluded from printing + reason codes.
     */
    public record AwbBatchResult(
        List<String> pdfBase64List,
        String emailMessage,
        List<AwbException> exceptions
    ) {}

    /**
     * One Bosta mass-awb call: the tracking numbers sent (in send order) and what came back —
     * exactly one of {@code pdf} (inline PDF), {@code emailMessage} (Bosta went to its
     * email-instead-of-PDF path) or {@code rejectedReason} (Bosta rejected the chunk).
     */
    public record AwbChunk(List<String> trackingNumbers, byte[] pdf,
                           String emailMessage, String rejectedReason) {}

    /**
     * Per-chunk result of a print request. {@code exclusions} are the shipments the
     * pre-filter kept away from Bosta (unlinked, terminal state, CRP), in input order.
     * {@code format} is the paper actually requested (override or the account default).
     */
    public record AwbDetailedResult(List<AwbChunk> chunks, List<AwbException> exclusions,
                                    String format) {}

    /**
     * Print AWB labels for the given shipment IDs.
     *
     * formatOverride / langOverride: if null, falls back to tenant's awb_format / awb_lang
     * from courier_accounts. Contract unchanged by S2; it is now built on
     * {@link #printAwbDetailed}, so tracking numbers reach Bosta in the order the ids were
     * given (previously database order).
     */
    public AwbBatchResult printAwb(UUID tenantId, List<UUID> shipmentIds,
                                    String formatOverride, String langOverride) {
        AwbDetailedResult detailed = printAwbDetailed(tenantId, shipmentIds, formatOverride, langOverride);

        List<String>       pdfBase64List = new ArrayList<>();
        List<AwbException> exceptions    = new ArrayList<>(detailed.exclusions());
        String             emailMessage  = null;
        for (AwbChunk chunk : detailed.chunks()) {
            if (chunk.pdf() != null) {
                pdfBase64List.add(Base64.getEncoder().encodeToString(chunk.pdf()));
            } else if (chunk.emailMessage() != null) {
                emailMessage = chunk.emailMessage();
            } else {
                for (String tn : chunk.trackingNumbers()) {
                    exceptions.add(new AwbException(tn, chunk.rejectedReason()));
                }
            }
        }
        return new AwbBatchResult(pdfBase64List, emailMessage, exceptions);
    }

    /**
     * Print AWB labels for the given shipment IDs, keeping every chunk's own result.
     *
     * Send order: tracking numbers go to Bosta in the order {@code shipmentIds} lists them
     * (duplicates dropped, ids not visible to this tenant skipped), chunked by
     * {@link #BATCH_SIZE}. Bosta's own page order inside a returned PDF is not assumed.
     */
    public AwbDetailedResult printAwbDetailed(UUID tenantId, List<UUID> shipmentIds,
                                              String formatOverride, String langOverride) {

        // Review mode (V130): a simulated-courier tenant has no Bosta account and is never sent
        // to Bosta — its waybills are rendered locally, before the account lookup below (which
        // would throw NoBostaAccountException). Same result shape, so single and batch print
        // (PackPrintBatchService / WaybillPdfAssembler) work unchanged.
        boolean simulated = TenantContext.runAs(tenantId, () ->
            Boolean.TRUE.equals(tx.execute(s -> CourierSimulation.isSimulated(jdbc, tenantId))));
        if (simulated) return printSimulated(tenantId, shipmentIds, formatOverride);

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
            throw new NoBostaAccountException(
                "No active Bosta account for this store.",
                "لا يوجد حساب Bosta نشط لهذا المتجر.");
        }

        String apiKey = encryptionService.decrypt((String) account.get("api_key_encrypted"));
        String format = formatOverride != null ? formatOverride : (String) account.get("awb_format");
        String lang   = langOverride   != null ? langOverride   : (String) account.get("awb_lang");
        if (format == null) format = "A4";
        if (lang   == null) lang   = "ar";

        // 2–3. Load the shipments in the caller's order and pre-filter (shared with review mode)
        final List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(shipmentIds));
        if (ids.isEmpty()) return new AwbDetailedResult(List.of(), List.of(), format);
        Printable p = loadPrintable(tenantId, ids);
        List<String>       printable    = p.trackings();
        List<AwbException> exclusions   = p.exclusions();
        Map<String, UUID>  trackingToId = p.trackingToId();

        // 4. Batch into ≤BATCH_SIZE (49) chunks, call Bosta for each
        List<AwbChunk> chunks = new ArrayList<>();

        for (int i = 0; i < printable.size(); i += BATCH_SIZE) {
            List<String> chunk = List.copyOf(printable.subList(i, Math.min(i + BATCH_SIZE, printable.size())));
            try {
                AwbPrintResult result = bostaGateway.printMassAwb(apiKey, chunk, format, lang);
                if (result.isInline()) {
                    chunks.add(new AwbChunk(chunk, result.pdfBytes(), null, null));
                } else {
                    // Bosta went async — surface message, treat chunk as un-printable
                    chunks.add(new AwbChunk(chunk, null, result.emailMessage(), null));
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
                chunks.add(new AwbChunk(chunk, null, null, rejectedReason));
                for (String tn : chunk) {
                    UUID sid = trackingToId.get(tn);
                    if (sid != null) markFailed(tenantId, sid, rejectedReason);
                }
            }
        }

        return new AwbDetailedResult(chunks, exclusions, format);
    }

    // ── Review mode ───────────────────────────────────────────────────────────

    /**
     * Review mode: the simulated print. Same pre-filter as the Bosta path (loadPrintable), then
     * SimulatedWaybillRenderer per ≤BATCH_SIZE chunk instead of mass-awb — one page per tracking
     * number, in send order. Paper: the override, else A4 (there is no courier row to hold an
     * awb_format). Never touches BostaGateway.
     */
    private AwbDetailedResult printSimulated(UUID tenantId, List<UUID> shipmentIds, String formatOverride) {
        String format = "A6".equalsIgnoreCase(formatOverride) ? "A6" : "A4";
        final List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(shipmentIds));
        if (ids.isEmpty()) return new AwbDetailedResult(List.of(), List.of(), format);
        Printable p = loadPrintable(tenantId, ids);

        Map<String, SimulatedWaybillRenderer.Waybill> details = new HashMap<>();
        if (!p.trackings().isEmpty()) {
            List<UUID> printableIds = p.trackings().stream().map(p.trackingToId()::get).toList();
            String placeholders = printableIds.stream().map(id -> "?::uuid").collect(Collectors.joining(","));
            Object[] params = Stream.concat(printableIds.stream(), Stream.of(tenantId)).toArray();
            TenantContext.runAs(tenantId, () -> tx.execute(s -> {
                jdbc.query(
                    "SELECT s.tracking_number, o.number, o.customer_name, o.customer_phone, " +
                    "       concat_ws(', ', o.address ->> 'address1', o.address ->> 'city') AS address_line, " +
                    "       COALESCE(s.cod_amount, o.cod_amount) AS cod " +
                    "FROM shipments s JOIN orders o ON o.id = s.order_id AND o.tenant_id = s.tenant_id " +
                    "WHERE s.id IN (" + placeholders + ") AND s.tenant_id = ?",
                    rs -> {
                        String address = rs.getString("address_line");
                        details.put(rs.getString("tracking_number"), new SimulatedWaybillRenderer.Waybill(
                            rs.getString("tracking_number"), rs.getString("number"),
                            rs.getString("customer_name"), rs.getString("customer_phone"),
                            address == null || address.isBlank() ? null : address,
                            rs.getBigDecimal("cod")));
                    },
                    params);
                return null;
            }));
        }

        List<AwbChunk> chunks = new ArrayList<>();
        for (int i = 0; i < p.trackings().size(); i += BATCH_SIZE) {
            List<String> chunk = List.copyOf(p.trackings().subList(i, Math.min(i + BATCH_SIZE, p.trackings().size())));
            List<SimulatedWaybillRenderer.Waybill> pages = chunk.stream()
                .map(tn -> details.getOrDefault(tn, new SimulatedWaybillRenderer.Waybill(tn, null, null, null, null, null)))
                .toList();
            chunks.add(new AwbChunk(chunk, SimulatedWaybillRenderer.render(pages, format), null, null));
        }
        log.info("Review mode: rendered {} simulated waybill(s) for tenant {}", p.trackings().size(), tenantId);
        return new AwbDetailedResult(chunks, p.exclusions(), format);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Printable tracking numbers (caller's order), their shipment ids, and the exclusions. */
    private record Printable(List<String> trackings, Map<String, UUID> trackingToId,
                             List<AwbException> exclusions) {}

    /**
     * Steps 2–3 of a print, shared by the Bosta path and review mode's simulated path so both
     * apply the exact same pre-filter: load the shipments (RLS enforced by TenantContext) in the
     * caller's order, exclude unlinked / terminal-state / CRP ones (marking them failed).
     */
    private Printable loadPrintable(UUID tenantId, List<UUID> ids) {
        String placeholders = ids.stream().map(id -> "?::uuid").collect(Collectors.joining(","));
        Object[] params = Stream.concat(ids.stream(), Stream.of(tenantId)).toArray();

        List<Map<String, Object>> loaded = TenantContext.runAs(tenantId, () ->
            tx.execute(s -> jdbc.queryForList(
                // raw->>'type' would return the JSON object as text — never "CRP".
                // Extract the numeric code instead: (raw->'type'->>'code')::int
                "SELECT id, tracking_number, internal_state::text AS internal_state, " +
                "       order_id, (raw -> 'type' ->> 'code')::int AS delivery_type_code " +
                "FROM shipments WHERE id IN (" + placeholders + ") AND tenant_id = ?",
                params)));

        // IN (...) returns rows in no particular order — put them back in the caller's order
        // so the send order to Bosta is the order asked for (Step 0 finding 4).
        Map<UUID, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> row : loaded) byId.put((UUID) row.get("id"), row);
        List<Map<String, Object>> rows = ids.stream()
            .map(byId::get).filter(Objects::nonNull).toList();

        // 3. Pre-filter: separate printable from non-printable
        List<String>       printable  = new ArrayList<>();
        List<AwbException> exclusions = new ArrayList<>();

        // Build an id→tracking map for Bosta-rejection lookups later
        Map<String, UUID> trackingToId = new HashMap<>();

        for (Map<String, Object> row : rows) {
            UUID   id             = (UUID)    row.get("id");
            String tracking       = (String)  row.get("tracking_number");
            String state          = (String)  row.get("internal_state");
            Number typeCodeNum    = (Number)  row.get("delivery_type_code");
            int    delivTypeCode  = typeCodeNum != null ? typeCodeNum.intValue() : -1;
            Object orderId        =            row.get("order_id");

            String exclusionReason = null;

            if (orderId == null) {
                exclusionReason = "UNLINKED";
            } else if (state != null && NON_PRINTABLE_STATES.contains(state)) {
                exclusionReason = "NON_PRINTABLE_STATE:" + state;
            } else if (delivTypeCode == CRP_TYPE_CODE) {
                exclusionReason = "NON_PRINTABLE_TYPE:CRP";
            }

            if (exclusionReason != null) {
                exclusions.add(new AwbException(tracking, exclusionReason));
                markFailed(tenantId, id, exclusionReason);
                log.debug("AWB excluded: tracking={} reason={}", tracking, exclusionReason);
            } else if (tracking != null) {
                printable.add(tracking);
                if (id != null) trackingToId.put(tracking, id);
            }
        }

        return new Printable(printable, trackingToId, exclusions);
    }


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
