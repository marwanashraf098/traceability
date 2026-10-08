package com.traceability.analytics;

import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Analytics slice 10 — the owner's "read Shopify cost + stock now" (one pass for their own tenant,
 * at most once every 10 minutes; enqueued, the pass runs in the background) and the last pass's
 * status per field. OWNER only. Lets ops verify Shopify access in production after a deploy.
 */
@RestController
@RequestMapping("/api/v1/analytics/inventory-sync")
public class AnalyticsInventorySyncController {

    private final AnalyticsInventorySyncService sync;
    private final AnalyticsInventorySyncJob job;
    private final JobScheduler jobs;

    public AnalyticsInventorySyncController(AnalyticsInventorySyncService sync, AnalyticsInventorySyncJob job, JobScheduler jobs) {
        this.sync = sync;
        this.job = job;
        this.jobs = jobs;
    }

    @PostMapping("/run")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Map<String, Object>> run() {
        UUID tid = TenantContext.require();
        if (!sync.claimManualRun()) {
            AnalyticsInventorySyncService.Status s = sync.status();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "RATE_LIMITED");
            body.put("detail", "One run every " + AnalyticsInventorySyncService.RUN_EVERY_MINUTES + " minutes");
            body.put("nextRunAllowedAt", s.nextRunAllowedAt());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
        }
        jobs.enqueue(() -> job.manual(tid));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("queued", true);
        body.put("status", sync.status());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    @GetMapping("/status")
    @PreAuthorize("hasRole('OWNER')")
    public AnalyticsInventorySyncService.Status status() {
        return sync.status();
    }
}
