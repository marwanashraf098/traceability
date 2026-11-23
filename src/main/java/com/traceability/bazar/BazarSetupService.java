package com.traceability.bazar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.account.OnboardingProgressService;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.ShopifyVariantStockGateway;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.inventory.ReceivingService;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Bazar B9b — the guided bazar setup for a store that signed up to sell at a bazar: pick the products → receive them
 * into Traced Main (labels or count only) → "Lower these at the location you took them from".
 *
 * <ul>
 *   <li><b>Gate</b> ({@link #on}): {@code BAZAR_SIGNUP_ENABLED} on AND the tenant's signup intent is 'bazar' AND
 *       {@code tenants.bazar_enabled}. Otherwise every endpoint answers 404 — normal tenants never see any of it.</li>
 *   <li><b>Receiving</b>: ONLY {@link ReceivingService}'s public methods (createSession → addLine → finalize), one
 *       print_stick receipt for the labelled products and one count_only receipt for the rest, in ONE transaction.
 *       Pieces are created by {@code InventoryLedger.batchReceive} inside finalize — this class writes no piece and no
 *       piece event ({@code BazarSetupNoStatusWriterGuardTest}). Shopify: finalize's existing +N at Traced Main, after
 *       commit; nothing here calls a Shopify write and nothing is written to any other Shopify location.</li>
 *   <li><b>The batch</b>: {@code onboarding_prefs.bazarSetup} = {eventId, requestId, labelledReceiptId,
 *       countOnlyReceiptId, pieces, receivedAt, loweredAt, loweredBy}, written only through
 *       {@link OnboardingProgressService#writeBazarSetup}. The same requestId again returns the same batch. A new batch
 *       is refused while the last one hasn't been lowered (its reminder would be lost).</li>
 *   <li><b>"N in your other locations"</b>: Shopify's total minus Traced Main, read live and READ ONLY through
 *       {@link ShopifyVariantStockGateway} with no DB transaction open during the call; a failed read just hides it.</li>
 * </ul>
 */
@Service
public class BazarSetupService {

    private static final Logger log = LoggerFactory.getLogger(BazarSetupService.class);
    static final int STOCK_PAGE_SIZE = 100;
    static final int STOCK_MAX_PAGES = 5;
    static final int MAX_LINES = 500;
    static final int MAX_QTY   = 10_000;
    static final int MAX_PRODUCTS = 2_000;
    static final String DEFAULT_TITLE = "Default Title";
    /** Shopify's size order (the variant's position, from its stored REST payload) — sizes S, M, L, XL stay in order. */
    static final String VARIANT_ORDER_SQL =
        "CASE WHEN v.raw->>'position' ~ '^[0-9]{1,6}$' THEN (v.raw->>'position')::int END NULLS LAST, v.title, v.id";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate readTx;
    private final BazarSignup signup;
    private final ReceivingService receiving;
    private final OnboardingProgressService prefs;
    private final StoreRepository stores;
    private final ShopifyTokenProvider tokens;
    private final ShopifyVariantStockGateway stockGateway;
    private final ObjectMapper mapper;

    public BazarSetupService(JdbcTemplate jdbc, PlatformTransactionManager txm, BazarSignup signup,
                             ReceivingService receiving, OnboardingProgressService prefs, StoreRepository stores,
                             ShopifyTokenProvider tokens, ShopifyVariantStockGateway stockGateway, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.readTx = new TransactionTemplate(txm);
        this.readTx.setReadOnly(true);
        this.signup = signup;
        this.receiving = receiving;
        this.prefs = prefs;
        this.stores = stores;
        this.tokens = tokens;
        this.stockGateway = stockGateway;
        this.mapper = mapper;
    }

    // ── Gate ─────────────────────────────────────────────────────────────────

    /** The ONE gate. Caller's transaction and tenant context. */
    boolean on(UUID tenantId) {
        return on(jdbc, tenantId, signup.enabled());
    }

    /** The ONE gate, for GET /me's {@code bazarSetup} (UserService) too. {@code signupEnabled} = BazarSignup.enabled(). */
    public static boolean on(JdbcTemplate jdbc, UUID tenantId, boolean signupEnabled) {
        if (!signupEnabled) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT bazar_enabled AND COALESCE(onboarding_prefs->>'intent', '') = ? FROM tenants WHERE id = ?",
            Boolean.class, OnboardingProgressService.INTENT_BAZAR, tenantId));
    }

    private UUID requireOn() {
        UUID tenantId = TenantContext.require();
        // Cheap before any SQL: the server setting off answers 404 with no read at all.
        if (!signup.enabled() || !on(tenantId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return tenantId;
    }

    // ── Status (Overview card, Get ready line, the wizard's resume) ──────────

    @Transactional(readOnly = true)
    public Map<String, Object> status(boolean owner) {
        UUID tenantId = requireOn();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("on", true);
        out.put("canSetUp", owner);
        out.put("batch", batchView(tenantId, readBatch(tenantId, false)));
        return out;
    }

    private Map<String, Object> batchView(UUID tenantId, Map<String, Object> b) {
        if (b == null) return null;
        Map<String, Object> v = new LinkedHashMap<>();
        UUID eventId = uuid(b.get("eventId"));
        v.put("eventId", eventId);
        List<Map<String, Object>> ev = eventId == null ? List.of() : jdbc.queryForList(
            "SELECT name, status FROM bazar_events WHERE id = ? AND tenant_id = ?", eventId, tenantId);
        v.put("eventName", ev.isEmpty() ? null : ev.get(0).get("name"));
        v.put("eventStatus", ev.isEmpty() ? null : ev.get(0).get("status"));
        v.put("labelledReceiptId", uuid(b.get("labelledReceiptId")));
        v.put("countOnlyReceiptId", uuid(b.get("countOnlyReceiptId")));
        v.put("pieces", lowerTotal(tenantId, b));
        v.put("receivedAt", b.get("receivedAt"));
        v.put("lowered", b.get("loweredAt") != null);
        v.put("loweredAt", b.get("loweredAt"));
        return v;
    }

    // ── Products to pick (catalog + "N in your other locations") ─────────────

    /** No transaction around this method: the Shopify read runs with none open. */
    public Map<String, Object> products() {
        UUID tenantId = TenantContext.require();
        List<Map<String, Object>> catalog = readTx.execute(st -> {
            if (!signup.enabled() || !on(tenantId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            return catalog(tenantId);
        });
        Map<String, Integer> other = new HashMap<>();
        boolean known = otherLocations(tenantId, other);
        for (Map<String, Object> p : catalog) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> vs = (List<Map<String, Object>>) p.get("variants");
            for (Map<String, Object> v : vs) {
                String gid = (String) v.remove("externalId");
                Integer n = gid == null ? null : other.get(gid);
                v.put("otherLocations", known ? (n == null ? Integer.valueOf(0) : n) : n);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("products", catalog);
        out.put("stockKnown", known);
        return out;
    }

    private List<Map<String, Object>> catalog(UUID tenantId) {
        Map<UUID, Map<String, Object>> byProduct = new LinkedHashMap<>();
        jdbc.query(
            "SELECT pr.id AS product_id, pr.title AS product_title, pr.image_url, " +
            "       v.id AS variant_id, v.title AS variant_title, v.sku, v.price, v.external_id " +
            "FROM products pr JOIN variants v ON v.product_id = pr.id AND v.tenant_id = pr.tenant_id " +
            "WHERE pr.tenant_id = ? AND pr.status = 'active' " +
            "ORDER BY lower(pr.title), pr.id, " + VARIANT_ORDER_SQL,
            rs -> {
                UUID pid = (UUID) rs.getObject("product_id");
                Map<String, Object> p = byProduct.get(pid);
                if (p == null) {
                    if (byProduct.size() >= MAX_PRODUCTS) return;
                    p = new LinkedHashMap<>();
                    p.put("id", pid);
                    p.put("title", rs.getString("product_title"));
                    p.put("imageUrl", rs.getString("image_url"));
                    p.put("variants", new ArrayList<Map<String, Object>>());
                    byProduct.put(pid, p);
                }
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("id", rs.getObject("variant_id"));
                String title = rs.getString("variant_title");
                v.put("title", DEFAULT_TITLE.equals(title) ? null : title);
                v.put("sku", rs.getString("sku"));
                BigDecimal price = rs.getBigDecimal("price");
                v.put("price", price);
                v.put("externalId", rs.getString("external_id"));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> vs = (List<Map<String, Object>>) p.get("variants");
                vs.add(v);
            }, tenantId);
        return new ArrayList<>(byProduct.values());
    }

    /**
     * Fills {@code out} with variant GID → Shopify total minus Traced Main (never below 0), most stock first, up to
     * {@link #STOCK_MAX_PAGES} pages. Returns true when every variant with stock was read (a variant not in
     * {@code out} then has 0 elsewhere); false when the read failed or stopped early (unknown → hidden).
     */
    private boolean otherLocations(UUID tenantId, Map<String, Integer> out) {
        StoreRepository.Store store = readTx.execute(st -> stores.findActiveStoreByTenant(tenantId).orElse(null));
        if (store == null) return false;
        String mainGid = readTx.execute(st -> {
            List<String> l = jdbc.queryForList(
                "SELECT shopify_location_id FROM locations WHERE tenant_id = ? AND is_fulfillment = true " +
                "  AND shopify_sync_status = 'linked' AND shopify_location_id IS NOT NULL", String.class, tenantId);
            return l.isEmpty() ? null : l.get(0);
        });
        try {
            String token = tokens.getValidToken(store.id());
            String after = null;
            for (int page = 0; page < STOCK_MAX_PAGES; page++) {
                ShopifyVariantStockGateway.Page p =
                    stockGateway.topVariantsByStock(store.shopDomain(), token, mainGid, after, STOCK_PAGE_SIZE);
                boolean reachedZero = false;
                for (ShopifyVariantStockGateway.VariantStock v : p.variants()) {
                    if (v.inventoryQuantity() <= 0) { reachedZero = true; continue; }
                    int atMain = v.onHandAtLocation() == null ? 0 : v.onHandAtLocation();
                    out.put(v.variantGid(), Math.max(0, v.inventoryQuantity() - atMain));
                }
                if (reachedZero || !p.hasNextPage() || p.endCursor() == null) return true;
                after = p.endCursor();
            }
            return false;
        } catch (ShopifyVariantStockGateway.VariantStockReadException e) {
            log.warn("Bazar setup: Shopify stock read failed tenant={} reason={}", tenantId, e.reason());
        } catch (Exception e) {
            log.warn("Bazar setup: Shopify stock read failed tenant={} cause={}", tenantId, e.getClass().getSimpleName());
        }
        out.clear();
        return false;
    }

    // ── Receive into Traced Main ─────────────────────────────────────────────

    public record Line(UUID variantId, Integer quantity, Boolean labels) {}

    @Transactional
    public Map<String, Object> receive(UUID eventId, String requestId, List<Line> lines, UUID actorUserId) {
        UUID tenantId = requireOn();
        if (requestId == null || requestId.isBlank() || requestId.length() > 100) {
            throw invalid("Missing request id.", "معرّف الطلب مفقود.");
        }
        // The tenant row is the batch's lock: two receives (a double tap, a retry) run one after the other.
        Map<String, Object> current = readBatch(tenantId, true);
        if (current != null && requestId.equals(current.get("requestId"))) {
            return status(true);
        }
        if (current != null && current.get("loweredAt") == null) {
            throw new BazarException(BazarException.Code.BAZAR_SETUP_LOWER_FIRST,
                "Lower the last list at the location you took the pieces from first, then add more.",
                "خفّض القائمة السابقة في الموقع الذي أخذت منه القطع أولًا، ثم أضف المزيد.", HttpStatus.CONFLICT);
        }
        if (eventId == null) throw invalid("Choose the bazar first.", "اختر البازار أولًا.");
        List<Map<String, Object>> ev = jdbc.queryForList(
            "SELECT name, status FROM bazar_events WHERE id = ? AND tenant_id = ?", eventId, tenantId);
        if (ev.isEmpty()) {
            throw new BazarException(BazarException.Code.BAZAR_EVENT_NOT_FOUND,
                "Bazar not found.", "لم يتم العثور على البازار.", HttpStatus.NOT_FOUND);
        }
        if (!"draft".equals(ev.get(0).get("status"))) {
            throw new BazarException(BazarException.Code.BAZAR_EVENT_NOT_DRAFT,
                "This bazar has already started.", "هذا البازار بدأ بالفعل.", HttpStatus.CONFLICT);
        }

        // Validate every line before anything is created.
        List<Line> labelled = new ArrayList<>(), countOnly = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        int pieces = 0;
        for (Line l : lines == null ? List.<Line>of() : lines) {
            if (l == null || l.variantId() == null || l.quantity() == null || l.quantity() < 1 || l.quantity() > MAX_QTY) {
                throw invalid("Each size needs a quantity from 1 to " + MAX_QTY + ".",
                    "كل مقاس يحتاج كمية من 1 إلى " + MAX_QTY + ".");
            }
            if (!seen.add(l.variantId())) throw invalid("A size is listed twice.", "مقاس مكرر في القائمة.");
            Boolean known = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM variants v JOIN products p ON p.id = v.product_id AND p.tenant_id = v.tenant_id " +
                "WHERE v.id = ? AND v.tenant_id = ? AND p.status = 'active')", Boolean.class, l.variantId(), tenantId);
            if (!Boolean.TRUE.equals(known)) {
                throw new BazarException(BazarException.Code.BAZAR_VARIANT_NOT_FOUND,
                    "A product you picked isn't in your catalog any more.", "منتج اخترته لم يعد في الكتالوج.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
            }
            (Boolean.FALSE.equals(l.labels()) ? countOnly : labelled).add(l);
            pieces += l.quantity();
        }
        if (seen.isEmpty()) {
            throw new BazarException(BazarException.Code.BAZAR_SETUP_NOTHING_PICKED,
                "Pick at least one piece to take.", "اختر قطعة واحدة على الأقل.", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (seen.size() > MAX_LINES) throw invalid("Too many sizes at once.", "مقاسات كثيرة جدًا دفعة واحدة.");
        List<UUID> main = jdbc.queryForList(
            "SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment", UUID.class, tenantId);
        if (main.isEmpty()) {
            throw new BazarException(BazarException.Code.BAZAR_SETUP_NO_MAIN_WAREHOUSE,
                "Your store has no main warehouse yet.", "لا يوجد مستودع رئيسي لمتجرك بعد.", HttpStatus.CONFLICT);
        }
        UUID mainId = main.get(0);
        String name = (String) ev.get(0).get("name");
        String reference = ("Bazar · " + name).length() > 120 ? ("Bazar · " + name).substring(0, 120) : "Bazar · " + name;

        // The existing receiving paths only: createSession → addLine → finalize (→ InventoryLedger.batchReceive).
        UUID labelledId = labelled.isEmpty() ? null
            : receiveOne(actorUserId, mainId, reference, ReceivingService.MODE_PRINT_STICK, labelled);
        UUID countOnlyId = countOnly.isEmpty() ? null
            : receiveOne(actorUserId, mainId, reference, ReceivingService.MODE_COUNT_ONLY, countOnly);

        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("eventId", eventId.toString());
        batch.put("requestId", requestId);
        batch.put("labelledReceiptId", labelledId == null ? null : labelledId.toString());
        batch.put("countOnlyReceiptId", countOnlyId == null ? null : countOnlyId.toString());
        batch.put("pieces", pieces);
        batch.put("receivedAt", Instant.now().toString());
        batch.put("loweredAt", null);
        batch.put("loweredBy", null);
        prefs.writeBazarSetup(actorUserId, batch, "bazar_setup_received");
        return status(true);
    }

    private UUID receiveOne(UUID actor, UUID mainId, String reference, String mode, List<Line> lines) {
        UUID session = receiving.createSession(actor, mainId, reference, null, "Bazar setup", mode);
        for (Line l : lines) receiving.addLine(session, l.variantId(), l.quantity());
        receiving.finalize(session, actor);
        return session;
    }

    // ── "Lower these at the location you took them from" ─────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> lowerList() {
        UUID tenantId = requireOn();
        Map<String, Object> b = readBatch(tenantId, false);
        if (b == null) throw noList();
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = lowerRows(tenantId, b);
        out.put("rows", rows);
        out.put("total", rows.stream().mapToInt(r -> ((Number) r.get("quantity")).intValue()).sum());
        out.put("lowered", b.get("loweredAt") != null);
        out.put("loweredAt", b.get("loweredAt"));
        return out;
    }

    /**
     * Every variant the batch received and how many — labelled and count-only pieces alike (both are pieces of the
     * batch's receipts; the list never says which).
     */
    private List<Map<String, Object>> lowerRows(UUID tenantId, Map<String, Object> b) {
        List<UUID> receipts = receiptsOf(b);
        if (receipts.isEmpty()) return List.of();
        String in = String.join(",", Collections.nCopies(receipts.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(receipts);
        List<Map<String, Object>> rows = new ArrayList<>();
        jdbc.query(
            "SELECT v.id AS variant_id, pr.title AS product_title, v.title AS variant_title, v.sku, count(*)::int AS qty " +
            "FROM pieces p JOIN variants v ON v.id = p.variant_id AND v.tenant_id = p.tenant_id " +
            "JOIN products pr ON pr.id = v.product_id AND pr.tenant_id = v.tenant_id " +
            "WHERE p.tenant_id = ? AND p.receipt_id IN (" + in + ") " +
            "GROUP BY v.id, pr.title, v.title, v.sku, v.raw " +
            "ORDER BY lower(pr.title), pr.title, " + VARIANT_ORDER_SQL,
            rs -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("variantId", rs.getObject("variant_id"));
                r.put("productTitle", rs.getString("product_title"));
                String vt = rs.getString("variant_title");
                r.put("variantTitle", DEFAULT_TITLE.equals(vt) ? null : vt);
                r.put("sku", rs.getString("sku"));
                r.put("quantity", rs.getInt("qty"));
                rows.add(r);
            }, args.toArray());
        return rows;
    }

    private int lowerTotal(UUID tenantId, Map<String, Object> b) {
        return lowerRows(tenantId, b).stream().mapToInt(r -> ((Number) r.get("quantity")).intValue()).sum();
    }

    /** The list as CSV (UTF-8 with a BOM so Excel reads Arabic). Columns: product, size, SKU, quantity. */
    @Transactional(readOnly = true)
    public String lowerCsv(boolean arabic) {
        UUID tenantId = requireOn();
        Map<String, Object> b = readBatch(tenantId, false);
        if (b == null) throw noList();
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(arabic ? "المنتج,المقاس,SKU,الكمية" : "Product,Size,SKU,Quantity").append("\r\n");
        for (Map<String, Object> r : lowerRows(tenantId, b)) {
            String size = r.get("variantTitle") == null ? (arabic ? "مقاس واحد" : "One size") : (String) r.get("variantTitle");
            sb.append(csvCell((String) r.get("productTitle"))).append(',')
              .append(csvCell(size)).append(',')
              .append(csvCell((String) r.get("sku"))).append(',')
              .append(r.get("quantity")).append("\r\n");
        }
        return sb.toString();
    }

    /** One CSV cell: quoted, quotes doubled, and a leading = + - @ (a spreadsheet formula) defused with a '. */
    public static String csvCell(String v) {
        if (v == null) return "";
        String s = v;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** "Done, I lowered them" — stamps the batch once (one audit row); again is a no-op. */
    @Transactional
    public Map<String, Object> lowerDone(UUID actorUserId, boolean owner) {
        UUID tenantId = requireOn();
        Map<String, Object> b = readBatch(tenantId, true);
        if (b == null) throw noList();
        if (b.get("loweredAt") == null) {
            Map<String, Object> next = new LinkedHashMap<>(b);
            next.put("loweredAt", Instant.now().toString());
            next.put("loweredBy", actorUserId.toString());
            prefs.writeBazarSetup(actorUserId, next, "bazar_setup_lowered");
        }
        return status(owner);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> readBatch(UUID tenantId, boolean forUpdate) {
        String raw = jdbc.queryForObject(
            "SELECT (onboarding_prefs->'" + OnboardingProgressService.BAZAR_SETUP_KEY + "')::text FROM tenants WHERE id = ?" +
            (forUpdate ? " FOR UPDATE" : ""), String.class, tenantId);
        if (raw == null || "null".equals(raw)) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = mapper.readValue(raw, Map.class);
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<UUID> receiptsOf(Map<String, Object> b) {
        List<UUID> out = new ArrayList<>();
        UUID a = uuid(b.get("labelledReceiptId")), c = uuid(b.get("countOnlyReceiptId"));
        if (a != null) out.add(a);
        if (c != null) out.add(c);
        return out;
    }

    private static UUID uuid(Object o) {
        if (o == null) return null;
        try { return UUID.fromString(o.toString()); } catch (IllegalArgumentException e) { return null; }
    }

    private static BazarException noList() {
        return new BazarException(BazarException.Code.BAZAR_SETUP_NO_LIST,
            "There's nothing to lower yet.", "لا يوجد شيء لتخفيضه بعد.", HttpStatus.CONFLICT);
    }

    private static BazarException invalid(String en, String ar) {
        return new BazarException(BazarException.Code.BAZAR_INVALID_INPUT, en, ar, HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
