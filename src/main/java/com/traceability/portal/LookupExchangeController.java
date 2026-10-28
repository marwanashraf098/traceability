package com.traceability.portal;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * C2 Lookup Exchange — owner / manager only. Every endpoint answers 409 LOOKUP_EXCHANGE_UNAVAILABLE unless the store
 * ships with its own courier and has no active Bosta account (LookupExchangeService.gateOpen); cancel is gated by
 * the request itself (origin 'lookup').
 */
@RestController
@RequestMapping("/api/v1")
public class LookupExchangeController {

    private final LookupExchangeService service;

    public LookupExchangeController(LookupExchangeService service) {
        this.service = service;
    }

    /** Step 1 — the customer's original orders matching q (number, name, phone) with something to bring back. */
    @GetMapping("/lookup-exchange/orders")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<Map<String, Object>> searchOrders(@RequestParam(required = false) String q) {
        return service.searchOrders(q);
    }

    /** Step 2 — what can come back on that order: delivered pieces, and untracked units one row each. */
    @GetMapping("/lookup-exchange/orders/{orderId}/returnables")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<Map<String, Object>> returnables(@PathVariable UUID orderId) {
        return service.returnables(orderId);
    }

    public record ExchangeBody(UUID orderId, String returningPieceId, UUID returningOrderItemId, Integer returningUnitNo,
                               String reasonCode, String carrier, String outboundTrackingNumber,
                               String returnTrackingNumber) {}

    /** Step 3 — create the exchange; this piece is the replacement. */
    @PostMapping("/pieces/{id}/exchange")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    public LookupExchangeService.Created create(@PathVariable String id, @RequestBody(required = false) ExchangeBody body,
                                                @AuthenticationPrincipal CustomUserDetails principal) {
        LookupExchangeService.CreateRequest req = body == null ? null : new LookupExchangeService.CreateRequest(
            body.orderId(), body.returningPieceId(), body.returningOrderItemId(), body.returningUnitNo(),
            body.reasonCode(), body.carrier(), body.outboundTrackingNumber(), body.returnTrackingNumber());
        return service.create(id, req, principal.userId());
    }

    /** Cancel exchange — only before the replacement left. */
    @PostMapping("/return-requests/{id}/cancel-exchange")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID id, @AuthenticationPrincipal CustomUserDetails principal) {
        service.cancel(id, principal.userId());
    }
}
