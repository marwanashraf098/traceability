package com.traceability.analytics;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Analytics — sales, delivery outcomes, customer returns and cities (slices 1–2). OWNER only: managers and workers (station PIN sessions included)
 * get 403. Read-only; see {@link SalesAnalyticsService} for the sold-line rules and
 * {@link AnalyticsPeriod} for the period parameters (period=today|7d|30d, or from=&to=).
 */
@RestController
@RequestMapping("/api/v1/analytics/sales")
public class SalesAnalyticsController {

    private final SalesAnalyticsService svc;

    public SalesAnalyticsController(SalesAnalyticsService svc) {
        this.svc = svc;
    }

    @GetMapping("/variants")
    @PreAuthorize("hasRole('OWNER')")
    public SalesAnalyticsService.VariantSalesResponse variants(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return svc.variants(AnalyticsPeriod.resolve(period, from, to, svc.today()));
    }

    /**
     * Sold units per Cairo day for up to 20 variants (comma-separated ids) — the Top SKUs
     * sparklines and the SKU drawer's chart.
     */
    @GetMapping("/variants/daily")
    @PreAuthorize("hasRole('OWNER')")
    public SalesAnalyticsService.VariantDailyResponse variantsDaily(
            @RequestParam String ids,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        java.util.List<java.util.UUID> list = new java.util.ArrayList<>();
        for (String part : ids.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            try {
                java.util.UUID id = java.util.UUID.fromString(p);
                if (!list.contains(id)) list.add(id);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ids must be variant ids (UUIDs)");
            }
        }
        if (list.isEmpty() || list.size() > SalesAnalyticsService.MAX_DAILY_VARIANTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "ids must hold 1 to " + SalesAnalyticsService.MAX_DAILY_VARIANTS + " variant ids");
        }
        return svc.variantsDaily(AnalyticsPeriod.resolve(period, from, to, svc.today()), list);
    }

    /** Per city of the order's deciding Bosta forward leg; orders with no Bosta leg aren't listed. */
    @GetMapping("/cities")
    @PreAuthorize("hasRole('OWNER')")
    public SalesAnalyticsService.CitySalesResponse cities(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return svc.cities(AnalyticsPeriod.resolve(period, from, to, svc.today()));
    }

    @GetMapping("/products")
    @PreAuthorize("hasRole('OWNER')")
    public SalesAnalyticsService.ProductSalesResponse products(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false, defaultValue = "units") String sort,
            @RequestParam(required = false) Integer limit) {
        SalesAnalyticsService.Sort s = switch (sort) {
            case "units"   -> SalesAnalyticsService.Sort.UNITS;
            case "revenue" -> SalesAnalyticsService.Sort.REVENUE;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sort must be units or revenue");
        };
        int n = limit == null ? SalesAnalyticsService.DEFAULT_PRODUCT_LIMIT : limit;
        if (n < 1 || n > SalesAnalyticsService.MAX_PRODUCT_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "limit must be between 1 and " + SalesAnalyticsService.MAX_PRODUCT_LIMIT);
        }
        return svc.products(AnalyticsPeriod.resolve(period, from, to, svc.today()), s, n);
    }
}
