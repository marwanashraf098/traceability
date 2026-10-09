package com.traceability;

import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.portal.PortalService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Returns portal P1 — uploaded logo, font, public logo endpoint, per-IP lookup throttle. Over HTTP
 * (the multipart path, real security), plus app_user connections for the isolation proofs.
 *   u1 upload → re-encoded PNG stored; settings carry it; config logoUrl points at the public endpoint
 *   u2 replace leaves exactly one asset (the new one); remove nulls the column and deletes the row;
 *      config falls back to the Shopify link, then to null (wordmark)
 *   u3 rejected uploads: spoofed extension / content type, over 2 MB, over the 8 MB multipart
 *      ceiling (413), pixel bomb, not an image, no file — nothing stored, logo unchanged
 *   u4 worker → 403 on upload / remove / preview
 *   p1 public logo: serves only the slug's tenant's asset; ETag + 304; Cache-Control; 404s
 *   x1 cross-tenant over HTTP: B never reads, replaces or removes A's logo (positive control: A)
 *   x2 cross-tenant on app_user: B can't see / delete / point at A's asset; UPDATE refused; control
 *   f1 font: saved, validated, absent = unchanged; in GET settings and the public config
 *   t1 per-IP lookup throttle trips across different order numbers — same generic 404; another IP
 *      still finds the order; the raw IP is never stored
 *   t2 over HTTP: the controller passes the client IP; the 21st lookup is the generic 404
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortalLogoTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
    }

    static final String SHOPIFY_LOGO = "https://cdn.shopify.com/s/files/1/0001/logo.png";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate     jdbc;
    @Autowired PasswordEncoder  passwordEncoder;
    @Autowired PortalService    portal;

    UUID a, b;
    String ownerA, managerA, ownerB, workerA;

    @BeforeAll
    void setup() {
        a = tenant("Nour Studio", "nour-logo");
        b = tenant("Jumi Store", "jumi-logo");
        ownerA   = login(user(a, "owner"));
        managerA = login(user(a, "manager"));
        workerA  = login(user(a, "worker"));
        ownerB   = login(user(b, "owner"));
    }

    @AfterEach
    void reset() {
        for (UUID t : List.of(a, b)) {
            jdbc.update("UPDATE tenants SET portal_logo_asset_id = NULL, portal_logo_url = NULL, portal_font = 'cairo', " +
                        "portal_enabled = true WHERE id = ?", t);
            jdbc.update("DELETE FROM portal_assets WHERE tenant_id = ?", t);
        }
    }

    // ── u1 ───────────────────────────────────────────────────────────────────

    @Test
    void u1_upload_storesReencodedPng_settingsAndConfigPointAtIt() throws Exception {
        ResponseEntity<Map> r = upload(png(1200, 300, true), "logo.png", "image/png", managerA);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> logo = (Map<String, Object>) r.getBody().get("logo");
        assertThat(logo).containsEntry("contentType", "image/png").containsEntry("width", 600).containsEntry("height", 150);

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT a.kind, a.content_type, a.width, a.height, a.size_bytes, a.sha256, a.bytes FROM tenants t " +
            "JOIN portal_assets a ON a.id = t.portal_logo_asset_id WHERE t.id = ?", a);
        byte[] stored = (byte[]) row.get("bytes");
        assertThat(row).containsEntry("kind", "logo").containsEntry("content_type", "image/png").containsEntry("width", 600);
        assertThat(row.get("size_bytes")).isEqualTo(stored.length);
        assertThat(row.get("sha256")).isEqualTo(sha256(stored));
        assertThat(ImageIO.read(new ByteArrayInputStream(stored)).getColorModel().hasAlpha()).isTrue();
        assertThat(logo.get("version")).isEqualTo(((String) row.get("sha256")).substring(0, 16));

        // The settings preview endpoint returns the same bytes.
        ResponseEntity<byte[]> preview = getBytes("/api/v1/tenant/portal-settings/logo", ownerA, null);
        assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(preview.getBody()).isEqualTo(stored);

        // The public config points at the public endpoint, versioned.
        Map<String, Object> config = rest.getForEntity(base() + "/api/v1/portal/nour-logo/config", Map.class).getBody();
        assertThat(config.get("logoUrl")).isEqualTo("/api/v1/portal/nour-logo/logo?v=" + ((String) row.get("sha256")).substring(0, 16));
    }

    // ── u2 ───────────────────────────────────────────────────────────────────

    @Test
    void u2_replaceKeepsOneAsset_removeFallsBackToLinkThenWordmark() throws Exception {
        jdbc.update("UPDATE tenants SET portal_logo_url = ? WHERE id = ?", SHOPIFY_LOGO, a);
        upload(png(100, 40, false), "first.png", "image/png", ownerA);
        UUID first = currentAsset(a);
        assertThat(upload(jpeg(80, 80), "second.jpg", "image/jpeg", ownerA).getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID second = currentAsset(a);
        assertThat(second).isNotEqualTo(first);
        assertThat(jdbc.queryForList("SELECT id FROM portal_assets WHERE tenant_id = ?", UUID.class, a)).containsExactly(second);
        assertThat(configOf("nour-logo").get("logoUrl")).asString().startsWith("/api/v1/portal/nour-logo/logo?v=");

        ResponseEntity<Map> removed = exchange("/api/v1/tenant/portal-settings/logo", HttpMethod.DELETE, null, ownerA);
        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(removed.getBody()).containsEntry("logo", null);
        assertThat(currentAsset(a)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM portal_assets WHERE tenant_id = ?", Integer.class, a)).isZero();
        assertThat(configOf("nour-logo")).containsEntry("logoUrl", SHOPIFY_LOGO);   // asset gone → Shopify link

        jdbc.update("UPDATE tenants SET portal_logo_url = NULL WHERE id = ?", a);
        assertThat(configOf("nour-logo")).containsEntry("logoUrl", null);           // → wordmark
        // Removing again is harmless; the preview is a 404.
        assertThat(exchange("/api/v1/tenant/portal-settings/logo", HttpMethod.DELETE, null, ownerA).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(getBytes("/api/v1/tenant/portal-settings/logo", ownerA, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── u3 ───────────────────────────────────────────────────────────────────

    @Test
    void u3_rejectedUploads_clearErrors_nothingStored() throws Exception {
        upload(png(50, 50, false), "keep.png", "image/png", ownerA);
        UUID kept = currentAsset(a);

        record Case(byte[] bytes, String name, String type, HttpStatus status, String code) {}
        byte[] gif = encode(solid(30, 30), "gif");
        byte[] overTwoMb = new byte[2 * 1024 * 1024 + 1];
        System.arraycopy(png(10, 10, false), 0, overTwoMb, 0, 8);   // a PNG signature, then padding
        List<Case> cases = List.of(
            new Case(gif, "logo.png", "image/png", HttpStatus.BAD_REQUEST, "LOGO_TYPE"),            // spoofed
            new Case("not an image".getBytes(), "logo.webp", "image/webp", HttpStatus.BAD_REQUEST, "LOGO_TYPE"),
            new Case(encode(solid(30, 30), "bmp"), "logo.jpg", "image/jpeg", HttpStatus.BAD_REQUEST, "LOGO_TYPE"),
            new Case(overTwoMb, "big.png", "image/png", HttpStatus.BAD_REQUEST, "LOGO_TOO_LARGE"),
            new Case(com.traceability.assets.ImagePipelineTest.pngHeaderOnly(50_000, 50_000), "bomb.png", "image/png",
                HttpStatus.BAD_REQUEST, "LOGO_PIXELS"),
            new Case(Arrays.copyOf(png(60, 60, false), 40), "cut.png", "image/png", HttpStatus.BAD_REQUEST, "LOGO_UNREADABLE"));
        for (Case c : cases) {
            ResponseEntity<Map> r = upload(c.bytes(), c.name(), c.type(), ownerA);
            assertThat(r.getStatusCode()).as(c.code()).isEqualTo(c.status());
            assertThat(r.getBody()).as(c.code()).containsEntry("field", "logo").containsEntry("error", c.code());
        }
        // Over the 8 MB multipart ceiling: refused while parsing — 413 with a code, not a 500.
        ResponseEntity<Map> huge = upload(new byte[9 * 1024 * 1024], "huge.png", "image/png", ownerA);
        assertThat(huge.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(huge.getBody()).containsEntry("error", "FILE_TOO_LARGE");
        // No file part at all.
        ResponseEntity<Map> none = exchange("/api/v1/tenant/portal-settings/logo", HttpMethod.PUT, Map.of(), ownerA);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(none.getBody()).containsEntry("error", "LOGO_REQUIRED");

        assertThat(currentAsset(a)).isEqualTo(kept);
        assertThat(jdbc.queryForList("SELECT id FROM portal_assets WHERE tenant_id = ?", UUID.class, a)).containsExactly(kept);
    }

    // ── u4 ───────────────────────────────────────────────────────────────────

    @Test
    void u4_worker_forbidden() throws Exception {
        assertThat(upload(png(20, 20, false), "w.png", "image/png", workerA).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/api/v1/tenant/portal-settings/logo", HttpMethod.DELETE, null, workerA).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(getBytes("/api/v1/tenant/portal-settings/logo", workerA, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(currentAsset(a)).isNull();
    }

    // ── p1 ───────────────────────────────────────────────────────────────────

    @Test
    void p1_publicLogo_onlyTheSlugsTenant_etag304_cache_404s() throws Exception {
        upload(png(200, 80, true), "a.png", "image/png", ownerA);
        byte[] aBytes = storedBytes(a);

        ResponseEntity<byte[]> r = rest.getForEntity(base() + "/api/v1/portal/nour-logo/logo", byte[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).isEqualTo(aBytes);
        assertThat(r.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(r.getHeaders().getETag()).isEqualTo("\"" + sha256(aBytes) + "\"");
        assertThat(r.getHeaders().getCacheControl()).contains("public").contains("max-age=3600");

        HttpHeaders h = new HttpHeaders();
        h.setIfNoneMatch(r.getHeaders().getETag());
        ResponseEntity<byte[]> notModified = rest.exchange(base() + "/api/v1/portal/nour-logo/logo", HttpMethod.GET,
            new HttpEntity<>(h), byte[].class);
        assertThat(notModified.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(notModified.getBody()).isNull();

        // B has no logo: B's slug is a 404, never A's bytes. Then B uploads its own.
        assertThat(rest.getForEntity(base() + "/api/v1/portal/jumi-logo/logo", byte[].class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        upload(jpeg(90, 30), "b.jpg", "image/jpeg", ownerB);
        byte[] bBytes = storedBytes(b);
        assertThat(rest.getForEntity(base() + "/api/v1/portal/jumi-logo/logo", byte[].class).getBody()).isEqualTo(bBytes);
        assertThat(rest.getForEntity(base() + "/api/v1/portal/nour-logo/logo", byte[].class).getBody()).isEqualTo(aBytes);

        // Unknown slug, disabled portal → 404.
        assertThat(rest.getForEntity(base() + "/api/v1/portal/no-such-store/logo", byte[].class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        jdbc.update("UPDATE tenants SET portal_enabled = false WHERE id = ?", a);
        assertThat(rest.getForEntity(base() + "/api/v1/portal/nour-logo/logo", byte[].class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── x1 ───────────────────────────────────────────────────────────────────

    @Test
    void x1_crossTenant_http_bNeverReadsReplacesOrRemovesA() throws Exception {
        upload(png(64, 64, true), "a.png", "image/png", ownerA);
        UUID aAsset = currentAsset(a);
        byte[] aBytes = storedBytes(a);

        // B reads its own (none) — never A's.
        assertThat(getBytes("/api/v1/tenant/portal-settings/logo", ownerB, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // B uploads and removes: only B's row moves.
        upload(png(32, 32, false), "b.png", "image/png", ownerB);
        assertThat(currentAsset(b)).isNotNull().isNotEqualTo(aAsset);
        assertThat(exchange("/api/v1/tenant/portal-settings/logo", HttpMethod.DELETE, null, ownerB).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        // A untouched — the same-tenant positive control.
        assertThat(currentAsset(a)).isEqualTo(aAsset);
        assertThat(getBytes("/api/v1/tenant/portal-settings/logo", ownerA, null).getBody()).isEqualTo(aBytes);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM portal_assets WHERE tenant_id = ?", Integer.class, b)).isZero();
    }

    // ── x2 ───────────────────────────────────────────────────────────────────

    @Test
    void x2_crossTenant_appUser_invisible_undeletable_unpointable_immutable_withControl() throws Exception {
        upload(png(40, 40, true), "a.png", "image/png", ownerA);
        UUID aAsset = currentAsset(a);
        DataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate app = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        // Tenant B on app_user: A's asset is invisible and can't be deleted.
        Integer seenByB = TenantContext.runAs(b, () -> tx.execute(s ->
            app.queryForObject("SELECT COUNT(*) FROM portal_assets WHERE id = ?", Integer.class, aAsset)));
        assertThat(seenByB).isZero();
        Integer deletedByB = TenantContext.runAs(b, () -> tx.execute(s ->
            app.update("DELETE FROM portal_assets WHERE id = ?", aAsset)));
        assertThat(deletedByB).isZero();
        // ... and B can't point its own tenant row at A's asset (composite FK: the asset must be B's).
        assertThatThrownBy(() -> TenantContext.runAs(b, () -> tx.execute(s ->
            app.update("UPDATE tenants SET portal_logo_asset_id = ? WHERE id = ?", aAsset, b))))
            .hasMessageContaining("tenants_portal_logo_asset_fk");
        // No tenant set → nothing visible.
        Integer noTenant = tx.execute(s -> app.queryForObject("SELECT COUNT(*) FROM portal_assets", Integer.class));
        assertThat(noTenant).isZero();
        // Assets are immutable for app_user, even its own.
        assertThatThrownBy(() -> TenantContext.runAs(a, () -> tx.execute(s ->
            app.update("UPDATE portal_assets SET width = 1 WHERE id = ?", aAsset))))
            .rootCause().hasMessageContaining("permission denied");

        // Positive control: tenant A sees its own asset on app_user.
        Integer seenByA = TenantContext.runAs(a, () -> tx.execute(s ->
            app.queryForObject("SELECT COUNT(*) FROM portal_assets WHERE id = ?", Integer.class, aAsset)));
        assertThat(seenByA).isEqualTo(1);
        assertThat(currentAsset(a)).isEqualTo(aAsset);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_policies WHERE tablename = 'portal_assets' " +
            "AND policyname = 'tenant_isolation'", Integer.class)).isEqualTo(1);
    }

    // ── f1 ───────────────────────────────────────────────────────────────────

    @Test
    void f1_font_savedValidatedAbsentUnchanged_inSettingsAndConfig() {
        assertThat(configOf("nour-logo")).containsEntry("font", "cairo");   // the default

        Map<String, Object> body = new HashMap<>(settings());
        body.put("font", "readex-pro");
        ResponseEntity<Map> r = exchange("/api/v1/tenant/portal-settings", HttpMethod.PUT, body, ownerA);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("font", "readex-pro");
        assertThat(configOf("nour-logo")).containsEntry("font", "readex-pro");

        body.put("font", "comic-sans");
        ResponseEntity<Map> bad = exchange("/api/v1/tenant/portal-settings", HttpMethod.PUT, body, ownerA);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody()).containsEntry("field", "font").containsEntry("error", "FONT");

        // A PUT without font (an older client) leaves it as it is.
        assertThat(exchange("/api/v1/tenant/portal-settings", HttpMethod.PUT, settings(), ownerA).getBody())
            .containsEntry("font", "readex-pro");
        assertThat(jdbc.queryForObject("SELECT portal_font FROM tenants WHERE id = ?", String.class, a)).isEqualTo("readex-pro");
        // Other tenant untouched.
        assertThat(configOf("jumi-logo")).containsEntry("font", "cairo");
    }

    // ── t1 ───────────────────────────────────────────────────────────────────

    @Test
    void t1_perIpThrottle_acrossOrderKeys_genericNotFound_otherIpStillWorks_noRawIp() {
        UUID t = tenant("Throttle Store", "throttle-ip");
        UUID store = store(t, "throttle-ip");
        deliveredOrder(t, store, "#9001", "01000009001");
        String ip = "203.0.113.7";

        for (int i = 0; i < 20; i++) {
            assertThat(portal.lookup("throttle-ip", "#" + (5000 + i), "01000009001", ip).orElseThrow().outcome())
                .isEqualTo(PortalService.Outcome.NOT_FOUND);
        }
        // Each order key failed once — far below the per-order limit — but the IP is now over its own.
        PortalService.LookupResult tripped = portal.lookup("throttle-ip", "#9001", "01000009001", ip).orElseThrow();
        assertThat(tripped.outcome()).as("same generic answer as a wrong order").isEqualTo(PortalService.Outcome.NOT_FOUND);
        assertThat(tripped.body()).isNull();
        // Another IP still finds the order (positive control) ...
        assertThat(portal.lookup("throttle-ip", "#9001", "01000009001", "198.51.100.9").orElseThrow().outcome())
            .isEqualTo(PortalService.Outcome.SUCCESS);
        // ... and the tripped call was not recorded.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM portal_lookup_attempts WHERE tenant_id = ?", Integer.class, t))
            .isEqualTo(21);
        // Only HMACs: never the raw address.
        assertThat(jdbc.queryForList("SELECT DISTINCT ip_hash FROM portal_lookup_attempts WHERE tenant_id = ?", String.class, t))
            .hasSize(2).allSatisfy(h -> assertThat(h).matches("^[0-9a-f]{64}$").doesNotContain("203.0.113.7"));
    }

    // ── t2 ───────────────────────────────────────────────────────────────────

    @Test
    void t2_http_clientIpReachesThrottle_21stLookupIsGeneric404() {
        UUID t = tenant("Throttle Http", "throttle-http");
        UUID store = store(t, "throttle-http");
        deliveredOrder(t, store, "#9101", "01000009101");
        String url = base() + "/api/v1/portal/throttle-http/lookup";

        ResponseEntity<Map> ok = rest.postForEntity(url, Map.of("orderNumber", "#9101", "phone", "01000009101"), Map.class);
        assertThat(ok.getStatusCode()).as("positive control before the trip").isEqualTo(HttpStatus.OK);
        for (int i = 0; i < 20; i++) {
            assertThat(rest.postForEntity(url, Map.of("orderNumber", "#" + (6000 + i), "phone", "01000009101"), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
        ResponseEntity<Map> tripped = rest.postForEntity(url, Map.of("orderNumber", "#9101", "phone", "01000009101"), Map.class);
        assertThat(tripped.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(tripped.getBody()).containsExactlyEntriesOf(
            Map.of("message", "We couldn't find a returnable order with those details."));
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT ip_hash) FROM portal_lookup_attempts WHERE tenant_id = ?",
            Integer.class, t)).isEqualTo(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> settings() {
        Map<String, Object> m = new HashMap<>();
        m.put("slug", "nour-logo");
        m.put("enabled", true);
        m.put("autoApprove", false);
        m.put("returnWindowDays", 30);
        return m;
    }

    private Map<String, Object> configOf(String slug) {
        ResponseEntity<Map> r = rest.getForEntity(base() + "/api/v1/portal/" + slug + "/config", Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private UUID currentAsset(UUID tenant) {
        return jdbc.queryForObject("SELECT portal_logo_asset_id FROM tenants WHERE id = ?", UUID.class, tenant);
    }

    private byte[] storedBytes(UUID tenant) {
        return jdbc.queryForObject("SELECT a.bytes FROM tenants t JOIN portal_assets a ON a.id = t.portal_logo_asset_id " +
            "WHERE t.id = ?", byte[].class, tenant);
    }

    private ResponseEntity<Map> upload(byte[] bytes, String filename, String contentType, String token) {
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(resource, partHeaders));
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.exchange(base() + "/api/v1/tenant/portal-settings/logo", HttpMethod.PUT, new HttpEntity<>(body, h), Map.class);
    }

    private ResponseEntity<Map> exchange(String path, HttpMethod method, Object body, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base() + path, method, new HttpEntity<>(body, h), Map.class);
    }

    private ResponseEntity<byte[]> getBytes(String path, String token, String ifNoneMatch) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.setBearerAuth(token);
        if (ifNoneMatch != null) h.setIfNoneMatch(ifNoneMatch);
        return rest.exchange(base() + path, HttpMethod.GET, new HttpEntity<>(h), byte[].class);
    }

    private static byte[] png(int w, int h, boolean alpha) throws Exception {
        BufferedImage img = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(15, 118, 110));
        g.fillOval(0, 0, w, h);
        g.dispose();
        return encode(img, "png");
    }

    private static byte[] jpeg(int w, int h) throws Exception {
        return encode(solid(w, h), "jpg");
    }

    private static BufferedImage solid(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private static byte[] encode(BufferedImage img, String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(img, format, out)).isTrue();
        return out.toByteArray();
    }

    private static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    private void deliveredOrder(UUID tenant, UUID store, String number, String phone) {
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, customer_phone) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now(), ?) RETURNING id",
            UUID.class, tenant, store, "gid://shopify/Order/" + UUID.randomUUID(), number, phone);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, delivered_at) " +
                    "VALUES (?, ?, 'bosta', ?, 'delivered'::shipment_internal_state, 'forward', now() - interval '2 days')",
                    tenant, order, String.valueOf(4_100_000_000L + Math.abs(order.getLeastSignificantBits() % 1_000_000_000L)));
    }

    private UUID tenant(String name, String slug) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled) VALUES (?, ?, ?, true)", id, name, slug);
        return id;
    }

    private UUID store(UUID tenant, String slug) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            id, tenant, slug + ".myshopify.com");
        return id;
    }

    private UUID user(UUID tenant, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, ?, ?, ?, ?::user_role, true)",
            id, tenant, role, role + "-" + id + "@test.local", passwordEncoder.encode("pass123"), role);
        return id;
    }

    private String base() { return "http://localhost:" + port; }

    private String login(UUID userId) {
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AccessTokenResponse> resp = rest.postForEntity(base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", email, "password", "pass123"), h), AccessTokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody().accessToken();
    }
}
