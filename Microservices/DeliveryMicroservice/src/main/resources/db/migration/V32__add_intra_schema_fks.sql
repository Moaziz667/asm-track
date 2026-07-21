-- Add referential integrity to the high-value intra-schema relations that were modelled as raw UUIDs
-- with no FK (orphan rows were possible; integrity was only enforced in code). Cross-service refs
-- (driver_id → driver-service, etc.) are intentionally left as raw UUIDs — no FK is possible across
-- service boundaries. An orphan audit across all live tenant schemas returned 0, so these can be added
-- as validated constraints.
--
-- Child "detail" rows owned by a delivery cascade on delete; reference-style links use the default
-- NO ACTION (you cannot delete a row that is still referenced). Guarded + existence-checked so the
-- migration is safe to replay across every tenant schema (Flyway runs it per schema).
DO $$
DECLARE
    s text := current_schema();
    -- child_table, child_col, parent_table, on_delete
    fks text[][] := ARRAY[
        ['proof_of_delivery',       'delivery_id', 'deliveries', 'CASCADE'],
        ['tracking',                'delivery_id', 'deliveries', 'CASCADE'],
        ['delivery_status_history', 'delivery_id', 'deliveries', 'CASCADE'],
        ['handoffs',                'delivery_id', 'deliveries', 'CASCADE'],
        ['route_stops',             'delivery_id', 'deliveries', 'NO ACTION'],
        ['rma',                     'order_id',    'orders',     'NO ACTION'],
        ['rma',                     'delivery_id', 'deliveries', 'NO ACTION'],
        ['routes',                  'vehicle_id',  'vehicles',   'NO ACTION'],
        ['routes',                  'depot_id',    'depots',     'NO ACTION'],
        ['deliveries',              'source_depot_id', 'depots', 'NO ACTION']
    ];
    i int;
    cname text;
BEGIN
    FOR i IN 1 .. array_length(fks, 1) LOOP
        -- skip if child or parent table (or the FK column) is absent in this schema
        CONTINUE WHEN NOT EXISTS (SELECT 1 FROM information_schema.tables
                                  WHERE table_schema = s AND table_name = fks[i][1]);
        CONTINUE WHEN NOT EXISTS (SELECT 1 FROM information_schema.tables
                                  WHERE table_schema = s AND table_name = fks[i][3]);
        CONTINUE WHEN NOT EXISTS (SELECT 1 FROM information_schema.columns
                                  WHERE table_schema = s AND table_name = fks[i][1] AND column_name = fks[i][2]);

        cname := 'fk_' || fks[i][1] || '_' || fks[i][2];
        -- skip if this FK already exists
        CONTINUE WHEN EXISTS (SELECT 1 FROM pg_constraint c
                              JOIN pg_class t   ON t.oid = c.conrelid
                              JOIN pg_namespace n ON n.oid = t.relnamespace
                              WHERE n.nspname = s AND c.contype = 'f' AND c.conname = cname);

        EXECUTE format('ALTER TABLE %I.%I ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES %I.%I(id) ON DELETE %s',
                       s, fks[i][1], cname, fks[i][2], s, fks[i][3], fks[i][4]);
    END LOOP;
END $$;
