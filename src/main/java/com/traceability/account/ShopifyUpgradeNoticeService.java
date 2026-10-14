package com.traceability.account;

import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * The app-wide Shopify upgrade notice (GET /me's {@code shopifyUpgradeStage}) and its one write, the owner's
 * "I've deleted the old custom app" confirmation (V166).
 *
 * The stage is read from the SAME store row the Connections card shows ({@link StoreRepository#findByTenant}):
 *   'upgrade'        connection_type = 'custom_app_cc' AND status = 'connected' AND the upgrade is offered to
 *                    this shop ({@link ShopifyUpgradeRollout} — the Connections card's own rule, so the notice
 *                    never points at a card without an upgrade button)
 *   'delete_old_app' connection_type = 'oauth' AND oauth_upgraded_from IS NOT NULL (V146) AND
 *                    custom_app_deletion_confirmed_at IS NULL
 *   null             otherwise, and always for a worker
 * The two stages need different connection types, so they can never show together; the delete instruction can
 * never show while the store is still a custom app (deleting it first would disconnect the store).
 */
@Service
public class ShopifyUpgradeNoticeService {

    public static final String STAGE_UPGRADE        = "upgrade";
    public static final String STAGE_DELETE_OLD_APP = "delete_old_app";

    public enum ConfirmResult { CONFIRMED, ALREADY_CONFIRMED, NOT_AWAITING_DELETION }

    private final JdbcTemplate          jdbc;
    private final StoreRepository       storeRepository;
    private final ShopifyUpgradeRollout rollout;

    public ShopifyUpgradeNoticeService(JdbcTemplate jdbc, StoreRepository storeRepository,
                                       ShopifyUpgradeRollout rollout) {
        this.jdbc            = jdbc;
        this.storeRepository = storeRepository;
        this.rollout         = rollout;
    }

    /** The caller's stage, or null. {@code role} is the caller's role as stored (owner / manager / worker). */
    @Transactional(readOnly = true)
    public String stage(String role) {
        UUID tenantId = TenantContext.require();
        if (!"owner".equalsIgnoreCase(role) && !"manager".equalsIgnoreCase(role)) return null;
        Optional<StoreRepository.Store> storeOpt = storeRepository.findByTenant(tenantId);
        if (storeOpt.isEmpty()) return null;
        StoreRepository.Store store = storeOpt.get();

        if ("custom_app_cc".equals(store.connectionType())) {
            return "connected".equals(store.status()) && rollout.offered(store.shopDomain()) ? STAGE_UPGRADE : null;
        }
        if ("oauth".equals(store.connectionType())) {
            Boolean awaiting = jdbc.query(
                "SELECT oauth_upgraded_from IS NOT NULL AND custom_app_deletion_confirmed_at IS NULL " +
                "FROM stores WHERE id = ? AND tenant_id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : Boolean.FALSE,
                store.id(), tenantId);
            return Boolean.TRUE.equals(awaiting) ? STAGE_DELETE_OLD_APP : null;
        }
        return null;
    }

    /**
     * The owner confirms the old custom app is deleted. Only the tenant's store row (the Connections card's pick)
     * while it is in the 'delete_old_app' condition; a repeat after a confirmation is a no-op (no rewrite).
     */
    @Transactional
    public ConfirmResult confirmDeletion(UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        Optional<StoreRepository.Store> storeOpt = storeRepository.findByTenant(tenantId);
        if (storeOpt.isEmpty()) return ConfirmResult.NOT_AWAITING_DELETION;
        UUID storeId = storeOpt.get().id();

        int updated = jdbc.update(
            "UPDATE stores SET custom_app_deletion_confirmed_at = now(), custom_app_deletion_confirmed_by = ? " +
            "WHERE id = ? AND tenant_id = ? AND connection_type = 'oauth' " +
            "  AND oauth_upgraded_from IS NOT NULL AND custom_app_deletion_confirmed_at IS NULL",
            actorUserId, storeId, tenantId);
        if (updated == 1) return ConfirmResult.CONFIRMED;

        Boolean confirmed = jdbc.query(
            "SELECT custom_app_deletion_confirmed_at IS NOT NULL FROM stores " +
            "WHERE id = ? AND tenant_id = ? AND connection_type = 'oauth' AND oauth_upgraded_from IS NOT NULL",
            rs -> rs.next() ? rs.getBoolean(1) : Boolean.FALSE,
            storeId, tenantId);
        return Boolean.TRUE.equals(confirmed) ? ConfirmResult.ALREADY_CONFIRMED : ConfirmResult.NOT_AWAITING_DELETION;
    }
}
