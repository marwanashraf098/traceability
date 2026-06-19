-- JobRunr 7.3.0 schema — collapsed final DDL from all 15 internal migrations.
--
-- Source: extracted from jobrunr-7.3.0.jar
--   org/jobrunr/storage/sql/common/migrations/v000–v015
--   org/jobrunr/storage/sql/postgres/migrations/v014 (Postgres-specific override)
--
-- skip-create=true is kept in application.yml so JobRunr never attempts DDL.
-- This migration (run by Flyway as postgres/owner) creates the tables in their
-- final collapsed form and pre-populates jobrunr_migrations so that if
-- skip-create is ever changed to false, JobRunr sees all scripts as already applied.
--
-- app_user gets SELECT/INSERT/UPDATE/DELETE automatically via the
-- ALTER DEFAULT PRIVILEGES already set in V1 (Flyway runs as postgres = same grantor).
-- Explicit GRANTs below are belt-and-suspenders — removes any dependency on the
-- ALTER DEFAULT PRIVILEGES ordering across sessions.

-- ── Internal migration tracker (v000) ────────────────────────────────────────
CREATE TABLE jobrunr_migrations
(
    id          nchar(36)   PRIMARY KEY,
    script      varchar(64) NOT NULL,
    installedOn varchar(29) NOT NULL
);

-- ── Jobs (v001 + v006 + v014 index changes) ──────────────────────────────────
CREATE TABLE jobrunr_jobs
(
    id             nchar(36)    PRIMARY KEY,
    version        int          NOT NULL,
    jobAsJson      text         NOT NULL,
    jobSignature   varchar(512) NOT NULL,
    state          varchar(36)  NOT NULL,
    createdAt      timestamp    NOT NULL,
    updatedAt      timestamp    NOT NULL,
    scheduledAt    timestamp,
    recurringJobId varchar(128)
);

CREATE INDEX jobrunr_state_idx              ON jobrunr_jobs (state);
CREATE INDEX jobrunr_job_signature_idx      ON jobrunr_jobs (jobSignature);
CREATE INDEX jobrunr_job_created_at_idx     ON jobrunr_jobs (createdAt);
CREATE INDEX jobrunr_job_scheduled_at_idx   ON jobrunr_jobs (scheduledAt);
CREATE INDEX jobrunr_job_rci_idx            ON jobrunr_jobs (recurringJobId);
-- v014 drops updatedAt index and replaces with compound (state, updatedAt):
CREATE INDEX jobrunr_jobs_state_updated_idx ON jobrunr_jobs (state ASC, updatedAt ASC);

-- ── Recurring jobs (v002 + v013) ─────────────────────────────────────────────
CREATE TABLE jobrunr_recurring_jobs
(
    id        nchar(128) PRIMARY KEY,
    version   int        NOT NULL,
    jobAsJson text       NOT NULL,
    createdAt bigint     NOT NULL DEFAULT 0
);

CREATE INDEX jobrunr_recurring_job_created_at_idx ON jobrunr_recurring_jobs (createdAt);

-- ── Background job servers (v003 + v007 + v015) ───────────────────────────────
CREATE TABLE jobrunr_backgroundjobservers
(
    id                       nchar(36)     PRIMARY KEY,
    workerPoolSize           int           NOT NULL,
    pollIntervalInSeconds    int           NOT NULL,
    firstHeartbeat           timestamp(6)  NOT NULL,
    lastHeartbeat            timestamp(6)  NOT NULL,
    running                  int           NOT NULL,
    systemTotalMemory        bigint        NOT NULL,
    systemFreeMemory         bigint        NOT NULL,
    systemCpuLoad            numeric(3, 2) NOT NULL,
    processMaxMemory         bigint        NOT NULL,
    processFreeMemory        bigint        NOT NULL,
    processAllocatedMemory   bigint        NOT NULL,
    processCpuLoad           numeric(3, 2) NOT NULL,
    deleteSucceededJobsAfter varchar(32),
    permanentlyDeleteJobsAfter varchar(32),
    name                     varchar(128)
);

CREATE INDEX jobrunr_bgjobsrvrs_fsthb_idx ON jobrunr_backgroundjobservers (firstHeartbeat);
CREATE INDEX jobrunr_bgjobsrvrs_lsthb_idx ON jobrunr_backgroundjobservers (lastHeartbeat);

-- ── Metadata (v009) ───────────────────────────────────────────────────────────
CREATE TABLE jobrunr_metadata
(
    id        varchar(156) PRIMARY KEY,
    name      varchar(92)  NOT NULL,
    owner     varchar(64)  NOT NULL,
    value     text         NOT NULL,
    createdAt timestamp    NOT NULL,
    updatedAt timestamp    NOT NULL
);

INSERT INTO jobrunr_metadata (id, name, owner, value, createdAt, updatedAt)
VALUES ('succeeded-jobs-counter-cluster', 'succeeded-jobs-counter', 'cluster',
        '0', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ── Job stats view (Postgres-specific v014) ───────────────────────────────────
CREATE VIEW jobrunr_jobs_stats AS
WITH job_stat_results AS (
    SELECT state, count(*) AS count
    FROM jobrunr_jobs
    GROUP BY ROLLUP (state)
)
SELECT
    coalesce((SELECT count FROM job_stat_results WHERE state IS NULL),          0) AS total,
    coalesce((SELECT count FROM job_stat_results WHERE state = 'SCHEDULED'),    0) AS scheduled,
    coalesce((SELECT count FROM job_stat_results WHERE state = 'ENQUEUED'),     0) AS enqueued,
    coalesce((SELECT count FROM job_stat_results WHERE state = 'PROCESSING'),   0) AS processing,
    coalesce((SELECT count FROM job_stat_results WHERE state = 'FAILED'),       0) AS failed,
    coalesce((SELECT count FROM job_stat_results WHERE state = 'SUCCEEDED'),    0) AS succeeded,
    coalesce((SELECT cast(cast(value AS char(10)) AS decimal(10, 0))
              FROM jobrunr_metadata jm
              WHERE jm.id = 'succeeded-jobs-counter-cluster'),                  0) AS allTimeSucceeded,
    coalesce((SELECT count FROM job_stat_results WHERE state = 'DELETED'),      0) AS deleted,
    (SELECT count(*) FROM jobrunr_backgroundjobservers)                            AS nbrOfBackgroundJobServers,
    (SELECT count(*) FROM jobrunr_recurring_jobs)                                  AS nbrOfRecurringJobs;

-- ── Grants (belt-and-suspenders; ALTER DEFAULT PRIVILEGES in V1 also covers these) ──
GRANT SELECT, INSERT, UPDATE, DELETE ON jobrunr_jobs                TO app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON jobrunr_recurring_jobs      TO app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON jobrunr_backgroundjobservers TO app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON jobrunr_metadata            TO app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON jobrunr_migrations          TO app_user;
GRANT SELECT                         ON jobrunr_jobs_stats          TO app_user;

-- ── Pre-populate migration tracker so JobRunr sees all scripts as applied ─────
-- If skip-create is ever set to false, DatabaseCreator checks this table and
-- skips scripts that are already listed here.
INSERT INTO jobrunr_migrations (id, script, installedOn) VALUES
    (gen_random_uuid()::text, 'v000__create_migrations_table.sql',                           '2026-06-20T00:00:00.000000000'),
    (gen_random_uuid()::text, 'v001__create_job_table.sql',                                  '2026-06-20T00:00:00.000000000'),
    (gen_random_uuid()::text, 'v002__create_recurring_job_table.sql',                        '2026-06-20T00:00:00.000000000'),
    (gen_random_uuid()::text, 'v003__create_background_job_server_table.sql',                '2026-06-20T00:00:00.000000000'),
    (gen_random_uuid()::text, 'v004__create_job_stats_view.sql',                             '2026-06-20T00:00:00.000000000'),
    (gen_random_uuid()::text, 'v005__update_job_stats_view.sql',                             '2026-06-20T00:00:00.000000000'),
    (gen_random_uuid()::text, 'v006__alter_table_jobs_add_recurringjob.sql',                 '2026-06-20T00:00:00.000000000'),
    (gen_random_uuid()::text, 'v007__alter_table_backgroundjobserver_add_delete_config.sql', '2026-06-20T00:00:00.000000000'),
    (gen_random_uuid()::text, 'v008__alter_table_jobs_increase_jobAsJson_size.sql',          '2026-06-20T00:00:00.000000000'),
    (gen_random_uuid()::text, 'v009__change_jobrunr_job_counters_to_jobrunr_metadata.sql',   '2026-06-20T00:00:00.000000000'),
    (gen_random_uuid()::text, 'v010__change_job_stats.sql',                                  '2026-06-20T00:00:00.000000000'),
    (gen_random_uuid()::text, 'v011__change_sqlserver_text_to_varchar.sql',                  '2026-06-20T00:00:00.000000000'),
    (gen_random_uuid()::text, 'v012__change_oracle_alter_jobrunr_metadata_column_size.sql',  '2026-06-20T00:00:00.000000000'),
    (gen_random_uuid()::text, 'v013__alter_table_recurring_job_add_createdAt.sql',           '2026-06-20T00:00:00.000000000'),
    (gen_random_uuid()::text, 'v014__improve_job_stats.sql',                                 '2026-06-20T00:00:00.000000000'),
    (gen_random_uuid()::text, 'v015__alter_table_backgroundjobserver_add_name.sql',          '2026-06-20T00:00:00.000000000');
