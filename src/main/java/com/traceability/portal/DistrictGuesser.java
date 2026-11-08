package com.traceability.portal;

import java.util.*;
import java.util.regex.Pattern;

/**
 * "Same address" area guess (2026-11-08). Bosta's live API needs a district on a pickup (RR-CM5NYQ was refused without
 * one), but 95% of Bosta deliveries carry only a city — so the portal asks the customer for the area, and pre-selects
 * it when the original address names exactly one of the city's pickup-available districts. The customer confirms or
 * changes it; nothing is ever booked from a guess the customer didn't submit.
 *
 * <p><b>Normalization</b> ({@link #normalize}, mirrored by the dry-run SQL — keep them in step): Arabic-Indic digits →
 * Latin; lower case; Arabic diacritics and tatweel removed; أ إ آ ٱ → ا, ة → ه, ى → ي, ؤ → و, ئ → ي; anything but
 * letters and digits → space; the articles "el" / "al" dropped as words and "ال" dropped from the start of a word of
 * 4+ letters; spaces collapsed.
 *
 * <p><b>Match</b> (exact or unique-contains only): a district's names are its district and zone names, English and
 * Arabic (a zone name speaks for every district of the zone). EXACT — some address field equals a name: one district
 * → it, more → AMBIGUOUS. Else CONTAINS — a name of 4+ characters appears as whole words inside a field; a matched
 * name that is part of a longer matched name is dropped ("maadi" inside "zahraa maadi"); one district left → it, more
 * → AMBIGUOUS, none → NONE. Never fuzzy.
 */
public final class DistrictGuesser {

    private DistrictGuesser() {}

    public enum Outcome { EXACT, CONTAINS, AMBIGUOUS, NONE }

    public record Guess(Outcome outcome, String districtId) {
        static final Guess NONE = new Guess(Outcome.NONE, null);
        static final Guess AMBIGUOUS = new Guess(Outcome.AMBIGUOUS, null);
    }

    /** A candidate: one pickup-available district of the fixed city with its names. */
    public record Candidate(String districtId, String name, String nameAr, String zoneName, String zoneNameAr) {}

    static final int MIN_EXACT = 3;
    static final int MIN_CONTAINS = 4;

    private static final Pattern DIACRITICS = Pattern.compile("[\\u064B-\\u065F\\u0670\\u0640]");
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");

    public static String normalize(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            if (ch >= '٠' && ch <= '٩') ch = (char) ('0' + (ch - '٠'));
            else if (ch >= '۰' && ch <= '۹') ch = (char) ('0' + (ch - '۰'));
            b.append(ch);
        }
        String x = b.toString().toLowerCase(Locale.ROOT);
        x = DIACRITICS.matcher(x).replaceAll("");
        x = x.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
             .replace('ة', 'ه').replace('ى', 'ي').replace('ؤ', 'و').replace('ئ', 'ي');
        x = NON_ALNUM.matcher(x).replaceAll(" ").trim();
        if (x.isEmpty()) return "";
        List<String> out = new ArrayList<>();
        for (String t : x.split(" ")) {
            if (t.equals("el") || t.equals("al")) continue;
            if (t.startsWith("ال") && t.length() >= 4) t = t.substring(2);
            out.add(t);
        }
        return String.join(" ", out);
    }

    public static Guess guess(Collection<String> addressFields, List<Candidate> candidates) {
        List<String> fields = new ArrayList<>();
        for (String f : addressFields) {
            String n = normalize(f);
            if (!n.isEmpty()) fields.add(n);
        }
        if (fields.isEmpty() || candidates.isEmpty()) return Guess.NONE;

        // name → the districts it speaks for
        Map<String, Set<String>> byName = new HashMap<>();
        for (Candidate c : candidates) {
            for (String raw : new String[]{c.name(), c.nameAr(), c.zoneName(), c.zoneNameAr()}) {
                String n = normalize(raw);
                if (n.length() >= MIN_EXACT) byName.computeIfAbsent(n, k -> new TreeSet<>()).add(c.districtId());
            }
        }

        Set<String> exact = new TreeSet<>();
        for (String f : fields) {
            Set<String> ds = byName.get(f);
            if (ds != null) exact.addAll(ds);
        }
        if (exact.size() == 1) return new Guess(Outcome.EXACT, exact.iterator().next());
        if (exact.size() > 1) return Guess.AMBIGUOUS;

        Set<String> matchedNames = new HashSet<>();
        for (String name : byName.keySet()) {
            if (name.length() < MIN_CONTAINS) continue;
            String needle = " " + name + " ";
            for (String f : fields) {
                if ((" " + f + " ").contains(needle)) { matchedNames.add(name); break; }
            }
        }
        Set<String> districts = new TreeSet<>();
        for (String name : matchedNames) {
            boolean partOfLonger = false;
            for (String other : matchedNames) {
                if (!other.equals(name) && (" " + other + " ").contains(" " + name + " ")) { partOfLonger = true; break; }
            }
            if (!partOfLonger) districts.addAll(byName.get(name));
        }
        if (districts.size() == 1) return new Guess(Outcome.CONTAINS, districts.iterator().next());
        return districts.isEmpty() ? Guess.NONE : Guess.AMBIGUOUS;
    }
}
