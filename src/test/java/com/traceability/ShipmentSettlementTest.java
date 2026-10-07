package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.integrations.bosta.ShipmentSettlement;
import com.traceability.integrations.bosta.ShipmentSettlement.Fields;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Analytics slice 3 — ShipmentSettlement.extract() over the payload shapes Bosta sends. */
class ShipmentSettlementTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode json(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void deliveredCod_paid_numbersAsStrings_dateFromTransactionId() {
        Fields f = ShipmentSettlement.extract(json(SettlementPayloads.DELIVERED_PAID));
        assertThat(f.depositedAt()).isEqualTo(Instant.parse("2026-09-01T08:08:44Z"));
        assertThat(f.depositedAmt()).isEqualByComparingTo("885.12");
        assertThat(f.codSettled()).isEqualByComparingTo("990.00");
        assertThat(f.bostaFees()).isEqualByComparingTo("104.88");
        assertThat(f.shippingFees()).isEqualByComparingTo("85.00");
        assertThat(f.vat()).isEqualByComparingTo("12.88");
        assertThat(f.openingPackageFees()).isEqualByComparingTo("7.00");
        assertThat(f.promotionDiscount()).isEqualByComparingTo("0");
        assertThat(f.shipmentFeesQuoted()).isEqualByComparingTo("92");
        assertThat(f.cashCycleId()).isEqualTo("76483748");
        assertThat(f.cashoutTxnId()).isEqualTo("WEDCOD09SEP26");
        assertThat(f.cashoutDate()).isEqualTo(LocalDate.of(2026, 9, 9));
        assertThat(f.cashoutAmount()).isNull();
        assertThat(f.provenStatus()).isEqualTo("paid");
        // Bosta's own identity on every settled prod row.
        assertThat(f.codSettled().subtract(f.bostaFees())).isEqualByComparingTo(f.depositedAmt());
    }

    @Test
    void rto_negativeDeposit_nextCashoutDate() {
        Fields f = ShipmentSettlement.extract(json(SettlementPayloads.RTO_DEPOSITED));
        assertThat(f.depositedAmt()).isEqualByComparingTo("-77.52");
        assertThat(f.codSettled()).isEqualByComparingTo("0");
        assertThat(f.cashoutTxnId()).isNull();
        assertThat(f.cashoutDate()).isNull();
        assertThat(f.nextCashoutDate()).isEqualTo(LocalDate.of(2026, 9, 9));
        assertThat(f.provenStatus()).isEqualTo("deposited");
    }

    @Test
    void crp_paid_batchTotal_transactionDateWins() {
        Fields f = ShipmentSettlement.extract(json(SettlementPayloads.CRP_PAID_WITH_BATCH));
        assertThat(f.depositedAmt()).isEqualByComparingTo("-106.02");
        assertThat(f.cashoutAmount()).isEqualByComparingTo("67854.59");
        assertThat(f.cashoutTxnId()).isEqualTo("MONCOD24AUG26");
        // transaction_date midnight UTC = 02:00/03:00 Cairo, same day.
        assertThat(f.cashoutDate()).isEqualTo(LocalDate.of(2026, 8, 24));
        assertThat(f.provenStatus()).isEqualTo("paid");
    }

    @Test
    void exchange_promotion() {
        Fields f = ShipmentSettlement.extract(json(SettlementPayloads.EXCHANGE_PROMO));
        assertThat(f.bostaFees()).isEqualByComparingTo("0");
        assertThat(f.shippingFees()).isEqualByComparingTo("94.00");
        assertThat(f.promotionDiscount()).isEqualByComparingTo("94.00");
        assertThat(f.depositedAmt()).isEqualByComparingTo("0");
        assertThat(f.provenStatus()).isEqualTo("deposited");
    }

    @Test
    void v2Item_nullWallet_saysNothingButTheQuote() {
        Fields f = ShipmentSettlement.extract(json(SettlementPayloads.V2_ITEM_NULL_WALLET));
        assertThat(f.depositedAt()).isNull();
        assertThat(f.bostaFees()).isNull();
        assertThat(f.cashCycleId()).isNull();
        assertThat(f.cashoutTxnId()).isNull();
        assertThat(f.nextCashoutDate()).isNull();
        assertThat(f.shipmentFeesQuoted()).isEqualByComparingTo("59");
        assertThat(f.provenStatus()).isEqualTo("none");
    }

    @Test
    void nullOrNonObject_isNone() {
        assertThat(ShipmentSettlement.extract(null)).isEqualTo(Fields.NONE);
        assertThat(ShipmentSettlement.extract(json("[]"))).isEqualTo(Fields.NONE);
        assertThat(ShipmentSettlement.extract(json("{\"state\":{\"code\":45}}"))).isEqualTo(Fields.NONE);
    }

    @Test
    void lenientNumbers_garbageIsNull() {
        Fields f = ShipmentSettlement.extract(json(
            "{\"shipmentFees\":\"n/a\",\"wallet\":{\"cashCycle\":{\"deposited_at\":\"not a date\"," +
            "\"deposited_amt\":\"12.5\",\"bosta_fees\":{\"x\":1},\"_id\":\"abc\"}," +
            "\"cashout\":{\"transaction_id\":\"\",\"amount\":7}}}"));
        assertThat(f.shipmentFeesQuoted()).isNull();
        assertThat(f.depositedAt()).isNull();
        assertThat(f.depositedAmt()).isEqualByComparingTo(new BigDecimal("12.5"));
        assertThat(f.bostaFees()).isNull();
        assertThat(f.cashCycleId()).isEqualTo("abc");
        assertThat(f.cashoutTxnId()).isNull();
        assertThat(f.cashoutAmount()).isEqualByComparingTo("7");
        assertThat(f.provenStatus()).isEqualTo("none");
    }

    @Test
    void transactionIdDate_parses_caseInsensitive_rejectsOtherShapes() {
        Fields mon = ShipmentSettlement.extract(json("{\"wallet\":{\"cashout\":{\"transaction_id\":\"MONCOD17AUG26\"}}}"));
        assertThat(mon.cashoutDate()).isEqualTo(LocalDate.of(2026, 8, 17));
        Fields odd = ShipmentSettlement.extract(json("{\"wallet\":{\"cashout\":{\"transaction_id\":\"TXN-123\"}}}"));
        assertThat(odd.cashoutTxnId()).isEqualTo("TXN-123");
        assertThat(odd.cashoutDate()).isNull();
        Fields bad = ShipmentSettlement.extract(json("{\"wallet\":{\"cashout\":{\"transaction_id\":\"WEDCOD31FEB26\"}}}"));
        assertThat(bad.cashoutDate()).isNull();
    }
}
