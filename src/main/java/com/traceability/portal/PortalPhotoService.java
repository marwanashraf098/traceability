package com.traceability.portal;

import com.traceability.assets.BinaryAssetStore;
import com.traceability.assets.ImagePipeline;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Returns portal P3 — customer photos of returned items.
 *
 * Upload (PUBLIC): hatch #14 resolves the slug, the lookup token must verify for that tenant, and
 * the photo is tied to the token's order — there are no anonymous uploads to any tenant. The file
 * goes through {@link ImagePipeline} (PHOTO: JPEG, ≤ 1600 px, orientation applied, no metadata)
 * BEFORE any transaction; then, under TenantContext.runAs + RLS, the limits are checked and the
 * bytes stored through {@link BinaryAssetStore}. The submission claims photos ({@link ReturnPhotos}).
 * Never logs content.
 */
@Service
public class PortalPhotoService {

    /** The app's own cap per file (multipart's 8 MB / 10 MB are the outer ceiling). */
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    /** Uploads per order within one lookup token's life (PortalTokenService.TTL = 30 min). */
    static final int PER_ORDER_MAX = 30;
    static final int PER_ORDER_WINDOW_MINUTES = 30;
    /** Uploads per tenant per hour — a ceiling on the whole public endpoint for one store. */
    static final int PER_TENANT_HOURLY_MAX = 600;

    public enum Outcome { CREATED, UNAUTHORIZED, INVALID, THROTTLED }

    /** {@code code}: PHOTO_REQUIRED | PHOTO_TOO_LARGE | PHOTO_TYPE | PHOTO_PIXELS | PHOTO_UNREADABLE for INVALID. */
    public record UploadResult(Outcome outcome, String code, UUID photoId, int width, int height) {
        static UploadResult of(Outcome o) { return new UploadResult(o, null, null, 0, 0); }
        static UploadResult invalid(String code) { return new UploadResult(Outcome.INVALID, code, null, 0, 0); }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PortalTokenService tokens;
    private final BinaryAssetStore store;
    private final ImagePipeline pipeline;

    public PortalPhotoService(JdbcTemplate jdbc, PlatformTransactionManager txm, PortalTokenService tokens,
                              BinaryAssetStore store, ImagePipeline pipeline) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.tokens = tokens;
        this.store = store;
        this.pipeline = pipeline;
    }

    /** Empty for an unknown or disabled slug. */
    public Optional<UploadResult> upload(String slug, String bearerToken, byte[] file) {
        if (slug == null || slug.isBlank()) return Optional.empty();
        UUID tenantId = jdbc.queryForObject("SELECT resolve_tenant_by_portal_slug(?)", UUID.class, slug);
        if (tenantId == null) return Optional.empty();
        Optional<PortalTokenService.Claims> claims = tokens.verify(bearerToken, tenantId);
        if (claims.isEmpty()) return Optional.of(UploadResult.of(Outcome.UNAUTHORIZED));
        UUID orderId = claims.get().orderId();

        if (file == null || file.length == 0) return Optional.of(UploadResult.invalid("PHOTO_REQUIRED"));
        if (file.length > MAX_BYTES) return Optional.of(UploadResult.invalid("PHOTO_TOO_LARGE"));
        ImagePipeline.Processed photo;
        try {
            photo = pipeline.process(file, ImagePipeline.Profile.PHOTO);   // before any transaction
        } catch (ImagePipeline.RejectedException e) {
            return Optional.of(UploadResult.invalid(switch (e.reason()) {
                case UNSUPPORTED_TYPE -> "PHOTO_TYPE";
                case TOO_MANY_PIXELS -> "PHOTO_PIXELS";
                case UNREADABLE -> "PHOTO_UNREADABLE";
            }));
        }
        return Optional.of(TenantContext.runAs(tenantId, () -> tx.execute(s -> {
            Boolean orderOk = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM orders WHERE id = ? AND tenant_id = ? AND pii_redacted_at IS NULL)",
                Boolean.class, orderId, tenantId);
            if (!Boolean.TRUE.equals(orderOk)) return UploadResult.of(Outcome.UNAUTHORIZED);
            Integer perOrder = jdbc.queryForObject(
                "SELECT COUNT(*) FROM return_request_photos WHERE tenant_id = ? AND order_id = ? " +
                "  AND created_at > now() - (interval '1 minute' * ?)",
                Integer.class, tenantId, orderId, PER_ORDER_WINDOW_MINUTES);
            Integer perTenant = jdbc.queryForObject(
                "SELECT COUNT(*) FROM return_request_photos WHERE tenant_id = ? AND created_at > now() - interval '1 hour'",
                Integer.class, tenantId);
            if (perOrder != null && perOrder >= PER_ORDER_MAX || perTenant != null && perTenant >= PER_TENANT_HOURLY_MAX) {
                return UploadResult.of(Outcome.THROTTLED);
            }
            UUID assetId = store.put(tenantId, "photo", photo);
            UUID photoId = jdbc.queryForObject(
                "INSERT INTO return_request_photos (tenant_id, order_id, asset_id, content_type, size_bytes, width, height, sha256) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                UUID.class, tenantId, orderId, assetId, photo.contentType(), photo.sizeBytes(), photo.width(),
                photo.height(), photo.sha256());
            return new UploadResult(Outcome.CREATED, null, photoId, photo.width(), photo.height());
        })));
    }

    public record PhotoBytes(String contentType, byte[] bytes) {}

    /**
     * Merchant read (owner / manager): one photo of one of this tenant's requests. 404 when the
     * photo isn't on that request (or not this tenant's); 410 once its bytes were removed.
     */
    @Transactional(readOnly = true)
    public PhotoBytes merchantPhoto(UUID requestId, UUID photoId) {
        UUID tenantId = TenantContext.require();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT asset_id FROM return_request_photos WHERE id = ? AND request_id = ? AND tenant_id = ?",
            photoId, requestId, tenantId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found");
        UUID assetId = (UUID) rows.get(0).get("asset_id");
        if (assetId == null) throw new ResponseStatusException(HttpStatus.GONE, "Photo removed");
        BinaryAssetStore.Asset asset = store.get(tenantId, assetId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "Photo removed"));
        return new PhotoBytes(asset.info().contentType(), asset.bytes());
    }

    /** Job: delete this tenant's unclaimed uploads older than an hour (runAs + RLS). */
    public int expireUnclaimed(UUID tenantId) {
        Integer n = TenantContext.runAs(tenantId, () -> tx.execute(s -> new ReturnPhotos(jdbc, store).expireUnclaimed(tenantId)));
        return n == null ? 0 : n;
    }

    /** Job: remove photo bytes 90 days after their request ended (runAs + RLS). */
    public int purgeEnded(UUID tenantId) {
        Integer n = TenantContext.runAs(tenantId, () -> tx.execute(s -> new ReturnPhotos(jdbc, store).purgeEnded(tenantId)));
        return n == null ? 0 : n;
    }
}
