package com.traceability.inventory;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Part D P3 (2026-11-08) — source guard: the hidden-stock audit can never write.
 *
 *   r1 — MainLevelAuditJob calls Shopify only through auditMainLevels (no other gateway method), and its SQL has no INSERT / UPDATE / DELETE / UPSERT / TRUNCATE / MERGE / COPY / lock; every DB touch runs in its
 *        read-only TransactionTemplate (setReadOnly(true)) — no bare jdbc call outside it
 *   r2 — the gateway's audit read is a QUERY: MAIN_LEVEL_AUDIT_QUERY starts with "query", has no "mutation", and the
 *        auditMainLevels body sends only that document
 */
class MainLevelAuditReadOnlyGuardTest {

    static final Path JOB = Path.of("src/main/java/com/traceability/inventory/MainLevelAuditJob.java");
    static final Path GATEWAY = Path.of("src/main/java/com/traceability/integrations/shopify/ShopifyHttpGateway.java");

    @Test
    void r1_job_readsOnly() throws IOException {
        String src = Files.readString(JOB);
        Set<String> calls = new TreeSet<>();
        Matcher m = Pattern.compile("\\bshopify\\.(\\w+)\\(").matcher(src);
        while (m.find()) calls.add(m.group(1));
        assertThat(calls).containsExactly("auditMainLevels");
        String code = withoutComments(src);
        // The job's SQL is its *_SQL constants (the only strings passed to jdbc): none may write or lock.
        StringBuilder sqlText = new StringBuilder();
        Matcher c = Pattern.compile("static final String (\\w+_SQL) =([^;]*);").matcher(code);
        Set<String> constants = new TreeSet<>();
        while (c.find()) { constants.add(c.group(1)); sqlText.append(c.group(2)).append('\n'); }
        assertThat(constants).containsExactly("MAIN_SQL", "VARIANTS_SQL");
        assertThat(sqlText.toString()).contains("SELECT");
        for (String sql : List.of("INSERT", "UPDATE", "DELETE", "UPSERT", "TRUNCATE", "MERGE", "COPY ", "FOR SHARE",
                "PG_ADVISORY", "NEXTVAL", "SET_CONFIG")) {
            assertThat(sqlText.toString().toUpperCase()).as("SQL keyword %s", sql).doesNotContain(sql);
        }
        Matcher q = Pattern.compile("jdbc\\.queryForList\\((\\w+)").matcher(code);
        while (q.find()) assertThat(q.group(1)).as("jdbc runs only the SQL constants").isIn(constants);
        assertThat(code).contains("this.readOnly.setReadOnly(true);");
        // Every jdbc call sits inside readOnly.execute(...): the only jdbc.* calls are the two reads in that lambda.
        Matcher j = Pattern.compile("\\bjdbc\\.(\\w+)\\(").matcher(code);
        int jdbcCalls = 0;
        while (j.find()) { jdbcCalls++; assertThat(j.group(1)).isEqualTo("queryForList"); }
        assertThat(jdbcCalls).isEqualTo(2);
        int ro = code.indexOf("readOnly.execute(");
        assertThat(ro).isPositive();
        assertThat(code.indexOf("jdbc.queryForList(")).isGreaterThan(ro);
        assertThat(code).doesNotContain("tx.execute(").doesNotContain("@Transactional").doesNotContain("ownerDataSource")
            .doesNotContain("JobScheduler").doesNotContain("@Recurring").doesNotContain("@Scheduled");
    }

    @Test
    void r2_gatewayAuditRead_isAQuery() throws IOException {
        String src = Files.readString(GATEWAY);
        int q = src.indexOf("static final String MAIN_LEVEL_AUDIT_QUERY = \"\"\"");
        assertThat(q).isPositive();
        String doc = src.substring(q, src.indexOf("\"\"\";", q + 50));
        assertThat(doc.replace("static final String MAIN_LEVEL_AUDIT_QUERY = \"\"\"", "").trim())
            .startsWith("query TracedMainLevelAudit").doesNotContain("mutation");
        String body = methodBody(src, "public List<MainLevelAudit> auditMainLevels(");
        Matcher sends = Pattern.compile("postGraphQL\\(shopDomain, token, (\\w+)").matcher(body);
        int n = 0;
        while (sends.find()) { n++; assertThat(sends.group(1)).isEqualTo("MAIN_LEVEL_AUDIT_QUERY"); }
        assertThat(n).isEqualTo(1);
        assertThat(body).doesNotContain("restClient").doesNotContain("executeGraphQL(").doesNotContain("MUTATION");
    }

    private static String withoutComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    private static String methodBody(String src, String signature) {
        int start = src.indexOf(signature);
        assertThat(start).as("found " + signature).isGreaterThanOrEqualTo(0);
        int open = src.indexOf('{', src.indexOf(')', start));
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(start, i + 1);
        }
        throw new AssertionError("unbalanced " + signature);
    }
}
