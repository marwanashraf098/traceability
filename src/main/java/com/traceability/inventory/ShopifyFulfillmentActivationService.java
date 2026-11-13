package com.traceability.inventory;

import com.traceability.account.AuditService;
import com.traceability.integrations.shopify.ShopifyDeliveryProfileGateway;
import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
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
 * AUTOMATIC AT FIRST LINK, MANUAL RETRY (L1, approved by Marawan 2026-11-12). When the Traced Main Warehouse is
 * first linked to Shopify (created OR an existing "Traced Main Warehouse" reused) it holds no stock, so
 * TracedLocationOnlineService joins it here automatically through {@link #joinWithReadBack} — same advisory lock,
 * membership check first, then a read-back of the profile that decides the recorded status. On a reconnect of an
 * already-linked tenant only {@link #readMembership} runs (read only, never a write). The owner's
 * "Activate fulfillment" button ({@link #activate}) and "Turn back on" stay as the retry / fix paths.
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
    private final StoreRepository storeRepository;

    public ShopifyFulfillmentActivationService(JdbcTemplate jdbc, PlatformTransactionManager txm,
                                                ShopifyDeliveryProfileGateway deliveryProfiles,
                                                ShopifyTokenProvider tokenProvider,
                                                AuditService auditService,
                                                StoreRepository storeRepository) {
        this.jdbc             = jdbc;
        this.tx               = new TransactionTemplate(txm);
        this.deliveryProfiles = deliveryProfiles;
        this.tokenProvider    = tokenProvider;
        this.auditService     = auditService;
        this.storeRepository  = storeRepository;
    }

    private record Context(UUID storeId, String shopDomain, String grantedScopes, String connectionType,
                            UUID locationId, String locationGid) {}

    // FR-3.1 follow-up — StoreRepository.findActiveStoreByTenant() is the single canonical
    // pick shared by every job/service (never a disconnected row).
    private Context resolveContext(UUID tenantId) {
        StoreRepository.Store store = storeRepository.findActiveStoreByTenant(tenantId).orElse(null);
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

        return new Context(store.id(), store.shopDomain(), store.accessTokenScopes(), store.connectionType(),
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

    /** L1 — the outcome of {@link #joinWithReadBack}. */
    public enum JoinOutcome { ALREADY_MEMBER, JOINED, FAILED }

    /**
     * L1 — joins the Traced Main Warehouse to the default delivery profile's location group, AUTOMATICALLY (first
     * link) or from the owner's "Turn back on". Same per-tenant advisory lock as {@link #activate}; membership is
     * checked first (a member → no write); after deliveryProfileUpdate the profile is READ BACK and only a confirmed
     * membership records 'activated' — anything else records 'error' with the reason. Never throws: every outcome is
     * recorded on the location row (and in audit_log, actor null when automatic) and returned.
     *
     * @param actorUserId null when automatic
     * @param source      'first_link' | 'turn_back_on' (audit metadata)
     */
    public JoinOutcome joinWithReadBack(UUID tenantId, UUID actorUserId, String source) {
        try {
            return tx.execute(status -> {
                acquireTenantLock(tenantId);
                Context ctx;
                try {
                    ctx = resolveContext(tenantId);
                } catch (ResponseStatusException e) {
                    log.info("Fulfillment auto-join skipped: tenant={} reason={}", tenantId, e.getReason());
                    return JoinOutcome.FAILED;                     // no linked row to record against
                }
                try {
                    if (!ShopifyGateway.isScopeGranted("write_shipping", ctx.grantedScopes())) {
                        String why = ShopifyGateway.scopeGrantMessage(ctx.connectionType(), "write_shipping", ctx.grantedScopes());
                        recordStatus(tenantId, ctx.locationId(), STATUS_ERROR, why);
                        audit(actorUserId, ctx, source, "failed", why);
                        return JoinOutcome.FAILED;
                    }
                    String token = tokenProvider.getValidToken(ctx.storeId());
                    Optional<ShopifyDeliveryProfileGateway.LocationGroupInfo> found =
                        deliveryProfiles.findDefaultProfileLocationGroup(ctx.shopDomain(), token);
                    if (found.isEmpty()) {
                        String why = "Shop has no default delivery profile — cannot activate fulfillment";
                        recordStatus(tenantId, ctx.locationId(), STATUS_ERROR, why);
                        audit(actorUserId, ctx, source, "failed", why);
                        return JoinOutcome.FAILED;
                    }
                    ShopifyDeliveryProfileGateway.LocationGroupInfo group = found.get();
                    if (group.memberLocationGids().contains(ctx.locationGid())) {
                        recordStatus(tenantId, ctx.locationId(), STATUS_ACTIVATED, null);
                        audit(actorUserId, ctx, source, "already_member", null);
                        return JoinOutcome.ALREADY_MEMBER;
                    }
                    deliveryProfiles.addLocationToGroup(ctx.shopDomain(), token,
                        group.deliveryProfileId(), group.locationGroupId(), ctx.locationGid());
                    // Read back: only Shopify's own answer decides 'activated'.
                    boolean member = deliveryProfiles.findDefaultProfileLocationGroup(ctx.shopDomain(), token)
                        .map(g -> g.memberLocationGids().contains(ctx.locationGid()))
                        .orElse(false);
                    if (!member) {
                        String why = "Traced Main Warehouse is not in the default delivery profile after the update";
                        recordStatus(tenantId, ctx.locationId(), STATUS_ERROR, why);
                        audit(actorUserId, ctx, source, "failed", why);
                        return JoinOutcome.FAILED;
                    }
                    recordStatus(tenantId, ctx.locationId(), STATUS_ACTIVATED, null);
                    audit(actorUserId, ctx, source, "joined", null);
                    log.info("Fulfillment joined ({}): tenant={} location={} deliveryProfile={}",
                        source, tenantId, ctx.locationId(), group.deliveryProfileId());
                    return JoinOutcome.JOINED;
                } catch (Exception e) {
                    String why = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    recordStatus(tenantId, ctx.locationId(), STATUS_ERROR, why);
                    audit(actorUserId, ctx, source, "failed", why);
                    log.warn("Fulfillment auto-join failed ({}): tenant={} location={} error={}",
                        source, tenantId, ctx.locationId(), why);
                    return JoinOutcome.FAILED;
                }
            });
        } catch (Exception e) {
            log.warn("Fulfillment auto-join failed before it could be recorded: tenant={} error={}", tenantId, e.toString());
            return JoinOutcome.FAILED;
        }
    }

    /**
     * L1 — READ ONLY (reconnect of an already-linked tenant): is the Traced Main Warehouse in the default delivery
     * profile's location group? Records 'activated' / 'not_activated' (activated_at kept, so a location that was in
     * the profile and was taken out reads as "turned off") with shopify_delivery_profile_checked_at. Never writes
     * to Shopify, never throws; a failed read changes nothing but the checked error. Empty = unknown.
     */
    public Optional<Boolean> readMembership(UUID tenantId) {
        try {
            return tx.execute(status -> {
                Context ctx;
                try {
                    ctx = resolveContext(tenantId);
                } catch (ResponseStatusException e) {
                    return Optional.<Boolean>empty();
                }
                try {
                    String token = tokenProvider.getValidToken(ctx.storeId());
                    Optional<ShopifyDeliveryProfileGateway.LocationGroupInfo> found =
                        deliveryProfiles.findDefaultProfileLocationGroup(ctx.shopDomain(), token);
                    if (found.isEmpty()) return Optional.<Boolean>empty();
                    boolean member = found.get().memberLocationGids().contains(ctx.locationGid());
                    jdbc.update(
                        "UPDATE locations SET shopify_delivery_profile_status = ?, " +
                        "  shopify_delivery_profile_error = NULL, shopify_delivery_profile_checked_at = now(), " +
                        "  shopify_delivery_profile_activated_at = CASE WHEN ? THEN COALESCE(shopify_delivery_profile_activated_at, now()) " +
                        "                                         ELSE shopify_delivery_profile_activated_at END " +
                        "WHERE id = ? AND tenant_id = ?",
                        member ? STATUS_ACTIVATED : STATUS_NOT_ACTIVATED, member, ctx.locationId(), tenantId);
                    return Optional.of(member);
                } catch (Exception e) {
                    log.warn("Delivery profile read failed on reconnect: tenant={} error={}", tenantId, e.toString());
                    return Optional.<Boolean>empty();
                }
            });
        } catch (Exception e) {
            log.warn("Delivery profile read failed on reconnect: tenant={} error={}", tenantId, e.toString());
            return Optional.empty();
        }
    }

    private void audit(UUID actorUserId, Context ctx, String source, String outcome, String error) {
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("outcome", outcome);
        meta.put("source", source);
        if (error != null) meta.put("error", error);
        auditService.record(actorUserId, "shopify_fulfillment_activation", "location", ctx.locationId().toString(), meta);
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
