package com.traceability.bench;

import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaV2Client;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Analytics slice 8 — endpoint benchmark on a generated tenant (src/test/resources/bench/seed.sql:
 * 60k orders / 120k lines / ~57k shipments). Skipped unless -Dbench.url is set, e.g.
 *
 *   docker run -d --name traced-bench -e POSTGRES_HOST_AUTH_METHOD=trust -e POSTGRES_DB=bench \
 *     -p 55432:5432 postgres:16-alpine
 *   mvn test -Dtest=AnalyticsBenchmark -Dbench.url=jdbc:postgresql://localhost:55432/bench \
 *     -Dbench.label=before [-Dbench.cold=true -Dbench.container=traced-bench]
 *     [-Dbench.periods=30d,366d] [-Dbench.startAt="revenue/summary [366d]"] [-Dbench.only=sales/variants,...]
 *
 * The app connects as app_user (RLS on, as in prod). Warm: 3 warm-up calls, then 20 timed calls
 * per endpoint and period. Cold (-Dbench.cold=true): before each of 3 samples per endpoint, the
 * Postgres container is restarted and the Docker VM page cache dropped. Writes target/bench-<label>.md.
 */
@EnabledIfSystemProperty(named = "bench.url", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnalyticsBenchmark {

    static final String URL = System.getProperty("bench.url", "");
    static final String TENANT = "b0000000-0000-0000-0000-000000000001";
    static final String OWNER = "b0000000-0000-0000-0000-000000000002";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> URL);
        r.add("spring.datasource.username", () -> "app_user");
        r.add("spring.datasource.password", () -> "x");
        r.add("spring.flyway.url", () -> URL);
        r.add("spring.flyway.user", () -> "postgres");
        r.add("spring.flyway.password", () -> "x");
        r.add("analytics.settlement.refresh-enabled", () -> "false");
        r.add("bosta.poll.enabled", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired ApplicationContext ctx;
    @MockBean BostaV2Client bostaV2;

    @Test
    void benchmark() throws Exception {
        seedIfEmpty();
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Cairo"));
        Map<String, String> periods = new LinkedHashMap<>();
        List<String> only = List.of(System.getProperty("bench.periods", "30d,366d").split(","));
        if (only.contains("30d")) periods.put("30d", "period=30d");
        if (only.contains("366d")) periods.put("366d", "from=" + today.minusDays(365) + "&to=" + today);
        List<String[]> endpoints = new ArrayList<>();
        for (Map.Entry<String, String> p : periods.entrySet()) {
            String q = p.getValue();
            for (String path : List.of("sales/variants", "sales/products", "sales/cities", "money/pipeline", "money/fees",
                                       "money/fees/extra?groupBy=awb", "money/fees/extra?groupBy=sku", "money/payouts",
                                       "revenue/summary", "revenue/breakdown?by=channel", "revenue/breakdown?by=payment",
                                       "revenue/breakdown?by=governorate", "revenue/breakdown?by=productType",
                                       "revenue/discounts", "revenue/heatmap", "delivery/summary",
                                       "delivery/failure-reasons", "products/extras")) {
                String url = "/api/v1/analytics/" + path + (path.contains("?") ? "&" : "?") + q;
                endpoints.add(new String[] {path + " [" + p.getKey() + "]", url});
            }
        }
        endpoints.add(new String[] {"money/stuck", "/api/v1/analytics/money/stuck"});
        String onlyEndpoints = System.getProperty("bench.only");       // e.g. sales/variants,revenue/discounts
        if (onlyEndpoints != null) {
            List<String> keep = List.of(onlyEndpoints.split(","));
            endpoints.removeIf(e -> keep.stream().noneMatch(k -> e[0].startsWith(k + " ") || e[0].equals(k)));
        }
        String startAt = System.getProperty("bench.startAt");          // resume: skip endpoints before this one
        if (startAt != null) {
            int k = 0;
            while (k < endpoints.size() && !endpoints.get(k)[0].equals(startAt)) k++;
            endpoints = new ArrayList<>(endpoints.subList(Math.min(k, endpoints.size()), endpoints.size()));
        }

        boolean cold = Boolean.getBoolean("bench.cold");
        StringBuilder md = new StringBuilder("| endpoint | warm p50 ms | warm p95 ms |" + (cold ? " cold p50 ms | cold p95 ms |" : "") + "\n");
        md.append("|---|---:|---:|").append(cold ? "---:|---:|" : "").append("\n");
        for (String[] e : endpoints) {
            String token = jwt.issueAccessToken(UUID.fromString(OWNER), UUID.fromString(TENANT), "owner");   // tokens expire
            for (int i = 0; i < 3; i++) call(token, e[1]);
            long[] warm = new long[20];
            for (int i = 0; i < warm.length; i++) warm[i] = call(token, e[1]);
            String row = "| " + e[0] + " | " + pct(warm, 50) + " | " + pct(warm, 95) + " |";
            if (cold) {
                long[] c = new long[3];
                for (int i = 0; i < c.length; i++) {
                    restartDatabase();
                    c[i] = call(token, e[1]);
                }
                row += " " + pct(c, 50) + " | " + pct(c, 95) + " |";
            }
            md.append(row).append("\n");
            System.out.println("BENCH " + row);
        }
        Path out = Path.of("target/bench-" + System.getProperty("bench.label", "run") + ".md");
        Files.writeString(out, md.toString());
        System.out.println("BENCH written " + out.toAbsolutePath());
    }

    long call(String token, String url) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        long t0 = System.nanoTime();
        ResponseEntity<String> r = rest.exchange("http://localhost:" + port + url, HttpMethod.GET, new HttpEntity<>(h), String.class);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(r.getStatusCode()).as("%s: %s", url, r.getBody()).isEqualTo(HttpStatus.OK);
        return ms;
    }

    static long pct(long[] v, int p) {
        long[] s = v.clone();
        Arrays.sort(s);
        int idx = (int) Math.ceil(p / 100.0 * s.length) - 1;
        return s[Math.max(0, Math.min(s.length - 1, idx))];
    }

    void seedIfEmpty() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "postgres", "x")) {
            try (ResultSet rs = c.createStatement().executeQuery("SELECT count(*) FROM tenants WHERE id = '" + TENANT + "'")) {
                rs.next();
                if (rs.getLong(1) > 0) return;
            }
            long t0 = System.currentTimeMillis();
            ScriptUtils.executeSqlScript(c, new ClassPathResource("bench/seed.sql"));
            System.out.println("BENCH seeded in " + (System.currentTimeMillis() - t0) / 1000 + " s");
        }
    }

    /** Restart the Postgres container, drop the Docker VM page cache, reconnect every pool. */
    void restartDatabase() throws Exception {
        String container = System.getProperty("bench.container", "traced-bench");
        run("docker", "restart", container);
        run("docker", "run", "--rm", "--privileged", "alpine", "sh", "-c", "sync; echo 3 > /proc/sys/vm/drop_caches");
        for (int i = 0; i < 100; i++) {
            try (Connection c = DriverManager.getConnection(URL, "postgres", "x")) {
                break;
            } catch (Exception e) {
                Thread.sleep(200);
            }
        }
        // Every pool, including the app pool wrapped inside TenantAwareDataSource: a connection from
        // before the restart would fail with 57P01 on its first use.
        for (javax.sql.DataSource ds : ctx.getBeansOfType(javax.sql.DataSource.class).values()) {
            if (ds.isWrapperFor(HikariDataSource.class)) {
                ds.unwrap(HikariDataSource.class).getHikariPoolMXBean().softEvictConnections();
            }
        }
        DriverManagerDataSource probe = new DriverManagerDataSource(URL, "app_user", "x");
        probe.getConnection().close();
    }

    static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IllegalStateException(String.join(" ", cmd) + ": " + out);
    }
}
