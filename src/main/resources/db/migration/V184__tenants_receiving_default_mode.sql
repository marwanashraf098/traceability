-- Flexible labeling slice 8 — Settings → Labels: the receiving mode the "How do you want to receive this delivery?"
-- step pre-selects. Only a preference: every receiving session still records its own mode, and the switch
-- (flexible_labeling_enabled) stays admin-only. Constant default: metadata-only in Postgres 11+.
ALTER TABLE tenants ADD COLUMN receiving_default_mode text NOT NULL DEFAULT 'print_stick'
    CHECK (receiving_default_mode IN ('print_stick', 'count_only'));
