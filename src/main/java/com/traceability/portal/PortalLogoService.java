package com.traceability.portal;

import com.traceability.assets.BinaryAssetStore;
import com.traceability.assets.ImagePipeline;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Returns portal P1 — the merchant's uploaded logo (V159). The upload is checked and re-encoded
 * by {@link ImagePipeline} BEFORE any transaction opens ({@link #process}); {@link #save} then
 * stores it and points the tenant at it, and {@link #remove} takes it away. Both lock the tenant
 * row first, so two concurrent uploads end with exactly one logo asset — the last to commit.
 *
 * Portal precedence: this asset > tenants.portal_logo_url (a Shopify Files link, V103,
 * unchanged) > the store-name wordmark.
 */
@Service
public class PortalLogoService {

    public static final String KIND = "logo";
    /** The app's own cap for a logo (multipart's 8 MB is only the outer ceiling). */
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    /** The logo URL's ?v= — the first 12 hex digits of the stored bytes' SHA-256. */
    static final int VERSION_LENGTH = 12;

    /** The ?v= value for a stored logo's SHA-256. */
    public static String version(String sha256) {
        return sha256.substring(0, VERSION_LENGTH);
    }

    /** The public, versioned logo URL (same origin — the portal CSP stays img-src 'self'). */
    public static String publicUrl(String slug, String sha256) {
        return "/api/v1/portal/" + slug.trim().toLowerCase(java.util.Locale.ROOT) + "/logo?v=" + version(sha256);
    }

    private final JdbcTemplate jdbc;
    private final BinaryAssetStore store;
    private final ImagePipeline pipeline;

    public PortalLogoService(JdbcTemplate jdbc, BinaryAssetStore store, ImagePipeline pipeline) {
        this.jdbc = jdbc;
        this.store = store;
        this.pipeline = pipeline;
    }

    /** Size cap, type sniff, pixel cap, decode, orientation, re-encode (PNG, alpha kept, ≤ 600 px wide). */
    public ImagePipeline.Processed process(byte[] upload) {
        if (upload == null || upload.length == 0) {
            throw field(HttpStatus.BAD_REQUEST, "LOGO_REQUIRED", "Choose an image file to upload.");
        }
        if (upload.length > MAX_BYTES) {
            throw field(HttpStatus.BAD_REQUEST, "LOGO_TOO_LARGE", "The logo can be at most 2 MB.");
        }
        try {
            return pipeline.process(upload, ImagePipeline.Profile.LOGO);
        } catch (ImagePipeline.RejectedException e) {
            throw switch (e.reason()) {
                case UNSUPPORTED_TYPE -> field(HttpStatus.BAD_REQUEST, "LOGO_TYPE", "Use a PNG, JPG or WebP image.");
                case TOO_MANY_PIXELS  -> field(HttpStatus.BAD_REQUEST, "LOGO_PIXELS",
                    "This image is too large to process. Use a smaller image.");
                case UNREADABLE       -> field(HttpStatus.BAD_REQUEST, "LOGO_UNREADABLE",
                    "This file couldn't be read as an image.");
            };
        }
    }

    /** Stores the processed logo and makes it the tenant's logo; the previous one is deleted. */
    @Transactional
    public void save(ImagePipeline.Processed logo) {
        UUID tenantId = TenantContext.require();
        lockTenant(tenantId);
        UUID assetId = store.put(tenantId, KIND, logo);
        // The survivor first (the FK must point at a live asset), then every other logo goes.
        jdbc.update("UPDATE tenants SET portal_logo_asset_id = ? WHERE id = ?", assetId, tenantId);
        store.deleteKindExcept(tenantId, KIND, assetId);
    }

    /** Removes the uploaded logo: the column is nulled and the asset rows deleted, one transaction. */
    @Transactional
    public void remove() {
        UUID tenantId = TenantContext.require();
        lockTenant(tenantId);
        jdbc.update("UPDATE tenants SET portal_logo_asset_id = NULL WHERE id = ?", tenantId);
        store.deleteKindExcept(tenantId, KIND, null);
    }

    /** The tenant's current uploaded logo, bytes included (the settings preview). */
    @Transactional(readOnly = true)
    public Optional<BinaryAssetStore.Asset> current() {
        UUID tenantId = TenantContext.require();
        return store.get(tenantId, currentId(jdbc, tenantId));
    }

    /** The tenant's current logo asset id, or null. Runs on the caller's transaction. */
    static UUID currentId(JdbcTemplate jdbc, UUID tenantId) {
        return jdbc.queryForObject("SELECT portal_logo_asset_id FROM tenants WHERE id = ?", UUID.class, tenantId);
    }

    private void lockTenant(UUID tenantId) {
        jdbc.queryForObject("SELECT id FROM tenants WHERE id = ? FOR UPDATE", UUID.class, tenantId);
    }

    private static PortalSettingsService.FieldException field(HttpStatus status, String code, String message) {
        return new PortalSettingsService.FieldException(status, "logo", code, message);
    }
}
