package com.traceability.inventory;

import java.util.UUID;

/**
 * S3b (2026-11-19) — after an exchange is committed (its replacement order + forward leg created, or its Bosta leg
 * linked to a Shopify-exchange shadow), the new leg is brought to the exchange's CURRENT Bosta state through the
 * existing type-30 path (ExchangeStateInterpreter + BostaWebhookJob.applyMappedState). A leg is created at 'created';
 * an exchange first committed after Bosta already took it past pickup (the multi-item backfill, a late mapping) would
 * otherwise sit at 'created' and be pickable. No-op while Bosta is at "Pickup requested" (timeline 'new') or the
 * interpreter doesn't know the milestone. Implemented by BostaWebhookJob (integrations.bosta) — an interface here so
 * inventory never depends on that package.
 */
public interface ExchangeLegAdvancer {
    void advanceAtCommit(UUID tenantId, String trackingNumber);

    /**
     * THE "advanced at commit, nothing from Bosta since" rule for shipment alias {@code s}: the leg has status history
     * and none of it came from a Bosta update (webhook_event_id) or another source — only advanceAtCommit wrote it.
     * The exchange_unmapped_state alert leaves such a leg out (a backfill must not raise one alert per exchange); the
     * first real Bosta update for the AWB ends it, and the leg is then treated like every other exchange.
     */
    static String advancedAtCommitOnlySql(String s) {
        return "(EXISTS (SELECT 1 FROM shipment_status_history h WHERE h.shipment_id = " + s + ".id) " +
               " AND NOT EXISTS (SELECT 1 FROM shipment_status_history h WHERE h.shipment_id = " + s + ".id " +
               "   AND (h.webhook_event_id IS NOT NULL OR h.source IS NOT NULL)))";
    }
}
