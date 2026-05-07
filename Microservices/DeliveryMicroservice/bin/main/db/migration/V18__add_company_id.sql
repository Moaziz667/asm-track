ALTER TABLE orders     ADD COLUMN company_id UUID REFERENCES companies(id);
ALTER TABLE routes     ADD COLUMN company_id UUID REFERENCES companies(id);
ALTER TABLE deliveries ADD COLUMN company_id UUID REFERENCES companies(id);
ALTER TABLE vehicles   ADD COLUMN company_id UUID REFERENCES companies(id);
ALTER TABLE depots     ADD COLUMN company_id UUID REFERENCES companies(id);
ALTER TABLE zones      ADD COLUMN company_id UUID REFERENCES companies(id);

UPDATE orders     SET company_id = '00000000-0000-0000-0000-000000000001';
UPDATE routes     SET company_id = '00000000-0000-0000-0000-000000000001';
UPDATE deliveries SET company_id = '00000000-0000-0000-0000-000000000001';
UPDATE vehicles   SET company_id = '00000000-0000-0000-0000-000000000001';
UPDATE depots     SET company_id = '00000000-0000-0000-0000-000000000001';
UPDATE zones      SET company_id = '00000000-0000-0000-0000-000000000001';

ALTER TABLE orders     ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE routes     ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE deliveries ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE vehicles   ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE depots     ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE zones      ALTER COLUMN company_id SET NOT NULL;

CREATE INDEX idx_orders_company     ON orders(company_id);
CREATE INDEX idx_routes_company     ON routes(company_id);
CREATE INDEX idx_deliveries_company ON deliveries(company_id);
CREATE INDEX idx_vehicles_company   ON vehicles(company_id);
CREATE INDEX idx_depots_company     ON depots(company_id);
CREATE INDEX idx_zones_company      ON zones(company_id);
