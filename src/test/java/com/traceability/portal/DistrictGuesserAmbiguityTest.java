package com.traceability.portal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DistrictGuesser's ambiguity rules (2026-11-08 evening — prod dry run was ~55% ambiguous). Never a pre-select while
 * still ambiguous.
 *
 *   k1 a city / governorate name matches nothing (any Bosta city, EN or AR)
 *   k2 an all-generic name ("New City", numbers) matches nothing
 *   z1 SAME_ZONE: every candidate in one zone, same drop-off → one; the district named like the zone wins
 *   z2 SAME_ZONE: none named like the zone → the first by name
 *   z3 one zone but Bosta serves them differently (drop-off) → still AMBIGUOUS
 *   b1 BOSTA_FIELD: ambiguous overall, the Bosta secondLine / zone names exactly one → it
 *   b2 BOSTA_FIELD: the Bosta fields are themselves ambiguous across zones → AMBIGUOUS
 *   u1 unchanged: one district matched → EXACT / CONTAINS as before
 */
class DistrictGuesserAmbiguityTest {

    static DistrictGuesser.Candidate c(String id, String name, String nameAr, String zone, String zoneAr, String zoneId, boolean dropoff) {
        return new DistrictGuesser.Candidate(id, name, nameAr, zone, zoneAr, zoneId, dropoff);
    }

    static final List<DistrictGuesser.Candidate> CAIRO = List.of(
        c("maadi", "Maadi", "المعادي", "Maadi", "المعادي", "zM", true),
        c("zahraa", "Zahraa El Maadi", "زهراء المعادي", "Maadi", "المعادي", "zM", true),
        c("degla", "Degla", "دجلة", "Maadi", "المعادي", "zM", true),
        c("nasr7", "Nasr City - 7th District", "مدينة نصر - الحي السابع", "Nasr City", "مدينة نصر", "zN", true),
        c("nasr10", "Nasr City - 10th District", "مدينة نصر - الحي العاشر", "Nasr City", "مدينة نصر", "zN", false),
        c("zamalek", "Zamalek", "الزمالك", "Downtown", "وسط البلد", "zD", true),
        c("garden", "Garden City", "جاردن سيتي", "Downtown", "وسط البلد", "zD", true),
        c("cairoD", "Cairo", "القاهرة", "Downtown", "وسط البلد", "zD", true),
        c("newcity", "New City", "مدينة جديدة", "Outer", null, "zO", true));

    static final Set<String> CITIES = Set.of("Cairo", "القاهرة", "Giza", "الجيزة");

    static DistrictGuesser.Field shop(String t) { return new DistrictGuesser.Field(t, false); }
    static DistrictGuesser.Field bosta(String t) { return new DistrictGuesser.Field(t, true); }

    static DistrictGuesser.Guess g(List<DistrictGuesser.Field> f) { return DistrictGuesser.guess(f, CAIRO, CITIES); }

    @Test
    void k1_cityNameMatchesNothing() {
        assertThat(g(List.of(shop("Cairo"))).outcome()).isEqualTo(DistrictGuesser.Outcome.NONE);
        assertThat(g(List.of(shop("القاهرة"))).outcome()).isEqualTo(DistrictGuesser.Outcome.NONE);
        assertThat(g(List.of(shop("15 Tahrir St, Cairo"))).outcome()).isEqualTo(DistrictGuesser.Outcome.NONE);
    }

    @Test
    void k2_genericNameMatchesNothing() {
        assertThat(DistrictGuesser.generic("new city")).isTrue();
        assertThat(DistrictGuesser.generic("10 street")).isTrue();
        assertThat(DistrictGuesser.generic("nasr city")).isFalse();
        assertThat(g(List.of(shop("New City"))).outcome()).isEqualTo(DistrictGuesser.Outcome.NONE);
    }

    @Test
    void z1_sameZone_theDistrictNamedLikeTheZoneWins() {
        // "Maadi" is Maadi's name and the zone of all three Maadi districts → all three, one zone, same drop-off.
        assertThat(g(List.of(shop("Maadi")))).isEqualTo(new DistrictGuesser.Guess(DistrictGuesser.Outcome.SAME_ZONE, "maadi"));
    }

    @Test
    void z2_sameZone_noneNamedLikeTheZone_firstByName() {
        // "Downtown" (zone) names Cairo / Garden City / Zamalek; "Cairo" the district is a city name, but as a member of
        // the zone it is still a candidate; none is named "Downtown" → the first by name.
        assertThat(g(List.of(shop("Downtown")))).isEqualTo(new DistrictGuesser.Guess(DistrictGuesser.Outcome.SAME_ZONE, "cairoD"));
    }

    @Test
    void z3_oneZone_servedDifferently_stillAmbiguous() {
        assertThat(g(List.of(shop("10 Abbas El Akkad, Nasr City")))).isEqualTo(new DistrictGuesser.Guess(DistrictGuesser.Outcome.AMBIGUOUS, null));
    }

    @Test
    void b1_bostaFieldNamesOne() {
        assertThat(g(List.of(shop("Zamalek office"), bosta("near Zahraa El Maadi club"))))
            .isEqualTo(new DistrictGuesser.Guess(DistrictGuesser.Outcome.BOSTA_FIELD, "zahraa"));
        // The Bosta zone itself, spanning one served-alike zone → that zone's representative.
        assertThat(g(List.of(shop("Zamalek"), bosta("Maadi"))))
            .isEqualTo(new DistrictGuesser.Guess(DistrictGuesser.Outcome.BOSTA_FIELD, "maadi"));
    }

    @Test
    void b2_bostaFieldsAmbiguousAcrossZones() {
        assertThat(g(List.of(shop("Zamalek, Degla"), bosta("Zamalek / Degla"))).outcome())
            .isEqualTo(DistrictGuesser.Outcome.AMBIGUOUS);
        assertThat(g(List.of(shop("Zamalek, Degla"))).outcome()).as("no Bosta field to prefer").isEqualTo(DistrictGuesser.Outcome.AMBIGUOUS);
    }

    @Test
    void u1_unchangedForOneMatch() {
        assertThat(g(List.of(shop("Zamalek")))).isEqualTo(new DistrictGuesser.Guess(DistrictGuesser.Outcome.EXACT, "zamalek"));
        assertThat(g(List.of(shop("5 Hassan Sabry St, Zamalek")))).isEqualTo(new DistrictGuesser.Guess(DistrictGuesser.Outcome.CONTAINS, "zamalek"));
    }
}
