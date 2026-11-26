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

    /** U1d — what the New bazar form may offer: {@code keep_available} (Main reconcile writes for this store). */
    @GetMapping("/online-stock")
    public Map<String, Object> onlineStockOptions() {
        return bazar.onlineStockOptions();
    }

    @GetMapping("/{eventId}")
    public Map<String, Object> get(@PathVariable UUID eventId) {
        return bazar.get(eventId);
    }

    /** U1b — delete a draft bazar that nothing was ever scanned out for (soft delete; its transfer is cancelled). */
    @DeleteMapping("/{eventId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID eventId, @AuthenticationPrincipal CustomUserDetails principal) {
        bazar.delete(eventId, principal.userId());
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

    // ── Top-up: add pieces to a live bazar (V245) ────────────────────────────

    /** Opens the bazar's top-up batch (or returns the one already open). Live bazars only. */
    @PostMapping("/{eventId}/batches")
    public Map<String, Object> openBatch(@PathVariable UUID eventId,
                                         @AuthenticationPrincipal CustomUserDetails principal) {
        return bazar.openBatch(eventId, principal.userId());
    }

    @GetMapping("/{eventId}/batches/current")
    public Map<String, Object> currentBatch(@PathVariable UUID eventId) {
        return bazar.currentBatch(eventId);
    }

    /** Lists one scanned piece on the batch (nothing moves until Send). */
    @PostMapping("/{eventId}/batches/{batchId}/scan")
    public TransferService.ScanOutResult batchScan(@PathVariable UUID eventId, @PathVariable UUID batchId,
                                                   @RequestBody ScanRequest req,
                                                   @AuthenticationPrincipal CustomUserDetails principal) {
        return bazar.batchScan(eventId, batchId, req.barcode(), principal.userId());
    }

    @PostMapping("/{eventId}/batches/{batchId}/count")
    public TransferService.CountResult batchCount(@PathVariable UUID eventId, @PathVariable UUID batchId,
                                                  @RequestBody CountRequest req,
                                                  @AuthenticationPrincipal CustomUserDetails principal) {
        return bazar.batchAddCount(eventId, batchId, req.variantId(), req.quantity(), req.clientRequestId(),
            principal.userId());
    }

    @PostMapping("/{eventId}/batches/{batchId}/remove")
    public TransferService.CountResult batchRemove(@PathVariable UUID eventId, @PathVariable UUID batchId,
                                                   @RequestBody RemoveRequest req,
                                                   @AuthenticationPrincipal CustomUserDetails principal) {
        return bazar.batchRemove(eventId, batchId, req.code(), req.variantId(), req.quantity(), req.clientRequestId(),
            principal.userId());
    }

    @PostMapping("/{eventId}/batches/{batchId}/discard")
    public Map<String, Object> discardBatch(@PathVariable UUID eventId, @PathVariable UUID batchId,
                                            @AuthenticationPrincipal CustomUserDetails principal) {
        return bazar.discardBatch(eventId, batchId, principal.userId());
    }

    /** "Send these to the bazar" — body {client_request_id}. */
    @PostMapping("/{eventId}/batches/{batchId}/send")
    public Map<String, Object> sendBatch(@PathVariable UUID eventId, @PathVariable UUID batchId,
                                         @RequestBody SendBatchRequest req,
                                         @AuthenticationPrincipal CustomUserDetails principal) {
        return bazar.sendBatch(eventId, batchId, req.clientRequestId(), principal.userId());
    }

    public record SendBatchRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("client_request_id") String clientRequestId) {}

    /** B8 — Remove from online stock / Keep showing online; only while draft and preparing (locked after send). */
    @PutMapping("/{eventId}/online-stock")
    public Map<String, Object> setOnlineStock(@PathVariable UUID eventId, @RequestBody OnlineStockRequest req,
                                              @AuthenticationPrincipal CustomUserDetails principal) {
        return bazar.setOnlineStock(eventId, req == null ? null : req.mode(), principal.userId());
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

    public record OnlineStockRequest(String mode) {}

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
