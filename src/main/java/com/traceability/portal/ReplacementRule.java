package com.traceability.portal;

import java.util.List;
import java.util.UUID;

/**
 * Defect replacement (V248, 2026-11-27) — THE rule for "Replace with the same item" (never re-derive it):
 * an exchange whose replacement is the SAME variant as the item coming back is allowed only for a defect reason —
 * {@link #DEFECT_REASONS}, a fixed list in code (Marawan Q1: damaged + wrong_item; not not_as_pictured, and never the
 * per-store merchant-fault list). Such a request is stored with {@code return_requests.exchange_kind = 'replacement'};
 * every other exchange is a 'swap'.
 *
 * <ul>
 *   <li>Pricing: a replacement NEVER has a price difference (no charge, no refund), under any setting. Its fee is
 *       charged only when the store's "Charge fees on defect replacements" ({@code tenants.portal_replacement_fees}) is
 *       on — then the exchange rule applies and the merchant-fault waiver does not.</li>
 *   <li>Stock: the same check as every exchange (VariantStockService available &gt; 0) — the customer's own item is
 *       delivered, never on hand, so a second unit must really be in stock.</li>
 *   <li>Everything after submit (approval, Bosta type 30, other couriers, reservation, the Step 5a −1, the Shopify return
 *       sync, Scan returns) is the exchange path unchanged — only the wording differs.</li>
 * </ul>
 */
public final class ReplacementRule {

    private ReplacementRule() {}

    public static final String KIND_SWAP = "swap";
    public static final String KIND_REPLACEMENT = "replacement";

    /** The reasons that allow a replacement with the same item (Q1). */
    public static final List<String> DEFECT_REASONS = List.of("damaged", "wrong_item");

    public static boolean defectReason(String reasonCode) {
        return reasonCode != null && DEFECT_REASONS.contains(reasonCode);
    }

    /** Same variant out as back. */
    public static boolean sameItem(UUID itemVariant, UUID replacementVariant) {
        return itemVariant != null && itemVariant.equals(replacementVariant);
    }

    /**
     * The order ids (of {@code ids}) that are the replacement order of a replacement request — a Bosta exchange's
     * internal order (exchanges.outbound_order_id of a request's exchanges row) or another courier's
     * (return_requests.replacement_order_id). Params: tenant, ids (uuid[]), tenant, ids (uuid[]).
     */
    public static final String REPLACEMENT_ORDERS_SQL =
        "SELECT x.outbound_order_id AS order_id FROM exchanges x " +
        "JOIN return_requests rr ON rr.id = x.return_request_id AND rr.tenant_id = x.tenant_id " +
        "WHERE x.tenant_id = ? AND x.outbound_order_id = ANY (?::uuid[]) AND rr.exchange_kind = 'replacement' " +
        "UNION " +
        "SELECT rr.replacement_order_id FROM return_requests rr " +
        "WHERE rr.tenant_id = ? AND rr.replacement_order_id = ANY (?::uuid[]) AND rr.exchange_kind = 'replacement'";
}
