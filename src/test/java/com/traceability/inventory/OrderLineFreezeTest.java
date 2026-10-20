package com.traceability.inventory;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OrderLineFreeze — the one freeze definition (Shopify order edits, 2026-10-21).
 *
 *   z1 PieceStatus.LEFT_CUSTODY is exactly with_courier, delivered, return_in_transit,
 *      return_pending_inspection — a reordering of the enum that changes the range fails here
 *   z2 frozenSql starts with the ALREADY_SHIPPED predicate FulfillService.scan()/complete() use
 *   z3 the repair script inlines frozenSql("o.id", "o.tenant_id") verbatim (whitespace aside)
 */
class OrderLineFreezeTest {

    @Test
    void z1_leftCustody_isTheCourierAndReturnLifecycle() {
        assertThat(PieceStatus.LEFT_CUSTODY).isEqualTo(EnumSet.of(
            PieceStatus.WITH_COURIER, PieceStatus.DELIVERED,
            PieceStatus.RETURN_IN_TRANSIT, PieceStatus.RETURN_PENDING_INSPECTION));
        assertThat(OrderLineFreeze.leftCustodySqlList())
            .isEqualTo("'with_courier', 'delivered', 'return_in_transit', 'return_pending_inspection'");
    }

    @Test
    void z2_frozenSql_includesTheShippedGuard() {
        assertThat(OrderLineFreeze.frozenSql("o.id", "o.tenant_id"))
            .startsWith("(" + OrderLineFreeze.shippedPastCreatedSql("o.id", "o.tenant_id"))
            .contains("pe_fh.order_id = oi_fh.order_id")
            .contains("a_fz.status = 'packed'");
    }

    @Test
    void z3_repairScript_inlinesTheFreezeVerbatim() throws Exception {
        String script = Files.readString(Path.of("scripts/ops/2026-10-21-order-edit-quantity-repair.sql"));
        assertThat(strip(script)).contains("ANDNOT" + strip(OrderLineFreeze.frozenSql("o.id", "o.tenant_id")));
    }

    private static String strip(String s) {
        return s.replaceAll("\\s+", "");
    }
}
