package com.traceability.inventory;

import com.traceability.identity.CustomUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FR-EXCHANGE Phase 2 (outbound mapping) + Step 3 Part D (inbound match resolution).
 * Same access tier as UnlinkedDeliveryController (OWNER/MANAGER) — both mapping and
 * unmatched-exchange resolution are ambiguous-record resolution actions, not routine
 * worker pick/pack.
 */
@RestController
@RequestMapping("/api/v1/exchanges")
public class ExchangeController {

    private final ExchangeService svc;
    private final ExchangeMatchService matchSvc;
    private final ExchangeOrderSearch orderSearch;

    public ExchangeController(ExchangeService svc, ExchangeMatchService matchSvc, ExchangeOrderSearch orderSearch) {
        this.svc = svc;
        this.matchSvc = matchSvc;
        this.orderSearch = orderSearch;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<Map<String, Object>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        return svc.list(status, page, Math.min(size, 100));
    }

    /** Step 4a-1 — single-exchange detail, same row shape as list(). */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> detail(@PathVariable UUID id) {
        return svc.detail(id);
    }

    /** Step 4a-1 — wires the already-built ExchangeMatchService.listCandidates(); derived at read. */
    @GetMapping("/{id}/candidates")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<ExchangeMatchService.Candidate> candidates(@PathVariable UUID id) {
        return matchSvc.listCandidates(id);
    }

    @PostMapping("/{id}/map")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> map(@PathVariable UUID id, @RequestBody MapRequest req) {
        return svc.map(id, req.outboundVariantId(), req.inboundVariantId());
    }

    /**
     * S3a (2026-11-18) — the N-row mapping: outbound rows (each mapped) and inbound rows (a variant or unknown), each
     * with a quantity and a description hint. One internal:exchange: order with one line per outbound row.
     */
    @PostMapping("/{id}/map-items")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> mapItems(@PathVariable UUID id, @RequestBody MapItemsRequest req,
                                        @AuthenticationPrincipal CustomUserDetails principal) {
        return svc.mapItems(id, req.outbound(), req.inbound(), principal == null ? null : principal.userId());
    }

    /** S3a — the linked original order's lines (what the old items can be), for the mapping screen's inbound rows. */
    @GetMapping("/{id}/original-lines")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<Map<String, Object>> originalLines(@PathVariable UUID id) {
        return svc.originalLines(id);
    }

    /** Part A/C — what the resolver currently says for this exchange's outbound leg
     *  (EXACT/RECS/NONE + ranked candidates), without committing anything. Feeds the
     *  mapping screen's RECS pre-population. */
    @GetMapping("/{id}/outbound-candidates")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> outboundCandidates(@PathVariable UUID id) {
        return svc.outboundResolution(id);
    }

    /** Part B — corrects an auto-committed (or any still-unpicked) exchange's outbound
     *  variant before pack. */
    @PostMapping("/{id}/outbound-variant")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> overrideOutboundVariant(@PathVariable UUID id, @RequestBody OverrideOutboundRequest req) {
        return svc.overrideOutboundVariant(id, req.variantId(), req.orderItemId());
    }

    /** Part D — merchant supplies the order the old item is actually returning from. */
    @PostMapping("/{id}/attach")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> attach(@PathVariable UUID id, @RequestBody AttachRequest req) {
        return matchSvc.searchAttach(id, req.orderId());
    }

    /**
     * S2 (2026-11-18) — the drawer's "Link to order" search: the tenant's orders by number / phone / name (empty q →
     * suggestions by Bosta's receiver phone), max 20, plus an "Import from Shopify" offer for a pre-connect number.
     */
    @GetMapping("/{id}/order-search")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ExchangeOrderSearch.SearchResult orderSearch(@PathVariable UUID id,
                                                        @RequestParam(required = false) String q) {
        return orderSearch.search(id, q);
    }

    /**
     * S2 — "Import from Shopify": the S1 pre-connect parent import (no transaction open — it calls Shopify), then, with
     * {@code link: true}, the exchange is linked to it. A miss is a 200 with its status, not an error.
     */
    @PostMapping("/{id}/import-parent")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ExchangeOrderSearch.ImportResult importParent(@PathVariable UUID id, @RequestBody ImportParentRequest req) {
        return orderSearch.importParent(id, req.number(), Boolean.TRUE.equals(req.link()));
    }

    /** Part D — accept the physical item back with no order link (custody starts at intake). */
    @PostMapping("/{id}/bare-return")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public void bareReturn(@PathVariable UUID id) {
        matchSvc.acceptAsBareReturn(id);
    }

    /** Part D — dismiss an unmatched/needs_confirmation exchange. */
    @PostMapping("/{id}/dismiss")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public void dismiss(@PathVariable UUID id) {
        matchSvc.dismiss(id);
    }

    public record MapRequest(UUID outboundVariantId, UUID inboundVariantId) {}
    public record AttachRequest(UUID orderId) {}
    public record ImportParentRequest(String number, Boolean link) {}
    /** S3a: {@code orderItemId} — which line of a multi-line replacement order (optional on a single line). */
    public record OverrideOutboundRequest(UUID variantId, UUID orderItemId) {}
    public record MapItemsRequest(List<ExchangeService.ItemRow> outbound, List<ExchangeService.ItemRow> inbound) {}
}
