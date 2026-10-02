-- V128 — Discovery poll retry list (2026-10-02).
--
-- BostaDiscoveryPollJob used to skip a delivery whose per-item fetch failed (429, IO error,
-- 5xx, "Delivery not found", anything unexpected) and still advance the high-water mark past
-- it, so the delivery was never looked at again. A failed tracking number now gets a row here;
-- every discovery cycle retries the open rows by tracking number (independent of the
-- newest-first list window) before walking the list.
--
--   attempts            counted failures (everything except a 429 — rate limiting is not the
--                       delivery's fault and stops the whole cycle instead)
--   rate_limited_count  429s seen for this tracking number (informational, never counts toward escalation)
--   escalated_at        set once attempts reaches bosta.poll.discovery-max-item-failures (10):
--                       raises the bosta_discovery_failed exception (ExceptionService), open until
--                       the delivery reaches Traced or the exception is resolved. From then on the
--                       row is retried only every bosta.poll.discovery-slow-retry-minutes (60).
--   next_retry_at       the earliest time an escalated row is retried again
--   retries_stopped_at  set once bosta.poll.discovery-retry-cap-hours (48) have passed since
--                       first_failed_at — no more retries; the exception stays open (a row that
--                       reaches the cap without escalating, e.g. only 429s, is escalated then too).
-- A successful ingest deletes the row (and so clears the exception).

CREATE TABLE bosta_discovery_failures (
    id                 bigserial    PRIMARY KEY,
    tenant_id          uuid         NOT NULL REFERENCES tenants(id),
    tracking_number    text         NOT NULL,
    attempts           int          NOT NULL DEFAULT 0,
    rate_limited_count int          NOT NULL DEFAULT 0,
    first_failed_at    timestamptz  NOT NULL DEFAULT now(),
    last_failed_at     timestamptz  NOT NULL DEFAULT now(),
    last_error         text,
    escalated_at       timestamptz,
    next_retry_at      timestamptz,
    retries_stopped_at timestamptz,
    UNIQUE (tenant_id, tracking_number)
);

CREATE INDEX bosta_discovery_failures_open
    ON bosta_discovery_failures (tenant_id, first_failed_at)
    WHERE retries_stopped_at IS NULL;

ALTER TABLE bosta_discovery_failures ENABLE ROW LEVEL SECURITY;
ALTER TABLE bosta_discovery_failures FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON bosta_discovery_failures
    USING  (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
