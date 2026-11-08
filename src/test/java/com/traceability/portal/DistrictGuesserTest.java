package com.traceability.portal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DistrictGuesser (2026-11-08) — the "Same address" area guess: exact or unique-contains only, normalized Arabic +
 * English. Candidates mimic Bosta's Cairo list (district + zone names, EN + AR).
 *
 *   n1 normalize: case, punctuation, el/al, ال, hamza / ta marbuta / alef maqsura, diacritics, Arabic-Indic digits
 *   e1 exact EN (city field "Maadi" ↔ district "El Maadi")
 *   e2 exact AR with spelling variants ("المعادى" ↔ "المعادي")
 *   c1 contains: the name inside a longer address line, unique
 *   c2 contains: a shorter matched name inside a longer one is dropped ("maadi" in "zahraa el maadi")
 *   a1 ambiguous: two districts exact-match; a zone name speaking for several districts
 *   z1 none: nothing matches; a 3-letter name is never a contains match; empty address
 */
class DistrictGuesserTest {

    static final List<DistrictGuesser.Candidate> CAIRO = List.of(
        new DistrictGuesser.Candidate("maadi", "El Maadi", "المعادي", "Maadi", "المعادي"),
        new DistrictGuesser.Candidate("zahraa", "Zahraa El Maadi", "زهراء المعادي", "Maadi", "المعادي"),
        new DistrictGuesser.Candidate("nasr7", "Nasr City - 7th District", "مدينة نصر - الحي السابع", "Nasr City", "مدينة نصر"),
        new DistrictGuesser.Candidate("nasr10", "Nasr City - 10th District", "مدينة نصر - الحي العاشر", "Nasr City", "مدينة نصر"),
        new DistrictGuesser.Candidate("zamalek", "Zamalek", "الزمالك", "Downtown", "وسط البلد"),
        new DistrictGuesser.Candidate("abc", "Abc", null, null, null));

    @Test
    void n1_normalize() {
        assertThat(DistrictGuesser.normalize("  El-Maadi,  CAIRO ")).isEqualTo("maadi cairo");
        assertThat(DistrictGuesser.normalize("Al Zamalek")).isEqualTo("zamalek");
        assertThat(DistrictGuesser.normalize("الزمالك")).isEqualTo("زمالك");
        assertThat(DistrictGuesser.normalize("مَدِينَة نَصْر")).isEqualTo("مدينه نصر");
        assertThat(DistrictGuesser.normalize("أسيوط إمبابة آمون")).isEqualTo("اسيوط امبابه امون");
        assertThat(DistrictGuesser.normalize("المعادى")).isEqualTo("معادي");
        assertThat(DistrictGuesser.normalize("شارع ٩")).isEqualTo("شارع 9");
        assertThat(DistrictGuesser.normalize(null)).isEmpty();
    }

    @Test
    void e1_exactEnglish() {
        assertThat(DistrictGuesser.guess(List.of("12 Road 9", "Zamalek"), CAIRO))
            .isEqualTo(new DistrictGuesser.Guess(DistrictGuesser.Outcome.EXACT, "zamalek"));
    }

    @Test
    void e2_exactArabic_spellingVariants() {
        assertThat(DistrictGuesser.guess(List.of("الزّمالك"), CAIRO))
            .isEqualTo(new DistrictGuesser.Guess(DistrictGuesser.Outcome.EXACT, "zamalek"));
        assertThat(DistrictGuesser.guess(List.of("زهراء المعادى"), CAIRO))
            .isEqualTo(new DistrictGuesser.Guess(DistrictGuesser.Outcome.EXACT, "zahraa"));
    }

    @Test
    void c1_containsUnique() {
        assertThat(DistrictGuesser.guess(List.of("Building 5, 26th of July St, Zamalek, near the club"), CAIRO))
            .isEqualTo(new DistrictGuesser.Guess(DistrictGuesser.Outcome.CONTAINS, "zamalek"));
        assertThat(DistrictGuesser.guess(List.of("عمارة 4 شارع 26 يوليو الزمالك"), CAIRO))
            .isEqualTo(new DistrictGuesser.Guess(DistrictGuesser.Outcome.CONTAINS, "zamalek"));
    }

    @Test
    void c2_shorterMatchInsideALongerOne_dropped() {
        assertThat(DistrictGuesser.guess(List.of("Street 233, Zahraa El Maadi"), CAIRO))
            .isEqualTo(new DistrictGuesser.Guess(DistrictGuesser.Outcome.CONTAINS, "zahraa"));
    }

    @Test
    void a1_ambiguous() {
        // "Maadi" is El Maadi's name AND the zone of both Maadi districts → two districts exact-match.
        assertThat(DistrictGuesser.guess(List.of("Maadi"), CAIRO).outcome()).isEqualTo(DistrictGuesser.Outcome.AMBIGUOUS);
        assertThat(DistrictGuesser.guess(List.of("Maadi"), CAIRO).districtId()).isNull();
        // A zone name inside a line speaks for every district of the zone.
        assertThat(DistrictGuesser.guess(List.of("10 Abbas El Akkad, Nasr City"), CAIRO).outcome())
            .isEqualTo(DistrictGuesser.Outcome.AMBIGUOUS);
        // Two different districts named in the same address.
        assertThat(DistrictGuesser.guess(List.of("Zamalek office", "Zahraa El Maadi home"), CAIRO).outcome())
            .isEqualTo(DistrictGuesser.Outcome.AMBIGUOUS);
    }

    @Test
    void z1_none() {
        assertThat(DistrictGuesser.guess(List.of("15 Tahrir St", "Giza"), CAIRO).outcome()).isEqualTo(DistrictGuesser.Outcome.NONE);
        assertThat(DistrictGuesser.guess(List.of("Abc tower, Tahrir"), CAIRO).outcome())
            .as("a 3-letter name is exact-only").isEqualTo(DistrictGuesser.Outcome.NONE);
        assertThat(DistrictGuesser.guess(List.of("Abc"), CAIRO))
            .isEqualTo(new DistrictGuesser.Guess(DistrictGuesser.Outcome.EXACT, "abc"));
        assertThat(DistrictGuesser.guess(List.of(), CAIRO).outcome()).isEqualTo(DistrictGuesser.Outcome.NONE);
        assertThat(DistrictGuesser.guess(List.of("Zamalek"), List.of()).outcome()).isEqualTo(DistrictGuesser.Outcome.NONE);
    }
}
