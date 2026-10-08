package com.traceability.analytics;

import com.traceability.analytics.AnalyticsSql.Compared;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Analytics slice 6 — customers since connect. OWNER only. Never returns a phone number or the
 * customer key; see {@link CustomerAnalyticsService}. {@code /customers/summary} is also the source of
 * the Revenue page's "New vs returning" split ({@code byClass}).
 */
@RestController
@RequestMapping("/api/v1/analytics/customers")
public class CustomerAnalyticsController {

    private final CustomerAnalyticsService customers;

    public CustomerAnalyticsController(CustomerAnalyticsService customers) {
        this.customers = customers;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasRole('OWNER')")
    public Compared<CustomerAnalyticsService.Summary> summary(@RequestParam(required = false) String period,
                                                             @RequestParam(required = false) String from,
                                                             @RequestParam(required = false) String to,
                                                             @RequestParam(defaultValue = "false") boolean compare) {
        return customers.summary(AnalyticsPeriod.resolve(period, from, to, customers.today()), compare);
    }

    @GetMapping("/top")
    @PreAuthorize("hasRole('OWNER')")
    public CustomerAnalyticsService.Top top(@RequestParam(defaultValue = "" + CustomerAnalyticsService.TOP) int limit) {
        if (limit < 1 || limit > CustomerAnalyticsService.TOP) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1–" + CustomerAnalyticsService.TOP);
        }
        return customers.top(limit);
    }

    @GetMapping("/by-governorate")
    @PreAuthorize("hasRole('OWNER')")
    public CustomerAnalyticsService.ByGovernorate byGovernorate() {
        return customers.byGovernorate();
    }

    @GetMapping("/cohorts")
    @PreAuthorize("hasRole('OWNER')")
    public CustomerAnalyticsService.Cohorts cohorts() {
        return customers.cohorts();
    }

    @GetMapping("/watch")
    @PreAuthorize("hasRole('OWNER')")
    public CustomerAnalyticsService.Watch watch() {
        return customers.watch();
    }
}
