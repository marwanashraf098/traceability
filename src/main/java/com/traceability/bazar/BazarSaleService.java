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
 * Who: sell needs the caller's role in the event's {@code seller_roles}. Undo: owner or manager any time while the
 * event is live, else the seller who made the sale within {@link #UNDO_WINDOW_SECONDS}. Payment method change and
 * customer edit: owner or manager, or the seller who made the sale, while the event is live. The sales list: a seller role, or owner /
 * manager; a worker sees the customer's phone masked.
 */
@Service
public class BazarSaleService {

    /** A seller (worker) may undo / change the method of their own sale for this long. */
    static final int UNDO_WINDOW_SECONDS = 120;

    static final String SOLD_EVENT   = "bazar_sold";
    static final String UNDONE_EVENT = "bazar_sale_undone";
    static final String RETURNED_EVENT = "bazar_returned";
    static final String RETURN_DAMAGED_EVENT = "bazar_return_damaged";

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

        String crid = requestId(clientRequestId);
        // One sale per request id: a concurrent retry waits here for the first, then finds its sale.
        lockRequest(tenantId, crid);
        List<UUID> existing = jdbc.queryForList(
            "SELECT id FROM bazar_sales WHERE tenant_id = ? AND client_request_id = ?", UUID.class, tenantId, crid);
        if (!existing.isEmpty()) {
            Map<String, Object> first = saleView(tenantId, existing.get(0), actor);
            first.put("replayed", true);
            return first;
        }

        Prepared sale = prepareSale(tenantId, eventId, ev, findPiece(tenantId, code), code);
        String chosen = resolveMethod(ev, method);
        UUID saleId = writeSale(tenantId, eventId, ev, sale, crid, actor, null, null, null);
        insertPayment(tenantId, saleId, chosen, sale.price(), actor.userId());

        Map<String, Object> out = saleView(tenantId, saleId, actor);
        out.put("replayed", false);
        return out;
    }

    /** A piece ready to be sold at the event: checked, nothing written yet. */
    private record Prepared(String pieceId, UUID variantId, UUID transferPieceId, UUID lineId, BigDecimal price,
                            String priceSource, String code) {}

    /** The piece — same normalizer and triple match as every piece scan. Untagged → refused. */
    private Map<String, Object> findPiece(UUID tenantId, String code) {
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
        return piece;
    }

    /** Is the piece at this bazar, unsold and priced? Nothing is written. */
    private Prepared prepareSale(UUID tenantId, UUID eventId, Map<String, Object> ev, Map<String, Object> piece,
                                 String code) {
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
        return new Prepared(pieceId, variantId, (UUID) tp.get("id"), (UUID) tp.get("line_id"),
            (BigDecimal) prices.get(0).get("price"), (String) prices.get(0).get("source"), code);
    }

    /** The sale's writes (no money row): the ledger edge, the transfer piece, the counter, the sale row. */
    private UUID writeSale(UUID tenantId, UUID eventId, Map<String, Object> ev, Prepared p, String crid, Actor actor,
                           UUID exchangeOf, String customerName, String customerPhone) {
        UUID transferId = (UUID) ev.get("transfer_id");
        UUID saleId = UUID.randomUUID();
        // The referee: the ledger's race-guarded UPDATE. The loser of two concurrent scans lands here.
        try {
            ledger.transition(p.pieceId(), PieceStatus.OUT_ON_TRANSFER, PieceStatus.SOLD, SOLD_EVENT, actor.userId(),
                new TransitionContext(null, null, (UUID) ev.get("location_id"), null,
                    "{\"event_id\":\"" + eventId + "\",\"transfer_id\":\"" + transferId
                        + "\",\"sale_id\":\"" + saleId + "\""
                        + (exchangeOf == null ? "" : ",\"exchange_of_sale_id\":\"" + exchangeOf + "\"") + "}",
                    p.code()));
        } catch (StateConflictException e) {
            // The whole transaction rolls back with this error — nothing of this attempt is written.
            if (e.getActual() == PieceStatus.SOLD) throw alreadySold(tenantId, p.pieceId(), eventId);
            throw notHere();
        }

        int claimed = jdbc.update(
            "UPDATE transfer_pieces SET outcome = 'sold', outcome_verified = true, outcome_at = now(), outcome_by = ? " +
            "WHERE id = ? AND tenant_id = ? AND outcome IS NULL", actor.userId(), p.transferPieceId(), tenantId);
        if (claimed != 1) throw new IllegalStateException("bazar sale: transfer piece " + p.transferPieceId() + " not open");
        jdbc.update("UPDATE transfer_lines SET qty_sold = qty_sold + 1 WHERE id = ? AND tenant_id = ?",
            p.lineId(), tenantId);

        Integer number = jdbc.queryForObject(
            "INSERT INTO bazar_sale_counters (tenant_id, last_number) VALUES (?, 1) " +
            "ON CONFLICT (tenant_id) DO UPDATE SET last_number = bazar_sale_counters.last_number + 1 " +
            "RETURNING last_number", Integer.class, tenantId);

        jdbc.update(
            "INSERT INTO bazar_sales (id, tenant_id, event_id, transfer_piece_id, piece_id, variant_id, price, " +
            "  price_source, sale_number, sold_by, business_day, client_request_id, exchange_of_sale_id, " +
            "  customer_name, customer_phone) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, (now() AT TIME ZONE 'Africa/Cairo')::date, ?, ?, ?, ?)",
            saleId, tenantId, eventId, p.transferPieceId(), p.pieceId(), p.variantId(), p.price(), p.priceSource(),
            number, actor.userId(), crid, exchangeOf, customerName, customerPhone);
        return saleId;
    }

    // ── Return (B3) ──────────────────────────────────────────────────────────

    /**
     * Take back a piece sold at THIS event (an active sale) while the event is live: the approved edge
     * sold → out_on_transfer (event 'bazar_returned'). Good: the piece is back on the bazar's stock (its transfer
     * piece outstanding again, the line's qty_sold −1). Damaged: then the existing out_on_transfer → damaged (event
     * 'bazar_return_damaged') and the transfer piece 'condemned' (qty_condemned +1) — never sold again. One 'refund'
     * money row on the sale for its price, by the chosen method (default: the one the customer paid with). Same
     * {@code client_request_id} twice → the first return. Any role in the event's seller_roles. No Shopify write.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> returnSale(UUID eventId, String code, String method, String condition,
                                          String clientRequestId, Actor actor) {
        UUID tenantId = requireEnabled();
        Map<String, Object> ev = liveEvent(tenantId, eventId);
        if (!sellerRoles(ev).contains(actor.roleKey())) throw notASeller();
        String crid = requestId(clientRequestId);
        String cond = condition(condition, false);

        lockRequest(tenantId, crid);
        List<UUID> existing = jdbc.queryForList(
            "SELECT id FROM bazar_sales WHERE tenant_id = ? AND return_client_request_id = ?", UUID.class, tenantId, crid);
        if (!existing.isEmpty()) {
            Map<String, Object> first = returnView(tenantId, existing.get(0), actor);
            first.put("replayed", true);
            return first;
        }

        Map<String, Object> sale = soldHere(tenantId, eventId, (String) findPiece(tenantId, code).get("id"));
        UUID saleId = (UUID) sale.get("id");
        String refundMethod = method == null || method.isBlank() ? paidMethod(tenantId, ev, saleId) : resolveMethod(ev, method);

        takeBack(tenantId, eventId, ev, sale, cond, crid, actor);
        insertMoney(tenantId, saleId, refundMethod, (BigDecimal) sale.get("price"), "refund", actor.userId());

        Map<String, Object> out = returnView(tenantId, saleId, actor);
        out.put("replayed", false);
        return out;
    }

    // ── Exchange (B3) ────────────────────────────────────────────────────────

    /**
     * One transaction: the old sale returned (as {@link #returnSale}, condition good by default) + a NEW sale of the
     * new piece linked to it ({@code exchange_of_sale_id}), the customer carried over. The old price is credited, not
     * paid back — only the DIFFERENCE moves as money, on the new sale: new &gt; old → a 'payment' row, new &lt; old →
     * a 'refund' row, equal → no money row. Anything refused (new piece not here / no price / already sold, old piece
     * not sold here) → nothing changes. Same {@code client_request_id} twice → the first exchange.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> exchange(UUID eventId, String oldCode, String newCode, String method, String condition,
                                        String clientRequestId, Actor actor) {
        UUID tenantId = requireEnabled();
        Map<String, Object> ev = liveEvent(tenantId, eventId);
        if (!sellerRoles(ev).contains(actor.roleKey())) throw notASeller();
        String crid = requestId(clientRequestId);
        String cond = condition(condition, true);

        lockRequest(tenantId, crid);
        List<Map<String, Object>> existing = jdbc.queryForList(
            "SELECT id, exchange_of_sale_id FROM bazar_sales WHERE tenant_id = ? AND client_request_id = ?",
            tenantId, crid);
        if (!existing.isEmpty()) {
            if (existing.get(0).get("exchange_of_sale_id") == null) {
                throw invalid("This request id was already used for a sale.", "تم استخدام معرّف الطلب هذا لعملية بيع.");
            }
            Map<String, Object> first = exchangeView(tenantId, (UUID) existing.get(0).get("id"), actor);
            first.put("replayed", true);
            return first;
        }

        // Check everything before writing anything (the transaction would roll back anyway).
        Map<String, Object> oldSale = soldHere(tenantId, eventId, (String) findPiece(tenantId, oldCode).get("id"));
        Map<String, Object> newPiece = findPiece(tenantId, newCode);
        if (newPiece.get("id").equals(oldSale.get("piece_id"))) {
            throw invalid("Scan a different piece for the exchange — this is the one coming back.",
                "امسح قطعة مختلفة للاستبدال — هذه هي القطعة المرتجعة.");
        }
        Prepared next = prepareSale(tenantId, eventId, ev, newPiece, newCode);
        UUID oldSaleId = (UUID) oldSale.get("id");
        BigDecimal diff = next.price().subtract((BigDecimal) oldSale.get("price"));
        String moneyMethod = diff.signum() == 0 ? null
            : method == null || method.isBlank() ? paidMethod(tenantId, ev, oldSaleId) : resolveMethod(ev, method);

        takeBack(tenantId, eventId, ev, oldSale, cond, crid, actor);
        UUID newSaleId = writeSale(tenantId, eventId, ev, next, crid, actor, oldSaleId,
            (String) oldSale.get("customer_name"), (String) oldSale.get("customer_phone"));
        if (diff.signum() > 0) insertMoney(tenantId, newSaleId, moneyMethod, diff, "payment", actor.userId());
        if (diff.signum() < 0) insertMoney(tenantId, newSaleId, moneyMethod, diff.negate(), "refund", actor.userId());

        Map<String, Object> out = exchangeView(tenantId, newSaleId, actor);
        out.put("replayed", false);
        return out;
    }

    // ── Piece lookup (B3, read-only) ─────────────────────────────────────────

    /**
     * What a scanned piece is at this event, for the phone's Return / Exchange screens before they confirm:
     * {@code state} 'sold' (its active sale here — returnable), 'for_sale' (on the bazar's rail, with its bazar price
     * or null), 'returned' (its last sale here was returned and it isn't for sale — e.g. damaged), 'not_here'.
     * Nothing is written. A seller role or owner / manager.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> lookupPiece(UUID eventId, String code, Actor actor) {
        UUID tenantId = requireEnabled();
        Map<String, Object> ev = event(tenantId, eventId, "");
        if (!actor.manages() && !sellerRoles(ev).contains(actor.roleKey())) throw notASeller();
        Map<String, Object> piece = findPiece(tenantId, code);
        String pieceId = (String) piece.get("id");
        Map<String, Object> info = jdbc.queryForMap(
            "SELECT pc.short_code, pr.title AS product_title, v.title AS variant_title, " +
            "  (SELECT bp.price FROM bazar_event_prices bp WHERE bp.event_id = ? AND bp.variant_id = pc.variant_id " +
            "     AND bp.tenant_id = pc.tenant_id) AS price " +
            "FROM pieces pc JOIN variants v ON v.id = pc.variant_id JOIN products pr ON pr.id = v.product_id " +
            "WHERE pc.id = ? AND pc.tenant_id = ?", eventId, pieceId, tenantId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("piece_id", pieceId);
        out.put("piece_code", info.get("short_code"));
        out.put("product_title", info.get("product_title"));
        out.put("variant_title", info.get("variant_title"));
        out.put("price", info.get("price"));

        List<UUID> active = jdbc.queryForList("SELECT id FROM bazar_sales WHERE piece_id = ? AND event_id = ? " +
            "AND tenant_id = ? AND status = 'active'", UUID.class, pieceId, eventId, tenantId);
        boolean onRail = !jdbc.queryForList(
            "SELECT 1 FROM transfer_pieces WHERE transfer_id = ? AND piece_id = ? AND tenant_id = ? AND outcome IS NULL",
            ev.get("transfer_id"), pieceId, tenantId).isEmpty() && "out_on_transfer".equals(piece.get("status"));
        List<UUID> returned = jdbc.queryForList("SELECT id FROM bazar_sales WHERE piece_id = ? AND event_id = ? " +
            "AND tenant_id = ? AND status = 'returned' ORDER BY returned_at DESC, id DESC LIMIT 1",
            UUID.class, pieceId, eventId, tenantId);
        if (!active.isEmpty()) {
            out.put("state", "sold");
            out.put("sale", saleView(tenantId, active.get(0), actor));
        } else if (onRail) {
            out.put("state", "for_sale");
            out.put("sale", null);
        } else if (!returned.isEmpty()) {
            out.put("state", "returned");
            out.put("sale", saleView(tenantId, returned.get(0), actor));
        } else {
            out.put("state", "not_here");
            out.put("sale", null);
        }
        return out;
    }

    /** The return's writes: ledger edge(s), the transfer piece, the line counters, the sale row → 'returned'. */
    private void takeBack(UUID tenantId, UUID eventId, Map<String, Object> ev, Map<String, Object> sale, String cond,
                          String crid, Actor actor) {
        String pieceId = (String) sale.get("piece_id");
        UUID saleId = (UUID) sale.get("id");
        UUID locationId = (UUID) ev.get("location_id");
        String meta = "{\"event_id\":\"" + eventId + "\",\"transfer_id\":\"" + ev.get("transfer_id")
            + "\",\"sale_id\":\"" + saleId + "\",\"condition\":\"" + cond + "\"}";
        try {
            ledger.transition(pieceId, PieceStatus.SOLD, PieceStatus.OUT_ON_TRANSFER, RETURNED_EVENT, actor.userId(),
                new TransitionContext(null, null, locationId, null, meta, null));
        } catch (StateConflictException e) {
            throw alreadyReturned(tenantId, saleId);
        }
        boolean damaged = "damaged".equals(cond);
        if (damaged) {
            ledger.transition(pieceId, PieceStatus.OUT_ON_TRANSFER, PieceStatus.DAMAGED, RETURN_DAMAGED_EVENT,
                actor.userId(), new TransitionContext(null, null, locationId, null, meta, null));
        }
        int moved = damaged
            ? jdbc.update("UPDATE transfer_pieces SET outcome = 'condemned', outcome_verified = true, " +
                "outcome_at = now(), outcome_by = ? WHERE id = ? AND tenant_id = ? AND outcome = 'sold'",
                actor.userId(), sale.get("transfer_piece_id"), tenantId)
            : jdbc.update("UPDATE transfer_pieces SET outcome = NULL, outcome_verified = NULL, outcome_at = NULL, " +
                "outcome_by = NULL WHERE id = ? AND tenant_id = ? AND outcome = 'sold'",
                sale.get("transfer_piece_id"), tenantId);
        if (moved != 1) {
            throw new IllegalStateException("bazar return: transfer piece " + sale.get("transfer_piece_id") + " not sold");
        }
        jdbc.update(
            "UPDATE transfer_lines SET qty_sold = qty_sold - 1" + (damaged ? ", qty_condemned = qty_condemned + 1" : "") +
            " WHERE id = (SELECT line_id FROM transfer_pieces WHERE id = ?) AND tenant_id = ?",
            sale.get("transfer_piece_id"), tenantId);
        int returned = jdbc.update(
            "UPDATE bazar_sales SET status = 'returned', returned_at = now(), returned_by = ?, return_condition = ?, " +
            "  return_client_request_id = ?, updated_at = now() WHERE id = ? AND tenant_id = ? AND status = 'active'",
            actor.userId(), cond, crid, saleId, tenantId);
        if (returned != 1) throw alreadyReturned(tenantId, saleId);
    }

    /**
     * The piece's live sale at THIS event, locked. None → BAZAR_NOT_SOLD_HERE; only a returned one →
     * BAZAR_ALREADY_RETURNED (an undone sale never counts: it wasn't sold).
     */
    private Map<String, Object> soldHere(UUID tenantId, UUID eventId, String pieceId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, status, piece_id, transfer_piece_id, sold_by, price, customer_name, customer_phone " +
            "FROM bazar_sales WHERE piece_id = ? AND event_id = ? AND tenant_id = ? AND status = 'active' FOR UPDATE",
            pieceId, eventId, tenantId);
        if (!rows.isEmpty() && "active".equals(rows.get(0).get("status"))) return rows.get(0);
        List<UUID> returned = jdbc.queryForList(
            "SELECT id FROM bazar_sales WHERE piece_id = ? AND event_id = ? AND tenant_id = ? AND status = 'returned' " +
            "ORDER BY returned_at DESC, id DESC LIMIT 1", UUID.class, pieceId, eventId, tenantId);
        if (!returned.isEmpty()) throw alreadyReturned(tenantId, returned.get(0));
        throw new BazarException(BazarException.Code.BAZAR_NOT_SOLD_HERE,
            "This piece wasn't sold at this bazar, so it can't be returned or exchanged here.",
            "هذه القطعة لم تُبع في هذا البازار، لذلك لا يمكن إرجاعها أو استبدالها هنا.", HttpStatus.CONFLICT);
    }

    /** The method the customer paid with: the sale's own live payment row, else (an exchange) the old sale's. */
    private String paidMethod(UUID tenantId, Map<String, Object> ev, UUID saleId) {
        List<String> enabled = toList(ev.get("payment_methods"));
        UUID current = saleId;
        for (int hops = 0; current != null && hops < 20; hops++) {
            List<String> m = jdbc.queryForList(
                "SELECT p.method FROM bazar_payments p WHERE p.sale_id = ? AND p.tenant_id = ? AND p.kind = 'payment' " +
                "AND NOT EXISTS (SELECT 1 FROM bazar_payments v WHERE v.voids_payment_id = p.id) " +
                "ORDER BY p.created_at DESC, p.id DESC LIMIT 1", String.class, current, tenantId);
            if (!m.isEmpty() && enabled.contains(m.get(0))) return m.get(0);
            List<UUID> prev = jdbc.queryForList(
                "SELECT exchange_of_sale_id FROM bazar_sales WHERE id = ? AND tenant_id = ?", UUID.class, current, tenantId);
            current = prev.isEmpty() ? null : prev.get(0);
        }
        return resolveMethod(ev, null);
    }

    private static String condition(String condition, boolean optional) {
        if ((condition == null || condition.isBlank()) && optional) return "good";
        String c = condition == null ? "" : condition.trim().toLowerCase();
        if (!c.equals("good") && !c.equals("damaged")) {
            throw invalid("Say whether the piece is good or damaged.", "حدّد إن كانت القطعة سليمة أم تالفة.");
        }
        return c;
    }

    private BazarException alreadyReturned(UUID tenantId, UUID saleId) {
        Map<String, Object> r = jdbc.queryForMap(
            "SELECT s.sale_number, s.returned_at, u.name AS returned_by_name FROM bazar_sales s " +
            "LEFT JOIN users u ON u.id = s.returned_by WHERE s.id = ? AND s.tenant_id = ?", saleId, tenantId);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("sale_number", saleNumber((Integer) r.get("sale_number")));
        d.put("returned_at", r.get("returned_at"));
        d.put("returned_by_name", r.get("returned_by_name"));
        return new BazarException(BazarException.Code.BAZAR_ALREADY_RETURNED,
            "This sale (" + d.get("sale_number") + ") was already returned.",
            "تم إرجاع عملية البيع هذه (" + d.get("sale_number") + ") بالفعل.", HttpStatus.CONFLICT, d);
    }

    /** {sale (the returned one), refund: {method, amount}}. */
    private Map<String, Object> returnView(UUID tenantId, UUID saleId, Actor actor) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sale", saleView(tenantId, saleId, actor));
        List<Map<String, Object>> refund = jdbc.queryForList(
            "SELECT method, amount FROM bazar_payments WHERE sale_id = ? AND tenant_id = ? AND kind = 'refund' " +
            "ORDER BY created_at, id LIMIT 1", saleId, tenantId);
        out.put("refund", refund.isEmpty() ? null : money(refund.get(0)));
        return out;
    }

    /** {old_sale, new_sale, difference (new − old: + the customer pays, − they get back), money: {kind, method, amount} | null}. */
    private Map<String, Object> exchangeView(UUID tenantId, UUID newSaleId, Actor actor) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> next = saleView(tenantId, newSaleId, actor);
        UUID oldId = jdbc.queryForObject("SELECT exchange_of_sale_id FROM bazar_sales WHERE id = ? AND tenant_id = ?",
            UUID.class, newSaleId, tenantId);
        Map<String, Object> old = saleView(tenantId, oldId, actor);
        out.put("old_sale", old);
        out.put("new_sale", next);
        out.put("difference", ((BigDecimal) next.get("price")).subtract((BigDecimal) old.get("price")).setScale(2));
        List<Map<String, Object>> m = jdbc.queryForList(
            "SELECT kind, method, amount FROM bazar_payments WHERE sale_id = ? AND tenant_id = ? " +
            "AND kind IN ('payment', 'refund') ORDER BY created_at, id LIMIT 1", newSaleId, tenantId);
        if (m.isEmpty()) {
            out.put("money", null);
        } else {
            Map<String, Object> row = money(m.get(0));
            row.put("kind", m.get(0).get("kind"));
            out.put("money", row);
        }
        return out;
    }

    private static Map<String, Object> money(Map<String, Object> r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("method", r.get("method"));
        out.put("amount", ((BigDecimal) r.get("amount")).setScale(2));
        return out;
    }

    // ── Undo ─────────────────────────────────────────────────────────────────

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> undo(UUID eventId, UUID saleId, Actor actor) {
        UUID tenantId = requireEnabled();
        Map<String, Object> ev = liveEvent(tenantId, eventId);
        Map<String, Object> sale = lockActiveSale(tenantId, eventId, saleId);
        requireOwnRecentOrManager(ev, sale, actor);
        if (sale.get("exchange_of_sale_id") != null) {
            // Undoing only the new half would lose the credit for the piece that came back (B3).
            throw new BazarException(BazarException.Code.BAZAR_UNDO_NOT_ALLOWED,
                "This sale is part of an exchange, so it can't be undone — return the piece instead.",
                "عملية البيع هذه جزء من استبدال، لذلك لا يمكن إلغاؤها — قم بإرجاع القطعة بدلًا من ذلك.",
                HttpStatus.CONFLICT);
        }

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
        // The seller may fix the method of their own sale for as long as the event is live (approved 2026-11-16;
        // B4 tightens it to "until that business day is closed"). Owner / manager: any time while live.
        boolean mine = actor.userId().equals(sale.get("sold_by")) && sellerRoles(ev).contains(actor.roleKey());
        if (!actor.manages() && !mine) {
            throw new BazarException(BazarException.Code.BAZAR_UNDO_NOT_ALLOWED,
                "Only the seller who made this sale, or an owner or manager, can change how it was paid.",
                "يمكن فقط للبائع الذي أجرى عملية البيع، أو المالك أو المدير، تغيير طريقة الدفع.",
                HttpStatus.FORBIDDEN);
        }
        if (method == null || method.isBlank()) throw methodRequired();
        String chosen = resolveMethod(ev, method);

        // Every live money row of the sale is re-written with the new method, same kind and amount: a plain sale has
        // one payment row (its price); an exchange's new sale has its difference row, or none for an even swap (B3).
        List<Map<String, Object>> live = jdbc.queryForList(
            "SELECT method, kind, amount FROM bazar_payments p WHERE p.sale_id = ? AND p.tenant_id = ? " +
            "AND p.kind IN ('payment', 'refund') " +
            "AND NOT EXISTS (SELECT 1 FROM bazar_payments v WHERE v.voids_payment_id = p.id) ORDER BY p.created_at, p.id",
            saleId, tenantId);
        if (live.stream().allMatch(r -> chosen.equals(r.get("method")))) return saleView(tenantId, saleId, actor);

        voidLivePayments(tenantId, saleId, actor.userId());
        for (Map<String, Object> r : live) {
            insertMoney(tenantId, saleId, chosen, (BigDecimal) r.get("amount"), (String) r.get("kind"), actor.userId());
        }
        jdbc.update("UPDATE bazar_sales SET updated_at = now() WHERE id = ? AND tenant_id = ?", saleId, tenantId);
        return saleView(tenantId, saleId, actor);
    }

    /** Sets both fields (null / blank clears one). The phone is stored as 01XXXXXXXXX; anything else is refused. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> setCustomer(UUID eventId, UUID saleId, String name, String phone, Actor actor) {
        return editCustomer(eventId, saleId, true, name, true, phone, actor);
    }

    /**
     * B6 review: a field NOT sent stays as it is; a field sent empty (null / blank) is cleared. So a worker — who sees
     * the phone masked and can't send it back — adds or changes the name without touching the stored number.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> editCustomer(UUID eventId, UUID saleId, boolean nameSent, String name,
                                            boolean phoneSent, String phone, Actor actor) {
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

        String n = !nameSent || name == null || name.isBlank() ? null : name.trim();
        if (n != null && n.length() > 80) {
            throw invalid("The customer's name can be up to 80 characters.", "اسم العميل حتى 80 حرفًا.");
        }
        String p = null;
        if (phoneSent && phone != null && !phone.isBlank()) {
            p = normalizePhone(phone);
            if (p == null) {
                throw new BazarException(BazarException.Code.BAZAR_INVALID_PHONE,
                    "Enter an Egyptian mobile number, like 01012345678.",
                    "أدخل رقم موبايل مصري، مثل 01012345678.", HttpStatus.BAD_REQUEST);
            }
        }
        if (!nameSent && !phoneSent) return saleView(tenantId, saleId, actor);
        jdbc.update("UPDATE bazar_sales SET " +
            "customer_name  = CASE WHEN ? THEN ? ELSE customer_name END, " +
            "customer_phone = CASE WHEN ? THEN ? ELSE customer_phone END, updated_at = now() " +
            "WHERE id = ? AND tenant_id = ?", nameSent, n, phoneSent, p, saleId, tenantId);
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

    // ── Today (B6 review) ────────────────────────────────────────────────────

    /**
     * The event's numbers for today (Africa/Cairo): total and by method = the real money that moved today ({@link
     * #summary}: payments − refunds; an undone sale is out), the count of active sales, returns, exchanges, revenue,
     * and per seller their active sales' count and sum. Any role in the event's
     * {@code seller_roles}, or owner / manager — the phone's Home and Sell footer read this, never a sum of the paged
     * sales list.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> todaySummary(UUID eventId, Actor actor) {
        UUID tenantId = requireEnabled();
        Map<String, Object> ev = event(tenantId, eventId, "");
        if (!actor.manages() && !sellerRoles(ev).contains(actor.roleKey())) throw notASeller();
        Map<String, Object> t = today(jdbc, tenantId, eventId, toList(ev.get("payment_methods")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("business_day", t.get("business_day"));
        out.put("total", t.get("total"));
        out.put("sale_count", t.get("sales"));
        out.put("by_method", t.get("by_method"));
        out.put("returns", t.get("returns"));
        out.put("exchanges", t.get("exchanges"));
        out.put("revenue", t.get("revenue"));
        List<Map<String, Object>> perSeller = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT s.sold_by, u.name AS seller_name, COUNT(*) AS sale_count, SUM(s.price) AS total " +
                "FROM bazar_sales s JOIN users u ON u.id = s.sold_by " +
                "WHERE s.event_id = ? AND s.tenant_id = ? AND s.status = 'active' " +
                "  AND s.business_day = (now() AT TIME ZONE 'Africa/Cairo')::date " +
                "GROUP BY s.sold_by, u.name ORDER BY COUNT(*) DESC, u.name, s.sold_by", eventId, tenantId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seller_id", String.valueOf(r.get("sold_by")));
            row.put("seller_name", r.get("seller_name"));
            row.put("sale_count", ((Number) r.get("sale_count")).intValue());
            row.put("total", ((BigDecimal) r.get("total")).setScale(2));
            perSeller.add(row);
        }
        out.put("per_seller", perSeller);
        return out;
    }

    // ── Event detail: today's money (used by BazarService.detail) ────────────

    /** Today's (Africa/Cairo) numbers — see {@link #summary}. */
    static Map<String, Object> today(JdbcTemplate jdbc, UUID tenantId, UUID eventId, List<String> methods) {
        return summary(jdbc, tenantId, eventId, methods, true);
    }

    /**
     * The event's numbers — today (Africa/Cairo) or, {@code todayOnly} false, the whole event (B3):
     * <ul>
     *   <li>{@code by_method} / {@code total}: REAL money that moved — every live (not voided) 'payment' row minus
     *       every live 'refund' row, counted on the day the row was written. A sale's payment stays when it is
     *       returned (the money came in) and the return's refund takes it back out; an exchange moves only its
     *       difference; an undone sale's rows are voided. Every enabled method is listed.</li>
     *   <li>{@code sales}: active sales sold that day (returned / undone ones are out). {@code returns}: sales returned
     *       that day that weren't exchanged. {@code exchanges}: exchanges made that day.</li>
     *   <li>{@code revenue}: sales sold that day (not undone) minus the price of sales returned that day — exchanges
     *       net to their difference. It equals {@code total}: every sale is paid in full and every return refunded
     *       in full, by construction.</li>
     * </ul>
     */
    static Map<String, Object> summary(JdbcTemplate jdbc, UUID tenantId, UUID eventId, List<String> methods,
                                       boolean todayOnly) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (todayOnly) {
            out.put("business_day", jdbc.queryForObject(
                "SELECT ((now() AT TIME ZONE 'Africa/Cairo')::date)::text", String.class));
        }
        String cairoToday = "(now() AT TIME ZONE 'Africa/Cairo')::date";
        Map<String, BigDecimal> byMethod = new LinkedHashMap<>();
        for (String m : methods) byMethod.put(m, BigDecimal.ZERO.setScale(2));
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT p.method, SUM(CASE WHEN p.kind = 'payment' THEN p.amount ELSE -p.amount END) AS total " +
            "FROM bazar_payments p JOIN bazar_sales s ON s.id = p.sale_id AND s.tenant_id = p.tenant_id " +
            "WHERE s.event_id = ? AND s.tenant_id = ? AND p.kind IN ('payment', 'refund') " +
            (todayOnly ? "  AND (p.created_at AT TIME ZONE 'Africa/Cairo')::date = " + cairoToday + " " : "") +
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

        String soldDay = todayOnly ? " AND s.business_day = " + cairoToday : "";
        String returnedDay = todayOnly ? " AND (s.returned_at AT TIME ZONE 'Africa/Cairo')::date = " + cairoToday : "";
        Map<String, Object> c = jdbc.queryForMap(
            "SELECT " +
            "  (SELECT COUNT(*) FROM bazar_sales s WHERE s.event_id = ? AND s.tenant_id = ? AND s.status = 'active'" +
                soldDay + ") AS sales, " +
            "  (SELECT COUNT(*) FROM bazar_sales s WHERE s.event_id = ? AND s.tenant_id = ? AND s.status = 'returned'" +
                returnedDay + " AND NOT EXISTS (SELECT 1 FROM bazar_sales x WHERE x.exchange_of_sale_id = s.id)) AS returns, " +
            "  (SELECT COUNT(*) FROM bazar_sales s WHERE s.event_id = ? AND s.tenant_id = ? " +
                "AND s.exchange_of_sale_id IS NOT NULL AND s.status <> 'undone'" + soldDay + ") AS exchanges, " +
            "  (SELECT COALESCE(SUM(s.price), 0) FROM bazar_sales s WHERE s.event_id = ? AND s.tenant_id = ? " +
                "AND s.status <> 'undone'" + soldDay + ") " +
            "  - (SELECT COALESCE(SUM(s.price), 0) FROM bazar_sales s WHERE s.event_id = ? AND s.tenant_id = ? " +
                "AND s.status = 'returned'" + returnedDay + ") AS revenue",
            eventId, tenantId, eventId, tenantId, eventId, tenantId, eventId, tenantId, eventId, tenantId);
        out.put("sales", ((Number) c.get("sales")).intValue());
        out.put("returns", ((Number) c.get("returns")).intValue());
        out.put("exchanges", ((Number) c.get("exchanges")).intValue());
        out.put("revenue", ((BigDecimal) c.get("revenue")).setScale(2));
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static final String SALE_SELECT =
        "SELECT s.id, s.sale_number, s.sold_at, s.created_at, s.status, s.price, s.price_source, s.business_day, " +
        "       s.customer_name, s.customer_phone, s.sold_by, u.name AS seller_name, s.undone_at, s.undone_by, " +
        "       s.piece_id, pc.short_code AS piece_code, s.variant_id, pr.title AS product_title, " +
        "       v.title AS variant_title, s.returned_at, s.return_condition, ru.name AS returned_by_name, " +
        "       s.exchange_of_sale_id, xo.sale_number AS exchange_of_sale_number, " +
        "       xn.id AS exchanged_for_sale_id, xn.sale_number AS exchanged_for_sale_number, " +
        // The method: the live payment row's; an undone sale shows the one it had (its newest payment row).
        "       (SELECT bp.method FROM bazar_payments bp WHERE bp.sale_id = s.id AND bp.tenant_id = s.tenant_id " +
        "          AND bp.kind = 'payment' " +
        "        ORDER BY EXISTS (SELECT 1 FROM bazar_payments v WHERE v.voids_payment_id = bp.id), " +
        "                 bp.created_at DESC, bp.id DESC LIMIT 1) AS method " +
        "FROM bazar_sales s " +
        "JOIN users u ON u.id = s.sold_by " +
        "JOIN pieces pc ON pc.id = s.piece_id " +
        "JOIN variants v ON v.id = s.variant_id " +
        "JOIN products pr ON pr.id = v.product_id " +
        "LEFT JOIN users ru ON ru.id = s.returned_by " +
        "LEFT JOIN bazar_sales xo ON xo.id = s.exchange_of_sale_id AND xo.tenant_id = s.tenant_id " +
        "LEFT JOIN bazar_sales xn ON xn.exchange_of_sale_id = s.id AND xn.tenant_id = s.tenant_id ";

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
        // B3: a returned sale (exchanged when a new sale points at it), and the new half of an exchange.
        out.put("returned_at", r.get("returned_at"));
        out.put("return_condition", r.get("return_condition"));
        out.put("returned_by_name", r.get("returned_by_name"));
        out.put("exchange_of_sale_id", r.get("exchange_of_sale_id"));
        out.put("exchange_of_sale_number",
            r.get("exchange_of_sale_number") == null ? null : saleNumber((Integer) r.get("exchange_of_sale_number")));
        out.put("exchanged_for_sale_id", r.get("exchanged_for_sale_id"));
        out.put("exchanged_for_sale_number",
            r.get("exchanged_for_sale_number") == null ? null : saleNumber((Integer) r.get("exchanged_for_sale_number")));
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
        insertMoney(tenantId, saleId, method, amount, "payment", actorId);
    }

    /** kind 'payment' (money in) or 'refund' (money out). */
    private void insertMoney(UUID tenantId, UUID saleId, String method, BigDecimal amount, String kind, UUID actorId) {
        // clock_timestamp(): rows written in one transaction (void + new) keep their real order.
        jdbc.update("INSERT INTO bazar_payments (tenant_id, sale_id, method, amount, kind, created_by, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, clock_timestamp())", tenantId, saleId, method, amount, kind, actorId);
    }

    private static String requestId(String clientRequestId) {
        String crid = clientRequestId == null ? "" : clientRequestId.trim();
        if (crid.isEmpty() || crid.length() > 100) {
            throw invalid("Missing request id (client_request_id).", "معرّف الطلب مفقود (client_request_id).");
        }
        return crid;
    }

    /** One write per request id: a concurrent retry waits here for the first (sell / return / exchange share it). */
    private void lockRequest(UUID tenantId, String crid) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", tenantId + ":bazar-sale:" + crid);
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
            "SELECT id, status, piece_id, transfer_piece_id, sold_by, price, exchange_of_sale_id, " +
            "       sold_at > now() - make_interval(secs => ?) AS recent " +
            "FROM bazar_sales WHERE id = ? AND event_id = ? AND tenant_id = ? FOR UPDATE",
            UNDO_WINDOW_SECONDS, saleId, eventId, tenantId);
        if (rows.isEmpty()) {
            throw new BazarException(BazarException.Code.BAZAR_SALE_NOT_FOUND,
                "Sale not found.", "لم يتم العثور على عملية البيع.", HttpStatus.NOT_FOUND);
        }
        if ("returned".equals(rows.get(0).get("status"))) throw alreadyReturned(tenantId, saleId);
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
