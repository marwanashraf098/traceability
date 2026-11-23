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

    /** U1a: a Keep-mode bazar's piece changes queue their variants for Main reconcile (after commit). */
    private BazarKeepModeQueue keepQueue;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setKeepQueue(BazarKeepModeQueue keepQueue) { this.keepQueue = keepQueue; }

    private void queueKeep(UUID tenantId, Map<String, Object> ev, List<String> pieceIds, String source) {
        if (keepQueue != null) keepQueue.pieces(tenantId, (UUID) ev.get("transfer_id"), pieceIds, source);
    }

    /** The caller: user id + role ('owner' / 'manager' / 'worker'). */
    public record Actor(UUID userId, String role) {
        boolean manages() { return role != null && MANAGERS.contains(role.toLowerCase()); }
        String  roleKey() { return role == null ? "" : role.toLowerCase(); }
    }

    // ── Sell ─────────────────────────────────────────────────────────────────

    // The B2 shape. Its own @Transactional: the call below is a self-call, which never goes through the proxy.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> sell(UUID eventId, String code, String clientRequestId, String method, Actor actor) {
        return sell(eventId, code, null, clientRequestId, method, actor);
    }

    /**
     * B7a: {@code variantId} instead of a code ("tap to sell") — the server picks the piece ({@link #pickPiece}: the
     * oldest open piece of that variant at this bazar) inside this transaction; everything after that is the scan
     * sale's own path (prepareSale → writeSale → insertPayment). Exactly one of code / variantId.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> sell(UUID eventId, String code, UUID variantId, String clientRequestId, String method,
                                    Actor actor) {
        UUID tenantId = requireEnabled();
        Map<String, Object> ev = liveEvent(tenantId, eventId);
        if (!sells(ev, actor)) throw notASeller();

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

        boolean tapped = (code == null || code.isBlank()) && variantId != null;
        if (!tapped && (code == null || code.isBlank())) {
            throw invalid("Scan a piece or pick a product.", "امسح قطعة أو اختر منتجًا.");
        }
        Map<String, Object> piece = tapped ? pickPiece(tenantId, ev, variantId, Set.of()) : findPiece(tenantId, code);
        Prepared sale = prepareSale(tenantId, eventId, ev, piece, tapped ? pieceCode(piece) : code);
        String chosen = resolveMethod(ev, method);
        UUID saleId = writeSale(tenantId, eventId, ev, sale, crid, actor, null, null, null);
        insertPayment(tenantId, eventId, saleId, chosen, sale.price(), actor.userId());

        Map<String, Object> out = saleView(tenantId, saleId, actor);
        out.put("replayed", false);
        return out;
    }

    /** A piece ready to be sold at the event: checked, nothing written yet. */
    private record Prepared(String pieceId, UUID variantId, UUID transferPieceId, UUID lineId, BigDecimal price,
                            String priceSource, String code) {}

    /** The piece — same normalizer and triple match as every piece scan. Untagged → refused. */
    private Map<String, Object> findPiece(UUID tenantId, String code) {
        if (ScanInputNormalizer.productNumber(code) != null) {
            // B7a: "12" / "#12" is a product number from the price sheet — it opens the product's sizes on the
            // phone, it never sells as a code.
            throw new BazarException(BazarException.Code.BAZAR_PRODUCT_NUMBER,
                "That's a product number from the price sheet — pick the size to sell it.",
                "هذا رقم منتج من قائمة الأسعار — اختر المقاس لبيعه.", HttpStatus.BAD_REQUEST);
        }
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

    /**
     * B7a — THE piece choice for a tapped variant: the OLDEST piece scanned out to this bazar that is still open on its
     * transfer (no outcome) and {@code out_on_transfer}. {@code FOR UPDATE OF tp SKIP LOCKED}: a piece another
     * seller's sale is writing right now is skipped (never waited on — a plain FOR UPDATE could come back empty after
     * the wait even with another piece free); the ledger's race-guarded UPDATE in writePiece stays the referee.
     * {@code exclude}: pieces this transaction already holds (a cart's other lines — SKIP LOCKED doesn't skip
     * one's own locks). None → BAZAR_SOLD_OUT with the variant and how many are left.
     */
    private Map<String, Object> pickPiece(UUID tenantId, Map<String, Object> ev, UUID variantId, Set<String> exclude) {
        List<Object> args = new ArrayList<>(List.of(ev.get("transfer_id"), tenantId, variantId));
        String notIn = "";
        if (!exclude.isEmpty()) {
            notIn = " AND p.id NOT IN (" + String.join(", ", java.util.Collections.nCopies(exclude.size(), "?")) + ")";
            args.addAll(exclude);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT p.id, p.variant_id, p.status::text AS status, p.short_code " +
            "FROM transfer_pieces tp JOIN pieces p ON p.id = tp.piece_id AND p.tenant_id = tp.tenant_id " +
            "WHERE tp.transfer_id = ? AND tp.tenant_id = ? AND tp.outcome IS NULL AND p.status = 'out_on_transfer' " +
            "  AND p.variant_id = ?" + notIn + " " +
            "ORDER BY tp.created_at, tp.id LIMIT 1 FOR UPDATE OF tp SKIP LOCKED", args.toArray());
        if (rows.isEmpty()) throw soldOut(tenantId, ev, variantId);
        return rows.get(0);
    }

    /** How many pieces of the variant are still open at this bazar (what a tap could still sell). */
    private int openLeft(UUID tenantId, Map<String, Object> ev, UUID variantId) {
        Integer n = jdbc.queryForObject(
            "SELECT count(*)::int FROM transfer_pieces tp JOIN pieces p ON p.id = tp.piece_id AND p.tenant_id = tp.tenant_id " +
            "WHERE tp.transfer_id = ? AND tp.tenant_id = ? AND tp.outcome IS NULL AND p.status = 'out_on_transfer' " +
            "  AND p.variant_id = ?", Integer.class, ev.get("transfer_id"), tenantId, variantId);
        return n == null ? 0 : n;
    }

    /** BAZAR_SOLD_OUT (409): details {variant_id, left, product_title, variant_title}. */
    private BazarException soldOut(UUID tenantId, Map<String, Object> ev, UUID variantId) {
        List<Map<String, Object>> v = jdbc.queryForList(
            "SELECT pr.title AS product_title, v.title AS variant_title FROM variants v " +
            "JOIN products pr ON pr.id = v.product_id AND pr.tenant_id = v.tenant_id WHERE v.id = ? AND v.tenant_id = ?",
            variantId, tenantId);
        String name = v.isEmpty() ? "" : v.get(0).get("product_title")
            + (v.get(0).get("variant_title") == null ? "" : " · " + v.get(0).get("variant_title"));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("variant_id", variantId);
        d.put("left", openLeft(tenantId, ev, variantId));
        if (!v.isEmpty()) {
            d.put("product_title", v.get(0).get("product_title"));
            d.put("variant_title", v.get(0).get("variant_title"));
        }
        return new BazarException(BazarException.Code.BAZAR_SOLD_OUT,
            (name.isEmpty() ? "This size" : name) + " is sold out at this bazar.",
            (name.isEmpty() ? "هذا المقاس" : name) + " نفد في هذا البازار.", HttpStatus.CONFLICT, d);
    }

    /** What a tapped sale records as its scan code: the piece's short code, else its internal id. */
    private static String pieceCode(Map<String, Object> piece) {
        Object sc = piece.get("short_code");
        return sc != null ? (String) sc : (String) piece.get("id");
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
        // B4: taken back damaged at this bazar (B3) — its transfer piece is 'condemned', the piece damaged. It can't be
        // sold, nor given as the new piece of an exchange (both come through here).
        if ("condemned".equals(tp.get("outcome")) && "damaged".equals(piece.get("status"))) throw pieceDamaged();
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
        return writePiece(tenantId, eventId, ev, p, crid, actor, exchangeOf, customerName, customerPhone,
            null, null, BigDecimal.ZERO);
    }

    /**
     * The piece's writes: the ledger edge, the transfer piece, the line counter, and its bazar_sales row — a quick
     * sale (own B-number + request id) or, B6c, a cart line ({@code receiptId} + {@code lineNo} + its discount share;
     * the B-number and request id are the receipt's).
     */
    private UUID writePiece(UUID tenantId, UUID eventId, Map<String, Object> ev, Prepared p, String crid, Actor actor,
                            UUID exchangeOf, String customerName, String customerPhone, UUID receiptId, Integer lineNo,
                            BigDecimal discount) {
        UUID transferId = (UUID) ev.get("transfer_id");
        UUID saleId = UUID.randomUUID();
        // The referee: the ledger's race-guarded UPDATE. The loser of two concurrent scans lands here.
        try {
            ledger.transition(p.pieceId(), PieceStatus.OUT_ON_TRANSFER, PieceStatus.SOLD, SOLD_EVENT, actor.userId(),
                new TransitionContext(null, null, (UUID) ev.get("location_id"), null,
                    "{\"event_id\":\"" + eventId + "\",\"transfer_id\":\"" + transferId
                        + "\",\"sale_id\":\"" + saleId + "\""
                        + (exchangeOf == null ? "" : ",\"exchange_of_sale_id\":\"" + exchangeOf + "\"")
                        + (receiptId == null ? "" : ",\"receipt_id\":\"" + receiptId + "\"") + "}",
                    p.code()));
        } catch (StateConflictException e) {
            // The whole transaction rolls back with this error — nothing of this attempt is written.
            if (e.getActual() == PieceStatus.SOLD) throw alreadySold(tenantId, p.pieceId(), eventId);
            throw notHere();
        }
        queueKeep(tenantId, ev, List.of(p.pieceId()), BazarKeepModeQueue.SALE);

        int claimed = jdbc.update(
            "UPDATE transfer_pieces SET outcome = 'sold', outcome_verified = true, outcome_at = now(), outcome_by = ? " +
            "WHERE id = ? AND tenant_id = ? AND outcome IS NULL", actor.userId(), p.transferPieceId(), tenantId);
        if (claimed != 1) throw new IllegalStateException("bazar sale: transfer piece " + p.transferPieceId() + " not open");
        jdbc.update("UPDATE transfer_lines SET qty_sold = qty_sold + 1 WHERE id = ? AND tenant_id = ?",
            p.lineId(), tenantId);

        Integer number = receiptId == null ? nextNumber(tenantId) : null;

        jdbc.update(
            "INSERT INTO bazar_sales (id, tenant_id, event_id, transfer_piece_id, piece_id, variant_id, price, " +
            "  price_source, sale_number, sold_by, business_day, client_request_id, exchange_of_sale_id, " +
            "  customer_name, customer_phone, receipt_id, line_no, discount) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, (now() AT TIME ZONE 'Africa/Cairo')::date, ?, ?, ?, ?, ?, ?, ?)",
            saleId, tenantId, eventId, p.transferPieceId(), p.pieceId(), p.variantId(), p.price(), p.priceSource(),
            number, actor.userId(), receiptId == null ? crid : null, exchangeOf, customerName, customerPhone,
            receiptId, lineNo, discount);
        return saleId;
    }

    /** The next B-number: one per-tenant counter for quick sales AND cart receipts (B6c). */
    private int nextNumber(UUID tenantId) {
        return jdbc.queryForObject(
            "INSERT INTO bazar_sale_counters (tenant_id, last_number) VALUES (?, 1) " +
            "ON CONFLICT (tenant_id) DO UPDATE SET last_number = bazar_sale_counters.last_number + 1 " +
            "RETURNING last_number", Integer.class, tenantId);
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
        return returnSale(eventId, code, null, method, condition, clientRequestId, actor);
    }

    /**
     * B7c: {@code saleId} instead of a code (no label — the seller found the sale by product + size,
     * {@link #findSales}): the sale's piece, then the very same path as a scanned return. Exactly one of code / saleId.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> returnSale(UUID eventId, String code, UUID saleId, String method, String condition,
                                          String clientRequestId, Actor actor) {
        UUID tenantId = requireEnabled();
        Map<String, Object> ev = liveEvent(tenantId, eventId);
        if (!sells(ev, actor)) throw notASeller();
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

        Map<String, Object> sale = soldHere(tenantId, eventId, returningPiece(tenantId, eventId, code, saleId));
        saleId = (UUID) sale.get("id");
        String refundMethod = method == null || method.isBlank() ? paidMethod(tenantId, ev, saleId) : resolveMethod(ev, method);

        takeBack(tenantId, eventId, ev, sale, cond, crid, actor);
        // B6c: a cart line refunds what it was paid — its net price (a quick sale's net price is its price).
        insertMoney(tenantId, eventId, saleId, refundMethod, (BigDecimal) sale.get("net_price"), "refund", actor.userId());

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
        return exchange(eventId, oldCode, null, newCode, null, method, condition, clientRequestId, actor);
    }

    /**
     * B7c: the piece coming back by {@code oldSaleId} (found by product + size) and / or the new piece as a tapped
     * {@code newVariantId} (B7a {@link #pickPiece} — never the piece coming back). Everything else is the B3 exchange.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> exchange(UUID eventId, String oldCode, UUID oldSaleId, String newCode, UUID newVariantId,
                                        String method, String condition, String clientRequestId, Actor actor) {
        UUID tenantId = requireEnabled();
        Map<String, Object> ev = liveEvent(tenantId, eventId);
        if (!sells(ev, actor)) throw notASeller();
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
        Map<String, Object> oldSale = soldHere(tenantId, eventId, returningPiece(tenantId, eventId, oldCode, oldSaleId));
        boolean tappedNew = (newCode == null || newCode.isBlank()) && newVariantId != null;
        Map<String, Object> newPiece = tappedNew
            ? pickPiece(tenantId, ev, newVariantId, Set.of((String) oldSale.get("piece_id")))
            : findPiece(tenantId, newCode);
        if (tappedNew) newCode = pieceCode(newPiece);
        if (newPiece.get("id").equals(oldSale.get("piece_id"))) {
            throw invalid("Scan a different piece for the exchange — this is the one coming back.",
                "امسح قطعة مختلفة للاستبدال — هذه هي القطعة المرتجعة.");
        }
        Prepared next = prepareSale(tenantId, eventId, ev, newPiece, newCode);
        oldSaleId = (UUID) oldSale.get("id");
        // B6c: the old piece is credited at what it was paid (a cart line's net price).
        BigDecimal diff = next.price().subtract((BigDecimal) oldSale.get("net_price"));
        String moneyMethod = diff.signum() == 0 ? null
            : method == null || method.isBlank() ? paidMethod(tenantId, ev, oldSaleId) : resolveMethod(ev, method);

        takeBack(tenantId, eventId, ev, oldSale, cond, crid, actor);
        UUID newSaleId = writeSale(tenantId, eventId, ev, next, crid, actor, oldSaleId,
            (String) oldSale.get("customer_name"), (String) oldSale.get("customer_phone"));
        if (diff.signum() > 0) insertMoney(tenantId, eventId, newSaleId, moneyMethod, diff, "payment", actor.userId());
        if (diff.signum() < 0) insertMoney(tenantId, eventId, newSaleId, moneyMethod, diff.negate(), "refund", actor.userId());

        Map<String, Object> out = exchangeView(tenantId, newSaleId, actor);
        out.put("replayed", false);
        return out;
    }

    /**
     * B7c: the piece of a return / exchange — a scanned code, or the piece of {@code saleId} when it is a sale of THIS
     * event (else BAZAR_NOT_SOLD_HERE). soldHere then decides exactly as for a scan.
     */
    private String returningPiece(UUID tenantId, UUID eventId, String code, UUID saleId) {
        if ((code == null || code.isBlank()) && saleId != null) {
            List<String> piece = jdbc.queryForList(
                "SELECT piece_id FROM bazar_sales WHERE id = ? AND event_id = ? AND tenant_id = ?",
                String.class, saleId, eventId, tenantId);
            if (piece.isEmpty()) throw notSoldHere();
            return piece.get(0);
        }
        if (code == null || code.isBlank()) {
            throw invalid("Scan the piece coming back or find its sale.", "امسح القطعة المرتجعة أو ابحث عن عملية بيعها.");
        }
        return (String) findPiece(tenantId, code).get("id");
    }

    static final int FIND_LIMIT = 10;

    /**
     * B7c — a return / exchange without a label: the ACTIVE sales of {@code variantId} at this event, newest first, at
     * most {@value #FIND_LIMIT}; {@code phone} (normalized like every customer phone) and {@code receipt} ("B-0057" or
     * 57 — a quick sale's number or a cart's receipt number) narrow it. {@code count} = all matches (more than shown →
     * the screen asks for a phone or receipt). Never picks one: one match is shown to confirm, several are listed.
     * Who: the same as a return (role in seller_roles, not removed). A worker sees the customer's phone masked.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> findSales(UUID eventId, UUID variantId, String phone, String receipt, Actor actor) {
        UUID tenantId = requireEnabled();
        Map<String, Object> ev = event(tenantId, eventId, "");
        if (!sells(ev, actor)) throw notASeller();
        if (variantId == null) throw invalid("Pick the product and size.", "اختر المنتج والمقاس.");
        StringBuilder where = new StringBuilder(
            "WHERE s.tenant_id = ? AND s.event_id = ? AND s.variant_id = ? AND s.status = 'active'");
        List<Object> args = new ArrayList<>(List.of(tenantId, eventId, variantId));
        if (phone != null && !phone.isBlank()) {
            String p = normalizePhone(phone);
            if (p == null) {
                throw new BazarException(BazarException.Code.BAZAR_INVALID_PHONE,
                    "Enter an Egyptian mobile number, like 01012345678.",
                    "أدخل رقم موبايل مصري، مثل 01012345678.", HttpStatus.BAD_REQUEST);
            }
            where.append(" AND s.customer_phone = ?");
            args.add(p);
        }
        if (receipt != null && !receipt.isBlank()) {
            Integer n = receiptNumber(receipt);
            if (n == null) throw invalid("Enter the receipt number, like B-0057.", "أدخل رقم الإيصال، مثل B-0057.");
            where.append(" AND COALESCE(s.sale_number, rc.receipt_number) = ?");
            args.add(n);
        }
        Integer count = jdbc.queryForObject(
            "SELECT count(*)::int FROM bazar_sales s " +
            "LEFT JOIN bazar_receipts rc ON rc.id = s.receipt_id AND rc.tenant_id = s.tenant_id " + where,
            Integer.class, args.toArray());
        List<Map<String, Object>> rows = jdbc.queryForList(
            SALE_SELECT + where + " ORDER BY s.sold_at DESC, s.id DESC LIMIT " + FIND_LIMIT, args.toArray());
        List<Map<String, Object>> sales = new ArrayList<>();
        for (Map<String, Object> r : rows) sales.add(present(r, actor));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("variant_id", variantId);
        out.put("count", count == null ? 0 : count);
        out.put("sales", sales);
        return out;
    }

    /** "B-0057", "b57", "57", "٥٧" → 57; anything else null. */
    static Integer receiptNumber(String raw) {
        String s = ScanInputNormalizer.foldDigits(raw.trim()).replaceAll("\\s+", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)^(?:B-?)?0*([0-9]{1,9})$").matcher(s);
        return m.matches() ? Integer.valueOf(m.group(1)) : null;
    }

    private static BazarException notSoldHere() {
        return new BazarException(BazarException.Code.BAZAR_NOT_SOLD_HERE,
            "This piece wasn't sold at this bazar, so it can't be returned or exchanged here.",
            "هذه القطعة لم تُبع في هذا البازار، لذلك لا يمكن إرجاعها أو استبدالها هنا.", HttpStatus.CONFLICT);
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
        if (!actor.manages() && !sells(ev, actor)) throw notASeller();
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
        // B4: came back damaged at this bazar → can't be sold or given in an exchange (BAZAR_PIECE_DAMAGED).
        out.put("damaged", "damaged".equals(piece.get("status")) && !jdbc.queryForList(
            "SELECT 1 FROM transfer_pieces WHERE transfer_id = ? AND piece_id = ? AND tenant_id = ? AND outcome = 'condemned'",
            ev.get("transfer_id"), pieceId, tenantId).isEmpty());
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
            // U1a: the Lookup "Condition" follows the status, as on every other path into damaged.
            jdbc.update("UPDATE pieces SET condition = 'damaged' WHERE id = ? AND tenant_id = ?", pieceId, tenantId);
        }
        queueKeep(tenantId, ev, List.of(pieceId), BazarKeepModeQueue.RETURN);
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
            "SELECT id, status, piece_id, transfer_piece_id, sold_by, price, net_price, receipt_id, customer_name, " +
            "       customer_phone " +
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
                "SELECT p.method FROM bazar_payments p WHERE p.tenant_id = ? AND p.kind = 'payment' " +
                "AND (p.sale_id = ? OR p.receipt_id = (SELECT receipt_id FROM bazar_sales WHERE id = ? AND tenant_id = ?)) " +
                "AND NOT EXISTS (SELECT 1 FROM bazar_payments v WHERE v.voids_payment_id = p.id) " +
                "ORDER BY p.created_at DESC, p.id DESC LIMIT 1", String.class, tenantId, current, current, tenantId);
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
            "SELECT COALESCE(s.sale_number, rc.receipt_number) AS sale_number, s.returned_at, " +
            "       u.name AS returned_by_name FROM bazar_sales s " +
            "LEFT JOIN bazar_receipts rc ON rc.id = s.receipt_id AND rc.tenant_id = s.tenant_id " +
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
        out.put("difference", ((BigDecimal) next.get("price")).subtract((BigDecimal) old.get("net_price")).setScale(2));
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
        if (sale.get("receipt_id") != null) throw cartLine(tenantId, (UUID) sale.get("receipt_id"), true);
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
        queueKeep(tenantId, ev, List.of((String) sale.get("piece_id")), BazarKeepModeQueue.UNDO);

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
        if (sale.get("receipt_id") != null) throw cartLine(tenantId, (UUID) sale.get("receipt_id"), false);
        // The seller may fix the method of their own sale until the sale's business day is closed (B4, approved
        // 2026-11-18; B2 allowed it while the event was live). Owner / manager: any time while live — after the day
        // was closed their change writes money rows after the close, so the day reads "changed after close".
        boolean mine = actor.userId().equals(sale.get("sold_by")) && sells(ev, actor);
        if (!actor.manages() && !mine) {
            throw new BazarException(BazarException.Code.BAZAR_UNDO_NOT_ALLOWED,
                "Only the seller who made this sale, or an owner or manager, can change how it was paid.",
                "يمكن فقط للبائع الذي أجرى عملية البيع، أو المالك أو المدير، تغيير طريقة الدفع.",
                HttpStatus.FORBIDDEN);
        }
        if (!actor.manages() && dayClosed(tenantId, eventId, sale.get("business_day"))) {
            throw new BazarException(BazarException.Code.BAZAR_DAY_CLOSED,
                "This day is already closed — ask an owner or manager to change how it was paid.",
                "تم إغلاق هذا اليوم بالفعل — اطلب من المالك أو المدير تغيير طريقة الدفع.", HttpStatus.FORBIDDEN);
        }
        if (method == null || method.isBlank()) throw methodRequired();
        String chosen = resolveMethod(ev, method);

        // Every live money row of the sale is re-written with the new method, same kind and amount: a plain sale has
        // one payment row (its price); an exchange's new sale has its difference row, or none for an even swap (B3).
        List<Map<String, Object>> live = jdbc.queryForList(
            "SELECT method, kind, amount, business_day FROM bazar_payments p WHERE p.sale_id = ? AND p.tenant_id = ? " +
            "AND p.kind IN ('payment', 'refund') " +
            "AND NOT EXISTS (SELECT 1 FROM bazar_payments v WHERE v.voids_payment_id = p.id) ORDER BY p.created_at, p.id",
            saleId, tenantId);
        if (live.stream().allMatch(r -> chosen.equals(r.get("method")))) return saleView(tenantId, saleId, actor);

        voidLivePayments(tenantId, saleId, actor.userId());
        // B4 review: a correction stays on the sale's day — the replacement counts on the business day of the row it
        // replaces (its void inherits it too), so no money moves on the day of the edit.
        for (Map<String, Object> r : live) {
            insertMoney(tenantId, eventId, saleId, null, chosen, (BigDecimal) r.get("amount"), (String) r.get("kind"),
                actor.userId(), String.valueOf(r.get("business_day")));
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
        boolean mine = actor.userId().equals(sale.get("sold_by")) && sells(ev, actor);
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
            // B6c: a cart has one customer — editing it on any of its lines edits every line of the receipt.
            "WHERE tenant_id = ? AND (id = ? OR receipt_id = ?)", nameSent, n, phoneSent, p, tenantId, saleId,
            sale.get("receipt_id"));
        return saleView(tenantId, saleId, actor);
    }

    // ── Cart (B6c) ───────────────────────────────────────────────────────────

    /** At most this many pieces in one cart. */
    static final int MAX_CART_LINES = 50;

    /**
     * Read-only: each scanned code as a cart line — the piece, its size and bazar price, or why it can't be sold
     * (the same checks as Complete: here, priced, not sold, not damaged, not scanned twice). Nothing is written or
     * reserved. A seller role, while the event is live.
     */
    /** B6c shape: every line a scanned code. (Own @Transactional — a self-call never goes through the proxy.) */
    @Transactional(readOnly = true)
    public Map<String, Object> previewCart(UUID eventId, List<String> codes, Actor actor) {
        return previewCartLines(eventId, codeLines(codes), actor);
    }

    /** B7a: a cart line — a scanned {@code code}, or a tapped {@code variantId} (the piece is picked at Complete). */
    public record CartLine(String code, UUID variantId) {
        boolean tapped() { return (code == null || code.isBlank()) && variantId != null; }
    }

    static List<CartLine> codeLines(List<String> codes) {
        if (codes == null) return null;
        List<CartLine> out = new ArrayList<>();
        for (String c : codes) out.add(new CartLine(c, null));
        return out;
    }

    /**
     * Read-only: each line as it would be sold — a scanned piece (the same checks as Complete: here, priced, not
     * sold, not damaged, not scanned twice), or a tapped variant (priced, and not more of it in the cart than are
     * left at the bazar — no piece is chosen or reserved here). Nothing is written. A seller role, while live.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> previewCartLines(UUID eventId, List<CartLine> input, Actor actor) {
        UUID tenantId = requireEnabled();
        Map<String, Object> ev = requireLive(event(tenantId, eventId, ""));
        if (!sells(ev, actor)) throw notASeller();
        List<CartLine> list = cartLines(input, true);
        List<Map<String, Object>> lines = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        Map<UUID, Integer> scannedPerVariant = new java.util.HashMap<>();
        Map<UUID, Integer> tappedPerVariant = new java.util.HashMap<>();
        // Scanned pieces are specific open pieces: they use up what a tapped line of the same variant can have.
        for (CartLine l : list) {
            if (l.tapped()) continue;
            try {
                Map<String, Object> piece = findPiece(tenantId, l.code());
                scannedPerVariant.merge((UUID) piece.get("variant_id"), 1, Integer::sum);
            } catch (BazarException ignored) { /* reported on its own line below */ }
        }
        BigDecimal subtotal = BigDecimal.ZERO.setScale(2);
        boolean ok = true;
        for (int i = 0; i < list.size(); i++) {
            CartLine l = list.get(i);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("index", i);
            line.put("code", l.tapped() ? null : l.code());
            try {
                if (l.tapped()) {
                    line.putAll(variantInfo(tenantId, l.variantId()));
                    BigDecimal price = eventPrice(tenantId, eventId, l.variantId());
                    int taken = tappedPerVariant.merge(l.variantId(), 1, Integer::sum)
                        + scannedPerVariant.getOrDefault(l.variantId(), 0);
                    if (taken > openLeft(tenantId, ev, l.variantId())) throw soldOut(tenantId, ev, l.variantId());
                    line.put("price", price.setScale(2));
                    line.put("error", null);
                    subtotal = subtotal.add(price);
                } else {
                    Map<String, Object> piece = findPiece(tenantId, l.code());
                    if (!seen.add((String) piece.get("id"))) throw duplicateInCart();
                    Prepared prep = prepareSale(tenantId, eventId, ev, piece, l.code());
                    line.putAll(pieceInfo(tenantId, prep.pieceId()));
                    line.put("price", prep.price().setScale(2));
                    line.put("error", null);
                    subtotal = subtotal.add(prep.price());
                }
            } catch (BazarException e) {
                ok = false;
                line.put("error", errorBody(e));
            }
            lines.add(line);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lines", lines);
        out.put("subtotal", subtotal.setScale(2));
        out.put("ok", ok && !lines.isEmpty());
        return out;
    }

    /** A tapped line's product / size (no piece yet). */
    private Map<String, Object> variantInfo(UUID tenantId, UUID variantId) {
        List<Map<String, Object>> r = jdbc.queryForList(
            "SELECT v.id, pr.title AS product_title, v.title AS variant_title FROM variants v " +
            "JOIN products pr ON pr.id = v.product_id AND pr.tenant_id = v.tenant_id WHERE v.id = ? AND v.tenant_id = ?",
            variantId, tenantId);
        if (r.isEmpty()) throw notHere();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("piece_id", null);
        out.put("piece_code", null);
        out.put("variant_id", variantId);
        out.put("product_title", r.get(0).get("product_title"));
        out.put("variant_title", r.get(0).get("variant_title"));
        return out;
    }

    /** The variant's bazar price at this event, else BAZAR_NO_PRICE (never 0 by default). */
    private BigDecimal eventPrice(UUID tenantId, UUID eventId, UUID variantId) {
        List<BigDecimal> p = jdbc.queryForList(
            "SELECT price FROM bazar_event_prices WHERE event_id = ? AND variant_id = ? AND tenant_id = ?",
            BigDecimal.class, eventId, variantId, tenantId);
        if (p.isEmpty()) {
            throw new BazarException(BazarException.Code.BAZAR_NO_PRICE,
                "Set a price first — this product has no bazar price yet.",
                "حدّد السعر أولًا — هذا المنتج ليس له سعر في البازار بعد.", HttpStatus.CONFLICT);
        }
        return p.get(0);
    }

    /**
     * Complete a cart: ONE transaction — every piece sold through the same writes as a quick sale (ledger edge
     * out_on_transfer → sold, transfer piece 'sold', the line's qty_sold), one receipt with a new B-number, the
     * discount spread over the lines ({@link #spreadDiscount}), the customer on every line, and ONE payment row for
     * the total on the receipt. Any piece refused → nothing is written, and the error names the piece (details
     * {@code line_index} / {@code line_code}). Same {@code client_request_id} twice → the first receipt.
     */
    // The B6c shape. Its own @Transactional: the call below is a self-call, which never goes through the proxy.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> completeCart(UUID eventId, List<String> codes, BigDecimal discount, String method,
                                            String customerName, String customerPhone, String clientRequestId,
                                            Actor actor) {
        return completeCartLines(eventId, codeLines(codes), discount, method, customerName, customerPhone,
            clientRequestId, actor);
    }

    /**
     * B7a: lines may be tapped variants — their pieces are picked here, in this one transaction, after every scanned
     * line is resolved ({@link #pickPiece}, never one already in the cart). A size with none left → BAZAR_SOLD_OUT on
     * that line ({@code line_index}); nothing is written.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> completeCartLines(UUID eventId, List<CartLine> input, BigDecimal discount, String method,
                                                 String customerName, String customerPhone, String clientRequestId,
                                                 Actor actor) {
        UUID tenantId = requireEnabled();
        Map<String, Object> ev = liveEvent(tenantId, eventId);
        if (!sells(ev, actor)) throw notASeller();
        String crid = requestId(clientRequestId);

        lockRequest(tenantId, crid);
        List<UUID> existing = jdbc.queryForList(
            "SELECT id FROM bazar_receipts WHERE tenant_id = ? AND client_request_id = ?", UUID.class, tenantId, crid);
        if (!existing.isEmpty()) {
            Map<String, Object> first = receiptView(tenantId, existing.get(0), actor);
            first.put("replayed", true);
            return first;
        }
        if (!jdbc.queryForList("SELECT 1 FROM bazar_sales WHERE tenant_id = ? AND client_request_id = ?",
                tenantId, crid).isEmpty()) {
            throw invalid("This request id was already used for a sale.", "تم استخدام معرّف الطلب هذا لعملية بيع.");
        }

        List<CartLine> list = cartLines(input, false);
        String chosen = resolveMethod(ev, method);
        BigDecimal off = discount == null ? BigDecimal.ZERO : discount;
        if (off.signum() < 0 || off.stripTrailingZeros().scale() > 0) throw invalidDiscount();
        String name = customerName == null || customerName.isBlank() ? null : customerName.trim();
        if (name != null && name.length() > 80) {
            throw invalid("The customer's name can be up to 80 characters.", "اسم العميل حتى 80 حرفًا.");
        }
        String phone = null;
        if (customerPhone != null && !customerPhone.isBlank()) {
            phone = normalizePhone(customerPhone);
            if (phone == null) {
                throw new BazarException(BazarException.Code.BAZAR_INVALID_PHONE,
                    "Enter an Egyptian mobile number, like 01012345678.",
                    "أدخل رقم موبايل مصري، مثل 01012345678.", HttpStatus.BAD_REQUEST);
            }
        }

        // Check every piece before writing anything: the scanned ones first, then a piece for each tapped line —
        // never one the cart already holds.
        Prepared[] prepared = new Prepared[list.size()];
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            CartLine l = list.get(i);
            if (l.tapped()) continue;
            try {
                Map<String, Object> piece = findPiece(tenantId, l.code());
                if (!seen.add((String) piece.get("id"))) throw duplicateInCart();
                prepared[i] = prepareSale(tenantId, eventId, ev, piece, l.code());
            } catch (BazarException e) {
                throw onLine(e, i, l.code());
            }
        }
        for (int i = 0; i < list.size(); i++) {
            CartLine l = list.get(i);
            if (!l.tapped()) continue;
            try {
                Map<String, Object> piece = pickPiece(tenantId, ev, l.variantId(), seen);
                seen.add((String) piece.get("id"));
                prepared[i] = prepareSale(tenantId, eventId, ev, piece, pieceCode(piece));
            } catch (BazarException e) {
                throw onLine(e, i, lineLabel(tenantId, l));
            }
        }
        List<Prepared> lines = java.util.Arrays.asList(prepared);
        BigDecimal subtotal = lines.stream().map(Prepared::price).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
        if (off.compareTo(subtotal) > 0) throw invalidDiscount();
        List<BigDecimal> shares = spreadDiscount(lines.stream().map(Prepared::price).toList(), off);
        BigDecimal total = subtotal.subtract(off).setScale(2);

        UUID receiptId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO bazar_receipts (id, tenant_id, event_id, receipt_number, sold_by, business_day, line_count, " +
            "  subtotal, discount, total, client_request_id) " +
            "VALUES (?, ?, ?, ?, ?, (now() AT TIME ZONE 'Africa/Cairo')::date, ?, ?, ?, ?, ?)",
            receiptId, tenantId, eventId, nextNumber(tenantId), actor.userId(), lines.size(), subtotal,
            off.setScale(2), total, crid);
        for (int i = 0; i < lines.size(); i++) {
            try {
                writePiece(tenantId, eventId, ev, lines.get(i), null, actor, null, name, phone, receiptId, i + 1,
                    shares.get(i));
            } catch (BazarException e) {
                // A concurrent sale took the piece between the check and the write: the whole cart rolls back.
                throw onLine(e, i, lines.get(i).code());
            }
        }
        insertMoney(tenantId, eventId, null, receiptId, chosen, total, "payment", actor.userId(), null);

        Map<String, Object> out = receiptView(tenantId, receiptId, actor);
        out.put("replayed", false);
        return out;
    }

    /**
     * THE cart discount rule: each line's share is proportional to its price, in whole pounds (rounded down); what
     * is left goes on the dearest line (the first of equal ones), and only if that line can't take it all, on the
     * next dearest — a line is never discounted below 0. The shares always add up to {@code discount} exactly.
     * {@code discount} must be ≤ the sum of {@code prices}.
     */
    static List<BigDecimal> spreadDiscount(List<BigDecimal> prices, BigDecimal discount) {
        BigDecimal subtotal = prices.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<BigDecimal> shares = new ArrayList<>();
        BigDecimal given = BigDecimal.ZERO;
        for (BigDecimal p : prices) {
            BigDecimal share = subtotal.signum() == 0 ? BigDecimal.ZERO
                : discount.multiply(p).divide(subtotal, 0, java.math.RoundingMode.FLOOR);
            shares.add(share);
            given = given.add(share);
        }
        BigDecimal left = discount.subtract(given);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < prices.size(); i++) order.add(i);
        order.sort((a, b) -> {
            int c = prices.get(b).compareTo(prices.get(a));
            return c != 0 ? c : Integer.compare(a, b);
        });
        for (int i : order) {
            if (left.signum() <= 0) break;
            BigDecimal room = prices.get(i).subtract(shares.get(i));
            BigDecimal add = left.min(room);
            shares.set(i, shares.get(i).add(add));
            left = left.subtract(add);
        }
        if (left.signum() != 0) throw new IllegalArgumentException("discount larger than the prices");
        List<BigDecimal> out = new ArrayList<>();
        for (BigDecimal b : shares) out.add(b.setScale(2));
        return out;
    }

    /**
     * Undo a whole cart: every line back on the bazar's rail (sold → out_on_transfer, event bazar_sale_undone), each
     * transfer piece reopened, the lines and the receipt 'undone', the receipt's payment voided. Same rule as a quick
     * sale: owner / manager while live, else the seller within {@link #UNDO_WINDOW_SECONDS}. A cart with a returned
     * or exchanged piece can't be undone.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> undoReceipt(UUID eventId, UUID receiptId, Actor actor) {
        UUID tenantId = requireEnabled();
        Map<String, Object> ev = liveEvent(tenantId, eventId);
        Map<String, Object> receipt = lockActiveReceipt(tenantId, eventId, receiptId);
        requireOwnRecentOrManager(ev, receipt, actor);
        List<Map<String, Object>> lines = jdbc.queryForList(
            "SELECT id, status, piece_id, transfer_piece_id FROM bazar_sales WHERE receipt_id = ? AND tenant_id = ? " +
            "ORDER BY line_no FOR UPDATE", receiptId, tenantId);
        if (lines.stream().anyMatch(l -> !"active".equals(l.get("status")))) {
            throw new BazarException(BazarException.Code.BAZAR_UNDO_NOT_ALLOWED,
                "A piece of this cart was already returned or exchanged, so the cart can't be undone — return the " +
                    "other pieces instead.",
                "تم إرجاع أو استبدال قطعة من هذه السلة بالفعل، لذلك لا يمكن إلغاؤها — قم بإرجاع باقي القطع بدلًا من ذلك.",
                HttpStatus.CONFLICT);
        }
        for (Map<String, Object> l : lines) {
            ledger.transition((String) l.get("piece_id"), PieceStatus.SOLD, PieceStatus.OUT_ON_TRANSFER, UNDONE_EVENT,
                actor.userId(), new TransitionContext(null, null, (UUID) ev.get("location_id"), null,
                    "{\"event_id\":\"" + eventId + "\",\"transfer_id\":\"" + ev.get("transfer_id")
                        + "\",\"sale_id\":\"" + l.get("id") + "\",\"receipt_id\":\"" + receiptId + "\"}", null));
            int reopened = jdbc.update(
                "UPDATE transfer_pieces SET outcome = NULL, outcome_verified = NULL, outcome_at = NULL, outcome_by = NULL " +
                "WHERE id = ? AND tenant_id = ? AND outcome = 'sold'", l.get("transfer_piece_id"), tenantId);
            if (reopened != 1) {
                throw new IllegalStateException("bazar cart undo: transfer piece " + l.get("transfer_piece_id") + " not sold");
            }
            jdbc.update(
                "UPDATE transfer_lines SET qty_sold = qty_sold - 1 " +
                "WHERE id = (SELECT line_id FROM transfer_pieces WHERE id = ?) AND tenant_id = ?",
                l.get("transfer_piece_id"), tenantId);
        }
        queueKeep(tenantId, ev, lines.stream().map(l -> (String) l.get("piece_id")).toList(), BazarKeepModeQueue.UNDO);
        jdbc.update("UPDATE bazar_sales SET status = 'undone', undone_at = now(), undone_by = ?, updated_at = now() " +
            "WHERE receipt_id = ? AND tenant_id = ?", actor.userId(), receiptId, tenantId);
        jdbc.update("UPDATE bazar_receipts SET status = 'undone', undone_at = now(), undone_by = ?, updated_at = now() " +
            "WHERE id = ? AND tenant_id = ?", actor.userId(), receiptId, tenantId);
        voidLiveReceiptPayments(tenantId, receiptId, actor.userId());
        return receiptView(tenantId, receiptId, actor);
    }

    /**
     * Change how a cart was paid: its live payment row voided + a new one for the same total and business day (the
     * B4 rule: the correction stays on the cart's day). Same who-may rule as a quick sale's method change.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> changeReceiptPayment(UUID eventId, UUID receiptId, String method, Actor actor) {
        UUID tenantId = requireEnabled();
        Map<String, Object> ev = liveEvent(tenantId, eventId);
        Map<String, Object> receipt = lockActiveReceipt(tenantId, eventId, receiptId);
        boolean mine = actor.userId().equals(receipt.get("sold_by")) && sells(ev, actor);
        if (!actor.manages() && !mine) {
            throw new BazarException(BazarException.Code.BAZAR_UNDO_NOT_ALLOWED,
                "Only the seller who made this sale, or an owner or manager, can change how it was paid.",
                "يمكن فقط للبائع الذي أجرى عملية البيع، أو المالك أو المدير، تغيير طريقة الدفع.",
                HttpStatus.FORBIDDEN);
        }
        if (!actor.manages() && dayClosed(tenantId, eventId, receipt.get("business_day"))) {
            throw new BazarException(BazarException.Code.BAZAR_DAY_CLOSED,
                "This day is already closed — ask an owner or manager to change how it was paid.",
                "تم إغلاق هذا اليوم بالفعل — اطلب من المالك أو المدير تغيير طريقة الدفع.", HttpStatus.FORBIDDEN);
        }
        if (method == null || method.isBlank()) throw methodRequired();
        String chosen = resolveMethod(ev, method);
        List<Map<String, Object>> live = jdbc.queryForList(
            "SELECT method, kind, amount, business_day FROM bazar_payments p WHERE p.receipt_id = ? AND p.tenant_id = ? " +
            "AND p.kind IN ('payment', 'refund') " +
            "AND NOT EXISTS (SELECT 1 FROM bazar_payments v WHERE v.voids_payment_id = p.id) ORDER BY p.created_at, p.id",
            receiptId, tenantId);
        if (live.stream().allMatch(r -> chosen.equals(r.get("method")))) return receiptView(tenantId, receiptId, actor);
        voidLiveReceiptPayments(tenantId, receiptId, actor.userId());
        for (Map<String, Object> r : live) {
            insertMoney(tenantId, eventId, null, receiptId, chosen, (BigDecimal) r.get("amount"), (String) r.get("kind"),
                actor.userId(), String.valueOf(r.get("business_day")));
        }
        jdbc.update("UPDATE bazar_receipts SET updated_at = now() WHERE id = ? AND tenant_id = ?", receiptId, tenantId);
        return receiptView(tenantId, receiptId, actor);
    }

    private Map<String, Object> lockActiveReceipt(UUID tenantId, UUID eventId, UUID receiptId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, status, sold_by, business_day, sold_at > now() - make_interval(secs => ?) AS recent " +
            "FROM bazar_receipts WHERE id = ? AND event_id = ? AND tenant_id = ? FOR UPDATE",
            UNDO_WINDOW_SECONDS, receiptId, eventId, tenantId);
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

    /**
     * A cart as one row: {kind 'receipt', id, sale_number (its B-number), sold_at, business_day, status, subtotal,
     * discount, total, method, seller, customer (phone masked for a worker), line_count, lines [sale views]}.
     */
    private Map<String, Object> receiptView(UUID tenantId, UUID receiptId, Actor actor) {
        Map<String, Object> r = jdbc.queryForMap(
            "SELECT r.id, r.receipt_number, r.sold_at, r.business_day, r.status, r.subtotal, r.discount, r.total, " +
            "       r.line_count, r.sold_by, u.name AS seller_name, r.undone_at, " +
            "       (SELECT bp.method FROM bazar_payments bp WHERE bp.receipt_id = r.id AND bp.tenant_id = r.tenant_id " +
            "          AND bp.kind = 'payment' " +
            "        ORDER BY EXISTS (SELECT 1 FROM bazar_payments v WHERE v.voids_payment_id = bp.id), " +
            "                 bp.created_at DESC, bp.id DESC LIMIT 1) AS method " +
            "FROM bazar_receipts r JOIN users u ON u.id = r.sold_by WHERE r.id = ? AND r.tenant_id = ?",
            receiptId, tenantId);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> l : jdbc.queryForList(
                SALE_SELECT + "WHERE s.receipt_id = ? AND s.tenant_id = ? ORDER BY s.line_no", receiptId, tenantId)) {
            lines.add(present(l, actor));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", "receipt");
        out.put("id", r.get("id"));
        out.put("sale_number", saleNumber((Integer) r.get("receipt_number")));
        out.put("sold_at", r.get("sold_at"));
        out.put("business_day", String.valueOf(r.get("business_day")));
        out.put("status", r.get("status"));
        out.put("subtotal", ((BigDecimal) r.get("subtotal")).setScale(2));
        out.put("discount", ((BigDecimal) r.get("discount")).setScale(2));
        out.put("total", ((BigDecimal) r.get("total")).setScale(2));
        out.put("price", ((BigDecimal) r.get("total")).setScale(2));
        out.put("method", r.get("method"));
        out.put("seller_id", r.get("sold_by"));
        out.put("seller_name", r.get("seller_name"));
        Map<String, Object> first = lines.isEmpty() ? Map.of() : lines.get(0);
        out.put("customer_name", first.get("customer_name"));
        out.put("customer_phone", first.get("customer_phone"));
        out.put("undone_at", r.get("undone_at"));
        out.put("line_count", ((Number) r.get("line_count")).intValue());
        out.put("lines", lines);
        return out;
    }

    private static List<CartLine> cartLines(List<CartLine> input, boolean emptyOk) {
        List<CartLine> list = new ArrayList<>();
        if (input != null) {
            for (CartLine l : input) {
                if (l == null) { list.add(new CartLine("", null)); continue; }
                list.add(new CartLine(l.code() == null ? null : l.code().trim(), l.variantId()));
            }
        }
        if (list.isEmpty() && !emptyOk) {
            throw invalid("Scan at least one piece into the cart.", "امسح قطعة واحدة على الأقل في السلة.");
        }
        if (list.size() > MAX_CART_LINES) {
            throw invalid("A cart can hold up to " + MAX_CART_LINES + " pieces.",
                "يمكن أن تحتوي السلة على " + MAX_CART_LINES + " قطعة كحد أقصى.");
        }
        return list.stream().map(l -> l.tapped() || l.code() != null ? l : new CartLine("", null)).toList();
    }

    /** How a tapped line is named in an error (no code was scanned): its product · size. */
    private String lineLabel(UUID tenantId, CartLine l) {
        try {
            Map<String, Object> v = variantInfo(tenantId, l.variantId());
            return v.get("product_title") + (v.get("variant_title") == null ? "" : " · " + v.get("variant_title"));
        } catch (BazarException e) {
            return String.valueOf(l.variantId());
        }
    }

    /** product / size of a piece, for a cart line. */
    private Map<String, Object> pieceInfo(UUID tenantId, String pieceId) {
        Map<String, Object> r = jdbc.queryForMap(
            "SELECT pc.id, pc.short_code, pc.variant_id, pr.title AS product_title, v.title AS variant_title " +
            "FROM pieces pc JOIN variants v ON v.id = pc.variant_id JOIN products pr ON pr.id = v.product_id " +
            "WHERE pc.id = ? AND pc.tenant_id = ?", pieceId, tenantId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("piece_id", r.get("id"));
        out.put("piece_code", r.get("short_code"));
        out.put("variant_id", r.get("variant_id"));
        out.put("product_title", r.get("product_title"));
        out.put("variant_title", r.get("variant_title"));
        return out;
    }

    /** The {code, message_en, message_ar, details?} body a refused piece would have on its own. */
    private static Map<String, Object> errorBody(BazarException e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", e.code().name());
        m.put("message_en", e.messageEn());
        m.put("message_ar", e.messageAr());
        if (e.details() != null) m.put("details", e.details());
        return m;
    }

    /** The piece's own error, naming the cart line: details gain {@code line_index} and {@code line_code}. */
    private static BazarException onLine(BazarException e, int index, String code) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (e.details() != null) d.putAll(e.details());
        d.put("line_index", index);
        d.put("line_code", code);
        return new BazarException(e.code(), code + ": " + e.messageEn(), code + ": " + e.messageAr(), e.httpStatus(), d);
    }

    private static BazarException duplicateInCart() {
        return new BazarException(BazarException.Code.BAZAR_CART_DUPLICATE_PIECE,
            "This piece is already in the cart.", "هذه القطعة موجودة في السلة بالفعل.", HttpStatus.CONFLICT);
    }

    private static BazarException invalidDiscount() {
        return new BazarException(BazarException.Code.BAZAR_INVALID_DISCOUNT,
            "The discount must be whole pounds, from 0 up to the cart's total.",
            "يجب أن يكون الخصم بالجنيه الكامل، من 0 حتى إجمالي السلة.", HttpStatus.BAD_REQUEST);
    }

    /** A cart line is changed through its cart: undo / payment on the receipt (B6c). */
    private BazarException cartLine(UUID tenantId, UUID receiptId, boolean undo) {
        String number = saleNumber(jdbc.queryForObject(
            "SELECT receipt_number FROM bazar_receipts WHERE id = ? AND tenant_id = ?", Integer.class, receiptId, tenantId));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("receipt_id", receiptId);
        d.put("sale_number", number);
        return undo
            ? new BazarException(BazarException.Code.BAZAR_UNDO_NOT_ALLOWED,
                "This piece was sold in a cart (" + number + ") — undo the whole cart, or return the piece.",
                "تم بيع هذه القطعة ضمن سلة (" + number + ") — ألغِ السلة كاملة، أو قم بإرجاع القطعة.",
                HttpStatus.CONFLICT, d)
            : new BazarException(BazarException.Code.BAZAR_UNDO_NOT_ALLOWED,
                "This piece was sold in a cart (" + number + ") — change how the cart was paid.",
                "تم بيع هذه القطعة ضمن سلة (" + number + ") — غيّر طريقة دفع السلة.", HttpStatus.CONFLICT, d);
    }

    // ── List ─────────────────────────────────────────────────────────────────

    /** Newest first (created_at DESC, id DESC); {@code cursor} is the previous page's {@code next_cursor}. */
    @Transactional(readOnly = true)
    public Map<String, Object> list(UUID eventId, Integer limit, String cursor, Actor actor) {
        UUID tenantId = requireEnabled();
        Map<String, Object> ev = event(tenantId, eventId, "");
        if (!actor.manages() && !sells(ev, actor)) throw notASeller();
        int n = limit == null ? 50 : limit;
        if (n < 1 || n > 200) throw invalid("limit must be 1–200.", "يجب أن يكون الحد بين 1 و200.");

        // B6c: a quick sale is a row; a cart is ONE row (kind 'receipt') with its lines inside.
        StringBuilder sql = new StringBuilder(
            "SELECT k.kind, k.id, k.created_at FROM (" +
            "  SELECT 'sale' AS kind, s.id, s.created_at FROM bazar_sales s " +
            "    WHERE s.event_id = ? AND s.tenant_id = ? AND s.receipt_id IS NULL " +
            "  UNION ALL " +
            "  SELECT 'receipt' AS kind, r.id, r.created_at FROM bazar_receipts r " +
            "    WHERE r.event_id = ? AND r.tenant_id = ?) k ");
        List<Object> args = new ArrayList<>(List.of(eventId, tenantId, eventId, tenantId));
        if (cursor != null && !cursor.isBlank()) {
            Object[] c = decodeCursor(cursor);
            sql.append("WHERE (k.created_at, k.id) < (?, ?) ");
            args.add(c[0]);
            args.add(c[1]);
        }
        sql.append("ORDER BY k.created_at DESC, k.id DESC LIMIT ?");
        args.add(n + 1);
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());

        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < Math.min(n, rows.size()); i++) {
            Map<String, Object> k = rows.get(i);
            items.add("receipt".equals(k.get("kind"))
                ? receiptView(tenantId, (UUID) k.get("id"), actor)
                : saleView(tenantId, (UUID) k.get("id"), actor));
        }
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
        if (!actor.manages() && !sells(ev, actor)) throw notASeller();
        Map<String, Object> t = today(jdbc, tenantId, eventId, toList(ev.get("payment_methods")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("business_day", t.get("business_day"));
        out.put("total", t.get("total"));
        out.put("sale_count", t.get("sales"));
        out.put("pieces", t.get("pieces"));
        out.put("discounts", t.get("discounts"));
        out.put("by_method", t.get("by_method"));
        out.put("returns", t.get("returns"));
        out.put("exchanges", t.get("exchanges"));
        out.put("revenue", t.get("revenue"));
        List<Map<String, Object>> perSeller = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT s.sold_by, u.name AS seller_name, COUNT(DISTINCT COALESCE(s.receipt_id, s.id)) AS sale_count, " +
                "       COUNT(*) AS pieces, SUM(s.net_price) AS total " +
                "FROM bazar_sales s JOIN users u ON u.id = s.sold_by " +
                "WHERE s.event_id = ? AND s.tenant_id = ? AND s.status = 'active' " +
                "  AND s.business_day = (now() AT TIME ZONE 'Africa/Cairo')::date " +
                "GROUP BY s.sold_by, u.name ORDER BY COUNT(*) DESC, u.name, s.sold_by", eventId, tenantId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seller_id", String.valueOf(r.get("sold_by")));
            row.put("seller_name", r.get("seller_name"));
            row.put("sale_count", ((Number) r.get("sale_count")).intValue());
            row.put("pieces", ((Number) r.get("pieces")).intValue());
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
        if (!todayOnly) return summary(jdbc, tenantId, eventId, methods, (java.time.LocalDate) null);
        java.time.LocalDate today = cairoToday(jdbc);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("business_day", today.toString());
        out.putAll(summary(jdbc, tenantId, eventId, methods, today));
        return out;
    }

    /** Today's business day: the Africa/Cairo date, read from the DB clock (the same clock every row is stamped with). */
    static java.time.LocalDate cairoToday(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT (now() AT TIME ZONE 'Africa/Cairo')::date", java.time.LocalDate.class);
    }

    /**
     * {@link #summary} for ONE business day ({@code day}), or the whole event ({@code day} null). B4's day close and
     * the B5 report read a day's money through this, so close and /today can never disagree.
     */
    static Map<String, Object> summary(JdbcTemplate jdbc, UUID tenantId, UUID eventId, List<String> methods,
                                       java.time.LocalDate day) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, BigDecimal> byMethod = new LinkedHashMap<>();
        for (String m : methods) byMethod.put(m, BigDecimal.ZERO.setScale(2));
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Map.Entry<String, BigDecimal> e : netByMethod(jdbc, tenantId, eventId, day).entrySet()) {
            byMethod.put(e.getKey(), e.getValue());
            total = total.add(e.getValue());
        }
        out.put("by_method", byMethod);
        out.put("total", total);

        String soldDay = day != null ? " AND s.business_day = ?::date" : "";
        String returnedDay = day != null ? " AND (s.returned_at AT TIME ZONE 'Africa/Cairo')::date = ?::date" : "";
        List<Object> args = new ArrayList<>();
        Object d = day == null ? null : day.toString();
        // sales, pieces, returns, exchanges, revenue (sold − returned), discounts: each sub-select takes event, tenant
        // (+ day).
        for (String sub : List.of(soldDay, soldDay, returnedDay, soldDay, soldDay, returnedDay, soldDay)) {
            args.add(eventId);
            args.add(tenantId);
            if (!sub.isEmpty()) args.add(d);
        }
        Map<String, Object> c = jdbc.queryForMap(
            "SELECT " +
            // B6c: a sale = a receipt (a quick sale is its own); pieces = lines.
            "  (SELECT COUNT(DISTINCT COALESCE(s.receipt_id, s.id)) FROM bazar_sales s WHERE s.event_id = ? " +
                "AND s.tenant_id = ? AND s.status = 'active'" + soldDay + ") AS sales, " +
            "  (SELECT COUNT(*) FROM bazar_sales s WHERE s.event_id = ? AND s.tenant_id = ? AND s.status = 'active'" +
                soldDay + ") AS pieces, " +
            "  (SELECT COUNT(*) FROM bazar_sales s WHERE s.event_id = ? AND s.tenant_id = ? AND s.status = 'returned'" +
                returnedDay + " AND NOT EXISTS (SELECT 1 FROM bazar_sales x WHERE x.exchange_of_sale_id = s.id)) AS returns, " +
            "  (SELECT COUNT(*) FROM bazar_sales s WHERE s.event_id = ? AND s.tenant_id = ? " +
                "AND s.exchange_of_sale_id IS NOT NULL AND s.status <> 'undone'" + soldDay + ") AS exchanges, " +
            "  (SELECT COALESCE(SUM(s.net_price), 0) FROM bazar_sales s WHERE s.event_id = ? AND s.tenant_id = ? " +
                "AND s.status <> 'undone'" + soldDay + ") " +
            "  - (SELECT COALESCE(SUM(s.net_price), 0) FROM bazar_sales s WHERE s.event_id = ? AND s.tenant_id = ? " +
                "AND s.status = 'returned'" + returnedDay + ") AS revenue, " +
            // Discounts given: the cart discount on the pieces sold that day that are still sold.
            "  (SELECT COALESCE(SUM(s.discount), 0) FROM bazar_sales s WHERE s.event_id = ? AND s.tenant_id = ? " +
                "AND s.status = 'active'" + soldDay + ") AS discounts",
            args.toArray());
        out.put("sales", ((Number) c.get("sales")).intValue());
        out.put("pieces", ((Number) c.get("pieces")).intValue());
        out.put("returns", ((Number) c.get("returns")).intValue());
        out.put("exchanges", ((Number) c.get("exchanges")).intValue());
        out.put("revenue", ((BigDecimal) c.get("revenue")).setScale(2));
        out.put("discounts", ((BigDecimal) c.get("discounts")).setScale(2));
        return out;
    }

    /**
     * THE money rule (B3 / B4): per method, every live (not voided) 'payment' row minus every live 'refund' row of the
     * event, counted on the row's {@code business_day} ({@code day} null = every day): the Africa/Cairo day real money
     * was written; a payment-method correction keeps the day of the row it replaces (B4 review). Methods with no row
     * are absent.
     */
    static Map<String, BigDecimal> netByMethod(JdbcTemplate jdbc, UUID tenantId, UUID eventId, java.time.LocalDate day) {
        List<Object> args = new ArrayList<>(List.of(eventId, tenantId));
        if (day != null) args.add(day.toString());
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT p.method, SUM(CASE WHEN p.kind = 'payment' THEN p.amount ELSE -p.amount END) AS total " +
                // B6c: a cart's payment row is on its receipt — every row carries the event (V237).
                "FROM bazar_payments p " +
                "WHERE p.event_id = ? AND p.tenant_id = ? AND p.kind IN ('payment', 'refund') " +
                (day != null ? "  AND p.business_day = ?::date " : "") +
                "  AND NOT EXISTS (SELECT 1 FROM bazar_payments v WHERE v.voids_payment_id = p.id) " +
                "GROUP BY p.method ORDER BY p.method", args.toArray())) {
            out.put((String) r.get("method"), ((BigDecimal) r.get("total")).setScale(2));
        }
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
        "       s.receipt_id, s.line_no, s.discount, s.net_price, rc.receipt_number, " +
        // The method: the live payment row's; an undone sale shows the one it had (its newest payment row). A cart
        // line's payment row is its receipt's (B6c).
        "       (SELECT bp.method FROM bazar_payments bp WHERE bp.tenant_id = s.tenant_id " +
        "          AND (bp.sale_id = s.id OR bp.receipt_id = s.receipt_id) AND bp.kind = 'payment' " +
        "        ORDER BY EXISTS (SELECT 1 FROM bazar_payments v WHERE v.voids_payment_id = bp.id), " +
        "                 bp.created_at DESC, bp.id DESC LIMIT 1) AS method " +
        "FROM bazar_sales s " +
        "JOIN users u ON u.id = s.sold_by " +
        "JOIN pieces pc ON pc.id = s.piece_id " +
        "JOIN variants v ON v.id = s.variant_id " +
        "JOIN products pr ON pr.id = v.product_id " +
        "LEFT JOIN users ru ON ru.id = s.returned_by " +
        "LEFT JOIN bazar_sales xo ON xo.id = s.exchange_of_sale_id AND xo.tenant_id = s.tenant_id " +
        "LEFT JOIN bazar_sales xn ON xn.exchange_of_sale_id = s.id AND xn.tenant_id = s.tenant_id " +
        "LEFT JOIN bazar_receipts rc ON rc.id = s.receipt_id AND rc.tenant_id = s.tenant_id ";

    private Map<String, Object> saleView(UUID tenantId, UUID saleId, Actor actor) {
        return present(jdbc.queryForMap(SALE_SELECT + "WHERE s.id = ? AND s.tenant_id = ?", saleId, tenantId), actor);
    }

    private static Map<String, Object> present(Map<String, Object> r, Actor actor) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.get("id"));
        // A cart line has no number of its own: it shows its receipt's (B6c).
        Integer number = r.get("sale_number") != null ? (Integer) r.get("sale_number") : (Integer) r.get("receipt_number");
        out.put("kind", "sale");
        out.put("sale_number", saleNumber(number));
        out.put("sold_at", r.get("sold_at"));
        out.put("business_day", String.valueOf(r.get("business_day")));
        out.put("status", r.get("status"));
        out.put("price", r.get("price"));
        out.put("price_source", r.get("price_source"));
        out.put("discount", r.get("discount"));
        out.put("net_price", r.get("net_price"));
        out.put("receipt_id", r.get("receipt_id"));
        out.put("line_no", r.get("line_no") == null ? null : ((Number) r.get("line_no")).intValue());
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

    private void insertPayment(UUID tenantId, UUID eventId, UUID saleId, String method, BigDecimal amount, UUID actorId) {
        insertMoney(tenantId, eventId, saleId, method, amount, "payment", actorId);
    }

    /** kind 'payment' (money in) or 'refund' (money out) — real money: counts on today's business day (Cairo). */
    private void insertMoney(UUID tenantId, UUID eventId, UUID saleId, String method, BigDecimal amount, String kind,
                             UUID actorId) {
        insertMoney(tenantId, eventId, saleId, null, method, amount, kind, actorId, null);
    }

    /**
     * One money row, on a sale ({@code saleId}) or on a cart's receipt ({@code receiptId}) — exactly one of the two.
     * {@code businessDay}: the day the row counts on (yyyy-MM-dd); null = today (Africa/Cairo).
     */
    private void insertMoney(UUID tenantId, UUID eventId, UUID saleId, UUID receiptId, String method, BigDecimal amount,
                             String kind, UUID actorId, String businessDay) {
        // clock_timestamp(): rows written in one transaction (void + new) keep their real order.
        jdbc.update("INSERT INTO bazar_payments (tenant_id, event_id, sale_id, receipt_id, method, amount, kind, " +
            "  created_by, created_at, business_day) VALUES (?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), " +
            "  COALESCE(?::date, (now() AT TIME ZONE 'Africa/Cairo')::date))",
            tenantId, eventId, saleId, receiptId, method, amount, kind, actorId, businessDay);
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
        voidLive(tenantId, "sale_id", saleId, actorId);
    }

    /** B6c: one void row per live payment row of a cart's receipt. */
    private void voidLiveReceiptPayments(UUID tenantId, UUID receiptId, UUID actorId) {
        voidLive(tenantId, "receipt_id", receiptId, actorId);
    }

    private void voidLive(UUID tenantId, String ownerColumn, UUID ownerId, UUID actorId) {
        jdbc.update(
            "INSERT INTO bazar_payments (tenant_id, event_id, sale_id, receipt_id, method, amount, kind, " +
            "  voids_payment_id, created_by, created_at, business_day) " +
            // B4 review: a void counts on the day of the row it cancels.
            "SELECT p.tenant_id, p.event_id, p.sale_id, p.receipt_id, p.method, p.amount, 'void', p.id, ?, " +
            "  clock_timestamp(), p.business_day " +
            "FROM bazar_payments p " +
            "WHERE p." + ownerColumn + " = ? AND p.tenant_id = ? AND p.kind IN ('payment', 'refund') " +
            "AND NOT EXISTS (SELECT 1 FROM bazar_payments v WHERE v.voids_payment_id = p.id) " +
            "ORDER BY p.created_at, p.id", actorId, ownerId, tenantId);
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
            "SELECT id, status, piece_id, transfer_piece_id, sold_by, price, exchange_of_sale_id, business_day, " +
            "       receipt_id, sold_at > now() - make_interval(secs => ?) AS recent " +
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

    /** Has the event's business day {@code day} been closed (B4)? Any close counts — a re-close only replaces it. */
    private boolean dayClosed(UUID tenantId, UUID eventId, Object day) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM bazar_day_closes WHERE event_id = ? AND tenant_id = ? AND business_day = ?::date)",
            Boolean.class, eventId, tenantId, String.valueOf(day)));
    }

    private void requireOwnRecentOrManager(Map<String, Object> ev, Map<String, Object> sale, Actor actor) {
        if (actor.manages()) return;
        boolean mine = actor.userId().equals(sale.get("sold_by")) && sells(ev, actor);
        if (!mine || !Boolean.TRUE.equals(sale.get("recent"))) throw undoNotAllowed();
    }

    private BazarException alreadySold(UUID tenantId, String pieceId, UUID eventId) {
        List<Map<String, Object>> s = jdbc.queryForList(
            "SELECT COALESCE(s.sale_number, rc.receipt_number) AS sale_number, s.sold_at, u.name AS seller_name " +
            "FROM bazar_sales s JOIN users u ON u.id = s.sold_by " +
            "LEFT JOIN bazar_receipts rc ON rc.id = s.receipt_id AND rc.tenant_id = s.tenant_id " +
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
        return requireLive(event(tenantId, eventId, " FOR SHARE OF e"));
    }

    /** ENDED / NOT_LIVE unless the event is live. */
    private static Map<String, Object> requireLive(Map<String, Object> ev) {
        if ("ended".equals(ev.get("status"))) {
            // B5: after End event nothing is sold, returned, exchanged or edited any more.
            throw new BazarException(BazarException.Code.BAZAR_EVENT_ENDED,
                "This bazar has ended — selling, returns, exchanges and edits are closed.",
                "انتهى هذا البازار — البيع والإرجاع والاستبدال والتعديل مغلقة.", HttpStatus.CONFLICT);
        }
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

    /**
     * B6b — may this caller sell at this event: their role is in its seller_roles AND they weren't removed from it
     * ("Remove seller", {@link BazarSellers#removed}). Every sell-side check goes through here.
     */
    private boolean sells(Map<String, Object> ev, Actor actor) {
        return sellerRoles(ev).contains(actor.roleKey())
            && !BazarSellers.removed(jdbc, (UUID) ev.get("id"), actor.userId());
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

    private static BazarException pieceDamaged() {
        return new BazarException(BazarException.Code.BAZAR_PIECE_DAMAGED,
            "This piece was returned damaged — it can't be sold.",
            "هذه القطعة أُرجعت تالفة — لا يمكن بيعها.", HttpStatus.CONFLICT);
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
