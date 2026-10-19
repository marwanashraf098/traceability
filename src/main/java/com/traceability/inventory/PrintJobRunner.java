package com.traceability.inventory;

import com.traceability.integrations.bosta.BostaAwbService;
import com.traceability.integrations.bosta.BostaAwbService.AwbChunk;
import com.traceability.integrations.bosta.BostaAwbService.AwbDetailedResult;
import com.traceability.integrations.bosta.BostaRateLimiter;
import com.traceability.integrations.bosta.NoBostaAccountException;
import com.traceability.inventory.PrintJobStore.Item;
import com.traceability.inventory.PrintJobStore.Job;
import com.traceability.inventory.PrintJobStore.Message;
import com.traceability.inventory.PrintJobStore.Result;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * Merged-PDF slice B — runs one print job (JobRunr, no retries).
 *
 *   1. Takes one of the server-wide print slots ({@link PrintJobSlots}); none free → stays queued and is
 *      rescheduled in {@code traced.print-jobs.queue-retry-seconds}.
 *   2. queued → running ({@link PrintJobStore#claim}, exactly once), then the plan: a print's candidates from
 *      {@link PackPrintBatchStore#candidates} (the queue's own rule — never copied), the first
 *      {@link #MAX_WAYBILLS_PER_JOB} in pick-list order; a reprint's items from
 *      {@link PackListService#reprintItems} (cancelled orders skipped, as before).
 *   3. Requests of ≤ {@link BostaAwbService#BATCH_SIZE} (49), one after another, each through
 *      {@link BostaAwbService#printAwbDetailed} at user-facing limiter priority — so slice 1's pre-filter and
 *      isolation and slice A's busy / temporary handling apply unchanged. Immediately before every Bosta call
 *      (each request and each isolation call) the job's heartbeat is written in its own short transaction, so
 *      a long isolation is never swept while it is still calling Bosta. After each request the outcomes are
 *      saved and its PDF(s) inserted once each as parts — never a growing PDF. No transaction or connection is
 *      held during a Bosta call.
 *   4. A rate limit or a refused API key stops it (the rest is reported without being sent). The end
 *      ({@link PrintJobStore#finish}) merges the parts into the final PDF, written once, and a print's batch
 *      row in the same transaction.
 */
@Component
public class PrintJobRunner {

    private static final Logger log = LoggerFactory.getLogger(PrintJobRunner.class);

    /** The heartbeat found the job no longer running (swept): stop before calling Bosta again. */
    static final class JobNotRunningException extends RuntimeException {
        JobNotRunningException() { super("print job is no longer running", null, false, false); }
    }

    /** Waybills per print job: 10 Bosta requests of 49. Above it, "N more ready — print again". */
    public static final int MAX_WAYBILLS_PER_JOB = 490;

    private final PrintJobStore        store;
    private final PrintJobSlots        slots;
    private final PackPrintBatchStore  candidates;
    private final PackListService      packLists;
    private final BostaAwbService      awbService;
    private final JobScheduler         jobScheduler;
    private final long                 queueRetrySeconds;

    public PrintJobRunner(PrintJobStore store, PrintJobSlots slots, PackPrintBatchStore candidates,
                          PackListService packLists, BostaAwbService awbService, JobScheduler jobScheduler,
                          @Value("${traced.print-jobs.queue-retry-seconds:5}") long queueRetrySeconds) {
        this.store             = store;
        this.slots             = slots;
        this.candidates        = candidates;
        this.packLists         = packLists;
        this.awbService        = awbService;
        this.jobScheduler      = jobScheduler;
        this.queueRetrySeconds = queueRetrySeconds;
    }

    @org.jobrunr.jobs.annotations.Job(name = "Print waybills — job %0", retries = 0)
    public void run(UUID jobId, UUID tenantId) {
        if (!slots.tryAcquire()) {
            boolean stillQueued = TenantContext.runAs(tenantId, () -> store.touchQueued(jobId));
            if (stillQueued) {
                jobScheduler.<PrintJobRunner>schedule(Instant.now().plusSeconds(queueRetrySeconds),
                    r -> r.run(jobId, tenantId));
            }
            return;
        }
        try {
            TenantContext.runAs(tenantId, () -> execute(jobId, tenantId));
        } finally {
            slots.release();
        }
    }

    private void execute(UUID jobId, UUID tenantId) {
        Optional<Job> claimed = store.claim(jobId);
        if (claimed.isEmpty()) return;                       // already started, swept, or not this tenant's
        Job job = claimed.get();

        // ── Plan ──
        List<Item> items = new ArrayList<>();
        String paper;
        int candidateCount, remaining;
        if ("print".equals(job.kind())) {
            List<PackPrintBatchStore.Candidate> all = candidates.candidates(job.scope(), job.sort());
            List<PackPrintBatchStore.Candidate> chosen = all.subList(0, Math.min(MAX_WAYBILLS_PER_JOB, all.size()));
            for (PackPrintBatchStore.Candidate c : chosen) {
                items.add(new Item(c.orderId(), c.orderNumber(), c.shipmentId(), c.trackingNumber(), null));
            }
            paper = job.paper();
            candidateCount = all.size();
            remaining = all.size() - chosen.size();
        } else {
            List<Map<String, Object>> rows = packLists.reprintItems(job.reprintBatchId());
            paper = rows.isEmpty() ? null : (String) rows.get(0).get("paper");
            for (Map<String, Object> r : rows) {
                items.add(new Item(null, (String) r.get("number"), (UUID) r.get("shipment_id"),
                    (String) r.get("tracking_number"),
                    Boolean.TRUE.equals(r.get("cancelled")) ? "ORDER_CANCELLED" : null));
            }
            candidateCount = rows.size();
            remaining = 0;
        }
        if (!store.savePlan(jobId, items.size(), candidateCount, remaining, new Result(items, null))) return;

        List<Integer> toTry = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) if (items.get(i).r() == null) toTry.add(i);
        if (toTry.isEmpty()) {
            Message m = items.isEmpty() && "print".equals(job.kind())
                ? msg("NOTHING_TO_PRINT", "No waybills to print.", "لا توجد بوالص للطباعة.")
                : msg("NOTHING_PRINTABLE", "Nothing in this batch can be printed again.",
                      "لا يوجد في هذه الدفعة ما يمكن طباعته مرة أخرى.");
            store.finish(jobId, "done", new Result(items, m), null);
            return;
        }

        // ── Requests of ≤ 49, one after another ──
        Map<String, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < items.size(); i++) indexOf.put(items.get(i).t(), i);
        String stop = null;
        Message message = null;
        int done = items.size() - toTry.size();

        for (int from = 0; from < toTry.size(); from += BostaAwbService.BATCH_SIZE) {
            List<Integer> chunk = toTry.subList(from, Math.min(from + BostaAwbService.BATCH_SIZE, toTry.size()));
            if (stop != null) {
                for (int i : chunk) items.set(i, items.get(i).withOutcome(stop));
                done += chunk.size();
                continue;
            }
            List<UUID> ids = chunk.stream().map(i -> items.get(i).s()).toList();
            Runnable heartbeat = () -> { if (!store.heartbeat(jobId)) throw new JobNotRunningException(); };
            AwbDetailedResult bosta;
            try {
                String p = paper;
                bosta = BostaRateLimiter.userFacing(() -> awbService.printAwbDetailed(tenantId, ids, p, null, heartbeat));
            } catch (JobNotRunningException e) {
                log.warn("print job {}: tenant={} is no longer running (swept) — stopping", jobId, tenantId);
                return;
            } catch (NoBostaAccountException e) {
                message = msg(e.code(), e.messageEn(), e.messageAr());
                stop = PrintJobStore.PRINT_INTERRUPTED;
                for (int i : chunk) items.set(i, items.get(i).withOutcome(stop));
                done += chunk.size();
                continue;
            } catch (RuntimeException e) {
                log.warn("print job {}: tenant={} request failed: {}", jobId, tenantId, e.getClass().getSimpleName());
                message = msg("PRINT_ERROR", "Something went wrong while printing. Print the rest again.",
                    "حدث خطأ أثناء الطباعة. اطبع الباقي مرة أخرى.");
                stop = PrintJobStore.PRINT_INTERRUPTED;
                for (int i : chunk) items.set(i, items.get(i).withOutcome(stop));
                done += chunk.size();
                continue;
            }

            List<WaybillPdfAssembler.ChunkPdf> newParts = new ArrayList<>();
            for (BostaAwbService.AwbException ex : bosta.exclusions()) set(items, indexOf, ex.trackingNumber(), ex.reason());
            for (AwbChunk c : bosta.chunks()) {
                if (c.pdf() != null && WaybillPdfAssembler.readable(c.pdf())) {
                    newParts.add(new WaybillPdfAssembler.ChunkPdf(c.pdf(), c.trackingNumbers()));
                    for (String tn : c.trackingNumbers()) set(items, indexOf, tn, PrintJobStore.PRINTED);
                } else if (c.pdf() != null) {
                    // Bosta's PDF for this part can't be opened: its waybills weren't really printed.
                    log.warn("print job {}: tenant={} could not read a Bosta PDF of {} waybill(s)",
                        jobId, tenantId, c.trackingNumbers().size());
                    for (String tn : c.trackingNumbers()) set(items, indexOf, tn, PrintJobStore.PRINT_INTERRUPTED);
                    if (stop == null) {
                        stop = PrintJobStore.PRINT_INTERRUPTED;
                        message = msg("PDF_UNREADABLE", "Bosta returned a waybill PDF that couldn't be read. Print the rest again.",
                            "أرسلت Bosta ملف بوالص لا يمكن قراءته. اطبع الباقي مرة أخرى.");
                    }
                } else {
                    String reason = c.emailMessage() != null ? "BOSTA_EMAIL_PATH" : c.rejectedReason();
                    for (String tn : c.trackingNumbers()) set(items, indexOf, tn, reason);
                    if (BostaAwbService.stopsPrint(reason) && stop == null) {
                        stop = reason;
                        message = stopMessage(reason);
                    }
                }
            }
            done += chunk.size();

            if (!store.saveProgress(jobId, done, new Result(items, message), newParts)) {
                log.warn("print job {}: tenant={} is no longer running (swept) — stopping", jobId, tenantId);
                return;
            }
        }

        boolean anyPrinted = items.stream().anyMatch(it -> PrintJobStore.PRINTED.equals(it.r()));
        String status = stop == null ? "done" : anyPrinted ? "partial" : "failed";
        store.finish(jobId, status, new Result(items, message), stop == null ? null : stop);
        log.info("print job {}: tenant={} kind={} status={} waybills={} printed={}", jobId, tenantId, job.kind(),
            status, items.size(), items.stream().filter(it -> PrintJobStore.PRINTED.equals(it.r())).count());
    }

    private static void set(List<Item> items, Map<String, Integer> indexOf, String tracking, String outcome) {
        Integer i = indexOf.get(tracking);
        if (i != null) items.set(i, items.get(i).withOutcome(outcome));
    }

    private static Message stopMessage(String reason) {
        if (BostaAwbService.BOSTA_AUTH.equals(reason)) {
            return msg(reason, "Bosta connection problem — check Settings → Connections.",
                "مشكلة في الاتصال بـ Bosta — راجع الإعدادات ← الاتصالات.");
        }
        return msg(reason, "Bosta is busy — the rest weren't printed. Print again in a few minutes.",
            "Bosta مشغولة الآن — لم تُطبع البقية. اطبع مرة أخرى بعد بضع دقائق.");
    }

    private static Message msg(String code, String en, String ar) { return new Message(code, en, ar); }
}
