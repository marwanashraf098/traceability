-- V243 — Bazar U1b (approved 2026-11-24): a DRAFT bazar can be deleted while nothing was ever scanned out for it.
--   Soft delete: app_user has no DELETE on bazar_events (V231). deleted_at / deleted_by mark the row; its linked
--   transfer is cancelled in the same transaction (TransferService.cancelForBazar — the same "never scanned" check as
--   the generic cancel). Every bazar read leaves a deleted event out (BazarEvents.LIVE_ROW = deleted_at IS NULL): the
--   list, every per-event endpoint (404 BAZAR_EVENT_NOT_FOUND), the seller's live-event lookup, unsentBazarsAt.
--   RLS policy unchanged (NULLIF, FORCE — V231); app_user already has UPDATE on the table.

ALTER TABLE bazar_events
    ADD COLUMN deleted_at timestamptz,
    ADD COLUMN deleted_by uuid REFERENCES users(id);

ALTER TABLE bazar_events ADD CONSTRAINT bazar_events_deleted_draft_only
    CHECK (deleted_at IS NULL OR status = 'draft');
ALTER TABLE bazar_events ADD CONSTRAINT bazar_events_deleted_by_pair
    CHECK ((deleted_at IS NULL) = (deleted_by IS NULL));
