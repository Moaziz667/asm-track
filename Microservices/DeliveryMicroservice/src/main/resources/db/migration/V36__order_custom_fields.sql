-- ERP values an integrator mapped that ASM has no field of its own for.
--
-- Every customer keeps something in their ERP that this product has no concept of — an internal
-- reference, a commercial zone, a priority code. Until now such a field could be mapped but had
-- nowhere to land, so the value was read and then dropped. This is the bag it lands in, keyed by the
-- label the integrator chose.
--
-- Nullable and display-only by design: ASM cannot sort or filter on what it does not understand, and
-- a field that needs to drive behaviour should become a real column instead of living here.
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS custom_fields JSONB;
