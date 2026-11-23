package com.traceability.inventory;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * THE label size of every one-piece-per-page label PDF (receiving labels and reprints, Lookup / Returns / Transfer
 * reprints, code-first labels, label runs, tag stock, factory) — the tenant's Settings value
 * ({@code tenants.label_width_mm / label_height_mm}: 40×25, 50×25, or 60×12 = the jewelry rat-tail, RatTailLabel).
 * Not set → 50×25. Read on the caller's connection, inside its transaction (RLS: the tenant's own row).
 *
 * Packing labels are NOT on this setting: they stay {@link #PACK} (50×25) whatever the tenant chose — their order
 * separators have no small-label design yet. Warehouse place stickers keep their own fixed sizes.
 */
public final class LabelSizes {

    public static final float DEFAULT_WIDTH_MM = 50f, DEFAULT_HEIGHT_MM = 25f;

    /** Packing labels (order separators + labels, and a pack screen's replacement label): always 50×25. */
    public static final PieceLabelLayout.Spec PACK = PieceLabelLayout.Spec.of(DEFAULT_WIDTH_MM, DEFAULT_HEIGHT_MM);

    private LabelSizes() {}

    /** The tenant's piece-label size; 50×25 when none is set. */
    public static PieceLabelLayout.Spec forTenant(JdbcTemplate jdbc, UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT label_width_mm, label_height_mm FROM tenants WHERE id = ?", tenantId);
        if (rows.isEmpty()) return PieceLabelLayout.Spec.of(DEFAULT_WIDTH_MM, DEFAULT_HEIGHT_MM);
        Object w = rows.get(0).get("label_width_mm"), h = rows.get(0).get("label_height_mm");
        if (w == null || h == null) return PieceLabelLayout.Spec.of(DEFAULT_WIDTH_MM, DEFAULT_HEIGHT_MM);
        return PieceLabelLayout.Spec.of(((BigDecimal) w).floatValue(), ((BigDecimal) h).floatValue());
    }

    /**
     * A caller's explicit size wins (the optional widthMm / heightMm request parameters; a missing one = 50×25's);
     * neither given → the tenant's setting.
     */
    public static PieceLabelLayout.Spec resolve(JdbcTemplate jdbc, UUID tenantId, Float widthMm, Float heightMm) {
        if (widthMm == null && heightMm == null) return forTenant(jdbc, tenantId);
        return PieceLabelLayout.Spec.of(widthMm != null ? widthMm : DEFAULT_WIDTH_MM,
            heightMm != null ? heightMm : DEFAULT_HEIGHT_MM);
    }
}
