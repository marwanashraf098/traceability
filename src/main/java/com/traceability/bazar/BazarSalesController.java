package com.traceability.bazar;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Bazar POS B2 — quick sell, undo, edits, the sales list. Any signed-in role reaches these endpoints; WHO may act is
 * decided per event by {@link BazarSaleService} (the event's {@code seller_roles}, owner / manager authority, the
 * seller's own 2-minute window). Every endpoint answers 404 BAZAR_NOT_ENABLED unless {@code tenants.bazar_enabled}.
 */
@RestController
@RequestMapping("/api/v1/bazar-events/{eventId}")
@PreAuthorize("hasAnyRole('OWNER','MANAGER','WORKER')")
public class BazarSalesController {

    private final BazarSaleService sales;

    public BazarSalesController(BazarSaleService sales) {
        this.sales = sales;
    }

    @PostMapping("/sell")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, Object> sell(@PathVariable UUID eventId, @RequestBody SellRequest req,
                                    @AuthenticationPrincipal CustomUserDetails principal) {
        return sales.sell(eventId, req.code(), req.clientRequestId(), req.method(), actor(principal));
    }

    /** B3: take back a piece sold at this event. */
    @PostMapping("/return")
    public Map<String, Object> returnSale(@PathVariable UUID eventId, @RequestBody ReturnRequest req,
                                          @AuthenticationPrincipal CustomUserDetails principal) {
        return sales.returnSale(eventId, req.code(), req.method(), req.condition(), req.clientRequestId(),
            actor(principal));
    }

    /** B3: the old piece back, the new piece sold — one transaction; only the difference moves as money. */
    @PostMapping("/exchange")
    public Map<String, Object> exchange(@PathVariable UUID eventId, @RequestBody ExchangeRequest req,
                                        @AuthenticationPrincipal CustomUserDetails principal) {
        return sales.exchange(eventId, req.oldCode(), req.newCode(), req.method(), req.condition(),
            req.clientRequestId(), actor(principal));
    }

    @PostMapping("/sales/{saleId}/undo")
    public Map<String, Object> undo(@PathVariable UUID eventId, @PathVariable UUID saleId,
                                    @AuthenticationPrincipal CustomUserDetails principal) {
        return sales.undo(eventId, saleId, actor(principal));
    }

    @PutMapping("/sales/{saleId}/payment")
    public Map<String, Object> payment(@PathVariable UUID eventId, @PathVariable UUID saleId,
                                       @RequestBody PaymentRequest req,
                                       @AuthenticationPrincipal CustomUserDetails principal) {
        return sales.changePayment(eventId, saleId, req.method(), actor(principal));
    }

    @PutMapping("/sales/{saleId}/customer")
    public Map<String, Object> customer(@PathVariable UUID eventId, @PathVariable UUID saleId,
                                        @RequestBody com.fasterxml.jackson.databind.JsonNode req,
                                        @AuthenticationPrincipal CustomUserDetails principal) {
        // A field not sent stays as it is; sent as null / "" it is cleared (B6 review).
        boolean nameSent = req != null && req.has("name");
        boolean phoneSent = req != null && req.has("phone");
        return sales.editCustomer(eventId, saleId, nameSent, text(req, "name"), phoneSent, text(req, "phone"),
            actor(principal));
    }

    /** B3: what a scanned piece is at this event (read-only) — the Return / Exchange screens' preview. */
    @GetMapping("/piece")
    public Map<String, Object> piece(@PathVariable UUID eventId, @RequestParam String code,
                                     @AuthenticationPrincipal CustomUserDetails principal) {
        return sales.lookupPiece(eventId, code, actor(principal));
    }

    @GetMapping("/today")
    public Map<String, Object> today(@PathVariable UUID eventId,
                                     @AuthenticationPrincipal CustomUserDetails principal) {
        return sales.todaySummary(eventId, actor(principal));
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) return null;
        return n.get(field).asText();
    }

    @GetMapping("/sales")
    public Map<String, Object> list(@PathVariable UUID eventId,
                                    @RequestParam(required = false) Integer limit,
                                    @RequestParam(required = false) String cursor,
                                    @AuthenticationPrincipal CustomUserDetails principal) {
        return sales.list(eventId, limit, cursor, actor(principal));
    }

    private static BazarSaleService.Actor actor(CustomUserDetails p) {
        return new BazarSaleService.Actor(p.userId(), p.role());
    }

    /** {@code client_request_id} also accepted in camelCase. */
    public record SellRequest(String code,
                              @com.fasterxml.jackson.annotation.JsonProperty("client_request_id")
                              @com.fasterxml.jackson.annotation.JsonAlias("clientRequestId")
                              String clientRequestId,
                              String method) {}

    public record PaymentRequest(String method) {}

    /** condition: good | damaged (required). method: optional — default the method the customer paid with. */
    public record ReturnRequest(String code, String method, String condition,
                                @com.fasterxml.jackson.annotation.JsonProperty("client_request_id")
                                @com.fasterxml.jackson.annotation.JsonAlias("clientRequestId")
                                String clientRequestId) {}

    /** condition: optional, the old piece's (default good). method: for the difference, when there is one. */
    public record ExchangeRequest(@com.fasterxml.jackson.annotation.JsonProperty("old_code")
                                  @com.fasterxml.jackson.annotation.JsonAlias("oldCode") String oldCode,
                                  @com.fasterxml.jackson.annotation.JsonProperty("new_code")
                                  @com.fasterxml.jackson.annotation.JsonAlias("newCode") String newCode,
                                  String method, String condition,
                                  @com.fasterxml.jackson.annotation.JsonProperty("client_request_id")
                                  @com.fasterxml.jackson.annotation.JsonAlias("clientRequestId")
                                  String clientRequestId) {}
}
