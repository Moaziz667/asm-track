-- COD tracking
-- is_cod: derived at import time from Odoo payment_term_id ("Immediate Payment" = true)
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS is_cod BOOLEAN NOT NULL DEFAULT FALSE;

-- cod_collected: NULL = not applicable (non-COD), TRUE = driver collected, FALSE = driver did not collect
-- cod_amount_collected: actual amount the driver collected at the door
ALTER TABLE deliveries
    ADD COLUMN IF NOT EXISTS cod_collected         BOOLEAN       DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS cod_amount_collected  NUMERIC(10,3) DEFAULT NULL;
