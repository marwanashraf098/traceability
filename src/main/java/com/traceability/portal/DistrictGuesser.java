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
 *
 * <p><b>Less ambiguity (2026-11-08, prod dry run: ~55% ambiguous)</b> — still never a pre-select while ambiguous:
 * <ul>
 *   <li>a name that is just a city / governorate name (any Bosta city, EN or AR) or only generic words
 *       ({@link #GENERIC}, numbers) matches nothing;</li>
 *   <li>SAME_ZONE — when every candidate left is in ONE Bosta zone and Bosta serves them the same way (pickup and
 *       drop-off flags equal), they count as one: the district named like the zone, else the first by name;</li>
 *   <li>BOSTA_FIELD — still ambiguous: the match is redone on the Bosta delivery's own zone and secondLine only; a
 *       single district there (or one zone, as above) that was among the candidates wins.</li>
 * </ul>
 */
public final class DistrictGuesser {

    private DistrictGuesser() {}

    public enum Outcome { EXACT, CONTAINS, SAME_ZONE, BOSTA_FIELD, AMBIGUOUS, NONE }

    public record Guess(Outcome outcome, String districtId) {
        static final Guess NONE = new Guess(Outcome.NONE, null);
        static final Guess AMBIGUOUS = new Guess(Outcome.AMBIGUOUS, null);
    }

    /**
     * A candidate: one pickup-available district of the fixed city with its names, its Bosta zone id and whether Bosta
     * also delivers there (null zoneId = no same-zone grouping).
     */
    public record Candidate(String districtId, String name, String nameAr, String zoneName, String zoneNameAr,
                            String zoneId, boolean dropoff) {
        public Candidate(String districtId, String name, String nameAr, String zoneName, String zoneNameAr) {
            this(districtId, name, nameAr, zoneName, zoneNameAr, null, false);
        }
    }

    /** One address text; {@code bosta} = the Bosta delivery's own zone or secondLine (preferred when ambiguous). */
    public record Field(String text, boolean bosta) {}

    /** Words that alone never name a district (normalized). Numbers are generic too. */
    static final Set<String> GENERIC = Set.of(
        "city", "new", "old", "district", "area", "zone", "street", "st", "road", "rd", "square", "sq", "west", "east",
        "north", "south", "first", "second", "third", "1st", "2nd", "3rd", "the", "of",
        "مدينه", "حي", "منطقه", "شارع", "طريق", "ميدان", "جديده", "جديد", "قديم", "غرب", "شرق", "شمال", "جنوب", "اول", "ثاني", "ثالث");

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

    /** Before 2026-11-08's ambiguity rules' inputs: plain fields, no city names to ignore. */
    public static Guess guess(Collection<String> addressFields, List<Candidate> candidates) {
        List<Field> fs = new ArrayList<>();
        for (String f : addressFields) fs.add(new Field(f, false));
        return guess(fs, candidates, Set.of());
    }

    /** THE guess. {@code cityNames}: every Bosta city / governorate name (raw), never a district match. */
    public static Guess guess(List<Field> fields, List<Candidate> candidates, Collection<String> cityNames) {
        Set<String> ignored = new HashSet<>();
        for (String c : cityNames) ignored.add(normalize(c));
        Map<String, Set<String>> byName = names(candidates, ignored);
        Map<String, Candidate> byId = new HashMap<>();
        for (Candidate c : candidates) byId.put(c.districtId(), c);

        Match all = match(texts(fields, false), byName);
        if (all.districts.isEmpty()) return Guess.NONE;
        if (all.districts.size() == 1) return new Guess(all.exact ? Outcome.EXACT : Outcome.CONTAINS, all.districts.iterator().next());
        String zone = sameZone(all.districts, byId);
        if (zone != null) return new Guess(Outcome.SAME_ZONE, zone);

        Match bosta = match(texts(fields, true), byName);
        Set<String> within = new TreeSet<>(bosta.districts);
        within.retainAll(all.districts);
        if (!within.isEmpty() && within.size() == bosta.districts.size()) {
            String pick = within.size() == 1 ? within.iterator().next() : sameZone(within, byId);
            if (pick != null) return new Guess(Outcome.BOSTA_FIELD, pick);
        }
        return Guess.AMBIGUOUS;
    }

    private record Match(Set<String> districts, boolean exact) {}

    private static List<String> texts(List<Field> fields, boolean bostaOnly) {
        List<String> out = new ArrayList<>();
        for (Field f : fields) {
            if (bostaOnly && !f.bosta()) continue;
            String n = normalize(f.text());
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    /** name → the districts it speaks for, without city names and all-generic names. */
    private static Map<String, Set<String>> names(List<Candidate> candidates, Set<String> ignored) {
        Map<String, Set<String>> byName = new HashMap<>();
        for (Candidate c : candidates) {
            for (String raw : new String[]{c.name(), c.nameAr(), c.zoneName(), c.zoneNameAr()}) {
                String n = normalize(raw);
                if (n.length() < MIN_EXACT || ignored.contains(n) || generic(n)) continue;
                byName.computeIfAbsent(n, k -> new TreeSet<>()).add(c.districtId());
            }
        }
        return byName;
    }

    static boolean generic(String normalized) {
        for (String t : normalized.split(" ")) {
            if (!GENERIC.contains(t) && !t.chars().allMatch(Character::isDigit)) return false;
        }
        return true;
    }

    /** Exact, else contains (longest name wins) — the districts hit. */
    private static Match match(List<String> fields, Map<String, Set<String>> byName) {
        if (fields.isEmpty()) return new Match(Set.of(), false);
        Set<String> exact = new TreeSet<>();
        for (String f : fields) {
            Set<String> ds = byName.get(f);
            if (ds != null) exact.addAll(ds);
        }
        if (!exact.isEmpty()) return new Match(exact, true);
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
        return new Match(districts, false);
    }

    /**
     * One zone, served the same way (every candidate known, same non-null zone id, same drop-off flag — all are
     * pickup-available) → its representative: the district named like the zone, else the first by name; else null.
     */
    private static String sameZone(Set<String> ids, Map<String, Candidate> byId) {
        String zone = null;
        Boolean dropoff = null;
        List<Candidate> cs = new ArrayList<>();
        for (String id : ids) {
            Candidate c = byId.get(id);
            if (c == null || c.zoneId() == null) return null;
            if (zone == null) { zone = c.zoneId(); dropoff = c.dropoff(); }
            else if (!zone.equals(c.zoneId()) || dropoff != c.dropoff()) return null;
            cs.add(c);
        }
        cs.sort(Comparator.comparing((Candidate c) -> normalize(c.name())).thenComparing(Candidate::districtId));
        for (Candidate c : cs) {
            if (!normalize(c.zoneName()).isEmpty() && normalize(c.name()).equals(normalize(c.zoneName()))) return c.districtId();
        }
        return cs.get(0).districtId();
    }
}
