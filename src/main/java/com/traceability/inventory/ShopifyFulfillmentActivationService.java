package com.traceability.inventory;

import com.traceability.account.AuditService;
import com.traceability.integrations.shopify.ShopifyDeliveryProfileGateway;
import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * FR-17 v2 — fulfillment activation: joins the Traced Main Warehouse to the shop's default
 * delivery profile's location group via deliveryProfileUpdate, making it live to the storefront
 * (Shopify sums on_hand across every location in a product's delivery profile).
 *
 * NOT CALLED FROM ANYWHERE YET. Deliberately left with zero call sites — see the flow proposal
 * delivered alongside this change. Part A's automatic seed (ShopifyLocationProvisioningService,
 * ShopifyCatalogActivationService, ShopifyInventoryReconcileService, all run unconditionally
 * from ShopifyImportJob) only creates the Location object and seeds its stock; it never makes
 * the location live to the storefront, so shipping Part A alone is safe on its own — this
 * service is the separate, deliberate step that changes that, and where its trigger lives
 * (an operator-facing endpoint gated on merchant confirmation vs. something automatic) is a
 * decision pending Marawan's call.
 *
 * Manual, one-shot, one-tenant-at-a-time action — same trade-off as
 * ShopifyInventoryReconcileService.apply(): serialized via a per-tenant
 * pg_advisory_xact_lock held for the whole operation, not a claim-row race guard. Idempotency
 * is a live membership check (deliveryProfileUpdate carries no @idempotent directive to lean
 * on), not a mutation-level key.
 */
@Service
public class ShopifyFulfillmentActivationService {

    private static final Logger log = LoggerFactory.getLogger(ShopifyFulfillmentActivationService.class);

    public static final String STATUS_ACTIVATED     = "activated";
    public static final String STATUS_NOT_ACTIVATED = "not_activated";
    public static final String STATUS_ERROR         = "error";

    public record ActivationResult(String status, String locationGid, String deliveryProfileId,
                                    String locationGroupId, boolean alreadyMember) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ShopifyDeliveryProfileGateway deliveryProfiles;
    private final ShopifyTokenProvider tokenProvider;
    private final AuditService auditService;

    public ShopifyFulfillmentActivationService(JdbcTemplate jdbc, PlatformTransactionManager txm,
                                                ShopifyDeliveryProfileGateway deliveryProfiles,
                                                ShopifyTokenProvider tokenProvider,
                                                AuditService auditService) {
        this.jdbc             = jdbc;
        this.tx               = new TransactionTemplate(txm);
        this.deliveryProfiles = deliveryProfiles;
        this.tokenProvider    = tokenProvider;
        this.auditService     = auditService;
    }

    private record Context(UUID storeId, String shopDomain, String grantedScopes, String connectionType,
                            UUID locationId, String locationGid) {}

    private Context resolveContext(UUID tenantId) {
        record StoreSnap(UUID id, String shopDomain, String grantedScopes, String connectionType) {}
        StoreSnap store = jdbc.query(
            "SELECT id, shop_domain, access_token_scopes, connection_type FROM stores WHERE tenant_id = ? LIMIT 1",
            rs -> rs.next() ? new StoreSnap(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)) : null,
            tenantId);
        if (store == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No Shopify store connected");
        }

        record LocSnap(UUID id, String gid) {}
        LocSnap loc = jdbc.query(
            "SELECT id, shopify_location_id FROM locations " +
            "WHERE tenant_id = ? AND is_fulfillment = true AND shopify_sync_status = 'linked' LIMIT 1",
            rs -> rs.next() ? new LocSnap(rs.getObject(1, UUID.class), rs.getString(2)) : null,
            tenantId);
        if (loc == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Traced Main Warehouse is not linked to Shopify yet");
        }

        return new Context(store.id(), store.shopDomain(), store.grantedScopes(), store.connectionType(),
            loc.id(), loc.gid());
    }

    /** Never thrown across the tx.execute boundary — see {@link #activate}'s comment on why. */
    private record Attempt(ActivationResult result, ShopifyFulfillmentActivationException.Code errorCode,
                            String errorEn, String errorAr, HttpStatus errorStatus) {
        static Attempt ok(ActivationResult r) { return new Attempt(r, null, null, null, null); }
        static Attempt fail(ShopifyFulfillmentActivationException.Code code, String en, String ar, HttpStatus s) {
            return new Attempt(null, code, en, ar, s);
        }
        boolean failed() { return result == null; }
    }

    /**
     * @throws ResponseStatusException 409 if no Shopify store is connected or the Traced Main
     *         Warehouse isn't linked to Shopify yet (no location row to record a guard error
     *         against, so these stay the generic bodyless form).
     * @throws ShopifyFulfillmentActivationException for every OTHER guard failure (missing
     *         write_shipping scope, no default delivery profile, ambiguous location groups,
     *         the mutation itself failing) — carries {code, message_en, message_ar} to the
     *         frontend via ApiExceptionHandler. Never half-adds the location — either it ends
     *         up a confirmed group member, or nothing was written.
     */
    public ActivationResult activate(UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        // Every branch below returns an Attempt instead of throwing FROM INSIDE tx.execute:
        // this whole block is one transaction (the advisory lock must span it), and a
        // RuntimeException escaping a TransactionTemplate callback rolls back everything in
        // it — including the very recordStatus() write meant to survive the failure. Guard
        // outcomes are recorded and committed here; the exception is thrown AFTER the
        // transaction has committed, from the unwrap below.
        Attempt attempt = tx.execute(status -> {
            acquireTenantLock(tenantId);

            Context ctx = resolveContext(tenantId);

            if (!ShopifyGateway.isScopeGranted("write_shipping", ctx.grantedScopes())) {
                String messageEn = ShopifyGateway.scopeGrantMessage(
                    ctx.connectionType(), "write_shipping", ctx.grantedScopes());
                String messageAr = "صلاحية write_shipping غير ممنوحة لهذا المتجر — يجب تحديث نطاقات " +
                    "التطبيق وإعادة إصدار رمز الدخول قبل تفعيل الشحن من هذا الموقع.";
                log.warn("Fulfillment activation skipped: {}", messageEn);
                recordStatus(tenantId, ctx.locationId(), STATUS_ERROR, messageEn);
                return Attempt.fail(ShopifyFulfillmentActivationException.Code.MISSING_SCOPE,
                    messageEn, messageAr, HttpStatus.CONFLICT);
            }

            String token = tokenProvider.getValidToken(ctx.storeId());

            Optional<ShopifyDeliveryProfileGateway.LocationGroupInfo> found;
            try {
                found = deliveryProfiles.findDefaultProfileLocationGroup(ctx.shopDomain(), token);
            } catch (ShopifyException e) {
                // Covers the ambiguous->1-location-group case the gateway refuses to guess on.
                String messageEn = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                String messageAr = "تعذّر تحديد مجموعة الموقع الافتراضية للشحن بشكل قاطع — " +
                    "راجع إعدادات ملفات الشحن في Shopify قبل إعادة المحاولة.";
                recordStatus(tenantId, ctx.locationId(), STATUS_ERROR, messageEn);
                return Attempt.fail(ShopifyFulfillmentActivationException.Code.AMBIGUOUS_LOCATION_GROUPS,
                    messageEn, messageAr, HttpStatus.CONFLICT);
            }
            if (found.isEmpty()) {
                String messageEn = "Shop has no default delivery profile — cannot activate fulfillment";
                String messageAr = "لا يوجد ملف شحن افتراضي لهذا المتجر — لا يمكن تفعيل الشحن من هذا الموقع.";
                recordStatus(tenantId, ctx.locationId(), STATUS_ERROR, messageEn);
                return Attempt.fail(ShopifyFulfillmentActivationException.Code.NO_DEFAULT_PROFILE,
                    messageEn, messageAr, HttpStatus.CONFLICT);
            }
            ShopifyDeliveryProfileGateway.LocationGroupInfo group = found.get();

            if (group.memberLocationGids().contains(ctx.locationGid())) {
                recordStatus(tenantId, ctx.locationId(), STATUS_ACTIVATED, null);
                auditService.record(actorUserId, "shopify_fulfillment_activation", "location",
                    ctx.locationId().toString(), Map.of("outcome", "already_member"));
                return Attempt.ok(new ActivationResult(STATUS_ACTIVATED, ctx.locationGid(),
                    group.deliveryProfileId(), group.locationGroupId(), true));
            }

            try {
                deliveryProfiles.addLocationToGroup(ctx.shopDomain(), token,
                    group.deliveryProfileId(), group.locationGroupId(), ctx.locationGid());
                recordStatus(tenantId, ctx.locationId(), STATUS_ACTIVATED, null);
                auditService.record(actorUserId, "shopify_fulfillment_activation", "location",
                    ctx.locationId().toString(), Map.of(
                        "outcome", "joined",
                        "deliveryProfileId", group.deliveryProfileId(),
                        "locationGroupId", group.locationGroupId()));
                log.info("Fulfillment activated: tenant={} location={} deliveryProfile={} locationGroup={}",
                    tenantId, ctx.locationId(), group.deliveryProfileId(), group.locationGroupId());
                return Attempt.ok(new ActivationResult(STATUS_ACTIVATED, ctx.locationGid(),
                    group.deliveryProfileId(), group.locationGroupId(), false));
            } catch (Exception e) {
                String messageEn = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                String messageAr = "فشل تفعيل الشحن من موقع Traced — حاول مرة أخرى أو تواصل مع الدعم.";
                recordStatus(tenantId, ctx.locationId(), STATUS_ERROR, messageEn);
                auditService.record(actorUserId, "shopify_fulfillment_activation", "location",
                    ctx.locationId().toString(), Map.of("outcome", "failed", "error", messageEn));
                log.warn("Fulfillment activation failed: tenant={} location={} error={}",
                    tenantId, ctx.locationId(), messageEn);
                return Attempt.fail(ShopifyFulfillmentActivationException.Code.MUTATION_FAILED,
                    "Fulfillment activation failed: " + messageEn, messageAr, HttpStatus.BAD_GATEWAY);
            }
        });

        if (attempt.failed()) {
            throw new ShopifyFulfillmentActivationException(
                attempt.errorCode(), attempt.errorEn(), attempt.errorAr(), attempt.errorStatus());
        }
        return attempt.result();
    }

    /** pg_advisory_xact_lock is transaction-scoped — released automatically on commit/rollback. */
    private void acquireTenantLock(UUID tenantId) {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
            try (var ps = con.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)")) {
                ps.setString(1, tenantId.toString());
                ps.execute();
            }
            return null;
        });
    }

    private void recordStatus(UUID tenantId, UUID locationId, String status, String error) {
        if (STATUS_ACTIVATED.equals(status)) {
            jdbc.update(
                "UPDATE locations SET shopify_delivery_profile_status = ?, shopify_delivery_profile_error = NULL, " +
                "shopify_delivery_profile_activated_at = now() WHERE id = ? AND tenant_id = ?",
                status, locationId, tenantId);
        } else {
            jdbc.update(
                "UPDATE locations SET shopify_delivery_profile_status = ?, shopify_delivery_profile_error = ? " +
                "WHERE id = ? AND tenant_id = ?",
                status, error, locationId, tenantId);
        }
    }
}
