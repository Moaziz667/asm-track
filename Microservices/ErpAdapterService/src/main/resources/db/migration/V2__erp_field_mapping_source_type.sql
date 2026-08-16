-- The ERP type of the field this mapping points at, as it was when the integrator chose it.
--
-- Kept so a later import can notice the field changed underneath. An Odoo `selection` turned into a
-- `char` six months after go-live still resolves — it just stops meaning what it meant, and the only
-- visible symptom used to be values quietly going blank. Comparing the type seen today against the
-- one recorded here turns that into something the system can report.
--
-- Nullable on purpose: rows written before this column existed have no observed type, and guessing
-- one would fabricate a baseline that drift detection would then trust.
ALTER TABLE erp_field_mapping
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(24);

COMMENT ON COLUMN erp_field_mapping.source_type IS
    'Normalised ERP type (SourceType) observed when the mapping was saved; used for drift detection.';
