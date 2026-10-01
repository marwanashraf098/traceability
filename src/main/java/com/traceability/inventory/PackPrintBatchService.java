package com.traceability.inventory;

import com.traceability.integrations.bosta.BostaAwbService;
import com.traceability.integrations.bosta.BostaAwbService.AwbChunk;
import com.traceability.integrations.bosta.BostaAwbService.AwbDetailedResult;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.*;

/**
 * Pick &amp; Pack S2 — "Print waybills": one merged PDF for every waybill ready to pack (or only
 * the ones never printed), recorded as a print batch.
 *
 * Deliberately NOT @Transactional: the Bosta calls happen between two short transactions in
 * {@link PackPrintBatchStore} — read the candidates, call Bosta + build the PDF, then write the
 * batch. Only shipments that came back in a PDF are recorded; Bosta exclusions, rejected
 * chunks and email-path chunks are returned in {@code excluded} and stay "not printed".
 */
@Service
public class PackPrintBatchService {

    private static final Logger log = LoggerFactory.getLogger(PackPrintBatchService.class);

    static final Set<String> SCOPES = Set.of("new", "all");
    static final Set<String> PAPERS = Set.of("A6", "A4");
    static final Set<String> SORTS  = Set.of("oldest", "newest");

    /**
     * At most 49 waybills per print, so every print is ONE Bosta mass-awb request: from 50
     * tracking numbers up Bosta stops returning the PDF and emails the labels instead (the same
     * threshold as BostaAwbService.BATCH_SIZE). Applied after sorting — the first 49 in the
     * chosen order; "New only" then picks up the rest on the next print.
     */
    public static final int MAX_WAYBILLS_PER_PRINT = 49;

    public record Excluded(String orderNumber, String trackingNumber, String reason) {}

    /**
     * batchId / batchNo / pdfBase64 are null when nothing was printed. waybillCount = pages
     * printed and recorded; candidateCount = waybills that were ready to print (all of them);
     * remainingCount = candidates left out by {@link #MAX_WAYBILLS_PER_PRINT}, for the next print.
     */
    public record PrintBatchResult(UUID batchId, Integer batchNo, int waybillCount, int candidateCount,
                                   int remainingCount, boolean orderGuaranteed, String pdfBase64,
                                   List<Excluded> excluded, String message) {}

    public record PrintOptions(String defaultPaper) {}

    private final PackPrintBatchStore store;
    private final BostaAwbService     awbService;

    public PackPrintBatchService(PackPrintBatchStore store, BostaAwbService awbService) {
        this.store      = store;
        this.awbService = awbService;
    }

    public PrintOptions options() {
        return new PrintOptions(store.defaultPaper());
    }

    public PrintBatchResult print(String scope, String paper, String sort, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        if (!SCOPES.contains(scope) || !PAPERS.contains(paper) || !SORTS.contains(sort)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "scope must be new|all, paper A6|A4, sort oldest|newest");
        }

        // a) Sorted candidates (own read-only transaction), capped to one Bosta request.
        List<PackPrintBatchStore.Candidate> all = store.candidates(scope, sort);
        if (all.isEmpty()) {
            return new PrintBatchResult(null, null, 0, 0, 0, true, null, List.of(),
                "No waybills to print.");
        }
        List<PackPrintBatchStore.Candidate> candidates =
            all.subList(0, Math.min(MAX_WAYBILLS_PER_PRINT, all.size()));
        int remaining = all.size() - candidates.size();

        // b, c) Bosta, in sorted order, ≤50 per call — outside any transaction.
        AwbDetailedResult bosta = awbService.printAwbDetailed(
            tenantId, candidates.stream().map(PackPrintBatchStore.Candidate::shipmentId).toList(),
            paper, null);

        Map<String, PackPrintBatchStore.Candidate> byTracking = new HashMap<>();
        for (PackPrintBatchStore.Candidate c : candidates) byTracking.put(c.trackingNumber(), c);

        List<Excluded> excluded = new ArrayList<>();
        for (BostaAwbService.AwbException ex : bosta.exclusions()) {
            excluded.add(excludedFor(byTracking, ex.trackingNumber(), ex.reason()));
        }
        List<byte[]> pdfs = new ArrayList<>();
        Set<String> inPdf = new HashSet<>();
        for (AwbChunk chunk : bosta.chunks()) {
            if (chunk.pdf() != null) {
                pdfs.add(chunk.pdf());
                inPdf.addAll(chunk.trackingNumbers());
            } else {
                String reason = chunk.emailMessage() != null ? "BOSTA_EMAIL_PATH" : chunk.rejectedReason();
                for (String tn : chunk.trackingNumbers()) excluded.add(excludedFor(byTracking, tn, reason));
            }
        }

        // Printed = in a returned PDF, kept in candidate (sorted) order.
        List<PackPrintBatchStore.PrintedItem> printed = candidates.stream()
            .filter(c -> inPdf.contains(c.trackingNumber()))
            .map(c -> new PackPrintBatchStore.PrintedItem(c.orderId(), c.shipmentId(), c.trackingNumber()))
            .toList();
        if (printed.isEmpty()) {
            return new PrintBatchResult(null, null, 0, all.size(), remaining, true, null, excluded,
                "Bosta didn't return any waybills to print.");
        }

        // d) One PDF, pages in our order when the page text proves it.
        WaybillPdfAssembler.Assembled pdf;
        try {
            pdf = WaybillPdfAssembler.assemble(pdfs,
                printed.stream().map(PackPrintBatchStore.PrintedItem::trackingNumber).toList());
        } catch (IOException e) {
            log.warn("print batch: tenant={} could not read Bosta's PDF: {}", tenantId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Bosta returned a waybill PDF that couldn't be read — nothing was recorded, try again");
        }
        log.info("print batch: tenant={} waybills={} pages={} chunks={} orderVerified={} unmatchedPages={}",
            tenantId, printed.size(), pdf.pageCount(), pdfs.size(), pdf.orderGuaranteed(), pdf.unmatchedPages());

        // f) Record only after the PDF exists — own short transaction. Wrapped in runAs because
        //    BostaAwbService's own TenantContext.runAs(...) blocks CLEAR the context when they
        //    finish (they don't restore it), so this thread has no tenant any more.
        PackPrintBatchStore.RecordedBatch batch = TenantContext.runAs(tenantId, () ->
            store.record(actorUserId, paper, sort, scope, pdf.orderGuaranteed(), printed));

        return new PrintBatchResult(batch.batchId(), batch.batchNo(), printed.size(), all.size(),
            remaining, pdf.orderGuaranteed(), Base64.getEncoder().encodeToString(pdf.pdf()), excluded, null);
    }

    private static Excluded excludedFor(Map<String, PackPrintBatchStore.Candidate> byTracking,
                                        String tracking, String reason) {
        PackPrintBatchStore.Candidate c = byTracking.get(tracking);
        return new Excluded(c != null ? c.orderNumber() : null, tracking, reason);
    }
}
