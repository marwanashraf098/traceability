package com.traceability.inventory;

import com.traceability.account.AuditService;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyLocationGateway;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * L1 (approved by Marawan 2026-11-12) — the Traced Main Warehouse sells online.
 *
 * <ul>
 *   <li>{@link #atFirstLink} — when ShopifyLocationProvisioningService links the location for the FIRST time (created
 *       or an existing "Traced Main Warehouse" reused; it holds no stock then): fulfils online orders — read, a
 *       locationEdit only when Shopify says false, read back — then joins the default delivery profile through
 *       {@link ShopifyFulfillmentActivationService#joinWithReadBack} (lock, membership first, read back).</li>
 *   <li>{@link #onReconnect} — an ALREADY-linked tenant connects / reconnects / re-syncs: READ ONLY. Both settings are
 *       read and recorded; nothing is ever written to Shopify here. The connection card shows "Turn back on" when
 *       Traced finds either one off.</li>
 *   <li>{@link #turnBackOn} — the owner's one click (owner-only endpoint): the same write path as first link.</li>
 * </ul>
 * Every write targets ONLY the GID on the tenant's is_fulfillment row, read here — never a GID from a request, never
 * another location, never false, never a name / address / metafield (TracedLocationEditGuardTest). Nothing here
 * throws into provisioning or the import: a failure is recorded on the location row and shown on the card.
 */
@Service
public class TracedLocationOnlineService {

    private static final Logger log = LoggerFactory.getLogger(TracedLocationOnlineService.class);

    public static final String ON = "on";
    public static final String OFF = "off";
    public static final String ERROR = "error";

    /** The tenant's Traced Main Warehouse as Shopify knows it — the ONLY location this class ever touches. */
    record Main(UUID locationId, String gid) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ShopifyLocationGateway locations;
    private final ShopifyFulfillmentActivationService activation;
    private final ShopifyTokenProvider tokens;
    private final StoreRepository stores;
    private final AuditService audit;

    public TracedLocationOnlineService(JdbcTemplate jdbc, PlatformTransactionManager txm,
                                       ShopifyLocationGateway locations,
                                       ShopifyFulfillmentActivationService activation,
                                       ShopifyTokenProvider tokens, StoreRepository stores, AuditService audit) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.locations = locations;
        this.activation = activation;
        this.tokens = tokens;
        this.stores = stores;
        this.audit = audit;
    }

    /** What happened, for logs and the endpoint. */
    public record Outcome(String onlineStatus, String profileOutcome) {}

    /** First link only (provisioning calls it right after the link committed). Never throws. */
    public Outcome atFirstLink(UUID tenantId) {
        try {
            String online = ensureOnline(tenantId, null, "first_link");
            ShopifyFulfillmentActivationService.JoinOutcome joined =
                activation.joinWithReadBack(tenantId, null, "first_link");
            log.info("Traced Main Warehouse online setup at first link: tenant={} online={} profile={}",
                tenantId, online, joined);
            return new Outcome(online, joined.name());
        } catch (Exception e) {
            log.warn("Traced Main Warehouse online setup failed at first link: tenant={} error={}", tenantId, e.toString());
            return new Outcome(ERROR, "FAILED");
        }
    }

    /** Reconnect of an already-linked tenant: READ ONLY. Never writes to Shopify, never throws. */
    public Outcome onReconnect(UUID tenantId) {
        try {
            String online = readOnline(tenantId);
            Optional<Boolean> member = activation.readMembership(tenantId);
            String profile = member.map(m -> m ? "member" : "not_member").orElse("unknown");
            log.info("Traced Main Warehouse online check on reconnect (read only): tenant={} online={} profile={}",
                tenantId, online, profile);
            return new Outcome(online, profile);
        } catch (Exception e) {
            log.warn("Traced Main Warehouse online check failed on reconnect: tenant={} error={}", tenantId, e.toString());
            return new Outcome(ERROR, "unknown");
        }
    }

    /** The owner's "Turn back on" (owner-only endpoint). 409 when the location isn't linked. */
    public Outcome turnBackOn(UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        if (main(tenantId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Traced Main Warehouse is not linked to Shopify yet");
        }
        String online = ensureOnline(tenantId, actorUserId, "turn_back_on");
        ShopifyFulfillmentActivationService.JoinOutcome joined =
            activation.joinWithReadBack(tenantId, actorUserId, "turn_back_on");
        return new Outcome(online, joined.name());
    }

    // ── fulfils online orders ────────────────────────────────────────────────────────────────────────────────────

    /** Read → edit only when Shopify says false → read back. Records and returns on / error. */
    private String ensureOnline(UUID tenantId, UUID actorUserId, String source) {
        Optional<Main> m = main(tenantId);
        if (m.isEmpty()) return ERROR;
        Main main = m.get();
        String result;
        String error = null;
        boolean turnedOn = false;
        try {
            Ctx c = ctx(tenantId);
            Optional<ShopifyLocationGateway.OnlineFulfillment> before = locations.readOnlineFulfillment(c.shop, c.token, main.gid());
            if (before.isEmpty()) {
                result = ERROR;
                error = "Shopify has no location with this id";
            } else if (before.get().fulfillsOnlineOrders()) {
                result = ON;
            } else if (!before.get().active()) {
                result = ERROR;
                error = "The Traced location is deactivated in Shopify";
            } else if (!ShopifyGateway.isScopeGranted("write_locations", c.scopes)) {
                result = ERROR;
                error = ShopifyGateway.scopeGrantMessage(c.connectionType, "write_locations", c.scopes);
            } else {
                locations.enableOnlineFulfillment(c.shop, c.token, main.gid());
                Optional<ShopifyLocationGateway.OnlineFulfillment> after = locations.readOnlineFulfillment(c.shop, c.token, main.gid());
                if (after.isPresent() && after.get().fulfillsOnlineOrders()) {
                    result = ON;
                    turnedOn = true;
                } else {
                    result = ERROR;
                    error = "Shopify still shows the Traced location as not fulfilling online orders after the update";
                }
            }
        } catch (Exception e) {
            result = ERROR;
            error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }
        record(tenantId, main.locationId(), result, error, turnedOn);
        if (turnedOn || ERROR.equals(result)) {
            auditOnline(tenantId, actorUserId, main, source, turnedOn ? "turned_on" : "failed", error);
        }
        return result;
    }

    /** Read only: records on / off / error. */
    private String readOnline(UUID tenantId) {
        Optional<Main> m = main(tenantId);
        if (m.isEmpty()) return ERROR;
        Main main = m.get();
        String result;
        String error = null;
        try {
            Ctx c = ctx(tenantId);
            Optional<ShopifyLocationGateway.OnlineFulfillment> now = locations.readOnlineFulfillment(c.shop, c.token, main.gid());
            if (now.isEmpty()) {
                result = ERROR;
                error = "Shopify has no location with this id";
            } else {
                result = now.get().fulfillsOnlineOrders() ? ON : OFF;
            }
        } catch (Exception e) {
            result = ERROR;
            error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }
        record(tenantId, main.locationId(), result, error, false);
        return result;
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────────────────

    /** The is_fulfillment row, linked, with its GID — the only target. */
    Optional<Main> main(UUID tenantId) {
        List<Main> rows = tx.execute(s -> jdbc.query(
            "SELECT id, shopify_location_id FROM locations " +
            "WHERE tenant_id = ? AND is_fulfillment = true AND shopify_sync_status = 'linked' " +
            "  AND shopify_location_id IS NOT NULL LIMIT 1",
            (rs, i) -> new Main(rs.getObject(1, UUID.class), rs.getString(2)), tenantId));
        return rows == null || rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private record Ctx(String shop, String token, String scopes, String connectionType) {}

    private Ctx ctx(UUID tenantId) {
        StoreRepository.Store store = stores.findActiveStoreByTenant(tenantId)
            .orElseThrow(() -> new IllegalStateException("No Shopify store connected"));
        return new Ctx(store.shopDomain(), tokens.getValidToken(store.id()), store.accessTokenScopes(), store.connectionType());
    }

    private void record(UUID tenantId, UUID locationId, String status, String error, boolean turnedOn) {
        tx.execute(s -> jdbc.update(
            "UPDATE locations SET shopify_online_fulfillment_status = ?, shopify_online_fulfillment_error = ?, " +
            "  shopify_online_fulfillment_checked_at = now(), " +
            "  shopify_online_fulfillment_turned_on_at = CASE WHEN ? THEN now() ELSE shopify_online_fulfillment_turned_on_at END " +
            "WHERE id = ? AND tenant_id = ? AND is_fulfillment = true",
            status, error, turnedOn, locationId, tenantId));
    }

    private void auditOnline(UUID tenantId, UUID actorUserId, Main main, String source, String outcome, String error) {
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("outcome", outcome);
            meta.put("source", source);
            if (error != null) meta.put("error", error);
            tx.execute(s -> {
                audit.record(actorUserId, "shopify_online_fulfillment", "location", main.locationId().toString(), meta);
                return null;
            });
        } catch (Exception e) {
            log.warn("audit of online fulfillment failed: tenant={} error={}", tenantId, e.toString());
        }
    }
}
