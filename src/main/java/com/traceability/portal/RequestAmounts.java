package com.traceability.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Returns portal fees F2 — a request's FROZEN amounts ({@code return_request_amounts}, written once at submit by
 * {@link PortalAmounts#freeze}): the merchant drawer's breakdown, the refund suggestion's net, and the owner /
 * manager override or waiver — before approval only (status 'requested'; the V210 trigger refuses any later UPDATE),
 * recorded as an {@code amounts_overridden} request event through {@link ReturnRequestLifecycle} with before / after.
 *
 * Not a bean: built on the caller's JdbcTemplate (joins its transaction; works on an app_user connection), like
 * ReturnRequestLifecycle. A request with no row (fees off, or no fee rule on when it was submitted) has no amounts —
 * every caller then behaves exactly as before F2.
 */
public class RequestAmounts {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final BigDecimal MAX_FEE = new BigDecimal("30000");

    private final JdbcTemplate jdbc;

    public RequestAmounts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The stored row, or empty. */
    public Optional<Map<String, Object>> row(UUID tenantId, UUID requestId) {
        return jdbc.queryForList(
            "SELECT a.*, a.fee_rule_snapshot::text AS snapshot, a.lines::text AS lines_json, u.name AS overridden_by_name " +
            "FROM return_request_amounts a LEFT JOIN users u ON u.id = a.overridden_by " +
            "WHERE a.request_id = ? AND a.tenant_id = ?", requestId, tenantId).stream().findFirst();
    }

    /** The drawer's breakdown (GET /return-requests/{id} → "amounts"); empty when the request has none. */
    public Optional<Map<String, Object>> forDetail(UUID tenantId, UUID requestId, String status) {
        return row(tenantId, requestId).map(a -> {
            Map<String, Object> snap = parse((String) a.get("snapshot"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", a.get("fee_kind"));
            m.put("currency", a.get("currency"));
            m.put("itemValue", plain(a.get("item_value")));
            m.put("approximate", approximate((String) a.get("lines_json")));
            m.put("fee", plain(a.get("fee_amount")));
            m.put("feeWaived", a.get("fee_waived"));
            m.put("waiveReason", a.get("waive_reason"));
            m.put("calculatedFee", snap.get("calculatedFee"));
            m.put("mode", snap.get("mode"));
            m.put("percent", snap.get("percent"));
            m.put("areaSource", snap.get("source"));
            m.put("refundDue", plain(a.get("refund_due")));
            // F2: an exchange's fee is what the customer owes. Bosta COD stays 0 until F4 — the drawer shows it as
            // an instruction to collect, nothing is sent to any courier.
            m.put("collect", plain(a.get("collect_cod")));
            m.put("nothingOwed", a.get("refund_due") != null && ((BigDecimal) a.get("refund_due")).signum() == 0);
            m.put("overriddenAt", a.get("overridden_at") == null ? null : a.get("overridden_at").toString());
            m.put("overriddenByName", a.get("overridden_by_name"));
            m.put("canOverride", "requested".equals(status));
            m.put("lines", parseList((String) a.get("lines_json")));
            return m;
        });
    }

    /**
     * PUT /return-requests/{id}/amounts — {waived:true} waives the fee (reason merchant_override); {fee:N} sets it
     * (whole EGP, 0..30,000). Only while the request is 'requested' (409 otherwise; the trigger is the backstop).
     * No change → no event. Recomputes due / collect from the frozen item value — never re-prices anything.
     */
    public void override(UUID tenantId, UUID requestId, Boolean waived, BigDecimal fee, UUID actor) {
        boolean waive = Boolean.TRUE.equals(waived);
        if (!waive && (fee == null || fee.signum() < 0 || fee.compareTo(MAX_FEE) > 0
                || fee.stripTrailingZeros().scale() > 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a whole amount from 0 to 30,000.");
        }
        List<Map<String, Object>> rr = jdbc.queryForList(
            "SELECT status::text AS status FROM return_requests WHERE id = ? AND tenant_id = ? FOR UPDATE", requestId, tenantId);
        if (rr.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Return request not found");
        Map<String, Object> a = row(tenantId, requestId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "This request has no fee."));
        if (!"requested".equals(rr.get(0).get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The fee can only be changed before the request is approved.");
        }
        BigDecimal newFee = waive ? BigDecimal.ZERO : PortalAmounts.whole(fee);
        String reason = waive ? "merchant_override" : null;
        BigDecimal oldFee = (BigDecimal) a.get("fee_amount");
        boolean oldWaived = Boolean.TRUE.equals(a.get("fee_waived"));
        if (oldFee.compareTo(newFee) == 0 && oldWaived == waive && Objects.equals(a.get("waive_reason"), reason)) return;

        BigDecimal value = (BigDecimal) a.get("item_value");
        boolean refund = "refund".equals(a.get("fee_kind"));
        BigDecimal due = refund ? value.subtract(newFee).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP) : null;
        BigDecimal collect = refund ? BigDecimal.ZERO : newFee;
        jdbc.update(
            "UPDATE return_request_amounts SET fee_amount = ?, fee_waived = ?, waive_reason = ?, collect_cod = ?, " +
            "    refund_due = ?, overridden_at = now(), overridden_by = ? WHERE request_id = ? AND tenant_id = ?",
            newFee, waive, reason, collect, due, actor, requestId, tenantId);
        Map<String, Object> from = new LinkedHashMap<>();
        from.put("fee", oldFee.setScale(2, RoundingMode.HALF_UP).toPlainString());
        from.put("waived", oldWaived);
        from.put("waive_reason", a.get("waive_reason"));
        Map<String, Object> to = new LinkedHashMap<>();
        to.put("fee", newFee.setScale(2, RoundingMode.HALF_UP).toPlainString());
        to.put("waived", waive);
        to.put("waive_reason", reason);
        new ReturnRequestLifecycle(jdbc).event(tenantId, requestId, "amounts_overridden", actor,
            ReturnRequestLifecycle.meta("from", from, "to", to));
    }

    /**
     * The refund suggestion's net for the given items (item ids → their frozen unit prices), or null when the request
     * has no frozen REFUND amounts (the suggestion then works exactly as before F2). Paid price − the request's fee,
     * floored at 0: the fee is charged once per request.
     */
    public record Net(BigDecimal itemValue, BigDecimal fee, boolean feeWaived, BigDecimal amount, boolean approximate,
                      Map<String, BigDecimal> unitByItem) {}

    public Net net(UUID tenantId, UUID requestId, Collection<UUID> itemIds) {
        Optional<Map<String, Object>> o = row(tenantId, requestId);
        if (o.isEmpty() || !"refund".equals(o.get().get("fee_kind"))) return null;
        Map<String, Object> a = o.get();
        Map<String, BigDecimal> unit = new HashMap<>();
        boolean approx = false;
        for (Map<String, Object> l : parseList((String) a.get("lines_json"))) {
            Object p = l.get("unitPrice");
            unit.put(String.valueOf(l.get("key")), p == null ? null : new BigDecimal(p.toString()));
            if (p == null || "catalog".equals(l.get("priceSource"))) approx = true;
        }
        BigDecimal value = BigDecimal.ZERO;
        for (UUID id : itemIds) {
            BigDecimal p = unit.get(id.toString());
            if (p != null) value = value.add(p);
        }
        value = value.setScale(2, RoundingMode.HALF_UP);
        BigDecimal fee = ((BigDecimal) a.get("fee_amount")).setScale(2, RoundingMode.HALF_UP);
        return new Net(value, fee, Boolean.TRUE.equals(a.get("fee_waived")),
            value.subtract(fee).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP), approx, unit);
    }

    /** True when the request's frozen refund due is exactly 0 (fee ≥ item value) — it may close as nothing_owed. */
    public boolean nothingOwed(UUID tenantId, UUID requestId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM return_request_amounts WHERE request_id = ? AND tenant_id = ? " +
            "  AND fee_kind = 'refund' AND refund_due = 0)", Boolean.class, requestId, tenantId));
    }

    private static boolean approximate(String linesJson) {
        for (Map<String, Object> l : parseList(linesJson)) {
            if (l.get("unitPrice") == null || "catalog".equals(l.get("priceSource"))) return true;
        }
        return false;
    }

    private static String plain(Object v) {
        return v == null ? null : ((BigDecimal) v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        try {
            return json == null ? Map.of() : JSON.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseList(String json) {
        try {
            return json == null ? List.of() : JSON.readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
