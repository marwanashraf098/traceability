package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.inventory.*;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Processes a persisted Bosta webhook event.
 *
 * Idempotency boundaries (two layers):
 *   1. Dedup check (optimization): if another row already has external_event_id=idemKey
 *      and status='processed', this row is a redelivery — mark as duplicate and return.
 *   2. State-machine guard (backstop): InventoryLedger.transition() rejects re-applying
 *      the same state. A crash after the ledger writes but before the final UPDATE leaves
 *      the event as 'pending' for retry; the re-run hits the already-applied fast-path
 *      (current==target) and skips cleanly. The external_event_id is set AFTER all state
 *      changes are applied.
 *
 * Verify-by-fetch: the webhook payload is an untrusted hint. We always re-fetch the
 * delivery from Bosta and act on the FETCHED state, not the payload state.
 *
 * Piece transition already-applied handling:
 *   Fast path — current == target: skip (no DB write).
 *   StateConflictException where getActual()==target: concurrent worker applied it first,
 *     treat as no-op.
 *   StateConflictException where getActual()!=target: piece in unexpected state, log warning,
 *     continue to next piece (do not fail the whole job).
 *   IllegalTransitionException: piece has no legal path to target (already terminal or in
 *     unrelated state machine branch), log warning and continue.
 *
 * Unlinked Mode-B deliveries: if no shipment row exists for the tracking_number, the
 * delivery is recorded in unlinked_bosta_deliveries for the operator to match manually,
 * and the webhook_event is marked processed (not failed — this is an expected Mode-B case).
 *
 * Failure modes:
 *   BostaTransientException (network/5xx) → rethrown → JobRunr retries
 *   BostaException (404 / permanent)      → webhook_events.status='failed'
 *   Mapped to exception shipment state    → webhook_events.status='failed' + alert
 */
@Component
public class BostaWebhookJob {

    private static final Logger log = LoggerFactory.getLogger(BostaWebhookJob.class);

    private final JdbcTemplate        jdbc;
    private final TransactionTemplate tx;
    private final BostaGateway         bostaGateway;
    private final BostaStateMapper     stateMapper;
    private final EncryptionService    encryptionService;
    private final ObjectMapper         mapper;
    private final InventoryLedger      ledger;
    private final com.traceability.inventory.ShipmentLinkService shipmentLinkService;

    public BostaWebhookJob(JdbcTemplate jdbc,
                            PlatformTransactionManager txm,
                            BostaGateway bostaGateway,
                            BostaStateMapper stateMapper,
                            EncryptionService encryptionService,
                            ObjectMapper mapper,
                            InventoryLedger ledger,
                            com.traceability.inventory.ShipmentLinkService shipmentLinkService) {
        this.jdbc                = jdbc;
        this.tx                  = new TransactionTemplate(txm);
        this.bostaGateway        = bostaGateway;
        this.stateMapper         = stateMapper;
        this.encryptionService   = encryptionService;
        this.mapper              = mapper;
        this.ledger              = ledger;
        this.shipmentLinkService = shipmentLinkService;
    }

    // ---- private row types -------------------------------------------------

    private record ShipmentRow(UUID id, UUID orderId) {}
    private record PieceRow(String id, String status) {}

    // ---- job ---------------------------------------------------------------

    @Job(name = "Bosta webhook — event %0")
    public void process(long webhookEventId, UUID tenantId) {
        TenantContext.runAs(tenantId, (Runnable) () -> {

            // 1. Load payload (pending); transactional so GUC is set and RLS applies.
            JsonNode payload = tx.execute(s -> jdbc.query(
                "SELECT payload FROM webhook_events WHERE id = ? AND status = 'pending'",
                rs -> {
                    if (!rs.next()) return null;
                    try { return mapper.readTree(rs.getString("payload")); }
                    catch (Exception e) { throw new RuntimeException(e); }
                }, webhookEventId));

            if (payload == null) {
                log.debug("Webhook event {} not found or already processed — skipping", webhookEventId);
                return;
            }

            // 2. Extract payload fields (Bosta §8 camelCase shape).
            String trackingNumber = payload.path("trackingNumber").asText();
            int    payloadState   = payload.path("state").asInt(-1);
            String timestamp      = payload.path("updatedAt").asText("unknown");

            // 3. Idempotency key: stable for a given (trackingNumber, payloadState, timestamp).
            //    Based on the PAYLOAD (not the fetched state) so it is stable for redeliveries.
            String idemKey = sha256(trackingNumber + ":" + payloadState + ":" + timestamp);

            // 4. Dedup check (optimization): sibling row already processed → mark duplicate.
            Integer alreadyDone = jdbc.queryForObject(
                "SELECT COUNT(*) FROM webhook_events " +
                "WHERE external_event_id = ? AND status = 'processed'",
                Integer.class, idemKey);
            if (alreadyDone != null && alreadyDone > 0) {
                markDuplicate(webhookEventId, "duplicate: already processed");
                return;
            }

            // 5. Load this tenant's Bosta API key (under tenant context / RLS).
            String[] accountInfo = tx.execute(s -> jdbc.query(
                "SELECT api_key_encrypted FROM courier_accounts " +
                "WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active' LIMIT 1",
                rs -> rs.next() ? new String[]{rs.getString(1)} : null,
                tenantId));

            if (accountInfo == null) {
                markFailed(webhookEventId, "No active Bosta account for tenant");
                return;
            }

            String rawApiKey = encryptionService.decrypt(accountInfo[0]);

            // 6. Verify-by-fetch: payload is untrusted; always re-fetch authoritative state.
            BostaDelivery delivery;
            try {
                delivery = bostaGateway.fetchDelivery(rawApiKey, trackingNumber);
            } catch (BostaTransientException e) {
                log.warn("Transient Bosta fetch error for tracking {} — will retry: {}",
                    trackingNumber, e.getMessage());
                throw e;
            } catch (BostaException e) {
                markFailed(webhookEventId, "Bosta fetch error: " + e.getMessage());
                return;
            }

            if (delivery == null) {
                markFailed(webhookEventId, "Delivery not found: " + trackingNumber);
                return;
            }

            // 7. Map the FETCHED state (not the payload state).
            BostaStateMapper.MappedState mapped =
                stateMapper.map(delivery.stateCode(), delivery.type());

            if (mapped.isException()) {
                log.warn("Unknown Bosta state code {} type '{}' for tracking {} — alerting",
                    delivery.stateCode(), delivery.type(), trackingNumber);
                markFailed(webhookEventId,
                    "Unknown state code: " + delivery.stateCode() + " type: " + delivery.type());
                return;
            }

            // 8. Find shipment by tracking_number under RLS.
            //    In Mode-B the shipment row is created when we ingest from Bosta's list API
            //    and match by businessReference / phone+COD. A webhook may arrive before that
            //    ingestion has run, so "no shipment found" is a normal case, not an error.
            ShipmentRow shipment = tx.execute(s -> jdbc.query(
                "SELECT id, order_id FROM shipments WHERE tracking_number = ?",
                rs -> rs.next() ? new ShipmentRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("order_id", UUID.class)) : null,
                trackingNumber));

            if (shipment == null) {
                // 8.5 — Try to auto-match by businessReference / phone+COD (Mode B linking).
                ShipmentLinkService.LinkResult linkResult = shipmentLinkService.tryMatchDelivery(
                    tenantId, trackingNumber, delivery, mapped);

                if (linkResult.orderId() != null) {
                    // Successfully matched — re-fetch shipment for steps 9–10.
                    shipment = tx.execute(s -> jdbc.query(
                        "SELECT id, order_id FROM shipments WHERE tracking_number = ? AND tenant_id = ?",
                        rs -> rs.next() ? new ShipmentRow(
                            rs.getObject("id", UUID.class),
                            rs.getObject("order_id", UUID.class)) : null,
                        trackingNumber, tenantId));
                } else {
                    log.info("Webhook {}: tracking {} unmatched ({}) — recording as unlinked",
                        webhookEventId, trackingNumber, linkResult.unlinkedReason());
                    recordUnlinked(tenantId, trackingNumber, delivery, webhookEventId,
                                   linkResult.unlinkedReason());
                    markProcessed(webhookEventId, idemKey, "unlinked: " + trackingNumber);
                    return;
                }
            }

            // Lambda capture requires effectively-final reference.
            final ShipmentRow resolvedShipment = shipment;

            // 9. Persist updated courier state on the shipment row.
            //    For state 46 (returned_to_business), set returned_at to start the
            //    never-received detection clock (FR-12.4).
            boolean isReturnedState = "returned".equals(mapped.shipmentInternalState());
            tx.execute(s -> {
                jdbc.update("""
                    UPDATE shipments
                    SET internal_state     = ?::shipment_internal_state,
                        provider_state     = ?,
                        number_of_attempts = ?,
                        raw                = ?::jsonb,
                        last_synced_at     = now(),
                        returned_at        = CASE WHEN ? THEN now() ELSE returned_at END
                    WHERE id = ?
                    """,
                    mapped.shipmentInternalState(), delivery.stateCode(),
                    delivery.numberOfAttempts(),
                    delivery.raw().toString(), isReturnedState, resolvedShipment.id());
                return null;
            });

            // 10. Move pieces — only if the mapping carries a piece-status change.
            //     The ledger's state-machine guard is the idempotency backstop:
            //       • current==target  → fast skip, no DB write.
            //       • StateConflictException(actual==target) → concurrent worker already applied, no-op.
            //       • StateConflictException(actual!=target) → unexpected state, log + skip.
            //       • IllegalTransitionException → no legal path to target from current, log + skip.
            String targetDb = mapped.pieceStatusAfter();
            if (targetDb != null) {
                PieceStatus targetStatus = PieceStatus.fromDb(targetDb);
                String metaJson = String.format(
                    "{\"provider_state\":%d,\"order_type\":\"%s\",\"attempts\":%d}",
                    delivery.stateCode(), delivery.type(), delivery.numberOfAttempts());

                List<PieceRow> pieces = tx.execute(s -> jdbc.query("""
                    SELECT p.id, p.status::text AS status
                    FROM pieces p
                    JOIN allocations a  ON a.piece_id  = p.id
                    JOIN order_items oi ON oi.id        = a.order_item_id
                    WHERE oi.order_id = ?
                      AND a.status IN ('active', 'packed')
                    """,
                    (rs, i) -> new PieceRow(rs.getString("id"), rs.getString("status")),
                    resolvedShipment.orderId()));

                for (PieceRow pr : pieces) {
                    PieceStatus current = PieceStatus.fromDb(pr.status());

                    if (current == targetStatus) {
                        // Already at target — idempotent no-op, no DB write.
                        log.debug("Piece {} already at {}, webhook {} — skipping",
                            pr.id(), targetStatus, webhookEventId);
                        continue;
                    }

                    TransitionContext ctx = new TransitionContext(
                        shipment.orderId(), shipment.id(), null, shipment.orderId(), metaJson);

                    try {
                        ledger.transition(pr.id(), current, targetStatus, "courier_update", null, ctx);
                        log.debug("Piece {} {} → {} via webhook {}",
                            pr.id(), current, targetStatus, webhookEventId);
                    } catch (StateConflictException e) {
                        if (e.getActual() == targetStatus) {
                            // Concurrent duplicate already applied this transition.
                            log.debug("Piece {} already at {} (concurrent), webhook {} — no-op",
                                pr.id(), targetStatus, webhookEventId);
                        } else {
                            log.warn("Piece {} in unexpected state {} for target {}, webhook {} — skipping",
                                pr.id(), e.getActual(), targetStatus, webhookEventId);
                        }
                    } catch (IllegalTransitionException e) {
                        // Piece has no legal path to target from its current state.
                        // This is expected for pieces already past the target state (e.g.
                        // a stale 'with_courier' event arriving after 'delivered').
                        log.warn("Illegal transition {} → {} for piece {}, webhook {} — skipping",
                            current, targetStatus, pr.id(), webhookEventId);
                    }
                }
            }

            // 11. Mark processed + claim external_event_id AFTER all state is applied.
            //     A crash before this step leaves the event 'pending' → retry-safe.
            try {
                tx.execute(s -> {
                    jdbc.update(
                        "UPDATE webhook_events " +
                        "SET status = 'processed', external_event_id = ?, processed_at = now() " +
                        "WHERE id = ?",
                        idemKey, webhookEventId);
                    return null;
                });
            } catch (DuplicateKeyException e) {
                // Concurrent worker claimed the same idemKey first.
                log.debug("Concurrent duplicate for webhook event {} — marking processed", webhookEventId);
                tx.execute(s -> {
                    jdbc.update(
                        "UPDATE webhook_events " +
                        "SET status = 'processed', processed_at = now(), error = 'concurrent duplicate' " +
                        "WHERE id = ?",
                        webhookEventId);
                    return null;
                });
            }
        });
    }

    // ---- helpers -----------------------------------------------------------

    private void recordUnlinked(UUID tenantId, String trackingNumber,
                                 BostaDelivery delivery, long webhookEventId, String matchReason) {
        tx.execute(s -> {
            jdbc.update("""
                INSERT INTO unlinked_bosta_deliveries
                    (tenant_id, tracking_number, business_reference,
                     bosta_state_code, bosta_order_type, raw, webhook_event_id, match_reason)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
                tenantId, trackingNumber, delivery.businessReference(),
                delivery.stateCode(), delivery.type(), delivery.raw().toString(),
                webhookEventId, matchReason);
            return null;
        });
    }

    private void markFailed(long id, String error) {
        tx.execute(s -> {
            jdbc.update(
                "UPDATE webhook_events SET status = 'failed', error = ?, processed_at = now() WHERE id = ?",
                error, id);
            return null;
        });
    }

    private void markDuplicate(long id, String note) {
        tx.execute(s -> {
            jdbc.update(
                "UPDATE webhook_events " +
                "SET status = 'processed', processed_at = now(), error = ? " +
                "WHERE id = ?",
                note, id);
            return null;
        });
    }

    private void markProcessed(long id, String idemKey, String note) {
        tx.execute(s -> {
            jdbc.update(
                "UPDATE webhook_events " +
                "SET status = 'processed', external_event_id = ?, processed_at = now(), error = ? " +
                "WHERE id = ?",
                idemKey, note, id);
            return null;
        });
    }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
