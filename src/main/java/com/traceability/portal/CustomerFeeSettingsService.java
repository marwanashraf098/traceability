package com.traceability.portal;

import com.traceability.account.AuditService;
import com.traceability.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Array;
import java.time.Instant;
import java.util.*;

/**
 * Returns portal fees F1 — Settings → Returns portal → Customer fees and Exchange pricing (V210).
 *
 * <p><b>Data only in F1:</b> nothing reads these settings for a decision yet — the portal lookup, submission,
 * approval, booking and refund suggestion are unchanged whatever is saved here (F2 calculates fees, F3 the
 * price difference and the different-product picker). Until F2 ships, the section is hidden behind
 * {@code traced.portal-fees.settings-enabled} (default false): GET answers {@code visible:false} and every write
 * 404s, so no merchant can switch on a fee that wouldn't be charged.
 *
 * <p>Its own endpoints, so the existing portal-settings response is unchanged. Owner writes (the controller
 * gates it), owner + manager read. Each real change is one audit_log row (from → to); a save that changes
 * nothing writes none. Amounts are whole EGP, percentages at most 2 decimals; area overrides by Bosta city or
 * district (bosta_districts ids), never zone.
 */
@Service
public class CustomerFeeSettingsService {

    public static final List<String> KINDS = List.of("exchange", "refund");
    static final BigDecimal MAX_AMOUNT = new BigDecimal("30000");

    public record Rule(String kind, boolean enabled, String mode, BigDecimal amount, BigDecimal percent,
                       BigDecimal minAmount, BigDecimal maxAmount, boolean waiveMerchantFault, Instant updatedAt) {}

    public record RuleInput(Boolean enabled, String mode, BigDecimal amount, BigDecimal percent,
                            BigDecimal minAmount, BigDecimal maxAmount, Boolean waiveMerchantFault) {}

    public record Override(UUID id, String kind, String cityId, String cityName, String cityNameAr,
                           String districtId, String districtName, String districtNameAr,
                           String mode, BigDecimal amount, BigDecimal percent, BigDecimal minAmount, BigDecimal maxAmount) {}

    public record OverrideInput(String kind, String cityId, String districtId, String mode, BigDecimal amount,
                                BigDecimal percent, BigDecimal minAmount, BigDecimal maxAmount) {}

    public record ExchangePricing(boolean anyProduct, boolean sameOrLowerOnly, BigDecimal maxDifference) {}

    public record ExchangePricingInput(Boolean anyProduct, Boolean sameOrLowerOnly, BigDecimal maxDifference) {}

    public record Settings(boolean visible, Map<String, Rule> rules, List<String> merchantFaultReasons,
                           List<Override> overrides, ExchangePricing exchangePricing) {}

    public record Area(String id, String name, String nameAr) {}

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final boolean visible;

    public CustomerFeeSettingsService(JdbcTemplate jdbc, AuditService audit,
                                      @Value("${traced.portal-fees.settings-enabled:false}") boolean visible) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.visible = visible;
    }

    public boolean visible() { return visible; }

    // ── read ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Settings get() {
        UUID tenantId = TenantContext.require();
        Map<String, Rule> rules = new LinkedHashMap<>();
        for (String k : KINDS) rules.put(k, null);
        jdbc.query("SELECT kind, enabled, mode, amount, percent, min_amount, max_amount, waive_merchant_fault, updated_at " +
                   "FROM portal_fee_rules WHERE tenant_id = ?", rs -> {
            rules.put(rs.getString("kind"), new Rule(rs.getString("kind"), rs.getBoolean("enabled"), rs.getString("mode"),
                rs.getBigDecimal("amount"), rs.getBigDecimal("percent"), rs.getBigDecimal("min_amount"),
                rs.getBigDecimal("max_amount"), rs.getBoolean("waive_merchant_fault"), rs.getTimestamp("updated_at").toInstant()));
        }, tenantId);

        Map<String, Object> t = jdbc.queryForMap(
            "SELECT portal_exchange_any_product, portal_exchange_same_or_lower_only, portal_exchange_max_difference, " +
            "       portal_merchant_fault_reasons FROM tenants WHERE id = ?", tenantId);

        List<Override> overrides = jdbc.query(
            "SELECT o.id, o.kind, o.city_id, c.city_name, c.city_name_ar, o.district_id, d.district_name, d.district_name_ar, " +
            "       o.mode, o.amount, o.percent, o.min_amount, o.max_amount " +
            "FROM portal_fee_area_overrides o " +
            "LEFT JOIN LATERAL (SELECT city_name, city_name_ar FROM bosta_districts WHERE city_id = o.city_id " +
            "                   ORDER BY district_id LIMIT 1) c ON true " +
            "LEFT JOIN bosta_districts d ON d.district_id = o.district_id " +
            "WHERE o.tenant_id = ? " +
            "ORDER BY o.kind, c.city_name NULLS LAST, o.city_id, d.district_name NULLS FIRST, o.id",
            (rs, i) -> new Override(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("city_id"),
                rs.getString("city_name"), rs.getString("city_name_ar"), rs.getString("district_id"),
                rs.getString("district_name"), rs.getString("district_name_ar"), rs.getString("mode"),
                rs.getBigDecimal("amount"), rs.getBigDecimal("percent"), rs.getBigDecimal("min_amount"),
                rs.getBigDecimal("max_amount")),
            tenantId);

        return new Settings(visible, rules, reasons(t.get("portal_merchant_fault_reasons")), overrides,
            new ExchangePricing(Boolean.TRUE.equals(t.get("portal_exchange_any_product")),
                Boolean.TRUE.equals(t.get("portal_exchange_same_or_lower_only")),
                (BigDecimal) t.get("portal_exchange_max_difference")));
    }

    /** Bosta cities (cityId null) or the districts of one city — names only, for the area override picker. */
    @Transactional(readOnly = true)
    public List<Area> areas(String cityId) {
        TenantContext.require();
        if (!visible) throw CustomerFeeException.notAvailable();
        if (cityId == null || cityId.isBlank()) {
            return jdbc.query(
                "SELECT DISTINCT ON (city_id) city_id, city_name, city_name_ar FROM bosta_districts " +
                "ORDER BY city_id, district_id",
                (rs, i) -> new Area(rs.getString(1), rs.getString(2), rs.getString(3)))
                .stream().sorted(Comparator.comparing(Area::name, String.CASE_INSENSITIVE_ORDER)).toList();
        }
        return jdbc.query(
            "SELECT district_id, district_name, district_name_ar FROM bosta_districts WHERE city_id = ? " +
            "ORDER BY lower(district_name), district_id",
            (rs, i) -> new Area(rs.getString(1), rs.getString(2), rs.getString(3)), cityId);
    }

    // ── writes (owner; one audit row per real change) ───────────────────────────

    @Transactional
    public Settings putRule(UUID actor, String kind, RuleInput in) {
        UUID tenantId = TenantContext.require();
        if (!visible) throw CustomerFeeException.notAvailable();
        if (!KINDS.contains(kind)) throw CustomerFeeException.kind();
        if (in == null) throw CustomerFeeException.mode();
        Shape s = shape(in.mode(), in.amount(), in.percent(), in.minAmount(), in.maxAmount(), false);
        boolean enabled = Boolean.TRUE.equals(in.enabled());
        boolean waive = in.waiveMerchantFault() == null || in.waiveMerchantFault();

        Map<String, Object> before = ruleSnapshot(tenantId, kind, true);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("enabled", enabled);
        after.put("mode", s.mode);
        after.put("amount", s.amount);
        after.put("percent", s.percent);
        after.put("minAmount", s.min);
        after.put("maxAmount", s.max);
        after.put("waiveMerchantFault", waive);
        if (after.equals(before)) return get();

        jdbc.update(
            "INSERT INTO portal_fee_rules (tenant_id, kind, enabled, mode, amount, percent, min_amount, max_amount, " +
            "                              waive_merchant_fault, updated_by, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()) " +
            "ON CONFLICT (tenant_id, kind) DO UPDATE SET enabled = EXCLUDED.enabled, mode = EXCLUDED.mode, " +
            "    amount = EXCLUDED.amount, percent = EXCLUDED.percent, min_amount = EXCLUDED.min_amount, " +
            "    max_amount = EXCLUDED.max_amount, waive_merchant_fault = EXCLUDED.waive_merchant_fault, " +
            "    updated_by = EXCLUDED.updated_by, updated_at = now()",
            tenantId, kind, enabled, s.mode, s.amount, s.percent, s.min, s.max, waive, actor);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("from", before);
        m.put("to", after);
        audit.record(actor, "portal_fee_rule_update", "tenant", tenantId.toString(), m);
        return get();
    }

    @Transactional
    public Settings putMerchantFaultReasons(UUID actor, List<String> reasons) {
        UUID tenantId = TenantContext.require();
        if (!visible) throw CustomerFeeException.notAvailable();
        if (reasons == null) throw CustomerFeeException.reason();
        // Kept in the portal's own reason order, de-duplicated.
        for (String r : reasons) if (r == null || !PortalService.REASON_CODES.contains(r)) throw CustomerFeeException.reason();
        List<String> next = PortalService.REASON_CODES.stream().filter(reasons::contains).toList();
        List<String> was = reasons(jdbc.queryForObject(
            "SELECT portal_merchant_fault_reasons FROM tenants WHERE id = ? FOR UPDATE", Object.class, tenantId));
        if (new HashSet<>(was).equals(new HashSet<>(next))) return get();
        jdbc.update("UPDATE tenants SET portal_merchant_fault_reasons = ?::text[] WHERE id = ?",
            "{" + String.join(",", next) + "}", tenantId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", was);
        m.put("to", next);
        audit.record(actor, "portal_merchant_fault_reasons_update", "tenant", tenantId.toString(), m);
        return get();
    }

    @Transactional
    public Settings putExchangePricing(UUID actor, ExchangePricingInput in) {
        UUID tenantId = TenantContext.require();
        if (!visible) throw CustomerFeeException.notAvailable();
        if (in == null) throw CustomerFeeException.maxDifference();
        BigDecimal maxDiff = in.maxDifference();
        if (maxDiff != null && !wholeInRange(maxDiff, true)) throw CustomerFeeException.maxDifference();
        Map<String, Object> t = jdbc.queryForMap(
            "SELECT portal_exchange_any_product, portal_exchange_same_or_lower_only, portal_exchange_max_difference " +
            "FROM tenants WHERE id = ? FOR UPDATE", tenantId);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("anyProduct", Boolean.TRUE.equals(t.get("portal_exchange_any_product")));
        before.put("sameOrLowerOnly", Boolean.TRUE.equals(t.get("portal_exchange_same_or_lower_only")));
        before.put("maxDifference", norm((BigDecimal) t.get("portal_exchange_max_difference")));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("anyProduct", Boolean.TRUE.equals(in.anyProduct()));
        after.put("sameOrLowerOnly", Boolean.TRUE.equals(in.sameOrLowerOnly()));
        after.put("maxDifference", norm(maxDiff));
        if (after.equals(before)) return get();
        jdbc.update("UPDATE tenants SET portal_exchange_any_product = ?, portal_exchange_same_or_lower_only = ?, " +
                    "    portal_exchange_max_difference = ? WHERE id = ?",
            after.get("anyProduct"), after.get("sameOrLowerOnly"), after.get("maxDifference"), tenantId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", before);
        m.put("to", after);
        audit.record(actor, "portal_exchange_pricing_update", "tenant", tenantId.toString(), m);
        return get();
    }

    @Transactional
    public Settings createOverride(UUID actor, OverrideInput in) {
        UUID tenantId = TenantContext.require();
        if (!visible) throw CustomerFeeException.notAvailable();
        if (in == null || !KINDS.contains(in.kind())) throw CustomerFeeException.kind();
        String cityId = blankToNull(in.cityId());
        String districtId = blankToNull(in.districtId());
        checkArea(cityId, districtId);
        Shape s = shape(in.mode(), in.amount(), in.percent(), in.minAmount(), in.maxAmount(), true);
        UUID id;
        try {
            id = jdbc.queryForObject(
                "INSERT INTO portal_fee_area_overrides (tenant_id, kind, city_id, district_id, mode, amount, percent, " +
                "                                       min_amount, max_amount, updated_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                UUID.class, tenantId, in.kind(), cityId, districtId, s.mode, s.amount, s.percent, s.min, s.max, actor);
        } catch (DuplicateKeyException e) {
            throw CustomerFeeException.overrideExists();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("overrideId", id.toString());
        m.put("to", overrideSnapshot(in.kind(), cityId, districtId, s));
        audit.record(actor, "portal_fee_override_create", "tenant", tenantId.toString(), m);
        return get();
    }

    /** Changes the amount of an area override; its kind and area stay (delete and add to move it). */
    @Transactional
    public Settings updateOverride(UUID actor, UUID id, OverrideInput in) {
        UUID tenantId = TenantContext.require();
        if (!visible) throw CustomerFeeException.notAvailable();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT kind, city_id, district_id, mode, amount, percent, min_amount, max_amount " +
            "FROM portal_fee_area_overrides WHERE id = ? AND tenant_id = ? FOR UPDATE", id, tenantId);
        if (rows.isEmpty()) throw CustomerFeeException.overrideNotFound();
        if (in == null) throw CustomerFeeException.mode();
        Map<String, Object> r = rows.get(0);
        Shape was = new Shape((String) r.get("mode"), norm((BigDecimal) r.get("amount")), norm((BigDecimal) r.get("percent")),
            norm((BigDecimal) r.get("min_amount")), norm((BigDecimal) r.get("max_amount")));
        Shape s = shape(in.mode(), in.amount(), in.percent(), in.minAmount(), in.maxAmount(), true);
        if (s.equals(was)) return get();
        jdbc.update("UPDATE portal_fee_area_overrides SET mode = ?, amount = ?, percent = ?, min_amount = ?, max_amount = ?, " +
                    "    updated_by = ?, updated_at = now() WHERE id = ? AND tenant_id = ?",
            s.mode, s.amount, s.percent, s.min, s.max, actor, id, tenantId);
        String kind = (String) r.get("kind"), cityId = (String) r.get("city_id"), districtId = (String) r.get("district_id");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("overrideId", id.toString());
        m.put("from", overrideSnapshot(kind, cityId, districtId, was));
        m.put("to", overrideSnapshot(kind, cityId, districtId, s));
        audit.record(actor, "portal_fee_override_update", "tenant", tenantId.toString(), m);
        return get();
    }

    @Transactional
    public Settings deleteOverride(UUID actor, UUID id) {
        UUID tenantId = TenantContext.require();
        if (!visible) throw CustomerFeeException.notAvailable();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "DELETE FROM portal_fee_area_overrides WHERE id = ? AND tenant_id = ? " +
            "RETURNING kind, city_id, district_id, mode, amount, percent, min_amount, max_amount", id, tenantId);
        if (rows.isEmpty()) throw CustomerFeeException.overrideNotFound();
        Map<String, Object> r = rows.get(0);
        Shape was = new Shape((String) r.get("mode"), norm((BigDecimal) r.get("amount")), norm((BigDecimal) r.get("percent")),
            norm((BigDecimal) r.get("min_amount")), norm((BigDecimal) r.get("max_amount")));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("overrideId", id.toString());
        m.put("from", overrideSnapshot((String) r.get("kind"), (String) r.get("city_id"), (String) r.get("district_id"), was));
        audit.record(actor, "portal_fee_override_delete", "tenant", tenantId.toString(), m);
        return get();
    }

    // ── validation ───────────────────────────────────────────────────────────

    private record Shape(String mode, BigDecimal amount, BigDecimal percent, BigDecimal min, BigDecimal max) {}

    /** A rule (zeroAllowed false: a fixed fee of 0 is "off", a percent must be above 0) or an override (0 = no fee there). */
    static Shape shape(String mode, BigDecimal amount, BigDecimal percent, BigDecimal min, BigDecimal max, boolean zeroAllowed) {
        if ("fixed".equals(mode)) {
            if (amount == null || !wholeInRange(amount, zeroAllowed)) throw CustomerFeeException.amount(zeroAllowed);
            return new Shape(mode, norm(amount), null, null, null);
        }
        if ("percent".equals(mode)) {
            if (percent == null || percent.stripTrailingZeros().scale() > 2
                    || percent.compareTo(BigDecimal.valueOf(100)) > 0
                    || (zeroAllowed ? percent.signum() < 0 : percent.signum() <= 0)) {
                throw CustomerFeeException.percent(zeroAllowed);
            }
            if ((min != null && !wholeInRange(min, true)) || (max != null && !wholeInRange(max, true))
                    || (min != null && max != null && min.compareTo(max) > 0)) {
                throw CustomerFeeException.minMax();
            }
            return new Shape(mode, null, norm(percent), norm(min), norm(max));
        }
        throw CustomerFeeException.mode();
    }

    private static boolean wholeInRange(BigDecimal v, boolean zeroAllowed) {
        return v.stripTrailingZeros().scale() <= 0
            && (zeroAllowed ? v.signum() >= 0 : v.signum() > 0)
            && v.compareTo(MAX_AMOUNT) <= 0;
    }

    /** One scale for comparing and storing: 2 dp. */
    private static BigDecimal norm(BigDecimal v) {
        return v == null ? null : v.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    private void checkArea(String cityId, String districtId) {
        if (cityId == null) throw CustomerFeeException.area();
        boolean ok = districtId == null
            ? Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM bosta_districts WHERE city_id = ?)", Boolean.class, cityId))
            : Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM bosta_districts WHERE city_id = ? AND district_id = ?)", Boolean.class,
                cityId, districtId));
        if (!ok) throw CustomerFeeException.area();
    }

    private Map<String, Object> ruleSnapshot(UUID tenantId, String kind, boolean lock) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT enabled, mode, amount, percent, min_amount, max_amount, waive_merchant_fault " +
            "FROM portal_fee_rules WHERE tenant_id = ? AND kind = ?" + (lock ? " FOR UPDATE" : ""), tenantId, kind);
        if (rows.isEmpty()) return null;
        Map<String, Object> r = rows.get(0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", r.get("enabled"));
        m.put("mode", r.get("mode"));
        m.put("amount", norm((BigDecimal) r.get("amount")));
        m.put("percent", norm((BigDecimal) r.get("percent")));
        m.put("minAmount", norm((BigDecimal) r.get("min_amount")));
        m.put("maxAmount", norm((BigDecimal) r.get("max_amount")));
        m.put("waiveMerchantFault", r.get("waive_merchant_fault"));
        return m;
    }

    private static Map<String, Object> overrideSnapshot(String kind, String cityId, String districtId, Shape s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("cityId", cityId);
        m.put("districtId", districtId);
        m.put("mode", s.mode);
        m.put("amount", s.amount);
        m.put("percent", s.percent);
        m.put("minAmount", s.min);
        m.put("maxAmount", s.max);
        return m;
    }

    private static List<String> reasons(Object sqlArray) {
        if (sqlArray == null) return List.of();
        try {
            Object[] a = sqlArray instanceof Array arr ? (Object[]) arr.getArray() : (Object[]) sqlArray;
            List<String> out = new ArrayList<>();
            for (String code : PortalService.REASON_CODES) {
                for (Object o : a) if (code.equals(o)) { out.add(code); break; }
            }
            return out;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
