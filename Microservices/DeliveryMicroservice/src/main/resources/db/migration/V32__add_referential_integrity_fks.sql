-- Referential integrity for the delivery lifecycle: these child tables reference a delivery/order by
-- raw UUID with no foreign key, so a bad write (or a bug) could leave an orphan row pointing at a
-- delivery/order that doesn't exist. Add FKs so the database guarantees the link.
--
-- Scope kept deliberately safe:
--   * only child -> deliveries/orders links are added. Deliveries and orders are NEVER hard-deleted
--     (verified in code), so the default ON DELETE NO ACTION can never block an existing flow — it just
--     forbids ever orphaning these records.
--   * cross-service refs (driver_id) are intentionally left FK-less (drivers live in driver-service).
--   * routes.vehicle_id / *.depot_id are deferred: vehicles/depots DO have delete flows, so those need
--     ON DELETE SET NULL + a flow review — a separate migration.
--
-- Orphan audit run against every live tenant schema + public before writing this: 0 orphans, so the
-- constraints validate cleanly. Guarded ADD CONSTRAINT so a replay on an already-migrated schema is a
-- no-op (PostgreSQL has no ADD CONSTRAINT IF NOT EXISTS). Flyway runs this per tenant schema with the
-- search_path set, so unqualified names resolve to the current schema.

DO $$
DECLARE
    -- child_table, fk_column, constraint_name, parent_table
    fks text[][] := ARRAY[
        ['route_stops',             'delivery_id', 'fk_route_stops_delivery',   'deliveries'],
        ['rma',                     'order_id',    'fk_rma_order',              'orders'],
        ['rma',                     'delivery_id', 'fk_rma_delivery',           'deliveries'],
        ['proof_of_delivery',       'delivery_id', 'fk_pod_delivery',           'deliveries'],
        ['tracking',                'delivery_id', 'fk_tracking_delivery',      'deliveries'],
        ['delivery_status_history', 'delivery_id', 'fk_dsh_delivery',           'deliveries'],
        ['handoffs',                'delivery_id', 'fk_handoffs_delivery',      'deliveries']
    ];
    i int;
BEGIN
    FOR i IN 1 .. array_length(fks, 1) LOOP
        -- Skip if the tables aren't present in this schema (defensive).
        CONTINUE WHEN NOT EXISTS (SELECT 1 FROM information_schema.tables
                                  WHERE table_schema = current_schema() AND table_name = fks[i][1]);
        CONTINUE WHEN NOT EXISTS (SELECT 1 FROM information_schema.tables
                                  WHERE table_schema = current_schema() AND table_name = fks[i][4]);
        -- Add the FK only if a constraint of that name doesn't already exist in this schema.
        IF NOT EXISTS (SELECT 1 FROM pg_constraint
                       WHERE conname = fks[i][3] AND connamespace = current_schema()::regnamespace) THEN
            EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES %I(id)',
                           fks[i][1], fks[i][3], fks[i][2], fks[i][4]);
        END IF;
    END LOOP;
END $$;
