package com.traceability.inventory;

import com.traceability.integrations.bosta.BostaReferenceParser;
import com.traceability.integrations.bosta.PreConnectDeliveryFilter;
import com.traceability.portal.PreConnectParentImport;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Exchange reference S2 (2026-11-18) — the exchange drawer's "Link to order": which of the tenant's orders a dashboard
 * exchange's old item comes back from.
 *
 * <ul>
 *   <li>{@link #search}: merchant_orders only (never an internal order — exchange replacement, shadow, pre-connect
 *       row), at most {@value #LIMIT}, newest first ({@code created_at DESC, id DESC}). A typed query matches the order
 *       number through {@link BostaReferenceParser} (so "#OD2394", "OD2394", "wjwy1a-ia:#5126" all work; digits / the
 *       number's middle also match as a substring from 3 characters), the customer phone (Egyptian formats, Arabic-Indic
 *       digits, through {@link ShipmentLinkService#normalizePhone} and the matcher's own phone expression) and the
 *       customer name (ILIKE, Arabic letters folded: أ إ آ ٱ → ا, ى → ي, ة → ه, tatweel and harakat dropped). An empty
 *       query suggests the orders of Bosta's receiver phone on the exchange — that phone is only compared, never
 *       returned. Each hit carries what the order drawer already shows: number, date, customer name, items.</li>
 *   <li>{@code importOption}: a clean typed number that matched no order, while the tenant has a connected store and
 *       the number would be a pre-connect one ({@link PreConnectDeliveryFilter#preConnectNumber}) — "Import from
 *       Shopify". No Shopify call while searching.</li>
 *   <li>{@link #importParent}: the S1 path, unchanged — {@link PreConnectParentImport#importParent} (the one Shopify
 *       read, NO transaction open), then, when {@code link}, {@link ExchangeMatchService#matchToPreConnectParent}. A
 *       pre-connect parent is identified by its NUMBER here, never by an order id (attach refuses portal rows). A miss
 *       is an answer (status + the importer's reason), not an error.</li>
 * </ul>
 * Owner / manager only (ExchangeController). Every read runs in a transaction under the caller's tenant.
 */
@Service
public class ExchangeOrderSearch {

    static final int LIMIT = 20;
    private static final int MAX_QUERY = 100;
    private static final int MIN_NUMBER_SUBSTRING = 3;
    private static final Pattern NUMBER_TEXT = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final Pattern PHONE_TEXT = Pattern.compile("^[+0-9\\s().-]{8,}$");
    /** Arabic harakat (fathatan … sukun) and the superscript alef — dropped from both sides of a name compare. */
    private static final String HARAKAT_CLASS = "[ً-ْٰ]";
    /** translate(): alef forms → ا, alef maqsura → ي, taa marbuta → ه, tatweel removed (no counterpart). */
    private static final String AR_FROM = "\u0623\u0625\u0622\u0671\u0649\u0629\u0640";
    private static final String AR_TO = "\u0627\u0627\u0627\u0627\u064A\u0647";

    public enum ImportStatus {
        /** Stored (or already stored) — not linked yet; the drawer shows its items to confirm. */
        FOUND,
        /** Linked: the exchange's original is now this pre-connect order. */
        LINKED,
        /** Shopify had no single pre-connect order of that number (the importer's reason says which). */
        NOT_FOUND,
        /** Shopify couldn't be asked (token / Shopify failure / store not eligible). */
        UNAVAILABLE,
        /** The number isn't a clean order number, or isn't below the lowest ingested one. */
        NOT_PRE_CONNECT
    }

    public record Item(String productTitle, String variantTitle, String sku, int quantity) {}

    public record OrderHit(String id, String number, Object placedAt, String customerName, List<Item> items) {}

    public record ImportOption(String number) {}

    public record SearchResult(String query, boolean suggested, List<OrderHit> orders, ImportOption importOption) {}

    public record ImportResult(ImportStatus status, String reason, String number, List<Item> items,
                               String exchangeStatus) {}

    private record Exchange(String trackingNumber, UUID outboundOrderId, String receiverPhone, boolean open) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final PreConnectDeliveryFilter preConnectFilter;
    private final PreConnectParentImport parentImport;
    private final ExchangeMatchService matchService;

    public ExchangeOrderSearch(JdbcTemplate jdbc, PlatformTransactionManager txm,
                               PreConnectDeliveryFilter preConnectFilter, PreConnectParentImport parentImport,
                               ExchangeMatchService matchService) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.readTx = new TransactionTemplate(txm);
        this.readTx.setReadOnly(true);
        this.preConnectFilter = preConnectFilter;
        this.parentImport = parentImport;
        this.matchService = matchService;
    }

    // ── search ──────────────────────────────────────────────────────────────────────────────────

    public SearchResult search(UUID exchangeId, String query) {
        UUID tenantId = TenantContext.require();
        return TenantContext.runAs(tenantId, () -> readTx.execute(s -> searchInTx(tenantId, exchangeId, query)));
    }

    private SearchResult searchInTx(UUID tenantId, UUID exchangeId, String query) {
        Exchange ex = loadExchange(tenantId, exchangeId);
        String term = query == null ? "" : ScanInputNormalizer.foldDigits(query.strip());
        if (term.length() > MAX_QUERY) term = term.substring(0, MAX_QUERY);

        if (term.isEmpty()) {
            String phone = ShipmentLinkService.normalizePhone(
                ex.receiverPhone() == null ? null : ScanInputNormalizer.foldDigits(ex.receiverPhone()));
            if (phone == null) return new SearchResult("", true, List.of(), null);
            List<OrderHit> hits = find(tenantId, ex, List.of(ExchangeMatchService.ORDER_PHONE_MATCH_EXPR + " = ?"),
                List.of(phone));
            return new SearchResult("", true, hits, null);
        }

        List<String> clauses = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        Set<String> variants = BostaReferenceParser.numberVariants(term, false);
        if (!variants.isEmpty()) {
            clauses.add("o.number IN (" + placeholders(variants.size()) + ")");
            args.addAll(variants);
        }
        String bare = term.startsWith("#") ? term.substring(1).strip() : term;
        if (bare.length() >= MIN_NUMBER_SUBSTRING && NUMBER_TEXT.matcher(bare).matches()) {
            clauses.add("ltrim(o.number, '#') ILIKE ? ESCAPE '\\'");
            args.add("%" + escapeLike(bare) + "%");
        }
        if (PHONE_TEXT.matcher(term).matches()) {
            String phone = ShipmentLinkService.normalizePhone(term);
            if (phone != null) {
                clauses.add(ExchangeMatchService.ORDER_PHONE_MATCH_EXPR + " = ?");
                args.add(phone);
            }
        }
        if (term.codePoints().anyMatch(Character::isLetter) && term.length() >= 2) {
            clauses.add(arabicFold("o.customer_name") + " ILIKE ? ESCAPE '\\'");
            args.add(HARAKAT_CLASS);
            args.add("%" + escapeLike(foldArabic(term)) + "%");
        }
        List<OrderHit> hits = clauses.isEmpty() ? List.of() : find(tenantId, ex, clauses, args);

        ImportOption option = null;
        String number = BostaReferenceParser.orderNumber(term);
        if (number != null && ex.open() && hits.stream().noneMatch(h -> sameNumber(h.number(), number))
                && connectedStore(tenantId) && preConnectFilter.preConnectNumber(tenantId, number)) {
            option = new ImportOption(number);
        }
        return new SearchResult(term, false, hits, option);
    }

    /** The name fold above, in SQL: the harakat class is the first bind parameter of the clause. */
    private static String arabicFold(String col) {
        return "regexp_replace(translate(lower(coalesce(" + col + ", '')), '" + AR_FROM + "', '" + AR_TO + "'), ?, '', 'g')";
    }

    static String foldArabic(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            int at = AR_FROM.indexOf(c);
            if (at >= 0) {
                if (at < AR_TO.length()) b.append(AR_TO.charAt(at));
                continue;
            }
            if ((c >= 'ً' && c <= 'ْ') || c == 'ٰ') continue;
            b.append(c);
        }
        return b.toString();
    }

    private List<OrderHit> find(UUID tenantId, Exchange ex, List<String> clauses, List<Object> clauseArgs) {
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(ex.outboundOrderId());
        args.addAll(clauseArgs);
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT o.id, o.number, o.placed_at, o.customer_name FROM merchant_orders o " +
            "WHERE o.tenant_id = ? AND o.external_id NOT LIKE 'internal:%' AND o.id IS DISTINCT FROM ? " +
            "  AND (" + String.join(" OR ", clauses) + ") " +
            "ORDER BY o.created_at DESC, o.id DESC LIMIT " + LIMIT,
            args.toArray());
        if (rows.isEmpty()) return List.of();
        List<UUID> ids = rows.stream().map(r -> (UUID) r.get("id")).toList();
        Map<UUID, List<Item>> items = items(tenantId, ids);
        List<OrderHit> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            UUID id = (UUID) r.get("id");
            out.add(new OrderHit(id.toString(), (String) r.get("number"), r.get("placed_at"),
                (String) r.get("customer_name"), items.getOrDefault(id, List.of())));
        }
        return out;
    }

    /** Lines of these orders (order_items — no orders table read): product, variant, SKU, quantity. */
    private Map<UUID, List<Item>> items(UUID tenantId, List<UUID> orderIds) {
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(orderIds);
        Map<UUID, List<Item>> out = new LinkedHashMap<>();
        jdbc.query(
            "SELECT oi.order_id, coalesce(pr.title, oi.raw->>'title') AS product_title, " +
            "       coalesce(v.title, oi.raw->>'variant_title') AS variant_title, v.sku, oi.quantity " +
            "FROM order_items oi " +
            "LEFT JOIN variants v ON v.id = oi.variant_id AND v.tenant_id = oi.tenant_id " +
            "LEFT JOIN products pr ON pr.id = v.product_id " +
            "WHERE oi.tenant_id = ? AND oi.order_id IN (" + placeholders(orderIds.size()) + ") " +
            "ORDER BY oi.order_id, product_title NULLS LAST, variant_title NULLS LAST, oi.id",
            rs -> {
                out.computeIfAbsent(rs.getObject("order_id", UUID.class), k -> new ArrayList<>())
                    .add(new Item(rs.getString("product_title"), rs.getString("variant_title"),
                        rs.getString("sku"), rs.getInt("quantity")));
            },
            args.toArray());
        return out;
    }

    // ── import (S1 path) ────────────────────────────────────────────────────────────────────────

    /**
     * Imports (or finds the already-stored) pre-connect order of this number and, when {@code link}, links the exchange
     * to it. MUST run with no transaction open — the importer calls Shopify.
     */
    public ImportResult importParent(UUID exchangeId, String rawNumber, boolean link) {
        UUID tenantId = TenantContext.require();
        Exchange ex = TenantContext.runAs(tenantId, () -> readTx.execute(s -> loadExchange(tenantId, exchangeId)));
        if (!ex.open()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Exchange is not open for manual attach");
        }
        String number = rawNumber == null ? null
            : BostaReferenceParser.orderNumber(ScanInputNormalizer.foldDigits(rawNumber.strip()));
        if (number == null) return new ImportResult(ImportStatus.NOT_PRE_CONNECT, "NO_NUMBER", null, List.of(), null);
        boolean preConnect = Boolean.TRUE.equals(TenantContext.runAs(tenantId, () -> readTx.execute(s ->
            connectedStore(tenantId) && preConnectFilter.preConnectNumber(tenantId, number))));
        if (!preConnect) return new ImportResult(ImportStatus.NOT_PRE_CONNECT, "NOT_PRE_CONNECT", number, List.of(), null);

        PreConnectParentImport.Outcome o = parentImport.importParent(tenantId, number);
        if (o.orderId() == null) {
            ImportStatus st = switch (o.reason()) {
                case TOKEN_UNAVAILABLE, SHOPIFY_ERROR, INELIGIBLE_STORE -> ImportStatus.UNAVAILABLE;
                case NOT_PRE_CONNECT, NO_NUMBER -> ImportStatus.NOT_PRE_CONNECT;
                default -> ImportStatus.NOT_FOUND;   // NOT_FOUND, AMBIGUOUS, NO_CATALOG_LINES, CACHED_MISS
            };
            return new ImportResult(st, o.reason().name(), number, List.of(), null);
        }
        UUID parentId = o.orderId();
        List<Item> items = TenantContext.runAs(tenantId, () -> readTx.execute(s ->
            items(tenantId, List.of(parentId)).getOrDefault(parentId, List.of())));
        if (!link) return new ImportResult(ImportStatus.FOUND, o.reason().name(), number, items, null);

        String status = TenantContext.runAs(tenantId, () -> tx.execute(s -> {
            if (!matchService.matchToPreConnectParent(tenantId, ex.trackingNumber(), parentId)) return null;
            return jdbc.queryForObject("SELECT status FROM exchanges WHERE id = ? AND tenant_id = ?",
                String.class, exchangeId, tenantId);
        }));
        if (status == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Exchange is not open for manual attach");
        }
        return new ImportResult(ImportStatus.LINKED, o.reason().name(), number, items, status);
    }

    // ── shared ──────────────────────────────────────────────────────────────────────────────────

    private Exchange loadExchange(UUID tenantId, UUID exchangeId) {
        List<Exchange> rows = jdbc.query(
            "SELECT tracking_number, outbound_order_id, raw->'receiver'->>'phone' AS receiver_phone, " +
            "       (" + ExchangeMatchService.MANUAL_ATTACH_OPEN_SQL + ") AS open " +
            "FROM exchanges WHERE id = ? AND tenant_id = ?",
            (rs, i) -> new Exchange(rs.getString("tracking_number"), rs.getObject("outbound_order_id", UUID.class),
                rs.getString("receiver_phone"), rs.getBoolean("open")),
            exchangeId, tenantId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exchange not found");
        return rows.get(0);
    }

    private boolean connectedStore(UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM stores WHERE tenant_id = ? AND status = 'connected')",
            Boolean.class, tenantId));
    }

    private static boolean sameNumber(String orderNumber, String number) {
        if (orderNumber == null) return false;
        String n = orderNumber.startsWith("#") ? orderNumber.substring(1) : orderNumber;
        return n.equalsIgnoreCase(number);
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String placeholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }
}
