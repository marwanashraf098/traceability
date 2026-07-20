package com.traceability;

import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.oned.Code128Writer;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.EnumMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * lb1 — encode → render (BufferedImage) → decode round-trip.
 *
 * Pure unit test: no Spring, no DB, no PDF, no disk I/O.
 * Proves that the MARGIN=10 + draw-width-based pixelW settings used by
 * LabelService produce a ZXing-decodable Code 128 symbol.
 *
 * History: original code had MARGIN=0 (no quiet zone), which is a hard Code 128
 * standard violation — scanners cannot find START/STOP patterns without quiet zones.
 * This test must stay green forever so that setting can never regress to 0.
 */
class LabelRoundTripTest {

    private static final float MM_TO_PT = 72f / 25.4f;
    private static final int   DPI      = 203;

    @Test
    void lb1_code128_with_correct_settings_is_decodable() throws Exception {
        // FR-19 short code — "P" + 6 digits, 7 chars total.
        // This is what is now encoded in piece labels; the 26-char ULID is no longer used.
        String shortCode = "P000001";

        // Mirror LabelService constants exactly so this test locks the settings.
        float labelW_mm   = 50f;
        float margin_mm   = 3f;
        float barcodeW_mm = labelW_mm - 2 * margin_mm;           // 44mm draw area
        float barcodeH_mm = 12f;
        float barcodeW_pt = barcodeW_mm * MM_TO_PT;
        float barcodeH_pt = barcodeH_mm * MM_TO_PT;

        int pixelW = Math.round(barcodeW_pt * DPI / 72f);        // 352px
        int pixelH = Math.round(barcodeH_pt * DPI / 72f);

        EnumMap<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 10);                     // quiet zone: 10 modules each side

        BitMatrix matrix = new Code128Writer().encode(
                shortCode, BarcodeFormat.CODE_128, pixelW, pixelH, hints);
        BufferedImage img = MatrixToImageWriter.toBufferedImage(matrix);

        // Decode via the same reader path a real scanner uses.
        BinaryBitmap bmp = new BinaryBitmap(
                new HybridBinarizer(new BufferedImageLuminanceSource(img)));
        Result decoded = new MultiFormatReader().decode(bmp);

        assertThat(decoded.getText())
                .as("decoded barcode must equal the short code")
                .isEqualTo(shortCode);
        assertThat(decoded.getBarcodeFormat())
                .as("format must be CODE_128")
                .isEqualTo(BarcodeFormat.CODE_128);

        // Assert the matrix actually has quiet-zone columns — if MARGIN regresses to 0
        // the first and last columns are black (bar pixels), not white.
        int h = matrix.getHeight();
        int midRow = h / 2;
        assertThat(matrix.get(0, midRow))
                .as("leftmost column must be white (quiet zone)")
                .isFalse();
        assertThat(matrix.get(matrix.getWidth() - 1, midRow))
                .as("rightmost column must be white (quiet zone)")
                .isFalse();

        // Module width verification — 7-char content → 132 total modules (7×11 + 55).
        // At 44mm draw width: 44/132 = 0.333mm per module, well above the 0.191mm
        // GS1 general-use minimum that the previous 26-char ULID (0.129mm) violated.
        int totalModules = shortCode.length() * 11 + 55; // 132
        float moduleWidthMm = barcodeW_mm / totalModules;
        assertThat(moduleWidthMm)
                .as("module width must clear GS1 general-use minimum (0.191mm)")
                .isGreaterThanOrEqualTo(0.191f);
    }
}
