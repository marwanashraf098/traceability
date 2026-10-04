package com.traceability.integrations.shopify;

import com.traceability.identity.CustomUserDetails;
import org.jobrunr.jobs.JobId;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Owner one-off: re-process this tenant's unapplied Shopify webhook events (dry run unless apply=true). */
@RestController
@RequestMapping("/api/v1")
public class ShopifyWebhookReprocessController {

    private final JobScheduler jobScheduler;
    private final ShopifyWebhookReprocessService service;

    public ShopifyWebhookReprocessController(JobScheduler jobScheduler, ShopifyWebhookReprocessService service) {
        this.jobScheduler = jobScheduler;
        this.service = service;
    }

    @PostMapping("/shopify/webhooks/reprocess")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('OWNER')")
    public Map<String, String> reprocess(@RequestParam(name = "apply", defaultValue = "false") boolean apply,
                                         @AuthenticationPrincipal CustomUserDetails principal) {
        UUID tenantId = principal.tenantId();
        JobId jobId = jobScheduler.enqueue(() -> service.runAndLog(tenantId, apply));
        Map<String, String> resp = new LinkedHashMap<>();
        resp.put("jobId",   jobId != null ? jobId.asUUID().toString() : "enqueued");
        resp.put("message", (apply ? "Re-process (APPLY)" : "Re-process dry run") + " enqueued — results in the logs (SHOPIFY_REPROCESS)");
        return resp;
    }
}
