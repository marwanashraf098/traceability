package com.traceability.geo;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Egypt's governorates in ONE place: the Shopify / ISO-3166-2 province codes, their EN / AR names
 * for pickers, and the Bosta city each one maps to.
 *
 * {@link #TO_BOSTA_CITY} — Shopify Egypt province codes → Bosta city names
 * (dropOffAddress.city.name), used by analytics for orders not booked with Bosta yet and by the
 * onboarding address picker. 6th of October (SU) and Helwan (HU) are legacy codes Shopify still
 * accepts; Bosta files them under Giza / Cairo.
 *
 * {@link #PICKER} — the 27 current governorates (no SU / HU), in display order.
 */
public final class EgyptProvinces {

    private EgyptProvinces() {}

    public record Governorate(String code, String nameEn, String nameAr) {
        public String bostaCity() { return TO_BOSTA_CITY.get(code); }
    }

    public static final Map<String, String> TO_BOSTA_CITY = Map.ofEntries(
        Map.entry("C", "Cairo"), Map.entry("HU", "Cairo"), Map.entry("GZ", "Giza"), Map.entry("SU", "Giza"),
        Map.entry("ALX", "Alexandria"), Map.entry("DK", "Dakahlia"), Map.entry("KB", "El Kalioubia"),
        Map.entry("SHR", "Sharqia"), Map.entry("GH", "Gharbia"), Map.entry("MNF", "Monufia"),
        Map.entry("BH", "Behira"), Map.entry("DT", "Damietta"), Map.entry("PTS", "Port Said"),
        Map.entry("SUZ", "Suez"), Map.entry("IS", "Ismailia"), Map.entry("BA", "Red Sea"),
        Map.entry("JS", "South Sinai"), Map.entry("SIN", "North Sinai"), Map.entry("MT", "Matrouh"),
        Map.entry("KFS", "Kafr Alsheikh"), Map.entry("FYM", "Fayoum"), Map.entry("BNS", "Bani Suif"),
        Map.entry("MN", "Menya"), Map.entry("AST", "Assuit"), Map.entry("SHG", "Sohag"),
        Map.entry("KN", "Qena"), Map.entry("LX", "Luxor"), Map.entry("ASN", "Aswan"),
        Map.entry("WAD", "New Valley"));

    public static final List<Governorate> PICKER = List.of(
        new Governorate("C",   "Cairo",          "القاهرة"),
        new Governorate("GZ",  "Giza",           "الجيزة"),
        new Governorate("ALX", "Alexandria",     "الإسكندرية"),
        new Governorate("KB",  "Qalyubia",       "القليوبية"),
        new Governorate("DK",  "Dakahlia",       "الدقهلية"),
        new Governorate("SHR", "Sharqia",        "الشرقية"),
        new Governorate("GH",  "Gharbia",        "الغربية"),
        new Governorate("MNF", "Monufia",        "المنوفية"),
        new Governorate("BH",  "Beheira",        "البحيرة"),
        new Governorate("KFS", "Kafr El Sheikh", "كفر الشيخ"),
        new Governorate("DT",  "Damietta",       "دمياط"),
        new Governorate("PTS", "Port Said",      "بورسعيد"),
        new Governorate("IS",  "Ismailia",       "الإسماعيلية"),
        new Governorate("SUZ", "Suez",           "السويس"),
        new Governorate("FYM", "Faiyum",         "الفيوم"),
        new Governorate("BNS", "Beni Suef",      "بني سويف"),
        new Governorate("MN",  "Minya",          "المنيا"),
        new Governorate("AST", "Asyut",          "أسيوط"),
        new Governorate("SHG", "Sohag",          "سوهاج"),
        new Governorate("KN",  "Qena",           "قنا"),
        new Governorate("LX",  "Luxor",          "الأقصر"),
        new Governorate("ASN", "Aswan",          "أسوان"),
        new Governorate("BA",  "Red Sea",        "البحر الأحمر"),
        new Governorate("MT",  "Matrouh",        "مطروح"),
        new Governorate("WAD", "New Valley",     "الوادي الجديد"),
        new Governorate("SIN", "North Sinai",    "شمال سيناء"),
        new Governorate("JS",  "South Sinai",    "جنوب سيناء"));

    /** A current governorate by code (case-insensitive); legacy SU / HU are not offered. */
    public static Optional<Governorate> pickable(String code) {
        if (code == null) return Optional.empty();
        String c = code.trim().toUpperCase(Locale.ROOT);
        return PICKER.stream().filter(g -> g.code().equals(c)).findFirst();
    }
}
