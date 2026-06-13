package com.traceability;

import com.traceability.inventory.*;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.traceability.inventory.PieceStatus.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Day-3 integration tests for InventoryLedger.transition().
 *
 * Role split:
 *   ledger / jdbc  → postgres (BYPASSRLS): tests (a)(b)(c) verify logic, state machine,
 *                    and race semantics independently of RLS.
 *   appUserLedger  → app_user (RLS enforced, no BYPASSRLS): tests (d)(e) verify the
 *                    append-only REVOKE and tenant-isolation fail-closed behaviour by
 *                    invoking the real transition() method over an app_user connection.
 *
 * @TestInstance(PER_CLASS) + static initializer:
 *   With PER_CLASS, Spring's postProcessTestInstance() fires before Testcontainers'
 *   BeforeAllCallback, so @DynamicPropertySource would try POSTGRES.getJdbcUrl() before
 *   the container exists. The static initializer starts the container at class-load time,
 *   guaranteeing it is running before Spring's context initialization. @BeforeAll is then
 *   non-static and can use @Autowired jdbc (injected by postProcessTestInstance).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InventoryLedgerTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    // Eager start so the container is running when @DynamicPropertySource fires
    // (Spring context init runs before TestcontainersExtension.beforeAll() with PER_CLASS).
    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
    }

    // ---- Spring beans (postgres role, BYPASSRLS) ---------------------------
    @Autowired InventoryLedger ledger;
    @Autowired JdbcTemplate    jdbc;   // postgres — for fixture insert and verification

    // ---- app_user infrastructure (built in @BeforeAll after TestSetup fires) ----
    InventoryLedger      appUserLedger;
    TransactionTemplate  appUserTx;
    JdbcTemplate         appUserJdbc;

    // ---- shared fixture IDs -----------------------------------------------
    UUID tenantId;
    UUID variantId;
    UUID actorId;   // a real users row so pieces.last_user_id FK is satisfied

    // -----------------------------------------------------------------------
    // Fixture setup — runs once, after Spring context is up and TestSetup has
    // set the app_user password via ApplicationReadyEvent.
    // -----------------------------------------------------------------------

    @BeforeAll
    void setupFixtures() {
        tenantId  = UUID.randomUUID();
        actorId   = UUID.randomUUID();
        UUID storeId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();

        jdbc.update(
            "INSERT INTO tenants (id, name, plan, status) VALUES (?, 'Ledger Test Co', 'trial', 'trial')",
            tenantId);
        // Real user row so pieces.last_user_id and piece_events.actor_user_id FKs are satisfied.
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Test Actor', 'actor@ledger-test.com', 'hash', 'owner')",
            actorId, tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'ledger-test.myshopify.com', 'disconnected')",
            storeId, tenantId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-001', 'Test Product', 'active')",
            productId, tenantId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title) " +
            "VALUES (?, ?, ?, 'VAR-001', 'Test Variant')",
            variantId, tenantId, productId);

        // app_user datasource — TestSetup (ApplicationReadyEvent) ran before @BeforeAll,
        // so 'testpw' is set. DriverManagerDataSource opens connections on demand;
        // no actual connection is made here.
        DriverManagerDataSource rawAppUser = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(rawAppUser);
        appUserJdbc = new JdbcTemplate(appUserDs);
        DataSourceTransactionManager appUserTxm = new DataSourceTransactionManager(appUserDs);
        appUserTx = new TransactionTemplate(appUserTxm);
        // Non-proxied instance: @Transactional annotation is not active, but appUserTx
        // supplies the transaction boundary, so TenantAwareConnection fires SET LOCAL and
        // all three SQL statements share the same connection within one transaction.
        appUserLedger = new InventoryLedger(appUserJdbc);
    }

    @BeforeEach
    void setTenantContext() {
        TenantContext.set(tenantId);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // -----------------------------------------------------------------------
    // helpers (postgres role — bypasses RLS, suitable for setup and verification)
    // -----------------------------------------------------------------------

    private String insertPiece(PieceStatus initialStatus) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, status) " +
            "VALUES (?, ?, ?, ?, ?::piece_status)",
            id, tenantId, variantId, "PC-" + id, initialStatus.db);
        return id;
    }

    private int countEvents(String pieceId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM piece_events WHERE piece_id = ?", Integer.class, pieceId);
        return n == null ? 0 : n;
    }

    private String fetchStatus(String pieceId) {
        return jdbc.queryForObject(
                "SELECT status FROM pieces WHERE id = ?", String.class, pieceId);
    }

    // -----------------------------------------------------------------------
    // (a) Every legal transition succeeds and writes exactly one correct event
    // -----------------------------------------------------------------------

    static Stream<Arguments> legalTransitions() {
        return Stream.of(
            Arguments.of(AVAILABLE,                 RESERVED),
            Arguments.of(AVAILABLE,                 DAMAGED),
            Arguments.of(AVAILABLE,                 LOST),
            Arguments.of(AVAILABLE,                 DESTROYED),
            Arguments.of(RESERVED,                  AVAILABLE),
            Arguments.of(RESERVED,                  PACKED),
            Arguments.of(PACKED,                    AVAILABLE),
            Arguments.of(PACKED,                    AWAITING_PICKUP),
            Arguments.of(AWAITING_PICKUP,           WITH_COURIER),
            Arguments.of(AWAITING_PICKUP,           AVAILABLE),
            Arguments.of(WITH_COURIER,              DELIVERED),
            Arguments.of(WITH_COURIER,              RETURN_IN_TRANSIT),
            Arguments.of(WITH_COURIER,              LOST),
            Arguments.of(RETURN_IN_TRANSIT,         RETURN_PENDING_INSPECTION),
            Arguments.of(RETURN_PENDING_INSPECTION, AVAILABLE),
            Arguments.of(RETURN_PENDING_INSPECTION, DAMAGED),
            Arguments.of(DAMAGED,                   DESTROYED),
            Arguments.of(LOST,                      AVAILABLE)
        );
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("legalTransitions")
    void legalTransition_succeedsAndWritesOneEvent(PieceStatus from, PieceStatus to) {
        String pieceId = insertPiece(from);

        ledger.transition(pieceId, from, to, "test_event", actorId, TransitionContext.empty());

        assertThat(countEvents(pieceId)).as("exactly one event written").isEqualTo(1);
        assertThat(fetchStatus(pieceId)).as("piece status updated").isEqualTo(to.db);

        var row = jdbc.queryForMap(
                "SELECT event_type, actor_user_id, from_status, to_status " +
                "FROM piece_events WHERE piece_id = ?", pieceId);
        assertThat(row.get("event_type")).isEqualTo("test_event");
        assertThat(row.get("actor_user_id").toString()).isEqualTo(actorId.toString());
        assertThat(row.get("from_status")).isEqualTo(from.db);
        assertThat(row.get("to_status")).isEqualTo(to.db);
    }

    // -----------------------------------------------------------------------
    // (b) Every illegal transition throws before any DB access, writes no event
    // -----------------------------------------------------------------------

    static Stream<Arguments> illegalTransitions() {
        return Stream.of(
            Arguments.of(AVAILABLE,  PACKED),           // skip reserved
            Arguments.of(AVAILABLE,  DELIVERED),        // skip entire chain
            Arguments.of(RESERVED,   AWAITING_PICKUP),  // skip packed
            Arguments.of(PACKED,     RESERVED),         // backward
            Arguments.of(DELIVERED,  AVAILABLE),        // terminal → forward
            Arguments.of(LOST,       DELIVERED),        // terminal → other
            Arguments.of(DESTROYED,  AVAILABLE)         // terminal → anything
        );
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("illegalTransitions")
    void illegalTransition_throwsBeforeDbAndWritesNoEvent(PieceStatus from, PieceStatus to) {
        String pieceId = insertPiece(from);

        assertThatThrownBy(() ->
            ledger.transition(pieceId, from, to, "bad_event", null, TransitionContext.empty())
        ).isInstanceOf(IllegalTransitionException.class);

        assertThat(countEvents(pieceId)).as("no event written for illegal transition").isEqualTo(0);
        assertThat(fetchStatus(pieceId)).as("piece status unchanged").isEqualTo(from.db);
    }

    // -----------------------------------------------------------------------
    // (c) Race guard: two concurrent callers → exactly one wins, one event row
    // -----------------------------------------------------------------------

    @Test
    void raceGuard_exactlyOneWins_exactlyOneEventWritten() throws InterruptedException {
        String pieceId = insertPiece(AVAILABLE);

        CountDownLatch ready     = new CountDownLatch(2);
        CountDownLatch go        = new CountDownLatch(1);
        AtomicInteger  successes = new AtomicInteger();
        AtomicInteger  conflicts = new AtomicInteger();

        Runnable attempt = () -> {
            TenantContext.set(tenantId); // each thread sets its own ThreadLocal
            try {
                ready.countDown();
                go.await();
                ledger.transition(pieceId, AVAILABLE, RESERVED,
                        "race_reservation", null, TransitionContext.empty());
                successes.incrementAndGet();
            } catch (StateConflictException | PieceNotFoundException e) {
                conflicts.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                TenantContext.clear();
            }
        };

        Thread t1 = new Thread(attempt);
        Thread t2 = new Thread(attempt);
        t1.start();
        t2.start();
        ready.await();  // both threads ready at the starting line
        go.countDown(); // release simultaneously
        t1.join();
        t2.join();

        assertThat(successes.get()).as("exactly one thread wins").isEqualTo(1);
        assertThat(conflicts.get()).as("exactly one thread loses").isEqualTo(1);
        assertThat(countEvents(pieceId)).as("exactly one event written").isEqualTo(1);
        assertThat(fetchStatus(pieceId)).as("piece is now reserved").isEqualTo(RESERVED.db);
    }

    // -----------------------------------------------------------------------
    // (d) piece_events is append-only at the privilege level (app_user role).
    //     V1 migration: REVOKE UPDATE, DELETE ON piece_events FROM app_user.
    //     PostgreSQL checks table privileges before RLS, so GUC is irrelevant
    //     here — but we set TenantContext anyway to isolate the REVOKE as the
    //     sole rejection reason.
    // -----------------------------------------------------------------------

    @Test
    void pieceEvents_appendOnly_updateAndDeleteDeniedForAppUser() {
        String pieceId = insertPiece(AVAILABLE);
        ledger.transition(pieceId, AVAILABLE, RESERVED, "alloc", null, TransitionContext.empty());

        long eventId = jdbc.queryForObject(
                "SELECT id FROM piece_events WHERE piece_id = ?", Long.class, pieceId);

        // Spring 6.x DataAccessException.getMessage() no longer appends the cause;
        // "permission denied" lives in getCause().getMessage() (the PSQLException).
        assertThatThrownBy(() ->
            appUserTx.execute(status -> {
                appUserJdbc.update(
                    "UPDATE piece_events SET event_type = 'hacked' WHERE id = ?", eventId);
                return null;
            })
        ).isInstanceOf(DataAccessException.class)
         .cause()
         .hasMessageContaining("permission denied");

        assertThatThrownBy(() ->
            appUserTx.execute(status -> {
                appUserJdbc.update("DELETE FROM piece_events WHERE id = ?", eventId);
                return null;
            })
        ).isInstanceOf(DataAccessException.class)
         .cause()
         .hasMessageContaining("permission denied");

        assertThat(countEvents(pieceId))
                .as("event row survives both rejected write attempts").isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // (e) No tenant context → RLS fail-closed: the REAL ledger.transition()
    //     called as app_user with no GUC set → UPDATE matches 0 rows (RLS
    //     blocks) → diagnostic SELECT also returns nothing → PieceNotFoundException
    //     → zero piece_events written, piece status unchanged.
    //
    //     appUserLedger is a real InventoryLedger wired to an app_user
    //     TenantAwareDataSource. appUserTx supplies the transaction boundary
    //     so TenantAwareConnection's setAutoCommit(false) intercept fires —
    //     but with TenantContext cleared, it sets no GUC.
    //
    //     NOTE: a piece belonging to a DIFFERENT tenant produces the same
    //     PieceNotFoundException — RLS makes it invisible, so the diagnostic
    //     SELECT returns nothing just as if the piece did not exist. This is
    //     intentional (never reveal another tenant's piece ID); do not
    //     "fix" it by distinguishing not-found from cross-tenant.
    // -----------------------------------------------------------------------

    @Test
    void noTenantContext_rlsFailClosed_throwsAndWritesNoEvent() {
        String pieceId = insertPiece(AVAILABLE);

        // Clear context: TenantAwareConnection will not call SET LOCAL in the transaction.
        TenantContext.clear();

        assertThatThrownBy(() ->
            appUserTx.execute(status -> {
                appUserLedger.transition(
                        pieceId, AVAILABLE, RESERVED, "should_not_be_written",
                        null, TransitionContext.empty());
                return null;
            })
        ).as("app_user with no GUC must fail closed")
         .isInstanceOf(PieceNotFoundException.class);

        // Restore context so the verification queries (postgres role) can run.
        TenantContext.set(tenantId);

        assertThat(countEvents(pieceId))
                .as("zero piece_events written when RLS blocks the transition")
                .isEqualTo(0);
        assertThat(fetchStatus(pieceId))
                .as("piece status unchanged after blocked transition")
                .isEqualTo(AVAILABLE.db);
    }
}
