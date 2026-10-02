package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * S6 — phone as scanner (waybill mode). A worker pairs a phone with their open waybill pack
 * session by scanning a QR on the tablet; the phone reads barcodes and relays them; the tablet
 * applies each one through its own scan path and reports the outcome back.
 *
 * Credentials: a one-time pair code (in the QR, valid 2 min) is claimed for a device secret
 * (valid 12 h). Both are random (pair code 128-bit, device secret 256-bit), URL-safe, and only
 * their SHA-256 hashes are stored. A hash is matched to a pairing only through hatch #15
 * ({@code resolve_scan_pairing}); everything after that runs under app_user + RLS with the
 * pairing's tenant (the phone controller sets it for that request only).
 *
 * Relay: a phone scan is a {@code scan_relay_events} row (idempotent on the phone's seq). It is
 * delivered to the tablet's stream only while it is under 5 s old — claimed 'pending' →
 * 'delivered' before it is written, released back if the write fails — and an older pending
 * one is marked 'expired' and never delivered. No replay, ever.
 *
 * Revocation: a new pairing replaces the previous one; unpair; session end
 * ({@link PackSessionStore#end}); worker switch ({@code AuthController.pinSwitch}, and the
 * tablet's own sign-out); and the 12 h expiry (hatch #15 stops resolving it).
 */
@Service
public class ScanPairingService {

    static final int PAIR_CODE_BYTES = 16;          // 128-bit
    static final int DEVICE_SECRET_BYTES = 32;      // 256-bit
    static final String PAIR_CODE_TTL = "2 minutes";
    static final String PAIRING_TTL = "12 hours";
    static final String DELIVERY_WINDOW = "5 seconds";
    static final int MAX_SCANS_PER_SECOND = 10;
    static final int MAX_CODE_LENGTH = 200;
    static final int MAX_MESSAGE_LENGTH = 200;

    public record PairingCreated(UUID pairingId, String pairUrl, Instant pairCodeExpiresAt, Instant expiresAt) {}

    /** status: none | waiting | connected | expired. */
    public record PairingStatus(String status, UUID pairingId, String deviceLabel, Instant pairCodeExpiresAt,
                                Instant claimedAt, Instant expiresAt, String reason) {
        static PairingStatus none(String reason) { return new PairingStatus("none", null, null, null, null, null, reason); }
    }

    public record Resolved(UUID tenantId, UUID pairingId) {}

    public record OrderContext(String number, String customerName, int scanned, int required) {}

    public record PhoneContext(String state, String workerName, OrderContext order, Instant expiresAt) {}

    public record Claimed(String deviceSecret, PhoneContext context) {}

    public record RelayEvent(UUID id, long seq, String code, Instant createdAt) {}

    public record EventStatus(UUID eventId, String status, String message) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate        jdbc;
    private final TransactionTemplate tx;
    private final ScanRelayHub        hub;
    private final String              appUrl;
    private final Map<UUID, ArrayDeque<Long>> recentScans = new java.util.concurrent.ConcurrentHashMap<>();

    public ScanPairingService(JdbcTemplate jdbc, PlatformTransactionManager txm, ScanRelayHub hub,
                              @Value("${shopify.app-url:http://localhost:5173}") String appUrl) {
        this.jdbc   = jdbc;
        this.tx     = new TransactionTemplate(txm);
        this.hub    = hub;
        this.appUrl = appUrl.endsWith("/") ? appUrl.substring(0, appUrl.length() - 1) : appUrl;
    }

    // ── Tablet side (authenticated; the session owner only) ──────────────────

    /** New pairing for the caller's open waybill session; any previous one is revoked ('replaced'). */
    public PairingCreated create(UUID sessionId, UUID userId) {
        UUID tenantId = TenantContext.require();
        String pairCode = randomToken(PAIR_CODE_BYTES);
        PairingCreated created = tx.execute(s -> {
            requireOwnSession(sessionId, userId, tenantId, true);
            revokeWhere("pack_session_id = ?", sessionId, tenantId, "replaced");
            Map<String, Object> row = jdbc.queryForMap(
                "INSERT INTO scan_pairings (tenant_id, pack_session_id, station_user_id, pair_code_hash, " +
                "                           pair_code_expires_at, expires_at) " +
                "VALUES (?, ?, ?, ?, now() + interval '" + PAIR_CODE_TTL + "', now() + interval '" + PAIRING_TTL + "') " +
                "RETURNING id, pair_code_expires_at, expires_at",
                tenantId, sessionId, userId, sha256(pairCode));
            return new PairingCreated((UUID) row.get("id"), appUrl + "/scan/" + pairCode,
                instant(row.get("pair_code_expires_at")), instant(row.get("expires_at")));
        });
        hub.sendStatus(sessionId, new PairingStatus("waiting", created.pairingId(), null,
            created.pairCodeExpiresAt(), null, created.expiresAt(), null));
        return created;
    }

    /** Unpair the session's phone. */
    public void unpair(UUID sessionId, UUID userId) {
        UUID tenantId = TenantContext.require();
        tx.executeWithoutResult(s -> {
            requireOwnSession(sessionId, userId, tenantId, false);
            revokeWhere("pack_session_id = ?", sessionId, tenantId, "unpaired");
        });
    }

    public PairingStatus current(UUID sessionId, UUID userId) {
        UUID tenantId = TenantContext.require();
        return tx.execute(s -> {
            requireOwnSession(sessionId, userId, tenantId, false);
            return statusOf(sessionId, tenantId);
        });
    }

    /** Checks the caller may open this session's relay stream (own, open, waybill mode). */
    public void requireStreamable(UUID sessionId, UUID userId) {
        UUID tenantId = TenantContext.require();
        tx.executeWithoutResult(s -> requireOwnSession(sessionId, userId, tenantId, true));
    }

    /**
     * The tablet's verdict on a delivered phone scan. Idempotent: an event that already has an
     * outcome (or expired) is left as it is. 404 for an event that isn't this session's.
     */
    public void outcome(UUID sessionId, UUID userId, UUID eventId, String result, String message) {
        if (!"accepted".equals(result) && !"rejected".equals(result)) throw ScanPairException.badOutcome();
        UUID tenantId = TenantContext.require();
        tx.executeWithoutResult(s -> {
            requireOwnSession(sessionId, userId, tenantId, false);
            int n = jdbc.update(
                "UPDATE scan_relay_events e SET status = ?, message = ?, outcome_at = now() " +
                "FROM scan_pairings p " +
                "WHERE e.id = ? AND e.tenant_id = ? AND p.id = e.pairing_id AND p.pack_session_id = ? " +
                "  AND e.status IN ('pending', 'delivered')",
                result, oneLine(message), eventId, tenantId, sessionId);
            if (n == 0) {
                Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM scan_relay_events e JOIN scan_pairings p ON p.id = e.pairing_id " +
                    "WHERE e.id = ? AND e.tenant_id = ? AND p.pack_session_id = ?",
                    Integer.class, eventId, tenantId, sessionId);
                if (exists == null || exists == 0) throw ScanPairException.eventNotFound();
            }
        });
    }

    /** Session end (PackSessionStore.end, inside its transaction). */
    void revokeForSession(UUID sessionId, UUID tenantId, String reason) {
        revokeWhere("pack_session_id = ?", sessionId, tenantId, reason);
    }

    /** Worker switch: every live pairing the outgoing worker's stations hold. */
    public void revokeForUser(UUID userId, String reason) {
        UUID tenantId = TenantContext.require();
        tx.executeWithoutResult(s -> revokeWhere("station_user_id = ?", userId, tenantId, reason));
    }

    // ── Relay delivery (tablet stream) ───────────────────────────────────────

    /**
     * Writes every deliverable phone scan of the session to its open stream, oldest first:
     * pending ones older than 5 s are expired first (never delivered); each fresh one is claimed
     * 'delivered' before the write and released to 'pending' if the write fails. Called with the
     * session's tenant set — from the stream request, or from the phone's scan request.
     */
    public void deliver(UUID sessionId) {
        ScanRelayHub.Subscriber sub = hub.get(sessionId);
        if (sub == null) return;
        UUID tenantId = TenantContext.require();
        if (!tenantId.equals(sub.tenantId)) return;
        synchronized (sub.lock) {
            List<RelayEvent> claimed = tx.execute(s -> {
                jdbc.update(
                    "UPDATE scan_relay_events e SET status = 'expired', outcome_at = now() " +
                    "FROM scan_pairings p " +
                    "WHERE p.id = e.pairing_id AND p.pack_session_id = ? AND e.tenant_id = ? " +
                    "  AND e.status = 'pending' AND e.created_at <= now() - interval '" + DELIVERY_WINDOW + "'",
                    sessionId, tenantId);
                List<RelayEvent> rows = jdbc.query(
                    "UPDATE scan_relay_events e SET status = 'delivered' " +
                    "FROM scan_pairings p " +
                    "WHERE p.id = e.pairing_id AND p.pack_session_id = ? AND p.revoked_at IS NULL " +
                    "  AND e.tenant_id = ? AND e.status = 'pending' " +
                    "  AND e.created_at > now() - interval '" + DELIVERY_WINDOW + "' " +
                    "RETURNING e.id, e.seq, e.code, e.created_at",
                    (rs, i) -> new RelayEvent(rs.getObject("id", UUID.class), rs.getLong("seq"),
                        rs.getString("code"), rs.getTimestamp("created_at").toInstant()),
                    sessionId, tenantId);
                List<RelayEvent> sorted = new ArrayList<>(rows);
                sorted.sort(Comparator.comparing(RelayEvent::createdAt).thenComparingLong(RelayEvent::seq));
                return sorted;
            });
            for (int i = 0; i < claimed.size(); i++) {
                if (!hub.send(sub, "scan", claimed.get(i))) {
                    List<UUID> unsent = claimed.subList(i, claimed.size()).stream().map(RelayEvent::id).toList();
                    tx.executeWithoutResult(s -> unsent.forEach(id -> jdbc.update(
                        "UPDATE scan_relay_events SET status = 'pending' WHERE id = ? AND tenant_id = ? AND status = 'delivered'",
                        id, tenantId)));
                    return;
                }
            }
        }
    }

    /** The stream's first event: the session's pairing status. */
    PairingStatus statusForStream(UUID sessionId) {
        UUID tenantId = TenantContext.require();
        return tx.execute(s -> statusOf(sessionId, tenantId));
    }

    // ── Phone side (public; resolved through hatch #15) ──────────────────────

    /** Hatch #15. Null when the secret doesn't resolve. Runs with no tenant set. */
    public Resolved resolve(String kind, String secret) {
        if (secret == null || secret.isBlank() || secret.length() > 128) return null;
        List<Resolved> rows = jdbc.query(
            "SELECT tenant_id, pairing_id FROM resolve_scan_pairing(?, ?)",
            (rs, i) -> new Resolved(rs.getObject("tenant_id", UUID.class), rs.getObject("pairing_id", UUID.class)),
            kind, sha256(secret.trim()));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Claims the pair code (once): a fresh device secret, returned only here. Tenant is set. */
    public Claimed claim(Resolved r, String deviceLabel) {
        String deviceSecret = randomToken(DEVICE_SECRET_BYTES);
        UUID tenantId = TenantContext.require();
        UUID sessionId = tx.execute(s -> {
            List<UUID> rows = jdbc.queryForList(
                "UPDATE scan_pairings SET claimed_at = now(), device_secret_hash = ?, device_label = ? " +
                "WHERE id = ? AND tenant_id = ? AND claimed_at IS NULL AND revoked_at IS NULL " +
                "  AND now() < pair_code_expires_at AND now() < expires_at " +
                "RETURNING pack_session_id",
                UUID.class, sha256(deviceSecret), deviceLabel, r.pairingId(), tenantId);
            if (rows.isEmpty()) throw ScanPairException.ended();       // lost a concurrent claim
            return rows.get(0);
        });
        hub.sendStatus(sessionId, tx.execute(s -> statusOf(sessionId, tenantId)));
        return new Claimed(deviceSecret, phoneStatus(r));
    }

    /**
     * Records a phone scan (idempotent on the phone's seq — a retried POST returns the same
     * event) and offers it to the tablet's stream. Tenant is set.
     */
    public UUID scan(Resolved r, Long seq, String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim();
        if (seq == null || seq < 0 || code.isEmpty() || code.length() > MAX_CODE_LENGTH) throw ScanPairException.badScan();
        throttle(r.pairingId());
        UUID tenantId = TenantContext.require();
        UUID[] sessionAndEvent = tx.execute(s -> {
            UUID sessionId = jdbc.queryForObject(
                "SELECT pack_session_id FROM scan_pairings WHERE id = ? AND tenant_id = ?",
                UUID.class, r.pairingId(), tenantId);
            List<UUID> inserted = jdbc.queryForList(
                "INSERT INTO scan_relay_events (tenant_id, pairing_id, seq, code) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (pairing_id, seq) DO NOTHING RETURNING id",
                UUID.class, tenantId, r.pairingId(), seq, code);
            UUID eventId = !inserted.isEmpty() ? inserted.get(0) : jdbc.queryForObject(
                "SELECT id FROM scan_relay_events WHERE pairing_id = ? AND seq = ? AND tenant_id = ?",
                UUID.class, r.pairingId(), seq, tenantId);
            return new UUID[] { sessionId, eventId };
        });
        deliver(sessionAndEvent[0]);
        return sessionAndEvent[1];
    }

    /** The phone's poll: a pending event past the delivery window is expired here too. */
    public EventStatus eventStatus(Resolved r, UUID eventId) {
        UUID tenantId = TenantContext.require();
        return tx.execute(s -> {
            jdbc.update(
                "UPDATE scan_relay_events SET status = 'expired', outcome_at = now() " +
                "WHERE id = ? AND pairing_id = ? AND tenant_id = ? AND status = 'pending' " +
                "  AND created_at <= now() - interval '" + DELIVERY_WINDOW + "'",
                eventId, r.pairingId(), tenantId);
            List<EventStatus> rows = jdbc.query(
                "SELECT id, status, message FROM scan_relay_events WHERE id = ? AND pairing_id = ? AND tenant_id = ?",
                (rs, i) -> new EventStatus(rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("message")),
                eventId, r.pairingId(), tenantId);
            if (rows.isEmpty()) throw ScanPairException.eventNotFound();
            return rows.get(0);
        });
    }

    /** The phone header: who it's connected to and the order open at the station. Tenant is set. */
    public PhoneContext phoneStatus(Resolved r) {
        UUID tenantId = TenantContext.require();
        return tx.execute(s -> {
            Map<String, Object> p = jdbc.queryForMap(
                "SELECT sp.expires_at, u.name AS worker_name, ps.current_order_id " +
                "FROM scan_pairings sp " +
                "JOIN pack_sessions ps ON ps.id = sp.pack_session_id AND ps.tenant_id = sp.tenant_id " +
                "LEFT JOIN users u ON u.id = sp.station_user_id " +
                "WHERE sp.id = ? AND sp.tenant_id = ?",
                r.pairingId(), tenantId);
            UUID orderId = (UUID) p.get("current_order_id");
            OrderContext order = null;
            if (orderId != null) {
                order = jdbc.query(
                    "SELECT o.number, o.customer_name, " +
                    "       COALESCE((SELECT SUM(oi.quantity) FROM order_items oi WHERE oi.order_id = o.id), 0) AS required, " +
                    "       COALESCE((SELECT COUNT(*) FROM allocations a JOIN order_items oi2 ON oi2.id = a.order_item_id " +
                    "                 WHERE oi2.order_id = o.id AND a.status IN ('active', 'packed')), 0) AS scanned " +
                    "FROM orders o WHERE o.id = ? AND o.tenant_id = ?",
                    rs -> rs.next() ? new OrderContext(rs.getString("number"), rs.getString("customer_name"),
                        rs.getInt("scanned"), rs.getInt("required")) : null,
                    orderId, tenantId);
            }
            return new PhoneContext("connected", (String) p.get("worker_name"), order, instant(p.get("expires_at")));
        });
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private void requireOwnSession(UUID sessionId, UUID userId, UUID tenantId, boolean requireOpen) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT user_id, status, mode FROM pack_sessions WHERE id = ? AND tenant_id = ?", sessionId, tenantId);
        if (rows.isEmpty()) throw PackSessionException.notFound();
        Map<String, Object> s = rows.get(0);
        if (!userId.equals(s.get("user_id"))) throw PackSessionException.notYours();
        if (requireOpen && !"open".equals(s.get("status"))) throw PackSessionException.ended();
        if (!"waybill_scan".equals(s.get("mode"))) throw PackSessionException.modeNotWaybill();
    }

    /** Revokes the live pairings matching {@code where} (+ their undelivered events); tells the streams. */
    private void revokeWhere(String where, UUID arg, UUID tenantId, String reason) {
        List<UUID[]> revoked = jdbc.query(
            "UPDATE scan_pairings SET revoked_at = now(), revoked_reason = ? " +
            "WHERE tenant_id = ? AND revoked_at IS NULL AND " + where + " RETURNING id, pack_session_id",
            (rs, i) -> new UUID[] { rs.getObject("id", UUID.class), rs.getObject("pack_session_id", UUID.class) },
            reason, tenantId, arg);
        if (revoked.isEmpty()) return;
        for (UUID[] p : revoked) {
            jdbc.update(
                "UPDATE scan_relay_events SET status = 'expired', outcome_at = now() " +
                "WHERE pairing_id = ? AND tenant_id = ? AND status = 'pending'",
                p[0], tenantId);
        }
        List<UUID> sessions = revoked.stream().map(p -> p[1]).distinct().toList();
        PairingStatus ended = PairingStatus.none(reason);
        afterCommit(() -> sessions.forEach(sid -> hub.sendStatus(sid, ended)));
    }

    private PairingStatus statusOf(UUID sessionId, UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, device_label, pair_code_expires_at, claimed_at, expires_at, " +
            "       (now() >= expires_at) AS expired, (now() >= pair_code_expires_at) AS code_expired " +
            "FROM scan_pairings WHERE pack_session_id = ? AND tenant_id = ? AND revoked_at IS NULL",
            sessionId, tenantId);
        if (rows.isEmpty()) return PairingStatus.none(null);
        Map<String, Object> p = rows.get(0);
        boolean claimed = p.get("claimed_at") != null;
        String status = Boolean.TRUE.equals(p.get("expired")) ? "expired"
            : claimed ? "connected"
            : Boolean.TRUE.equals(p.get("code_expired")) ? "expired" : "waiting";
        return new PairingStatus(status, (UUID) p.get("id"), (String) p.get("device_label"),
            instant(p.get("pair_code_expires_at")), instant(p.get("claimed_at")), instant(p.get("expires_at")), null);
    }

    private void throttle(UUID pairingId) {
        long now = System.nanoTime();
        ArrayDeque<Long> window = recentScans.computeIfAbsent(pairingId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= 1_000_000_000L) window.pollFirst();
            if (window.size() >= MAX_SCANS_PER_SECOND) throw ScanPairException.tooFast();
            window.addLast(now);
        }
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { r.run(); }
            });
        } else {
            r.run();
        }
    }

    static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** "iPhone · Safari", "Android · Chrome", … — a short label for the tablet's chip. */
    static String deviceLabel(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "Phone";
        String ua = userAgent;
        String device = ua.contains("iPhone") ? "iPhone" : ua.contains("iPad") ? "iPad"
            : ua.contains("Android") ? "Android" : ua.contains("Macintosh") ? "Mac"
            : ua.contains("Windows") ? "Windows" : "Phone";
        String browser = ua.contains("EdgA/") || ua.contains("Edg/") ? "Edge"
            : ua.contains("SamsungBrowser") ? "Samsung Internet"
            : ua.contains("CriOS") || (ua.contains("Chrome/") && !ua.contains("Chromium")) ? "Chrome"
            : ua.contains("FxiOS") || ua.contains("Firefox/") ? "Firefox"
            : ua.contains("Safari/") ? "Safari" : null;
        return browser == null ? device : device + " · " + browser;
    }

    private static String oneLine(String message) {
        if (message == null) return null;
        String m = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return m.length() > MAX_MESSAGE_LENGTH ? m.substring(0, MAX_MESSAGE_LENGTH) : m;
    }

    private static Instant instant(Object ts) {
        return ts == null ? null : ((Timestamp) ts).toInstant();
    }
}
