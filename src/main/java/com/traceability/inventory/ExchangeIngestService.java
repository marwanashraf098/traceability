package com.traceability.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.ExchangeStateInterpreter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * FR-EXCHANGE Phase 1 — ingest-time upsert of the {@code exchanges} aggregate from a
 * fetched Bosta type.code=30 delivery. Called by BostaWebhookJob BEFORE the generic
 * (state_code, type) mapper — type-30 deliveries never enter that mapper at all (the
 * :EXCHANGE seed row V74 deletes would otherwise 404 through unknownCode() for state
 * 41, which has no :ALL fallback).
 *
 * Single-variant-per-leg schema: the fleet-confirmed shape has itemsCount=1 on both
 * legs. A CONFIRMED multi-item exchange (itemsCount != 1 on either leg, once known)
 * cannot be represented — upsertFromDelivery() returns MULTI_ITEM and the caller falls
 * back to the pre-existing generic unmatched-delivery lane so it still raises an
 * exception (just not exchange-shaped), per the "do not auto-handle" instruction.
 *
 * Step 2 Part B (Step 1 diagnosis §2b): {@code raw.returnSpecs} — the INBOUND leg's
 * item details — is confirmed live to populate only once the courier reaches the
 * doorstep (state 41+, "out_for_exchange" or later); on early sightings (Pickup
 * requested / Route Assigned) it is entirely ABSENT, not itemsCount=0 or missing just
 * the count. Treating "absent" the same as "confirmed != 1" was the bug: two live false
 * positives (Snouts 5794052882, Jumi 4818277658) were rejected as EXCHANGE_MULTI_ITEM
 * while still at their earliest sighting, purely because Bosta hadn't told us the
 * inbound item yet. upsertFromDelivery() now returns HELD for that case instead —
 * never records EXCHANGE_MULTI_ITEM, never creates an exchanges row.
 *
 * The itemsCount check only gates the FIRST sighting of a tracking number. Once an
 * exchanges row exists, later webhooks (state updates) always update it regardless of
 * itemsCount — re-validating on every webhook would let a later malformed/re-fetched
 * payload evict an exchange that was already correctly captured. HELD deliberately
 * creates NO row of any kind (exchanges or unlinked), so the "exists" check below stays
 * false and a later webhook for the same tracking re-runs this whole gate from scratch —
 * that IS the re-check mechanism, no separate pending marker needed. Creating a
 * placeholder exchanges row while inbound is still unknown was considered and rejected:
 * once a row exists this gate never re-runs, so a genuinely multi-item inbound
 * discovered on a later webhook would silently never be caught.
 */
@Service
public class ExchangeIngestService {

    /** Outcome of a first-sighting ingest attempt. See class javadoc for HELD. */
    public enum IngestOutcome { ROUTED, MULTI_ITEM, HELD }

    private final JdbcTemplate jdbc;
    private final ExchangeStateInterpreter interpreter;

    public ExchangeIngestService(JdbcTemplate jdbc, ExchangeStateInterpreter interpreter) {
        this.jdbc = jdbc;
        this.interpreter = interpreter;
    }

    /**
     * @return ROUTED if the delivery was upserted into the exchange lane (caller should
     *         mark the webhook event processed and stop); MULTI_ITEM if a leg's itemsCount
     *         is confirmed != 1 for a NEW tracking number (caller must fall back to the
     *         generic unmatched lane); HELD if the inbound leg's item details are not yet
     *         known (caller marks the webhook processed and does nothing else — a later
     *         webhook re-evaluates).
     */
    @Transactional
    public IngestOutcome upsertFromDelivery(UUID tenantId, String trackingNumber, BostaDelivery delivery) {
        JsonNode raw = delivery.raw();
        if (raw == null) return IngestOutcome.MULTI_ITEM;

        JsonNode outboundDetails = raw.path("specs").path("packageDetails");
        JsonNode returnSpecsNode = raw.path("returnSpecs");
        JsonNode inboundDetails  = returnSpecsNode.path("packageDetails");

        Boolean exists = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM exchanges WHERE tenant_id = ? AND tracking_number = ?)",
            Boolean.class, tenantId, trackingNumber);

        if (!Boolean.TRUE.equals(exists)) {
            int outboundCount = outboundDetails.path("itemsCount").asInt(-1);
            if (outboundCount != 1) {
                return IngestOutcome.MULTI_ITEM;
            }
            if (returnSpecsNode.isMissingNode() || inboundDetails.isMissingNode()) {
                return IngestOutcome.HELD;
            }
            int inboundCount = inboundDetails.path("itemsCount").asInt(-1);
            if (inboundCount != 1) {
                return IngestOutcome.MULTI_ITEM;
            }
        }

        String outboundDesc  = outboundDetails.path("description").asText(null);
        String inboundDesc   = inboundDetails.path("description").asText(null);
        String inboundDescAr = inboundDetails.path("descriptionAr").asText(null);
        BigDecimal cod         = parseDecimal(raw.path("cod"));
        BigDecimal goodsValue  = parseDecimal(raw.path("goodsInfo").path("amount"));

        jdbc.update(
            "INSERT INTO exchanges " +
            "(tenant_id, tracking_number, status, outbound_description, inbound_description, " +
            " inbound_description_ar, cod, goods_value, raw) " +
            "VALUES (?, ?, 'needs_mapping', ?, ?, ?, ?, ?, ?::jsonb) " +
            "ON CONFLICT (tenant_id, tracking_number) DO UPDATE SET " +
            "    raw        = EXCLUDED.raw, " +
            "    updated_at = now()",
            tenantId, trackingNumber, outboundDesc, inboundDesc, inboundDescAr,
            cod, goodsValue, raw.toString());

        // FR-EXCHANGE Phase 3/4 §3.5 structural hook: derive exchanges.status from
        // return-leg timeline progress. interpretReturnLeg() always returns empty this
        // pass (HELD pending confirmed vocabulary — see ExchangeStateInterpreter javadoc),
        // so this is a no-op today; wired now so a future vocabulary pass only needs to
        // populate the interpreter's map, not touch this call site. Guarded to
        // status <> 'needs_mapping' so this can never resurrect/advance a not-yet-mapped
        // exchange — status only ever moves forward from a mapped exchange onward.
        Optional<String> returnLegStatus = interpreter.interpretReturnLeg(raw);
        returnLegStatus.ifPresent(status -> jdbc.update(
            "UPDATE exchanges SET status = ?, updated_at = now() " +
            "WHERE tenant_id = ? AND tracking_number = ? AND status <> 'needs_mapping'",
            status, tenantId, trackingNumber));

        return IngestOutcome.ROUTED;
    }

    private static BigDecimal parseDecimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
