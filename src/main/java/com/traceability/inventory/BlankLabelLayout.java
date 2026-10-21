package com.traceability.inventory;

import java.util.List;

/**
 * Where things go on one BLANK label (flexible labeling): the Code 128 of the code and the code as
 * text — no product, variant or SKU. Pure geometry, no PDF; drawn by LabelPdfRenderer.renderBlank.
 * The piece label (PieceLabelLayout) is untouched by this class.
 *
 * Same horizontal rules as a piece label (barcode x = 3 mm, width W − 6 mm, so the module width and
 * quiet zones are the same), 1.5 mm top/bottom margins. With no text rows to make room for, the
 * barcode is taller — every row of the label height the code line doesn't need, capped at 15 mm —
 * and the barcode + code block is centred vertically.
 */
public final class BlankLabelLayout {

    static final float CODE_SIZE = 9f;
    static final float MAX_BARCODE_HEIGHT_MM = 15f;

    private BlankLabelLayout() {}

    public static PieceLabelLayout.Layout layout(PieceLabelLayout.Spec spec, String code) {
        float pageW = spec.widthMm() * PieceLabelLayout.MM, pageH = spec.heightMm() * PieceLabelLayout.MM;
        float textW = pageW - 2 * PieceLabelLayout.TEXT_SIDE;

        LabelTextFitter.Fit fit = LabelTextFitter.fit(code == null ? "" : code.strip(),
            LabelFonts.Face.BOLD, CODE_SIZE, CODE_SIZE, 1, textW);
        LabelTextFitter.Line line = fit.lines().get(0);
        float[] m = PieceLabelLayout.lineMetrics(line.runs());
        float codeH = m[1] * fit.size();

        float room = pageH - 2 * PieceLabelLayout.MARGIN_TB - PieceLabelLayout.BARCODE_GAP - codeH;
        float barcodeH = Math.min(room, MAX_BARCODE_HEIGHT_MM * PieceLabelLayout.MM);
        float blockTop = pageH - (pageH - (barcodeH + PieceLabelLayout.BARCODE_GAP + codeH)) / 2f;

        PieceLabelLayout.Box barcode = new PieceLabelLayout.Box(
            PieceLabelLayout.BARCODE_X, blockTop - barcodeH, pageW - 2 * PieceLabelLayout.BARCODE_X, barcodeH);
        float top = barcode.y() - PieceLabelLayout.BARCODE_GAP;
        PieceLabelLayout.TextLine text = new PieceLabelLayout.TextLine(PieceLabelLayout.Row.CODE, line.runs(),
            line.logical(), fit.size(), (pageW - line.width()) / 2f, top - m[0] * fit.size(), line.width(), m[0], m[1]);
        return new PieceLabelLayout.Layout(pageW, pageH, barcode, List.of(text), List.of(), List.of(), fit.ellipsized());
    }
}
