package com.traceability.analytics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Analytics slice 4 — the pure stock rules. */
class StockRulesTest {

    @Test
    void restock_roundsUp_neverNegative() {
        // 31 delivered in 30 days × 56 days = 57.87 units needed, 10 on hand + 2 coming back → 46.
        assertThat(StockAnalyticsService.suggestedUnits(31, 56, 10, 2)).isEqualTo(46);
        assertThat(StockAnalyticsService.suggestedUnits(30, 30, 30, 0)).isZero();      // exactly covered
        assertThat(StockAnalyticsService.suggestedUnits(30, 30, 29, 0)).isEqualTo(1);
        assertThat(StockAnalyticsService.suggestedUnits(3, 56, 50, 0)).isZero();       // never below 0
        assertThat(StockAnalyticsService.suggestedUnits(0, 56, 0, 0)).isZero();        // no sales, nothing
        assertThat(StockAnalyticsService.suggestedUnits(1, 1, 0, 0)).isEqualTo(1);     // 0.03 → 1 unit
    }

    @Test
    void velocityAndCover() {
        assertThat(StockAnalyticsService.velocity(31)).isEqualByComparingTo("1.03");
        assertThat(StockAnalyticsService.velocity(0)).isEqualByComparingTo("0.00");
        assertThat(StockAnalyticsService.cover(5, 30)).isEqualByComparingTo("5.0");
        assertThat(StockAnalyticsService.cover(0, 30)).isEqualByComparingTo("0.0");    // sold out: 0 days
        assertThat(StockAnalyticsService.cover(9, 60)).isEqualByComparingTo("4.5");
        assertThat(StockAnalyticsService.cover(5, 0)).isNull();                         // no sales: no cover
    }

    @Test
    void trip_isOutOfTheWarehouseOnly() {
        assertThat(StockAnalyticsService.trip("e"))
            .contains("e.from_status IN ('packed', 'awaiting_pickup', 'reserved')")
            .contains("e.to_status IN ('with_courier', 'delivered')");
    }
}
