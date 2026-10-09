package com.traceability.analytics;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Analytics slice 10 — gross margin, margin by product type, contribution profit and true net per
 * SKU (see {@link ProfitAnalyticsService}). OWNER only. Same period parameters as the other
 * analytics endpoints. Costed / total coverage is in every response. Stock value at cost and
 * dead-stock cash at cost are on /stock/summary and /stock/variants (they read the same unit_cost).
 */
@RestController
@RequestMapping("/api/v1/analytics/profit")
public class ProfitAnalyticsController {

    static final int MAX_LIMIT = 500;

    private final ProfitAnalyticsService profit;
    private final SalesAnalyticsService sales;

    public ProfitAnalyticsController(ProfitAnalyticsService profit, SalesAnalyticsService sales) {
        this.profit = profit;
        this.sales = sales;
    }

    private AnalyticsPeriod period(String period, String from, String to) {
        return AnalyticsPeriod.resolve(period, from, to, sales.today());
    }

    @GetMapping("/summary")
    @PreAuthorize("hasRole('OWNER')")
    public ProfitAnalyticsService.Summary summary(@RequestParam(required = false) String period,
                                                  @RequestParam(required = false) String from,
                                                  @RequestParam(required = false) String to) {
        return profit.summary(period(period, from, to));
    }

    @GetMapping("/by-product-type")
    @PreAuthorize("hasRole('OWNER')")
    public ProfitAnalyticsService.ByType byProductType(@RequestParam(required = false) String period,
                                                       @RequestParam(required = false) String from,
                                                       @RequestParam(required = false) String to) {
        return profit.byProductType(period(period, from, to));
    }

    /** sort: trueNet (lowest first — where money leaks), realized (highest first), margin (lowest first). */
    @GetMapping("/skus")
    @PreAuthorize("hasRole('OWNER')")
    public ProfitAnalyticsService.Skus skus(@RequestParam(required = false) String period,
                                            @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to,
                                            @RequestParam(defaultValue = "trueNet") String sort,
                                            @RequestParam(defaultValue = "100") int limit) {
        if (!ProfitAnalyticsService.SKU_SORTS.contains(sort)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sort must be one of " + String.join(", ", ProfitAnalyticsService.SKU_SORTS));
        }
        if (limit < 1 || limit > MAX_LIMIT) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1–" + MAX_LIMIT);
        return profit.skus(period(period, from, to), sort, limit);
    }
}
