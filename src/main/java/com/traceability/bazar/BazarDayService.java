package com.traceability.bazar;

import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bazar POS B4 — close the day. The ONE writer of {@code bazar_day_closes} (append-only: a re-close is a new row that
 * supersedes the current one). Owner / manager only. NO Shopify write of any kind, and nothing here writes a sale or a
 * money row.
 * <ul>
 *   <li><b>A business day</b> = an Africa/Cairo date. A day's EXPECTED money comes only from {@code bazar_payments}
 *       rows counting on that day ({@link BazarSaleService#netByMethod}: payments − refunds, voided rows out — the same
 *       source as /today; real money counts on the day written, a method correction on the replaced row's day): cash = opening float + cash net; card = card net; InstaPay = InstaPay net.</li>
 *   <li><b>The calculation</b> ({@link #calculate}): the difference per method (entered − expected; InstaPay unticked =
 *       the whole InstaPay amount not received), "X EGP not received" = the sum of the shortfalls, money over shown
 *       as over (never netted against a shortfall), take-home cash = counted − kept.</li>
 *   <li><b>Opening float</b>: the first close of the event asks it (there is nothing before it); later days default
 *       to the previous close's cash kept for tomorrow; a re-close defaults to the day's own float. It can be
 *       entered by hand on any close.</li>
 *   <li><b>Status</b> per day: open / closed_balanced / closed_short / changed_after_close — the last when a money row
 *       of the day was written (or one of its rows voided) after the close, or the day's money no longer equals the
 *       close's snapshot. Closing does not stop selling.</li>
 * </ul>
 * {@link #dayReport} is the per-day money summary (the B5 report reuses it).
 */
@Service
public class BazarDayService {

    private static final Set<String> MANAGERS = Set.of("owner", "manager");
    private static final List<String> METHODS = List.of("cash", "card", "instapay");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");

    private final JdbcTemplate jdbc;

    public BazarDayService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── The calculation (pure) ───────────────────────────────────────────────

    /** What the merchant entered. Any field may be null in a preview (not typed yet); a close needs them all. */
    public record Entered(BigDecimal cashCounted, BigDecimal cardSettlement, Boolean instapayReceived,
                          BigDecimal cashKept, BigDecimal openingFloat) {}

    /** A day's nets per method (payments − refunds written that day). */
    public record Nets(BigDecimal cash, BigDecimal card, BigDecimal instapay) {
        static Nets of(Map<String, BigDecimal> byMethod) {
            return new Nets(byMethod.getOrDefault("cash", ZERO), byMethod.getOrDefault("card", ZERO),
                byMethod.getOrDefault("instapay", ZERO));
        }
    }

    /** The result of a close (or its preview). Differences are entered − expected: negative = short. */
    public record Result(BigDecimal openingFloat, BigDecimal expectedCash, BigDecimal expectedCard,
                         BigDecimal expectedInstapay, BigDecimal cashCounted, BigDecimal cardSettlement,
                         boolean instapayReceived, BigDecimal cashKept, BigDecimal cashDifference,
                         BigDecimal cardDifference, BigDecimal instapayDifference, BigDecimal cashShort,
                         BigDecimal cardShort, BigDecimal instapayNotReceived, BigDecimal notReceivedTotal,
                         BigDecimal cashOver, BigDecimal cardOver, BigDecimal overTotal, BigDecimal takeHome) {}

    /**
     * THE close rule. Missing entries count as 0 (cash, card), ticked (InstaPay) and the opening float (kept); the
     * caller decides whether a missing entry is allowed. Pure: no DB.
     */
    public static Result calculate(Nets nets, BigDecimal openingFloat, Entered e) {
        BigDecimal flt   = scale(openingFloat == null ? ZERO : openingFloat);
        BigDecimal cash  = scale(e.cashCounted() == null ? ZERO : e.cashCounted());
        BigDecimal card  = scale(e.cardSettlement() == null ? ZERO : e.cardSettlement());
        boolean    insta = e.instapayReceived() == null || e.instapayReceived();
        BigDecimal kept  = scale(e.cashKept() == null ? flt : e.cashKept());

        BigDecimal expectedCash  = flt.add(nets.cash());
        BigDecimal expectedCard  = nets.card();
        BigDecimal expectedInsta = nets.instapay();

        BigDecimal cashDiff  = cash.subtract(expectedCash);
        BigDecimal cardDiff  = card.subtract(expectedCard);
        // Unticked: the WHOLE InstaPay amount counts as not received (nothing, when refunds outweighed payments).
        BigDecimal instaDiff = insta ? ZERO : expectedInsta.max(ZERO).negate();

        BigDecimal cashShort = cashDiff.negate().max(ZERO);
        BigDecimal cardShort = cardDiff.negate().max(ZERO);
        BigDecimal instaShort = instaDiff.negate().max(ZERO);
        BigDecimal cashOver = cashDiff.max(ZERO);
        BigDecimal cardOver = cardDiff.max(ZERO);
        return new Result(flt, expectedCash, expectedCard, expectedInsta, cash, card, insta, kept,
            cashDiff, cardDiff, instaDiff, cashShort, cardShort, instaShort,
            cashShort.add(cardShort).add(instaShort), cashOver, cardOver, cashOver.add(cardOver),
            cash.subtract(kept));
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.UNNECESSARY);
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    /** Every business day of the event so far, oldest first, each with its money, status and current close. */
    @Transactional(readOnly = true)
    public Map<String, Object> days(UUID eventId, BazarSaleService.Actor actor) {
        UUID tenantId = requireManager(actor);
        Map<String, Object> ev = event(tenantId, eventId, "");
        List<LocalDate> days = eventDays(tenantId, ev);
        List<Map<String, Object>> out = new ArrayList<>();
        int n = 0;
        for (LocalDate d : days) {
            Map<String, Object> view = dayReport(tenantId, ev, d);
            view.put("day_number", ++n);
            out.add(view);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("today", BazarSaleService.cairoToday(jdbc).toString());
        body.put("payment_methods", BazarSaleService.toList(ev.get("payment_methods")));
        body.put("days", out);
        return body;
    }

    /** The close calculation for {@code day} with what the merchant typed so far. Nothing is written. */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(UUID eventId, LocalDate day, Entered entered, BazarSaleService.Actor actor) {
        UUID tenantId = requireManager(actor);
        Map<String, Object> ev = event(tenantId, eventId, "");
        requireClosable(tenantId, ev, day);
        validateAmounts(entered);
        Map<String, Object> d = dayReport(tenantId, ev, day);
        OpeningFloat flt = openingFloat(tenantId, eventId, day, entered.openingFloat());
        Result r = calculate(nets(d), flt.amount(), entered);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("business_day", day.toString());
        out.put("status", d.get("status"));
        out.put("money", d.get("money"));
        out.put("opening_float_source", flt.source());
        out.put("needs_opening_float", "none".equals(flt.source()));
        out.put("result", resultView(r, BazarSaleService.toList(ev.get("payment_methods"))));
        // Problems the close would refuse, shown while typing (a preview never refuses them).
        List<String> problems = new ArrayList<>();
        if (entered.cashCounted() != null && r.cashKept().compareTo(r.cashCounted()) > 0) {
            problems.add("kept_more_than_counted");
        }
        out.put("problems", problems);
        return out;
    }

    /**
     * The per-day money summary — B4's days list and the B5 report read the same thing:
     * {business_day, is_today, money {by_method, total, sales, returns, exchanges, revenue}, status, close | null,
     * close_count, opening_float_default {amount, source}}.
     */
    public Map<String, Object> dayReport(UUID tenantId, Map<String, Object> ev, LocalDate day) {
        UUID eventId = (UUID) ev.get("id");
        List<String> methods = BazarSaleService.toList(ev.get("payment_methods"));
        Map<String, Object> money = BazarSaleService.summary(jdbc, tenantId, eventId, methods, day);
        @SuppressWarnings("unchecked")
        Map<String, BigDecimal> byMethod = (Map<String, BigDecimal>) money.get("by_method");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("business_day", day.toString());
        out.put("is_today", day.equals(BazarSaleService.cairoToday(jdbc)));
        out.put("money", money);

        Map<String, Object> close = currentClose(tenantId, eventId, day);
        String status;
        if (close == null) {
            status = "open";
        } else if (changedAfterClose(tenantId, eventId, day, close, byMethod)) {
            status = "changed_after_close";
        } else {
            status = ((BigDecimal) close.get("not_received_total")).signum() > 0 ? "closed_short" : "closed_balanced";
        }
        out.put("status", status);
        out.put("close", close == null ? null : closeView(close, methods));
        out.put("close_count", jdbc.queryForObject(
            "SELECT COUNT(*) FROM bazar_day_closes WHERE event_id = ? AND tenant_id = ? AND business_day = ?::date",
            Integer.class, eventId, tenantId, day.toString()));
        OpeningFloat flt = openingFloat(tenantId, eventId, day, null);
        Map<String, Object> fl = new LinkedHashMap<>();
        fl.put("amount", "none".equals(flt.source()) ? null : flt.amount());
        fl.put("source", flt.source());
        out.put("opening_float_default", fl);
        return out;
    }

    // ── Close ────────────────────────────────────────────────────────────────

    /**
     * Close {@code day}: one append-only row with what was entered, the expected snapshot and the result. The day's
     * current close (if any) is superseded by it. Same {@code client_request_id} again → the first close, nothing new.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> close(UUID eventId, LocalDate day, Entered entered, String note, String clientRequestId,
                                     BazarSaleService.Actor actor) {
        UUID tenantId = requireManager(actor);
        Map<String, Object> ev = event(tenantId, eventId, " FOR SHARE OF e");
        String crid = clientRequestId == null ? "" : clientRequestId.trim();
        if (crid.isEmpty() || crid.length() > 100) {
            throw invalid("Missing request id (client_request_id).", "معرّف الطلب مفقود (client_request_id).");
        }
        // One close of a day at a time; a concurrent retry of the same request waits, then finds the first.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            tenantId + ":bazar-day:" + eventId + ":" + day);
        List<Map<String, Object>> existing = jdbc.queryForList(
            "SELECT id, event_id, business_day FROM bazar_day_closes WHERE tenant_id = ? AND client_request_id = ?",
            tenantId, crid);
        if (!existing.isEmpty()) {
            Map<String, Object> first = existing.get(0);
            if (!eventId.equals(first.get("event_id"))
                    || !day.equals(((java.sql.Date) first.get("business_day")).toLocalDate())) {
                throw invalid("This request id was already used to close another day.",
                    "تم استخدام معرّف الطلب هذا لإغلاق يوم آخر.");
            }
            Map<String, Object> out = dayReport(tenantId, ev, day);
            out.put("replayed", true);
            return out;
        }

        requireClosable(tenantId, ev, day);
        boolean cardOn = BazarSaleService.toList(ev.get("payment_methods")).contains("card");
        boolean instaOn = BazarSaleService.toList(ev.get("payment_methods")).contains("instapay");
        if (entered.cashCounted() == null) {
            throw invalid("Enter the cash counted in the drawer.", "أدخل النقدية الموجودة في الدرج.");
        }
        if (cardOn && entered.cardSettlement() == null) {
            throw invalid("Enter the card machine's settlement.", "أدخل تسوية ماكينة الكارت.");
        }
        if (instaOn && entered.instapayReceived() == null) {
            throw invalid("Say whether the InstaPay money was received.", "حدّد إن كانت أموال إنستاباي قد وصلت.");
        }
        validateAmounts(entered);
        String n = note == null || note.isBlank() ? null : note.trim();
        if (n != null && n.length() > 500) throw invalid("The note can be up to 500 characters.", "الملاحظة حتى 500 حرف.");

        OpeningFloat flt = openingFloat(tenantId, eventId, day, entered.openingFloat());
        if ("none".equals(flt.source())) {
            throw new BazarException(BazarException.Code.BAZAR_OPENING_FLOAT_REQUIRED,
                "Enter the cash the drawer started with (opening float) — this is the bazar's first close.",
                "أدخل النقدية التي بدأ بها الدرج (رصيد الافتتاح) — هذا أول إغلاق للبازار.", HttpStatus.BAD_REQUEST);
        }
        Result r = calculate(Nets.of(BazarSaleService.netByMethod(jdbc, tenantId, eventId, day)), flt.amount(),
            new Entered(entered.cashCounted(), entered.cardSettlement(),
                instaOn ? entered.instapayReceived() : Boolean.TRUE, entered.cashKept(), flt.amount()));
        if (r.cashKept().compareTo(r.cashCounted()) > 0) {
            throw invalid("You can't keep more cash for tomorrow than you counted.",
                "لا يمكن الاحتفاظ بنقدية للغد أكثر مما تم عدّه.");
        }

        Map<String, Object> current = currentClose(tenantId, eventId, day);
        jdbc.update(
            "INSERT INTO bazar_day_closes (tenant_id, event_id, business_day, opening_float, cash_counted, " +
            "  card_settlement, instapay_received, cash_kept, note, net_cash, net_card, net_instapay, expected_cash, " +
            "  expected_card, expected_instapay, cash_difference, card_difference, instapay_difference, " +
            "  not_received_total, over_total, take_home, closed_by, closed_at, client_request_id, supersedes_id) " +
            "VALUES (?, ?, ?::date, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?, ?)",
            tenantId, eventId, day.toString(), r.openingFloat(), r.cashCounted(), r.cardSettlement(),
            r.instapayReceived(), r.cashKept(), n, r.expectedCash().subtract(r.openingFloat()), r.expectedCard(),
            r.expectedInstapay(), r.expectedCash(), r.expectedCard(), r.expectedInstapay(), r.cashDifference(),
            r.cardDifference(), r.instapayDifference(), r.notReceivedTotal(), r.overTotal(), r.takeHome(),
            actor.userId(), crid, current == null ? null : current.get("id"));

        Map<String, Object> out = dayReport(tenantId, ev, day);
        out.put("replayed", false);
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private record OpeningFloat(BigDecimal amount, String source) {}

    /**
     * The opening float of {@code day}: typed → 'entered'; else the day's own current close (a re-close) →
     * 'this_day_close'; else the latest earlier day's current close's cash kept → 'previous_close'; else 'none'
     * (the bazar's first close — it must be entered).
     */
    private OpeningFloat openingFloat(UUID tenantId, UUID eventId, LocalDate day, BigDecimal typed) {
        if (typed != null) return new OpeningFloat(scale(typed), "entered");
        List<BigDecimal> own = jdbc.queryForList(
            "SELECT c.opening_float FROM bazar_day_closes c WHERE c.event_id = ? AND c.tenant_id = ? " +
            "AND c.business_day = ?::date " + NOT_SUPERSEDED, BigDecimal.class, eventId, tenantId, day.toString());
        if (!own.isEmpty()) return new OpeningFloat(own.get(0).setScale(2), "this_day_close");
        List<BigDecimal> prev = jdbc.queryForList(
            "SELECT c.cash_kept FROM bazar_day_closes c WHERE c.event_id = ? AND c.tenant_id = ? " +
            "AND c.business_day < ?::date " + NOT_SUPERSEDED + "ORDER BY c.business_day DESC LIMIT 1",
            BigDecimal.class, eventId, tenantId, day.toString());
        if (!prev.isEmpty()) return new OpeningFloat(prev.get(0).setScale(2), "previous_close");
        return new OpeningFloat(ZERO, "none");
    }

    private static final String NOT_SUPERSEDED =
        "AND NOT EXISTS (SELECT 1 FROM bazar_day_closes n WHERE n.supersedes_id = c.id) ";

    /** The day's current close (the one nothing supersedes), or null. */
    private Map<String, Object> currentClose(UUID tenantId, UUID eventId, LocalDate day) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT c.*, u.name AS closed_by_name FROM bazar_day_closes c JOIN users u ON u.id = c.closed_by " +
            "WHERE c.event_id = ? AND c.tenant_id = ? AND c.business_day = ?::date " + NOT_SUPERSEDED,
            eventId, tenantId, day.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Changed after close: a money row counting on the day was written after the close — a sale, return or exchange
     * that day, or a void / method-change replacement of one of its rows (they inherit its business_day) — or, as a
     * backstop for a row stamped just before the close but committed after it, the day's nets no longer equal the
     * close's snapshot.
     */
    private boolean changedAfterClose(UUID tenantId, UUID eventId, LocalDate day, Map<String, Object> close,
                                      Map<String, BigDecimal> byMethod) {
        Nets now = Nets.of(byMethod);
        if (now.cash().compareTo((BigDecimal) close.get("net_cash")) != 0
                || now.card().compareTo((BigDecimal) close.get("net_card")) != 0
                || now.instapay().compareTo((BigDecimal) close.get("net_instapay")) != 0) {
            return true;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM bazar_payments p " +
            "  WHERE p.event_id = ? AND p.tenant_id = ? AND p.created_at > ? AND p.business_day = ?::date)",
            Boolean.class, eventId, tenantId, close.get("closed_at"), day.toString()));
    }

    private static Nets nets(Map<String, Object> dayReport) {
        @SuppressWarnings("unchecked")
        Map<String, Object> money = (Map<String, Object>) dayReport.get("money");
        @SuppressWarnings("unchecked")
        Map<String, BigDecimal> byMethod = (Map<String, BigDecimal>) money.get("by_method");
        return Nets.of(byMethod);
    }

    /** The close as the client sees it: what was entered, the snapshot and the result. */
    private static Map<String, Object> closeView(Map<String, Object> c, List<String> methods) {
        Result r = new Result((BigDecimal) c.get("opening_float"), (BigDecimal) c.get("expected_cash"),
            (BigDecimal) c.get("expected_card"), (BigDecimal) c.get("expected_instapay"),
            (BigDecimal) c.get("cash_counted"), (BigDecimal) c.get("card_settlement"),
            (Boolean) c.get("instapay_received"), (BigDecimal) c.get("cash_kept"),
            (BigDecimal) c.get("cash_difference"), (BigDecimal) c.get("card_difference"),
            (BigDecimal) c.get("instapay_difference"),
            ((BigDecimal) c.get("cash_difference")).negate().max(ZERO),
            ((BigDecimal) c.get("card_difference")).negate().max(ZERO),
            ((BigDecimal) c.get("instapay_difference")).negate().max(ZERO),
            (BigDecimal) c.get("not_received_total"),
            ((BigDecimal) c.get("cash_difference")).max(ZERO), ((BigDecimal) c.get("card_difference")).max(ZERO),
            (BigDecimal) c.get("over_total"), (BigDecimal) c.get("take_home"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", c.get("id"));
        out.put("closed_at", c.get("closed_at"));
        out.put("closed_by_name", c.get("closed_by_name"));
        out.put("note", c.get("note"));
        out.put("supersedes_id", c.get("supersedes_id"));
        out.put("result", resultView(r, methods));
        return out;
    }

    /**
     * {opening_float, cash_kept, take_home, not_received_total, not_received {cash, card, instapay}, over_total,
     * methods: [{method, expected, entered, difference, state: even | short | over | not_received}]} — every enabled
     * method, plus any other with money (a method switched off mid-event).
     */
    static Map<String, Object> resultView(Result r, List<String> enabled) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("opening_float", r.openingFloat());
        out.put("cash_kept", r.cashKept());
        out.put("take_home", r.takeHome());
        out.put("not_received_total", r.notReceivedTotal());
        Map<String, Object> split = new LinkedHashMap<>();
        split.put("cash", r.cashShort());
        split.put("card", r.cardShort());
        split.put("instapay", r.instapayNotReceived());
        out.put("not_received", split);
        out.put("over_total", r.overTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String m : METHODS) {
            BigDecimal expected = switch (m) {
                case "cash" -> r.expectedCash();
                case "card" -> r.expectedCard();
                default -> r.expectedInstapay();
            };
            boolean show = enabled.contains(m) || expected.signum() != 0;
            if (!show) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("method", m);
            row.put("expected", expected);
            switch (m) {
                case "cash" -> {
                    row.put("entered", r.cashCounted());
                    row.put("difference", r.cashDifference());
                    row.put("state", state(r.cashDifference(), false));
                }
                case "card" -> {
                    row.put("entered", r.cardSettlement());
                    row.put("difference", r.cardDifference());
                    row.put("state", state(r.cardDifference(), false));
                }
                default -> {
                    row.put("received", r.instapayReceived());
                    row.put("entered", r.instapayReceived() ? r.expectedInstapay() : ZERO);
                    row.put("difference", r.instapayDifference());
                    row.put("state", state(r.instapayDifference(), true));
                }
            }
            rows.add(row);
        }
        out.put("methods", rows);
        return out;
    }

    private static String state(BigDecimal diff, boolean instapay) {
        if (diff.signum() == 0) return "even";
        if (diff.signum() > 0) return "over";
        return instapay ? "not_received" : "short";
    }

    /** The event's business days so far: from the day it started (or its first day) to today / the day it ended. */
    private List<LocalDate> eventDays(UUID tenantId, Map<String, Object> ev) {
        if ("draft".equals(ev.get("status"))) return List.of();
        return jdbc.queryForList(
            "SELECT d::date FROM generate_series(" +
            "  LEAST((SELECT (started_at AT TIME ZONE 'Africa/Cairo')::date FROM bazar_events WHERE id = ? AND tenant_id = ?), " +
            "        (SELECT MIN(p.business_day) FROM bazar_payments p " +
            "           WHERE p.event_id = ? AND p.tenant_id = ?)), " +
            "  (SELECT COALESCE((ended_at AT TIME ZONE 'Africa/Cairo')::date, (now() AT TIME ZONE 'Africa/Cairo')::date) " +
            "     FROM bazar_events WHERE id = ? AND tenant_id = ?), interval '1 day') AS d ORDER BY d",
            LocalDate.class, ev.get("id"), tenantId, ev.get("id"), tenantId, ev.get("id"), tenantId);
    }

    /** A day of a started event, not in the future (and not after the day it ended). */
    private void requireClosable(UUID tenantId, Map<String, Object> ev, LocalDate day) {
        if (day == null || !eventDays(tenantId, ev).contains(day)) {
            throw new BazarException(BazarException.Code.BAZAR_DAY_NOT_CLOSABLE,
                "That isn't a day of this bazar that can be closed yet.",
                "هذا ليس يومًا من أيام هذا البازار يمكن إغلاقه بعد.", HttpStatus.CONFLICT);
        }
    }

    private static void validateAmounts(Entered e) {
        for (BigDecimal v : new BigDecimal[] { e.cashCounted(), e.cardSettlement(), e.cashKept(), e.openingFloat() }) {
            if (v == null) continue;
            if (v.signum() < 0 || v.compareTo(MAX_AMOUNT) > 0 || v.stripTrailingZeros().scale() > 2) {
                throw invalid("Amounts must be 0 or more, with at most 2 decimals.",
                    "يجب أن تكون المبالغ 0 أو أكثر، وبحد أقصى رقمين عشريين.");
            }
        }
    }

    private UUID requireManager(BazarSaleService.Actor actor) {
        UUID tenantId = TenantContext.require();
        if (!Bazar.enabled(jdbc, tenantId)) {
            throw new BazarException(BazarException.Code.BAZAR_NOT_ENABLED, "Not found.", "غير موجود.",
                HttpStatus.NOT_FOUND);
        }
        if (actor == null || actor.role() == null || !MANAGERS.contains(actor.role().toLowerCase())) {
            throw new BazarException(BazarException.Code.BAZAR_MANAGERS_ONLY,
                "Only an owner or manager can close the day.", "يمكن للمالك أو المدير فقط إغلاق اليوم.",
                HttpStatus.FORBIDDEN);
        }
        return tenantId;
    }

    private Map<String, Object> event(UUID tenantId, UUID eventId, String lock) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT e.id, e.status, e.payment_methods FROM bazar_events e WHERE e.id = ? AND e.tenant_id = ?" + lock,
            eventId, tenantId);
        if (rows.isEmpty()) {
            throw new BazarException(BazarException.Code.BAZAR_EVENT_NOT_FOUND,
                "Bazar not found.", "لم يتم العثور على البازار.", HttpStatus.NOT_FOUND);
        }
        return rows.get(0);
    }

    private static BazarException invalid(String en, String ar) {
        return new BazarException(BazarException.Code.BAZAR_INVALID_INPUT, en, ar, HttpStatus.BAD_REQUEST);
    }
}
