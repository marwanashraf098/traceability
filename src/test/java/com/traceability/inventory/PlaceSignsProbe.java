package com.traceability.inventory;

/** Place sticker sizes — test access to PlaceSigns' bar-width rule (package-private). */
public final class PlaceSignsProbe {

    private PlaceSignsProbe() {}

    /** The bar width PlaceSigns uses for {@code content} in {@code availW} points, A4 or A6. */
    public static float barWidthMm(String content, float availW, boolean a4) {
        return PlaceSigns.barWidthMm(content, availW, a4 ? PlaceSigns.A4_X_MM : PlaceSigns.A6_X_MM);
    }

    public static int quietModules() {
        return PlaceSigns.QUIET_MODULES;
    }
}
