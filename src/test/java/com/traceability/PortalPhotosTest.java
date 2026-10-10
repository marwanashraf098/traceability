package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.portal.PortalPhotoService;
import com.traceability.portal.PortalService;
import com.traceability.privacy.CustomerDataRequestService;
import com.traceability.privacy.CustomerRedaction;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import java.io.ByteArrayOutputStream;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Returns portal P3 — customer photos of returned items (V161).
 *   u1 upload: token-bound to its order; JPEG out; GIF named .jpg → PHOTO_TYPE; pixel bomb → PHOTO_PIXELS;
 *      over 8 MB → 413; no token / another tenant's token → 401; per-order cap → 429
 *   c1 claim: only the token's order's unclaimed uploads — another order's, another tenant's, an expired or an
 *      already-claimed photo id rolls the submission back; photos attach to the line's FIRST item row
 *   r1 required: a photoless line refused, > 3 refused, 1–3 accepted; off → optional; existing tenants OFF,
 *      a new signup ON; /config and settings carry requirePhotos
 *   e1 unclaimed uploads deleted after an hour (rows + bytes); claimed / fresh ones kept
 *   p1 retention: bytes removed 90 days after the request ended (row kept, reason retention), not at 89
 *   g1 customers/redact + shop/redact remove bytes (reason privacy); export carries the image while held
 *   m1 merchant read: owner / manager get the bytes (private, no-store); worker 403; other tenant 404; removed 410;
 *      the detail lists ids + dimensions only
 *   x1 app_user: a claimed row can't be deleted, only claim / redaction columns can change, other tenants invisible
 *   l1 photo bytes never reach the logs
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class PortalPhotosTest {

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

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired PortalService portal;
    @Autowired PortalPhotoService photoService;
    @Autowired CustomerDataRequestService dataRequests;
    @Autowired ObjectMapper mapper;

    record T(UUID id, UUID store, UUID variant, String slug, String owner, String manager, String worker, UUID ownerId) {}
    record Order(UUID id, String number, String phone, String gid, UUID orderItem) {}

    T a, b;

    @BeforeAll
    void setup() {
        a = tenant("Snouts P3", "snouts-p3");
        b = tenant("Jumi P3", "jumi-p3");
    }

    @AfterEach
    void reset() {
        for (T t : List.of(a, b)) jdbc.update("UPDATE tenants SET portal_require_photos = true WHERE id = ?", t.id());
    }

    // ── u1 ───────────────────────────────────────────────────────────────────

    @Test
    void u1_upload_tokenBound_validated_capped() throws Exception {
        Order o = order(a, "#U1A", 1);
        String token = lookup(a, o);
        ResponseEntity<Map> ok = upload(a.slug(), token, jpeg(2400, 1800), "IMG_1.jpg", "image/jpeg");
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ok.getBody()).containsEntry("width", 1600).containsEntry("height", 1200);
        UUID photo = UUID.fromString((String) ok.getBody().get("photoId"));
        Map<String, Object> row = jdbc.queryForMap("SELECT order_id, request_id, content_type, asset_id FROM return_request_photos WHERE id = ?", photo);
        assertThat(row).containsEntry("order_id", o.id()).containsEntry("request_id", null).containsEntry("content_type", "image/jpeg");
        assertThat(jdbc.queryForObject("SELECT kind FROM portal_assets WHERE id = ?", String.class, row.get("asset_id"))).isEqualTo("photo");

        ResponseEntity<Map> gif = upload(a.slug(), token, encode(solid(20, 20), "gif"), "spoof.jpg", "image/jpeg");
        assertThat(gif.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(gif.getBody()).containsEntry("error", "PHOTO_TYPE");
        assertThat(upload(a.slug(), token, com.traceability.assets.ImagePipelineTest.pngHeaderOnly(50_000, 50_000), "bomb.png",
            "image/png").getBody()).containsEntry("error", "PHOTO_PIXELS");
        ResponseEntity<Map> huge = upload(a.slug(), token, new byte[9 * 1024 * 1024], "huge.jpg", "image/jpeg");
        assertThat(huge.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);

        // 401s at the service (the JDK test client can't read a 401 to a streamed multipart POST).
        assertThat(photoService.upload(a.slug(), null, jpeg(50, 50)).orElseThrow().outcome())
            .isEqualTo(PortalPhotoService.Outcome.UNAUTHORIZED);
        Order other = order(b, "#U1B", 1);
        assertThat(photoService.upload(a.slug(), lookup(b, other), jpeg(50, 50)).orElseThrow().outcome())
            .as("tenant B's token on tenant A's slug").isEqualTo(PortalPhotoService.Outcome.UNAUTHORIZED);
        assertThat(photoService.upload("no-such-store", token, jpeg(50, 50))).isEmpty();

        // Per-order cap (the token's life).
        jdbc.update("INSERT INTO return_request_photos (tenant_id, order_id, content_type, size_bytes, width, height, sha256) " +
            "SELECT ?, ?, 'image/jpeg', 1, 1, 1, repeat('a', 64) FROM generate_series(1, 30)", a.id(), o.id());
        assertThat(upload(a.slug(), token, jpeg(50, 50), "x.jpg", "image/jpeg").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        jdbc.update("DELETE FROM return_request_photos WHERE order_id = ? AND asset_id IS NULL", o.id());
    }

    // ── c1 ───────────────────────────────────────────────────────────────────

    @Test
    void c1_claim_onlyThisOrdersUnclaimedUploads_firstItemOfTheLine() throws Exception {
        Order o = order(a, "#C1A", 2);
        String token = lookup(a, o);
        UUID p1 = photo(a, token), p2 = photo(a, token);

        Order other = order(a, "#C1B", 1);
        UUID otherOrders = photo(a, lookup(a, other));
        Order bo = order(b, "#C1C", 1);
        UUID otherTenants = photo(b, lookup(b, bo));
        UUID expired = photo(a, token);
        jdbc.update("UPDATE return_request_photos SET created_at = now() - interval '61 minutes' WHERE id = ?", expired);

        for (UUID bad : List.of(otherOrders, otherTenants, expired)) {
            assertThat(submit(a, o, token, 2, List.of(p1, bad))).as(bad.toString()).isEqualTo(PortalService.SubmitOutcome.INVALID);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_request_photos WHERE id IN (?, ?) AND request_id IS NOT NULL",
            Integer.class, p1, p2)).as("a refused submission claims nothing").isZero();

        assertThat(submit(a, o, token, 2, List.of(p1, p2))).isEqualTo(PortalService.SubmitOutcome.CREATED);
        UUID request = jdbc.queryForObject("SELECT id FROM return_requests WHERE order_id = ?", UUID.class, o.id());
        UUID firstItem = jdbc.queryForObject("SELECT id FROM return_request_items WHERE request_id = ? ORDER BY unit_no LIMIT 1",
            UUID.class, request);
        assertThat(jdbc.queryForList("SELECT DISTINCT item_id FROM return_request_photos WHERE request_id = ?", UUID.class, request))
            .containsExactly(firstItem);

        // An already-claimed photo can't be claimed again.
        Order again = order(a, "#C1D", 1);
        assertThat(submit(a, again, lookup(a, again), 1, List.of(p1))).isEqualTo(PortalService.SubmitOutcome.INVALID);
    }

    // ── r1 ───────────────────────────────────────────────────────────────────

    @Test
    void r1_requiredToggle_limits_defaults() throws Exception {
        Order o = order(a, "#R1A", 1);
        String token = lookup(a, o);
        assertThat(submit(a, o, token, 1, List.of())).as("required, none").isEqualTo(PortalService.SubmitOutcome.INVALID);
        List<UUID> four = List.of(photo(a, token), photo(a, token), photo(a, token), photo(a, token));
        assertThat(submit(a, o, token, 1, four)).as("> 3").isEqualTo(PortalService.SubmitOutcome.INVALID);
        assertThat(submit(a, o, token, 1, four.subList(0, 3))).isEqualTo(PortalService.SubmitOutcome.CREATED);

        jdbc.update("UPDATE tenants SET portal_require_photos = false WHERE id = ?", a.id());
        Order opt = order(a, "#R1B", 1);
        assertThat(submit(a, opt, lookup(a, opt), 1, List.of())).as("optional").isEqualTo(PortalService.SubmitOutcome.CREATED);
        assertThat(portal.config(a.slug()).orElseThrow()).containsEntry("requirePhotos", false);

        // Settings: switch it, absent leaves it.
        Map<String, Object> body = new HashMap<>(Map.of("slug", a.slug(), "enabled", true, "autoApprove", false, "returnWindowDays", 30));
        body.put("requirePhotos", true);
        assertThat(exchange("/api/v1/tenant/portal-settings", HttpMethod.PUT, body, a.owner()).getBody()).containsEntry("requirePhotos", true);
        body.remove("requirePhotos");
        assertThat(exchange("/api/v1/tenant/portal-settings", HttpMethod.PUT, body, a.owner()).getBody()).containsEntry("requirePhotos", true);
        assertThat(portal.config(a.slug()).orElseThrow()).containsEntry("requirePhotos", true);

        // Defaults: a row created like every pre-V161 store → OFF; a new signup → ON.
        UUID existing = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Existing store')", existing);
        assertThat(jdbc.queryForObject("SELECT portal_require_photos FROM tenants WHERE id = ?", Boolean.class, existing)).isFalse();
        ResponseEntity<Map> signup = rest.postForEntity(base() + "/api/v1/auth/signup",
            Map.of("tenantName", "Fresh P3 Store", "name", "fresh", "email", "fresh-p3@test.local", "phone", "01055556666",
                "password", "Password99!", "consent", true), Map.class);
        assertThat(signup.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT t.portal_require_photos FROM tenants t JOIN users u ON u.tenant_id = t.id " +
            "WHERE u.email = 'fresh-p3@test.local'", Boolean.class)).isTrue();
    }

    // ── e1 ───────────────────────────────────────────────────────────────────

    @Test
    void e1_unclaimedExpireAfterAnHour() throws Exception {
        Order o = order(a, "#E1A", 1);
        String token = lookup(a, o);
        UUID old = photo(a, token), fresh = photo(a, token), claimed = photo(a, token);
        assertThat(submit(a, o, token, 1, List.of(claimed))).isEqualTo(PortalService.SubmitOutcome.CREATED);
        jdbc.update("UPDATE return_request_photos SET created_at = now() - interval '61 minutes' WHERE id IN (?, ?)", old, claimed);
        UUID oldAsset = assetOf(old);

        assertThat(photoService.expireUnclaimed(a.id())).isEqualTo(1);
        assertThat(exists(old)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM portal_assets WHERE id = ?", Integer.class, oldAsset)).isZero();
        assertThat(exists(fresh)).isTrue();
        assertThat(assetOf(claimed)).isNotNull();
    }

    // ── p1 ───────────────────────────────────────────────────────────────────

    @Test
    void p1_retention_90DaysAfterTheRequestEnded() throws Exception {
        UUID p91 = requestWithPhoto(a, "#P1A"), p89 = requestWithPhoto(a, "#P1B"), open = requestWithPhoto(a, "#P1C");
        endRequest(p91, 91);
        endRequest(p89, 89);
        UUID asset91 = assetOf(p91);

        photoService.purgeEnded(a.id());
        assertThat(jdbc.queryForMap("SELECT asset_id, redaction_reason, redacted_at IS NOT NULL AS removed FROM return_request_photos WHERE id = ?", p91))
            .containsEntry("asset_id", null).containsEntry("redaction_reason", "retention").containsEntry("removed", true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM portal_assets WHERE id = ?", Integer.class, asset91)).isZero();
        assertThat(assetOf(p89)).isNotNull();
        assertThat(assetOf(open)).isNotNull();
    }

    // ── g1 ───────────────────────────────────────────────────────────────────

    @Test
    void g1_redactAndExport() throws Exception {
        Order o = order(a, "#G1A", 1);
        String token = lookup(a, o);
        UUID p = photo(a, token);
        assertThat(submit(a, o, token, 1, List.of(p))).isEqualTo(PortalService.SubmitOutcome.CREATED);

        JsonNode held = export(a, o);
        JsonNode ph = held.path("return_request_photos").get(0);
        assertThat(ph.path("id").asText()).isEqualTo(p.toString());
        assertThat(ph.path("image").asText()).startsWith("data:image/jpeg;base64,/9j/");
        assertThat(ph.has("asset_id")).isFalse();

        TenantContext.runAs(a.id(), () -> new CustomerRedaction(jdbc).redactCustomer(a.id(), List.of(o.gid()), null, null));
        assertThat(jdbc.queryForMap("SELECT asset_id, redaction_reason FROM return_request_photos WHERE id = ?", p))
            .containsEntry("asset_id", null).containsEntry("redaction_reason", "privacy");
        JsonNode after = export(a, o).path("return_request_photos").get(0);
        assertThat(after.path("image").isNull()).isTrue();
        assertThat(after.path("redaction_reason").asText()).isEqualTo("privacy");

        Order bo = order(b, "#G1B", 1);
        UUID bp = photo(b, lookup(b, bo));   // unclaimed counts too
        TenantContext.runAs(b.id(), () -> new CustomerRedaction(jdbc).redactShop(b.id()));
        assertThat(assetOf(bp)).isNull();
    }

    // ── m1 ───────────────────────────────────────────────────────────────────

    @Test
    void m1_merchantRead_roles_crossTenant_removed_detail() throws Exception {
        UUID p = requestWithPhoto(a, "#M1A");
        UUID request = jdbc.queryForObject("SELECT request_id FROM return_request_photos WHERE id = ?", UUID.class, p);
        String url = "/api/v1/return-requests/" + request + "/photos/" + p;

        ResponseEntity<byte[]> own = bytes(url, a.owner());
        assertThat(own.getStatusCode()).as("same-tenant positive control").isEqualTo(HttpStatus.OK);
        assertThat(own.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(own.getHeaders().getCacheControl()).contains("no-store").contains("private");
        assertThat(own.getBody()).isEqualTo(jdbc.queryForObject(
            "SELECT a.bytes FROM portal_assets a JOIN return_request_photos p ON p.asset_id = a.id WHERE p.id = ?", byte[].class, p));
        assertThat(bytes(url, a.manager()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bytes(url, a.worker()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bytes(url, b.owner()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        String detail = exchange("/api/v1/return-requests/" + request, HttpMethod.GET, null, a.owner(), String.class).getBody();
        assertThat(detail).contains("\"photos\":[{\"id\":\"" + p + "\",\"width\":").doesNotContain("base64");

        endRequest(p, 100);
        photoService.purgeEnded(a.id());
        assertThat(bytes(url, a.owner()).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(exchange("/api/v1/return-requests/" + request, HttpMethod.GET, null, a.owner(), String.class).getBody())
            .contains("\"photos\":[]").contains("\"reason\":\"retention\"");
    }

    // ── x1 ───────────────────────────────────────────────────────────────────

    @Test
    void x1_appUser_deleteOnlyUnclaimed_columnGrants_isolation() throws Exception {
        UUID claimed = requestWithPhoto(a, "#X1A");
        Order o = order(a, "#X1B", 1);
        UUID unclaimed = photo(a, lookup(a, o));
        DataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate app = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        Integer deletedClaimed = TenantContext.runAs(a.id(), () -> tx.execute(s ->
            app.update("DELETE FROM return_request_photos WHERE id = ?", claimed)));
        assertThat(deletedClaimed).as("a claimed photo is a record — never deleted").isZero();
        assertThatThrownBy(() -> TenantContext.runAs(a.id(), () -> tx.execute(s ->
            app.update("UPDATE return_request_photos SET order_id = order_id WHERE id = ?", unclaimed))))
            .rootCause().hasMessageContaining("permission denied");
        Integer seenByB = TenantContext.runAs(b.id(), () -> tx.execute(s ->
            app.queryForObject("SELECT COUNT(*) FROM return_request_photos WHERE id IN (?, ?)", Integer.class, claimed, unclaimed)));
        assertThat(seenByB).isZero();
        Integer deletedByB = TenantContext.runAs(b.id(), () -> tx.execute(s ->
            app.update("DELETE FROM return_request_photos WHERE id = ?", unclaimed)));
        assertThat(deletedByB).isZero();
        // Positive control: tenant A deletes its own unclaimed upload.
        Integer deletedOwn = TenantContext.runAs(a.id(), () -> tx.execute(s ->
            app.update("DELETE FROM return_request_photos WHERE id = ?", unclaimed)));
        assertThat(deletedOwn).isEqualTo(1);
        assertThat(exists(claimed)).isTrue();
    }

    // ── l1 ───────────────────────────────────────────────────────────────────

    @Test
    void l1_photoBytesNeverLogged(CapturedOutput output) throws Exception {
        Order o = order(a, "#L1A", 1);
        String token = lookup(a, o);
        byte[] original = jpeg(300, 200);
        UUID p = UUID.fromString((String) upload(a.slug(), token, original, "IMG_9.jpg", "image/jpeg").getBody().get("photoId"));
        upload(a.slug(), token, encode(solid(20, 20), "gif"), "bad.jpg", "image/jpeg");
        assertThat(submit(a, o, token, 1, List.of(p))).isEqualTo(PortalService.SubmitOutcome.CREATED);
        UUID request = jdbc.queryForObject("SELECT request_id FROM return_request_photos WHERE id = ?", UUID.class, p);
        bytes("/api/v1/return-requests/" + request + "/photos/" + p, a.owner());
        export(a, o);
        endRequest(p, 100);
        photoService.purgeEnded(a.id());

        String stored = Base64.getEncoder().encodeToString(original);
        assertThat(output.getAll()).doesNotContain(stored.substring(0, 60)).doesNotContain("data:image/jpeg;base64")
            .doesNotContain("IMG_9.jpg");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID requestWithPhoto(T t, String number) throws Exception {
        Order o = order(t, number, 1);
        String token = lookup(t, o);
        UUID p = photo(t, token);
        assertThat(submit(t, o, token, 1, List.of(p))).isEqualTo(PortalService.SubmitOutcome.CREATED);
        return p;
    }

    private void endRequest(UUID photo, int daysAgo) {
        jdbc.update("UPDATE return_requests SET status = 'refunded', refunded_at = now() - (interval '1 day' * ?) " +
            "WHERE id = (SELECT request_id FROM return_request_photos WHERE id = ?)", daysAgo, photo);
    }

    private UUID assetOf(UUID photo) {
        return jdbc.queryForObject("SELECT asset_id FROM return_request_photos WHERE id = ?", UUID.class, photo);
    }

    private boolean exists(UUID photo) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM return_request_photos WHERE id = ?", Integer.class, photo) == 1;
    }

    private JsonNode export(T t, Order o) throws Exception {
        UUID dr = UUID.randomUUID();
        jdbc.update("INSERT INTO customer_data_requests (id, tenant_id, webhook_event_id, shop_domain, orders_requested) " +
            "VALUES (?, ?, ?, ?, ?::text[])", dr, t.id(), UUID.randomUUID(), t.slug() + ".myshopify.com", new String[]{o.gid()});
        return mapper.readTree(TenantContext.runAs(t.id(), () -> dataRequests.export(t.id(), dr, t.ownerId())).json());
    }

    private UUID photo(T t, String token) throws Exception {
        ResponseEntity<Map> r = upload(t.slug(), token, jpeg(120, 90), "IMG.jpg", "image/jpeg");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("photoId"));
    }

    private PortalService.SubmitOutcome submit(T t, Order o, String token, int qty, List<UUID> photoIds) {
        PortalService.SubmitRequest req = new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(null, qty, "damaged", o.orderItem(), photoIds)), null, null, null);
        return portal.submit(t.slug(), token, req).orElseThrow().outcome();
    }

    private String lookup(T t, Order o) {
        PortalService.LookupResult r = portal.lookup(t.slug(), o.number(), o.phone()).orElseThrow();
        assertThat(r.outcome()).isEqualTo(PortalService.Outcome.SUCCESS);
        return (String) r.body().get("token");
    }

    private ResponseEntity<Map> upload(String slug, String token, byte[] bytes, String filename, String type) {
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.parseMediaType(type));
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(new ByteArrayResource(bytes) { @Override public String getFilename() { return filename; } }, part));
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.setBearerAuth(token);
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.exchange(base() + "/api/v1/portal/" + slug + "/photos", HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }

    private ResponseEntity<byte[]> bytes(String path, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange(base() + path, HttpMethod.GET, new HttpEntity<>(h), byte[].class);
    }

    private ResponseEntity<Map> exchange(String path, HttpMethod m, Object body, String token) {
        return exchange(path, m, body, token, Map.class);
    }

    private <X> ResponseEntity<X> exchange(String path, HttpMethod m, Object body, String token, Class<X> type) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base() + path, m, new HttpEntity<>(body, h), type);
    }

    private static byte[] jpeg(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(150, 120, 90));
        g.fillRect(0, 0, w, h);
        g.setColor(Color.WHITE);
        g.fillOval(w / 4, h / 4, w / 2, h / 2);
        g.dispose();
        return encode(img, "jpg");
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

    /** A delivered order with one UNTRACKED line of quantity {@code qty} (no pieces needed). */
    private Order order(T t, String number, int qty) {
        String phone = "010" + (10_000_000 + new Random().nextInt(89_999_999));
        String gid = "gid://shopify/Order/" + UUID.randomUUID();
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, customer_phone) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now(), ?) RETURNING id",
            UUID.class, t.id(), t.store(), gid, number, phone);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, delivered_at) " +
            "VALUES (?, ?, 'bosta', ?, 'delivered'::shipment_internal_state, 'forward', now() - interval '2 days')",
            t.id(), order, String.valueOf(4_300_000_000L + Math.abs(order.getLeastSignificantBits() % 1_000_000_000L)));
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, ?)",
            item, t.id(), order, t.variant(), qty);
        return new Order(order, number, phone, gid, item);
    }

    private T tenant(String name, String slug) {
        UUID id = UUID.randomUUID(), store = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled, portal_require_photos) VALUES (?, ?, ?, true, true)",
            id, name, slug);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, id, slug + ".myshopify.com");
        UUID p = UUID.randomUUID(), v = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Linen Shirt', 'active')",
            p, id, store, "P-" + p);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'White / M', ?)",
            v, id, p, "V-" + v, "SKU-" + v.toString().substring(0, 6));
        UUID ownerId = user(id, "owner");
        return new T(id, store, v, slug, login(ownerId), login(user(id, "manager")), login(user(id, "worker")), ownerId);
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
