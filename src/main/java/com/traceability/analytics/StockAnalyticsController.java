package com.traceability.analytics;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Analytics slice 4 — stock health, restock suggestions, piece trips and the analytics settings.
 * OWNER only. Tenants without pieces get {@code hasPieces: false} (see {@link StockAnalyticsService}).
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class StockAnalyticsController {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;

    private final StockAnalyticsService stock;

    public StockAnalyticsController(StockAnalyticsService stock) {
        this.stock = stock;
    }

    /** {@code period} only bounds "lost / written off this period" (default last 30 days). */
    @GetMapping("/stock/summary")
    @PreAuthorize("hasRole('OWNER')")
    public StockAnalyticsService.Summary summary(@RequestParam(required = false) String period,
                                                 @RequestParam(required = false) String from,
                                                 @RequestParam(required = false) String to) {
        return stock.summary(AnalyticsPeriod.resolve(period, from, to, stock.today()));
    }

    @GetMapping("/stock/variants")
    @PreAuthorize("hasRole('OWNER')")
    public StockAnalyticsService.Variants variants(@RequestParam(defaultValue = "velocity") String sort,
                                                   @RequestParam(defaultValue = "all") String filter,
                                                   @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        if (!StockAnalyticsService.SORTS.contains(sort)) {
            throw bad("sort must be one of " + String.join(", ", StockAnalyticsService.SORTS));
        }
        if (!StockAnalyticsService.FILTERS.contains(filter)) {
            throw bad("filter must be one of " + String.join(", ", StockAnalyticsService.FILTERS));
        }
        if (limit < 1 || limit > MAX_LIMIT) throw bad("limit must be 1–" + MAX_LIMIT);
        return stock.variants(sort, filter, limit);
    }

    @GetMapping("/stock/restock")
    @PreAuthorize("hasRole('OWNER')")
    public StockAnalyticsService.Restock restock() {
        return stock.restock();
    }

    @GetMapping("/settings")
    @PreAuthorize("hasRole('OWNER')")
    public StockAnalyticsService.Settings settings() {
        return stock.settings();
    }

    public record SettingsRequest(Integer supplierLeadDays, Integer coverDays) {}

    @PutMapping("/settings")
    @PreAuthorize("hasRole('OWNER')")
    public StockAnalyticsService.Settings saveSettings(@RequestBody SettingsRequest req,
                                                       @AuthenticationPrincipal CustomUserDetails principal) {
        if (req == null || req.supplierLeadDays() == null || req.coverDays() == null) {
            throw bad("supplierLeadDays and coverDays are both required");
        }
        if (req.supplierLeadDays() < 0 || req.supplierLeadDays() > 365) throw bad("supplierLeadDays must be 0–365");
        if (req.coverDays() < 1 || req.coverDays() > 365) throw bad("coverDays must be 1–365");
        return stock.saveSettings(req.supplierLeadDays(), req.coverDays(), principal.userId());
    }

    @GetMapping("/pieces/{id}/history")
    @PreAuthorize("hasRole('OWNER')")
    public StockAnalyticsService.PieceHistory pieceHistory(@PathVariable String id) {
        StockAnalyticsService.PieceHistory h = stock.pieceHistory(id);
        if (h == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "piece not found");
        return h;
    }

    @GetMapping("/variants/{id}/pieces")
    @PreAuthorize("hasRole('OWNER')")
    public StockAnalyticsService.VariantPieces variantPieces(@PathVariable UUID id,
                                                             @RequestParam(defaultValue = "0") int minTrips,
                                                             @RequestParam(defaultValue = "" + StockAnalyticsService.MAX_PIECES) int limit) {
        if (minTrips < 0) throw bad("minTrips must be 0 or more");
        if (limit < 1 || limit > StockAnalyticsService.MAX_PIECES) throw bad("limit must be 1–" + StockAnalyticsService.MAX_PIECES);
        return stock.variantPieces(id, minTrips, limit);
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
