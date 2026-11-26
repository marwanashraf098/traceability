package com.traceability.bazar;

import com.traceability.account.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Bazar customer details on request (2026-11-26): find one customer across every bazar of the store, export what Traced
 * holds on them as CSV, or delete their name and phone. The only place a bazar customer's details live is
 * {@code bazar_sales.customer_name / customer_phone} (quick sales, cart lines — a receipt's customer is its lines' —
 * exchanges' new sales, undone and returned rows alike); nothing else stores them.
 * <ul>
 *   <li><b>A customer</b> is the key the report's Customers card uses ({@link #CUSTOMER_KEY}): their phone
 *       ({@code 01XXXXXXXXX}), else {@code name:} + their lower-cased trimmed name (rows with no phone only). The screen
 *       may tick several keys together (one person's phone rows and their name-only rows).</li>
 *   <li><b>Who:</b> search and export — owner or manager; delete — owner only.</li>
 *   <li><b>Never in a URL:</b> every call takes a JSON body (BazarNoPersonalQueryParamGuardTest).</li>
 *   <li><b>Never in a log:</b> the audit rows ({@code bazar_customer_export}, {@code bazar_customer_delete}) carry
 *       counts only — never a name, a phone or a hash of one (an Egyptian mobile's hash is easy to reverse).</li>
 *   <li><b>Delete</b> anonymises in place: only the two customer columns change (through
 *       {@link BazarSaleService#clearCustomerDetails}, the one writer of bazar_sales), so sales, amounts, items,
 *       payments and every report total stay as they were. The owner first sees a preview count; the delete sends it
 *       back and is refused (409 BAZAR_CUSTOMER_CHANGED) when the rows changed since. It cannot be undone.</li>
 * </ul>
 * Reads only bazar tables and the catalog / users they name, under TenantContext + RLS (another store sees nothing).
 */
@Service
public class BazarCustomerDataService {

    /** Same rule as the report's Customers card: their phone, else their name (case-insensitive). */
    static final String CUSTOMER_KEY = "COALESCE(s.customer_phone, 'name:' || lower(btrim(s.customer_name)))";

    static final int SEARCH_LIMIT = 50;
    static final int MAX_KEYS = 20;
    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");

    private final JdbcTemplate jdbc;
    private final BazarSaleService sales;
    private final AuditService audit;

    public BazarCustomerDataService(JdbcTemplate jdbc, BazarSaleService sales, AuditService audit) {
        this.jdbc = jdbc;
        this.sales = sales;
        this.audit = audit;
    }

    // ── Find ─────────────────────────────────────────────────────────────────

    /**
     * {customers: [{key, name, names, phone, bazars, sales, pieces, total, last_visit}], more}. {@code q}: a phone in
     * any usual shape (010…, +20 10…, 0020…, spaces, Arabic digits — normalised like every bazar phone) → that phone
     * exactly; anything else (2+ characters, not only digits) → names containing it, case-insensitive. A customer found
     * by one of their names is shown whole (every row of their key).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> search(String q, BazarSaleService.Actor actor) {
        UUID tenantId = BazarEndService.requireManagerTenant(jdbc, actor);
        String text = q == null ? "" : q.trim();
        String match;
        Object arg;
        String phone = text.isEmpty() ? null : BazarSaleService.normalizePhone(text);
        if (phone != null) {
            match = "s.customer_phone = ?";
            arg = phone;
        } else if (text.matches("[0-9٠-٩۰-۹+()\\-.\\s]+")) {
            throw new BazarException(BazarException.Code.BAZAR_INVALID_PHONE,
                "Enter an Egyptian mobile number, like 01012345678, or a name.",
                "أدخل رقم موبايل مصري، مثل 01012345678، أو اسمًا.", HttpStatus.BAD_REQUEST);
        } else if (text.length() < 2 || text.length() > 80) {
            throw invalid("Type a phone number or at least 2 letters of a name.",
                "اكتب رقم موبايل أو حرفين على الأقل من الاسم.");
        } else {
            match = "lower(s.customer_name) LIKE ? ESCAPE '\\'";
            arg = "%" + text.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
            "WITH k AS (SELECT DISTINCT " + CUSTOMER_KEY + " AS key FROM bazar_sales s " +
            "           WHERE s.tenant_id = ? AND " + match + " AND " + CUSTOMER_KEY + " IS NOT NULL) " +
            "SELECT " + CUSTOMER_KEY + " AS key, " +
            "       (array_agg(s.customer_name ORDER BY s.sold_at DESC, s.id DESC) " +
            "          FILTER (WHERE s.customer_name IS NOT NULL))[1] AS name, " +
            "       array_remove(array_agg(DISTINCT s.customer_name), NULL) AS names, " +
            "       max(s.customer_phone) AS phone, COUNT(DISTINCT s.event_id) AS bazars, COUNT(*) AS sales, " +
            "       COUNT(*) FILTER (WHERE s.status = 'active') AS pieces, " +
            "       COALESCE(SUM(s.net_price) FILTER (WHERE s.status = 'active'), 0) AS total, " +
            "       max(s.sold_at) AS last_visit " +
            "FROM bazar_sales s WHERE s.tenant_id = ? AND " + CUSTOMER_KEY + " IN (SELECT key FROM k) " +
            "GROUP BY 1 ORDER BY max(s.sold_at) DESC, 1 LIMIT " + (SEARCH_LIMIT + 1),
            tenantId, arg, tenantId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows.subList(0, Math.min(rows.size(), SEARCH_LIMIT))) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("key", r.get("key"));
            c.put("name", r.get("name"));
            c.put("names", names(r.get("names")));
            c.put("phone", r.get("phone"));
            c.put("bazars", ((Number) r.get("bazars")).intValue());
            c.put("sales", ((Number) r.get("sales")).intValue());
            c.put("pieces", ((Number) r.get("pieces")).intValue());
            c.put("total", ((BigDecimal) r.get("total")).setScale(2));
            c.put("last_visit", at(r.get("last_visit")).toString());
            out.add(c);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("customers", out);
        res.put("more", rows.size() > SEARCH_LIMIT);
        return res;
    }

    // ── Export ───────────────────────────────────────────────────────────────

    /**
     * Every bazar sale row of these customers, oldest first — undone and returned ones too (it is what Traced holds):
     * bazar, day, time, number, product, variant, sku, piece, price, discount, net, method, status
     * (sold / returned / exchanged / undone), customer_name, customer_phone. 404 BAZAR_CUSTOMER_NOT_FOUND when nothing
     * matches (another store's customer reads as nobody). Audit: {@code bazar_customer_export} {customers, rows,
     * bazars} — no name, no phone.
     */
    @Transactional
    public String exportCsv(List<String> keys, BazarSaleService.Actor actor) {
        UUID tenantId = BazarEndService.requireManagerTenant(jdbc, actor);
        List<String> ks = validKeys(keys);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(ks);
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT e.name AS bazar, s.event_id, COALESCE(s.sale_number, rc.receipt_number) AS number, s.business_day, " +
            "       s.sold_at, pr.title AS product, v.title AS variant, v.sku, pc.short_code AS piece_code, s.price, " +
            "       s.discount, s.net_price, s.status, s.customer_name, s.customer_phone, " +
            "       EXISTS (SELECT 1 FROM bazar_sales xn WHERE xn.exchange_of_sale_id = s.id AND xn.tenant_id = s.tenant_id) AS exchanged, " +
            "       (SELECT bp.method FROM bazar_payments bp WHERE bp.tenant_id = s.tenant_id " +
            "          AND (bp.sale_id = s.id OR bp.receipt_id = s.receipt_id) AND bp.kind = 'payment' " +
            "        ORDER BY EXISTS (SELECT 1 FROM bazar_payments x WHERE x.voids_payment_id = bp.id), " +
            "                 bp.created_at DESC, bp.id DESC LIMIT 1) AS method " +
            "FROM bazar_sales s JOIN bazar_events e ON e.id = s.event_id AND e.tenant_id = s.tenant_id " +
            "JOIN pieces pc ON pc.id = s.piece_id JOIN variants v ON v.id = s.variant_id " +
            "JOIN products pr ON pr.id = v.product_id " +
            "LEFT JOIN bazar_receipts rc ON rc.id = s.receipt_id AND rc.tenant_id = s.tenant_id " +
            "WHERE s.tenant_id = ? AND " + CUSTOMER_KEY + " IN (" + placeholders(ks.size()) + ") " +
            "ORDER BY s.sold_at, COALESCE(s.line_no, 0), s.id", args.toArray());
        if (rows.isEmpty()) throw notFound();
        StringBuilder csv = new StringBuilder("bazar,day,time,number,product,variant,sku,piece,price,discount,net," +
            "method,status,customer_name,customer_phone\n");
        DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH:mm");
        Set<Object> events = new HashSet<>();
        for (Map<String, Object> r : rows) {
            events.add(r.get("event_id"));
            String status = switch ((String) r.get("status")) {
                case "active" -> "sold";
                case "undone" -> "undone";
                default -> Boolean.TRUE.equals(r.get("exchanged")) ? "exchanged" : "returned";
            };
            csv.append(BazarReportService.cell((String) r.get("bazar"))).append(',')
               .append(r.get("business_day")).append(',')
               .append(at(r.get("sold_at")).atZoneSameInstant(CAIRO).format(hm)).append(',')
               .append(String.format("B-%04d", ((Number) r.get("number")).intValue())).append(',')
               .append(BazarReportService.cell((String) r.get("product"))).append(',')
               .append(BazarReportService.cell(BazarReportService.variantTitle((String) r.get("variant")))).append(',')
               .append(BazarReportService.cell((String) r.get("sku"))).append(',')
               .append(BazarReportService.cell((String) r.get("piece_code"))).append(',')
               .append(BazarReportService.money(r.get("price"))).append(',')
               .append(BazarReportService.money(r.get("discount"))).append(',')
               .append(BazarReportService.money(r.get("net_price"))).append(',')
               .append(BazarReportService.cell((String) r.get("method"))).append(',')
               .append(status).append(',')
               .append(BazarReportService.cell((String) r.get("customer_name"))).append(',')
               .append(BazarReportService.cell((String) r.get("customer_phone"))).append('\n');
        }
        audit.record(actor.userId(), "bazar_customer_export", "tenant", tenantId.toString(),
            counts(ks.size(), rows.size(), null, events.size()));
        return csv.toString();
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    /** What a delete would change: {rows, receipts, bazars}. Owner only. 404 when nothing matches. Writes nothing. */
    @Transactional(readOnly = true)
    public Map<String, Object> deletePreview(List<String> keys, BazarSaleService.Actor actor) {
        UUID tenantId = requireOwnerTenant(actor);
        List<Map<String, Object>> rows = matching(tenantId, validKeys(keys), false);
        if (rows.isEmpty()) throw notFound();
        return summary(rows);
    }

    /**
     * Clears the customers' name and phone on every bazar sale row of the store (any bazar, any status). Owner only.
     * {@code expectedRows} = the preview's {@code rows}: the rows are locked, then counted again — a different count
     * (a sale added, edited or already cleared since) → 409 BAZAR_CUSTOMER_CHANGED, nothing changed. Audit:
     * {@code bazar_customer_delete} {customers, rows, receipts, bazars} — no name, no phone. Returns those counts.
     */
    @Transactional
    public Map<String, Object> delete(List<String> keys, Integer expectedRows, BazarSaleService.Actor actor) {
        UUID tenantId = requireOwnerTenant(actor);
        List<String> ks = validKeys(keys);
        if (expectedRows == null || expectedRows < 1) {
            throw invalid("Check the details first, then delete.", "راجع البيانات أولًا ثم احذف.");
        }
        List<Map<String, Object>> rows = matching(tenantId, ks, true);
        if (rows.isEmpty()) throw notFound();
        if (rows.size() != expectedRows) {
            throw new BazarException(BazarException.Code.BAZAR_CUSTOMER_CHANGED,
                "This customer's sales changed since you checked. Check again before deleting.",
                "تغيّرت مبيعات هذا العميل منذ المراجعة. راجعها مرة أخرى قبل الحذف.", HttpStatus.CONFLICT);
        }
        List<UUID> ids = new ArrayList<>();
        for (Map<String, Object> r : rows) ids.add((UUID) r.get("id"));
        int changed = sales.clearCustomerDetails(tenantId, ids);
        if (changed != rows.size()) throw new IllegalStateException("bazar customer delete: " + changed + " of " + rows.size());
        Map<String, Object> out = summary(rows);
        audit.record(actor.userId(), "bazar_customer_delete", "tenant", tenantId.toString(),
            counts(ks.size(), rows.size(), (Integer) out.get("receipts"), (Integer) out.get("bazars")));
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private List<Map<String, Object>> matching(UUID tenantId, List<String> ks, boolean lock) {
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(ks);
        return jdbc.queryForList(
            "SELECT s.id, s.event_id, s.receipt_id FROM bazar_sales s " +
            "WHERE s.tenant_id = ? AND " + CUSTOMER_KEY + " IN (" + placeholders(ks.size()) + ") " +
            "ORDER BY s.id" + (lock ? " FOR UPDATE" : ""), args.toArray());
    }

    private static Map<String, Object> summary(List<Map<String, Object>> rows) {
        Set<Object> receipts = new HashSet<>(), events = new HashSet<>();
        for (Map<String, Object> r : rows) {
            if (r.get("receipt_id") != null) receipts.add(r.get("receipt_id"));
            events.add(r.get("event_id"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows.size());
        out.put("receipts", receipts.size());
        out.put("bazars", events.size());
        return out;
    }

    /** Audit metadata: counts only — never a name, a phone or anything derived from one. */
    private static Map<String, Object> counts(int customers, int rows, Integer receipts, int bazars) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("customers", customers);
        m.put("rows", rows);
        if (receipts != null) m.put("receipts", receipts);
        m.put("bazars", bazars);
        return m;
    }

    /** 1..20 distinct keys, each a stored phone (01XXXXXXXXX) or "name:" + a lower-cased trimmed name. */
    static List<String> validKeys(List<String> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > MAX_KEYS) {
            throw invalid("Pick between 1 and " + MAX_KEYS + " customers.", "اختر من 1 إلى " + MAX_KEYS + " عملاء.");
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String k : keys) {
            boolean ok = k != null && (k.matches("01[0125][0-9]{8}")
                || (k.startsWith("name:") && k.length() > 5 && k.length() <= 85 && k.equals("name:" + k.substring(5).trim().toLowerCase(Locale.ROOT))));
            if (!ok) throw invalid("Pick the customers from the search results.", "اختر العملاء من نتائج البحث.");
            out.add(k);
        }
        return new ArrayList<>(out);
    }

    private UUID requireOwnerTenant(BazarSaleService.Actor actor) {
        UUID tenantId = BazarEndService.requireEnabledTenant(jdbc);
        if (actor == null || !"owner".equals(actor.roleKey())) {
            throw new BazarException(BazarException.Code.BAZAR_OWNER_ONLY,
                "Only the owner can delete a customer's details.", "يمكن للمالك فقط حذف بيانات العميل.",
                HttpStatus.FORBIDDEN);
        }
        return tenantId;
    }

    private static String placeholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }

    private static List<String> names(Object arr) {
        if (arr == null) return List.of();
        try {
            Object[] a = (Object[]) ((java.sql.Array) arr).getArray();
            List<String> out = new ArrayList<>();
            for (Object o : a) out.add((String) o);
            return out;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static OffsetDateTime at(Object v) {
        return v instanceof OffsetDateTime o ? o : ((Timestamp) v).toInstant().atOffset(ZoneOffset.UTC);
    }

    private static BazarException notFound() {
        return new BazarException(BazarException.Code.BAZAR_CUSTOMER_NOT_FOUND,
            "No bazar sales with these customer details.", "لا توجد مبيعات بازار ببيانات هذا العميل.",
            HttpStatus.NOT_FOUND);
    }

    private static BazarException invalid(String en, String ar) {
        return new BazarException(BazarException.Code.BAZAR_INVALID_INPUT, en, ar, HttpStatus.BAD_REQUEST);
    }
}
