package com.traceability.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.traceability.integrations.bosta.BostaDelivery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * FR-EXCHANGE Phase 1 — ingest-time upsert of the {@code exchanges} aggregate from a
 * fetched Bosta type.code=30 delivery. Called by BostaWebhookJob BEFORE the generic
 * (state_code, type) mapper — type-30 deliveries never enter that mapper at all (the
 * :EXCHANGE seed row V74 deletes would otherwise 404 through unknownCode() for state
 * 41, which has no :ALL fallback).
 *
 * Single-variant-per-leg schema: the fleet-confirmed shape has itemsCount=1 on both
 * legs. A multi-item exchange (itemsCount != 1 on either leg) cannot be represented —
 * upsertFromDelivery() returns false and the caller falls back to the pre-existing
 * generic unmatched-delivery lane so it still raises an exception (just not
 * exchange-shaped), per the "do not auto-handle" instruction.
 *
 * The itemsCount check only gates the FIRST sighting of a tracking number. Once an
 * exchanges row exists, later webhooks (state updates) always update it regardless of
 * itemsCount — re-validating on every webhook would let a later malformed/re-fetched
 * payload evict an exchange that was already correctly captured.
 */
@Service
public class ExchangeIngestService {

    private final JdbcTemplate jdbc;

    public ExchangeIngestService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return true if the delivery was upserted into the exchange lane (caller should
     *         mark the webhook event processed and stop); false if it could not be
     *         represented (missing raw, or itemsCount != 1 on a leg for a NEW tracking
     *         number) and the caller must fall back to the generic unmatched lane.
     */
    @Transactional
    public boolean upsertFromDelivery(UUID tenantId, String trackingNumber, BostaDelivery delivery) {
        JsonNode raw = delivery.raw();
        if (raw == null) return false;

        JsonNode outboundDetails = raw.path("specs").path("packageDetails");
        JsonNode inboundDetails  = raw.path("returnSpecs").path("packageDetails");

        Boolean exists = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM exchanges WHERE tenant_id = ? AND tracking_number = ?)",
            Boolean.class, tenantId, trackingNumber);

        if (!Boolean.TRUE.equals(exists)) {
            int outboundCount = outboundDetails.path("itemsCount").asInt(-1);
            int inboundCount  = inboundDetails.path("itemsCount").asInt(-1);
            if (outboundCount != 1 || inboundCount != 1) {
                return false;
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
        return true;
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
