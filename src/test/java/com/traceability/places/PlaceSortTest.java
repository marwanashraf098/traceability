package com.traceability.places;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Warehouse places S3 — the walking order of places (PlaceSort.key). */
class PlaceSortTest {

    private static PlaceSort.Level zone(String c)  { return new PlaceSort.Level("zone", c); }
    private static PlaceSort.Level rack(String c)  { return new PlaceSort.Level("rack", c); }
    private static PlaceSort.Level shelf(String c) { return new PlaceSort.Level("shelf", c); }
    private static PlaceSort.Level box(String c)   { return new PlaceSort.Level("box", c); }

    private static String key(PlaceSort.Level... chain) { return PlaceSort.key(Arrays.asList(chain)); }

    /** Shuffles the named keys, sorts by plain string order and returns the names in that order. */
    private static List<String> sorted(Map<String, String> byName) {
        Map<String, String> nameOf = byName.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        List<String> keys = new ArrayList<>(byName.values());
        Collections.shuffle(keys, new java.util.Random(7));
        Collections.sort(keys);
        return keys.stream().map(nameOf::get).toList();
    }

    @Test
    void naturalOrder_numbersCompareAsNumbers() {
        assertThat(key(zone("Z2"))).isLessThan(key(zone("Z10")));
        assertThat(key(zone("Z1"), rack("Z1-R02"))).isLessThan(key(zone("Z1"), rack("Z1-R10")));
        assertThat(key(zone("Z1"), rack("Z1-R01"), shelf("Z1-R01-S9")))
            .isLessThan(key(zone("Z1"), rack("Z1-R01"), shelf("Z1-R01-S10")));
        assertThat(key(zone("z1"))).as("letters ignore case").isEqualTo(key(zone("Z1")));
        assertThat(PlaceSort.natural("BOX-07")).isEqualTo("BOX-0000000007");
    }

    @Test
    void walkingOrder_boxUnderItsShelf_floorBoxesAfterTheZonesRacks_zonesInNaturalOrder() {
        PlaceSort.Level z1 = zone("Z1"), z2 = zone("Z2"), z10 = zone("Z10");
        PlaceSort.Level r1 = rack("Z1-R01"), r2 = rack("Z1-R02");
        Map<String, String> keys = new java.util.LinkedHashMap<>();
        keys.put("Z1-R01-S1", key(z1, r1, shelf("Z1-R01-S1")));
        keys.put("Z1-R01-S2", key(z1, r1, shelf("Z1-R01-S2")));
        keys.put("BOX-12 on Z1-R01-S2", key(z1, r1, shelf("Z1-R01-S2"), box("BOX-12")));
        keys.put("BOX-03 on Z1-R01-S2", key(z1, r1, shelf("Z1-R01-S2"), box("BOX-03")));
        keys.put("Z1-R01-S10", key(z1, r1, shelf("Z1-R01-S10")));
        keys.put("Z1-R02-S1", key(z1, r2, shelf("Z1-R02-S1")));
        keys.put("BOX-01 on the Z1 floor", key(z1, box("BOX-01")));
        keys.put("BOX-02 on the Z1 floor", key(z1, box("BOX-02")));
        keys.put("Z2-R01-S1", key(z2, rack("Z2-R01"), shelf("Z2-R01-S1")));
        keys.put("BOX-09 on the Z2 floor", key(z2, box("BOX-09")));
        keys.put("Z10-R01-S1", key(z10, rack("Z10-R01"), shelf("Z10-R01-S1")));

        assertThat(sorted(keys)).containsExactly(
            "Z1-R01-S1", "Z1-R01-S2", "BOX-03 on Z1-R01-S2", "BOX-12 on Z1-R01-S2", "Z1-R01-S10", "Z1-R02-S1",
            "BOX-01 on the Z1 floor", "BOX-02 on the Z1 floor",
            "Z2-R01-S1", "BOX-09 on the Z2 floor",
            "Z10-R01-S1");
    }

    @Test
    void aSiblingWhoseCodeExtendsAnother_neverLandsBetweenAPlaceAndItsChildren() {
        PlaceSort.Level z1 = zone("Z1"), r1 = rack("Z1-R01");
        Map<String, String> keys = new java.util.LinkedHashMap<>();
        keys.put("S2", key(z1, r1, shelf("Z1-R01-S2")));
        keys.put("box on S2", key(z1, r1, shelf("Z1-R01-S2"), box("BOX-01")));
        keys.put("S2-A", key(z1, r1, shelf("Z1-R01-S2-A")));
        keys.put("S2.B", key(z1, r1, shelf("Z1-R01-S2.B")));
        assertThat(sorted(keys)).containsExactly("S2", "box on S2", "S2-A", "S2.B");
    }

    @Test
    void everyCodeCharacter_sortsBetweenTheSeparatorAndTheFloorMark() {
        // bins_code_shape allows letters, digits, '.', '_' and '-' (upper-cased): all above the separator, below '~'.
        Function<Character, Boolean> between = ch -> ch > ' ' && ch < '~';
        for (char ch : "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-".toCharArray()) {
            assertThat(between.apply(ch)).as("'%s'", ch).isTrue();
        }
    }
}
