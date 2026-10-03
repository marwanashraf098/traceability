-- V131 — Link Bosta deliveries from Shopify fulfillment tracking (2026-10-03).
--
-- 1. webhook_events.source gains 'shopify_fulfillment': events written ONLY by
--    BostaFulfillmentLinkService. BostaWebhookJob honours the payload's orderId hint (link this
--    delivery to that order) for this source only — never for a Bosta-facing source.
--
-- 2. order_fulfillment_tracking (V129) gains the link state of each Bosta tracking row:
--      link_status  NULL         never attempted
--                   'linked'     the order has a forward shipment with this tracking number
--                   'retry'      Bosta answered 404 / "Delivery not found", 429, 5xx or a network
--                                error; retried by the fulfillment-link-retry sweeper at
--                                link_next_retry_at, for up to 24 h after link_first_failed_at
--                   'gave_up'    still not found 24 h after the first failure → exception
--                   'conflict'   never linked, needs a person: the delivery's reference / Shopify
--                                id point at another order, or are both missing, or the type is
--                                not a forward delivery, or the tracking number is already a
--                                shipment on another order → exception (link_reason says which)
--                   'skipped'    no-op: the order already has a different active forward leg
--      link_reason, link_attempts, link_first_failed_at, link_next_retry_at, link_checked_at,
--      linked_at — bookkeeping for the above.

ALTER TYPE webhook_source ADD VALUE IF NOT EXISTS 'shopify_fulfillment';

ALTER TABLE order_fulfillment_tracking
    ADD COLUMN link_status          text
        CHECK (link_status IN ('linked', 'retry', 'gave_up', 'conflict', 'skipped')),
    ADD COLUMN link_reason          text,
    ADD COLUMN link_attempts        int NOT NULL DEFAULT 0,
    ADD COLUMN link_first_failed_at timestamptz,
    ADD COLUMN link_next_retry_at   timestamptz,
    ADD COLUMN link_checked_at      timestamptz,
    ADD COLUMN linked_at            timestamptz;

CREATE INDEX order_fulfillment_tracking_link_retry
    ON order_fulfillment_tracking (link_next_retry_at)
    WHERE link_status = 'retry';
