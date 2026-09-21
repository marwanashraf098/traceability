package com.traceability.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * FR-EXCHANGE Step 3 Part B — Option A fuzzy matcher for the INBOUND (old item) leg.
 * Resolves {@code exchanges.matched_order_id}: which of the customer's prior, already-
 * delivered orders the physical old item is coming back from. Distinct from
 * {@link ExchangeService} (Phase 2 — maps the OUTBOUND/new-item leg to a variant + the
 * synthetic Model-A order) and from {@link ExchangeIngestService} (Phase 1 — ingest).
 *
 * Gated on status='mapped' (or a prior open re-check — see {@link #OPEN_FOR_MATCHING}),
 * NOT 'needs_mapping': ExchangeService.map()'s claim UPDATE
 * ({@code WHERE status = 'needs_mapping'}) must stay the exclusive writer of that
 * transition. Matching only makes sense once the exchange is confirmed real (Phase 2
 * done) — attempting it any earlier would race Phase 2's claim and, if this method won,
 * permanently 409 the operator's map() call. needs_mapping is never touched here.
 *
 * Trigger points (Part 0 — hook the existing ingest/interpret path, no new poll):
 *   BostaWebhookJob calls {@link #attemptMatch} after every webhook for this tracking
 *   once a forward shipment exists (Step 2 Part A's post-pack per-leg branch) — that is
 *   every webhook once Phase 2 has run, since {@code raw} (and therefore
 *   {@code returnSpecs}) refreshes on every one of them. Also called from the pre-pack
 *   ROUTED branch for symmetry; it is a cheap no-op there today (status is
 *   'needs_mapping' pre-Phase-2) but costs one indexed lookup.
 *
 * Reuses (never re-implements): {@link ShipmentLinkService#normalizePhone} for the
 * canonical 01XXXXXXXXX form, and the SAME phone-normalization SQL expression
 * {@code matchByPhoneAndCod} uses against {@code orders.customer_phone} — extracted here
 * as {@link #ORDER_PHONE_MATCH_EXPR} so the two never drift. COD is NOT reused —
 * {@code matchByPhoneAndCod} hard-requires it (Part 0.2 finding); an exchange's inbound
 * leg has no COD value of its own to compare, so this is phone-only by design, narrowed
 * by the existing {@code tenants.customer_return_window_days} window (same column
 * ReturnSessionService.withinReturnWindow() already reads) and, only when phone alone
 * leaves more than one candidate, a best-effort textual match against
 * {@code returnSpecs.description} (Part 0.5 finding: free-text, NOT reliably
 * deterministic — used only to narrow multiple candidates down to exactly one; never to
 * force a pick among several that still look equally plausible after narrowing).
 */
@Service
public class ExchangeMatchService {

    private static final Set<String> OPEN_FOR_MATCHING = Set.of("mapped", "unmatched", "needs_confirmation");

    // Part D — the two statuses a merchant/manual action can resolve FROM. 'mapped'
    // deliberately excluded: an exchange still awaiting its first auto-match attempt
    // hasn't had a chance to resolve itself yet — jumping straight to a manual action
    // would race attemptMatch() the same way matching an already-'needs_mapping' exchange
    // would race ExchangeService.map() (see class javadoc).
    private static final Set<String> OPEN_FOR_MANUAL_RESOLUTION = Set.of("unmatched", "needs_confirmation");

    // Same expression matchByPhoneAndCod() uses (ShipmentLinkService) — kept identical so
    // the two matchers can never silently diverge on what "same phone" means.
    static final String ORDER_PHONE_MATCH_EXPR =
        "'0' || RIGHT(REGEXP_REPLACE(o.customer_phone, '[^0-9]', '', 'g'), 10)";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ExchangeMatchService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private record ExchangeRow(UUID id, String status, String inboundDescription,
                                UUID outboundOrderId, String rawJson) {}

    /** One delivered piece from a candidate order — the eventual link target. */
    public record Candidate(String pieceId, UUID orderId, String variantTitle, String productTitle) {}

    /**
     * @return the candidate pieces the phone+window query currently finds for this
     *         exchange — same query {@link #attemptMatch} uses. Exposed for a future
     *         Step 4 "needs_confirmation" UI to render ranked candidates without a
     *         separate persisted table (Part B: "persist... or derive at read" — this
     *         is the derive-at-read choice).
     */
    @Transactional(readOnly = true)
    public List<Candidate> listCandidates(UUID exchangeId) {
        UUID tenantId = TenantContext.require();
        ExchangeRow ex = loadExchange(exchangeId, tenantId);
        if (ex == null) return List.of();
        JsonNode raw = parseRaw(ex.rawJson());
        if (raw == null) return List.of();
        String bostaPhone = ShipmentLinkService.normalizePhone(raw.path("receiver").path("phone").asText(null));
        if (bostaPhone == null) return List.of();
        int windowDays = returnWindowDays(tenantId);
        return findCandidates(tenantId, bostaPhone, windowDays, ex.outboundOrderId());
    }

    /**
     * Attempts to resolve {@code matched_order_id} for the exchange with this tracking
     * number, if it is currently open for matching. Idempotent: re-running against the
     * same underlying data always reaches the same status/link (a repeat auto-link
     * re-writes the identical matched_order_id; a repeat non-match re-derives the same
     * candidate set).
     */
    @Transactional
    public void attemptMatch(String trackingNumber) {
        UUID tenantId = TenantContext.require();

        ExchangeRow ex = jdbc.query(
            "SELECT id, status, inbound_description, outbound_order_id, raw::text AS raw " +
            "FROM exchanges WHERE tenant_id = ? AND tracking_number = ?",
            rs -> rs.next() ? new ExchangeRow(
                rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getString("inbound_description"),
                rs.getObject("outbound_order_id", UUID.class), rs.getString("raw")) : null,
            tenantId, trackingNumber);
        if (ex == null || !OPEN_FOR_MATCHING.contains(ex.status())) return;

        JsonNode raw = parseRaw(ex.rawJson());
        if (raw == null) return;

        // returnSpecs not populated yet (pre state-41 — Step 2 Part B §2b) — not a
        // rejection, just not ready. Leave status untouched; the next webhook re-checks.
        if (raw.path("returnSpecs").isMissingNode()) return;

        String bostaPhone = ShipmentLinkService.normalizePhone(raw.path("receiver").path("phone").asText(null));
        if (bostaPhone == null) {
            // No phone signal at all to search with — genuinely unmatched, not "not yet ready".
            setStatus(ex.id(), tenantId, "unmatched");
            return;
        }

        int windowDays = returnWindowDays(tenantId);
        List<Candidate> candidates = findCandidates(tenantId, bostaPhone, windowDays, ex.outboundOrderId());

        if (candidates.isEmpty()) {
            setStatus(ex.id(), tenantId, "unmatched");
            return;
        }

        List<Candidate> narrowed = candidates;
        if (candidates.size() > 1 && ex.inboundDescription() != null && !ex.inboundDescription().isBlank()) {
            String desc = ex.inboundDescription().toLowerCase(Locale.ROOT);
            // Variant-title match takes precedence over product-title match — candidates
            // sharing a product (different variants, e.g. Red vs Blue of the same Bucket
            // Hat) must never both "match" via the shared product name alone. Product
            // title is only consulted as a fallback when no candidate's variant title
            // appears in the description at all.
            List<Candidate> byVariant = candidates.stream()
                .filter(c -> matchesText(desc, c.variantTitle()))
                .toList();
            if (byVariant.size() == 1) {
                narrowed = byVariant;
            } else if (byVariant.isEmpty()) {
                List<Candidate> byProduct = candidates.stream()
                    .filter(c -> matchesText(desc, c.productTitle()))
                    .toList();
                if (byProduct.size() == 1) narrowed = byProduct;
            }
            // byVariant.size() > 1: still ambiguous at the variant level — never fall
            // through to product-title matching, which could only widen it further.
        }

        if (narrowed.size() == 1) {
            jdbc.update(
                "UPDATE exchanges SET matched_order_id = ?, match_method = 'phone', " +
                "    matched_at = now(), status = 'matched', updated_at = now() " +
                "WHERE id = ? AND tenant_id = ?",
                narrowed.get(0).orderId(), ex.id(), tenantId);
        } else {
            setStatus(ex.id(), tenantId, "needs_confirmation");
        }
    }

    // ── Part D — manual resolution actions for unmatched/needs_confirmation ────
    //
    // All three are claim-before-call (conditional UPDATE, WHERE status IN
    // OPEN_FOR_MANUAL_RESOLUTION): a concurrent second call, or a call against an
    // exchange the auto-matcher already resolved in the meantime, sees 0 rows affected
    // and 409s instead of silently overwriting an existing decision.

    /**
     * Merchant-supplied order attach — the manual counterpart to attemptMatch()'s auto
     * phone match. Same target columns (matched_order_id/match_method/matched_at/status),
     * different match_method ('manual' vs 'phone') and no candidate search: the merchant
     * is asserting the link directly, so it is trusted without a delivered/phone/window
     * check — an operator can attach any of the tenant's own orders.
     */
    @Transactional
    public Map<String, Object> searchAttach(UUID exchangeId, UUID orderId) {
        UUID tenantId = TenantContext.require();

        Boolean orderExists = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM orders WHERE id = ? AND tenant_id = ?)",
            Boolean.class, orderId, tenantId);
        if (!Boolean.TRUE.equals(orderExists)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order not found");
        }

        int claimed = jdbc.update(
            "UPDATE exchanges SET matched_order_id = ?, match_method = 'manual', " +
            "    matched_at = now(), status = 'matched', updated_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND status IN ('unmatched', 'needs_confirmation')",
            orderId, exchangeId, tenantId);
        if (claimed == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Exchange is not open for manual attach");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exchangeId", exchangeId.toString());
        result.put("matchedOrderId", orderId.toString());
        result.put("status", "matched");
        return result;
    }

    /**
     * The physical old item can be scanned, restocked or marked damaged with NO order
     * link at all — custody starts at intake for this return, order_id stays null.
     * Matching never gates whether an item can be taken back onto the shelf; this action
     * only records that the merchant is deliberately not pursuing a match for it.
     */
    @Transactional
    public void acceptAsBareReturn(UUID exchangeId) {
        UUID tenantId = TenantContext.require();
        int claimed = jdbc.update(
            "UPDATE exchanges SET status = 'bare_return', updated_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND status IN ('unmatched', 'needs_confirmation')",
            exchangeId, tenantId);
        if (claimed == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Exchange is not open for this action");
        }
    }

    /** Dismisses an unmatched/needs_confirmation exchange — no further action expected. */
    @Transactional
    public void dismiss(UUID exchangeId) {
        UUID tenantId = TenantContext.require();
        int claimed = jdbc.update(
            "UPDATE exchanges SET status = 'dismissed', updated_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND status IN ('unmatched', 'needs_confirmation')",
            exchangeId, tenantId);
        if (claimed == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Exchange is not open for this action");
        }
    }

    private List<Candidate> findCandidates(UUID tenantId, String bostaPhone, int windowDays,
                                            UUID outboundOrderId) {
        return jdbc.query(
            "SELECT p.id AS piece_id, p.current_order_id AS order_id, " +
            "       v.title AS variant_title, pr.title AS product_title " +
            "FROM pieces p " +
            "JOIN orders o    ON o.id  = p.current_order_id AND o.tenant_id = p.tenant_id " +
            "JOIN variants v  ON v.id  = p.variant_id " +
            "JOIN products pr ON pr.id = v.product_id " +
            "WHERE p.tenant_id = ? " +
            "  AND p.status = 'delivered'::piece_status " +
            "  AND p.last_event_at >= now() - (interval '1 day' * ?) " +
            "  AND " + ORDER_PHONE_MATCH_EXPR + " = ? " +
            // The exchange's OWN synthetic Model-A order (the NEW replacement item) is
            // never the candidate for its OLD item's original order.
            "  AND (?::uuid IS NULL OR o.id <> ?::uuid)",
            (rs, i) -> new Candidate(rs.getString("piece_id"), rs.getObject("order_id", UUID.class),
                rs.getString("variant_title"), rs.getString("product_title")),
            tenantId, windowDays, bostaPhone, outboundOrderId, outboundOrderId);
    }

    private boolean matchesText(String desc, String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        return !t.isBlank() && (desc.contains(t) || t.contains(desc));
    }

    private void setStatus(UUID exchangeId, UUID tenantId, String status) {
        jdbc.update(
            "UPDATE exchanges SET status = ?, updated_at = now() WHERE id = ? AND tenant_id = ?",
            status, exchangeId, tenantId);
    }

    private int returnWindowDays(UUID tenantId) {
        return jdbc.queryForObject(
            "SELECT customer_return_window_days FROM tenants WHERE id = ?", Integer.class, tenantId);
    }

    private ExchangeRow loadExchange(UUID exchangeId, UUID tenantId) {
        return jdbc.query(
            "SELECT id, status, inbound_description, outbound_order_id, raw::text AS raw " +
            "FROM exchanges WHERE id = ? AND tenant_id = ?",
            rs -> rs.next() ? new ExchangeRow(
                rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getString("inbound_description"),
                rs.getObject("outbound_order_id", UUID.class), rs.getString("raw")) : null,
            exchangeId, tenantId);
    }

    private JsonNode parseRaw(String rawJson) {
        try {
            return mapper.readTree(rawJson);
        } catch (Exception e) {
            return null;
        }
    }
}
