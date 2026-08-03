-- Widen the order totals, which could not hold a large ERP order.
--
-- Both were numeric(10,3), so anything from 10,000,000 up was rejected by Postgres. That ceiling is
-- reachable in normal business: a B2B distributor's order can exceed ten million in a low-denomination
-- currency, and a bulk shipment can exceed ten thousand tonnes. Importing such an order failed with
-- "numeric field overflow" surfacing as a 500 — the operator saw a server error, with nothing to
-- suggest the cause was the size of their own order or what to do about it.
--
-- Widening is safe in both directions: no existing value is affected, the scale is unchanged so
-- nothing is re-rounded, and Postgres rewrites nothing for a numeric precision increase.
ALTER TABLE orders
    ALTER COLUMN total_amount    TYPE NUMERIC(19,3),
    ALTER COLUMN total_weight_kg TYPE NUMERIC(19,3);
