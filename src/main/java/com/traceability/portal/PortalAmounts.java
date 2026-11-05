package com.traceability.portal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Returns portal fees F2 — THE calculator of a request's customer fee and what is due (the ONE definition: the portal
 * quote, the frozen amounts at submit and the merchant's override all go through it — never re-derive it).
 *
 * <ul>
 *   <li><b>Terms</b> ({@link #terms}): the kind's rule ({@code portal_fee_rules}) when it is ENABLED, its amounts
 *       replaced by the most specific area override — district, then city, then the rule. No enabled rule = no fee
 *       (and nothing is frozen: stores that leave fees off see no change).</li>
 *   <li><b>Fixed:</b> charged once per request, unless {@code waive_merchant_fault} and EVERY item's reason is one of
 *       the merchant-fault reasons (then waived, reason {@code merchant_fault}).</li>
 *   <li><b>Percent:</b> per item — a merchant-fault item (when waiving) adds nothing; the others' paid prices are
 *       summed, × percent, clamped to min / max. Every item merchant-fault → waived.</li>
 *   <li>Fees are whole EGP, ROUND_HALF_UP (Marawan, Q5).</li>
 *   <li><b>Refund:</b> due = item value − fee, floored at 0 (fee ≥ value → 0: the request is allowed and closed as
 *       {@code nothing_owed}). <b>Exchange</b> (F2, same product, no price difference yet): the customer owes the fee —
 *       {@code collect} — shown to the merchant as an instruction only; Bosta COD stays 0 until F4.</li>
 * </ul>
 *
 * Pure arithmetic plus loaders that take the CALLER's JdbcTemplate (so they join its transaction and work on an
 * app_user connection). No PII anywhere in a quote.
 */
public final class PortalAmounts {

    private PortalAmounts() {}

    public static final String CURRENCY = "EGP";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** The fee terms that apply: the rule's mode / amounts, or an area override's. {@code source}: rule | city | district. */
    public record Terms(String kind, String mode, BigDecimal amount, BigDecimal percent, BigDecimal min, BigDecimal max,
                        boolean waiveMerchantFault, String source, String cityId, String districtId) {
        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("mode", mode);
            m.put("amount", plain(amount));
            m.put("percent", plain(percent));
            m.put("min", plain(min));
            m.put("max", plain(max));
            m.put("waiveMerchantFault", waiveMerchantFault);
            m.put("source", source);
            m.put("cityId", cityId);
            m.put("districtId", districtId);
            return m;
        }
    }

    /**
     * One returned unit. {@code key} identifies it to the caller (an item id once stored). {@code unitPrice} null =
     * no price known (counts 0). {@code priceSource}: {@link OrderLinePaidPrices.Source} name, lower case.
     */
    public record Item(String key, UUID variantId, UUID orderItemId, BigDecimal unitPrice, String priceSource, String reason) {}

    public record Line(Item item, boolean merchantFault, boolean waived) {
        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", item.key());
            m.put("variantId", item.variantId() == null ? null : item.variantId().toString());
            m.put("orderItemId", item.orderItemId() == null ? null : item.orderItemId().toString());
            m.put("unitPrice", plain(item.unitPrice()));
            m.put("priceSource", item.priceSource());
            m.put("reason", item.reason());
            m.put("merchantFault", merchantFault);
            m.put("waived", waived);
            return m;
        }
    }

    /**
     * The result. {@code fee} is what is charged (0 when waived). {@code refundDue} null for an exchange;
     * {@code collect} 0 for a refund.
     */
    public record Quote(String kind, Terms terms, BigDecimal itemValue, boolean approximate, BigDecimal fee,
                        boolean feeWaived, String waiveReason, BigDecimal refundDue, BigDecimal collect, List<Line> lines) {

        public boolean nothingOwed() { return refundDue != null && refundDue.signum() == 0; }

        /**
         * fee / item value × 100 (4 dp) — kept for S5's Shopify returnCreate; null when the value is 0 or the fee is
         * more than 10× the value (the column is numeric(7,4); S5 maps the fee as a fixed amount anyway).
         */
        public BigDecimal effectivePercent() {
            if (itemValue.signum() == 0) return null;
            BigDecimal p = fee.multiply(HUNDRED).divide(itemValue, 4, RoundingMode.HALF_UP);
            return p.compareTo(new BigDecimal("1000")) >= 0 ? null : p;
        }

        /** The customer-facing quote (portal). No PII; item keys are the caller's. */
        public Map<String, Object> toPortalJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("currency", CURRENCY);
            m.put("itemValue", plain(itemValue));
            m.put("approximate", approximate);
            m.put("fee", plain(fee));
            m.put("feeWaived", feeWaived);
            m.put("waiveReason", waiveReason);
            m.put("mode", terms.mode());
            m.put("percent", plain(terms.percent()));
            m.put("refundDue", plain(refundDue));
            m.put("collect", plain(collect));
            m.put("nothingOwed", nothingOwed());
            List<Map<String, Object>> ls = new ArrayList<>();
            for (Line l : lines) {
                Map<String, Object> lm = new LinkedHashMap<>();
                lm.put("key", l.item().key());
                lm.put("unitPrice", plain(l.item().unitPrice()));
                lm.put("waived", l.waived());
                ls.add(lm);
            }
            m.put("lines", ls);
            return m;
        }
    }

    // ── the calculation ──────────────────────────────────────────────────────

    /** THE calculation. {@code terms} non-null (no terms = no fee = no quote). */
    public static Quote calculate(Terms terms, Set<String> merchantFaultReasons, List<Item> items) {
        Objects.requireNonNull(terms, "terms");
        boolean waive = terms.waiveMerchantFault();
        List<Line> lines = new ArrayList<>(items.size());
        BigDecimal value = BigDecimal.ZERO, chargeable = BigDecimal.ZERO;
        boolean approximate = false, allFault = !items.isEmpty();
        for (Item it : items) {
            boolean fault = it.reason() != null && merchantFaultReasons.contains(it.reason());
            boolean waived = waive && fault;
            BigDecimal price = it.unitPrice() == null ? BigDecimal.ZERO : it.unitPrice();
            value = value.add(price);
            if (!waived) chargeable = chargeable.add(price);
            if (!fault) allFault = false;
            if (it.unitPrice() == null || "catalog".equals(it.priceSource())) approximate = true;
            lines.add(new Line(it, fault, waived));
        }
        value = value.setScale(2, RoundingMode.HALF_UP);

        BigDecimal fee;
        boolean feeWaived;
        if ("fixed".equals(terms.mode())) {
            feeWaived = waive && allFault;
            fee = feeWaived ? BigDecimal.ZERO : whole(terms.amount());
            // A fixed fee is charged once — the per-item "waived" marks only say which items were merchant-fault.
            if (!feeWaived) lines.replaceAll(l -> new Line(l.item(), l.merchantFault(), false));
        } else {
            boolean anyChargeable = lines.stream().anyMatch(l -> !l.waived());
            feeWaived = !anyChargeable && !lines.isEmpty();
            if (!anyChargeable) {
                fee = BigDecimal.ZERO;
            } else {
                BigDecimal raw = chargeable.multiply(terms.percent()).divide(HUNDRED, 4, RoundingMode.HALF_UP);
                if (terms.min() != null && raw.compareTo(terms.min()) < 0) raw = terms.min();
                if (terms.max() != null && raw.compareTo(terms.max()) > 0) raw = terms.max();
                fee = whole(raw);
            }
        }
        String waiveReason = feeWaived ? "merchant_fault" : null;
        return withFee(terms, value, approximate, fee, feeWaived, waiveReason, lines);
    }

    /** Re-derives due / collect for a given fee (the merchant's override uses this too). */
    static Quote withFee(Terms terms, BigDecimal value, boolean approximate, BigDecimal fee, boolean feeWaived,
                         String waiveReason, List<Line> lines) {
        boolean refund = "refund".equals(terms.kind());
        BigDecimal due = refund ? value.subtract(fee).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP) : null;
        BigDecimal collect = refund ? BigDecimal.ZERO.setScale(2) : fee.setScale(2, RoundingMode.HALF_UP);
        return new Quote(terms.kind(), terms, value, approximate, fee.setScale(2, RoundingMode.HALF_UP), feeWaived,
                         waiveReason, due, collect, List.copyOf(lines));
    }

    /** Whole EGP, half up (Q5). */
    static BigDecimal whole(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(0, RoundingMode.HALF_UP);
    }

    // ── the inputs (caller's connection, caller's tenant context) ─────────────

    /**
     * The terms for {@code kind} in an area, or empty when the kind's rule is absent or switched off. The area is a
     * Bosta city and, optionally, one of its districts (null for an order with no Bosta area: the rule alone).
     */
    public static Optional<Terms> terms(JdbcTemplate jdbc, UUID tenantId, String kind, String cityId, String districtId) {
        List<Map<String, Object>> rule = jdbc.queryForList(
            "SELECT mode, amount, percent, min_amount, max_amount, waive_merchant_fault FROM portal_fee_rules " +
            "WHERE tenant_id = ? AND kind = ? AND enabled", tenantId, kind);
        if (rule.isEmpty()) return Optional.empty();
        Map<String, Object> r = rule.get(0);
        boolean waive = Boolean.TRUE.equals(r.get("waive_merchant_fault"));
        if (cityId != null) {
            // District beats city: an exact district row first, else the city-wide row (district NULL).
            List<Map<String, Object>> o = jdbc.queryForList(
                "SELECT mode, amount, percent, min_amount, max_amount, district_id FROM portal_fee_area_overrides " +
                "WHERE tenant_id = ? AND kind = ? AND city_id = ? AND (district_id IS NULL OR district_id = ?) " +
                "ORDER BY district_id IS NULL, district_id LIMIT 1",
                tenantId, kind, cityId, districtId);
            if (!o.isEmpty()) {
                Map<String, Object> m = o.get(0);
                String d = (String) m.get("district_id");
                return Optional.of(new Terms(kind, (String) m.get("mode"), (BigDecimal) m.get("amount"),
                    (BigDecimal) m.get("percent"), (BigDecimal) m.get("min_amount"), (BigDecimal) m.get("max_amount"),
                    waive, d == null ? "city" : "district", cityId, d));
            }
        }
        return Optional.of(new Terms(kind, (String) r.get("mode"), (BigDecimal) r.get("amount"),
            (BigDecimal) r.get("percent"), (BigDecimal) r.get("min_amount"), (BigDecimal) r.get("max_amount"),
            waive, "rule", cityId, districtId));
    }

    /** True when the kind's rule is switched on (the cheap pre-check before resolving prices). */
    public static boolean ruleEnabled(JdbcTemplate jdbc, UUID tenantId, String kind) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM portal_fee_rules WHERE tenant_id = ? AND kind = ? AND enabled)",
            Boolean.class, tenantId, kind));
    }

    /** The tenant's merchant-fault reasons (F1 setting). */
    public static Set<String> merchantFaultReasons(JdbcTemplate jdbc, UUID tenantId) {
        Object arr = jdbc.queryForObject("SELECT portal_merchant_fault_reasons FROM tenants WHERE id = ?", Object.class, tenantId);
        return new HashSet<>(PortalSettingsService.textArray(arr));
    }

    /**
     * Prices by order line and by variant, from the F0 resolver's answer. A tracked piece is priced by its variant
     * (the best-sourced line of that variant on the order); an untracked unit by its own order line.
     */
    public record Prices(Map<UUID, OrderLinePaidPrices.LinePrice> byOrderItem,
                         Map<UUID, OrderLinePaidPrices.LinePrice> byVariant) {
        public static Prices of(List<OrderLinePaidPrices.LinePrice> lines) {
            Map<UUID, OrderLinePaidPrices.LinePrice> byItem = new HashMap<>(), byVariant = new HashMap<>();
            for (OrderLinePaidPrices.LinePrice lp : lines) {
                byItem.put(lp.orderItemId(), lp);
                byVariant.merge(lp.variantId(), lp, (a, b) -> b.source().ordinal() < a.source().ordinal() ? b : a);
            }
            return new Prices(byItem, byVariant);
        }

        public Item item(String key, UUID variantId, UUID orderItemId, String reason) {
            OrderLinePaidPrices.LinePrice lp = orderItemId != null ? byOrderItem.get(orderItemId) : byVariant.get(variantId);
            if (lp == null) return new Item(key, variantId, orderItemId, null, "none", reason);
            return new Item(key, variantId, orderItemId, lp.unitPrice(), lp.source().name().toLowerCase(Locale.ROOT), reason);
        }
    }

    // ── freezing (return_request_amounts) ─────────────────────────────────────

    /** Writes the request's frozen amounts (once, at submit, in the submit transaction). */
    public static void freeze(JdbcTemplate jdbc, UUID tenantId, UUID requestId, Quote q) {
        BigDecimal originalUnit = q.lines().size() == 1 ? q.lines().get(0).item().unitPrice() : null;
        String originalSource = q.lines().size() == 1 ? q.lines().get(0).item().priceSource() : null;
        Map<String, Object> snapshot = q.terms().toJson();
        snapshot.put("calculatedFee", plain(q.fee()));
        snapshot.put("calculatedWaived", q.feeWaived());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Line l : q.lines()) lines.add(l.toJson());
        jdbc.update(
            "INSERT INTO return_request_amounts (request_id, tenant_id, currency, original_unit_price, original_price_source, " +
            "    item_value, fee_kind, fee_amount, fee_rule_snapshot, fee_waived, waive_reason, collect_cod, customer_owed, " +
            "    refund_due, lines, fee_shopify_kind, fee_effective_percent) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, 0, ?, ?::jsonb, 'return_shipping', ?)",
            requestId, tenantId, CURRENCY, originalUnit, originalSource, q.itemValue(), q.kind(), q.fee(),
            toJson(snapshot), q.feeWaived(), q.waiveReason(), q.collect(), q.refundDue(), toJson(lines),
            q.effectivePercent());
    }

    static String plain(BigDecimal v) { return v == null ? null : v.toPlainString(); }

    static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
