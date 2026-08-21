package com.traceability.overview;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * FR-Overview §2 — 5 stat-card sparklines (trends) + Top-selling SKUs. Both
 * read-only, live-aggregated (see OverviewService's class doc for why). Distinct
 * from the currently-open exceptions badge (GET /exceptions/count, unchanged) —
 * that's a stock count, this is a flow count; deliberately different queries.
 */
@RestController
@RequestMapping("/api/v1/overview")
public class OverviewController {

    private final OverviewService svc;

    public OverviewController(OverviewService svc) {
        this.svc = svc;
    }

    @GetMapping("/trends")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    public List<OverviewService.MetricTrend> trends() {
        return svc.trends();
    }

    @GetMapping("/top-skus")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    public List<OverviewService.TopSku> topSkus() {
        return svc.topSkus();
    }
}
