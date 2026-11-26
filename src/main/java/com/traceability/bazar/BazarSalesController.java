package com.traceability.bazar;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
        return sales.sell(eventId, req.code(), req.variantId(), req.clientRequestId(), req.method(), actor(principal));
    }

    /** B3: take back a piece sold at this event. */
    @PostMapping("/return")
    public Map<String, Object> returnSale(@PathVariable UUID eventId, @RequestBody ReturnRequest req,
                                          @AuthenticationPrincipal CustomUserDetails principal) {
        return sales.returnSale(eventId, req.code(), req.saleId(), req.method(), req.condition(), req.clientRequestId(),
            actor(principal));
    }

    /** B3: the old piece back, the new piece sold — one transaction; only the difference moves as money. */
    @PostMapping("/exchange")
    public Map<String, Object> exchange(@PathVariable UUID eventId, @RequestBody ExchangeRequest req,
                                        @AuthenticationPrincipal CustomUserDetails principal) {
        return sales.exchange(eventId, req.oldCode(), req.oldSaleId(), req.newCode(), req.newVariantId(), req.method(),
            req.condition(), req.clientRequestId(), actor(principal));
    }

    /**
     * B7c: no label — the active sales of a variant at this event (newest first, at most 10), optionally by the
     * customer's phone or the receipt number. Never chooses: the screen confirms one or lists several. A POST with the
     * phone in the JSON body (customer details, 2026-11-26) — never in the URL, where access logs would keep it. A read:
     * nothing is written.
     */
    @PostMapping("/sales/find")
    public Map<String, Object> findSales(@PathVariable UUID eventId, @RequestBody FindSalesRequest req,
                                         @AuthenticationPrincipal CustomUserDetails principal) {
        return sales.findSales(eventId, req.variantId(), req.phone(), req.receipt(), actor(principal));
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

    /** B6c: what each scanned code would be as a cart line (read-only — nothing is sold or reserved). */
    @PostMapping("/cart/preview")
    public Map<String, Object> cartPreview(@PathVariable UUID eventId, @RequestBody CartPreviewRequest req,
                                           @AuthenticationPrincipal CustomUserDetails principal) {
        return sales.previewCartLines(eventId, req == null ? null : lines(req.lines(), req.codes()), actor(principal));
    }

    /** B6c: sell every piece of the cart in one transaction, or none. */
    @PostMapping("/cart/complete")
    public Map<String, Object> cartComplete(@PathVariable UUID eventId, @RequestBody CartCompleteRequest req,
                                            @AuthenticationPrincipal CustomUserDetails principal) {
        CartCustomer c = req.customer();
        return sales.completeCartLines(eventId, lines(req.lines(), req.codes()), req.discount(), req.method(),
            c == null ? null : c.name(), c == null ? null : c.phone(), req.clientRequestId(), actor(principal));
    }

    /** B6c: undo a whole cart. */
    @PostMapping("/receipts/{receiptId}/undo")
    public Map<String, Object> undoReceipt(@PathVariable UUID eventId, @PathVariable UUID receiptId,
                                           @AuthenticationPrincipal CustomUserDetails principal) {
        return sales.undoReceipt(eventId, receiptId, actor(principal));
    }

    /** B6c: change how a whole cart was paid. */
    @PutMapping("/receipts/{receiptId}/payment")
    public Map<String, Object> receiptPayment(@PathVariable UUID eventId, @PathVariable UUID receiptId,
                                              @RequestBody PaymentRequest req,
                                              @AuthenticationPrincipal CustomUserDetails principal) {
        return sales.changeReceiptPayment(eventId, receiptId, req.method(), actor(principal));
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
    /** B7a: {@code lines} ([{code} | {variant_id}]) when sent, else B6c's {@code codes}. */
    private static List<BazarSaleService.CartLine> lines(List<CartLineRequest> lines, List<String> codes) {
        if (lines == null) return BazarSaleService.codeLines(codes);
        return lines.stream().map(l -> l == null ? new BazarSaleService.CartLine("", null)
            : new BazarSaleService.CartLine(l.code(), l.variantId())).toList();
    }

    public record CartLineRequest(String code,
                                  @com.fasterxml.jackson.annotation.JsonProperty("variant_id")
                                  @com.fasterxml.jackson.annotation.JsonAlias("variantId") UUID variantId) {}

    /** B7c find: the size, and optionally the customer's phone / the receipt number — in the body, never the URL. */
    public record FindSalesRequest(@com.fasterxml.jackson.annotation.JsonProperty("variant_id")
                                   @com.fasterxml.jackson.annotation.JsonAlias("variantId") UUID variantId,
                                   String phone, String receipt) {}

    /** {@code code} (a scan) or B7a {@code variant_id} (a tap — the server picks the piece). */
    public record SellRequest(String code,
                              @com.fasterxml.jackson.annotation.JsonProperty("variant_id")
                              @com.fasterxml.jackson.annotation.JsonAlias("variantId") UUID variantId,
                              @com.fasterxml.jackson.annotation.JsonProperty("client_request_id")
                              @com.fasterxml.jackson.annotation.JsonAlias("clientRequestId")
                              String clientRequestId,
                              String method) {}

    public record PaymentRequest(String method) {}

    public record CartPreviewRequest(List<String> codes, List<CartLineRequest> lines) {}

    public record CartCustomer(String name, String phone) {}

    /** discount: whole pounds (≥ 0, ≤ the cart's total). method: default cash. customer: optional. */
    public record CartCompleteRequest(List<String> codes, List<CartLineRequest> lines, java.math.BigDecimal discount,
                                      String method,
                                      CartCustomer customer,
                                      @com.fasterxml.jackson.annotation.JsonProperty("client_request_id")
                                      @com.fasterxml.jackson.annotation.JsonAlias("clientRequestId")
                                      String clientRequestId) {}

    /** condition: good | damaged (required). method: optional — default the method the customer paid with. */
    /** {@code code} (scanned) or B7c {@code sale_id} (found by product + size). */
    public record ReturnRequest(String code,
                                @com.fasterxml.jackson.annotation.JsonProperty("sale_id")
                                @com.fasterxml.jackson.annotation.JsonAlias("saleId") UUID saleId,
                                String method, String condition,
                                @com.fasterxml.jackson.annotation.JsonProperty("client_request_id")
                                @com.fasterxml.jackson.annotation.JsonAlias("clientRequestId")
                                String clientRequestId) {}

    /** condition: optional, the old piece's (default good). method: for the difference, when there is one. */
    public record ExchangeRequest(@com.fasterxml.jackson.annotation.JsonProperty("old_code")
                                  @com.fasterxml.jackson.annotation.JsonAlias("oldCode") String oldCode,
                                  @com.fasterxml.jackson.annotation.JsonProperty("old_sale_id")
                                  @com.fasterxml.jackson.annotation.JsonAlias("oldSaleId") UUID oldSaleId,
                                  @com.fasterxml.jackson.annotation.JsonProperty("new_code")
                                  @com.fasterxml.jackson.annotation.JsonAlias("newCode") String newCode,
                                  @com.fasterxml.jackson.annotation.JsonProperty("new_variant_id")
                                  @com.fasterxml.jackson.annotation.JsonAlias("newVariantId") UUID newVariantId,
                                  String method, String condition,
                                  @com.fasterxml.jackson.annotation.JsonProperty("client_request_id")
                                  @com.fasterxml.jackson.annotation.JsonAlias("clientRequestId")
                                  String clientRequestId) {}
}
