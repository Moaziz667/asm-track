-- COD phase 0 — what the ERP says about collecting payment on arrival.
--
-- An earlier attempt (dropped in V8) put `is_cod` and `amount_to_collect` here and
-- `cod_collected` / `cod_amount_collected` on `deliveries`. That shape has no room for a partial
-- collection, a cheque, a reason for not collecting, or the handover of the cash at the depot — so it
-- was never wired to anything and was removed as dead weight. These two columns are the ERP's
-- instruction only; everything the driver and the cashier do lives in its own tables (V42, V43).

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS cod_required BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS cod_amount   NUMERIC(19,3);

-- Partial index: COD orders are the minority, and every COD query filters on this flag.
CREATE INDEX IF NOT EXISTS idx_orders_cod_required
    ON orders (cod_required) WHERE cod_required = TRUE;

COMMENT ON COLUMN orders.cod_required IS
    'ERP says the driver must collect payment on arrival. Default FALSE — with money the safe guess is "collect nothing"; switching it on is a mapping decision.';
COMMENT ON COLUMN orders.cod_amount IS
    'Amount to collect, frozen at import. Compared against what the driver reports; a value that moved afterwards would make reconciliation meaningless.';
