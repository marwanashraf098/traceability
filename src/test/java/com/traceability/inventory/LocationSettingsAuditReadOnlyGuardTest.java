package com.traceability.inventory;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 (2026-11-12) — source guard: the location-settings audit can never write.
 *
 *   r1 LocationSettingsAuditJob calls Shopify only through the two reads (readOnlineFulfillment,
 *      findDefaultProfileLocationGroup); its SQL is SELECT only; every jdbc call runs in its read-only
 *      TransactionTemplate; it is never scheduled
 */
class LocationSettingsAuditReadOnlyGuardTest {

    static final Path JOB = Path.of("src/main/java/com/traceability/inventory/LocationSettingsAuditJob.java");

    @Test
    void r1_job_readsOnly() throws IOException {
        String code = Files.readString(JOB).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        Set<String> shopifyCalls = new TreeSet<>();
        Matcher m = Pattern.compile("\\b(?:locations|profiles)\\.(\\w+)\\(").matcher(code);
        while (m.find()) shopifyCalls.add(m.group(1));
        assertThat(shopifyCalls).containsExactly("findDefaultProfileLocationGroup", "readOnlineFulfillment");

        Matcher c = Pattern.compile("static final String (\\w+_SQL) =([^;]*);").matcher(code);
        StringBuilder sql = new StringBuilder();
        while (c.find()) sql.append(c.group(2));
        assertThat(sql.toString()).contains("SELECT");
        for (String w : new String[]{"INSERT", "UPDATE", "DELETE", "TRUNCATE", "MERGE", "PG_ADVISORY", "FOR UPDATE"}) {
            assertThat(sql.toString().toUpperCase()).as(w).doesNotContain(w);
        }
        Matcher j = Pattern.compile("\\bjdbc\\.(\\w+)\\(").matcher(code);
        int n = 0;
        while (j.find()) { n++; assertThat(j.group(1)).isEqualTo("queryForList"); }
        assertThat(n).isEqualTo(1);
        assertThat(code.indexOf("jdbc.queryForList(")).isGreaterThan(code.indexOf("readOnly.execute("));
        assertThat(code).contains("this.readOnly.setReadOnly(true);")
            .doesNotContain("tx.execute(").doesNotContain("@Transactional").doesNotContain("@Recurring")
            .doesNotContain("@Scheduled").doesNotContain("JobScheduler").doesNotContain("enableOnlineFulfillment")
            .doesNotContain("addLocationToGroup").doesNotContain("TracedLocationOnlineService");
    }
}
