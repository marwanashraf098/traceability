package com.traceability.integrations.bosta;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.stereotype.Component;

/**
 * Exposes the currently-applied Flyway schema version as the matcher version tag.
 *
 * Used by BostaWebhookJob (step-4 dedup, markProcessed, recordUnlinked) and
 * BostaIngestionHelper (Guard 3) to stamp processed rows and gate retries.
 *
 * Semantics: two rows with the same idem key but different matcher_version values
 * represent the SAME delivery state processed by DIFFERENT matching logic. A deploy
 * that bumps the Flyway version makes old unlinked rows retry-eligible — Guard 3
 * passes (old version ≠ current), step 4 passes (unlinked at old version → not
 * alreadyDone), and tryMatchDelivery runs once with the new logic.
 *
 * Version strings are compared with strict equality (= / <>), never with < / >
 * ordering, since Flyway version strings do not sort lexically as numeric values.
 */
@Component
public class MatcherVersionHolder {

    private final String version;

    public MatcherVersionHolder(Flyway flyway) {
        MigrationInfo current = flyway.info().current();
        this.version = (current != null) ? current.getVersion().getVersion() : "0";
    }

    public String get() { return version; }
}
