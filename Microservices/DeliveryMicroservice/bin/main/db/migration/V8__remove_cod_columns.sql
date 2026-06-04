ALTER TABLE orders      DROP COLUMN IF EXISTS is_cod;
ALTER TABLE orders      DROP COLUMN IF EXISTS amount_to_collect;
ALTER TABLE deliveries  DROP COLUMN IF EXISTS cod_collected;
ALTER TABLE deliveries  DROP COLUMN IF EXISTS cod_amount_collected;
