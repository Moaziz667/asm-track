-- Vehicles are ASM-owned assets, not per-company
ALTER TABLE vehicles DROP COLUMN IF EXISTS company_id;
