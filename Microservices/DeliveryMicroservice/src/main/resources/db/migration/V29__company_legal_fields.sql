-- Legal émetteur fields for the ASM-native delivery note (bon de livraison).
-- Tunisia: matricule fiscal (tax_id) + registre de commerce (registration_number).
ALTER TABLE companies ADD COLUMN IF NOT EXISTS city                VARCHAR(120);
ALTER TABLE companies ADD COLUMN IF NOT EXISTS phone               VARCHAR(40);
ALTER TABLE companies ADD COLUMN IF NOT EXISTS tax_id              VARCHAR(50);
ALTER TABLE companies ADD COLUMN IF NOT EXISTS registration_number VARCHAR(50);
