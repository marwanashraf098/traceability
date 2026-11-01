package com.traceability.places;

/**
 * Place sticker sizes — the words printed on a place sticker, EN or AR: the kind, the box's path line
 * ("on Z1-R01-S2" / "Z1 floor") and the Damaged band. Codes, names and the location are printed as they are.
 */
record StickerText(String zone, String rack, String shelf, String box, String onFormat, String floorFormat,
                   String damaged) {

    static final StickerText EN = new StickerText("Zone", "Rack", "Shelf", "Box", "on %s", "%s floor", "DAMAGED");
    static final StickerText AR = new StickerText("منطقة", "وحدة رفوف", "رف", "صندوق", "على %s", "أرضية %s", "تالف");

    /** 'ar' (any case, or ar-EG …) → Arabic; anything else → English. */
    static StickerText of(String lang) {
        return lang != null && lang.strip().toLowerCase(java.util.Locale.ROOT).startsWith("ar") ? AR : EN;
    }

    String kind(String kind) {
        return switch (kind) {
            case "zone" -> zone;
            case "rack" -> rack;
            case "shelf" -> shelf;
            default -> box;
        };
    }

    String on(String parentCode) {
        return String.format(onFormat, parentCode);
    }

    String floor(String zoneCode) {
        return String.format(floorFormat, zoneCode);
    }
}
