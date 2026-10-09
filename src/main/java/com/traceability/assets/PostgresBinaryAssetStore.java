package com.traceability.assets;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/** {@link BinaryAssetStore} on {@code portal_assets} (V159): bytea, RLS + tenant_id-bound. */
@Component
public class PostgresBinaryAssetStore implements BinaryAssetStore {

    private static final String INFO_COLUMNS =
        "id, kind, content_type, size_bytes, width, height, sha256, created_at";

    private final JdbcTemplate jdbc;

    public PostgresBinaryAssetStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID put(UUID tenantId, String kind, ImagePipeline.Processed image) {
        return jdbc.queryForObject(
            "INSERT INTO portal_assets (tenant_id, kind, content_type, bytes, size_bytes, width, height, sha256) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
            UUID.class, tenantId, kind, image.contentType(), image.bytes(), image.sizeBytes(),
            image.width(), image.height(), image.sha256());
    }

    @Override
    public Optional<AssetInfo> info(UUID tenantId, UUID assetId) {
        if (assetId == null) return Optional.empty();
        return jdbc.query(
            "SELECT " + INFO_COLUMNS + " FROM portal_assets WHERE tenant_id = ? AND id = ?",
            INFO, tenantId, assetId).stream().findFirst();
    }

    @Override
    public Optional<Asset> get(UUID tenantId, UUID assetId) {
        if (assetId == null) return Optional.empty();
        return jdbc.query(
            "SELECT " + INFO_COLUMNS + ", bytes FROM portal_assets WHERE tenant_id = ? AND id = ?",
            (rs, i) -> new Asset(INFO.mapRow(rs, i), rs.getBytes("bytes")), tenantId, assetId)
            .stream().findFirst();
    }

    @Override
    public int deleteKindExcept(UUID tenantId, String kind, UUID keep) {
        return keep == null
            ? jdbc.update("DELETE FROM portal_assets WHERE tenant_id = ? AND kind = ?", tenantId, kind)
            : jdbc.update("DELETE FROM portal_assets WHERE tenant_id = ? AND kind = ? AND id <> ?", tenantId, kind, keep);
    }

    private static final RowMapper<AssetInfo> INFO = PostgresBinaryAssetStore::info;

    private static AssetInfo info(ResultSet rs, int i) throws SQLException {
        return new AssetInfo(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("content_type"),
            rs.getInt("size_bytes"), rs.getInt("width"), rs.getInt("height"), rs.getString("sha256"),
            rs.getTimestamp("created_at").toInstant());
    }
}
