package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.regex.Pattern;

/**
 * Phone as scanner, per station (Q1, V139; S6 tied a pairing to one pack session). A worker pairs
 * a phone with the TABLET they're working at by scanning a QR on it; the phone reads barcodes and
 * relays them; the tablet hands each one to whichever scanning screen is open (or answers "no
 * scanning screen open") and reports the outcome back. A pairing belongs to the tablet
 * ({@code station_device_id}, a random per-tablet id from its localStorage — a routing key, not a
 * secret) AND to the worker who made it; it survives pack sessions and screens.
 *
 * Credentials: a one-time pair code (in the QR, valid 2 min) is claimed for a device secret
 * (valid 12 h). Both are random (pair code 128-bit, device secret 256-bit), URL-safe, and only
 * their SHA-256 hashes are stored. A hash is matched to a pairing only through hatch #15
 * ({@code resolve_scan_pairing}, which also requires the worker to be an active user); everything
 * after that runs under app_user + RLS with the pairing's tenant (the phone controller sets it for
 * that request only).
 *
 * Relay: a phone scan is a {@code scan_relay_events} row (idempotent on the phone's seq). It is
 * delivered to the tablet's stream only while it is under 5 s old — claimed 'pending' →
 * 'delivered' before it is written, released back if the write fails — and an older pending
 * one is marked 'expired' and never delivered. No replay, ever.
 *
 * Revocation: unpair; a new pairing on the same tablet or by the same worker ('replaced'); worker
 * switch ({@code AuthController.pinSwitch}, and the tablet's own sign-out); the station locking
 * (back at the PIN gate); full logout; the worker deactivated (hatch #15 stops resolving); and the
 * 12 h expiry. Ending a pack session does NOT end the pairing.
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
    static final int MAX_TARGET_LENGTH = 80;
    /** V139's CHECK on station_device_id. */
    static final Pattern DEVICE_ID = Pattern.compile("^[A-Za-z0-9_-]{16,64}$");
    /** Reasons a caller may give for revoking their own pairings (DELETE /pack-sessions/pairings/mine). */
    public static final Set<String> SELF_REVOKE_REASONS = Set.of("worker_switched", "station_locked");

    public record PairingCreated(UUID pairingId, String pairUrl, Instant pairCodeExpiresAt, Instant expiresAt) {}

    /** status: none | waiting | connected | expired. */
    public record PairingStatus(String status, UUID pairingId, String deviceLabel, Instant pairCodeExpiresAt,
                                Instant claimedAt, Instant expiresAt, String reason) {
        static PairingStatus none(String reason) { return new PairingStatus("none", null, null, null, null, null, reason); }
    }

    public record Resolved(UUID tenantId, UUID pairingId) {}

    /** The phone header: the worker it's paired with and the scanning screen open on their tablet. */
    public record PhoneContext(String state, String workerName, String target, Instant expiresAt) {}

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

    // ── Tablet side (authenticated; the caller's own pairing only) ───────────

    /**
     * New pairing for this tablet and the caller. Any live pairing on the same tablet or held by
     * the same worker is revoked ('replaced') first — one per tablet, one per worker (V139).
     */
    public PairingCreated create(String deviceId, UUID userId) {
        requireDeviceId(deviceId);
        UUID tenantId = TenantContext.require();
        String pairCode = randomToken(PAIR_CODE_BYTES);
        try {
            return tx.execute(s -> {
                revokeWhere("(station_device_id = ? OR station_user_id = ?)", new Object[] { deviceId, userId },
                    tenantId, "replaced");
                Map<String, Object> row = jdbc.queryForMap(
                    "INSERT INTO scan_pairings (tenant_id, station_device_id, station_user_id, pair_code_hash, " +
                    "                           pair_code_expires_at, expires_at) " +
                    "VALUES (?, ?, ?, ?, now() + interval '" + PAIR_CODE_TTL + "', now() + interval '" + PAIRING_TTL + "') " +
                    "RETURNING id, pair_code_expires_at, expires_at",
                    tenantId, deviceId, userId, sha256(pairCode));
                return new PairingCreated((UUID) row.get("id"), appUrl + "/scan/" + pairCode,
                    instant(row.get("pair_code_expires_at")), instant(row.get("expires_at")));
            });
        } catch (DataIntegrityViolationException e) {
            // A concurrent create on the same tablet / by the same worker won the one-active index.
            throw ScanPairException.busy();
        }
    }

    /** Unpair the caller's phone on this tablet. */
    public void unpair(String deviceId, UUID userId) {
        requireDeviceId(deviceId);
        UUID tenantId = TenantContext.require();
        tx.executeWithoutResult(s -> revokeWhere("station_device_id = ? AND station_user_id = ?",
            new Object[] { deviceId, userId }, tenantId, "unpaired"));
    }

    /** The caller's pairing on this tablet ('none' when there is none — or it's another worker's). */
    public PairingStatus current(String deviceId, UUID userId) {
        requireDeviceId(deviceId);
        UUID tenantId = TenantContext.require();
        return tx.execute(s -> statusOf(liveRow(deviceId, tenantId), userId));
    }

    /**
     * The pairing this caller may stream on this tablet: the tablet's live pairing, which must be
     * the caller's own (403 PAIRING_NOT_YOURS otherwise; 409 NO_PAIRING when there is none).
     */
    public UUID requireStreamable(String deviceId, UUID userId) {
        requireDeviceId(deviceId);
        UUID tenantId = TenantContext.require();
        return tx.execute(s -> {
            Map<String, Object> row = liveRow(deviceId, tenantId);
            if (row == null) throw ScanPairException.noPairing();
            if (!userId.equals(row.get("station_user_id"))) throw ScanPairException.notYours();
            return (UUID) row.get("id");
        });
    }

    /**
     * The tablet's verdict on a delivered phone scan. Idempotent: an event that already has an
     * outcome (or expired) is left as it is. 404 for an event that isn't the caller's pairing's.
     */
    public void outcome(UUID userId, UUID eventId, String result, String message) {
        if (!"accepted".equals(result) && !"rejected".equals(result)) throw ScanPairException.badOutcome();
        UUID tenantId = TenantContext.require();
        tx.executeWithoutResult(s -> {
            int n = jdbc.update(
                "UPDATE scan_relay_events e SET status = ?, message = ?, outcome_at = now() " +
                "FROM scan_pairings p " +
                "WHERE e.id = ? AND e.tenant_id = ? AND p.id = e.pairing_id AND p.station_user_id = ? " +
                "  AND e.status IN ('pending', 'delivered')",
                result, oneLine(message), eventId, tenantId, userId);
            if (n == 0) {
                Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM scan_relay_events e JOIN scan_pairings p ON p.id = e.pairing_id " +
                    "WHERE e.id = ? AND e.tenant_id = ? AND p.station_user_id = ?",
                    Integer.class, eventId, tenantId, userId);
                if (exists == null || exists == 0) throw ScanPairException.eventNotFound();
            }
        });
    }

    /**
     * The scanning screen open on the tablet ("Pick & Pack · #1047"), shown in the phone header;
     * null / blank clears it. No-op when the caller has no live pairing on this tablet.
     */
    public void setTarget(String deviceId, UUID userId, String label) {
        requireDeviceId(deviceId);
        UUID tenantId = TenantContext.require();
        String target = oneLine(label);
        if (target != null && target.isEmpty()) target = null;
        if (target != null && target.length() > MAX_TARGET_LENGTH) target = target.substring(0, MAX_TARGET_LENGTH);
        String t = target;
        tx.executeWithoutResult(s -> jdbc.update(
            "UPDATE scan_pairings SET active_target = ?, active_target_at = now() " +
            "WHERE tenant_id = ? AND station_device_id = ? AND station_user_id = ? AND revoked_at IS NULL",
            t, tenantId, deviceId, userId));
    }

    /**
     * Every live pairing the user holds: worker switch, the tablet's sign-out, the station
     * locking, full logout.
     */
    public void revokeForUser(UUID userId, String reason) {
        UUID tenantId = TenantContext.require();
        tx.executeWithoutResult(s -> revokeWhere("station_user_id = ?", new Object[] { userId }, tenantId, reason));
    }

    // ── Relay delivery (tablet stream) ───────────────────────────────────────

    /**
     * Writes every deliverable phone scan of the pairing to its open stream, oldest first:
     * pending ones older than 5 s are expired first (never delivered); each fresh one is claimed
     * 'delivered' before the write and released to 'pending' if the write fails. Called with the
     * pairing's tenant set — from the stream request, or from the phone's scan request.
     */
    public void deliver(UUID pairingId) {
        ScanRelayHub.Subscriber sub = hub.get(pairingId);
        if (sub == null) return;
        UUID tenantId = TenantContext.require();
        if (!tenantId.equals(sub.tenantId)) return;
        synchronized (sub.lock) {
            List<RelayEvent> claimed = tx.execute(s -> {
                jdbc.update(
                    "UPDATE scan_relay_events SET status = 'expired', outcome_at = now() " +
                    "WHERE pairing_id = ? AND tenant_id = ? " +
                    "  AND status = 'pending' AND created_at <= now() - interval '" + DELIVERY_WINDOW + "'",
                    pairingId, tenantId);
                List<RelayEvent> rows = jdbc.query(
                    "UPDATE scan_relay_events e SET status = 'delivered' " +
                    "FROM scan_pairings p " +
                    "WHERE p.id = e.pairing_id AND p.id = ? AND p.revoked_at IS NULL " +
                    "  AND e.tenant_id = ? AND e.status = 'pending' " +
                    "  AND e.created_at > now() - interval '" + DELIVERY_WINDOW + "' " +
                    "RETURNING e.id, e.seq, e.code, e.created_at",
                    (rs, i) -> new RelayEvent(rs.getObject("id", UUID.class), rs.getLong("seq"),
                        rs.getString("code"), rs.getTimestamp("created_at").toInstant()),
                    pairingId, tenantId);
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

    /** The stream's first event: the pairing's status. */
    PairingStatus statusForStream(UUID pairingId) {
        UUID tenantId = TenantContext.require();
        return tx.execute(s -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + STATUS_COLUMNS + " FROM scan_pairings WHERE id = ? AND tenant_id = ? AND revoked_at IS NULL",
                pairingId, tenantId);
            return rows.isEmpty() ? PairingStatus.none(null) : statusOf(rows.get(0), null);
        });
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
        tx.executeWithoutResult(s -> {
            int n = jdbc.update(
                "UPDATE scan_pairings SET claimed_at = now(), device_secret_hash = ?, device_label = ? " +
                "WHERE id = ? AND tenant_id = ? AND claimed_at IS NULL AND revoked_at IS NULL " +
                "  AND now() < pair_code_expires_at AND now() < expires_at",
                sha256(deviceSecret), deviceLabel, r.pairingId(), tenantId);
            if (n == 0) throw ScanPairException.ended();               // lost a concurrent claim
        });
        hub.sendStatus(r.pairingId(), statusForStream(r.pairingId()));
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
        UUID eventId = tx.execute(s -> {
            List<UUID> inserted = jdbc.queryForList(
                "INSERT INTO scan_relay_events (tenant_id, pairing_id, seq, code) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (pairing_id, seq) DO NOTHING RETURNING id",
                UUID.class, tenantId, r.pairingId(), seq, code);
            return !inserted.isEmpty() ? inserted.get(0) : jdbc.queryForObject(
                "SELECT id FROM scan_relay_events WHERE pairing_id = ? AND seq = ? AND tenant_id = ?",
                UUID.class, r.pairingId(), seq, tenantId);
        });
        deliver(r.pairingId());
        return eventId;
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

    /** The phone header: whose tablet it's paired with and the scanning screen open there. Tenant is set. */
    public PhoneContext phoneStatus(Resolved r) {
        UUID tenantId = TenantContext.require();
        return tx.execute(s -> {
            Map<String, Object> p = jdbc.queryForMap(
                "SELECT sp.expires_at, sp.active_target, u.name AS worker_name " +
                "FROM scan_pairings sp " +
                "LEFT JOIN users u ON u.id = sp.station_user_id " +
                "WHERE sp.id = ? AND sp.tenant_id = ?",
                r.pairingId(), tenantId);
            return new PhoneContext("connected", (String) p.get("worker_name"), (String) p.get("active_target"),
                instant(p.get("expires_at")));
        });
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private static void requireDeviceId(String deviceId) {
        if (deviceId == null || !DEVICE_ID.matcher(deviceId).matches()) throw ScanPairException.badDevice();
    }

    private static final String STATUS_COLUMNS =
        "id, station_user_id, device_label, pair_code_expires_at, claimed_at, expires_at, " +
        "(now() >= expires_at) AS expired, (now() >= pair_code_expires_at) AS code_expired";

    /** The tablet's live (unrevoked) pairing, whoever's it is; null when none. */
    private Map<String, Object> liveRow(String deviceId, UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT " + STATUS_COLUMNS + " FROM scan_pairings " +
            "WHERE tenant_id = ? AND station_device_id = ? AND revoked_at IS NULL",
            tenantId, deviceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Revokes the live pairings matching {@code where} (+ their undelivered events); after commit
     * tells each one's stream and closes it.
     */
    private void revokeWhere(String where, Object[] args, UUID tenantId, String reason) {
        Object[] all = new Object[args.length + 2];
        all[0] = reason;
        all[1] = tenantId;
        System.arraycopy(args, 0, all, 2, args.length);
        List<UUID> revoked = jdbc.queryForList(
            "UPDATE scan_pairings SET revoked_at = now(), revoked_reason = ? " +
            "WHERE tenant_id = ? AND revoked_at IS NULL AND " + where + " RETURNING id",
            UUID.class, all);
        if (revoked.isEmpty()) return;
        for (UUID id : revoked) {
            jdbc.update(
                "UPDATE scan_relay_events SET status = 'expired', outcome_at = now() " +
                "WHERE pairing_id = ? AND tenant_id = ? AND status = 'pending'",
                id, tenantId);
        }
        PairingStatus ended = PairingStatus.none(reason);
        afterCommit(() -> revoked.forEach(id -> {
            hub.sendStatus(id, ended);
            hub.close(id);
        }));
    }

    /** A pairing row → its status; 'none' when there's no row or it isn't {@code userId}'s (null = anyone's). */
    private static PairingStatus statusOf(Map<String, Object> p, UUID userId) {
        if (p == null) return PairingStatus.none(null);
        if (userId != null && !userId.equals(p.get("station_user_id"))) return PairingStatus.none(null);
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
