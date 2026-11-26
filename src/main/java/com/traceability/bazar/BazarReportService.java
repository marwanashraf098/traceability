package com.traceability.bazar;

import com.traceability.account.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bazar POS B5 — the event report (mockup step 10) and the customers CSV. Owner / manager only. Built ONLY from the
 * bazar tables (bazar_events / bazar_sales / bazar_payments / bazar_day_closes) and the event's linked transfer —
 * never from Analytics / Overview (BazarReportGuardTest). Money uses the one money rule
 * ({@link BazarSaleService#summary}): revenue = sales − returns (exchanges net to their difference) = money in.
 * Shopify's "Default Title" variant name is never shown.
 */
@Service
public class BazarReportService {

    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");

    private final JdbcTemplate jdbc;
    private final BazarDayService days;
    private final BazarEndService end;
    private final AuditService audit;

    public BazarReportService(JdbcTemplate jdbc, BazarDayService days, BazarEndService end, AuditService audit) {
        this.jdbc = jdbc;
        this.days = days;
        this.end = end;
        this.audit = audit;
    }

    /** Shopify names a variant-less product's only variant "Default Title" — that's not a name; null instead. */
    static String variantTitle(String title) {
        return title == null || title.isBlank() || "Default Title".equalsIgnoreCase(title.trim()) ? null : title;
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> report(UUID eventId, BazarSaleService.Actor actor) {
        UUID tenantId = BazarEndService.requireManagerTenant(jdbc, actor);
        Map<String, Object> ev = event(tenantId, eventId);
        List<String> methods = BazarSaleService.toList(ev.get("payment_methods"));
        Map<String, Object> totals = BazarSaleService.summary(jdbc, tenantId, eventId, methods, (java.time.LocalDate) null);
        Map<String, Object> left = end.leftovers(tenantId, ev);
        Map<String, Object> counts = (Map<String, Object>) left.get("counts");
        int sent = (Integer) counts.get("sent");
        // B6c: pieces = lines (a cart's pieces each count); a sale = a receipt (a quick sale is its own).
        int sold = (Integer) totals.get("pieces");
        int salesCount = (Integer) totals.get("sales");
        BigDecimal revenue = (BigDecimal) totals.get("revenue");

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", ev.get("id"));
        e.put("name", ev.get("name"));
        e.put("status", ev.get("status"));
        e.put("starts_on", String.valueOf(ev.get("starts_on")));
        e.put("ends_on", String.valueOf(ev.get("ends_on")));
        e.put("started_at", ev.get("started_at"));
        e.put("ended_at", ev.get("ended_at"));
        e.put("location_name", ev.get("location_name"));
        e.put("online_stock", ev.get("online_stock"));   // B8: the send's snapshot, else the location's (before send)
        out.put("event", e);

        out.put("revenue", revenue);
        out.put("money_in", totals.get("total"));
        out.put("pieces_sold", sold);
        out.put("pieces_sent", sent);
        out.put("sell_through", ratio(sold, sent));
        out.put("sales", salesCount);
        out.put("average_sale", salesCount == 0 ? null
            : revenue.divide(BigDecimal.valueOf(salesCount), 2, RoundingMode.HALF_UP));
        out.put("discounts", totals.get("discounts"));
        out.put("returns", totals.get("returns"));
        out.put("exchanges", totals.get("exchanges"));

        // Sales by day: the B4 per-day money summary (same numbers as the days list and the day closes).
        List<Map<String, Object>> byDay = new ArrayList<>();
        for (Map<String, Object> d : (List<Map<String, Object>>) days.days(eventId, actor).get("days")) {
            Map<String, Object> money = (Map<String, Object>) d.get("money");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("business_day", d.get("business_day"));
            row.put("day_number", d.get("day_number"));
            row.put("sales", money.get("sales"));
            row.put("revenue", money.get("revenue"));
            row.put("money_in", money.get("total"));
            row.put("status", d.get("status"));
            byDay.add(row);
        }
        out.put("sales_by_day", byDay);

        // Busiest hours (U1b): sales that STAYED sold (status active — undone, returned and exchanged-back ones are
        // left out; a cart is one sale while one of its lines is kept), and the pieces they kept, by Cairo hour, all
        // days. Every hour from the first sale's hour to the last one's is listed (0 when quiet), so one busy hour isn't
        // a lone bar — the bazar's open hours, as its sales show them.
        int[] hours = new int[24], hourPieces = new int[24];
        int firstHour = 24, lastHour = -1;
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT EXTRACT(HOUR FROM s.sold_at AT TIME ZONE 'Africa/Cairo')::int AS h, " +
                "       COUNT(DISTINCT COALESCE(s.receipt_id, s.id)) AS n, COUNT(*) AS pieces " +
                "FROM bazar_sales s WHERE s.event_id = ? AND s.tenant_id = ? AND s.status = 'active' GROUP BY 1",
                eventId, tenantId)) {
            int h = ((Number) r.get("h")).intValue();
            hours[h] = ((Number) r.get("n")).intValue();
            hourPieces[h] = ((Number) r.get("pieces")).intValue();
            firstHour = Math.min(firstHour, h);
            lastHour = Math.max(lastHour, h);
        }
        List<Map<String, Object>> busiest = new ArrayList<>();
        for (int h = firstHour; h <= lastHour; h++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("hour", h);
            row.put("sales", hours[h]);
            row.put("pieces", hourPieces[h]);
            busiest.add(row);
        }
        out.put("busiest_hours", busiest);

        out.put("top_products", top(tenantId, eventId, (UUID) ev.get("transfer_id"), false));
        out.put("top_sizes", top(tenantId, eventId, (UUID) ev.get("transfer_id"), true));
        // U1b: products sent that sold nothing ("0 of N" under What sold), and whether any product sent has sizes —
        // when none has, Top sizes says so instead of "No sales yet".
        out.put("unsold_products", unsold(tenantId, eventId, (UUID) ev.get("transfer_id")));
        out.put("has_sizes", hasSizes(tenantId, (UUID) ev.get("transfer_id")));

        // Payment split: money in per method and its share of the total.
        Map<String, BigDecimal> byMethod = (Map<String, BigDecimal>) totals.get("by_method");
        BigDecimal moneyIn = (BigDecimal) totals.get("total");
        List<Map<String, Object>> split = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> m : byMethod.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("method", m.getKey());
            row.put("amount", m.getValue());
            row.put("share", moneyIn.signum() <= 0 ? null
                : m.getValue().divide(moneyIn, 4, RoundingMode.HALF_UP));
            split.add(row);
        }
        out.put("payment_split", split);

        out.put("money_not_received", notReceived(tenantId, eventId, byDay));

        List<Map<String, Object>> sellers = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT u.name, COUNT(*) AS pieces, SUM(s.net_price) AS total FROM bazar_sales s " +
                "JOIN users u ON u.id = s.sold_by WHERE s.event_id = ? AND s.tenant_id = ? AND s.status = 'active' " +
                "GROUP BY s.sold_by, u.name ORDER BY SUM(s.net_price) DESC, u.name", eventId, tenantId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seller_name", r.get("name"));
            row.put("pieces", ((Number) r.get("pieces")).intValue());
            row.put("total", ((BigDecimal) r.get("total")).setScale(2));
            sellers.add(row);
        }
        out.put("per_seller", sellers);

        out.put("customers_saved", jdbc.queryForObject(
            "SELECT COUNT(DISTINCT " + CUSTOMER_KEY + ") FROM bazar_sales s WHERE s.event_id = ? AND s.tenant_id = ? " +
            "AND s.status <> 'undone' AND (s.customer_phone IS NOT NULL OR s.customer_name IS NOT NULL)",
            Integer.class, eventId, tenantId));

        Map<String, Object> tr = new LinkedHashMap<>(counts);
        tr.put("transfer_id", left.get("transfer_id"));
        tr.put("status", left.get("transfer_status"));
        tr.put("balanced", left.get("balanced"));
        out.put("transfer", tr);
        return out;
    }

    /** A customer: their phone, else their name (case-insensitive). */
    private static final String CUSTOMER_KEY = "COALESCE(s.customer_phone, 'name:' || lower(btrim(s.customer_name)))";

    /** Products (or variants = "sizes") by pieces sold: sold, sent, sell-through, revenue. Top 10. */
    private List<Map<String, Object>> top(UUID tenantId, UUID eventId, UUID transferId, boolean byVariant) {
        String key = byVariant ? "v.id" : "pr.id";
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "WITH sent AS (SELECT " + key + " AS k, COUNT(*) AS n FROM transfer_pieces tp " +
                "  JOIN pieces p ON p.id = tp.piece_id JOIN variants v ON v.id = p.variant_id " +
                "  JOIN products pr ON pr.id = v.product_id WHERE tp.transfer_id = ? AND tp.tenant_id = ? GROUP BY 1), " +
                "sold AS (SELECT " + key + " AS k, COUNT(*) AS n, SUM(s.net_price) AS total FROM bazar_sales s " +
                "  JOIN variants v ON v.id = s.variant_id JOIN products pr ON pr.id = v.product_id " +
                "  WHERE s.event_id = ? AND s.tenant_id = ? AND s.status = 'active' GROUP BY 1) " +
                "SELECT sold.k, sold.n AS sold, sold.total, COALESCE(sent.n, 0) AS sent, pr.title AS product_title, " +
                "       " + (byVariant ? "v.title" : "NULL") + " AS variant_title " +
                "FROM sold LEFT JOIN sent ON sent.k = sold.k " +
                (byVariant ? "JOIN variants v ON v.id = sold.k JOIN products pr ON pr.id = v.product_id "
                           : "JOIN products pr ON pr.id = sold.k ") +
                "ORDER BY sold.n DESC, sold.total DESC, pr.title LIMIT 10",
                transferId, tenantId, eventId, tenantId)) {
            String vt = variantTitle((String) r.get("variant_title"));
            if (byVariant && vt == null) continue;              // "Default Title" is no size
            int s = ((Number) r.get("sold")).intValue(), n = ((Number) r.get("sent")).intValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("product_title", r.get("product_title"));
            if (byVariant) row.put("variant_title", vt);
            row.put("sold", s);
            row.put("sent", n);
            row.put("sell_through", ratio(s, n));
            row.put("revenue", ((BigDecimal) r.get("total")).setScale(2));
            out.add(row);
        }
        return out;
    }

    /** Products with pieces sent on the bazar's transfer and no sale kept: title, sent. By title. */
    private List<Map<String, Object>> unsold(UUID tenantId, UUID eventId, UUID transferId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT pr.title AS product_title, COUNT(*) AS sent FROM transfer_pieces tp " +
                "JOIN pieces p ON p.id = tp.piece_id JOIN variants v ON v.id = p.variant_id " +
                "JOIN products pr ON pr.id = v.product_id WHERE tp.transfer_id = ? AND tp.tenant_id = ? " +
                "AND NOT EXISTS (SELECT 1 FROM bazar_sales s JOIN variants sv ON sv.id = s.variant_id " +
                "  WHERE s.event_id = ? AND s.tenant_id = ? AND s.status = 'active' AND sv.product_id = pr.id) " +
                "GROUP BY pr.id, pr.title ORDER BY pr.title, pr.id",
                transferId, tenantId, eventId, tenantId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("product_title", r.get("product_title"));
            row.put("sold", 0);
            row.put("sent", ((Number) r.get("sent")).intValue());
            out.add(row);
        }
        return out;
    }

    /** Any variant sent on the transfer has a real size / option name (not Shopify's "Default Title"). */
    private boolean hasSizes(UUID tenantId, UUID transferId) {
        for (String t : jdbc.queryForList(
                "SELECT DISTINCT v.title FROM transfer_pieces tp JOIN pieces p ON p.id = tp.piece_id " +
                "JOIN variants v ON v.id = p.variant_id WHERE tp.transfer_id = ? AND tp.tenant_id = ?",
                String.class, transferId, tenantId)) {
            if (variantTitle(t) != null) return true;
        }
        return false;
    }

    /** From each day's CURRENT close: cash short / over, card short / over, InstaPay not received, and the total. */
    private Map<String, Object> notReceived(UUID tenantId, UUID eventId, List<Map<String, Object>> byDay) {
        Map<String, Object> r = jdbc.queryForMap(
            "SELECT COUNT(*) AS closes, " +
            "  COALESCE(SUM(GREATEST(-c.cash_difference, 0)), 0) AS cash_short, " +
            "  COALESCE(SUM(GREATEST(c.cash_difference, 0)), 0) AS cash_over, " +
            "  COALESCE(SUM(GREATEST(-c.card_difference, 0)), 0) AS card_short, " +
            "  COALESCE(SUM(GREATEST(c.card_difference, 0)), 0) AS card_over, " +
            "  COALESCE(SUM(GREATEST(-c.instapay_difference, 0)), 0) AS instapay_not_received, " +
            "  COALESCE(SUM(c.not_received_total), 0) AS total " +
            "FROM bazar_day_closes c WHERE c.event_id = ? AND c.tenant_id = ? " +
            "AND NOT EXISTS (SELECT 1 FROM bazar_day_closes n WHERE n.supersedes_id = c.id)", eventId, tenantId);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("cash_short", "cash_over", "card_short", "card_over", "instapay_not_received", "total")) {
            out.put(k, ((BigDecimal) r.get(k)).setScale(2));
        }
        out.put("days_closed", ((Number) r.get("closes")).intValue());
        out.put("days_not_closed", (int) byDay.stream()
            .filter(d -> "open".equals(d.get("status")) || "changed_after_close".equals(d.get("status"))).count());
        return out;
    }

    // ── Customers CSV ────────────────────────────────────────────────────────

    /**
     * name, phone, pieces, total, last visit — one row per customer (phone, else name) of the event's sales (undone
     * ones never happened; pieces / total count the ones they kept). Owner / manager only; every export is
     * audit-logged (bazar_customers_export). Cells that a spreadsheet would read as a formula are quoted with '.
     */
    @Transactional
    public String customersCsv(UUID eventId, BazarSaleService.Actor actor) {
        UUID tenantId = BazarEndService.requireManagerTenant(jdbc, actor);
        event(tenantId, eventId);
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT (array_agg(s.customer_name ORDER BY s.sold_at DESC) FILTER (WHERE s.customer_name IS NOT NULL))[1] AS name, " +
            "       max(s.customer_phone) AS phone, " +
            "       COUNT(*) FILTER (WHERE s.status = 'active') AS pieces, " +
            "       COALESCE(SUM(s.net_price) FILTER (WHERE s.status = 'active'), 0) AS total, max(s.sold_at) AS last_visit " +
            "FROM bazar_sales s WHERE s.event_id = ? AND s.tenant_id = ? AND s.status <> 'undone' " +
            "  AND (s.customer_phone IS NOT NULL OR s.customer_name IS NOT NULL) " +
            "GROUP BY " + CUSTOMER_KEY + " ORDER BY 4 DESC, 5 DESC", eventId, tenantId);
        StringBuilder csv = new StringBuilder("name,phone,pieces,total,last_visit\n");
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        for (Map<String, Object> r : rows) {
            Object lv = r.get("last_visit");
            OffsetDateTime at = lv instanceof OffsetDateTime o ? o : ((Timestamp) lv).toInstant().atOffset(java.time.ZoneOffset.UTC);
            csv.append(cell((String) r.get("name"))).append(',')
               .append(cell((String) r.get("phone"))).append(',')
               .append(((Number) r.get("pieces")).intValue()).append(',')
               .append(((BigDecimal) r.get("total")).setScale(2).toPlainString()).append(',')
               .append(at.atZoneSameInstant(CAIRO).format(f)).append('\n');
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("event_id", eventId.toString());
        meta.put("rows", rows.size());
        audit.record(actor.userId(), "bazar_customers_export", "bazar_event", eventId.toString(), meta);
        return csv.toString();
    }

    // ── Report PDF (U1b, page 1) ─────────────────────────────────────────────

    /**
     * The report's page 1 as an A4 PDF ({@link BazarReportPdf}): the same report and day list numbers, plus "below
     * Shopify prices" = Σ (Shopify price − net price) over the pieces still sold where the bazar sold below Shopify.
     * Owner / manager only (the report's own check). No customer data on it, so no audit row.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public byte[] reportPdf(UUID eventId, BazarSaleService.Actor actor, String lang) {
        Map<String, Object> r = report(eventId, actor);
        UUID tenantId = BazarEndService.requireManagerTenant(jdbc, actor);
        Map<String, Object> ev = event(tenantId, eventId);
        List<Map<String, Object>> dayRows = (List<Map<String, Object>>) days.days(eventId, actor).get("days");
        BigDecimal below = jdbc.queryForObject(
            "SELECT COALESCE(SUM(GREATEST(v.price - s.net_price, 0)), 0) FROM bazar_sales s " +
            "JOIN variants v ON v.id = s.variant_id WHERE s.event_id = ? AND s.tenant_id = ? AND s.status = 'active' " +
            "AND v.price IS NOT NULL", BigDecimal.class, eventId, tenantId);
        String transferCode = "TR-" + ev.get("transfer_id").toString().replace("-", "").substring(0, 6).toUpperCase();
        return BazarReportPdf.pdf(new BazarReportPdf.Data(r, dayRows,
            BazarSaleService.toList(ev.get("payment_methods")), below, transferCode), lang);
    }

    // ── Orders CSV (U1b) ─────────────────────────────────────────────────────

    /**
     * One row per piece sold at the event (a cart line is a row with its receipt's number): number, day, time, seller,
     * product, variant (never "Default Title"), SKU, piece code, bazar price, discount, net, payment method, status
     * (sold / returned / exchanged), customer name and phone. Undone sales never happened — left out. Owner / manager
     * only; audit-logged (bazar_orders_export), like the customers export.
     */
    @Transactional
    public String ordersCsv(UUID eventId, BazarSaleService.Actor actor) {
        UUID tenantId = BazarEndService.requireManagerTenant(jdbc, actor);
        event(tenantId, eventId);
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT COALESCE(s.sale_number, rc.receipt_number) AS number, s.business_day, s.sold_at, u.name AS seller, " +
            "       pr.title AS product, v.title AS variant, v.sku, pc.short_code AS piece_code, s.price, s.discount, " +
            "       s.net_price, s.status, s.customer_name, s.customer_phone, " +
            "       EXISTS (SELECT 1 FROM bazar_sales xn WHERE xn.exchange_of_sale_id = s.id AND xn.tenant_id = s.tenant_id) AS exchanged, " +
            "       (SELECT bp.method FROM bazar_payments bp WHERE bp.tenant_id = s.tenant_id " +
            "          AND (bp.sale_id = s.id OR bp.receipt_id = s.receipt_id) AND bp.kind = 'payment' " +
            "        ORDER BY EXISTS (SELECT 1 FROM bazar_payments x WHERE x.voids_payment_id = bp.id), " +
            "                 bp.created_at DESC, bp.id DESC LIMIT 1) AS method " +
            "FROM bazar_sales s JOIN users u ON u.id = s.sold_by JOIN pieces pc ON pc.id = s.piece_id " +
            "JOIN variants v ON v.id = s.variant_id JOIN products pr ON pr.id = v.product_id " +
            "LEFT JOIN bazar_receipts rc ON rc.id = s.receipt_id AND rc.tenant_id = s.tenant_id " +
            "WHERE s.event_id = ? AND s.tenant_id = ? AND s.status <> 'undone' " +
            "ORDER BY s.sold_at, COALESCE(s.line_no, 0), s.id", eventId, tenantId);
        StringBuilder csv = new StringBuilder(
            "number,day,time,seller,product,variant,sku,piece,price,discount,net,method,status,customer_name,customer_phone\n");
        DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH:mm");
        for (Map<String, Object> r : rows) {
            Object sa = r.get("sold_at");
            OffsetDateTime at = sa instanceof OffsetDateTime o ? o : ((Timestamp) sa).toInstant().atOffset(java.time.ZoneOffset.UTC);
            String status = "active".equals(r.get("status")) ? "sold"
                : Boolean.TRUE.equals(r.get("exchanged")) ? "exchanged" : "returned";
            csv.append(String.format("B-%04d", ((Number) r.get("number")).intValue())).append(',')
               .append(r.get("business_day")).append(',')
               .append(at.atZoneSameInstant(CAIRO).format(hm)).append(',')
               .append(cell((String) r.get("seller"))).append(',')
               .append(cell((String) r.get("product"))).append(',')
               .append(cell(variantTitle((String) r.get("variant")))).append(',')
               .append(cell((String) r.get("sku"))).append(',')
               .append(cell((String) r.get("piece_code"))).append(',')
               .append(money(r.get("price"))).append(',')
               .append(money(r.get("discount"))).append(',')
               .append(money(r.get("net_price"))).append(',')
               .append(cell((String) r.get("method"))).append(',')
               .append(status).append(',')
               .append(cell((String) r.get("customer_name"))).append(',')
               .append(cell((String) r.get("customer_phone"))).append('\n');
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("event_id", eventId.toString());
        meta.put("rows", rows.size());
        audit.record(actor.userId(), "bazar_orders_export", "bazar_event", eventId.toString(), meta);
        return csv.toString();
    }

    static String money(Object v) {
        return v == null ? "0.00" : ((BigDecimal) v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** RFC 4180 quoting, and a leading ' on anything a spreadsheet would run as a formula. */
    static String cell(String v) {
        if (v == null) return "";
        String s = v;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static BigDecimal ratio(int a, int b) {
        return b == 0 ? null : BigDecimal.valueOf(a).divide(BigDecimal.valueOf(b), 4, RoundingMode.HALF_UP);
    }

    private Map<String, Object> event(UUID tenantId, UUID eventId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT e.id, e.name, e.status, e.starts_on, e.ends_on, e.started_at, e.ended_at, e.transfer_id, " +
            "       e.location_id, e.payment_methods, l.name AS location_name, " +
            "       COALESCE(t.shopify_sync_mode, l.shopify_sync_mode) AS online_stock " +
            "FROM bazar_events e JOIN locations l ON l.id = e.location_id " +
            "JOIN transfers t ON t.id = e.transfer_id AND t.tenant_id = e.tenant_id WHERE e.id = ? AND e.tenant_id = ? AND e.deleted_at IS NULL",
            eventId, tenantId);
        if (rows.isEmpty()) {
            throw new BazarException(BazarException.Code.BAZAR_EVENT_NOT_FOUND,
                "Bazar not found.", "لم يتم العثور على البازار.", org.springframework.http.HttpStatus.NOT_FOUND);
        }
        return rows.get(0);
    }

}
