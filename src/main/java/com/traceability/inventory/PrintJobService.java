package com.traceability.inventory;

import com.traceability.inventory.PrintJobStore.Item;
import com.traceability.inventory.PrintJobStore.Job;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Merged-PDF slice B — start / follow / download background print jobs. Any user of the tenant (owner,
 * manager, worker, station worker) may start one, see any of the tenant's jobs and download its PDF; RLS
 * keeps every other tenant's jobs invisible (404). One queued-or-running job per tenant: a second start is
 * {@link JobRunningException} (409 carrying that job's id). The job is enqueued only after the inserting
 * transaction commits.
 */
@Service
public class PrintJobService {

    /** A waybill not printed and why, for the result list (order number, tracking number, reason code). */
    public record Excluded(String orderNumber, String trackingNumber, String reason) {}

    public record MessageView(String code, String messageEn, String messageAr) {}

    /**
     * Progress / result. done of total waybills have an outcome; printedCount are in the PDF; excluded lists
     * every other outcome so far (same reason codes as slice 1 / A). pdfAvailable: the PDF can be downloaded
     * (false before anything printed and after the 24 h cleanup).
     */
    public record JobView(UUID jobId, String kind, String status, int total, int done, int candidateCount,
                          int remainingCount, int printedCount, List<Excluded> excluded, UUID batchId,
                          Integer batchNo, UUID reprintBatchId, Integer reprintBatchNo, Boolean orderGuaranteed,
                          boolean pdfAvailable, Integer pdfBytes, MessageView message, Instant createdAt,
                          Instant finishedAt, Instant expiresAt) {}

    /** The tenant already has a queued / running job. */
    public static class JobRunningException extends RuntimeException {
        private final UUID jobId;
        public JobRunningException(UUID jobId) { super("PRINT_JOB_RUNNING"); this.jobId = jobId; }
        public UUID jobId() { return jobId; }
    }

    /** A stored PDF and the name to download it as. */
    public record Pdf(byte[] bytes, String filename) {}

    private final PrintJobStore store;
    private final JobScheduler  jobScheduler;

    public PrintJobService(PrintJobStore store, JobScheduler jobScheduler) {
        this.store        = store;
        this.jobScheduler = jobScheduler;
    }

    @Transactional
    public UUID startPrint(String scope, String paper, String sort, UUID userId) {
        TenantContext.require();
        if (!PackPrintBatchService.SCOPES.contains(scope) || !PackPrintBatchService.PAPERS.contains(paper)
                || !PackPrintBatchService.SORTS.contains(sort)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "scope must be new|all, paper A6|A4, sort oldest|newest");
        }
        return start(userId, "print", scope, sort, paper, null);
    }

    @Transactional
    public UUID startReprint(UUID batchId, UUID userId) {
        TenantContext.require();
        if (batchId == null || store.batchPaper(batchId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Print batch not found");
        }
        return start(userId, "reprint", null, null, null, batchId);
    }

    private UUID start(UUID userId, String kind, String scope, String sort, String paper, UUID batchId) {
        UUID tenantId = TenantContext.require();
        UUID jobId = store.insertQueued(userId, kind, scope, sort, paper, batchId);
        if (jobId == null) {
            throw new JobRunningException(store.activeJobId().orElse(null));
        }
        enqueueAfterCommit(jobId, tenantId);
        return jobId;
    }

    @Transactional(readOnly = true)
    public JobView view(UUID jobId) {
        TenantContext.require();
        return store.load(jobId).map(PrintJobService::toView)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Print job not found"));
    }

    /** The tenant's most recent job of the last 24 h — so a dialog closed mid-print finds it again. */
    @Transactional(readOnly = true)
    public Optional<JobView> latest() {
        TenantContext.require();
        return store.latest().map(PrintJobService::toView);
    }

    @Transactional(readOnly = true)
    public Pdf pdf(UUID jobId) {
        TenantContext.require();
        Job job = store.load(jobId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Print job not found"));
        byte[] bytes = store.pdf(jobId).orElseThrow(() -> new ResponseStatusException(
            job.finishedAt() != null && job.expiresAt() != null && job.expiresAt().isBefore(Instant.now())
                ? HttpStatus.GONE : HttpStatus.NOT_FOUND,
            "No waybills to download for this print"));
        Integer no = job.batchNo() != null ? job.batchNo() : job.reprintBatchNo();
        return new Pdf(bytes, no != null ? "waybills-batch-" + no + ".pdf" : "waybills-" + jobId.toString().substring(0, 8) + ".pdf");
    }

    static JobView toView(Job j) {
        List<Item> items = j.result().items();
        int printed = (int) items.stream().filter(it -> PrintJobStore.PRINTED.equals(it.r())).count();
        int done = (int) items.stream().filter(it -> it.r() != null).count();
        List<Excluded> excluded = items.stream()
            .filter(it -> it.r() != null && !PrintJobStore.PRINTED.equals(it.r()))
            .map(it -> new Excluded(it.n(), it.t(), it.r())).toList();
        PrintJobStore.Message m = j.result().message();
        return new JobView(j.id(), j.kind(), j.status(), j.total(), Math.max(done, j.doneCount()),
            j.candidateCount(), j.remainingCount(), printed, excluded, j.batchId(), j.batchNo(),
            j.reprintBatchId(), j.reprintBatchNo(), j.orderGuaranteed(), j.pdfBytes() != null, j.pdfBytes(),
            m == null ? null : new MessageView(m.code(), m.en(), m.ar()), j.createdAt(), j.finishedAt(),
            j.expiresAt());
    }

    private void enqueueAfterCommit(UUID jobId, UUID tenantId) {
        Runnable enqueue = () -> jobScheduler.<PrintJobRunner>enqueue(r -> r.run(jobId, tenantId));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { enqueue.run(); }
            });
        } else {
            enqueue.run();
        }
    }
}
