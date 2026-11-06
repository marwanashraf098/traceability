package com.traceability.returncases;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** S2 — the ONE Shopify return state derivation (return status + its exchange fulfillment orders). */
class ShopifyReturnStateTest {

    private static ShopifyReturnState.Fo fo(String status, String... holds) {
        return new ShopifyReturnState.Fo(status, List.of(holds));
    }

    private static ShopifyReturnState.Derived d(String status, ShopifyReturnState.Fo... fos) {
        return ShopifyReturnState.derive(status, List.of(fos));
    }

    @Test
    void returnStatusWinsOverFulfillmentOrders() {
        assertThat(d("REQUESTED", fo("ON_HOLD", "AWAITING_PAYMENT")).state()).isEqualTo("requested");
        assertThat(d("CLOSED", fo("OPEN")).state()).isEqualTo("closed");
        assertThat(d("DECLINED", fo("OPEN")).state()).isEqualTo("declined");
        assertThat(d("CANCELED", fo("CLOSED")).state()).isEqualTo("cancelled");
    }

    @Test
    void openReturnWithoutExchangeFulfillmentOrderIsOpen() {
        assertThat(d("OPEN").state()).isEqualTo("open");
        assertThat(ShopifyReturnState.derive("OPEN", null).state()).isEqualTo("open");
        // cancelled / incomplete exchange FOs don't count
        assertThat(d("OPEN", fo("CANCELLED"), fo("INCOMPLETE")).state()).isEqualTo("open");
        // scheduled (fulfil at a later date) is not released yet
        assertThat(d("OPEN", fo("SCHEDULED")).state()).isEqualTo("open");
    }

    @Test
    void onHoldCarriesTheHoldReasons() {
        ShopifyReturnState.Derived held = d("OPEN", fo("ON_HOLD", "AWAITING_PAYMENT"));
        assertThat(held.state()).isEqualTo("on_hold");
        assertThat(held.holdReasons()).containsExactly("AWAITING_PAYMENT");
        // two held FOs: reasons distinct, in order; a held FO wins over a released one
        ShopifyReturnState.Derived two = d("OPEN", fo("OPEN"), fo("ON_HOLD", "AWAITING_PAYMENT", "OTHER"),
            fo("ON_HOLD", "AWAITING_PAYMENT"));
        assertThat(two.state()).isEqualTo("on_hold");
        assertThat(two.holdReasons()).containsExactly("AWAITING_PAYMENT", "OTHER");
    }

    @Test
    void releasedThenShipped() {
        assertThat(d("OPEN", fo("OPEN")).state()).isEqualTo("released");
        assertThat(d("OPEN", fo("IN_PROGRESS")).state()).isEqualTo("released");
        assertThat(d("OPEN", fo("CLOSED"), fo("OPEN")).state()).isEqualTo("released");
        assertThat(d("OPEN", fo("CLOSED")).state()).isEqualTo("shipped");
        assertThat(d("OPEN", fo("CLOSED"), fo("CANCELLED")).state()).isEqualTo("shipped");
        assertThat(d("OPEN", fo("CLOSED")).holdReasons()).isEmpty();
    }

    @Test
    void finishedStatuses() {
        assertThat(ShopifyReturnState.finished("CLOSED")).isTrue();
        assertThat(ShopifyReturnState.finished("DECLINED")).isTrue();
        assertThat(ShopifyReturnState.finished("CANCELED")).isTrue();
        assertThat(ShopifyReturnState.finished("OPEN")).isFalse();
        assertThat(ShopifyReturnState.finished(null)).isFalse();
    }
}
