package com.traceability.assets;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Where tenant-uploaded binaries live (returns portal P1 logo; P3 photos). Today one
 * implementation, {@link PostgresBinaryAssetStore} (bytea in {@code portal_assets}, V159) — no
 * object store, no new vendor or secret. Callers depend on this interface so the bytes can move
 * to an object store later without touching them.
 *
 * Every method runs on the caller's connection and transaction, under the caller's tenant
 * context: reads and writes are RLS-scoped, and every statement also binds tenant_id. Assets are
 * immutable — a replacement is a new asset plus a delete of the old one.
 */
public interface BinaryAssetStore {

    /** Everything about an asset except its bytes — enough to answer a conditional GET. */
    record AssetInfo(UUID id, String kind, String contentType, int sizeBytes, int width, int height,
                     String sha256, Instant createdAt) {}

    record Asset(AssetInfo info, byte[] bytes) {}

    /** Stores a processed image; returns the new asset's id. */
    UUID put(UUID tenantId, String kind, ImagePipeline.Processed image);

    Optional<AssetInfo> info(UUID tenantId, UUID assetId);

    Optional<Asset> get(UUID tenantId, UUID assetId);

    /** Deletes every asset of this kind for the tenant except {@code keep} (null = all of them). */
    int deleteKindExcept(UUID tenantId, String kind, UUID keep);

    /** Deletes these assets of the tenant (others' ids are ignored); returns how many went. */
    int delete(UUID tenantId, java.util.Collection<UUID> assetIds);
}
