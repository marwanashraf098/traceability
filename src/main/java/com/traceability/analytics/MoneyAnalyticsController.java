package com.traceability.analytics;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Analytics — Bosta money (slice 3). OWNER only (managers and workers get 403). Same period
 * parameters as sales (period=today|7d|30d, or from=&to=, Cairo days); see
 * {@link MoneyAnalyticsService} for which date each figure counts by.
 */
@RestController
@RequestMapping("/api/v1/analytics/money")
public class MoneyAnalyticsController {

    private final MoneyAnalyticsService svc;

    public MoneyAnalyticsController(MoneyAnalyticsService svc) {
        this.svc = svc;
    }

    private AnalyticsPeriod period(String period, String from, String to) {
        return AnalyticsPeriod.resolve(period, from, to, svc.today());
    }

    @GetMapping("/pipeline")
    @PreAuthorize("hasRole('OWNER')")
    public MoneyAnalyticsService.Pipeline pipeline(@RequestParam(required = false) String period,
                                                   @RequestParam(required = false) String from,
                                                   @RequestParam(required = false) String to) {
        return svc.pipeline(period(period, from, to));
    }

    @GetMapping("/fees")
    @PreAuthorize("hasRole('OWNER')")
    public MoneyAnalyticsService.Fees fees(@RequestParam(required = false) String period,
                                           @RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to) {
        return svc.fees(period(period, from, to));
    }

    @GetMapping("/fees/extra")
    @PreAuthorize("hasRole('OWNER')")
    public MoneyAnalyticsService.ExtraFees extraFees(@RequestParam(required = false) String period,
                                                     @RequestParam(required = false) String from,
                                                     @RequestParam(required = false) String to,
                                                     @RequestParam(required = false, defaultValue = "sku") String groupBy) {
        if (!"sku".equals(groupBy) && !"awb".equals(groupBy)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "groupBy must be sku or awb");
        }
        return svc.extraFees(period(period, from, to), groupBy);
    }

    /** Point in time (now); takes no period. */
    @GetMapping("/stuck")
    @PreAuthorize("hasRole('OWNER')")
    public MoneyAnalyticsService.Stuck stuck() {
        return svc.stuck();
    }

    @GetMapping("/payouts")
    @PreAuthorize("hasRole('OWNER')")
    public MoneyAnalyticsService.Payouts payouts(@RequestParam(required = false) String period,
                                                 @RequestParam(required = false) String from,
                                                 @RequestParam(required = false) String to) {
        return svc.payouts(period(period, from, to));
    }
}
