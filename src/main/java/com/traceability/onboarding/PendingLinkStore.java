package com.traceability.onboarding;

import com.traceability.identity.AuthRepository;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyOAuthException;
import com.traceability.security.EncryptionService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Build D — pending links (shopify_pending_links, V147): an offline token the embedded app
 * exchanged for a shop no tenant owns, parked under a single-use random nonce for
 * {@value #TTL_MINUTES} minutes until a signed-in Traced owner confirms it.
 *
 * Only SHA-256(nonce) is stored; the tokens are AES-encrypted and cleared when the link is used.
 * Every statement runs in a transaction that first sets app.pending_link (SET LOCAL) to that
 * hash — the table's RLS policy shows exactly that one row, so app_user can't list or read
 * other links. Never log the nonce or the tokens.
 */
@Component
public class PendingLinkStore {

    public static final int TTL_MINUTES = 15;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EncryptionService encryption;

    public PendingLinkStore(JdbcTemplate jdbc, PlatformTransactionManager txm, EncryptionService encryption) {
        this.jdbc       = jdbc;
        this.tx         = new TransactionTemplate(txm);
        this.encryption = encryption;
    }

    /** A claimed link: the shop and the token to link it with. */
    public record Claimed(String shopDomain, ShopifyGateway.TokenResponse tokens) {}

    /** Parks {@code tokens} for {@code shop}; returns the raw nonce (URL-safe, 256 bits). */
    public String create(String shop, ShopifyGateway.TokenResponse tokens) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = AuthRepository.sha256(nonce);
        Instant now = Instant.now();
        tx.executeWithoutResult(s -> {
            bind(hash);
            jdbc.update(
                "INSERT INTO shopify_pending_links (nonce_hash, shop_domain, access_token_encrypted, " +
                "  access_token_expires_at, refresh_token_encrypted, refresh_token_expires_at, " +
                "  access_token_scopes, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                hash, shop,
                encryption.encrypt(tokens.accessToken()),
                Timestamp.from(now.plusSeconds(tokens.expiresIn())),
                tokens.refreshToken() != null ? encryption.encrypt(tokens.refreshToken()) : null,
                tokens.refreshToken() != null ? Timestamp.from(now.plusSeconds(tokens.refreshTokenExpiresIn())) : null,
                tokens.grantedScopes(),
                Timestamp.from(now.plus(Duration.ofMinutes(TTL_MINUTES))));
        });
        return nonce;
    }

    /** The shop of a live (unused, unexpired) link. @throws ShopifyOAuthException PENDING_LINK_INVALID */
    public String peekShop(String nonce) {
        String hash = hashOf(nonce);
        String shop = tx.execute(s -> {
            bind(hash);
            return jdbc.query(
                "SELECT shop_domain FROM shopify_pending_links " +
                "WHERE nonce_hash = ? AND consumed_at IS NULL AND expires_at > now()",
                rs -> rs.next() ? rs.getString(1) : null, hash);
        });
        if (shop == null) throw invalid();
        return shop;
    }

    /**
     * Uses the link — once: marks it consumed by {@code tenantId}, clears its tokens and returns
     * them (row-locked; a second claim, an expired or unknown link → PENDING_LINK_INVALID).
     */
    public Claimed claim(String nonce, UUID tenantId) {
        String hash = hashOf(nonce);
        record Row(String shop, String access, Timestamp accessExp, String refresh, Timestamp refreshExp, String scopes) {}
        Row row = tx.execute(s -> {
            bind(hash);
            return jdbc.query(
                "UPDATE shopify_pending_links p " +
                "   SET consumed_at = now(), consumed_by_tenant = ?, " +
                "       access_token_encrypted = NULL, refresh_token_encrypted = NULL " +
                "  FROM (SELECT id, access_token_encrypted, access_token_expires_at, refresh_token_encrypted, " +
                "               refresh_token_expires_at, access_token_scopes " +
                "          FROM shopify_pending_links " +
                "         WHERE nonce_hash = ? AND consumed_at IS NULL AND expires_at > now() " +
                "         FOR UPDATE) o " +
                " WHERE p.id = o.id " +
                "RETURNING p.shop_domain, o.access_token_encrypted, o.access_token_expires_at, " +
                "          o.refresh_token_encrypted, o.refresh_token_expires_at, o.access_token_scopes",
                rs -> rs.next() ? new Row(rs.getString(1), rs.getString(2), rs.getTimestamp(3),
                        rs.getString(4), rs.getTimestamp(5), rs.getString(6)) : null,
                tenantId, hash);
        });
        if (row == null || row.access() == null) throw invalid();
        Instant now = Instant.now();
        return new Claimed(row.shop(), new ShopifyGateway.TokenResponse(
            encryption.decrypt(row.access()),
            row.refresh() != null ? encryption.decrypt(row.refresh()) : null,
            secondsUntil(row.accessExp(), now),
            secondsUntil(row.refreshExp(), now),
            row.scopes()));
    }

    /** SET LOCAL app.pending_link — the RLS key that makes this one row visible in this transaction. */
    private void bind(String hash) {
        jdbc.queryForObject("SELECT set_config('app.pending_link', ?, true)", String.class, hash);
    }

    private static String hashOf(String nonce) {
        if (nonce == null || nonce.isBlank() || nonce.length() > 128) throw invalid();
        return AuthRepository.sha256(nonce.trim());
    }

    private static long secondsUntil(Timestamp t, Instant now) {
        return t == null ? 0 : Math.max(0, Duration.between(now, t.toInstant()).getSeconds());
    }

    static ShopifyOAuthException invalid() {
        return new ShopifyOAuthException(
            ShopifyOAuthException.Code.PENDING_LINK_INVALID,
            "This connection link has expired or was already used. Start again from Shopify.",
            "انتهت صلاحية رابط الربط هذا أو تم استخدامه بالفعل. ابدأ من جديد من Shopify.",
            HttpStatus.GONE);
    }
}
