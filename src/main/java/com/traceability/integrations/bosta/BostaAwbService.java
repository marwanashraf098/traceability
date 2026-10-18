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
 * center can surface them as missing_awb exceptions — except DELETED_IN_BOSTA (provider_not_found_at
 * set): never sent to Bosta, reported as skipped, not marked failed.
 *
 * A chunk Bosta rejects with a per-waybill 400 ("Couldn't find deliveries…", "…can't be printed…") is
 * isolated (see isolate()): the bad waybills are reported and marked, the rest printed.
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
        // A waybill an earlier print already isolated as not found in Bosta goes in its own request, after
        // the others: the rest of a reprint then holds at most the culprits not yet found, so "print again"
        // converges (a second unnamed culprit is bisected within the cap on the next run).
        List<String>       printable    = new ArrayList<>(p.trackings());
        printable.sort(Comparator.comparing(p.suspects()::contains));   // stable: order kept within each group
        int fresh = (int) printable.stream().filter(tn -> !p.suspects().contains(tn)).count();
        List<AwbException> exclusions   = p.exclusions();
        Map<String, UUID>  trackingToId = p.trackingToId();

        // 4. Batch into ≤BATCH_SIZE (49) chunks, call Bosta for each
        List<AwbChunk> chunks = new ArrayList<>();

        List<List<String>> requests = new ArrayList<>();
        for (List<String> group : List.of(printable.subList(0, fresh), printable.subList(fresh, printable.size()))) {
            for (int i = 0; i < group.size(); i += BATCH_SIZE) {
                requests.add(List.copyOf(group.subList(i, Math.min(i + BATCH_SIZE, group.size()))));
            }
        }
        for (List<String> chunk : requests) {
            AwbChunk whole;
            try {
                whole = toChunk(chunk, bostaGateway.printMassAwb(apiKey, chunk, format, lang));
            } catch (BostaException e) {
                Isolation kind = isolationKind(e);
                if (kind != null) {
                    // One (or a few) waybills Bosta can't find / won't print: isolate them, print the rest.
                    chunks.addAll(isolate(tenantId, apiKey, chunk, format, lang, trackingToId,
                        (BostaAwbRejectedException) e, kind));
                    continue;
                }
                // Bosta rejected the entire chunk — route each to missing-AWB exception.
                // (429 / 5xx / network / any other error: unchanged, never isolated.)
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
                continue;
            }
            chunks.add(whole);
        }

        return new AwbDetailedResult(chunks, exclusions, format);
    }

    // ── Isolating a bad waybill (deleted-in-Bosta workstream, slice 1) ───────

    /** Reported for a waybill Bosta can't find (deleted in Bosta): skipped before the call, or isolated. */
    public static final String DELETED_IN_BOSTA = "DELETED_IN_BOSTA";

    /**
     * Not printed because Bosta refused part of the chunk and the bad waybill(s) couldn't be pinned down
     * within {@link #MAX_ISOLATION_CALLS}, or another error stopped the isolation. Nobody is marked failed —
     * the set may hold innocent waybills; printing again retries them.
     */
    public static final String BOSTA_REJECTED_UNISOLATED = "BOSTA_REJECTED_UNISOLATED";

    /**
     * Extra mass-awb calls allowed per rejected chunk: 6 bisect one unnamed bad waybill out of 49
     * (⌈log2 49⌉), 1 prints what's left, 1 spare (or the hint retry when Bosta names a wrong number).
     * A correct hint costs one call. Two unnamed bad waybills may exceed it in one run; the one found is
     * marked and goes in its own request on the next print, which then finds the other.
     */
    static final int MAX_ISOLATION_CALLS = 8;

    /** Bosta's per-waybill 400s — the only rejections that are isolated. */
    enum Isolation { NOT_FOUND, NOT_PRINTABLE }

    private static final java.util.regex.Pattern NOT_FOUND_MESSAGE =
        java.util.regex.Pattern.compile("(couldn\\W?t|could not) find deliver", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern NOT_PRINTABLE_MESSAGE =
        java.util.regex.Pattern.compile("(can\\W?t|cannot|can not) be printed", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern DIGITS = java.util.regex.Pattern.compile("\\d+");

    static Isolation isolationKind(BostaException e) {
        if (!(e instanceof BostaAwbRejectedException r) || r.httpStatus() != 400) return null;
        String m = r.bostaMessage() == null ? "" : r.bostaMessage();
        if (NOT_FOUND_MESSAGE.matcher(m).find()) return Isolation.NOT_FOUND;
        if (NOT_PRINTABLE_MESSAGE.matcher(m).find()) return Isolation.NOT_PRINTABLE;
        return null;
    }

    /** The tracking numbers of {@code sent} that Bosta's message names (whole digit runs only). */
    static Set<String> namedIn(BostaAwbRejectedException e, Collection<String> sent) {
        Set<String> named = new LinkedHashSet<>();
        if (e == null || e.bostaMessage() == null) return named;
        Set<String> wanted = new HashSet<>(sent);
        java.util.regex.Matcher m = DIGITS.matcher(e.bostaMessage());
        while (m.find()) if (wanted.contains(m.group())) named.add(m.group());
        return named;
    }

    private static AwbChunk toChunk(List<String> trackings, AwbPrintResult result) {
        if (result.isInline()) return new AwbChunk(trackings, result.pdfBytes(), null, null);
        // Bosta went async — surface message, treat chunk as un-printable
        log.info("mass-awb returned email-path for chunk of {} trackings", trackings.size());
        return new AwbChunk(trackings, null, result.emailMessage(), null);
    }

    /**
     * {@code chunk} was rejected with a per-waybill 400. Finds the bad waybill(s) and prints the rest:
     * tracking numbers named in Bosta's message (and in this chunk) are hints only — the chunk is reprinted
     * once without them; printed → the culprit is among them, refused → hints are dropped for the rest of
     * the isolation and the hinted waybills wait for the final print. Otherwise bisect (print the first half:
     * printed → the culprit is in the second half; refused → it is in the first, the second half waits for
     * one final print). A waybill is marked only when it is the last one of a refused request whose every
     * other waybill printed — never on Bosta's message alone.
     * Every printed part comes back as its own pdf chunk (the callers merge them). Only the isolated
     * culprits are marked failed — DELETED_IN_BOSTA for "couldn't find", BOSTA_REJECTED:… otherwise;
     * provider_not_found_at / internal_state / pieces are never touched here. At most
     * {@link #MAX_ISOLATION_CALLS} extra calls; any other error, or the cap, leaves the rest
     * BOSTA_REJECTED_UNISOLATED. Called with no transaction open (each markFailed is its own).
     */
    private List<AwbChunk> isolate(UUID tenantId, String apiKey, List<String> chunk, String format, String lang,
                                   Map<String, UUID> trackingToId, BostaAwbRejectedException first, Isolation firstKind) {
        List<AwbChunk> out = new ArrayList<>();
        Map<String, String> culprits = new LinkedHashMap<>();   // tracking → reason
        List<String> unresolved = new ArrayList<>();
        List<String> waiting = new ArrayList<>();               // not printed yet, not known bad
        List<String> bad = new ArrayList<>(chunk);              // holds ≥ 1 culprit
        BostaAwbRejectedException err = first;                  // the rejection of exactly `bad` (null when deduced)
        BostaAwbRejectedException cause = first;                // the rejection that implicated `bad`
        Isolation kind = firstKind;
        boolean useHints = true;
        int calls = 0;

        search:
        while (true) {
            // 1. Pin down the culprit(s) in `bad`. Invariant: bad = the last refused request minus what has
            //    printed since, so a single waybill left in `bad` is confirmed by prints that succeeded without it.
            //    Numbers in Bosta's message are hints: tried once (reprint without them); never marked on the text.
            Set<String> hints = useHints ? namedIn(err, bad) : Set.of();
            if (!hints.isEmpty() && hints.size() < bad.size()) {
                if (calls >= MAX_ISOLATION_CALLS) { unresolved.addAll(bad); unresolved.addAll(waiting); break; }
                List<String> rest = bad.stream().filter(tn -> !hints.contains(tn)).toList();
                calls++;
                try {
                    out.add(toChunk(rest, bostaGateway.printMassAwb(apiKey, rest, format, lang)));
                    bad = new ArrayList<>(hints);                    // confirmed: the culprit is among the hints
                    err = null;
                } catch (BostaException e) {
                    Isolation k = isolationKind(e);
                    if (k == null) { unresolved.addAll(bad); unresolved.addAll(waiting); break; }
                    useHints = false;                                // the hint was wrong — bisect from here on
                    waiting.addAll(hints);
                    bad = new ArrayList<>(rest);
                    err = cause = (BostaAwbRejectedException) e;
                    kind = k;
                }
                continue;
            }
            if (bad.size() == 1) {
                culprits.put(bad.get(0), reasonFor(kind, cause));
            } else {
                if (calls >= MAX_ISOLATION_CALLS) { unresolved.addAll(bad); unresolved.addAll(waiting); break; }
                List<String> firstHalf = List.copyOf(bad.subList(0, (bad.size() + 1) / 2));
                List<String> secondHalf = List.copyOf(bad.subList(firstHalf.size(), bad.size()));
                calls++;
                try {
                    out.add(toChunk(firstHalf, bostaGateway.printMassAwb(apiKey, firstHalf, format, lang)));
                    bad = new ArrayList<>(secondHalf);
                    err = null;
                } catch (BostaException e) {
                    Isolation k = isolationKind(e);
                    if (k == null) { unresolved.addAll(bad); unresolved.addAll(waiting); break; }
                    waiting.addAll(secondHalf);
                    bad = new ArrayList<>(firstHalf);
                    err = cause = (BostaAwbRejectedException) e;
                    kind = k;
                }
                continue;
            }

            // 2. `bad` is resolved — print everything still waiting in one call.
            while (true) {
                if (waiting.isEmpty()) break search;
                if (calls >= MAX_ISOLATION_CALLS) { unresolved.addAll(waiting); break search; }
                List<String> rest = List.copyOf(waiting);
                waiting.clear();
                calls++;
                try {
                    out.add(toChunk(rest, bostaGateway.printMassAwb(apiKey, rest, format, lang)));
                    break search;
                } catch (BostaException e) {
                    Isolation k = isolationKind(e);
                    if (k == null) { unresolved.addAll(rest); break search; }
                    bad = new ArrayList<>(rest);
                    err = cause = (BostaAwbRejectedException) e;
                    kind = k;
                    continue search;
                }
            }
        }

        for (Map.Entry<String, String> c : culprits.entrySet()) {
            out.add(new AwbChunk(List.of(c.getKey()), null, null, c.getValue()));
            UUID sid = trackingToId.get(c.getKey());
            if (sid != null) markFailed(tenantId, sid, c.getValue());
        }
        if (!unresolved.isEmpty()) out.add(new AwbChunk(List.copyOf(unresolved), null, null, BOSTA_REJECTED_UNISOLATED));
        log.warn("mass-awb chunk of {} rejected ({}): isolated {} bad waybill(s), {} left unprinted, {} extra call(s)",
            chunk.size(), firstKind, culprits.size(), unresolved.size(), calls);
        return out;
    }

    private static String reasonFor(Isolation kind, BostaAwbRejectedException cause) {
        return kind == Isolation.NOT_FOUND ? DELETED_IN_BOSTA : "BOSTA_REJECTED:" + truncate(cause.getMessage(), 120);
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
                    "       concat_ws(', ', o.shopify_address ->> 'address1', " +
                    "                 COALESCE(o.shopify_address ->> 'city', o.address ->> 'city')) AS address_line, " +
                    "       COALESCE(s.cod_amount, o.cod_amount) AS cod " +
                    "FROM shipments s JOIN merchant_orders o ON o.id = s.order_id AND o.tenant_id = s.tenant_id " +
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
    private record Printable(List<String> trackings, Set<String> suspects, Map<String, UUID> trackingToId,
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
                "       order_id, (raw -> 'type' ->> 'code')::int AS delivery_type_code, " +
                "       provider_not_found_at IS NOT NULL AS not_found_in_bosta, " +
                "       awb_print_failed_reason IS NOT DISTINCT FROM '" + DELETED_IN_BOSTA + "' AS isolated_before " +
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
        Set<String> suspects = new HashSet<>();   // isolated as DELETED_IN_BOSTA by an earlier print

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
            } else if (Boolean.TRUE.equals(row.get("not_found_in_bosta"))) {
                // Bosta's status poll already got "Delivery not found" for it (provider_not_found_at):
                // never sent to Bosta, reported as skipped — not marked failed, so no missing_awb alert.
                exclusions.add(new AwbException(tracking, DELETED_IN_BOSTA));
                log.debug("AWB skipped: tracking={} reason={}", tracking, DELETED_IN_BOSTA);
                continue;
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
                if (Boolean.TRUE.equals(row.get("isolated_before"))) suspects.add(tracking);
            }
        }

        return new Printable(printable, suspects, trackingToId, exclusions);
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
