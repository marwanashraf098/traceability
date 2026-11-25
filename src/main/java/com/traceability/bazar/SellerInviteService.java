package com.traceability.bazar;

import com.traceability.identity.AuthRepository;
import com.traceability.identity.JwtService;
import com.traceability.identity.MagicLinkService;
import com.traceability.integrations.shopify.ShopifyOAuthException;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bazar B10 (approved 2026-11-22) — opening a seller invite in two steps, so a link preview (WhatsApp fetches every
 * link it shows) can't use it up. The link is {@code /auth/seller-invite?t=<tenant>#token=<raw>}: the GET only sends
 * the browser to the landing page; {@link #check} reads (nothing is written), {@link #redeem} — the "Start selling"
 * button — consumes the token through hatch #6 (MagicLinkService.consumeSellerInvite, its ONE caller) and stamps
 * {@code bazar_seller_invites.redeemed_at} (V242).
 *
 * <p>The link's state has ONE definition, {@link #STATE_SQL}: used / replaced (a newer link was sent to the same
 * seller for the same event — "send again cancels the earlier link") / removed (no longer a seller of the event, or
 * the account is off) / expired / valid. Everything runs as the link's tenant under RLS, so a wrong {@code t} finds
 * nothing. Links issued before B10 carry the token in the query and no tenant ({@code legacy}): the landing page shows
 * no names and redeem consumes first, then applies the same states (a refused one has its new session revoked).
 *
 * <p>Both POSTs: the Origin must be the app's ({@code shopify.app-url}); at most {@code traced.bazar.invite-ip-max-per-hour}
 * calls per IP per hour (embedded_onboarding_attempts, bucket {@code seller-invite-ip:<HMAC>} — no PII). Every check,
 * redeem and refusal is logged with its reason, user, tenant and event — never the token or its hash.
 */
@Service
public class SellerInviteService {

    private static final Logger log = LoggerFactory.getLogger(SellerInviteService.class);

    /** The state of one invite row {@code i} (joined to its seller row {@code es} and user {@code u}). */
    public static final String STATE_SQL =
        "CASE WHEN i.redeemed_at IS NOT NULL THEN 'used' " +
        "     WHEN EXISTS (SELECT 1 FROM bazar_seller_invites n WHERE n.tenant_id = i.tenant_id " +
        "                  AND n.event_id = i.event_id AND n.user_id = i.user_id " +
        "                  AND (n.created_at > i.created_at OR (n.created_at = i.created_at AND n.id > i.id))) " +
        "          THEN 'replaced' " +
        "     WHEN es.id IS NULL OR es.removed_at IS NOT NULL OR NOT u.active THEN 'removed' " +
        "     WHEN i.expires_at <= now() THEN 'expired' " +
        "     ELSE 'valid' END";

    private static final String INVITE_SQL =
        "SELECT i.id, i.event_id, i.user_id, " + STATE_SQL + " AS state, " +
        "       t.name AS store_name, e.name AS event_name, u.name AS seller_name " +
        "FROM bazar_seller_invites i " +
        "JOIN tenants t ON t.id = i.tenant_id " +
        "JOIN bazar_events e ON e.id = i.event_id AND e.tenant_id = i.tenant_id AND e.deleted_at IS NULL " +
        "JOIN users u ON u.id = i.user_id AND u.tenant_id = i.tenant_id " +
        "LEFT JOIN bazar_event_sellers es ON es.tenant_id = i.tenant_id AND es.event_id = i.event_id " +
        "      AND es.user_id = i.user_id " +
        "WHERE i.tenant_id = ? AND i.token_hash = ?";

    private final JdbcTemplate        jdbc;
    private final TransactionTemplate tx;
    private final MagicLinkService    magicLinks;
    private final AuthRepository      authRepository;
    private final JwtService          jwtService;
    private final String              appOrigin;
    private final byte[]              ipKey;
    private final int                 ipMaxPerHour;

    public SellerInviteService(JdbcTemplate jdbc, PlatformTransactionManager txm, MagicLinkService magicLinks,
                               AuthRepository authRepository, JwtService jwtService,
                               @Value("${shopify.app-url:http://localhost:5173}") String appUrl,
                               @Value("${traced.onboarding.ip-hash-key:${shopify.client-secret}}") String ipKey,
                               @Value("${traced.bazar.invite-ip-max-per-hour:60}") int ipMaxPerHour) {
        this.jdbc           = jdbc;
        this.tx             = new TransactionTemplate(txm);
        this.magicLinks     = magicLinks;
        this.authRepository = authRepository;
        this.jwtService     = jwtService;
        this.appOrigin      = originOf(appUrl);
        this.ipKey          = ipKey.getBytes(StandardCharsets.UTF_8);
        this.ipMaxPerHour   = ipMaxPerHour;
    }

    /** Who is calling: the Origin header, the client IP, and — when the browser is signed in — its access token. */
    public record Caller(String origin, String referer, String ip, String authorization, UUID userId, UUID tenantId) {}

    /** A redeemed link: the seller's tokens and the event they land on. */
    public record Redeemed(String accessToken, String refreshToken, UUID eventId) {}

    private record Invite(UUID id, UUID eventId, UUID userId, String state,
                          String storeName, String eventName, String sellerName) {}

    // ── check ────────────────────────────────────────────────────────────────

    /** {status, store_name, event_name, seller_name} — reads only. */
    public Map<String, Object> check(String t, String token, Caller caller) {
        guard(caller);
        Map<String, Object> out = new LinkedHashMap<>();
        if (token == null || token.isBlank()) {
            // The page opened without its #token= (an app dropped the fragment): the page says the link is incomplete.
            refused("missing_token", "check", null, t == null || t.isBlank() ? null : uuid(t), null);
            out.put("status", "missing_token");
            return out;
        }
        if (t == null || t.isBlank()) {
            log.info("Seller invite checked status=legacy phase=check");
            out.put("status", "legacy");
            return out;
        }
        UUID tenantId = uuid(t);
        Invite inv = tenantId == null ? null : find(tenantId, token, false);
        if (inv == null) {
            refused("unknown", "check", null, tenantId, null);
            out.put("status", "unknown");
            return out;
        }
        if ("valid".equals(inv.state())) {
            log.info("Seller invite checked status=valid phase=check userId={} tenantId={} eventId={}",
                inv.userId(), tenantId, inv.eventId());
        } else {
            refused(inv.state(), "check", inv.userId(), tenantId, inv.eventId());
        }
        out.put("status", inv.state());
        out.put("store_name", inv.storeName());
        out.put("event_name", inv.eventName());
        out.put("seller_name", inv.sellerName());
        out.put("seller_id", inv.userId().toString());
        return out;
    }

    // ── redeem ───────────────────────────────────────────────────────────────

    /** Consumes the link (one use), ends this browser's previous session, and signs the seller in. */
    public Redeemed redeem(String t, String token, Caller caller) {
        guard(caller);
        if (token == null || token.isBlank()) {
            refused("missing_token", "redeem", null, t == null || t.isBlank() ? null : uuid(t), null);
            throw refusal("missing_token");
        }
        Redeemed r = (t == null || t.isBlank()) ? redeemLegacy(token) : redeemCurrent(t, token);
        endPreviousSession(caller);
        return r;
    }

    private Redeemed redeemCurrent(String t, String token) {
        UUID tenantId = uuid(t);
        if (tenantId == null) {
            refused("unknown", "redeem", null, null, null);
            throw refusal("unknown");
        }
        return TenantContext.runAs(tenantId, () -> tx.execute(s -> {
            // Locked: a second redeem of the same link waits here, then reads 'used'.
            Invite inv = find(tenantId, token, true);
            if (inv == null) {
                refused("unknown", "redeem", null, tenantId, null);
                throw refusal("unknown");
            }
            if (!"valid".equals(inv.state())) {
                refused(inv.state(), "redeem", inv.userId(), tenantId, inv.eventId());
                throw refusal(inv.state());
            }
            MagicLinkService.Consumed c;
            try {
                c = magicLinks.consumeSellerInvite(token);
            } catch (ShopifyOAuthException e) {
                // The row says valid, the token is gone: it was used some other way.
                refused("used", "redeem", inv.userId(), tenantId, inv.eventId());
                throw refusal("used");
            }
            if (!tenantId.equals(c.tenantId()) || !inv.userId().equals(c.userId())) {
                throw new IllegalStateException("seller invite token and row disagree");
            }
            jdbc.update("UPDATE bazar_seller_invites SET redeemed_at = now() WHERE id = ? AND tenant_id = ?",
                inv.id(), tenantId);
            log.info("Seller invite redeemed userId={} tenantId={} eventId={}", inv.userId(), tenantId, inv.eventId());
            return new Redeemed(c.tokens().accessToken(), c.tokens().refreshToken(), inv.eventId());
        }));
    }

    /** A link issued before B10 (no tenant in it): consume first, then the same states. */
    private Redeemed redeemLegacy(String token) {
        MagicLinkService.Consumed c;
        try {
            c = magicLinks.consumeSellerInvite(token);
        } catch (ShopifyOAuthException e) {
            refused("unavailable", "redeem", null, null, null);
            throw refusal("unavailable");
        }
        Invite inv = find(c.tenantId(), token, false);
        String state = inv == null ? "unknown" : inv.state();
        if (!"valid".equals(state)) {
            // Not a (live) seller invite: the session the consume just made is ended at once.
            UUID sid = jwtService.sessionIdOf("Bearer " + c.tokens().accessToken());
            TenantContext.runAs(c.tenantId(), () -> tx.executeWithoutResult(s -> {
                if (sid != null) authRepository.revokeRefreshTokenById(sid, c.userId(), "logout_device");
            }));
            refused(state, "redeem", c.userId(), c.tenantId(), inv == null ? null : inv.eventId());
            throw refusal(state);
        }
        TenantContext.runAs(c.tenantId(), () -> tx.executeWithoutResult(s -> jdbc.update(
            "UPDATE bazar_seller_invites SET redeemed_at = now() WHERE id = ? AND tenant_id = ?", inv.id(), c.tenantId())));
        log.info("Seller invite redeemed (legacy link) userId={} tenantId={} eventId={}",
            c.userId(), c.tenantId(), inv.eventId());
        return new Redeemed(c.tokens().accessToken(), c.tokens().refreshToken(), inv.eventId());
    }

    /** The browser held another session (its one refresh cookie is replaced now): that session is ended too. */
    private void endPreviousSession(Caller caller) {
        if (caller.userId() == null || caller.tenantId() == null) return;
        UUID sid = jwtService.sessionIdOf(caller.authorization());
        if (sid == null) return;
        try {
            TenantContext.runAs(caller.tenantId(), () -> tx.executeWithoutResult(s ->
                authRepository.revokeRefreshTokenById(sid, caller.userId(), "logout_device")));
            log.info("Seller invite redeemed on a signed-in browser: previous session ended userId={} tenantId={}",
                caller.userId(), caller.tenantId());
        } catch (RuntimeException e) {
            log.warn("Seller invite: couldn't end the browser's previous session: {}", e.getClass().getSimpleName());
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Invite find(UUID tenantId, String token, boolean lock) {
        String hash = AuthRepository.sha256(token.trim());
        String sql = INVITE_SQL + (lock ? " FOR UPDATE OF i" : "");
        List<Invite> rows = TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.query(sql,
            (rs, n) -> new Invite(rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("state"), rs.getString("store_name"),
                rs.getString("event_name"), rs.getString("seller_name")),
            tenantId, hash)));
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * The Origin must be the app's ({@code shopify.app-url}) — CORS alone isn't enough: it lets Shopify admin pages
     * (https://admin.shopify.com, https://*.myshopify.com) send credentialed POSTs, and a forced redeem would swap the
     * browser's session. A browser that sends no Origin is judged by its Referer's origin. Then the per-IP limit.
     */
    private void guard(Caller caller) {
        String from = caller.origin() != null && !caller.origin().isBlank() ? caller.origin().trim()
            : refererOrigin(caller.referer());
        if (from == null || !appOrigin.equalsIgnoreCase(from)) {
            log.info("Seller invite refused reason=origin");
            throw new BazarException(BazarException.Code.BAZAR_INVITE_ORIGIN,
                "Open the link from your messages to sign in.", "افتح الرابط من رسائلك لتسجيل الدخول.",
                HttpStatus.FORBIDDEN);
        }
        String bucket = "seller-invite-ip:" + hmac(caller.ip() == null ? "" : caller.ip());
        tx.executeWithoutResult(s -> {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM embedded_onboarding_attempts " +
                "WHERE bucket = ? AND attempted_at > now() - interval '1 hour'", Integer.class, bucket);
            if (n != null && n >= ipMaxPerHour) {
                log.info("Seller invite refused reason=rate_limited");
                throw new BazarException(BazarException.Code.BAZAR_INVITE_RATE_LIMITED,
                    "Too many tries — wait a little and try again.", "محاولات كثيرة — انتظر قليلًا ثم حاول مرة أخرى.",
                    HttpStatus.TOO_MANY_REQUESTS);
            }
            jdbc.update("INSERT INTO embedded_onboarding_attempts (bucket) VALUES (?)", bucket);
        });
    }

    private static void refused(String reason, String phase, UUID userId, UUID tenantId, UUID eventId) {
        log.info("Seller invite refused reason={} phase={} userId={} tenantId={} eventId={}",
            reason, phase, userId, tenantId, eventId);
    }

    static BazarException refusal(String reason) {
        String en, ar;
        switch (reason) {
            case "used" -> {
                en = "This link was already used. Ask the owner to send you a new one.";
                ar = "تم استخدام هذا الرابط من قبل. اطلب من المالك إرسال رابط جديد.";
            }
            case "expired" -> {
                en = "This link has expired. Ask the owner to send you a new one.";
                ar = "انتهت صلاحية هذا الرابط. اطلب من المالك إرسال رابط جديد.";
            }
            case "replaced" -> {
                en = "A newer link was sent to you, so this one no longer works. Use the newest link, or ask the owner for a new one.";
                ar = "تم إرسال رابط أحدث لك، لذلك لم يعد هذا الرابط يعمل. استخدم أحدث رابط أو اطلب من المالك رابطًا جديدًا.";
            }
            case "removed" -> {
                en = "You're no longer a seller at this bazar. Ask the owner if this is a mistake.";
                ar = "لم تعد بائعًا في هذا البازار. اسأل المالك إذا كان هذا خطأ.";
            }
            case "missing_token" -> {
                en = "This link is incomplete. Open it again from the message, or ask the owner for a new one.";
                ar = "هذا الرابط غير مكتمل. افتحه مرة أخرى من الرسالة، أو اطلب من المالك رابطًا جديدًا.";
            }
            default -> {
                en = "This link doesn't work. Ask the owner to send you a new one.";
                ar = "هذا الرابط لا يعمل. اطلب من المالك إرسال رابط جديد.";
            }
        }
        return new BazarException(BazarException.Code.BAZAR_INVITE_REFUSED, en, ar, HttpStatus.CONFLICT,
            Map.of("reason", reason));
    }

    private static UUID uuid(String v) {
        try {
            return UUID.fromString(v.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String refererOrigin(String referer) {
        if (referer == null || referer.isBlank()) return null;
        try {
            URI u = URI.create(referer.trim());
            return u.getScheme() == null || u.getHost() == null ? null : originOf(referer);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String originOf(String url) {
        URI u = URI.create(url.trim());
        String origin = u.getScheme() + "://" + u.getHost();
        return u.getPort() == -1 ? origin : origin + ":" + u.getPort();
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(ipKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
