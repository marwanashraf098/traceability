package com.traceability.inventory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Merged-PDF slice B — database side of background print jobs ({@code pack_print_jobs}, V170). Every
 * method is its own short transaction under RLS (called through the Spring proxy, with the tenant set by
 * the request or by TenantContext.runAs in the runner / sweeper), so the Bosta calls PrintJobRunner makes
 * between them are never inside a transaction and never hold a connection.
 *
 * Per-waybill outcomes live in {@code result.items}, in pick-list order: {@code r} is null (not tried
 * yet), {@link #PRINTED}, or a slice 1 / A reason code. Every write of a running job is conditional on
 * {@code status = 'running'}, so a job the sweeper has already failed is never written again.
 */
@Component
public class PrintJobStore {

    public static final String PRINTED = "PRINTED";
    /** The job stopped (app restart, unexpected error) before this waybill was tried — print it again. */
    public static final String PRINT_INTERRUPTED = "PRINT_INTERRUPTED";
    /** Ten minutes without an update: the job is failed by the sweeper. */
    public static final int STUCK_AFTER_MINUTES = 10;
    /** A finished job's PDF is kept 24 h, then its bytes are deleted. */
    public static final int KEEP_PDF_HOURS = 24;

    /** One waybill of a job: order id / number, shipment id, tracking number, outcome. */
    public record Item(UUID o, String n, UUID s, String t, String r) {
        Item withOutcome(String outcome) { return new Item(o, n, s, t, outcome); }
    }

    /** A message for the merchant, EN + AR, rendered as-is by the UI. */
    public record Message(String code, String en, String ar) {}

    public record Result(List<Item> items, Message message) {}

    public record Job(UUID id, UUID tenantId, UUID requestedBy, String kind, String scope, String sort,
                      String paper, UUID reprintBatchId, String status, int total, int doneCount,
                      int candidateCount, int remainingCount, Result result, Integer pdfBytes,
                      Boolean orderGuaranteed, UUID batchId, Integer batchNo, Integer reprintBatchNo,
                      String errorCode, Instant createdAt, Instant updatedAt, Instant finishedAt,
                      Instant expiresAt) {
        public boolean active() { return "queued".equals(status) || "running".equals(status); }
    }

    private static final String SELECT_JOB =
        "SELECT j.id, j.tenant_id, j.requested_by, j.kind, j.scope, j.sort, j.paper, j.reprint_batch_id, j.status, " +
        "       j.total, j.done_count, j.candidate_count, j.remaining_count, j.result::text AS result, j.pdf_bytes, " +
        "       j.order_guaranteed, j.batch_id, b.batch_no, rb.batch_no AS reprint_batch_no, j.error_code, " +
        "       j.created_at, j.updated_at, j.finished_at, j.expires_at " +
        "FROM pack_print_jobs j " +
        "LEFT JOIN pack_print_batches b  ON b.id  = j.batch_id         AND b.tenant_id  = j.tenant_id " +
        "LEFT JOIN pack_print_batches rb ON rb.id = j.reprint_batch_id AND rb.tenant_id = j.tenant_id ";

    private final JdbcTemplate        jdbc;
    private final ObjectMapper        mapper;
    private final PackPrintBatchStore batches;

    public PrintJobStore(JdbcTemplate jdbc, ObjectMapper mapper, PackPrintBatchStore batches) {
        this.jdbc    = jdbc;
        this.mapper  = mapper;
        this.batches = batches;
    }

    // ── Start ─────────────────────────────────────────────────────────────────

    /**
     * Inserts a queued job, or returns null when the tenant already has a queued / running one (the partial
     * UNIQUE index decides — ON CONFLICT, so a race between two starts never raises).
     */
    @Transactional
    public UUID insertQueued(UUID requestedBy, String kind, String scope, String sort, String paper,
                             UUID reprintBatchId) {
        UUID tenantId = TenantContext.require();
        List<UUID> ids = jdbc.queryForList(
            "INSERT INTO pack_print_jobs (tenant_id, requested_by, kind, scope, sort, paper, reprint_batch_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (tenant_id) WHERE status IN ('queued', 'running') DO NOTHING RETURNING id",
            UUID.class, tenantId, requestedBy, kind, scope, sort, paper, reprintBatchId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** The tenant's queued or running job, if any. */
    @Transactional(readOnly = true)
    public Optional<UUID> activeJobId() {
        UUID tenantId = TenantContext.require();
        return jdbc.queryForList(
            "SELECT id FROM pack_print_jobs WHERE tenant_id = ? AND status IN ('queued', 'running')",
            UUID.class, tenantId).stream().findFirst();
    }

    /** A reprint's batch: its paper, or empty when the batch isn't this tenant's. */
    @Transactional(readOnly = true)
    public Optional<String> batchPaper(UUID batchId) {
        UUID tenantId = TenantContext.require();
        return jdbc.queryForList("SELECT paper FROM pack_print_batches WHERE id = ? AND tenant_id = ?",
            String.class, batchId, tenantId).stream().findFirst();
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Optional<Job> load(UUID jobId) {
        UUID tenantId = TenantContext.require();
        return jdbc.query(SELECT_JOB + "WHERE j.id = ? AND j.tenant_id = ?", (rs, i) -> job(rs), jobId, tenantId)
            .stream().findFirst();
    }

    /** The tenant's most recent job created in the last {@link #KEEP_PDF_HOURS} hours (resume after navigation). */
    @Transactional(readOnly = true)
    public Optional<Job> latest() {
        UUID tenantId = TenantContext.require();
        return jdbc.query(SELECT_JOB +
                "WHERE j.tenant_id = ? AND j.created_at > now() - make_interval(hours => " + KEEP_PDF_HOURS + ") " +
                // UUIDv4 is not time-ordered — order by created_at, never id (see CLAUDE.md invariant)
                "ORDER BY j.created_at DESC, j.id DESC LIMIT 1",
                (rs, i) -> job(rs), tenantId)
            .stream().findFirst();
    }

    /** The stored PDF, or empty when there is none (never printed, or deleted after 24 h). */
    @Transactional(readOnly = true)
    public Optional<byte[]> pdf(UUID jobId) {
        UUID tenantId = TenantContext.require();
        return jdbc.query("SELECT pdf FROM pack_print_jobs WHERE id = ? AND tenant_id = ? AND pdf IS NOT NULL",
            (rs, i) -> rs.getBytes(1), jobId, tenantId).stream().findFirst();
    }

    // ── Runner ────────────────────────────────────────────────────────────────

    /** A queued job waiting for a print slot: still queued → updated_at bumped (the sweeper leaves it alone). */
    @Transactional
    public boolean touchQueued(UUID jobId) {
        UUID tenantId = TenantContext.require();
        return jdbc.update("UPDATE pack_print_jobs SET updated_at = now() " +
                           "WHERE id = ? AND tenant_id = ? AND status = 'queued'", jobId, tenantId) == 1;
    }

    /** queued → running, exactly once. Empty when the job is gone, already started or swept. */
    @Transactional
    public Optional<Job> claim(UUID jobId) {
        UUID tenantId = TenantContext.require();
        int n = jdbc.update(
            "UPDATE pack_print_jobs SET status = 'running', started_at = now(), updated_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND status = 'queued'", jobId, tenantId);
        if (n == 0) return Optional.empty();
        return jdbc.query(SELECT_JOB + "WHERE j.id = ? AND j.tenant_id = ?", (rs, i) -> job(rs), jobId, tenantId)
            .stream().findFirst();
    }

    /** The plan: what will be tried, in pick-list order. False when the job is no longer running. */
    @Transactional
    public boolean savePlan(UUID jobId, int total, int candidateCount, int remainingCount, Result result) {
        UUID tenantId = TenantContext.require();
        return jdbc.update(
            "UPDATE pack_print_jobs SET total = ?, candidate_count = ?, remaining_count = ?, result = ?::jsonb, " +
            "       updated_at = now() WHERE id = ? AND tenant_id = ? AND status = 'running'",
            total, candidateCount, remainingCount, json(result), jobId, tenantId) == 1;
    }

    /**
     * After each Bosta request: progress, outcomes and the merged PDF so far (labels already fetched survive
     * a restart). False when the job is no longer running (swept) — the runner then stops.
     */
    @Transactional
    public boolean saveProgress(UUID jobId, int doneCount, Result result, byte[] pdf, Boolean orderGuaranteed) {
        UUID tenantId = TenantContext.require();
        return jdbc.update(
            "UPDATE pack_print_jobs SET done_count = ?, result = ?::jsonb, " +
            "       pdf = COALESCE(?::bytea, pdf), pdf_bytes = COALESCE(?::integer, pdf_bytes), " +
            "       order_guaranteed = COALESCE(?::boolean, order_guaranteed), updated_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND status = 'running'",
            doneCount, json(result), pdf, pdf == null ? null : pdf.length, orderGuaranteed,
            jobId, tenantId) == 1;
    }

    /**
     * Ends a running job in ONE transaction: a print with printed waybills writes its batch (the PDF already
     * exists — one job = one batch), then the job row. Nothing happens if the job is no longer running.
     * Waybills never tried are reported {@link #PRINT_INTERRUPTED}.
     */
    @Transactional
    public boolean finish(UUID jobId, String status, Result result, String errorCode) {
        UUID tenantId = TenantContext.require();
        List<Job> locked = jdbc.query(SELECT_JOB + "WHERE j.id = ? AND j.tenant_id = ? AND j.status = 'running' FOR UPDATE OF j",
            (rs, i) -> job(rs), jobId, tenantId);
        if (locked.isEmpty()) return false;
        endJob(locked.get(0), status, result, errorCode);
        return true;
    }

    // ── Sweeper ───────────────────────────────────────────────────────────────

    /**
     * Fails every queued / running job of the current tenant not updated for {@link #STUCK_AFTER_MINUTES}
     * minutes (e.g. the app restarted mid-print). Labels already saved stay downloadable — and, for a print,
     * are recorded as its batch; untried waybills are listed "print again"; the tenant can start again.
     */
    @Transactional
    public int sweepStuck() {
        UUID tenantId = TenantContext.require();
        List<Job> stuck = jdbc.query(SELECT_JOB +
                "WHERE j.tenant_id = ? AND j.status IN ('queued', 'running') " +
                "  AND j.updated_at < now() - make_interval(mins => " + STUCK_AFTER_MINUTES + ") FOR UPDATE OF j",
            (rs, i) -> job(rs), tenantId);
        for (Job j : stuck) {
            boolean anyPrinted = j.result().items().stream().anyMatch(it -> PRINTED.equals(it.r()));
            Message m = new Message("PRINT_STOPPED",
                anyPrinted
                    ? "This print stopped before it finished. The waybills already prepared can be opened below; print the rest again."
                    : "This print stopped before it finished. Print again.",
                anyPrinted
                    ? "توقفت هذه الطباعة قبل أن تنتهي. يمكنك فتح البوالص التي جُهزت بالفعل بالأسفل، واطبع الباقي مرة أخرى."
                    : "توقفت هذه الطباعة قبل أن تنتهي. اطبع مرة أخرى.");
            endJob(j, "failed", new Result(j.result().items(), m), "STUCK");
        }
        return stuck.size();
    }

    /** Deletes the PDF bytes of the current tenant's jobs past expires_at. */
    @Transactional
    public int deleteExpiredPdfs() {
        UUID tenantId = TenantContext.require();
        return jdbc.update("UPDATE pack_print_jobs SET pdf = NULL, pdf_bytes = NULL " +
                           "WHERE tenant_id = ? AND pdf IS NOT NULL AND expires_at < now()", tenantId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Writes the end of a locked job (caller's transaction): batch for printed waybills of a print, then the row. */
    private void endJob(Job j, String status, Result result, String errorCode) {
        List<Item> items = result.items().stream()
            .map(it -> it.r() == null ? it.withOutcome(PRINT_INTERRUPTED) : it).toList();
        Result done = new Result(items, result.message());

        UUID batchId = j.batchId();
        boolean hasPdf = j.pdfBytes() != null;
        List<PackPrintBatchStore.PrintedItem> printed = items.stream().filter(it -> PRINTED.equals(it.r()))
            .map(it -> new PackPrintBatchStore.PrintedItem(it.o(), it.s(), it.t())).toList();
        if ("print".equals(j.kind()) && hasPdf && !printed.isEmpty() && batchId == null) {
            batchId = batches.record(j.requestedBy(), j.paper(), j.sort(), j.scope(),
                Boolean.TRUE.equals(j.orderGuaranteed()), printed).batchId();
        }
        jdbc.update(
            "UPDATE pack_print_jobs SET status = ?, result = ?::jsonb, error_code = ?, batch_id = ?, " +
            "       done_count = total, updated_at = now(), finished_at = now(), " +
            "       expires_at = now() + make_interval(hours => " + KEEP_PDF_HOURS + ") " +
            "WHERE id = ? AND tenant_id = ?",
            status, json(done), errorCode, batchId, j.id(), j.tenantId());
    }

    private Job job(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Job(
            rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
            rs.getObject("requested_by", UUID.class), rs.getString("kind"), rs.getString("scope"),
            rs.getString("sort"), rs.getString("paper"), rs.getObject("reprint_batch_id", UUID.class),
            rs.getString("status"), rs.getInt("total"), rs.getInt("done_count"), rs.getInt("candidate_count"),
            rs.getInt("remaining_count"), result(rs.getString("result")),
            (Integer) rs.getObject("pdf_bytes"), (Boolean) rs.getObject("order_guaranteed"),
            rs.getObject("batch_id", UUID.class), (Integer) rs.getObject("batch_no"),
            (Integer) rs.getObject("reprint_batch_no"), rs.getString("error_code"),
            instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")),
            instant(rs.getTimestamp("finished_at")), instant(rs.getTimestamp("expires_at")));
    }

    private static Instant instant(Timestamp t) { return t == null ? null : t.toInstant(); }

    private Result result(String json) {
        try {
            Map<String, Object> m = mapper.readValue(json, new TypeReference<>() {});
            List<Item> items = m.containsKey("items")
                ? mapper.convertValue(m.get("items"), new TypeReference<List<Item>>() {}) : List.of();
            Message message = m.get("message") == null ? null : mapper.convertValue(m.get("message"), Message.class);
            return new Result(items, message);
        } catch (Exception e) {
            throw new IllegalStateException("pack_print_jobs.result is not readable", e);
        }
    }

    private String json(Result r) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("items", r.items());
            m.put("message", r.message());
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
