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
 * <p><b>Less ambiguity (2026-11-08, prod dry runs)</b> — still never a pre-select while ambiguous:
 * <ul>
 *   <li>a name that is just a city / governorate name (any Bosta city, EN or AR) or only generic words
 *       ({@link #GENERIC}, numbers) matches nothing;</li>
 *   <li><b>zone names never identify a district</b> (prod: "New Cairo" in the Shopify city names all 119 of its
 *       districts). A matched name is a DISTRICT name when some district carries it as its own name and it is not that
 *       district's zone name ("Maadi" is El Maadi's name AND its zone's — a zone name); otherwise a ZONE name. Zone
 *       names never count as "exact" (an exact zone name no longer hides a district named inside address1) — they only
 *       narrow: district matches outside the matched zones are dropped (none left → both kept, which stays
 *       ambiguous);</li>
 *   <li>one district matched → EXACT / CONTAINS (SAME_ZONE when only a zone name matched and the zone has one);</li>
 *   <li>SAME_ZONE — several candidates, all in ONE Bosta zone, served alike (same drop-off flag), and at most
 *       {@link #SAME_ZONE_MAX} of them: the district named like the zone, else the first by name;</li>
 *   <li>BOSTA_FIELD — the match redone on the Bosta delivery's own zone and secondLine alone gives one district (by
 *       the rules above) among the candidates;</li>
 *   <li>ZONE_ONLY — the candidates are all in one zone but too many (or served differently): no district, the portal's
 *       picker opens filtered to that zone's districts ({@link Guess#zoneDistrictIds}); the customer can clear it.</li>
 * </ul>
 */
public final class DistrictGuesser {

    private DistrictGuesser() {}

    public enum Outcome { EXACT, CONTAINS, SAME_ZONE, BOSTA_FIELD, ZONE_ONLY, AMBIGUOUS, NONE }

    /** {@code zoneDistrictIds}: ZONE_ONLY only — every candidate district of the zone (sorted), else empty. */
    public record Guess(Outcome outcome, String districtId, List<String> zoneDistrictIds) {
        public Guess(Outcome outcome, String districtId) { this(outcome, districtId, List.of()); }
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
    /** SAME_ZONE pre-selects only out of this many districts at most (prod 2026-11-08: 19–119-district zones). */
    static final int SAME_ZONE_MAX = 3;

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
        Names names = names(candidates, ignored);
        Map<String, Candidate> byId = new HashMap<>();
        for (Candidate c : candidates) byId.put(c.districtId(), c);

        Match all = match(texts(fields, false), names);
        if (all.candidates.isEmpty()) return Guess.NONE;
        Guess one = decide(all, byId);
        if (one != null) return one;

        Match bosta = match(texts(fields, true), names);
        if (!bosta.candidates.isEmpty() && all.candidates.containsAll(bosta.candidates)) {
            Guess b = decide(bosta, byId);
            if (b != null) return new Guess(Outcome.BOSTA_FIELD, b.districtId());
        }
        List<String> zone = oneZone(all.candidates, byId);
        if (zone != null) {
            List<String> ids = new ArrayList<>();
            for (Candidate c : candidates) if (zone.get(0).equals(c.zoneId())) ids.add(c.districtId());
            Collections.sort(ids);
            return new Guess(Outcome.ZONE_ONLY, null, List.copyOf(ids));
        }
        return Guess.AMBIGUOUS;
    }

    /** One district → EXACT / CONTAINS (SAME_ZONE when only a zone name matched); a small served-alike zone → SAME_ZONE; else null. */
    private static Guess decide(Match m, Map<String, Candidate> byId) {
        if (m.candidates.size() == 1) {
            Outcome o = m.viaZone ? Outcome.SAME_ZONE : m.exact ? Outcome.EXACT : Outcome.CONTAINS;
            return new Guess(o, m.candidates.iterator().next());
        }
        if (m.candidates.size() > SAME_ZONE_MAX) return null;
        String rep = sameZone(m.candidates, byId);
        return rep == null ? null : new Guess(Outcome.SAME_ZONE, rep);
    }

    /** {@code exact}: the candidates are district names some field equals; {@code viaZone}: only zone names matched. */
    private record Match(Set<String> candidates, boolean exact, boolean viaZone) {}

    /** name → districts it is the OWN name of (not their zone's name); name → districts of the zone it names. */
    private record Names(Map<String, Set<String>> own, Map<String, Set<String>> zone) {
        Set<String> all() { Set<String> s = new HashSet<>(own.keySet()); s.addAll(zone.keySet()); return s; }
    }

    private static List<String> texts(List<Field> fields, boolean bostaOnly) {
        List<String> out = new ArrayList<>();
        for (Field f : fields) {
            if (bostaOnly && !f.bosta()) continue;
            String n = normalize(f.text());
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    /** Without city names and all-generic names; a district's name equal to its zone's name is a zone name only. */
    private static Names names(List<Candidate> candidates, Set<String> ignored) {
        Map<String, Set<String>> own = new HashMap<>();
        Map<String, Set<String>> zone = new HashMap<>();
        for (Candidate c : candidates) {
            Set<String> zoneNs = new HashSet<>();
            for (String raw : new String[]{c.zoneName(), c.zoneNameAr()}) {
                String n = normalize(raw);
                if (!usable(n, ignored)) continue;
                zoneNs.add(n);
                zone.computeIfAbsent(n, k -> new TreeSet<>()).add(c.districtId());
            }
            for (String raw : new String[]{c.name(), c.nameAr()}) {
                String n = normalize(raw);
                if (!usable(n, ignored) || zoneNs.contains(n)) continue;
                own.computeIfAbsent(n, k -> new TreeSet<>()).add(c.districtId());
            }
        }
        // A name some district owns is a district name everywhere (its zone role is dropped).
        zone.keySet().removeAll(own.keySet());
        return new Names(own, zone);
    }

    private static boolean usable(String n, Set<String> ignored) {
        return n.length() >= MIN_EXACT && !ignored.contains(n) && !generic(n);
    }

    static boolean generic(String normalized) {
        for (String t : normalized.split(" ")) {
            if (!GENERIC.contains(t) && !t.chars().allMatch(Character::isDigit)) return false;
        }
        return true;
    }

    /**
     * Every name some field equals (exact) or holds as whole words (4+ characters); a name inside a longer matched name
     * is dropped. District names: exact ones if any, else the contained ones. Zone names narrow them (or are the
     * candidates when no district name matched).
     */
    private static Match match(List<String> fields, Names names) {
        if (fields.isEmpty()) return new Match(Set.of(), false, false);
        Set<String> exactNames = new HashSet<>();
        Set<String> matched = new HashSet<>();
        for (String name : names.all()) {
            String needle = " " + name + " ";
            for (String f : fields) {
                if (f.equals(name)) { exactNames.add(name); matched.add(name); break; }
                if (name.length() >= MIN_CONTAINS && (" " + f + " ").contains(needle)) matched.add(name);
            }
        }
        Set<String> kept = new HashSet<>();
        for (String name : matched) {
            boolean partOfLonger = false;
            for (String other : matched) {
                if (!other.equals(name) && (" " + other + " ").contains(" " + name + " ")) { partOfLonger = true; break; }
            }
            if (!partOfLonger) kept.add(name);
        }
        Set<String> exactD = new TreeSet<>(), containsD = new TreeSet<>(), zoneD = new TreeSet<>();
        for (String name : kept) {
            Set<String> own = names.own().get(name);
            if (own != null) (exactNames.contains(name) ? exactD : containsD).addAll(own);
            else zoneD.addAll(names.zone().get(name));
        }
        boolean exact = !exactD.isEmpty();
        Set<String> d = exact ? exactD : containsD;
        if (d.isEmpty()) return new Match(zoneD, false, true);
        if (zoneD.isEmpty()) return new Match(d, exact, false);
        Set<String> inZone = new TreeSet<>(d);
        inZone.retainAll(zoneD);
        if (!inZone.isEmpty()) return new Match(inZone, exact, false);
        Set<String> both = new TreeSet<>(d);
        both.addAll(zoneD);
        return new Match(both, false, false);   // district and zone names disagree — stays ambiguous
    }

    /** Every candidate known, one non-null zone id → [zoneId]; else null (drop-off not compared). */
    private static List<String> oneZone(Set<String> ids, Map<String, Candidate> byId) {
        String zone = null;
        for (String id : ids) {
            Candidate c = byId.get(id);
            if (c == null || c.zoneId() == null) return null;
            if (zone == null) zone = c.zoneId();
            else if (!zone.equals(c.zoneId())) return null;
        }
        return zone == null ? null : List.of(zone);
    }

    /**
     * One zone, served the same way (every candidate known, same non-null zone id, same drop-off flag — all are
     * pickup-available) → its representative: the district named like the zone, else the first by name; else null.
     */
    private static String sameZone(Set<String> ids, Map<String, Candidate> byId) {
        Boolean dropoff = null;
        if (oneZone(ids, byId) == null) return null;
        List<Candidate> cs = new ArrayList<>();
        for (String id : ids) {
            Candidate c = byId.get(id);
            if (dropoff == null) dropoff = c.dropoff();
            else if (dropoff != c.dropoff()) return null;
            cs.add(c);
        }
        cs.sort(Comparator.comparing((Candidate c) -> normalize(c.name())).thenComparing(Candidate::districtId));
        for (Candidate c : cs) {
            if (!normalize(c.zoneName()).isEmpty() && normalize(c.name()).equals(normalize(c.zoneName()))) return c.districtId();
        }
        return cs.get(0).districtId();
    }
}
