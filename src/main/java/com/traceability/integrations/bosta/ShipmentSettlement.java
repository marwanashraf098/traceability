package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analytics slice 3 — THE extractor from a Bosta delivery payload's wallet to the V148 settlement
 * columns on shipments, and the one writer of those columns from a payload.
 *
 * Called on EVERY path that writes shipments.raw (webhook / poll / walk / discovery ingest, the
 * lazy v0 refresher, both link-time inserts, the simulated insert) and by SettlementRefreshJob.
 * {@code ShipmentSettlementWiringGuardTest} fails the build when a new raw writer forgets it.
 *
 * Monotonic: a value already stored is never replaced by a missing one. v2 search items carry
 * {@code wallet} with nulls ({@code cashCycle: null, cashout: {next_cashout_date: null}}) and an
 * older or thinner v0 payload may lack the cashout — neither may clear what a fuller payload set.
 * A newer non-null value does replace an older one. settlement_status only moves forward
 * (none → deposited → paid); 'unresolved' (set by the refresh job) moves to deposited / paid only
 * when a payload shows that money.
 *
 * Numbers arrive as JSON numbers or strings ("93.00"). The cashout date is
 * {@code cashout.transaction_date}, else the transaction id's DDMONYY
 * ({@code WEDCOD09SEP26} → 2026-09-09) — Bosta stores the date on very few rows.
 */
public final class ShipmentSettlement {

    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    private static final Pattern TXN_DATE = Pattern.compile("^[A-Z]{3}COD(\\d{2}[A-Z]{3}\\d{2})$");
    private static final DateTimeFormatter TXN_FMT =
        new java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("ddMMMuu").toFormatter(Locale.ENGLISH)
            .withResolverStyle(java.time.format.ResolverStyle.STRICT);

    private ShipmentSettlement() {}

    /** Everything a payload says about settlement; null = the payload doesn't say. */
    public record Fields(Instant depositedAt, BigDecimal depositedAmt, BigDecimal codSettled,
                         BigDecimal bostaFees, BigDecimal shippingFees, BigDecimal vat,
                         BigDecimal openingPackageFees, BigDecimal collectionFees,
                         BigDecimal insuranceFees, BigDecimal flexShipFees,
                         BigDecimal promotionDiscount, BigDecimal shipmentFeesQuoted,
                         String cashCycleId, String cashoutTxnId, LocalDate cashoutDate,
                         BigDecimal cashoutAmount, LocalDate nextCashoutDate) {

        public static final Fields NONE = new Fields(null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null);

        /** The status this payload alone proves: paid / deposited / none. */
        public String provenStatus() {
            if (cashoutTxnId != null) return "paid";
            if (depositedAt != null) return "deposited";
            return "none";
        }
    }

    public static Fields extract(JsonNode raw) {
        if (raw == null || !raw.isObject()) return Fields.NONE;
        JsonNode cc = raw.path("wallet").path("cashCycle");
        JsonNode co = raw.path("wallet").path("cashout");
        boolean hasCycle = cc.isObject();
        Instant depositedAt = hasCycle ? instant(cc.path("deposited_at")) : null;
        String txn = text(co.path("transaction_id"));
        LocalDate cashoutDate = date(co.path("transaction_date"));
        if (cashoutDate == null) cashoutDate = txnDate(txn);
        return new Fields(
            depositedAt,
            hasCycle ? num(cc.path("deposited_amt")) : null,
            hasCycle ? num(cc.path("cod")) : null,
            hasCycle ? num(cc.path("bosta_fees")) : null,
            hasCycle ? num(cc.path("shipping_fees")) : null,
            hasCycle ? num(cc.path("vat")) : null,
            hasCycle ? num(cc.path("opening_package_fees")) : null,
            hasCycle ? num(cc.path("collection_fees")) : null,
            hasCycle ? num(cc.path("insurance_fees")) : null,
            hasCycle ? num(cc.path("flex_ship_fees")) : null,
            hasCycle ? num(cc.path("promotion_discount_amount")) : null,
            num(raw.path("shipmentFees")),
            hasCycle ? scalarText(cc.path("_id")) : null,
            txn,
            cashoutDate,
            num(co.path("amount")),
            date(co.path("next_cashout_date")));
    }

    /** Monotonic write of the payload's settlement fields onto one shipment row (caller's tx). */
    public static int apply(JdbcTemplate jdbc, UUID shipmentId, JsonNode raw) {
        if (shipmentId == null) return 0;
        Fields f = extract(raw);
        if (f.equals(Fields.NONE)) return 0;
        return write(jdbc, shipmentId, f, false);
    }

    /**
     * As {@link #apply}, for a payload the refresh job just read successfully from Bosta: also
     * stamps settlement_refreshed_at and settlement_verified_at.
     */
    public static int applyRefreshed(JdbcTemplate jdbc, UUID shipmentId, JsonNode raw) {
        return write(jdbc, shipmentId, extract(raw), true);
    }

    private static int write(JdbcTemplate jdbc, UUID shipmentId, Fields f, boolean refreshed) {
        return jdbc.update("""
            UPDATE shipments SET
                deposited_at         = COALESCE(?, deposited_at),
                deposited_amt        = COALESCE(?, deposited_amt),
                cod_settled          = COALESCE(?, cod_settled),
                bosta_fees           = COALESCE(?, bosta_fees),
                shipping_fees        = COALESCE(?, shipping_fees),
                vat                  = COALESCE(?, vat),
                opening_package_fees = COALESCE(?, opening_package_fees),
                collection_fees      = COALESCE(?, collection_fees),
                insurance_fees       = COALESCE(?, insurance_fees),
                flex_ship_fees       = COALESCE(?, flex_ship_fees),
                promotion_discount   = COALESCE(?, promotion_discount),
                shipment_fees_quoted = COALESCE(?, shipment_fees_quoted),
                cash_cycle_id        = COALESCE(?, cash_cycle_id),
                cashout_txn_id       = COALESCE(?, cashout_txn_id),
                cashout_date         = COALESCE(?, cashout_date),
                cashout_amount       = COALESCE(?, cashout_amount),
                next_cashout_date    = COALESCE(?, next_cashout_date),
                settlement_refreshed_at = CASE WHEN ? THEN now() ELSE settlement_refreshed_at END,
                settlement_verified_at  = CASE WHEN ? THEN now() ELSE settlement_verified_at END,
                settlement_status    = CASE
                    WHEN COALESCE(?, cashout_txn_id) IS NOT NULL THEN 'paid'
                    WHEN COALESCE(?::timestamptz, deposited_at) IS NOT NULL THEN 'deposited'
                    ELSE settlement_status END
            WHERE id = ?
            """,
            ts(f.depositedAt()), f.depositedAmt(), f.codSettled(), f.bostaFees(), f.shippingFees(),
            f.vat(), f.openingPackageFees(), f.collectionFees(), f.insuranceFees(), f.flexShipFees(),
            f.promotionDiscount(), f.shipmentFeesQuoted(), f.cashCycleId(), f.cashoutTxnId(),
            sqlDate(f.cashoutDate()), f.cashoutAmount(), sqlDate(f.nextCashoutDate()),
            refreshed, refreshed, f.cashoutTxnId(), ts(f.depositedAt()), shipmentId);
    }

    // ── parsing ─────────────────────────────────────────────────────────────

    static BigDecimal num(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        if (n.isNumber()) return n.decimalValue();
        if (n.isTextual()) {
            String s = n.asText().trim();
            if (s.matches("-?\\d+(\\.\\d+)?")) return new BigDecimal(s);
        }
        return null;
    }

    private static String text(JsonNode n) {
        if (n == null || !n.isTextual()) return null;
        String s = n.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static String scalarText(JsonNode n) {
        if (n == null) return null;
        if (n.isNumber()) return n.asText();
        return text(n);
    }

    private static Instant instant(JsonNode n) {
        String s = text(n);
        if (s == null) return null;
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            try {
                return java.time.OffsetDateTime.parse(s).toInstant();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    /** A Bosta date-time as the Cairo calendar day. */
    private static LocalDate date(JsonNode n) {
        Instant i = instant(n);
        return i == null ? null : i.atZone(CAIRO).toLocalDate();
    }

    static LocalDate txnDate(String txn) {
        if (txn == null) return null;
        Matcher m = TXN_DATE.matcher(txn);
        if (!m.matches()) return null;
        try {
            return LocalDate.parse(m.group(1), TXN_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Date sqlDate(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }
}
