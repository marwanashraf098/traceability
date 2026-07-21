-- V56: Per-variant label reprints — add variant_id to label_reprints.
-- NULL variant_id = whole-session reprint (existing rows unchanged, no backfill needed).
ALTER TABLE label_reprints ADD COLUMN variant_id uuid NULL REFERENCES variants(id);

-- Sparse index: only rows that carry a variant_id (variant-level reprints).
CREATE INDEX label_reprints_variant_idx ON label_reprints (variant_id)
    WHERE variant_id IS NOT NULL;
