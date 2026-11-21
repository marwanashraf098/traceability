package com.traceability.bazar;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Bazar POS B7a / B7b — selling without barcodes: the event's catalog (products by #number with sizes, bazar prices
 * and what's left) and its A4 price sheet. The event's sellers (role in seller_roles, not removed) and owners /
 * managers — checked in {@link BazarCatalogService}. 404 BAZAR_NOT_ENABLED unless {@code tenants.bazar_enabled}.
 */
@RestController
@RequestMapping("/api/v1/bazar-events/{eventId}")
public class BazarCatalogController {

    private final BazarCatalogService catalogs;

    public BazarCatalogController(BazarCatalogService catalogs) {
        this.catalogs = catalogs;
    }

    @GetMapping("/catalog")
    public Map<String, Object> catalog(@PathVariable UUID eventId, @AuthenticationPrincipal CustomUserDetails p) {
        return catalogs.catalogView(eventId, actor(p));
    }

    /** B7d-2 — leftovers without a barcode still out, for "Bring back by count" (the event's sellers). */
    @GetMapping("/bring-back-stock")
    public Map<String, Object> bringBackStock(@PathVariable UUID eventId, @AuthenticationPrincipal CustomUserDetails p) {
        return catalogs.bringBackStockView(eventId, actor(p));
    }

    /** The A4 price sheet, {@code lang} en (default) | ar. */
    @GetMapping("/price-sheet.pdf")
    public ResponseEntity<byte[]> priceSheet(@PathVariable UUID eventId, @RequestParam(required = false) String lang,
                                             @AuthenticationPrincipal CustomUserDetails p) {
        byte[] pdf = BazarPriceSheet.pdf(catalogs.catalog(eventId, actor(p)), lang);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bazar-price-sheet.pdf\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(pdf);
    }

    private static BazarSaleService.Actor actor(CustomUserDetails p) {
        return new BazarSaleService.Actor(p.userId(), p.role());
    }
}
