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
        public Map<String, Object> toJson() {
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
        public Map<String, Object> toJson() {
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
     * F3 — an exchange's price difference: the replacement's current price − the price paid for the item (signed;
     * positive = the customer pays). Present only when the difference applies: a different product, or the same
     * product while free variant swaps are off.
     */
    public record Difference(BigDecimal paidPrice, BigDecimal replacementPrice, boolean floorAtZero) {
        public Difference(BigDecimal paidPrice, BigDecimal replacementPrice) { this(paidPrice, replacementPrice, false); }

        /**
         * The difference that is charged or paid back. {@code floorAtZero} (a same-product swap while the store's
         * "Same-product swaps can refund the customer" is off): a lower new price never pays the customer back — 0.
         */
        BigDecimal amount() {
            BigDecimal d = replacementPrice.subtract(paidPrice == null ? BigDecimal.ZERO : paidPrice).setScale(2, RoundingMode.HALF_UP);
            return floorAtZero ? d.max(BigDecimal.ZERO.setScale(2)) : d;
        }
    }

    /**
     * The result. {@code fee} is what is charged (0 when waived). {@code terms} null = no fee rule on (an exchange
     * priced only by its difference). {@code refundDue} null for an exchange; {@code collect} 0 for a refund.
     * F3: an exchange's {@code priceDifference} (null when it doesn't apply) and fee combine — net = difference + fee;
     * the customer pays {@code collect} = max(net, 0) or is owed {@code customerOwed} = max(−net, 0).
     */
    public record Quote(String kind, Terms terms, BigDecimal itemValue, boolean approximate, BigDecimal fee,
                        boolean feeWaived, String waiveReason, BigDecimal refundDue, BigDecimal collect, List<Line> lines,
                        Difference difference, BigDecimal customerOwed) {

        public BigDecimal priceDifference() { return difference == null ? null : difference.amount(); }

        /** The same quote with its lines keyed by {@code keys} (in order) — the item ids, once stored. */
        public Quote rekey(List<String> keys) {
            List<Line> out = new ArrayList<>(lines.size());
            for (int i = 0; i < lines.size(); i++) {
                Item it = lines.get(i).item();
                out.add(new Line(new Item(keys.get(i), it.variantId(), it.orderItemId(), it.unitPrice(), it.priceSource(),
                    it.reason()), lines.get(i).merchantFault(), lines.get(i).waived()));
            }
            return new Quote(kind, terms, itemValue, approximate, fee, feeWaived, waiveReason, refundDue, collect, out,
                difference, customerOwed);
        }

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
            m.put("mode", terms == null ? null : terms.mode());
            m.put("percent", terms == null ? null : plain(terms.percent()));
            m.put("refundDue", plain(refundDue));
            m.put("collect", plain(collect));
            m.put("nothingOwed", nothingOwed());
            // F3: present only when an exchange's price difference applies (F2's quotes keep their exact shape).
            if (difference != null) {
                m.put("paidPrice", plain(difference.paidPrice()));
                m.put("replacementPrice", plain(difference.replacementPrice()));
                m.put("priceDifference", plain(priceDifference()));
                m.put("customerOwed", plain(customerOwed));
            }
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

    /** THE calculation, fee only (F2). {@code terms} non-null. */
    public static Quote calculate(Terms terms, Set<String> merchantFaultReasons, List<Item> items) {
        Objects.requireNonNull(terms, "terms");
        return calculate(terms.kind(), terms, merchantFaultReasons, items, null);
    }

    /**
     * THE calculation. {@code terms} null = no fee (an exchange priced by its difference only). {@code difference}
     * null = no price difference (refunds; same-product exchanges while free variant swaps are on).
     */
    public static Quote calculate(String kind, Terms terms, Set<String> merchantFaultReasons, List<Item> items,
                                  Difference difference) {
        boolean waive = terms != null && terms.waiveMerchantFault();
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
        if (terms == null) {
            fee = BigDecimal.ZERO;
            feeWaived = false;
        } else if ("fixed".equals(terms.mode())) {
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
        boolean refund = "refund".equals(kind);
        BigDecimal due = refund ? value.subtract(fee).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP) : null;
        Difference diff = refund ? null : difference;
        if (diff != null && (diff.paidPrice() == null || "catalog".equals(lines.isEmpty() ? null : lines.get(0).item().priceSource()))) {
            approximate = true;
        }
        BigDecimal[] split = split(refund, fee, diff);
        return new Quote(kind, terms, value, approximate, fee.setScale(2, RoundingMode.HALF_UP), feeWaived, waiveReason,
                         due, split[0], List.copyOf(lines), diff, split[1]);
    }

    /**
     * {collect, customerOwed} — a refund collects nothing and owes nothing here (its due is refundDue); an exchange's
     * net = price difference + fee: positive → the customer pays it, negative → the customer is owed it.
     */
    static BigDecimal[] split(boolean refund, BigDecimal fee, Difference diff) {
        if (refund) return new BigDecimal[]{BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2)};
        BigDecimal net = fee.add(diff == null ? BigDecimal.ZERO : diff.amount());
        return new BigDecimal[]{net.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP),
                                net.negate().max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)};
    }

    // ── F3: exchange pricing ─────────────────────────────────────────────────

    /** Bosta's COD limit — what a courier can collect on the replacement (F4 sends it; F3 refuses more). */
    public static final BigDecimal COD_LIMIT = new BigDecimal("30000");

    /**
     * The tenant's exchange pricing settings (F1). {@link #active}: a price difference can apply at all.
     * {@code sameProductRefunds} (V223, "Same-product swaps can refund the customer", default off): whether a
     * same-product swap to a LOWER price pays the customer back the difference. Different products always use the real
     * difference both ways.
     */
    public record ExchangePricing(boolean anyProduct, boolean sameOrLowerOnly, BigDecimal maxDifference, boolean freeSwaps,
                                  boolean sameProductRefunds) {
        /** Before V223 — a same-product swap paid back a lower price (the setting on). */
        public ExchangePricing(boolean anyProduct, boolean sameOrLowerOnly, BigDecimal maxDifference, boolean freeSwaps) {
            this(anyProduct, sameOrLowerOnly, maxDifference, freeSwaps, true);
        }

        public boolean active() { return anyProduct || !freeSwaps; }

        /** Whether a price difference applies to this exchange. */
        public boolean differenceApplies(boolean sameProduct) { return !sameProduct || !freeSwaps; }

        /** The exchange's difference, or null when none applies (decision C: same product + setting off → floored at 0). */
        public Difference difference(boolean sameProduct, BigDecimal paidPrice, BigDecimal replacementPrice) {
            if (!differenceApplies(sameProduct) || replacementPrice == null) return null;
            return new Difference(paidPrice, replacementPrice, sameProduct && !sameProductRefunds);
        }
    }

    public static ExchangePricing exchangePricing(JdbcTemplate jdbc, UUID tenantId) {
        Map<String, Object> t = jdbc.queryForMap(
            "SELECT portal_exchange_any_product, portal_exchange_same_or_lower_only, portal_exchange_max_difference, " +
            "       portal_free_variant_swaps, portal_same_product_swap_refunds FROM tenants WHERE id = ?", tenantId);
        return new ExchangePricing(Boolean.TRUE.equals(t.get("portal_exchange_any_product")),
            Boolean.TRUE.equals(t.get("portal_exchange_same_or_lower_only")),
            (BigDecimal) t.get("portal_exchange_max_difference"),
            Boolean.TRUE.equals(t.get("portal_free_variant_swaps")),
            Boolean.TRUE.equals(t.get("portal_same_product_swap_refunds")));
    }

    /**
     * Why an exchange can't be sent as quoted, or null: {@code approximate_price} (a different product needs the price
     * actually paid — only today's catalogue price is known), {@code same_or_lower_only}, {@code max_difference}, or
     * {@code cod_limit} (more than a courier can collect).
     */
    public static String exchangeBlock(ExchangePricing p, boolean sameProduct, Quote q) {
        if (!sameProduct) {
            Item original = q.lines().isEmpty() ? null : q.lines().get(0).item();
            if (original == null || original.unitPrice() == null
                    || !("stored".equals(original.priceSource()) || "shopify".equals(original.priceSource()))) {
                return "approximate_price";
            }
        }
        BigDecimal diff = q.priceDifference();
        if (diff != null && p.sameOrLowerOnly() && diff.signum() > 0) return "same_or_lower_only";
        if (diff != null && p.maxDifference() != null && diff.compareTo(p.maxDifference()) > 0) return "max_difference";
        if (q.collect().compareTo(COD_LIMIT) > 0) return "cod_limit";
        return null;
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
        Map<String, Object> snapshot = q.terms() == null ? new LinkedHashMap<>() : q.terms().toJson();
        snapshot.put("calculatedFee", plain(q.fee()));
        snapshot.put("calculatedWaived", q.feeWaived());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Line l : q.lines()) lines.add(l.toJson());
        jdbc.update(
            "INSERT INTO return_request_amounts (request_id, tenant_id, currency, original_unit_price, original_price_source, " +
            "    item_value, fee_kind, fee_amount, fee_rule_snapshot, fee_waived, waive_reason, collect_cod, customer_owed, " +
            "    refund_due, lines, fee_shopify_kind, fee_effective_percent, replacement_unit_price, price_difference) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, 'return_shipping', ?, ?, ?)",
            requestId, tenantId, CURRENCY, originalUnit, originalSource, q.itemValue(), q.kind(), q.fee(),
            toJson(snapshot), q.feeWaived(), q.waiveReason(), q.collect(), q.customerOwed(), q.refundDue(), toJson(lines),
            q.effectivePercent(), q.difference() == null ? null : q.difference().replacementPrice(), q.priceDifference());
    }

    public static String plain(BigDecimal v) { return v == null ? null : v.toPlainString(); }

    public static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
