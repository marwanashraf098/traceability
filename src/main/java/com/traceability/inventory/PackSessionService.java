package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * Pick &amp; Pack S3 — waybill pack sessions. Deliberately NOT @Transactional: a piece scan runs
 * in its own transaction ({@link PackSessionStore#scanPiece}) and commits; only then, when that
 * scan made the order fully scanned, complete+link runs in a SEPARATE transaction
 * ({@link PackCompleter#completeAndLink}). If that second step fails, the scan stays recorded, the
 * order stays open and claimed, and the packer gets complete_failed + "Try again"
 * ({@link #retryComplete}).
 */
@Service
public class PackSessionService {

    private static final Logger log = LoggerFactory.getLogger(PackSessionService.class);

    /**
     * status: 'scanned' (piece accepted), 'rejected' (piece refused — see scan.code),
     * 'completed' (order packed and linked — see packed), 'complete_failed' (scanned, but
     * complete+link didn't go through — failCode / failMessage; order still open).
     * order: the refreshed order card while the order is still open, else null.
     */
    public record ScanResponse(String status, FulfillService.ScanResult scan, Map<String, Object> order,
                               PackCompleter.Packed packed, String failCode, String failMessage) {}

    private final PackSessionStore store;
    private final PackCompleter    completer;

    public PackSessionService(PackSessionStore store, PackCompleter completer) {
        this.store     = store;
        this.completer = completer;
    }

    public ScanResponse scan(UUID sessionId, UUID orderId, String code, UUID userId) {
        UUID tenantId = TenantContext.require();
        FulfillService.ScanResult r = store.scanPiece(sessionId, orderId, code, userId);
        if (!r.success()) {
            return new ScanResponse("rejected", r, card(sessionId, userId, tenantId), null, null, null);
        }
        if (!r.allComplete()) {
            return new ScanResponse("scanned", r, card(sessionId, userId, tenantId), null, null, null);
        }
        return complete(sessionId, orderId, userId, tenantId, r);
    }

    /** Retry for a complete_failed order (the scan that finished it already committed). */
    public ScanResponse retryComplete(UUID sessionId, UUID orderId, UUID userId) {
        return complete(sessionId, orderId, userId, TenantContext.require(), null);
    }

    private ScanResponse complete(UUID sessionId, UUID orderId, UUID userId, UUID tenantId,
                                  FulfillService.ScanResult scan) {
        try {
            PackCompleter.Packed packed = completer.completeAndLink(sessionId, orderId, userId);
            return new ScanResponse("completed", scan, null, packed, null, null);
        } catch (PackSessionException e) {
            throw e;                                           // session-level refusal: not a complete failure
        } catch (PackCompleter.CompleteFailed e) {
            return failed(sessionId, userId, tenantId, scan, e.code(), e.getMessage());
        } catch (AwbMismatchException e) {
            return failed(sessionId, userId, tenantId, scan, "AWB_MISMATCH", e.getMessage());
        } catch (ResponseStatusException e) {
            return failed(sessionId, userId, tenantId, scan, "COMPLETE_REFUSED", e.getReason());
        } catch (RuntimeException e) {
            log.warn("pack session {}: complete+link failed for order {}: {}", sessionId, orderId, e.toString());
            return failed(sessionId, userId, tenantId, scan, "COMPLETE_ERROR", e.getMessage());
        }
    }

    private ScanResponse failed(UUID sessionId, UUID userId, UUID tenantId, FulfillService.ScanResult scan,
                                String code, String message) {
        return new ScanResponse("complete_failed", scan, card(sessionId, userId, tenantId), null, code, message);
    }

    /** The session's open order card, read fresh (read-only transaction), or null. */
    private Map<String, Object> card(UUID sessionId, UUID userId, UUID tenantId) {
        return store.view(sessionId, userId).openOrder();
    }
}
