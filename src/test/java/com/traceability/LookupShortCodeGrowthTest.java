package com.traceability;

import com.traceability.inventory.LookupController;
import com.traceability.inventory.ScanInputNormalizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flexible labeling slice 0 — Lookup routes a short code past P999999 as a piece.
 *
 * {@code InventoryLedger.batchReceive} formats short codes with {@code %06d}, a MINIMUM width:
 * the 1,000,000th piece of a tenant is {@code P1000000}. Every other scan path accepts
 * {@code P} + 6 or more digits ({@link ScanInputNormalizer}, {@code WaybillResolver.looksLikePieceCode});
 * Lookup accepted exactly 6 and sent a 7-digit code to the tracking / order lookups instead.
 *
 *   g1 — P + 7 and P + 8 digits route to the piece lookup
 *   g2 — Lookup's routing agrees with ScanInputNormalizer's piece shape for P codes
 *   g3 — still not a piece: fewer than 6 digits, a letter among the digits, bare digits
 */
class LookupShortCodeGrowthTest {

    @Test
    void g1_seven_and_eight_digit_short_codes_route_to_piece() {
        assertThat(LookupController.isPieceQuery("P1000000")).isTrue();
        assertThat(LookupController.isPieceQuery("P0000001")).isTrue();
        assertThat(LookupController.isPieceQuery("P12345678")).isTrue();
    }

    @Test
    void g2_routing_agrees_with_the_scan_normalizer_piece_shape() {
        for (String raw : new String[] { "p1000000", "P999999", "P1000000", "P12345678" }) {
            String normalized = ScanInputNormalizer.normalizePiece(raw);
            assertThat(normalized).startsWith("P");
            assertThat(LookupController.isPieceQuery(normalized))
                .as("normalized %s → %s", raw, normalized)
                .isTrue();
        }
    }

    @Test
    void g3_non_piece_shapes_still_do_not_route_to_piece() {
        assertThat(LookupController.isPieceQuery("P00001")).isFalse();    // 5 digits
        assertThat(LookupController.isPieceQuery("P")).isFalse();
        assertThat(LookupController.isPieceQuery("P100000A")).isFalse();
        assertThat(LookupController.isPieceQuery("P1000A00")).isFalse();
        assertThat(LookupController.isPieceQuery("10000000")).isFalse(); // a bare-digit AWB / order
    }
}
