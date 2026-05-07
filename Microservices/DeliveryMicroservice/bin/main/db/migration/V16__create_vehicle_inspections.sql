-- ── Vehicle Inspections Migration ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS vehicle_inspections (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  vehicle_id        UUID NOT NULL REFERENCES vehicles(id),
  driver_id         UUID NOT NULL,
  odometer_reading  INTEGER,
  fuel_level        INTEGER,
  tires_status      VARCHAR(20),
  brakes_status     VARCHAR(20),
  lights_status     VARCHAR(20),
  notes             VARCHAR(500),
  inspected_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_vehicle_inspections_vehicle_id ON vehicle_inspections(vehicle_id);
CREATE INDEX IF NOT EXISTS idx_vehicle_inspections_driver_id  ON vehicle_inspections(driver_id);
