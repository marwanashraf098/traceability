-- V199 — Place sticker sizes (2026-11-01): each tenant's last sticker size per place kind.
--
-- {"<kind>": {"size": "SMALL" | "A6" | "A4", "a6Mode": "single" | "sheet" | null}} for kind zone / rack / shelf / box,
-- written by PlacesService.stickers when a kind is printed (jsonb ||, so other kinds keep their choice). A kind with
-- no entry uses the default (shelf SMALL, box A6, rack A6, zone A4). Read and written only by PlacesService;
-- app_user already has UPDATE on tenants (RLS: own row).
ALTER TABLE tenants ADD COLUMN place_sticker_prefs jsonb NOT NULL DEFAULT '{}'::jsonb;
