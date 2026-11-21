package com.traceability.bazar;

import com.traceability.identity.CustomUserDetails;
import com.traceability.inventory.PhoneScanSource;
import com.traceability.inventory.TransferService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bazar POS B1 — events, their linked transfer's send-out, prices, start. OWNER / MANAGER only; every endpoint
 * answers 404 BAZAR_NOT_ENABLED unless {@code tenants.bazar_enabled}. Scan-out returns the transfer's
 * ScanOutResult (200 with success / code), exactly like {@code POST /transfers/{id}/scan-out}.
 */
@RestController
@RequestMapping("/api/v1/bazar-events")
@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
public class BazarController {

    private final BazarService    bazar;
    private final PhoneScanSource phoneScanSource;
    private final BazarCatalogService catalogs;

    public BazarController(BazarService bazar, PhoneScanSource phoneScanSource, BazarCatalogService catalogs) {
        this.bazar           = bazar;
        this.phoneScanSource = phoneScanSource;
        this.catalogs        = catalogs;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody CreateRequest req,
                                      @AuthenticationPrincipal CustomUserDetails principal) {
        return bazar.create(new BazarService.CreateEvent(req.name(), req.startsOn(), req.endsOn(),
            req.locationId(), req.locationName(), req.paymentMethods(), req.sellerRoles(),
            req.shopifySyncMode(), req.discountPct(), req.roundTo()), principal.userId());
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return bazar.list();
    }

    @GetMapping("/{eventId}")
    public Map<String, Object> get(@PathVariable UUID eventId) {
        return bazar.get(eventId);
    }

    @PostMapping("/{eventId}/scan-out")
    public TransferService.ScanOutResult scanOut(@PathVariable UUID eventId, @RequestBody ScanRequest req,
                                                 @AuthenticationPrincipal CustomUserDetails principal) {
        boolean viaPhone = phoneScanSource.isPhone(req.relayEventId(), req.barcode(), principal.userId());
        return bazar.scanOut(eventId, req.barcode(), principal.userId(), viaPhone);
    }

    /** B7d — send N of a size without scanning (Traced picks the pieces at the main warehouse). */
    @PostMapping("/{eventId}/scan-out-by-count")
    public TransferService.CountResult sendByCount(@PathVariable UUID eventId, @RequestBody CountRequest req,
                                                   @AuthenticationPrincipal CustomUserDetails principal) {
        return bazar.sendByCount(eventId, req.variantId(), req.quantity(), req.clientRequestId(), principal.userId());
    }

    /** B7d — take a scanned piece ({@code code}) or N of a size ({@code variant_id} + {@code quantity}) off the send. */
    @PostMapping("/{eventId}/remove-from-send")
    public TransferService.CountResult removeFromSend(@PathVariable UUID eventId, @RequestBody RemoveRequest req,
                                                      @AuthenticationPrincipal CustomUserDetails principal) {
        return bazar.removeFromSend(eventId, req.code(), req.variantId(), req.quantity(), req.clientRequestId(),
            principal.userId());
    }

    /** B7d — what the main warehouse has to send (labelled and untagged), with online orders' committed demand. */
    @GetMapping("/{eventId}/send-stock")
    public Map<String, Object> sendStock(@PathVariable UUID eventId) {
        return catalogs.sendStockView(eventId);
    }

    @PostMapping("/{eventId}/mark-sent")
    public Map<String, Object> markSent(@PathVariable UUID eventId,
                                        @AuthenticationPrincipal CustomUserDetails principal) {
        return bazar.markSent(eventId, principal.userId());
    }

    @PostMapping("/{eventId}/start")
    public Map<String, Object> start(@PathVariable UUID eventId,
                                     @AuthenticationPrincipal CustomUserDetails principal) {
        return bazar.start(eventId, principal.userId());
    }

    @GetMapping("/{eventId}/prices")
    public List<Map<String, Object>> prices(@PathVariable UUID eventId) {
        return bazar.listPrices(eventId);
    }

    /** Both null clears the rule (prices go back to Shopify's); overrides are kept. */
    @PutMapping("/{eventId}/bulk-rule")
    public List<Map<String, Object>> bulkRule(@PathVariable UUID eventId, @RequestBody BulkRuleRequest req,
                                              @AuthenticationPrincipal CustomUserDetails principal) {
        return bazar.setBulkRule(eventId, req.discountPct(), req.roundTo(), principal.userId());
    }

    @PutMapping("/{eventId}/prices/{variantId}")
    public List<Map<String, Object>> override(@PathVariable UUID eventId, @PathVariable UUID variantId,
                                              @RequestBody PriceRequest req,
                                              @AuthenticationPrincipal CustomUserDetails principal) {
        return bazar.setOverride(eventId, variantId, req.price(), principal.userId());
    }

    @DeleteMapping("/{eventId}/prices/{variantId}/override")
    public List<Map<String, Object>> clearOverride(@PathVariable UUID eventId, @PathVariable UUID variantId,
                                                   @AuthenticationPrincipal CustomUserDetails principal) {
        return bazar.clearOverride(eventId, variantId, principal.userId());
    }

    public record CreateRequest(String name, LocalDate startsOn, LocalDate endsOn, UUID locationId,
                                String locationName, List<String> paymentMethods, List<String> sellerRoles,
                                String shopifySyncMode, BigDecimal discountPct, BigDecimal roundTo) {}

    public record ScanRequest(String barcode, UUID relayEventId) {}

    public record CountRequest(@com.fasterxml.jackson.annotation.JsonProperty("variant_id") UUID variantId,
                               Integer quantity,
                               @com.fasterxml.jackson.annotation.JsonProperty("client_request_id") String clientRequestId) {}

    public record RemoveRequest(String code,
                                @com.fasterxml.jackson.annotation.JsonProperty("variant_id") UUID variantId,
                                Integer quantity,
                                @com.fasterxml.jackson.annotation.JsonProperty("client_request_id") String clientRequestId) {}

    public record BulkRuleRequest(BigDecimal discountPct, BigDecimal roundTo) {}

    public record PriceRequest(BigDecimal price) {}
}
