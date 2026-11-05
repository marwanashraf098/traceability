package com.traceability.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.shopify.ShopifyGateway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Returns portal fees F0 — what the pre-connect lookup stores as each portal line's 'paid_unit_price'.
 * The lookup merges an order's lines per variant, so the price is their quantity-weighted average.
 *
 *   p1 — one priced line → its price, verbatim at 2 dp.
 *   p2 — two lines of one variant (1 × 100, 3 × 200) → 175.00.
 *   p3 — a variant with one unpriced line gets no price at all (never a partial average).
 *   p4 — lines with quantity 0 (removed in an edit) and lines with no variant are ignored, as the insert does.
 */
class PortalPreConnectPaidPriceTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void p1_onePricedLine() {
        assertThat(PortalOnDemandLookup.paidUnitPriceByVariant(order(line("L1", "V1", 1, "640"))))
            .containsExactlyEntriesOf(Map.of("V1", "640.00"));
    }

    @Test
    void p2_linesOfOneVariant_weightedAverage() {
        assertThat(PortalOnDemandLookup.paidUnitPriceByVariant(order(
            line("L1", "V1", 1, "100.00"), line("L2", "V1", 3, "200.00"), line("L3", "V2", 2, "55.50"))))
            .containsExactlyInAnyOrderEntriesOf(Map.of("V1", "175.00", "V2", "55.50"));
    }

    @Test
    void p3_anUnpricedLine_noPriceForThatVariant() {
        assertThat(PortalOnDemandLookup.paidUnitPriceByVariant(order(
            line("L1", "V1", 1, "100.00"), line("L2", "V1", 1, null), line("L3", "V2", 1, "80.00"))))
            .containsExactlyEntriesOf(Map.of("V2", "80.00"));
    }

    @Test
    void p4_zeroQuantityAndVariantlessLinesIgnored() {
        assertThat(PortalOnDemandLookup.paidUnitPriceByVariant(order(
            line("L1", "V1", 0, null), line("L2", "V1", 2, "90.00"), line("L3", null, 1, "10.00"))))
            .containsExactlyEntriesOf(Map.of("V1", "90.00"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private record Line(String id, String variant, int qty, String price) {}

    private static Line line(String id, String variant, int qty, String price) { return new Line(id, variant, qty, price); }

    private static ShopifyGateway.Order order(Line... lines) {
        ObjectNode raw = M.createObjectNode();
        ArrayNode edges = raw.putObject("lineItems").putArray("edges");
        List<ShopifyGateway.LineItem> items = new ArrayList<>();
        for (Line l : lines) {
            ObjectNode n = edges.addObject().putObject("node");
            n.put("id", l.id());
            n.put("quantity", l.qty());
            if (l.variant() != null) n.putObject("variant").put("id", l.variant());
            items.add(new ShopifyGateway.LineItem(l.id(), l.qty(), l.variant(), l.qty(), l.price()));
        }
        return new ShopifyGateway.Order("gid://shopify/Order/1", "#1", null, null, null, "PENDING", List.of(),
            BigDecimal.ZERO, items, Instant.now(), raw);
    }
}
