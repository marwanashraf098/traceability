package com.traceability.inventory;

import com.traceability.account.AuditService;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyLocationGateway;
import com.traceability.integrations.shopify.MarketShippingCoverage;
import com.traceability.integrations.shopify.ShopifyShippingModelGateway;
import com.traceability.integrations.shopify.ShopifyShippingModelGateway.Model;
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
 *       locationEdit only when Shopify says false, read back — then the shipping READ ({@link #readShipping}).</li>
 *   <li>{@link #onReconnect} — an ALREADY-linked tenant connects / reconnects / re-syncs: READ ONLY. The connection
 *       card shows "Turn back on" when Traced finds online selling off.</li>
 *   <li>{@link #turnBackOn} — the owner's one click (owner-only endpoint): ONLY the fulfils-online-orders location
 *       edit, then the shipping READ.</li>
 *   <li>{@link #refreshQuietly} — the hourly check: fulfils online orders + the shipping READ.</li>
 *   <li>{@link #checkAgain} — the owner / manager "Check again": one per 2 minutes per tenant, then the same reads.</li>
 * </ul>
 * The shipping READ ({@link #readShipping}): the store's shipping model ({@link ShopifyShippingModelGateway}); LEGACY →
 * the default delivery profile membership (read only, {@link ShopifyFulfillmentActivationService#readMembership});
 * MARKET_DRIVEN → the Markets read (L1b-2, optional scope read_markets, MarketShippingCoverage); unknown → nothing.
 * L1b-2 (approved 2026-11-16): Traced NEVER writes a delivery profile or a shipping rate (DeliveryProfileMutationGuardTest)
 * — the owner adds the location in Shopify. The readiness shown to merchants is TracedShippingReadiness.SQL.
 * {@link #confirmShippingRates} — L1b-1 review: the owner's "I've added it" on a market-driven store (no Shopify call).
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
    private final ShopifyShippingModelGateway shippingModel;

    public TracedLocationOnlineService(JdbcTemplate jdbc, PlatformTransactionManager txm,
                                       ShopifyLocationGateway locations,
                                       ShopifyFulfillmentActivationService activation,
                                       ShopifyTokenProvider tokens, StoreRepository stores, AuditService audit,
                                       ShopifyShippingModelGateway shippingModel) {
        this.shippingModel = shippingModel;
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
            String shipping = readShipping(tenantId);
            log.info("Traced Main Warehouse online setup at first link: tenant={} online={} shipping={}",
                tenantId, online, shipping);
            return new Outcome(online, shipping);
        } catch (Exception e) {
            log.warn("Traced Main Warehouse online setup failed at first link: tenant={} error={}", tenantId, e.toString());
            return new Outcome(ERROR, "FAILED");
        }
    }

    /** Reconnect of an already-linked tenant: READ ONLY. Never writes to Shopify, never throws. */
    public Outcome onReconnect(UUID tenantId) {
        try {
            String online = readOnline(tenantId);
            String shipping = readShipping(tenantId);
            log.info("Traced Main Warehouse online check on reconnect (read only): tenant={} online={} shipping={}",
                tenantId, online, shipping);
            return new Outcome(online, shipping);
        } catch (Exception e) {
            log.warn("Traced Main Warehouse online check failed on reconnect: tenant={} error={}", tenantId, e.toString());
            return new Outcome(ERROR, "unknown");
        }
    }

    /**
     * The owner's "Turn back on" (owner-only endpoint): ONLY the fulfils-online-orders location edit (L1b-2 — never a
     * delivery-profile join), then the shipping READ. 409 when the location isn't linked.
     */
    public Outcome turnBackOn(UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        if (main(tenantId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Traced Main Warehouse is not linked to Shopify yet");
        }
        String online = ensureOnline(tenantId, actorUserId, "turn_back_on");
        return new Outcome(online, readShipping(tenantId));
    }

    /**
     * L1b-1 — the hourly location check (ShopifyLocationCheckJob): READ ONLY — fulfils online orders and the shipping
     * READ, recorded; it may update only the recorded statuses. A store Shopify moves to market-driven shipping shows
     * the instruction within the hour; a rate the merchant adds shows Ready within the hour. Never a write to Shopify,
     * never throws.
     */
    public void refreshQuietly(UUID tenantId) {
        try {
            TenantContext.runAs(tenantId, () -> {
                if (main(tenantId).isEmpty()) return;
                readOnline(tenantId);
                readShipping(tenantId);
            });
        } catch (Exception e) {
            log.warn("Traced Main Warehouse shipping refresh failed: tenant={} error={}", tenantId, e.getClass().getSimpleName());
        }
    }

    /**
     * L1b-1 review (approved 2026-11-15) — the owner's "I've added it" on a MARKET-DRIVEN store: records who and when
     * (once — a repeat keeps the first), audit-logged, no Shopify call. Readiness becomes 'owner_confirmed' ("Confirmed
     * by you") — NOT Ready. 409 unless the Traced Main Warehouse is linked and its store reads market-driven.
     */
    public void confirmShippingRates(UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        Integer n = tx.execute(s -> {
            List<UUID> ids = jdbc.query(
                "UPDATE locations SET shopify_shipping_owner_confirmed_at = COALESCE(shopify_shipping_owner_confirmed_at, now()), " +
                "  shopify_shipping_owner_confirmed_by = COALESCE(shopify_shipping_owner_confirmed_by, ?) " +
                "WHERE tenant_id = ? AND is_fulfillment = true AND shopify_sync_status = 'linked' " +
                "  AND shopify_location_id IS NOT NULL AND shopify_shipping_model = 'market_driven' RETURNING id",
                (rs, i) -> rs.getObject(1, UUID.class), actorUserId, tenantId);
            if (ids.isEmpty()) return 0;
            audit.record(actorUserId, "shopify_shipping_rates_confirmed", "location", ids.get(0).toString(),
                Map.of("source", "owner"));
            return ids.size();
        });
        if (n == null || n == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only a linked Traced Main Warehouse on a store with market-driven shipping can be confirmed");
        }
    }

    // ── L1b-2: "Check again" ────────────────────────────────────────────────────────────────────────────────────

    /** One "Check again" per tenant every this many minutes (approved 2026-11-16). */
    public static final int CHECK_EVERY_MINUTES = 2;

    /** allowed=false → nothing was read; nextAllowedAt says when. */
    public record CheckResult(boolean allowed, java.time.Instant nextAllowedAt, String shipping) {}

    /**
     * L1b-2 — the owner / manager "Check again": READ ONLY (fulfils online orders + the shipping READ). Claimed first —
     * a conditional UPDATE that succeeds once per {@link #CHECK_EVERY_MINUTES} per tenant; a second click inside the
     * window reads nothing. No transaction is open during the Shopify calls. 409 when the location isn't linked.
     */
    public CheckResult checkAgain() {
        UUID tenantId = TenantContext.require();
        if (main(tenantId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Traced Main Warehouse is not linked to Shopify yet");
        }
        List<UUID> claimed = tx.execute(s -> jdbc.query(
            "UPDATE locations SET shopify_shipping_check_claimed_at = now() " +
            "WHERE tenant_id = ? AND is_fulfillment = true AND shopify_sync_status = 'linked' " +
            "  AND shopify_location_id IS NOT NULL " +
            "  AND (shopify_shipping_check_claimed_at IS NULL " +
            "       OR shopify_shipping_check_claimed_at <= now() - make_interval(mins => ?)) RETURNING id",
            (rs, i) -> rs.getObject(1, UUID.class), tenantId, CHECK_EVERY_MINUTES));
        if (claimed == null || claimed.isEmpty()) {
            List<java.sql.Timestamp> next = tx.execute(s -> jdbc.queryForList(
                "SELECT shopify_shipping_check_claimed_at + make_interval(mins => ?) FROM locations " +
                "WHERE tenant_id = ? AND is_fulfillment = true AND shopify_sync_status = 'linked' LIMIT 1",
                java.sql.Timestamp.class, CHECK_EVERY_MINUTES, tenantId));
            java.time.Instant at = next == null || next.isEmpty() || next.get(0) == null ? null : next.get(0).toInstant();
            return new CheckResult(false, at, null);
        }
        readOnline(tenantId);
        return new CheckResult(true, null, readShipping(tenantId));
    }

    // ── Receiving move M1: the move gate ─────────────────────────────────────────────────────────────────────────

    /**
     * What the move gate saw. {@code verified} only on a VERIFIED Ready from THIS read; {@code readiness} is
     * TracedShippingReadiness.SQL after it ('unknown' when the stored value says ready but this read couldn't confirm
     * it); {@code locationNumber} / {@code shopDomain} build the owner's deep link.
     */
    public record MoveGate(boolean verified, String readiness, String shippingModel, String locationNumber,
                           String shopDomain) {}

    /**
     * Receiving move M1 (approved 2026-11-18) — the move gate's FRESH read, right before a move session is created:
     * fulfils online orders + the shipping READ (legacy: live delivery-profile membership; market-driven: the Markets
     * read, TracedMarketCheck 'covered' in the primary market), recorded like every other read. Verified Ready only
     * when THIS read answered on + member / covered AND TracedShippingReadiness then says 'ready' — never the stored
     * hourly result, never the owner's "I've added it" ('owner_confirmed'), never a read that failed (a stored Ready
     * this read couldn't confirm is not Ready). Read only, no rate limit (a session is created rarely), no transaction
     * open during the Shopify calls.
     */
    public MoveGate freshShippingForMove(UUID tenantId) {
        if (main(tenantId).isEmpty()) return new MoveGate(false, "not_linked", null, null, null);
        String online = readOnline(tenantId);
        String shipping = readShipping(tenantId);
        Map<String, Object> row = tx.execute(s -> jdbc.queryForMap(
            "SELECT " + TracedShippingReadiness.SQL + " AS readiness, l.shopify_shipping_model AS model, " +
            "       regexp_replace(l.shopify_location_id, '^.*/', '') AS location_number " +
            "FROM locations l WHERE l.tenant_id = ? AND l.is_fulfillment = true LIMIT 1", tenantId));
        String readiness = (String) row.get("readiness");
        boolean verified = ON.equals(online) && ("member".equals(shipping) || "covered".equals(shipping))
            && TracedShippingReadiness.READY.equals(readiness);
        String shown = verified || !TracedShippingReadiness.READY.equals(readiness) ? readiness : TracedShippingReadiness.UNKNOWN;
        String shop = stores.findActiveStoreByTenant(tenantId).map(StoreRepository.Store::shopDomain).orElse(null);
        return new MoveGate(verified, shown, (String) row.get("model"), (String) row.get("location_number"), shop);
    }

    // ── the shipping READ ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Reads the shipping model, then (legacy) the default-profile membership or (market-driven) the Markets coverage.
     * Read only, never throws. Returns a short outcome for logs: member / not_member / covered / not_covered /
     * no_verdict / no_access / unknown / SKIPPED_MODEL_UNKNOWN.
     */
    String readShipping(UUID tenantId) {
        try {
            Optional<Model> model = readModel(tenantId);
            if (model.isEmpty()) return "SKIPPED_MODEL_UNKNOWN";
            if (model.get() == Model.LEGACY) {
                return activation.readMembership(tenantId).map(m -> m ? "member" : "not_member").orElse("unknown");
            }
            return readMarketCoverage(tenantId);
        } catch (Exception e) {
            log.warn("Traced Main Warehouse shipping read failed: tenant={} error={}", tenantId, e.getClass().getSimpleName());
            return "unknown";
        }
    }

    /**
     * L1b-2 — market-driven only: does an active rate of the PRIMARY market list Traced Main (MarketShippingCoverage)?
     * Without read_markets → 'no_access', no call. A failed read changes nothing; a verdict (or no verdict) is recorded.
     */
    String readMarketCoverage(UUID tenantId) {
        Optional<Main> m = main(tenantId);
        if (m.isEmpty()) return "unknown";
        Ctx c = ctx(tenantId);
        if (!ShopifyGateway.isScopeGranted("read_markets", c.scopes)) {
            recordMarket(tenantId, m.get().locationId(), "no_access", null, null);
            return "no_access";
        }
        Optional<MarketShippingCoverage.Result> r = shippingModel.readMarketCoverage(c.shop, c.token, m.get().gid());
        if (r.isEmpty()) return "unknown";
        MarketShippingCoverage.Result res = r.get();
        String status = switch (res.verdict()) {
            case COVERED -> "covered";
            case NOT_COVERED -> "not_covered";
            case NO_ACCESS -> "no_access";
            case NO_VERDICT -> null;
        };
        recordMarket(tenantId, m.get().locationId(), status, res.coveredBy(),
            res.missing().isEmpty() ? null : res.missing().toArray(new String[0]));
        return status == null ? "no_verdict" : status;
    }

    private void recordMarket(UUID tenantId, UUID locationId, String status, String coveredBy, String[] missing) {
        tx.execute(s -> jdbc.update(
            "UPDATE locations SET shopify_market_shipping_status = ?, shopify_market_shipping_checked_at = now(), " +
            "  shopify_market_shipping_covered_by = ?, shopify_market_shipping_missing = ? " +
            "WHERE id = ? AND tenant_id = ? AND is_fulfillment = true",
            status, coveredBy, missing, locationId, tenantId));
    }

    /** Reads the store's shipping model (ShopifyShippingModelGateway) and records it; empty = unknown (recorded so). */
    Optional<Model> readModel(UUID tenantId) {
        Optional<Main> m = main(tenantId);
        if (m.isEmpty()) return Optional.empty();
        Optional<Model> model;
        try {
            Ctx c = ctx(tenantId);
            model = shippingModel.read(c.shop, c.token);
        } catch (Exception e) {
            model = Optional.empty();
        }
        String value = model.map(x -> x == Model.MARKET_DRIVEN ? "market_driven" : "legacy").orElse("unknown");
        tx.execute(s -> jdbc.update(
            "UPDATE locations SET shopify_shipping_model = ?, shopify_shipping_model_checked_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND is_fulfillment = true", value, m.get().locationId(), tenantId));
        return model;
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
