package com.traceability.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.courier.GenericShipments;
import com.traceability.integrations.bosta.CourierSimulation;
import com.traceability.places.Places;
import com.traceability.setup.SetupStatus;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Onboarding O1 — the progress model behind the Overview "Get started" card (V220).
 *
 * Every step and task is DERIVED at read time from rows that already exist; only what can't be
 * derived lives in {@code tenants.onboarding_prefs}, and this class is its ONE writer
 * ({@link #updatePrefs}, whitelisted keys; {@link #HIDE_CARD_SQL} is the legacy dismiss's card-only merge). The legacy checklist ({@code GET /onboarding/status},
 * {@link OnboardingController}) is untouched.
 *
 * Setup steps (in card order):
 *   shopify          tenants.shopify_connected_at (the gate's sticky stamp — live store status never
 *                    un-does it)
 *   address          the main warehouse has a complete address (SetupStatus.ADDRESS_COMPLETE_SQL)
 *   first_receiving  a finalized, non-reversed receipt
 *   courier          an active Bosta account, OR other couriers switched on, OR a simulated courier;
 *                    skippable ('courier' in prefs.skipped)
 *   test_label       prefs.testLabelPrinted OR any label_reprints row; skippable
 *
 * Tasks ("tried"):
 *   receive     = first_receiving
 *   pack        a Pick & Pack 'pack' piece event ({@link #REAL_PACK_SQL}: queue, waybill scan and the
 *               generic other-courier pack — all FulfillService.complete). The Lookup exchange dispatch's
 *               pack event (metadata event 'lookup_exchange_dispatch') is NOT packing and never counts.
 *   pickup      a closed pickup session
 *   returns     a closed return session OR a return_received piece event
 *   transfer    a transfer that wasn't cancelled
 *   stock_take  a stock take that wasn't cancelled
 *   portal      the returns portal is on
 *   team        more than one active user
 *   places      a live shelf or box — present ONLY when places are on for the tenant
 *
 * A step's {@code skipped} is true only while the step is not done.
 *
 * Get started card (O3): {@code card} is 'hidden' for the demo and review tenants whatever is stored
 * (ReviewCapabilities.scanHelpers — the same rule SetupStatus uses). {@code cardChosen} is true once the card
 * state was written through PATCH (server-stamped {@code cardChosenAt}, never client-writable): the frontend's
 * auto-slim never overrides a choice. {@code setupCompletedAt} (the gate's stamp) and {@code bostaConnected}
 * (an active Bosta account) feed the card's wording and the auto-slim rule.
 *
 * Setup intro (O2) — {@link #setupIntro}: the post-gate screens an OWNER is sent to once, after the gate
 * (Flow-3 courier while the courier step is neither done nor skipped, then Flow-4 once; Flow-4 sets the
 * {@code setupIntroSeen} pref). Read by GET /me; never for a manager / worker, a gated tenant, the demo or
 * review tenants, a tenant whose card is hidden (backfilled for every active tenant at V220) or one that
 * has seen it.
 */
@Service
public class OnboardingProgressService {

    public static final Set<String> CARD_STATES   = Set.of("full", "slim", "hidden");
    public static final Set<String> SKIPPABLE     = Set.of("courier", "test_label");
    public static final Set<String> PREF_KEYS     =
        Set.of("card", "skipped", "pickPackChosen", "testLabelPrinted", "labelingPace", "setupIntroSeen");
    static final int PACE_MIN = 1, PACE_MAX = 10_000;

    /**
     * "Packed in Pick & Pack" — a 'pack' piece event that isn't the Lookup exchange dispatch
     * (GenericLegTransitions.dispatchLookupReplacement tags its event {"event":"lookup_exchange_dispatch"}).
     * V220's pickPackChosen backfill inlines the same predicate. Alias {@code e}.
     */
    static final String REAL_PACK_SQL =
        "e.event_type = 'pack' AND COALESCE(e.metadata->>'event', '') <> 'lookup_exchange_dispatch'";

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public OnboardingProgressService(JdbcTemplate jdbc, AuditService audit, ObjectMapper mapper) {
        this.jdbc   = jdbc;
        this.audit  = audit;
        this.mapper = mapper;
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> progress() {
        UUID tenantId = TenantContext.require();
        Map<String, Object> prefs = readPrefs(tenantId);

        boolean shopify = exists(
            "SELECT shopify_connected_at IS NOT NULL FROM tenants WHERE id = ?", tenantId);
        boolean address = exists(
            "SELECT EXISTS (SELECT 1 FROM locations WHERE tenant_id = ? AND is_fulfillment AND "
                + SetupStatus.ADDRESS_COMPLETE_SQL + ")", tenantId);
        boolean firstReceiving = exists(
            "SELECT EXISTS (SELECT 1 FROM receipts WHERE tenant_id = ? " +
            "AND finalized_at IS NOT NULL AND status <> 'reversed')", tenantId);
        boolean courier = courierDone(jdbc, tenantId);
        boolean testLabel = Boolean.TRUE.equals(prefs.get("testLabelPrinted"))
            || exists("SELECT EXISTS (SELECT 1 FROM label_reprints WHERE tenant_id = ?)", tenantId);

        List<String> skipped = skippedOf(prefs);
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(step("shopify",         shopify,        false, skipped));
        steps.add(step("address",         address,        false, skipped));
        steps.add(step("first_receiving", firstReceiving, false, skipped));
        steps.add(step("courier",         courier,        true,  skipped));
        steps.add(step("test_label",      testLabel,      true,  skipped));

        List<Map<String, Object>> tasks = new ArrayList<>();
        tasks.add(task("receive", firstReceiving));
        tasks.add(task("pack", exists(
            "SELECT EXISTS (SELECT 1 FROM piece_events e WHERE e.tenant_id = ? AND " + REAL_PACK_SQL + ")", tenantId)));
        tasks.add(task("pickup", exists(
            "SELECT EXISTS (SELECT 1 FROM pickups WHERE tenant_id = ? AND session_status = 'closed')", tenantId)));
        tasks.add(task("returns", exists(
            "SELECT EXISTS (SELECT 1 FROM return_sessions WHERE tenant_id = ? AND status = 'closed') " +
            "    OR EXISTS (SELECT 1 FROM piece_events WHERE tenant_id = ? AND event_type = 'return_received')",
            tenantId, tenantId)));
        tasks.add(task("transfer", exists(
            "SELECT EXISTS (SELECT 1 FROM transfers WHERE tenant_id = ? AND status <> 'cancelled')", tenantId)));
        tasks.add(task("stock_take", exists(
            "SELECT EXISTS (SELECT 1 FROM stock_take_sessions WHERE tenant_id = ? AND status <> 'cancelled')",
            tenantId)));
        tasks.add(task("portal", exists(
            "SELECT portal_enabled FROM tenants WHERE id = ?", tenantId)));
        tasks.add(task("team", exists(
            "SELECT count(*) > 1 FROM users WHERE tenant_id = ? AND active", tenantId)));
        if (Places.enabled(jdbc, tenantId)) {
            tasks.add(task("places", Places.anySetUp(jdbc, tenantId)));
        }

        Object card = prefs.get("card");
        boolean demoOrReview = com.traceability.review.ReviewCapabilities.scanHelpers(jdbc, tenantId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("steps", steps);
        out.put("tasks", tasks);
        out.put("card",  demoOrReview ? "hidden" : card instanceof String s && CARD_STATES.contains(s) ? s : "full");
        out.put("cardChosen", prefs.get("cardChosenAt") != null);
        out.put("setupCompletedAt", jdbc.queryForObject(
            "SELECT to_char(setup_completed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM tenants WHERE id = ?",
            String.class, tenantId));
        out.put("bostaConnected", exists(
            "SELECT EXISTS (SELECT 1 FROM courier_accounts " +
            "WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active')", tenantId));
        out.put("pickPackChosen", Boolean.TRUE.equals(prefs.get("pickPackChosen")));
        out.put("labelingPace", prefs.get("labelingPace") instanceof Number n ? n.intValue() : null);
        out.put("setupIntroSeen", Boolean.TRUE.equals(prefs.get("setupIntroSeen")));
        return out;
    }

    /** The courier step: an active Bosta account, OR other couriers switched on, OR a simulated courier. */
    static boolean courierDone(JdbcTemplate jdbc, UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM courier_accounts " +
                "WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active')", Boolean.class, tenantId))
            || GenericShipments.enabled(jdbc, tenantId)
            || CourierSimulation.isSimulated(jdbc, tenantId);
    }

    public static final String INTRO_COURIER = "courier", INTRO_READY = "ready";

    /**
     * The setup intro screen an owner is sent to next — {@link #INTRO_COURIER}, {@link #INTRO_READY} or null.
     * Caller's transaction and tenant context; the caller has already checked role = owner and setupStep = null.
     */
    public static String setupIntro(JdbcTemplate jdbc, UUID tenantId) {
        if (com.traceability.review.ReviewCapabilities.scanHelpers(jdbc, tenantId)) return null;
        Map<String, Object> p = jdbc.queryForMap(
            "SELECT COALESCE(onboarding_prefs->>'setupIntroSeen', '') = 'true' AS seen, " +
            "       COALESCE(onboarding_prefs->>'card', 'full') = 'hidden' AS hidden, " +
            "       COALESCE(onboarding_prefs->'skipped', '[]'::jsonb) @> '[\"courier\"]'::jsonb AS courier_skipped " +
            "FROM tenants WHERE id = ?", tenantId);
        if (Boolean.TRUE.equals(p.get("seen")) || Boolean.TRUE.equals(p.get("hidden"))) return null;
        if (!Boolean.TRUE.equals(p.get("courier_skipped")) && !courierDone(jdbc, tenantId)) return INTRO_COURIER;
        return INTRO_READY;
    }

    // ── Write (the ONE writer of tenants.onboarding_prefs) ───────────────────

    /**
     * Merges the given keys into the tenant's prefs (top-level jsonb ||; {@code skipped} replaces the
     * array, de-duplicated). Validates EVERYTHING before writing: an empty body, an unknown key, a null
     * or a wrongly-typed value → 400 and nothing is written.
     */
    @Transactional
    public Map<String, Object> updatePrefs(UUID actorUserId, Map<String, Object> patch) {
        UUID tenantId = TenantContext.require();
        Map<String, Object> clean = validate(patch);
        String json;
        try {
            json = mapper.writeValueAsString(clean);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prefs not serializable");
        }
        // A card written here is the user's choice: stamp it (server-only key, not in PREF_KEYS).
        jdbc.update("UPDATE tenants SET onboarding_prefs = onboarding_prefs || ?::jsonb " +
                    "|| CASE WHEN ? THEN jsonb_build_object('cardChosenAt', now()) ELSE '{}'::jsonb END WHERE id = ?",
            json, clean.containsKey("card"), tenantId);
        audit.record(actorUserId, "onboarding_prefs_set", "tenant", tenantId.toString(), clean);
        return progress();
    }

    /**
     * Hides the card — the same merge {@link #updatePrefs} does for {card: 'hidden'}. Also run by the legacy
     * POST /onboarding/dismiss (OnboardingController), so a dismissal made on the old card before O3 sticks.
     */
    static final String HIDE_CARD_SQL =
        "UPDATE tenants SET onboarding_prefs = onboarding_prefs || '{\"card\":\"hidden\"}'::jsonb WHERE id = ?";

    static Map<String, Object> validate(Map<String, Object> patch) {
        if (patch == null || patch.isEmpty()) throw bad("no prefs given");
        Map<String, Object> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            String key = e.getKey();
            Object v = e.getValue();
            if (!PREF_KEYS.contains(key)) throw bad("unknown pref: " + key);
            switch (key) {
                case "card" -> {
                    if (!(v instanceof String s) || !CARD_STATES.contains(s))
                        throw bad("card must be one of " + new TreeSet<>(CARD_STATES));
                    clean.put(key, s);
                }
                case "skipped" -> {
                    if (!(v instanceof List<?> list)) throw bad("skipped must be an array");
                    LinkedHashSet<String> out = new LinkedHashSet<>();
                    for (Object o : list) {
                        if (!(o instanceof String s) || !SKIPPABLE.contains(s))
                            throw bad("skipped may hold only " + new TreeSet<>(SKIPPABLE));
                        out.add(s);
                    }
                    clean.put(key, new ArrayList<>(out));
                }
                case "pickPackChosen", "testLabelPrinted", "setupIntroSeen" -> {
                    if (!(v instanceof Boolean b)) throw bad(key + " must be true or false");
                    clean.put(key, b);
                }
                case "labelingPace" -> {
                    if (!(v instanceof Integer i) || i < PACE_MIN || i > PACE_MAX)
                        throw bad("labelingPace must be a whole number from " + PACE_MIN + " to " + PACE_MAX);
                    clean.put(key, i);
                }
                default -> throw bad("unknown pref: " + key);
            }
        }
        return clean;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> readPrefs(UUID tenantId) {
        String raw = jdbc.queryForObject("SELECT onboarding_prefs::text FROM tenants WHERE id = ?",
            String.class, tenantId);
        if (raw == null) return Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = mapper.readValue(raw, Map.class);
            return m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static List<String> skippedOf(Map<String, Object> prefs) {
        List<String> out = new ArrayList<>();
        if (prefs.get("skipped") instanceof List<?> l) {
            for (Object o : l) if (o instanceof String s) out.add(s);
        }
        return out;
    }

    private boolean exists(String sql, Object... args) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, args));
    }

    private static Map<String, Object> step(String key, boolean done, boolean skippable, List<String> skipped) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key",       key);
        m.put("done",      done);
        m.put("skippable", skippable);
        m.put("skipped",   skippable && !done && skipped.contains(key));
        return m;
    }

    private static Map<String, Object> task(String key, boolean tried) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key",   key);
        m.put("tried", tried);
        return m;
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
