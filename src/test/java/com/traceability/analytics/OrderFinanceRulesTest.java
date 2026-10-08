package com.traceability.analytics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Analytics slice 7 — the pure rules behind the order finance list and its CSV. */
class OrderFinanceRulesTest {

    static OrderFinanceService.Facts f(String outcome, boolean prepaid, boolean cashedOut, boolean notPaid,
                                       boolean stuck, boolean customerReturn) {
        return new OrderFinanceService.Facts(outcome, prepaid, cashedOut, notPaid, stuck, customerReturn);
    }

    static String status(OrderFinanceService.Facts f) {
        return OrderFinanceService.financialStatus(f);
    }

    @Test
    void financialStatus_firstMatchWins() {
        // Wijha beats everything, even facts that would otherwise mean paid / refunded.
        assertThat(status(f("wijha", true, true, true, true, true))).isEqualTo("other_carrier");
        assertThat(status(f("refused", true, true, false, false, true))).isEqualTo("lost");
        assertThat(status(f("other_terminal", false, false, false, true, false))).isEqualTo("lost");
        // Refunded beats paid.
        assertThat(status(f("delivered", false, true, false, false, true))).isEqualTo("refunded");
        assertThat(status(f("delivered", true, false, false, false, true))).isEqualTo("refunded");
        // Paid: a cashout (COD) or prepaid; paid beats overdue.
        assertThat(status(f("delivered", false, true, true, false, false))).isEqualTo("paid");
        assertThat(status(f("delivered", true, false, true, false, false))).isEqualTo("paid");
        // Overdue: delivered-not-paid, or in transit and stuck; awaiting otherwise.
        assertThat(status(f("delivered", false, false, true, false, false))).isEqualTo("overdue");
        assertThat(status(f("in_transit", false, false, false, true, false))).isEqualTo("overdue");
        assertThat(status(f("delivered", false, false, false, true, false))).isEqualTo("awaiting_payout");
        assertThat(status(f("in_transit", false, false, true, false, false))).isEqualTo("expected");
        assertThat(status(f("not_shipped", false, false, true, true, true))).isEqualTo("expected");
        // A return on an undelivered order is not "refunded".
        assertThat(status(f("in_transit", false, false, false, false, true))).isEqualTo("expected");
    }

    @Test
    void displayName_firstNameAndLastInitialOnly() {
        assertThat(OrderFinanceService.displayName("Mona Adel Hassan")).isEqualTo("Mona H.");
        assertThat(OrderFinanceService.displayName("  Omar   Said  ")).isEqualTo("Omar S.");
        assertThat(OrderFinanceService.displayName("Madonna")).isEqualTo("Madonna");
        assertThat(OrderFinanceService.displayName("منى عبد الله")).isEqualTo("منى ا.");
        assertThat(OrderFinanceService.displayName("Zoë 😀ok")).isEqualTo("Zoë 😀.");     // a whole code point
        assertThat(OrderFinanceService.displayName("   ")).isNull();
        assertThat(OrderFinanceService.displayName(null)).isNull();
    }

    @Test
    void deliveryStatus_plainLabels() {
        assertThat(OrderFinanceService.deliveryStatus("in_transit", "created").label()).isEqualTo("Booked");
        assertThat(OrderFinanceService.deliveryStatus("in_transit", "with_courier").label()).isEqualTo("With courier");
        assertThat(OrderFinanceService.deliveryStatus("in_transit", "exception").label()).isEqualTo("Delivery exception");
        assertThat(OrderFinanceService.deliveryStatus("refused", "returning").label()).isEqualTo("Refused, returning");
        assertThat(OrderFinanceService.deliveryStatus("refused", "returned").label()).isEqualTo("Refused");
        assertThat(OrderFinanceService.deliveryStatus("other_terminal", "lost").label()).isEqualTo("Lost");
        assertThat(OrderFinanceService.deliveryStatus("other_terminal", "terminated").label()).isEqualTo("Terminated");
        assertThat(OrderFinanceService.deliveryStatus("not_shipped", null).label()).isEqualTo("Not shipped");
        assertThat(OrderFinanceService.deliveryStatus("delivered", "exception").key()).isEqualTo("delivered");
    }

    @Test
    void csvText_quotesAndFormulaGuard() {
        assertThat(OrderFinanceController.text(null)).isEmpty();
        assertThat(OrderFinanceController.text("Cairo")).isEqualTo("Cairo");
        assertThat(OrderFinanceController.text("a,b")).isEqualTo("\"a,b\"");
        assertThat(OrderFinanceController.text("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(OrderFinanceController.text("=1+1")).isEqualTo("'=1+1");
        assertThat(OrderFinanceController.text("+20")).isEqualTo("'+20");
        assertThat(OrderFinanceController.text("-x")).isEqualTo("'-x");
        assertThat(OrderFinanceController.text("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(OrderFinanceController.text("line\nbreak")).isEqualTo("\"line\nbreak\"");
        assertThat(OrderFinanceController.DEFAULT_MAX_EXPORT_ROWS).isEqualTo(50_000);
    }

    /**
     * The export's audit INSERT needs the tenant set inside a transaction (app_user's RLS WITH
     * CHECK on audit_log); without one the real app answers 500 (seen on the app_user bench).
     */
    @Test
    void export_runsInOneReadWriteTransaction() throws Exception {
        java.lang.reflect.Method m = OrderFinanceService.class.getMethod("export", AnalyticsPeriod.class,
            OrderFinanceService.Filters.class, java.util.UUID.class, int.class);
        org.springframework.transaction.annotation.Transactional tx =
            m.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isFalse();
    }

    @Test
    void prepaid_bostaCodDecides_paymentGroupOnlyWithoutABostaLeg() {
        assertThat(OrderFinanceService.prepaid(true, java.math.BigDecimal.ZERO, "Manual")).isTrue();
        assertThat(OrderFinanceService.prepaid(true, java.math.BigDecimal.ZERO, "COD")).isTrue();
        assertThat(OrderFinanceService.prepaid(true, new java.math.BigDecimal("150"), "Card")).isFalse();
        assertThat(OrderFinanceService.prepaid(false, null, "Card")).isTrue();
        assertThat(OrderFinanceService.prepaid(true, null, "Card")).isTrue();            // COD unknown → group
        for (String g : new String[] {"Manual", "Mixed", "COD", "Other", null}) {
            assertThat(OrderFinanceService.prepaid(false, null, g)).as(g).isFalse();
        }
    }
}
