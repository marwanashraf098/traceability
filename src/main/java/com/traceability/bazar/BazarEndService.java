package com.traceability.bazar;

import com.traceability.inventory.ScanInputNormalizer;
import com.traceability.inventory.TransferService;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bazar POS B5 — end the event and bring the leftovers home on the SAME linked transfer. Owner / manager only.
 * The ONE caller of TransferService's bazar reconcile entry points (beginReconcileForBazar,
 * reconcileScanBackForBazar, bringBackDamagedForBazar, markLostForBazar, closeTransferForBazar) — each runs the
 * existing transfer code path (BazarTransferLockTest keeps the generic actions locked). No Shopify method of its own:
 * a good leftover's +1 is the existing onTransferReturn path; nothing else here writes to Shopify.
 * <ul>
 *   <li><b>End</b>: live → ended, refused while a business day with money is open or changed after its close
 *       (BAZAR_DAYS_NOT_CLOSED names them); the transfer goes sent → reconciling. Ended → the same answer again.</li>
 *   <li><b>Scan back</b>: a piece still out on the transfer → back GOOD, available at main; a piece taken back
 *       damaged at the bazar → brought home, still damaged. Same {@code client_request_id} again → the first
 *       answer (replayed).</li>
 *   <li><b>Mark lost</b>: only pieces still outstanding (sold ones never are — a sale resolves its transfer piece).</li>
 *   <li><b>Close transfer</b>: when sent = sold + back + damaged + lost (nothing outstanding).</li>
 * </ul>
 */
@Service
public class BazarEndService {

    private static final Set<String> MANAGERS = Set.of("owner", "manager");
    static final String BACK_GOOD_EVENT = "returned_from_transfer";
    static final String BACK_DAMAGED_EVENT = "bazar_damaged_brought_back";

    private final JdbcTemplate jdbc;
    private final TransferService transfers;
    private final BazarDayService days;

    public BazarEndService(JdbcTemplate jdbc, TransferService transfers, BazarDayService days) {
        this.jdbc = jdbc;
        this.transfers = transfers;
        this.days = days;
    }

    // ── End ──────────────────────────────────────────────────────────────────

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> end(UUID eventId, BazarSaleService.Actor actor) {
        UUID tenantId = requireManager(actor);
        // FOR UPDATE: a sale holds the event FOR SHARE, so every sale commits before this check or is refused after.
        Map<String, Object> ev = event(tenantId, eventId, " FOR UPDATE OF e");
        if ("ended".equals(ev.get("status"))) {
            Map<String, Object> out = leftovers(tenantId, ev);
            out.put("replayed", true);
            return out;
        }
        if (!"live".equals(ev.get("status"))) {
            throw new BazarException(BazarException.Code.BAZAR_EVENT_NOT_LIVE,
                "Only a live bazar can be ended.", "يمكن إنهاء البازار الجاري فقط.", HttpStatus.CONFLICT);
        }
        List<Map<String, Object>> needing = daysNeedingClose(eventId, actor);
        if (!needing.isEmpty()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("days", needing);
            throw new BazarException(BazarException.Code.BAZAR_DAYS_NOT_CLOSED,
                "Close every day first — " + needing.size() + " day(s) still need closing.",
                "أغلق كل الأيام أولًا — ما زال " + needing.size() + " يوم/أيام بحاجة إلى إغلاق.", HttpStatus.CONFLICT, d);
        }
        jdbc.update("UPDATE bazar_events SET status = 'ended', ended_at = now(), ended_by = ?, updated_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND status = 'live'", actor.userId(), eventId, tenantId);
        transfers.beginReconcileForBazar((UUID) ev.get("transfer_id"), actor.userId());
        ev.put("status", "ended");
        Map<String, Object> out = leftovers(tenantId, ev);
        out.put("replayed", false);
        return out;
    }

    /** Days with any money row that are open or changed after their close — the End event block. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> daysNeedingClose(UUID eventId, BazarSaleService.Actor actor) {
        UUID tenantId = TenantContext.require();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : (List<Map<String, Object>>) days.days(eventId, actor).get("days")) {
            String status = (String) d.get("status");
            if (!"open".equals(status) && !"changed_after_close".equals(status)) continue;
            boolean money = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM bazar_payments p JOIN bazar_sales s ON s.id = p.sale_id " +
                "AND s.tenant_id = p.tenant_id WHERE s.event_id = ? AND s.tenant_id = ? AND p.business_day = ?::date)",
                Boolean.class, eventId, tenantId, d.get("business_day")));
            if (!money) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("business_day", d.get("business_day"));
            row.put("day_number", d.get("day_number"));
            row.put("status", status);
            out.add(row);
        }
        return out;
    }

    // ── Leftovers ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> leftovers(UUID eventId, BazarSaleService.Actor actor) {
        UUID tenantId = requireEnabledTenant(jdbc);
        Map<String, Object> ev = event(tenantId, eventId, "");
        requireSeller(actor, ev);
        return leftovers(tenantId, ev);
    }

    /**
     * {event_status, transfer_status, counts {sent, sold, back, damaged, damaged_at_bazar, lost, outstanding},
     * balanced (sent = sold + back + damaged + lost), outstanding [pieces], damaged_to_bring_home [pieces]}.
     */
    Map<String, Object> leftovers(UUID tenantId, Map<String, Object> ev) {
        UUID transferId = (UUID) ev.get("transfer_id");
        Map<String, Object> c = jdbc.queryForMap(
            "SELECT COUNT(*) AS sent, " +
            "  COUNT(*) FILTER (WHERE tp.outcome = 'sold') AS sold, " +
            "  COUNT(*) FILTER (WHERE tp.outcome = 'returned_good') AS back, " +
            "  COUNT(*) FILTER (WHERE tp.outcome = 'condemned') AS damaged, " +
            "  COUNT(*) FILTER (WHERE tp.outcome = 'condemned' AND p.status = 'damaged' " +
            "                   AND p.current_location_id IS DISTINCT FROM main.id) AS damaged_at_bazar, " +
            "  COUNT(*) FILTER (WHERE tp.outcome = 'lost') AS lost, " +
            "  COUNT(*) FILTER (WHERE tp.outcome IS NULL) AS outstanding " +
            "FROM transfer_pieces tp JOIN pieces p ON p.id = tp.piece_id " +
            "LEFT JOIN locations main ON main.tenant_id = tp.tenant_id AND main.is_fulfillment " +
            "WHERE tp.transfer_id = ? AND tp.tenant_id = ?", transferId, tenantId);
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String k : List.of("sent", "sold", "back", "damaged", "damaged_at_bazar", "lost", "outstanding")) {
            counts.put(k, ((Number) c.get(k)).intValue());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("event_id", ev.get("id"));
        out.put("event_status", ev.get("status"));
        out.put("transfer_id", transferId);
        out.put("transfer_status", jdbc.queryForObject("SELECT status FROM transfers WHERE id = ? AND tenant_id = ?",
            String.class, transferId, tenantId));
        out.put("counts", counts);
        int sent = (Integer) counts.get("sent");
        out.put("balanced", sent == (Integer) counts.get("sold") + (Integer) counts.get("back")
            + (Integer) counts.get("damaged") + (Integer) counts.get("lost"));
        out.put("outstanding", pieceList(tenantId, transferId, "tp.outcome IS NULL"));
        out.put("damaged_to_bring_home", pieceList(tenantId, transferId,
            "tp.outcome = 'condemned' AND p.status = 'damaged' AND p.current_location_id IS DISTINCT FROM " +
            "(SELECT id FROM locations WHERE tenant_id = tp.tenant_id AND is_fulfillment)"));
        return out;
    }

    private List<Map<String, Object>> pieceList(UUID tenantId, UUID transferId, String where) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT p.id, p.short_code, pr.title AS product_title, v.title AS variant_title, v.sku " +
                "FROM transfer_pieces tp JOIN pieces p ON p.id = tp.piece_id JOIN variants v ON v.id = p.variant_id " +
                "JOIN products pr ON pr.id = v.product_id WHERE tp.transfer_id = ? AND tp.tenant_id = ? AND " + where +
                " ORDER BY pr.title, v.title, p.short_code, p.id", transferId, tenantId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("piece_id", r.get("id"));
            m.put("piece_code", r.get("short_code"));
            m.put("product_title", r.get("product_title"));
            m.put("variant_title", BazarReportService.variantTitle((String) r.get("variant_title")));
            m.put("sku", r.get("sku"));
            out.add(m);
        }
        return out;
    }

    // ── Scan back ────────────────────────────────────────────────────────────

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> scanBack(UUID eventId, String code, String clientRequestId, BazarSaleService.Actor actor) {
        UUID tenantId = requireEnabledTenant(jdbc);
        Map<String, Object> ev = endedEvent(tenantId, eventId);
        requireSeller(actor, ev);
        UUID transferId = (UUID) ev.get("transfer_id");
        String crid = clientRequestId == null ? "" : clientRequestId.trim();
        if (crid.isEmpty() || crid.length() > 100) {
            throw invalid("Missing request id (client_request_id).", "معرّف الطلب مفقود (client_request_id).");
        }
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", tenantId + ":bazar-back:" + crid);
        List<Map<String, Object>> first = jdbc.queryForList(
            "SELECT piece_id, event_type FROM piece_events WHERE tenant_id = ? AND metadata->>'client_request_id' = ? " +
            "AND event_type IN ('" + BACK_GOOD_EVENT + "', '" + BACK_DAMAGED_EVENT + "') " +
            "AND metadata->>'transfer_id' = ? LIMIT 1", tenantId, crid, transferId.toString());
        if (!first.isEmpty()) {
            return scanResult(tenantId, ev, (String) first.get(0).get("piece_id"),
                BACK_GOOD_EVENT.equals(first.get(0).get("event_type")) ? "back_good" : "back_damaged", true);
        }

        String lookup = ScanInputNormalizer.normalizePiece(code == null ? "" : code);
        List<Map<String, Object>> pieces = jdbc.queryForList(
            "SELECT id, status::text AS status FROM pieces WHERE (barcode = ? OR id = ? OR short_code = ?) AND tenant_id = ?",
            lookup, lookup, lookup, tenantId);
        if (pieces.isEmpty()) {
            throw new BazarException(BazarException.Code.BAZAR_PIECE_NOT_FOUND,
                "This code isn't a piece in your stock.", "هذا الكود ليس قطعة في مخزونك.", HttpStatus.NOT_FOUND);
        }
        String pieceId = (String) pieces.get(0).get("id");
        List<String> outcome = jdbc.queryForList(
            "SELECT COALESCE(outcome, '') FROM transfer_pieces WHERE transfer_id = ? AND piece_id = ? AND tenant_id = ? " +
            "ORDER BY created_at DESC, id DESC LIMIT 1", String.class, transferId, pieceId, tenantId);
        if (outcome.isEmpty()) {
            throw new BazarException(BazarException.Code.BAZAR_PIECE_NOT_HERE,
                "This piece didn't go to this bazar.", "هذه القطعة لم تذهب إلى هذا البازار.", HttpStatus.CONFLICT);
        }
        switch (outcome.get(0)) {
            case "" -> {
                TransferService.ScanBackResult r = transfers.reconcileScanBackForBazar(transferId, code, actor.userId(), crid);
                if (!r.success()) {
                    throw new BazarException(BazarException.Code.BAZAR_INVALID_INPUT, r.messageEn(), r.messageAr(),
                        HttpStatus.CONFLICT);
                }
                return scanResult(tenantId, ev, pieceId, "back_good", false);
            }
            case "condemned" -> {
                if (transfers.bringBackDamagedForBazar(transferId, pieceId, actor.userId(), crid)) {
                    return scanResult(tenantId, ev, pieceId, "back_damaged", false);
                }
                throw alreadyBack();
            }
            case "returned_good" -> throw alreadyBack();
            case "sold" -> throw new BazarException(BazarException.Code.BAZAR_ALREADY_SOLD,
                "This piece was sold at the bazar — it doesn't come back.",
                "تم بيع هذه القطعة في البازار — لا تعود.", HttpStatus.CONFLICT);
            default -> throw new BazarException(BazarException.Code.BAZAR_PIECE_MARKED_LOST,
                "This piece was marked lost.", "تم تسجيل هذه القطعة كمفقودة.", HttpStatus.CONFLICT);
        }
    }

    private Map<String, Object> scanResult(UUID tenantId, Map<String, Object> ev, String pieceId, String result,
                                           boolean replayed) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", result);
        Map<String, Object> p = jdbc.queryForMap(
            "SELECT p.id, p.short_code, pr.title AS product_title, v.title AS variant_title FROM pieces p " +
            "JOIN variants v ON v.id = p.variant_id JOIN products pr ON pr.id = v.product_id WHERE p.id = ? AND p.tenant_id = ?",
            pieceId, tenantId);
        Map<String, Object> piece = new LinkedHashMap<>();
        piece.put("piece_id", p.get("id"));
        piece.put("piece_code", p.get("short_code"));
        piece.put("product_title", p.get("product_title"));
        piece.put("variant_title", BazarReportService.variantTitle((String) p.get("variant_title")));
        out.put("piece", piece);
        out.put("leftovers", leftovers(tenantId, ev));
        out.put("replayed", replayed);
        return out;
    }

    // ── Mark lost ────────────────────────────────────────────────────────────

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> markLost(UUID eventId, List<String> pieceIds, BazarSaleService.Actor actor) {
        UUID tenantId = requireManager(actor);
        Map<String, Object> ev = endedEvent(tenantId, eventId);
        UUID transferId = (UUID) ev.get("transfer_id");
        Set<String> ids = new LinkedHashSet<>(pieceIds == null ? List.of() : pieceIds);
        if (ids.isEmpty()) throw invalid("Choose the pieces to mark lost.", "اختر القطع لتسجيلها كمفقودة.");
        // Only pieces still outstanding on this transfer — a sold, returned, damaged or lost one is refused.
        Map<UUID, List<String>> byLine = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT piece_id, line_id FROM transfer_pieces WHERE transfer_id = ? AND tenant_id = ? " +
                "AND outcome IS NULL AND piece_id = ANY(?) ORDER BY created_at, id",
                transferId, tenantId, ids.toArray(new String[0]))) {
            byLine.computeIfAbsent((UUID) r.get("line_id"), k -> new ArrayList<>()).add((String) r.get("piece_id"));
        }
        int found = byLine.values().stream().mapToInt(List::size).sum();
        if (found != ids.size()) {
            throw new BazarException(BazarException.Code.BAZAR_NOT_OUTSTANDING,
                "Only pieces still at the bazar (not sold, not back) can be marked lost — reload and try again.",
                "يمكن تسجيل القطع الموجودة في البازار فقط (غير المباعة وغير المرتجعة) كمفقودة — أعد التحميل وحاول مجددًا.",
                HttpStatus.CONFLICT);
        }
        for (Map.Entry<UUID, List<String>> e : byLine.entrySet()) {
            transfers.markLostForBazar(transferId, e.getKey(), e.getValue(), actor.userId());
        }
        return leftovers(tenantId, ev);
    }

    // ── Close transfer ───────────────────────────────────────────────────────

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> closeTransfer(UUID eventId, BazarSaleService.Actor actor) {
        UUID tenantId = requireManager(actor);
        Map<String, Object> ev = endedEvent(tenantId, eventId);
        UUID transferId = (UUID) ev.get("transfer_id");
        String status = jdbc.queryForObject("SELECT status FROM transfers WHERE id = ? AND tenant_id = ? FOR UPDATE",
            String.class, transferId, tenantId);
        if ("closed".equals(status)) {
            Map<String, Object> out = leftovers(tenantId, ev);
            out.put("replayed", true);
            return out;
        }
        Map<String, Object> before = leftovers(tenantId, ev);
        if (!Boolean.TRUE.equals(before.get("balanced"))) {
            @SuppressWarnings("unchecked") Map<String, Object> c = (Map<String, Object>) before.get("counts");
            Map<String, Object> d = new LinkedHashMap<>(c);
            throw new BazarException(BazarException.Code.BAZAR_LEFTOVERS_OUTSTANDING,
                c.get("outstanding") + " piece(s) are neither scanned back nor marked lost yet.",
                "ما زالت " + c.get("outstanding") + " قطعة/قطع لم تُمسح للعودة ولم تُسجَّل كمفقودة.",
                HttpStatus.CONFLICT, d);
        }
        transfers.closeTransferForBazar(transferId, actor.userId());
        Map<String, Object> out = leftovers(tenantId, ev);
        out.put("replayed", false);
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> endedEvent(UUID tenantId, UUID eventId) {
        Map<String, Object> ev = event(tenantId, eventId, " FOR SHARE OF e");
        if (!"ended".equals(ev.get("status"))) {
            throw new BazarException(BazarException.Code.BAZAR_EVENT_NOT_ENDED,
                "End the event first — leftovers come back after it ends.",
                "أنهِ البازار أولًا — تعود البضاعة المتبقية بعد انتهائه.", HttpStatus.CONFLICT);
        }
        return ev;
    }

    private Map<String, Object> event(UUID tenantId, UUID eventId, String lock) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT e.id, e.status, e.transfer_id, e.location_id FROM bazar_events e WHERE e.id = ? AND e.tenant_id = ?"
                + lock, eventId, tenantId);
        if (rows.isEmpty()) {
            throw new BazarException(BazarException.Code.BAZAR_EVENT_NOT_FOUND,
                "Bazar not found.", "لم يتم العثور على البازار.", HttpStatus.NOT_FOUND);
        }
        return rows.get(0);
    }

    static UUID requireEnabledTenant(JdbcTemplate jdbc) {
        UUID tenantId = TenantContext.require();
        if (!Bazar.enabled(jdbc, tenantId)) {
            throw new BazarException(BazarException.Code.BAZAR_NOT_ENABLED, "Not found.", "غير موجود.",
                HttpStatus.NOT_FOUND);
        }
        return tenantId;
    }

    /**
     * Scan-back and the leftovers list: any role in the event's seller_roles (workers included), or an owner /
     * manager — the same rule as the sell screen's piece lookup. End, mark lost, close transfer and the report stay
     * owner / manager.
     */
    private static void requireSeller(BazarSaleService.Actor actor, Map<String, Object> ev) {
        boolean manages = actor != null && actor.role() != null && MANAGERS.contains(actor.role().toLowerCase());
        if (manages) return;
        if (actor == null || actor.role() == null
                || !BazarSaleService.toList(ev.get("seller_roles")).contains(actor.roleKey())) {
            throw new BazarException(BazarException.Code.BAZAR_NOT_A_SELLER,
                "Your role can't do this at this bazar.", "دورك لا يسمح بذلك في هذا البازار.", HttpStatus.FORBIDDEN);
        }
    }

    static UUID requireManagerTenant(JdbcTemplate jdbc, BazarSaleService.Actor actor) {
        UUID tenantId = requireEnabledTenant(jdbc);
        if (actor == null || actor.role() == null || !MANAGERS.contains(actor.role().toLowerCase())) {
            throw new BazarException(BazarException.Code.BAZAR_MANAGERS_ONLY,
                "Only an owner or manager can do this.", "يمكن للمالك أو المدير فقط القيام بذلك.", HttpStatus.FORBIDDEN);
        }
        return tenantId;
    }

    private UUID requireManager(BazarSaleService.Actor actor) {
        return requireManagerTenant(jdbc, actor);
    }

    private static BazarException alreadyBack() {
        return new BazarException(BazarException.Code.BAZAR_ALREADY_BACK,
            "This piece is already back.", "هذه القطعة عادت بالفعل.", HttpStatus.CONFLICT);
    }

    private static BazarException invalid(String en, String ar) {
        return new BazarException(BazarException.Code.BAZAR_INVALID_INPUT, en, ar, HttpStatus.BAD_REQUEST);
    }

}
