package com.traceability;

import com.traceability.inventory.LookupController;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the scan-input routing predicate in LookupController.
 *
 * The search box receives raw scanner output and must route correctly:
 *   piece barcode  → lookupPiece()
 *   AWB / tracking → lookupTracking()
 *
 * Two piece formats exist:
 *   • Legacy: "PC-<ULID>" — old label batches already printed and in field use.
 *   • Current: bare 26-char ULID — labels generated after the MARGIN=0 barcode fix.
 *
 * Pure unit test — no Spring, no DB, no Docker.
 */
class LookupRoutingTest {

    // ── Piece queries (must route to lookupPiece) ─────────────────────────────

    @Test
    void r1_bare_ulid_routes_to_piece() {
        assertThat(LookupController.isPieceQuery("01HRTZP7DAQF8D3PD1XZH1T17W")).isTrue();
    }

    @Test
    void r2_pc_prefix_routes_to_piece() {
        assertThat(LookupController.isPieceQuery("PC-01HRTZP7DAQF8D3PD1XZH1T17W")).isTrue();
    }

    @Test
    void r3_ulid_with_all_valid_crockford_chars() {
        // All 32 Crockford characters represented (digits + eligible letters)
        assertThat(LookupController.isPieceQuery("0123456789ABCDEFGHJKMNPQRST")).isFalse(); // 27 chars
        assertThat(LookupController.isPieceQuery("0123456789ABCDEFGHJKMNPQRS")).isTrue();   // 26 chars, valid alphabet
    }

    // ── AWB / tracking queries (must route to lookupTracking) ─────────────────

    @Test
    void r4_pure_numeric_awb_routes_to_tracking() {
        assertThat(LookupController.isPieceQuery("9730639058")).isFalse();
    }

    @Test
    void r5_hub_prefixed_awb_routes_to_tracking() {
        // Bosta labels: hub-code dash tracking-number, e.g. "D-07-2944282510"
        assertThat(LookupController.isPieceQuery("D-07-2944282510")).isFalse();
    }

    @Test
    void r6_order_number_routes_to_tracking() {
        assertThat(LookupController.isPieceQuery("#1001")).isFalse();
        assertThat(LookupController.isPieceQuery("1001")).isFalse();
    }

    // ── Invalid Crockford chars (I, L, O, U) must not be mistaken for ULID ───

    @Test
    void r7_excluded_crockford_chars_are_rejected() {
        // ULID alphabet excludes I, L, O, U to avoid visual ambiguity with 1, 1, 0, V
        String withI = "01HRTZP7DAQF8D3PD1XZH1I17W"; // 'I' in position 22
        String withL = "01HRTZP7DAQF8D3PD1XZH1L17W"; // 'L'
        String withO = "01HRTZP7DAQF8D3PD1XZH1O17W"; // 'O'
        String withU = "01HRTZP7DAQF8D3PD1XZH1U17W"; // 'U'

        assertThat(LookupController.isPieceQuery(withI)).isFalse();
        assertThat(LookupController.isPieceQuery(withL)).isFalse();
        assertThat(LookupController.isPieceQuery(withO)).isFalse();
        assertThat(LookupController.isPieceQuery(withU)).isFalse();
    }

    @Test
    void r8_wrong_length_not_confused_with_ulid() {
        // 25 chars (one short) and 27 chars (one long) must not route to piece
        assertThat(LookupController.isPieceQuery("01HRTZP7DAQF8D3PD1XZH1T1")).isFalse();  // 25
        assertThat(LookupController.isPieceQuery("01HRTZP7DAQF8D3PD1XZH1T17WX")).isFalse(); // 27
    }
}
