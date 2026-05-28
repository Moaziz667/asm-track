-- 1. Drop old delivery_status_history table and dependent checks
DROP TABLE IF EXISTS delivery_status_history CASCADE;

-- 2. Recreate delivery_status_history table cleanly with Event Key and JSONB Parameters (Bringg standard)
CREATE TABLE delivery_status_history (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  delivery_id     UUID NOT NULL REFERENCES deliveries(id) ON DELETE CASCADE,
  status          VARCHAR(20) NOT NULL CHECK (status IN (
    'UNSCHEDULED',
    'SCHEDULED',
    'PICKED_UP',
    'IN_TRANSIT',
    'DELIVERED',
    'PARTIALLY_DELIVERED',
    'FAILED',
    'CANCELLED'
  )),
  changed_by      VARCHAR(100),
  changed_by_role VARCHAR(10) CHECK (changed_by_role IN ('CLIENT', 'DRIVER', 'DISPATCHER', 'MANAGER', 'ADMIN', 'SYSTEM')),
  event_key       VARCHAR(50) NOT NULL,
  event_params    JSONB NOT NULL DEFAULT '{}'::jsonb,
  changed_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 3. Create index for fast dynamic query execution
CREATE INDEX idx_history_delivery_id ON delivery_status_history(delivery_id);
