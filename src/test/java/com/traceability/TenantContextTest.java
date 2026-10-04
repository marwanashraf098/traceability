package com.traceability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.traceability.tenancy.TenantContext;
import com.traceability.tenancy.TenantContextSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TenantContext.runAs restores the previous tenant (both overloads) — nested, from none, on an
 * exception — and its tenant-switch guard: a DIFFERENT tenant while a transaction is active
 * throws under THROW (the test policy) and logs a WARN under WARN (production); the same
 * tenant, or no active transaction, is a no-op.
 */
class TenantContextTest {

    static final UUID A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    static final UUID B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void captureLog() {
        logger = (Logger) LoggerFactory.getLogger(TenantContext.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void cleanup() {
        logger.detachAppender(logs);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    // ── restore ──────────────────────────────────────────────────────────────

    @Test
    void nestedBInA_restoresA_callable() {
        UUID seenInside = TenantContext.runAs(A, () -> {
            UUID inner = TenantContext.runAs(B, TenantContext::get);
            assertThat(inner).isEqualTo(B);
            return TenantContext.get();
        });
        assertThat(seenInside).as("A is back after the nested B").isEqualTo(A);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void nestedBInA_restoresA_runnable() {
        AtomicReference<UUID> after = new AtomicReference<>();
        TenantContext.runAs(A, () -> {
            TenantContext.runAs(B, () -> assertThat(TenantContext.get()).isEqualTo(B));
            after.set(TenantContext.get());
        });
        assertThat(after.get()).isEqualTo(A);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void underARequestTenant_runAsRestoresIt() {
        TenantContext.set(A);                       // as TenantContextFilter does
        TenantContext.runAs(B, () -> {});
        assertThat(TenantContext.get()).isEqualTo(A);
        TenantContext.runAs(B, () -> "x");
        assertThat(TenantContext.get()).isEqualTo(A);
    }

    @Test
    void fromNone_leavesNone_bothOverloads() {
        TenantContext.runAs(A, () -> {});
        assertThat(TenantContext.get()).isNull();
        TenantContext.runAs(A, () -> 1);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void exception_restoresAndRethrows_unchanged() {
        IllegalArgumentException boom = new IllegalArgumentException("boom");
        TenantContext.set(A);
        assertThatThrownBy(() -> TenantContext.runAs(B, () -> { throw boom; }))
            .as("Runnable: RuntimeException as-is").isSameAs(boom);
        assertThat(TenantContext.get()).isEqualTo(A);

        assertThatThrownBy(() -> TenantContext.runAs(B, () -> { if (true) throw boom; return 1; }))
            .as("Callable: RuntimeException as-is").isSameAs(boom);
        assertThat(TenantContext.get()).isEqualTo(A);

        IOException checked = new IOException("io");
        assertThatThrownBy(() -> TenantContext.runAs(B, () -> { if (true) throw checked; return 1; }))
            .as("Callable: checked wrapped, as before").isExactlyInstanceOf(RuntimeException.class).hasCause(checked);
        assertThat(TenantContext.get()).isEqualTo(A);

        TenantContext.clear();
        assertThatThrownBy(() -> TenantContext.runAs(B, () -> { throw boom; })).isSameAs(boom);
        assertThat(TenantContext.get()).as("from none, an exception still leaves none").isNull();
    }

    // ── tenant-switch guard ──────────────────────────────────────────────────

    @Test
    void sameTenantNesting_isANoOp_evenInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TenantContext.runAs(A, () -> TenantContext.runAs(A, () -> assertThat(TenantContext.get()).isEqualTo(A)));
        assertThat(logs.list).isEmpty();
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void switchInsideActiveTransaction_throwsUnderTheTestPolicy() {
        assertThat(TenantContext.switchPolicy()).as("tests run with THROW").isEqualTo(TenantContext.SwitchPolicy.THROW);
        TenantContext.set(A);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> TenantContext.runAs(B, () -> {}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("aaaaaaaa -> bbbbbbbb")
            .hasMessageContaining("TenantContextTest.");
        assertThat(TenantContext.get()).as("refused before switching").isEqualTo(A);
    }

    @Test
    void switchInsideActiveTransaction_warnsAndContinuesUnderWarn() {
        TenantContext.setSwitchPolicy(TenantContext.SwitchPolicy.WARN);
        TenantContext.set(A);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        UUID inside = TenantContext.runAs(B, TenantContext::get);
        assertThat(inside).isEqualTo(B);
        assertThat(TenantContext.get()).isEqualTo(A);
        assertThat(logs.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage())
                .contains("aaaaaaaa -> bbbbbbbb")
                .contains("its GUC stays aaaaaaaa")
                .contains("TenantContextTest.switchInsideActiveTransaction_warnsAndContinuesUnderWarn")
                .doesNotContain(A.toString()).doesNotContain(B.toString());        // short ids only
        });
    }

    @Test
    void switchWithNoActiveTransaction_isFine() {
        TenantContext.set(A);
        TenantContext.runAs(B, () -> assertThat(TenantContext.get()).isEqualTo(B));
        assertThat(logs.list).isEmpty();
        assertThat(TenantContext.get()).isEqualTo(A);
    }

    @Test
    void settingsBean_mapsTheFlag() {
        new TenantContextSettings("warn");
        assertThat(TenantContext.switchPolicy()).isEqualTo(TenantContext.SwitchPolicy.WARN);
        new TenantContextSettings(" THROW ");
        assertThat(TenantContext.switchPolicy()).isEqualTo(TenantContext.SwitchPolicy.THROW);
        new TenantContextSettings("anything-else");
        assertThat(TenantContext.switchPolicy()).isEqualTo(TenantContext.SwitchPolicy.WARN);
    }
}
