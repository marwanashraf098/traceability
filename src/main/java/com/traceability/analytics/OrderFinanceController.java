package com.traceability.analytics;

import com.traceability.identity.CustomUserDetails;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Analytics slice 7 — order finances, the Summary's alerts and the cash forecast. OWNER only. Same
 * period parameters as the other analytics endpoints (period=today|7d|30d or from=&to=, Cairo days).
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class OrderFinanceController {

    static final int MAX_PAGE_SIZE = 200;
    static final int DEFAULT_MAX_EXPORT_ROWS = 50_000;
    static final String[] CSV_COLUMNS = {"order", "placed_at", "customer", "governorate", "governorate_ar", "items",
        "total", "payment", "delivery_status", "financial_status", "bosta_fees", "fees_estimated", "net_to_you",
        "refund_amount_unknown"};
    private static final DateTimeFormatter CAIRO_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(AnalyticsPeriod.CAIRO);

    private final OrderFinanceService finance;
    private final AnalyticsAlertsService alerts;
    private final int maxExportRows;

    public OrderFinanceController(OrderFinanceService finance, AnalyticsAlertsService alerts,
                                  @Value("${analytics.orders.export-max-rows:" + DEFAULT_MAX_EXPORT_ROWS + "}") int maxExportRows) {
        this.finance = finance;
        this.alerts = alerts;
        this.maxExportRows = maxExportRows;
    }

    private AnalyticsPeriod period(String period, String from, String to) {
        return AnalyticsPeriod.resolve(period, from, to, finance.today());
    }

    private static OrderFinanceService.Filters filters(String status, String governorate, UUID variantId, String q) {
        if (status != null && !OrderFinanceService.STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "status must be one of " + String.join(", ", OrderFinanceService.STATUSES));
        }
        return new OrderFinanceService.Filters(status, blankToNull(governorate), variantId, blankToNull(q));
    }

    /** sort: one of OrderFinanceService.SORTS (whitelist), dir: asc | desc — anything else is a 400. */
    static OrderFinanceService.Sort sort(String sort, String dir) {
        if (!OrderFinanceService.SORTS.contains(sort)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sort must be one of " + String.join(", ", OrderFinanceService.SORTS));
        }
        if (!"asc".equals(dir) && !"desc".equals(dir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dir must be asc or desc");
        }
        return new OrderFinanceService.Sort(sort, "asc".equals(dir));
    }

    @GetMapping("/orders")
    @PreAuthorize("hasRole('OWNER')")
    public OrderFinanceService.OrdersPage orders(@RequestParam(required = false) String period,
                                                 @RequestParam(required = false) String from,
                                                 @RequestParam(required = false) String to,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String governorate,
                                                 @RequestParam(required = false) UUID variantId,
                                                 @RequestParam(required = false) String q,
                                                 @RequestParam(defaultValue = "placedAt") String sort,
                                                 @RequestParam(defaultValue = "desc") String dir,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "50") int size) {
        if (page < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be 0 or more");
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be 1–" + MAX_PAGE_SIZE);
        }
        return finance.orders(period(period, from, to), filters(status, governorate, variantId, q), sort(sort, dir), page, size);
    }

    /**
     * The list as CSV (same filters and sort — default newest first —, at most 50,000 rows — X-Export-Truncated: true when
     * there were more), UTF-8 with a byte-order mark so Excel reads Arabic. One audit_log row per
     * export, carrying the filters only (q as present / absent), never row data.
     */
    @GetMapping("/orders/export.csv")
    @PreAuthorize("hasRole('OWNER')")
    public void export(@RequestParam(required = false) String period,
                       @RequestParam(required = false) String from,
                       @RequestParam(required = false) String to,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String governorate,
                       @RequestParam(required = false) UUID variantId,
                       @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "placedAt") String sort,
                       @RequestParam(defaultValue = "desc") String dir,
                       @AuthenticationPrincipal CustomUserDetails principal,
                       HttpServletResponse response) throws IOException {
        AnalyticsPeriod p = period(period, from, to);
        OrderFinanceService.Filters f = filters(status, governorate, variantId, q);
        OrderFinanceService.Export export = finance.export(p, f, sort(sort, dir), principal.userId(), maxExportRows);
        List<OrderFinanceService.OrderRow> rows = export.rows();
        boolean truncated = export.truncated();

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition",
            "attachment; filename=\"orders-" + p.from() + "-to-" + p.to() + ".csv\"");
        response.setHeader("X-Export-Truncated", String.valueOf(truncated));
        response.setHeader("Cache-Control", "no-store");
        Writer w = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
        w.write('\uFEFF');                                  // byte-order mark: Excel reads UTF-8 Arabic
        w.write(String.join(",", CSV_COLUMNS));
        w.write("\r\n");
        int n = 0;
        for (OrderFinanceService.OrderRow r : rows) {
            w.write(String.join(",",
                text(r.name()), r.placedAt() == null ? "" : CAIRO_TIME.format(r.placedAt()), text(r.customer()),
                text(r.governorate().label()), text(r.governorate().labelAr()), String.valueOf(r.items()),
                num(r.total()), text(r.paymentGroup()), text(r.deliveryStatus().label()), r.financialStatus(),
                num(r.bostaFees()), String.valueOf(r.feesEstimated()), num(r.netToYou()),
                String.valueOf(r.refundAmountUnknown())));
            w.write("\r\n");
            if (++n % 1000 == 0) w.flush();
        }
        w.flush();
    }

    @GetMapping("/variants/{id}/orders")
    @PreAuthorize("hasRole('OWNER')")
    public OrderFinanceService.VariantOrders variantOrders(@PathVariable UUID id) {
        return finance.variantOrders(id);
    }

    @GetMapping("/alerts")
    @PreAuthorize("hasRole('OWNER')")
    public AnalyticsAlertsService.Alerts alerts(@RequestParam(required = false) String period,
                                                @RequestParam(required = false) String from,
                                                @RequestParam(required = false) String to) {
        return alerts.alerts(period(period, from, to));
    }

    @GetMapping("/cash-forecast")
    @PreAuthorize("hasRole('OWNER')")
    public AnalyticsAlertsService.CashForecast cashForecast() {
        return alerts.cashForecast();
    }

    /**
     * A CSV text cell: quoted when it holds a comma, quote or line break; a leading = + - @ tab or
     * carriage return gets an apostrophe so a spreadsheet never runs it as a formula.
     */
    static String text(String v) {
        if (v == null) return "";
        String s = v;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    static String num(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
