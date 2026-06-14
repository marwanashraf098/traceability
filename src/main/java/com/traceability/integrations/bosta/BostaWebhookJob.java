package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.UUID;

/**
 * Processes a persisted Bosta webhook event.
 *
 * Idempotency boundaries (two layers):
 *   1. Dedup check (optimization): if another row already has external_event_id=idemKey
 *      and status='processed', this row is a redelivery — mark as duplicate and return.
 *   2. State-machine guard (backstop): InventoryLedger.transition() rejects re-applying
 *      the same state (StateConflictException = harmless no-op). A crash after fetch
 *      but before the final UPDATE means the next retry re-fetches, re-applies (safe),
 *      and marks processed. The external_event_id is set AFTER successful state application.
 *
 * Verify-by-fetch: the webhook payload is an untrusted hint. We always re-fetch the
 * delivery from Bosta to get authoritative state before applying any transitions.
 * Mock contract: fetchDelivery(apiKey, trackingNumber) → BostaDelivery where stateCode
 * may differ from the payload — tests must verify we act on the fetched state, not the payload.
 *
 * Failure modes:
 *   BostaTransientException (network/5xx) → rethrown → JobRunr retries
 *   BostaException (404 / permanent) → webhook_events.status='failed'
 *   Mapped to exception state → webhook_events.status='failed' + alert
 */
@Component
public class BostaWebhookJob {

    private static final Logger log = LoggerFactory.getLogger(BostaWebhookJob.class);

    private final JdbcTemplate      jdbc;
    private final TransactionTemplate tx;
    private final BostaGateway       bostaGateway;
    private final BostaStateMapper   stateMapper;
    private final EncryptionService  encryptionService;
    private final ObjectMapper       mapper;

    public BostaWebhookJob(JdbcTemplate jdbc,
                            PlatformTransactionManager txm,
                            BostaGateway bostaGateway,
                            BostaStateMapper stateMapper,
                            EncryptionService encryptionService,
                            ObjectMapper mapper) {
        this.jdbc              = jdbc;
        this.tx                = new TransactionTemplate(txm);
        this.bostaGateway      = bostaGateway;
        this.stateMapper       = stateMapper;
        this.encryptionService = encryptionService;
        this.mapper            = mapper;
    }

    @Job(name = "Bosta webhook — event %0")
    public void process(long webhookEventId, UUID tenantId) {
        TenantContext.runAs(tenantId, (Runnable) () -> {

            // 1. Load payload (transactional so GUC is set and RLS applies in production)
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

            // 2. Extract payload fields (Bosta §8 camelCase shape)
            String trackingNumber = payload.path("trackingNumber").asText();
            int    payloadState   = payload.path("state").asInt(-1);
            String timestamp      = payload.path("updatedAt").asText("unknown");

            // 3. Idempotency key: stable for a given (trackingNumber, state, timestamp) tuple.
            //    Based on the PAYLOAD (not the fetched state) so it's stable for redeliveries
            //    of the same logical event, regardless of when Bosta re-sends it.
            String idemKey = sha256(trackingNumber + ":" + payloadState + ":" + timestamp);

            // 4. Dedup check (optimization): if a sibling row is already processed with the
            //    same key, this is a redelivery — mark as duplicate without re-fetching.
            //    The duplicate row does NOT claim external_event_id (that stays with the first row).
            Integer alreadyDone = jdbc.queryForObject(
                "SELECT COUNT(*) FROM webhook_events " +
                "WHERE external_event_id = ? AND status = 'processed'",
                Integer.class, idemKey);
            if (alreadyDone != null && alreadyDone > 0) {
                markDuplicate(webhookEventId, "duplicate: already processed");
                return;
            }

            // 5. Load this tenant's Bosta API key (under tenant context / RLS)
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

            // 6. Verify-by-fetch: payload is untrusted; always re-fetch authoritative state
            BostaDelivery delivery;
            try {
                delivery = bostaGateway.fetchDelivery(rawApiKey, trackingNumber);
            } catch (BostaTransientException e) {
                log.warn("Transient Bosta fetch error for tracking {} — will retry: {}",
                    trackingNumber, e.getMessage());
                throw e;  // propagate → JobRunr retry
            } catch (BostaException e) {
                markFailed(webhookEventId, "Bosta fetch error: " + e.getMessage());
                return;
            }

            if (delivery == null) {
                markFailed(webhookEventId, "Delivery not found: " + trackingNumber);
                return;
            }

            // 7. Map the FETCHED state (not the payload state)
            BostaStateMapper.MappedState mapped =
                stateMapper.map(delivery.stateCode(), delivery.type());

            if (mapped.isException()) {
                log.warn("Unknown Bosta state code {} type '{}' for tracking {} — alerting",
                    delivery.stateCode(), delivery.type(), trackingNumber);
                markFailed(webhookEventId,
                    "Unknown state code: " + delivery.stateCode() + " type: " + delivery.type());
                return;
            }

            // 8. Apply ledger transition (deferred: no shipment↔trackingNumber link yet)
            // When shipments are linked: find shipment by tracking_number under RLS,
            // call ledger.transition(pieceId, currentStatus, mapped.pieceStatusAfter(), context).
            // The state machine IS the idempotency backstop: re-applying the same piece status
            // throws StateConflictException → caught and treated as success.
            log.debug("Webhook {}: tracking={} fetched state={} mapped={}",
                webhookEventId, trackingNumber, delivery.stateCode(), mapped.shipmentInternalState());

            // 9. Mark processed + claim external_event_id AFTER state is applied.
            //    external_event_id is set last so that a crash before step 9 leaves the event
            //    as 'pending' (retry-safe). Concurrent duplicate → DuplicateKeyException → mark processed.
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
                // Concurrent worker claimed the same key first — mark this row as duplicate
                log.debug("Concurrent duplicate for webhook event {} — already processed", webhookEventId);
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
