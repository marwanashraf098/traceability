package com.traceability.labels;

import com.traceability.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Flexible labeling slice 3 — a scanned code that is no piece but an ISSUED label code: the label was printed
 * and maybe stuck on, but never scanned in Tag stock (or, from slice 4, at packing), so no piece carries it yet.
 * Every scan path that misses on a piece asks here before answering "not found", and answers "Label not used yet
 * — tag it in Tag stock first" instead. Read on the caller's connection and tenant; the value is the caller's
 * already-normalized piece lookup value. Only ISSUED codes count (a voided code stays "not found"; a bound code
 * is a piece's short_code and never reaches here).
 *
 * <p>A FACTORY code (slice 6) is never tagged in Tag stock or at packing: it becomes a piece only when its factory
 * run is received. Every path answers it with {@link #FACTORY_MESSAGE_EN} instead — use {@link #codeFor},
 * {@link #messageEnFor}, {@link #messageArFor} or {@link #notUsedYet(Unused)}, never the tag-stock constants alone.
 */
public final class UnusedLabels {

    public static final String CODE = "LABEL_NOT_USED_YET";
    public static final String MESSAGE_EN = "Label not used yet — tag it in Tag stock first";
    public static final String MESSAGE_AR = "الملصق لم يُستخدم بعد — ألصقه في جلسة لصق المخزون أولًا";

    public static final String FACTORY_CODE = "FACTORY_LABEL_NOT_RECEIVED";
    public static final String FACTORY_MESSAGE_EN = "Factory label not received yet — receive it in its factory run first";
    public static final String FACTORY_MESSAGE_AR = "ملصق المصنع لم يُستلم بعد — استلمه في دفعة المصنع الخاصة به أولًا";

    public record Unused(UUID id, String code, UUID variantId, String productTitle, String variantTitle, String sku,
                         String kind) {
        public boolean factory() { return "factory".equals(kind); }
    }

    public static String codeFor(Unused u)      { return u.factory() ? FACTORY_CODE : CODE; }
    public static String messageEnFor(Unused u) { return u.factory() ? FACTORY_MESSAGE_EN : MESSAGE_EN; }
    public static String messageArFor(Unused u) { return u.factory() ? FACTORY_MESSAGE_AR : MESSAGE_AR; }

    private UnusedLabels() {}

    public static Optional<Unused> find(JdbcTemplate jdbc, UUID tenantId, String normalizedCode) {
        if (normalizedCode == null || normalizedCode.isBlank()) return Optional.empty();
        List<Unused> rows = jdbc.query(
            "SELECT lc.id, lc.code, lc.variant_id, lc.kind, pr.title AS product_title, v.title AS variant_title, v.sku " +
            "FROM label_codes lc JOIN variants v ON v.id = lc.variant_id JOIN products pr ON pr.id = v.product_id " +
            "WHERE lc.tenant_id = ? AND lc.code = ? AND lc.status = 'issued'",
            (rs, i) -> new Unused(rs.getObject("id", UUID.class), rs.getString("code"), rs.getObject("variant_id", UUID.class),
                rs.getString("product_title"), rs.getString("variant_title"), rs.getString("sku"), rs.getString("kind")),
            tenantId, normalizedCode);
        return rows.stream().findFirst();
    }

    /** Slice 4 (Pick &amp; Pack): a VOIDED label code — refused by name instead of "not found". */
    public static boolean isVoided(JdbcTemplate jdbc, UUID tenantId, String normalizedCode) {
        if (normalizedCode == null || normalizedCode.isBlank()) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM label_codes WHERE tenant_id = ? AND code = ? AND status = 'voided')",
            Boolean.class, tenantId, normalizedCode));
    }

    /** For the paths that answer with an exception body: 409 {code: LABEL_NOT_USED_YET, message_en, message_ar}. */
    public static ApiException notUsedYet() {
        return new NotUsedYet(CODE, MESSAGE_EN, MESSAGE_AR);
    }

    /** Same, kind-aware: a factory code answers {@link #FACTORY_CODE}. */
    public static ApiException notUsedYet(Unused u) {
        return new NotUsedYet(codeFor(u), messageEnFor(u), messageArFor(u));
    }

    static final class NotUsedYet extends ApiException {
        NotUsedYet(String code, String en, String ar) { super(code, en, ar, HttpStatus.CONFLICT); }
    }
}
