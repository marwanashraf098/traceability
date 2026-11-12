package com.traceability.inventory;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Bosta exchange (type 30) — what comes BACK on the exchange AWB (2026-11-12). The single type 30 shipment row is the
 * replacement order's forward leg (ExchangeService.commit → linkAtMapTime), so "the AWB's order" is the item that went
 * OUT. While the trip is an exchange (not turned around as a Return To Origin), Scan returns expects the INBOUND item,
 * checked against the ORIGINAL order ({@code exchanges.matched_order_id}):
 * <ul>
 *   <li>a Traced-booked exchange (return_request_id set): exactly its request's items (as a request-linked leg);</li>
 *   <li>a dashboard exchange: the original order's pieces of the inbound variant ({@link #variantSql} — V227's
 *       {@code exchange_inbound_variant}, the ONE resolver), and its untracked lines of that variant as unit rows
 *       (UntrackedParcelUnits); unknown → nothing expected, Bosta's description shown, any piece of the original order
 *       is accepted by the existing scan rules, unit rows for every untracked line of the original except the
 *       outbound variant.</li>
 * </ul>
 * A trip Bosta turned around (type Return To Origin) keeps the old behaviour: the replacement comes back.
 */
public final class ExchangeInbound {

    private ExchangeInbound() {}

    /**
     * Leg {@code s} is a Bosta exchange trip whose returning item is the inbound one: an exchanges row carries its AWB
     * (any status — the old item travels whatever the row's matching state) and Bosta has not turned it into a Return
     * To Origin.
     */
    public static String tripSql(String s) {
        return "(" + s + ".provider = 'bosta' " +
               "AND upper(COALESCE(" + s + ".raw #>> '{type,value}', '')) <> 'RETURN TO ORIGIN' " +
               "AND EXISTS (SELECT 1 FROM exchanges x_trip WHERE x_trip.tenant_id = " + s + ".tenant_id " +
               "            AND x_trip.tracking_number = " + s + ".tracking_number)) ";
    }

    /** {@code LEFT JOIN exchanges <e>} on the exchange trip of leg {@code s} (NULL columns when it isn't one). */
    public static String tripJoinSql(String e, String s) {
        return "LEFT JOIN exchanges " + e + " ON " + e + ".tenant_id = " + s + ".tenant_id " +
               "AND " + e + ".tracking_number = " + s + ".tracking_number AND " + tripSql(s);
    }

    /** The inbound variant of exchange {@code e} (stored, else resolved — never guessed; NULL when unknown). */
    public static String variantSql(String e) {
        return "exchange_inbound_variant(" + e + ".tenant_id, " + e + ".inbound_variant_id, " + e + ".inbound_description, "
             + e + ".matched_order_id, " + e + ".outbound_variant_id)";
    }

    /**
     * Ingest / match: stores the resolved inbound variant on the exchange of {@code trackingNumber} — only while it is
     * NULL (never overwrites, a person's pick stays). Joins the caller's transaction; RLS applies.
     */
    public static int fill(JdbcTemplate jdbc, UUID tenantId, String trackingNumber) {
        return jdbc.update(
            "UPDATE exchanges e SET inbound_variant_id = " + variantSql("e") + " " +
            "WHERE e.tenant_id = ? AND e.tracking_number = ? AND e.inbound_variant_id IS NULL " +
            "  AND " + variantSql("e") + " IS NOT NULL",
            tenantId, trackingNumber);
    }

    /** {@link #fill} by exchange id. */
    public static int fill(JdbcTemplate jdbc, UUID tenantId, UUID exchangeId) {
        return jdbc.update(
            "UPDATE exchanges e SET inbound_variant_id = " + variantSql("e") + " " +
            "WHERE e.tenant_id = ? AND e.id = ? AND e.inbound_variant_id IS NULL " +
            "  AND " + variantSql("e") + " IS NOT NULL",
            tenantId, exchangeId);
    }
}
