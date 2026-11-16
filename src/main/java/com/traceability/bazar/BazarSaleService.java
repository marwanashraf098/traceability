package com.traceability.bazar;

import com.traceability.inventory.InventoryLedger;
import com.traceability.inventory.PieceStatus;
import com.traceability.inventory.ScanInputNormalizer;
import com.traceability.inventory.StateConflictException;
import com.traceability.inventory.TransitionContext;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bazar POS B2 — quick sell, undo, payment method and customer edits, the sales list. The ONE writer of
 * {@code bazar_sales} / {@code bazar_payments} / {@code bazar_sale_counters}, and the only caller of the ledger's
 * bazar sale edges:
 * <ul>
 *   <li><b>Sell</b>: {@code out_on_transfer → sold} (event {@code bazar_sold}) + the piece's {@code transfer_pieces}
 *       row on the event's linked transfer → outcome 'sold' (+ the line's qty_sold), in one transaction. The ledger's
 *       race-guarded UPDATE is the referee: two sellers scanning one piece → one sale, the other BAZAR_ALREADY_SOLD.
 *       Same {@code client_request_id} twice → the first sale, never a second (a per-request advisory lock makes a
 *       concurrent retry wait for the first and then find it).</li>
 *   <li><b>Undo</b>: {@code sold → out_on_transfer} (event {@code bazar_sale_undone}, approved 2026-11-16), the
 *       transfer_pieces row reopened, qty_sold −1, the sale's live payment rows voided. The sale row stays (undone).</li>
 *   <li>NO Shopify write of any kind: the event runs in 'remove' mode, so the −N already happened when its transfer
 *       was marked sent. Nothing here calls the Shopify inventory code or the named decrement set.</li>
 *   <li>A variant with no bazar price row can't be sold (BAZAR_NO_PRICE) — never at 0 by default. The price is
 *       snapshotted onto the sale.</li>
 *   <li>Payments are append-only: a method change is a void row + a new payment row.</li>
 * </ul>
 * Who: sell needs the caller's role in the event's {@code seller_roles}. Undo / payment change: owner or manager
 * any time while the event is live, else the seller who made the sale within {@link #UNDO_WINDOW_SECONDS}. Customer
 * edit: owner or manager, or the seller who made the sale (while live). The sales list: a seller role, or owner /
 * manager; a worker sees the customer's phone masked.
 */
@Service
public class BazarSaleService {

    /** A seller (worker) may undo / change the method of their own sale for this long. */
    static final int UNDO_WINDOW_SECONDS = 120;

    static final String SOLD_EVENT   = "bazar_sold";
    static final String UNDONE_EVENT = "bazar_sale_undone";

    private static final Set<String> MANAGERS = Set.of("owner", "manager");

    private final JdbcTemplate    jdbc;
    private final InventoryLedger ledger;

    public BazarSaleService(JdbcTemplate jdbc, InventoryLedger ledger) {
        this.jdbc   = jdbc;
        this.ledger = ledger;
    }

    /** The caller: user id + role ('owner' / 'manager' / 'worker'). */
    public record Actor(UUID userId, String role) {
        boolean manages() { return role != null && MANAGERS.contains(role.toLowerCase()); }
        String  roleKey() { return role == null ? "" : role.toLowerCase(); }
    }

    // ── Sell ─────────────────────────────────────────────────────────────────

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> sell(UUID eventId, String code, String clientRequestId, String method, Actor actor) {
        UUID tenantId = requireEnabled();
        Map<String, Object> ev = liveEvent(tenantId, eventId);
        if (!sellerRoles(ev).contains(actor.roleKey())) throw notASeller();

        String crid = clientRequestId == null ? "" : clientRequestId.trim();
        if (crid.isEmpty() || crid.length() > 100) {
            throw invalid("Missing request id (client_request_id).", "معرّف الطلب مفقود (client_request_id).");
        }
        // One sale per request id: a concurrent retry waits here for the first, then finds its sale.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", tenantId + ":bazar-sale:" + crid);
        List<UUID> existing = jdbc.queryForList(
            "SELECT id FROM bazar_sales WHERE tenant_id = ? AND client_request_id = ?", UUID.class, tenantId, crid);
        if (!existing.isEmpty()) {
            Map<String, Object> first = saleView(tenantId, existing.get(0), actor);
            first.put("replayed", true);
            return first;
        }

        // The piece — same normalizer and triple match as every piece scan.
        String lookup = ScanInputNormalizer.normalizePiece(code == null ? "" : code);
        List<Map<String, Object>> pieces = jdbc.queryForList(
            "SELECT id, variant_id, status::text AS status, short_code FROM pieces " +
            "WHERE (barcode = ? OR id = ? OR short_code = ?) AND tenant_id = ?", lookup, lookup, lookup, tenantId);
        if (pieces.isEmpty()) {
            throw new BazarException(BazarException.Code.BAZAR_PIECE_NOT_FOUND,
                "This code isn't a piece in your stock.", "هذا الكود ليس قطعة في مخزونك.", HttpStatus.NOT_FOUND);
        }
        Map<String, Object> piece = pieces.get(0);
        if (piece.get("short_code") == null) {
            throw new BazarException(BazarException.Code.BAZAR_PIECE_UNTAGGED,
                "This piece has no barcode yet (untagged), so it can't be sold here yet.",
                "هذه القطعة بدون باركود بعد (غير مُلصقة)، لذلك لا يمكن بيعها هنا بعد.", HttpStatus.CONFLICT);
        }
        String pieceId   = (String) piece.get("id");
        UUID   variantId = (UUID) piece.get("variant_id");
        UUID   transferId = (UUID) ev.get("transfer_id");

        List<Map<String, Object>> tps = jdbc.queryForList(
            "SELECT id, line_id, outcome FROM transfer_pieces WHERE transfer_id = ? AND piece_id = ? AND tenant_id = ? " +
            "ORDER BY created_at DESC, id DESC LIMIT 1", transferId, pieceId, tenantId);
        if (tps.isEmpty()) throw notHere();
        Map<String, Object> tp = tps.get(0);
        if ("sold".equals(tp.get("outcome"))) throw alreadySold(tenantId, pieceId, eventId);
        if (tp.get("outcome") != null || !"out_on_transfer".equals(piece.get("status"))) throw notHere();

        List<Map<String, Object>> prices = jdbc.queryForList(
            "SELECT price, source FROM bazar_event_prices WHERE event_id = ? AND variant_id = ? AND tenant_id = ?",
            eventId, variantId, tenantId);
        if (prices.isEmpty()) {
            throw new BazarException(BazarException.Code.BAZAR_NO_PRICE,
                "Set a price first — this product has no bazar price yet.",
                "حدّد السعر أولًا — هذا المنتج ليس له سعر في البازار بعد.", HttpStatus.CONFLICT);
        }
        BigDecimal price = (BigDecimal) prices.get(0).get("price");
        String chosen = resolveMethod(ev, method);

        UUID saleId = UUID.randomUUID();
        // The referee: the ledger's race-guarded UPDATE. The loser of two concurrent scans lands here.
        try {
            ledger.transition(pieceId, PieceStatus.OUT_ON_TRANSFER, PieceStatus.SOLD, SOLD_EVENT, actor.userId(),
                new TransitionContext(null, null, (UUID) ev.get("location_id"), null,
                    "{\"event_id\":\"" + eventId + "\",\"transfer_id\":\"" + transferId
                        + "\",\"sale_id\":\"" + saleId + "\"}", code));
        } catch (StateConflictException e) {
            // The whole transaction rolls back with this error — nothing of this attempt is written.
            if (e.getActual() == PieceStatus.SOLD) throw alreadySold(tenantId, pieceId, eventId);
            throw notHere();
        }

        int claimed = jdbc.update(
            "UPDATE transfer_pieces SET outcome = 'sold', outcome_verified = true, outcome_at = now(), outcome_by = ? " +
            "WHERE id = ? AND tenant_id = ? AND outcome IS NULL", actor.userId(), tp.get("id"), tenantId);
        if (claimed != 1) throw new IllegalStateException("bazar sale: transfer piece " + tp.get("id") + " not open");
        jdbc.update("UPDATE transfer_lines SET qty_sold = qty_sold + 1 WHERE id = ? AND tenant_id = ?",
            tp.get("line_id"), tenantId);

        Integer number = jdbc.queryForObject(
            "INSERT INTO bazar_sale_counters (tenant_id, last_number) VALUES (?, 1) " +
            "ON CONFLICT (tenant_id) DO UPDATE SET last_number = bazar_sale_counters.last_number + 1 " +
            "RETURNING last_number", Integer.class, tenantId);

        jdbc.update(
            "INSERT INTO bazar_sales (id, tenant_id, event_id, transfer_piece_id, piece_id, variant_id, price, " +
            "  price_source, sale_number, sold_by, business_day, client_request_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, (now() AT TIME ZONE 'Africa/Cairo')::date, ?)",
            saleId, tenantId, eventId, tp.get("id"), pieceId, variantId, price, prices.get(0).get("source"),
            number, actor.userId(), crid);
        insertPayment(tenantId, saleId, chosen, price, actor.userId());

        Map<String, Object> out = saleView(tenantId, saleId, actor);
        out.put("replayed", false);
        return out;
    }

    // ── Undo ─────────────────────────────────────────────────────────────────

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> undo(UUID eventId, UUID saleId, Actor actor) {
        UUID tenantId = requireEnabled();
        Map<String, Object> ev = liveEvent(tenantId, eventId);
        Map<String, Object> sale = lockActiveSale(tenantId, eventId, saleId);
        requireOwnRecentOrManager(ev, sale, actor);

        ledger.transition((String) sale.get("piece_id"), PieceStatus.SOLD, PieceStatus.OUT_ON_TRANSFER, UNDONE_EVENT,
            actor.userId(), new TransitionContext(null, null, (UUID) ev.get("location_id"), null,
                "{\"event_id\":\"" + eventId + "\",\"transfer_id\":\"" + ev.get("transfer_id")
                    + "\",\"sale_id\":\"" + saleId + "\"}", null));

        int reopened = jdbc.update(
            "UPDATE transfer_pieces SET outcome = NULL, outcome_verified = NULL, outcome_at = NULL, outcome_by = NULL " +
            "WHERE id = ? AND tenant_id = ? AND outcome = 'sold'", sale.get("transfer_piece_id"), tenantId);
        if (reopened != 1) {
            throw new IllegalStateException("bazar undo: transfer piece " + sale.get("transfer_piece_id") + " not sold");
        }
        jdbc.update(
            "UPDATE transfer_lines SET qty_sold = qty_sold - 1 " +
            "WHERE id = (SELECT line_id FROM transfer_pieces WHERE id = ?) AND tenant_id = ?",
            sale.get("transfer_piece_id"), tenantId);
        jdbc.update(
            "UPDATE bazar_sales SET status = 'undone', undone_at = now(), undone_by = ?, updated_at = now() " +
            "WHERE id = ? AND tenant_id = ?", actor.userId(), saleId, tenantId);
        voidLivePayments(tenantId, saleId, actor.userId());
        return saleView(tenantId, saleId, actor);
    }

    // ── Edits ────────────────────────────────────────────────────────────────

    /** Change the sale's payment method: void the live payment row(s) + a new payment row. Same method → no change. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> changePayment(UUID eventId, UUID saleId, String method, Actor actor) {
        UUID tenantId = requireEnabled();
        Map<String, Object> ev = liveEvent(tenantId, eventId);
        Map<String, Object> sale = lockActiveSale(tenantId, eventId, saleId);
        requireOwnRecentOrManager(ev, sale, actor);
        if (method == null || method.isBlank()) throw methodRequired();
        String chosen = resolveMethod(ev, method);

        List<String> live = jdbc.queryForList(
            "SELECT method FROM bazar_payments p WHERE p.sale_id = ? AND p.tenant_id = ? AND p.kind = 'payment' " +
            "AND NOT EXISTS (SELECT 1 FROM bazar_payments v WHERE v.voids_payment_id = p.id)",
            String.class, saleId, tenantId);
        if (live.size() == 1 && live.get(0).equals(chosen)) return saleView(tenantId, saleId, actor);

        voidLivePayments(tenantId, saleId, actor.userId());
        insertPayment(tenantId, saleId, chosen, (BigDecimal) sale.get("price"), actor.userId());
        jdbc.update("UPDATE bazar_sales SET updated_at = now() WHERE id = ? AND tenant_id = ?", saleId, tenantId);
        return saleView(tenantId, saleId, actor);
    }

    /** Sets both fields (null / blank clears one). The phone is stored as 01XXXXXXXXX; anything else is refused. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> setCustomer(UUID eventId, UUID saleId, String name, String phone, Actor actor) {
        UUID tenantId = requireEnabled();
        Map<String, Object> ev = liveEvent(tenantId, eventId);
        Map<String, Object> sale = lockActiveSale(tenantId, eventId, saleId);
        boolean mine = actor.userId().equals(sale.get("sold_by")) && sellerRoles(ev).contains(actor.roleKey());
        if (!actor.manages() && !mine) {
            throw new BazarException(BazarException.Code.BAZAR_UNDO_NOT_ALLOWED,
                "Only the seller who made this sale, or an owner or manager, can change its customer.",
                "يمكن فقط للبائع الذي أجرى عملية البيع، أو المالك أو المدير، تعديل بيانات العميل.",
                HttpStatus.FORBIDDEN);
        }

        String n = name == null || name.isBlank() ? null : name.trim();
        if (n != null && n.length() > 80) {
            throw invalid("The customer's name can be up to 80 characters.", "اسم العميل حتى 80 حرفًا.");
        }
        String p = null;
        if (phone != null && !phone.isBlank()) {
            p = normalizePhone(phone);
            if (p == null) {
                throw new BazarException(BazarException.Code.BAZAR_INVALID_PHONE,
                    "Enter an Egyptian mobile number, like 01012345678.",
                    "أدخل رقم موبايل مصري، مثل 01012345678.", HttpStatus.BAD_REQUEST);
            }
        }
        jdbc.update("UPDATE bazar_sales SET customer_name = ?, customer_phone = ?, updated_at = now() " +
            "WHERE id = ? AND tenant_id = ?", n, p, saleId, tenantId);
        return saleView(tenantId, saleId, actor);
    }

    // ── List ─────────────────────────────────────────────────────────────────

    /** Newest first (created_at DESC, id DESC); {@code cursor} is the previous page's {@code next_cursor}. */
    @Transactional(readOnly = true)
    public Map<String, Object> list(UUID eventId, Integer limit, String cursor, Actor actor) {
        UUID tenantId = requireEnabled();
        Map<String, Object> ev = event(tenantId, eventId, "");
        if (!actor.manages() && !sellerRoles(ev).contains(actor.roleKey())) throw notASeller();
        int n = limit == null ? 50 : limit;
        if (n < 1 || n > 200) throw invalid("limit must be 1–200.", "يجب أن يكون الحد بين 1 و200.");

        StringBuilder sql = new StringBuilder(SALE_SELECT + "WHERE s.event_id = ? AND s.tenant_id = ? ");
        List<Object> args = new ArrayList<>(List.of(eventId, tenantId));
        if (cursor != null && !cursor.isBlank()) {
            Object[] c = decodeCursor(cursor);
            sql.append("AND (s.created_at, s.id) < (?, ?) ");
            args.add(c[0]);
            args.add(c[1]);
        }
        sql.append("ORDER BY s.created_at DESC, s.id DESC LIMIT ?");
        args.add(n + 1);
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());

        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < Math.min(n, rows.size()); i++) items.add(present(rows.get(i), actor));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("next_cursor", rows.size() > n ? encodeCursor(rows.get(n - 1)) : null);
        return out;
    }

    // ── Event detail: today's money (used by BazarService.detail) ────────────

    /** Today's (Africa/Cairo) live payments of the event's active sales, by method (every enabled method listed). */
    static Map<String, Object> today(JdbcTemplate jdbc, UUID tenantId, UUID eventId, List<String> methods) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("business_day", jdbc.queryForObject(
            "SELECT ((now() AT TIME ZONE 'Africa/Cairo')::date)::text", String.class));
        Map<String, BigDecimal> byMethod = new LinkedHashMap<>();
        for (String m : methods) byMethod.put(m, BigDecimal.ZERO.setScale(2));
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT p.method, SUM(CASE WHEN p.kind = 'payment' THEN p.amount ELSE -p.amount END) AS total " +
            "FROM bazar_payments p JOIN bazar_sales s ON s.id = p.sale_id AND s.tenant_id = p.tenant_id " +
            "WHERE s.event_id = ? AND s.tenant_id = ? AND s.status = 'active' " +
            "  AND s.business_day = (now() AT TIME ZONE 'Africa/Cairo')::date " +
            "  AND p.kind IN ('payment', 'refund') " +
            "  AND NOT EXISTS (SELECT 1 FROM bazar_payments v WHERE v.voids_payment_id = p.id) " +
            "GROUP BY p.method ORDER BY p.method", eventId, tenantId);
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Map<String, Object> r : rows) {
            BigDecimal t = ((BigDecimal) r.get("total")).setScale(2);
            byMethod.put((String) r.get("method"), t);
            total = total.add(t);
        }
        out.put("by_method", byMethod);
        out.put("total", total);
        out.put("sales", jdbc.queryForObject(
            "SELECT COUNT(*) FROM bazar_sales WHERE event_id = ? AND tenant_id = ? AND status = 'active' " +
            "AND business_day = (now() AT TIME ZONE 'Africa/Cairo')::date", Integer.class, eventId, tenantId));
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static final String SALE_SELECT =
        "SELECT s.id, s.sale_number, s.sold_at, s.created_at, s.status, s.price, s.price_source, s.business_day, " +
        "       s.customer_name, s.customer_phone, s.sold_by, u.name AS seller_name, s.undone_at, s.undone_by, " +
        "       s.piece_id, pc.short_code AS piece_code, s.variant_id, pr.title AS product_title, " +
        "       v.title AS variant_title, " +
        // The method: the live payment row's; an undone sale shows the one it had (its newest payment row).
        "       (SELECT bp.method FROM bazar_payments bp WHERE bp.sale_id = s.id AND bp.tenant_id = s.tenant_id " +
        "          AND bp.kind = 'payment' " +
        "        ORDER BY EXISTS (SELECT 1 FROM bazar_payments v WHERE v.voids_payment_id = bp.id), " +
        "                 bp.created_at DESC, bp.id DESC LIMIT 1) AS method " +
        "FROM bazar_sales s " +
        "JOIN users u ON u.id = s.sold_by " +
        "JOIN pieces pc ON pc.id = s.piece_id " +
        "JOIN variants v ON v.id = s.variant_id " +
        "JOIN products pr ON pr.id = v.product_id ";

    private Map<String, Object> saleView(UUID tenantId, UUID saleId, Actor actor) {
        return present(jdbc.queryForMap(SALE_SELECT + "WHERE s.id = ? AND s.tenant_id = ?", saleId, tenantId), actor);
    }

    private static Map<String, Object> present(Map<String, Object> r, Actor actor) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.get("id"));
        out.put("sale_number", saleNumber((Integer) r.get("sale_number")));
        out.put("sold_at", r.get("sold_at"));
        out.put("business_day", String.valueOf(r.get("business_day")));
        out.put("status", r.get("status"));
        out.put("price", r.get("price"));
        out.put("price_source", r.get("price_source"));
        out.put("method", r.get("method"));
        out.put("piece_id", r.get("piece_id"));
        out.put("piece_code", r.get("piece_code"));
        out.put("variant_id", r.get("variant_id"));
        out.put("product_title", r.get("product_title"));
        out.put("variant_title", r.get("variant_title"));
        out.put("seller_id", r.get("sold_by"));
        out.put("seller_name", r.get("seller_name"));
        out.put("customer_name", r.get("customer_name"));
        String phone = (String) r.get("customer_phone");
        out.put("customer_phone", actor.manages() ? phone : maskPhone(phone));
        out.put("undone_at", r.get("undone_at"));
        return out;
    }

    /** "B-0001" … "B-9999", then "B-10000" and up. */
    static String saleNumber(int n) {
        return "B-" + String.format("%04d", n);
    }

    /** 010•••••678 style: first 3 and last 3 digits only. */
    static String maskPhone(String phone) {
        if (phone == null) return null;
        if (phone.length() <= 6) return "*".repeat(phone.length());
        return phone.substring(0, 3) + "*".repeat(phone.length() - 6) + phone.substring(phone.length() - 3);
    }

    /** Egyptian mobile → 01XXXXXXXXX (010 / 011 / 012 / 015), else null. Arabic-Indic digits accepted. */
    static String normalizePhone(String raw) {
        StringBuilder b = new StringBuilder();
        for (char ch : raw.toCharArray()) {
            if (ch >= '٠' && ch <= '٩') b.append((char) ('0' + (ch - '٠')));
            else if (ch >= '۰' && ch <= '۹') b.append((char) ('0' + (ch - '۰')));
            else if (Character.isDigit(ch) && ch < 128) b.append(ch);
            else if (ch == '+' || ch == ' ' || ch == '-' || ch == '(' || ch == ')' || ch == '.') continue;
            else return null;   // letters or anything else: garbage, not a phone
        }
        String d = b.toString();
        if (d.startsWith("0020") && d.length() == 14) d = "0" + d.substring(4);
        else if (d.startsWith("20") && d.length() == 12) d = "0" + d.substring(2);
        else if (d.length() == 10 && d.startsWith("1")) d = "0" + d;
        return d.matches("01[0125][0-9]{8}") ? d : null;
    }

    private void insertPayment(UUID tenantId, UUID saleId, String method, BigDecimal amount, UUID actorId) {
        // clock_timestamp(): rows written in one transaction (void + new) keep their real order.
        jdbc.update("INSERT INTO bazar_payments (tenant_id, sale_id, method, amount, kind, created_by, created_at) " +
            "VALUES (?, ?, ?, ?, 'payment', ?, clock_timestamp())", tenantId, saleId, method, amount, actorId);
    }

    /** One void row per live payment / refund row of the sale. */
    private void voidLivePayments(UUID tenantId, UUID saleId, UUID actorId) {
        jdbc.update(
            "INSERT INTO bazar_payments (tenant_id, sale_id, method, amount, kind, voids_payment_id, created_by, " +
            "  created_at) " +
            "SELECT p.tenant_id, p.sale_id, p.method, p.amount, 'void', p.id, ?, clock_timestamp() FROM bazar_payments p " +
            "WHERE p.sale_id = ? AND p.tenant_id = ? AND p.kind IN ('payment', 'refund') " +
            "AND NOT EXISTS (SELECT 1 FROM bazar_payments v WHERE v.voids_payment_id = p.id) " +
            "ORDER BY p.created_at, p.id", actorId, saleId, tenantId);
    }

    private String resolveMethod(Map<String, Object> ev, String method) {
        List<String> enabled = toList(ev.get("payment_methods"));
        if (method == null || method.isBlank()) {
            if (enabled.contains("cash")) return "cash";
            throw methodRequired();
        }
        String m = method.trim().toLowerCase();
        if (!enabled.contains(m)) {
            throw new BazarException(BazarException.Code.BAZAR_PAYMENT_METHOD_NOT_ALLOWED,
                "This bazar doesn't take that payment method.", "هذا البازار لا يقبل طريقة الدفع هذه.",
                HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return m;
    }

    private Map<String, Object> lockActiveSale(UUID tenantId, UUID eventId, UUID saleId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, status, piece_id, transfer_piece_id, sold_by, price, " +
            "       sold_at > now() - make_interval(secs => ?) AS recent " +
            "FROM bazar_sales WHERE id = ? AND event_id = ? AND tenant_id = ? FOR UPDATE",
            UNDO_WINDOW_SECONDS, saleId, eventId, tenantId);
        if (rows.isEmpty()) {
            throw new BazarException(BazarException.Code.BAZAR_SALE_NOT_FOUND,
                "Sale not found.", "لم يتم العثور على عملية البيع.", HttpStatus.NOT_FOUND);
        }
        if (!"active".equals(rows.get(0).get("status"))) {
            throw new BazarException(BazarException.Code.BAZAR_SALE_UNDONE,
                "This sale was already undone.", "تم إلغاء عملية البيع هذه بالفعل.", HttpStatus.CONFLICT);
        }
        return rows.get(0);
    }

    private void requireOwnRecentOrManager(Map<String, Object> ev, Map<String, Object> sale, Actor actor) {
        if (actor.manages()) return;
        boolean mine = actor.userId().equals(sale.get("sold_by")) && sellerRoles(ev).contains(actor.roleKey());
        if (!mine || !Boolean.TRUE.equals(sale.get("recent"))) throw undoNotAllowed();
    }

    private BazarException alreadySold(UUID tenantId, String pieceId, UUID eventId) {
        List<Map<String, Object>> s = jdbc.queryForList(
            "SELECT s.sale_number, s.sold_at, u.name AS seller_name FROM bazar_sales s JOIN users u ON u.id = s.sold_by " +
            "WHERE s.piece_id = ? AND s.event_id = ? AND s.tenant_id = ? AND s.status = 'active'",
            pieceId, eventId, tenantId);
        if (s.isEmpty()) return notHere();
        Map<String, Object> d = new LinkedHashMap<>();
        String number = saleNumber((Integer) s.get(0).get("sale_number"));
        d.put("sale_number", number);
        d.put("sold_at", s.get(0).get("sold_at"));
        d.put("seller_name", s.get(0).get("seller_name"));
        String time = ((OffsetDateTime) toOffset(s.get(0).get("sold_at")))
            .atZoneSameInstant(java.time.ZoneId.of("Africa/Cairo")).toLocalTime()
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        return new BazarException(BazarException.Code.BAZAR_ALREADY_SOLD,
            "Already sold — " + number + " at " + time + " by " + d.get("seller_name") + ".",
            "تم بيعها بالفعل — " + number + " الساعة " + time + " بواسطة " + d.get("seller_name") + ".",
            HttpStatus.CONFLICT, d);
    }

    private static Object toOffset(Object ts) {
        if (ts instanceof OffsetDateTime o) return o;
        if (ts instanceof Timestamp t) return t.toInstant().atOffset(java.time.ZoneOffset.UTC);
        throw new IllegalStateException("unexpected timestamp type " + ts);
    }

    private UUID requireEnabled() {
        UUID tenantId = TenantContext.require();
        if (!Bazar.enabled(jdbc, tenantId)) {
            throw new BazarException(BazarException.Code.BAZAR_NOT_ENABLED,
                "Not found.", "غير موجود.", HttpStatus.NOT_FOUND);
        }
        return tenantId;
    }

    /** The event, FOR SHARE (a concurrent end / start waits for the sale), and live. */
    private Map<String, Object> liveEvent(UUID tenantId, UUID eventId) {
        Map<String, Object> ev = event(tenantId, eventId, " FOR SHARE OF e");
        if (!"live".equals(ev.get("status"))) {
            throw new BazarException(BazarException.Code.BAZAR_EVENT_NOT_LIVE,
                "This bazar isn't live — selling is open only while it's running.",
                "هذا البازار ليس جاريًا — البيع متاح فقط أثناء تشغيله.", HttpStatus.CONFLICT);
        }
        return ev;
    }

    private Map<String, Object> event(UUID tenantId, UUID eventId, String lock) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT e.id, e.status, e.location_id, e.transfer_id, e.payment_methods, e.seller_roles " +
            "FROM bazar_events e WHERE e.id = ? AND e.tenant_id = ?" + lock, eventId, tenantId);
        if (rows.isEmpty()) {
            throw new BazarException(BazarException.Code.BAZAR_EVENT_NOT_FOUND,
                "Bazar not found.", "لم يتم العثور على البازار.", HttpStatus.NOT_FOUND);
        }
        return rows.get(0);
    }

    private static List<String> sellerRoles(Map<String, Object> ev) {
        return toList(ev.get("seller_roles"));
    }

    static List<String> toList(Object pgArray) {
        try {
            if (pgArray instanceof java.sql.Array a) return List.of((String[]) a.getArray());
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return List.of();
    }

    private static String encodeCursor(Map<String, Object> row) {
        Instant at = ((OffsetDateTime) toOffset(row.get("created_at"))).toInstant();
        String raw = at.toString() + "|" + row.get("id");
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Object[] decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int bar = raw.indexOf('|');
            return new Object[] { Timestamp.from(Instant.parse(raw.substring(0, bar))),
                                  UUID.fromString(raw.substring(bar + 1)) };
        } catch (RuntimeException e) {
            throw invalid("Bad cursor.", "مؤشر غير صالح.");
        }
    }

    private static BazarException invalid(String en, String ar) {
        return new BazarException(BazarException.Code.BAZAR_INVALID_INPUT, en, ar, HttpStatus.BAD_REQUEST);
    }

    private static BazarException notHere() {
        return new BazarException(BazarException.Code.BAZAR_PIECE_NOT_HERE,
            "This piece isn't at this bazar — it wasn't sent out with its stock.",
            "هذه القطعة ليست في هذا البازار — لم تُرسل مع بضاعته.", HttpStatus.CONFLICT);
    }

    private static BazarException notASeller() {
        return new BazarException(BazarException.Code.BAZAR_NOT_A_SELLER,
            "Your role can't sell at this bazar.", "دورك لا يسمح بالبيع في هذا البازار.", HttpStatus.FORBIDDEN);
    }

    private static BazarException undoNotAllowed() {
        return new BazarException(BazarException.Code.BAZAR_UNDO_NOT_ALLOWED,
            "Only the seller can change this sale, within 2 minutes — after that ask an owner or manager.",
            "يمكن للبائع فقط تعديل هذه العملية خلال دقيقتين — بعد ذلك اطلب من المالك أو المدير.",
            HttpStatus.FORBIDDEN);
    }

    private static BazarException methodRequired() {
        return new BazarException(BazarException.Code.BAZAR_PAYMENT_METHOD_REQUIRED,
            "Choose how the customer paid.", "اختر طريقة دفع العميل.", HttpStatus.BAD_REQUEST);
    }
}
