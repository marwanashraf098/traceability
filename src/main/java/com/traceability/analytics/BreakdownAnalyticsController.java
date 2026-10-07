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
 * Analytics slice 5 — revenue, delivery and product breakdowns. OWNER only. Same period parameters
 * as the other analytics endpoints (period=today|7d|30d or from=&to=, Cairo days); every response
 * carries the period ({@code current}) and the previous period of the same length ({@code previous}).
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class BreakdownAnalyticsController {

    private final RevenueAnalyticsService revenue;
    private final DeliveryAnalyticsService delivery;
    private final ProductExtrasAnalyticsService products;
    private final SalesAnalyticsService sales;

    public BreakdownAnalyticsController(RevenueAnalyticsService revenue, DeliveryAnalyticsService delivery,
                                        ProductExtrasAnalyticsService products, SalesAnalyticsService sales) {
        this.revenue = revenue;
        this.delivery = delivery;
        this.products = products;
        this.sales = sales;
    }

    private AnalyticsPeriod period(String period, String from, String to) {
        return AnalyticsPeriod.resolve(period, from, to, sales.today());
    }

    @GetMapping("/revenue/summary")
    @PreAuthorize("hasRole('OWNER')")
    public Compared<RevenueAnalyticsService.Summary> summary(@RequestParam(required = false) String period,
                                                             @RequestParam(required = false) String from,
                                                             @RequestParam(required = false) String to) {
        return revenue.summary(period(period, from, to));
    }

    @GetMapping("/revenue/breakdown")
    @PreAuthorize("hasRole('OWNER')")
    public Compared<RevenueAnalyticsService.Breakdown> breakdown(@RequestParam(required = false) String period,
                                                                 @RequestParam(required = false) String from,
                                                                 @RequestParam(required = false) String to,
                                                                 @RequestParam String by) {
        RevenueAnalyticsService.By b = switch (by) {
            case "channel" -> RevenueAnalyticsService.By.CHANNEL;
            case "payment" -> RevenueAnalyticsService.By.PAYMENT;
            case "governorate" -> RevenueAnalyticsService.By.GOVERNORATE;
            case "productType" -> RevenueAnalyticsService.By.PRODUCT_TYPE;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "by must be channel, payment, governorate or productType");
        };
        return revenue.breakdown(period(period, from, to), b);
    }

    @GetMapping("/revenue/discounts")
    @PreAuthorize("hasRole('OWNER')")
    public Compared<RevenueAnalyticsService.Discounts> discounts(@RequestParam(required = false) String period,
                                                                 @RequestParam(required = false) String from,
                                                                 @RequestParam(required = false) String to) {
        return revenue.discounts(period(period, from, to));
    }

    @GetMapping("/revenue/heatmap")
    @PreAuthorize("hasRole('OWNER')")
    public Compared<RevenueAnalyticsService.Heatmap> heatmap(@RequestParam(required = false) String period,
                                                             @RequestParam(required = false) String from,
                                                             @RequestParam(required = false) String to) {
        return revenue.heatmap(period(period, from, to));
    }

    @GetMapping("/delivery/summary")
    @PreAuthorize("hasRole('OWNER')")
    public Compared<DeliveryAnalyticsService.Summary> deliverySummary(@RequestParam(required = false) String period,
                                                                      @RequestParam(required = false) String from,
                                                                      @RequestParam(required = false) String to) {
        return delivery.summary(period(period, from, to));
    }

    @GetMapping("/delivery/failure-reasons")
    @PreAuthorize("hasRole('OWNER')")
    public Compared<DeliveryAnalyticsService.FailureReasons> failureReasons(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return delivery.failureReasons(period(period, from, to));
    }

    @GetMapping("/products/extras")
    @PreAuthorize("hasRole('OWNER')")
    public Compared<ProductExtrasAnalyticsService.Extras> productExtras(@RequestParam(required = false) String period,
                                                                         @RequestParam(required = false) String from,
                                                                         @RequestParam(required = false) String to) {
        return products.extras(period(period, from, to));
    }
}
