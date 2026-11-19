package com.traceability.bazar;

import com.traceability.account.AuditService;
import com.traceability.account.UserService;
import com.traceability.identity.AuthRepository;
import com.traceability.identity.MagicLinkService;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bazar POS B6b — seller invites (approved 2026-11-19). The ONE writer of bazar_event_sellers / bazar_seller_invites.
 *
 * <ul>
 *   <li>Owner / manager only: list, candidates, add (a NEW worker from name + optional phone, or an existing active
 *       WORKER — never an owner / manager: the request carries no role, an existing non-worker is refused
 *       BAZAR_NOT_A_WORKER), a new link, remove, allow workers on the event.</li>
 *   <li>Each link: {@link MagicLinkService#issueSellerInviteLink} (24 h, single use, bound to that user + tenant),
 *       one bazar_seller_invites row, one audit_log row {@code bazar_seller_invite} (who invited whom, when);
 *       at most {@link #INVITES_PER_HOUR} links per tenant per hour (BAZAR_INVITE_RATE_LIMITED).</li>
 *   <li>Remove: removed_at stamped, every refresh token of the user revoked (the "log out of all devices" write),
 *       audit_log {@code bazar_seller_remove} with its outcome; the sell side refuses them ({@link BazarSellers}).
 *       A worker "Add seller" CREATED, leaving their last event that hasn't ended, is turned off through
 *       UserService.deactivate ({@code account_turned_off}); a picked team member stays active
 *       ({@code removed_from_event}).</li>
 *   <li>The seller's own: {@link #me} (the first-open screen), {@link #setPin} (once — then the PIN switch needs it),
 *       {@link #roster} (Switch seller: this event's sellers who have a PIN).</li>
 * </ul>
 */
@Service
public class BazarSellerService {

    public static final int INVITES_PER_HOUR = 30;

    private final JdbcTemplate        jdbc;
    private final MagicLinkService    magicLinks;
    private final AuthRepository      authRepository;
    private final AuditService        audit;
    private final PasswordEncoder     encoder;
    private final TransactionTemplate tx;
    private final UserService         users;

    public BazarSellerService(JdbcTemplate jdbc, MagicLinkService magicLinks, AuthRepository authRepository,
                              AuditService audit, PasswordEncoder encoder, PlatformTransactionManager txm,
                              UserService users) {
        this.jdbc           = jdbc;
        this.magicLinks     = magicLinks;
        this.authRepository = authRepository;
        this.audit          = audit;
        this.encoder        = encoder;
        this.tx             = new TransactionTemplate(txm);
        this.users          = users;
    }

    /**
     * B6b review — would removing this seller from this event turn their account off: "Add seller" created them
     * (a created_user row) AND they aren't a seller of any OTHER event that hasn't ended. Params: tenant, event, user.
     */
    static final String TURNS_OFF_SQL =
        "EXISTS (SELECT 1 FROM bazar_event_sellers c WHERE c.tenant_id = ? AND c.user_id = u.id AND c.created_user) " +
        "AND NOT EXISTS (SELECT 1 FROM bazar_event_sellers o JOIN bazar_events oe ON oe.id = o.event_id " +
        "  WHERE o.user_id = u.id AND o.event_id <> ? AND o.removed_at IS NULL AND oe.status <> 'ended')";

    public record AddSeller(UUID userId, String name, String phone) {}

    // ── Owner / manager ──────────────────────────────────────────────────────

    /** {workers_allowed, event_name, event_status, sellers [{user_id, name, phone, has_pin, added_at, last_invited_at, invite_expires_at}]}. */
    @Transactional(readOnly = true)
    public Map<String, Object> list(UUID eventId, BazarSaleService.Actor actor) {
        UUID tenantId = BazarEndService.requireManagerTenant(jdbc, actor);
        Map<String, Object> ev = event(tenantId, eventId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("event_name", ev.get("name"));
        out.put("event_status", ev.get("status"));
        out.put("workers_allowed", workersAllowed(ev));
        out.put("sellers", jdbc.queryForList(
            "SELECT u.id AS user_id, u.name, u.phone, (u.pin_code IS NOT NULL) AS has_pin, s.added_at, " +
            "       i.created_at AS last_invited_at, i.expires_at AS invite_expires_at, " +
            "       (" + TURNS_OFF_SQL + ") AS remove_turns_off_account " +
            "FROM bazar_event_sellers s JOIN users u ON u.id = s.user_id AND u.tenant_id = s.tenant_id " +
            "LEFT JOIN LATERAL (SELECT created_at, expires_at FROM bazar_seller_invites bi " +
            "  WHERE bi.event_id = s.event_id AND bi.user_id = s.user_id ORDER BY created_at DESC, id DESC LIMIT 1) i ON true " +
            "WHERE s.tenant_id = ? AND s.event_id = ? AND s.removed_at IS NULL AND u.active " +
            "ORDER BY s.added_at, u.name", tenantId, eventId, tenantId, eventId));
        return out;
    }

    /** Active workers of the tenant who aren't sellers of this event yet (Add seller → "pick an existing worker"). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> candidates(UUID eventId, BazarSaleService.Actor actor) {
        UUID tenantId = BazarEndService.requireManagerTenant(jdbc, actor);
        event(tenantId, eventId);
        return jdbc.queryForList(
            "SELECT u.id AS user_id, u.name, u.phone, (u.pin_code IS NOT NULL) AS has_pin FROM users u " +
            "WHERE u.tenant_id = ? AND u.active AND u.role = 'worker' AND NOT EXISTS (SELECT 1 FROM bazar_event_sellers s " +
            "  WHERE s.event_id = ? AND s.user_id = u.id AND s.removed_at IS NULL) ORDER BY u.name",
            tenantId, eventId);
    }

    /** Adds a seller (new worker or an existing one) and issues their first link: {seller, link, expires_at, workers_allowed}. */
    @Transactional
    public Map<String, Object> add(UUID eventId, AddSeller req, BazarSaleService.Actor actor) {
        UUID tenantId = BazarEndService.requireManagerTenant(jdbc, actor);
        Map<String, Object> ev = openEvent(tenantId, eventId);
        rateLimit(tenantId);

        UUID userId;
        if (req != null && req.userId() != null) {
            List<String> role = jdbc.queryForList(
                "SELECT role::text FROM users WHERE id = ? AND tenant_id = ? AND active", String.class,
                req.userId(), tenantId);
            if (role.isEmpty()) throw sellerNotFound();
            if (!"worker".equals(role.get(0))) {
                throw new BazarException(BazarException.Code.BAZAR_NOT_A_WORKER,
                    "Only workers can be invited as sellers — owners and managers sign in with their own account.",
                    "يمكن دعوة العاملين فقط كبائعين — المالك والمدير يسجلان الدخول بحسابهما.", HttpStatus.CONFLICT);
            }
            userId = req.userId();
        } else {
            String name = req == null || req.name() == null ? "" : req.name().trim();
            if (name.isEmpty() || name.length() > 80) {
                throw invalid("Enter the seller's name (up to 80 characters).", "أدخل اسم البائع (حتى 80 حرفًا).");
            }
            String phone = null;
            if (req.phone() != null && !req.phone().isBlank()) {
                phone = BazarSaleService.normalizePhone(req.phone());
                if (phone == null) {
                    throw new BazarException(BazarException.Code.BAZAR_INVALID_PHONE,
                        "That phone number doesn't look right.", "رقم الهاتف غير صحيح.", HttpStatus.BAD_REQUEST);
                }
            }
            userId = UUID.randomUUID();
            // Always a WORKER, with no PIN yet: they pick one when they open the link (setPin).
            jdbc.update("INSERT INTO users (id, tenant_id, name, phone, role) VALUES (?, ?, ?, ?, 'worker'::user_role)",
                userId, tenantId, name, phone);
            audit.record(actor.userId(), "user_create", "user", userId.toString(),
                Map.of("name", name, "role", "worker", "source", "bazar_seller_invite", "event_id", eventId.toString()));
        }

        boolean created = req == null || req.userId() == null;
        jdbc.update(
            "INSERT INTO bazar_event_sellers (tenant_id, event_id, user_id, added_by, created_user) VALUES (?, ?, ?, ?, ?) " +
            "ON CONFLICT (event_id, user_id) DO UPDATE SET removed_at = NULL, removed_by = NULL, " +
            "  added_by = EXCLUDED.added_by, added_at = now()",
            tenantId, eventId, userId, actor.userId(), created);

        MagicLinkService.SellerInviteLink link = invite(tenantId, eventId, userId, actor);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seller", sellerRow(tenantId, eventId, userId));
        out.put("link", link.url());
        out.put("expires_at", link.expiresAt().toString());
        out.put("event_name", ev.get("name"));
        out.put("workers_allowed", workersAllowed(ev));
        return out;
    }

    /** A fresh link for a seller of this event (Copy link / Send on WhatsApp again): {link, expires_at, event_name}. */
    @Transactional
    public Map<String, Object> newLink(UUID eventId, UUID userId, BazarSaleService.Actor actor) {
        UUID tenantId = BazarEndService.requireManagerTenant(jdbc, actor);
        Map<String, Object> ev = openEvent(tenantId, eventId);
        requireActiveSeller(tenantId, eventId, userId);
        rateLimit(tenantId);
        MagicLinkService.SellerInviteLink link = invite(tenantId, eventId, userId, actor);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("link", link.url());
        out.put("expires_at", link.expiresAt().toString());
        out.put("event_name", ev.get("name"));
        return out;
    }

    /** Removes a seller from this event and signs them out everywhere: {sessions_revoked}. */
    @Transactional
    public Map<String, Object> remove(UUID eventId, UUID userId, BazarSaleService.Actor actor) {
        UUID tenantId = BazarEndService.requireManagerTenant(jdbc, actor);
        event(tenantId, eventId);
        // Decided BEFORE this event's row is marked removed (TURNS_OFF_SQL looks at the OTHER events only anyway).
        Boolean turnsOff = jdbc.queryForObject(
            "SELECT " + TURNS_OFF_SQL + " FROM users u WHERE u.id = ? AND u.tenant_id = ? AND u.active",
            Boolean.class, tenantId, eventId, userId, tenantId);
        int n = jdbc.update(
            "UPDATE bazar_event_sellers SET removed_at = now(), removed_by = ? " +
            "WHERE tenant_id = ? AND event_id = ? AND user_id = ? AND removed_at IS NULL",
            actor.userId(), tenantId, eventId, userId);
        if (n == 0) throw sellerNotFound();
        int revoked = authRepository.revokeAllRefreshTokens(userId);
        // A worker "Add seller" created, leaving their last bazar that hasn't ended → their account is turned off
        // through the EXISTING Team settings path (active = false + its user_deactivate audit row); the refresh
        // tokens were just revoked and /auth/pin, /auth/refresh and login refuse an inactive user. A team member
        // picked from the list only leaves this event.
        boolean off = Boolean.TRUE.equals(turnsOff);
        if (off) users.deactivate(actor.userId(), actor.role(), userId);
        String outcome = off ? "account_turned_off" : "removed_from_event";
        audit.record(actor.userId(), "bazar_seller_remove", "user", userId.toString(),
            Map.of("event_id", eventId.toString(), "sessions_revoked", revoked, "outcome", outcome));
        return Map.of("sessions_revoked", revoked, "outcome", outcome);
    }

    /** Adds 'worker' to the event's seller_roles ("Allow workers to sell"); idempotent. */
    @Transactional
    public Map<String, Object> allowWorkers(UUID eventId, BazarSaleService.Actor actor) {
        UUID tenantId = BazarEndService.requireManagerTenant(jdbc, actor);
        openEvent(tenantId, eventId);
        int n = jdbc.update(
            "UPDATE bazar_events SET seller_roles = array_append(seller_roles, 'worker') " +
            "WHERE id = ? AND tenant_id = ? AND NOT ('worker' = ANY (seller_roles))", eventId, tenantId);
        if (n > 0) {
            audit.record(actor.userId(), "bazar_allow_workers", "bazar_event", eventId.toString(), null);
        }
        return Map.of("workers_allowed", true);
    }

    // ── The seller ───────────────────────────────────────────────────────────

    /** The first-open screen: {event_id, event_name, event_status, name, needs_pin, can_sell}. Any signed-in user. */
    @Transactional(readOnly = true)
    public Map<String, Object> me(UUID eventId, BazarSaleService.Actor actor) {
        UUID tenantId = BazarEndService.requireEnabledTenant(jdbc);
        Map<String, Object> ev = event(tenantId, eventId);
        Map<String, Object> u = jdbc.queryForMap(
            "SELECT name, role::text AS role, (pin_code IS NULL) AS no_pin FROM users WHERE id = ? AND tenant_id = ?",
            actor.userId(), tenantId);
        boolean worker = "worker".equals(u.get("role"));
        boolean roleOk = BazarSaleService.toList(ev.get("seller_roles")).contains(actor.roleKey());
        boolean removed = BazarSellers.removed(jdbc, eventId, actor.userId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("event_id", eventId);
        out.put("event_name", ev.get("name"));
        out.put("event_status", ev.get("status"));
        out.put("name", u.get("name"));
        out.put("needs_pin", worker && Boolean.TRUE.equals(u.get("no_pin")) && !removed);
        out.put("removed", removed);
        out.put("can_sell", roleOk && !removed && "live".equals(ev.get("status")));
        return out;
    }

    /**
     * The invited worker picks their 4-digit PIN — ONCE (pin_code was NULL); afterwards it can only be used, through
     * the existing PIN switch (/auth/pin). Only a worker who is a (not removed) seller of some bazar event of the
     * tenant. A normal app_user UPDATE under RLS — no hatch.
     */
    @Transactional
    public Map<String, Object> setPin(String pin, BazarSaleService.Actor actor) {
        UUID tenantId = BazarEndService.requireEnabledTenant(jdbc);
        if (pin == null || !pin.matches("\\d{4}")) {
            throw new BazarException(BazarException.Code.BAZAR_PIN_INVALID,
                "Pick a 4-digit PIN.", "اختر رقمًا سريًا من 4 أرقام.", HttpStatus.BAD_REQUEST);
        }
        Boolean seller = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM bazar_event_sellers WHERE tenant_id = ? AND user_id = ? AND removed_at IS NULL)",
            Boolean.class, tenantId, actor.userId());
        if (!Boolean.TRUE.equals(seller)) throw sellerNotFound();
        int n = jdbc.update(
            "UPDATE users SET pin_code = ?, pin_fail_count = 0, pin_locked_until = NULL " +
            "WHERE id = ? AND tenant_id = ? AND role = 'worker' AND active AND pin_code IS NULL",
            encoder.encode(pin), actor.userId(), tenantId);
        if (n == 0) {
            throw new BazarException(BazarException.Code.BAZAR_PIN_ALREADY_SET,
                "Your PIN is already set — ask the owner if you forgot it.",
                "رقمك السري محدد بالفعل — اسأل المالك إذا نسيته.", HttpStatus.CONFLICT);
        }
        audit.record(actor.userId(), "pin_set", "user", actor.userId().toString(), Map.of("source", "bazar_seller_invite"));
        return Map.of("pin_set", true);
    }

    /** Switch seller: this event's sellers who have a PIN [{id, name, locked, lockedUntil}]; any seller of the event. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> roster(UUID eventId, BazarSaleService.Actor actor) {
        UUID tenantId = BazarEndService.requireEnabledTenant(jdbc);
        Map<String, Object> ev = event(tenantId, eventId);
        boolean manages = actor.role() != null && List.of("owner", "manager").contains(actor.roleKey());
        if (!manages && (!BazarSaleService.toList(ev.get("seller_roles")).contains(actor.roleKey())
                || BazarSellers.removed(jdbc, eventId, actor.userId()))) {
            throw new BazarException(BazarException.Code.BAZAR_NOT_A_SELLER,
                "Your role can't do this at this bazar.", "دورك لا يسمح بذلك في هذا البازار.", HttpStatus.FORBIDDEN);
        }
        return jdbc.queryForList(
            "SELECT u.id, u.name, " +
            "       (u.pin_locked_until IS NOT NULL AND u.pin_locked_until > now()) AS locked, " +
            "       CASE WHEN u.pin_locked_until > now() THEN u.pin_locked_until END AS \"lockedUntil\" " +
            "FROM bazar_event_sellers s JOIN users u ON u.id = s.user_id AND u.tenant_id = s.tenant_id " +
            "WHERE s.tenant_id = ? AND s.event_id = ? AND s.removed_at IS NULL AND u.active AND u.pin_code IS NOT NULL " +
            "ORDER BY u.name", tenantId, eventId);
    }

    // ── Opening the link (SellerInviteController) ───────────────────────────

    /** Where an opened invite lands: its event, or null (not an invite / the seller was removed — {@code removed}). */
    public record Landing(UUID eventId, boolean removed) {}

    /** Runs under the link's tenant, after hatch #6 consumed it. */
    public Landing landing(UUID tenantId, UUID userId, String tokenHash) {
        return TenantContext.runAs(tenantId, () -> tx.execute(s -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT i.event_id, (es.removed_at IS NOT NULL OR es.id IS NULL) AS removed " +
                "FROM bazar_seller_invites i LEFT JOIN bazar_event_sellers es " +
                "  ON es.event_id = i.event_id AND es.user_id = i.user_id " +
                "WHERE i.tenant_id = ? AND i.user_id = ? AND i.token_hash = ?", tenantId, userId, tokenHash);
            if (rows.isEmpty()) return null;
            boolean removed = Boolean.TRUE.equals(rows.get(0).get("removed"));
            if (removed) authRepository.revokeAllRefreshTokens(userId);
            return new Landing((UUID) rows.get(0).get("event_id"), removed);
        }));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private MagicLinkService.SellerInviteLink invite(UUID tenantId, UUID eventId, UUID userId,
                                                     BazarSaleService.Actor actor) {
        MagicLinkService.SellerInviteLink link = magicLinks.issueSellerInviteLink(userId, tenantId);
        jdbc.update(
            "INSERT INTO bazar_seller_invites (tenant_id, event_id, user_id, invited_by, token_hash, expires_at) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            tenantId, eventId, userId, actor.userId(), link.tokenHash(), Timestamp.from(link.expiresAt()));
        audit.record(actor.userId(), "bazar_seller_invite", "user", userId.toString(),
            Map.of("event_id", eventId.toString(), "invited_by", actor.userId().toString(),
                   "expires_at", link.expiresAt().toString()));
        return link;
    }

    /** At most INVITES_PER_HOUR links per tenant per rolling hour; serialised per tenant. */
    private void rateLimit(UUID tenantId) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", tenantId + ":bazar-invites");
        Integer n = jdbc.queryForObject(
            "SELECT count(*) FROM bazar_seller_invites WHERE tenant_id = ? AND created_at > now() - interval '1 hour'",
            Integer.class, tenantId);
        if (n != null && n >= INVITES_PER_HOUR) {
            throw new BazarException(BazarException.Code.BAZAR_INVITE_RATE_LIMITED,
                "Too many seller links in the last hour — try again later.",
                "روابط بائعين كثيرة في الساعة الأخيرة — حاول لاحقًا.", HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    private Map<String, Object> sellerRow(UUID tenantId, UUID eventId, UUID userId) {
        return jdbc.queryForMap(
            "SELECT u.id AS user_id, u.name, u.phone, (u.pin_code IS NOT NULL) AS has_pin, s.added_at " +
            "FROM bazar_event_sellers s JOIN users u ON u.id = s.user_id AND u.tenant_id = s.tenant_id " +
            "WHERE s.tenant_id = ? AND s.event_id = ? AND s.user_id = ?", tenantId, eventId, userId);
    }

    private void requireActiveSeller(UUID tenantId, UUID eventId, UUID userId) {
        Boolean ok = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM bazar_event_sellers s JOIN users u ON u.id = s.user_id AND u.active " +
            "WHERE s.tenant_id = ? AND s.event_id = ? AND s.user_id = ? AND s.removed_at IS NULL)",
            Boolean.class, tenantId, eventId, userId);
        if (!Boolean.TRUE.equals(ok)) throw sellerNotFound();
    }

    private Map<String, Object> event(UUID tenantId, UUID eventId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, name, status, seller_roles FROM bazar_events WHERE id = ? AND tenant_id = ?", eventId, tenantId);
        if (rows.isEmpty()) {
            throw new BazarException(BazarException.Code.BAZAR_EVENT_NOT_FOUND,
                "Bazar not found.", "لم يتم العثور على البازار.", HttpStatus.NOT_FOUND);
        }
        return rows.get(0);
    }

    /** The event, not ended (sellers are added / invited only before or during it). */
    private Map<String, Object> openEvent(UUID tenantId, UUID eventId) {
        Map<String, Object> ev = event(tenantId, eventId);
        if ("ended".equals(ev.get("status"))) {
            throw new BazarException(BazarException.Code.BAZAR_EVENT_ENDED,
                "This bazar has ended — selling, returns, exchanges and edits are closed.",
                "انتهى هذا البازار — البيع والإرجاع والاستبدال والتعديل مغلقة.", HttpStatus.CONFLICT);
        }
        return ev;
    }

    private static boolean workersAllowed(Map<String, Object> ev) {
        return BazarSaleService.toList(ev.get("seller_roles")).contains("worker");
    }

    private static BazarException sellerNotFound() {
        return new BazarException(BazarException.Code.BAZAR_SELLER_NOT_FOUND,
            "Seller not found.", "لم يتم العثور على البائع.", HttpStatus.NOT_FOUND);
    }

    private static BazarException invalid(String en, String ar) {
        return new BazarException(BazarException.Code.BAZAR_INVALID_INPUT, en, ar, HttpStatus.BAD_REQUEST);
    }
}
