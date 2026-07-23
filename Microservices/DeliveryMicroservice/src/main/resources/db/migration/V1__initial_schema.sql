-- =============================================================================
-- ASM TRACK - CONSOLIDATED DATABASE SCHEMA (SINGLE-TENANT INITIAL MIGRATION)
-- =============================================================================
-- Enable pgcrypto for UUID generation if needed (built-in in PG 13+)
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
-- 1. COMPANIES (Branding Information)
CREATE TABLE companies (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(255) NOT NULL,
    logo_url      VARCHAR(512),
    address       VARCHAR(512),
    primary_color VARCHAR(7)   DEFAULT '#FF5722',
    support_email VARCHAR(255) DEFAULT 'support@asm.tn',
    active        BOOLEAN      DEFAULT true,
    created_at    TIMESTAMP    DEFAULT NOW()
);
-- No default company seed: multi-tenant. Each tenant schema is provisioned empty and its
-- company branding row is seeded explicitly at onboarding (companyId = Keycloak org_id).
-- 2. DEPOTS
CREATE TABLE depots (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(150) NOT NULL,
    address    TEXT,
    latitude   DOUBLE PRECISION NOT NULL,
    longitude  DOUBLE PRECISION NOT NULL,
    is_active  BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_depots_is_active ON depots(is_active);
-- 3. ZONES
CREATE TABLE zones (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(150) NOT NULL,
    color        VARCHAR(7),
    description  TEXT,
    cities       JSONB NOT NULL DEFAULT '[]',
    postal_codes JSONB NOT NULL DEFAULT '[]',
    is_active    BOOLEAN NOT NULL DEFAULT true,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    geometry     TEXT
);
CREATE INDEX idx_zones_is_active ON zones(is_active);
-- 4. ORDERS
CREATE TABLE orders (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source                VARCHAR(10) NOT NULL CHECK (source IN ('APP', 'ODOO')),
    schema_version        VARCHAR(10) NOT NULL DEFAULT '1.0.0',
    client_id             VARCHAR(100),
    client_name           VARCHAR(100) NOT NULL,
    client_phone          VARCHAR(20),
    client_email          VARCHAR(100),
    erp_order_id          VARCHAR(100) UNIQUE,
    erp_external_ref      VARCHAR(100),
    origin_name           VARCHAR(100),
    origin_address        TEXT,
    origin_city           VARCHAR(100),
    origin_postal_code    VARCHAR(20),
    origin_country_code   VARCHAR(2),
    origin_contact_name   VARCHAR(100),
    origin_contact_phone  VARCHAR(20),
    origin_contact_email  VARCHAR(100),
    dropoff_address       TEXT NOT NULL,
    dropoff_city          VARCHAR(100),
    dropoff_postal_code   VARCHAR(20),
    dropoff_country_code  VARCHAR(2) DEFAULT 'TN',
    dropoff_lat           NUMERIC(10,7),
    dropoff_lng           NUMERIC(10,7),
    delivery_instructions TEXT,
    total_amount          NUMERIC(10,3) NOT NULL,
    currency              VARCHAR(3) NOT NULL DEFAULT 'TND',
    payment_type          VARCHAR(10),
    amount_to_collect     NUMERIC(10,3) NOT NULL DEFAULT 0,
    is_cod                BOOLEAN NOT NULL DEFAULT FALSE,
    scheduled_at          TIMESTAMP,
    priority              VARCHAR(10) NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('NORMAL', 'HIGH')),
    items                 JSONB NOT NULL,
    total_quantity        INTEGER NOT NULL DEFAULT 0,
    total_weight_kg       NUMERIC(10,3) NOT NULL DEFAULT 0,
    status                VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DELIVERED', 'PARTIALLY_DELIVERED', 'CANCELLED')),
    last_synced_at        TIMESTAMP,
    created_at            TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP NOT NULL DEFAULT NOW(),
    odoo_sync_status      VARCHAR(40) DEFAULT 'SYNCED',
    odoo_backorder_id     INTEGER,
    sync_retry_count      INTEGER NOT NULL DEFAULT 0,
    next_sync_retry_at    TIMESTAMP,
    erp_client_id         VARCHAR(100),
    zone_id               UUID REFERENCES zones(id) ON DELETE SET NULL,
    parent_order_id       UUID REFERENCES orders(id)
);
CREATE INDEX idx_orders_client_id   ON orders(client_id);
CREATE INDEX idx_orders_status       ON orders(status);
CREATE INDEX idx_orders_source       ON orders(source);
CREATE INDEX idx_orders_erp_order_id ON orders(erp_order_id);
CREATE INDEX idx_orders_created_at   ON orders(created_at DESC);
-- 5. DELIVERIES
CREATE TABLE deliveries (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id               UUID NOT NULL UNIQUE REFERENCES orders(id) ON DELETE CASCADE,
    driver_id              UUID,
    status                 VARCHAR(20) NOT NULL DEFAULT 'UNSCHEDULED' CHECK (status IN (
        'UNSCHEDULED', 'SCHEDULED', 'PICKED_UP', 'IN_TRANSIT', 'DELIVERED', 'PARTIALLY_DELIVERED', 'FAILED', 'CANCELLED'
    )),
    assigned_at            TIMESTAMP,
    picked_up_at           TIMESTAMP,
    in_transit_at          TIMESTAMP,
    route_geometry         TEXT,
    route_distance_km      NUMERIC(10,3),
    route_duration_minutes INTEGER,
    route_eta_at           TIMESTAMP,
    transit_sla_minutes_computed INTEGER,
    route_last_computed_at TIMESTAMP,
    route_provider         VARCHAR(20),
    completed_at           TIMESTAMP,
    failed_at              TIMESTAMP,
    cancelled_at           TIMESTAMP,
    fail_reason            TEXT,
    cancel_reason          TEXT,
    cancelled_by           VARCHAR(10) CHECK (cancelled_by IN ('CLIENT', 'DRIVER', 'SYSTEM', 'ADMIN')),
    created_at             TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMP NOT NULL DEFAULT NOW(),
    failure_code           VARCHAR(30),
    route_stop_id          UUID,
    waiting_sla_minutes    INTEGER,
    assign_sla_minutes     INTEGER,
    pickup_sla_minutes     INTEGER,
    return_to_origin       BOOLEAN NOT NULL DEFAULT FALSE,
    cod_collected          BOOLEAN DEFAULT NULL,
    cod_amount_collected   NUMERIC(10,3) DEFAULT NULL,
    version                BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_deliveries_driver_id ON deliveries(driver_id);
CREATE INDEX idx_deliveries_status    ON deliveries(status);
-- 6. DELIVERY STATUS HISTORY
CREATE TABLE delivery_status_history (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id     UUID NOT NULL REFERENCES deliveries(id) ON DELETE CASCADE,
    status          VARCHAR(20) NOT NULL CHECK (status IN (
        'UNSCHEDULED', 'SCHEDULED', 'PICKED_UP', 'IN_TRANSIT', 'DELIVERED', 'PARTIALLY_DELIVERED', 'FAILED', 'CANCELLED'
    )),
    changed_by      VARCHAR(100),
    changed_by_role VARCHAR(10) CHECK (changed_by_role IN ('CLIENT', 'DRIVER', 'DISPATCHER', 'MANAGER', 'ADMIN', 'SYSTEM')),
    event_key       VARCHAR(50) NOT NULL,
    event_params    JSONB NOT NULL DEFAULT '{}'::jsonb,
    changed_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    note            TEXT
);
CREATE INDEX idx_history_delivery_id ON delivery_status_history(delivery_id);
-- 7. VEHICLES
CREATE TABLE vehicles (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name             VARCHAR(100) NOT NULL,
    make             VARCHAR(60) NOT NULL,
    model            VARCHAR(60) NOT NULL,
    manufacture_year INTEGER,
    color            VARCHAR(40),
    vin              VARCHAR(40),
    fuel_type        VARCHAR(30),
    payload_kg       INTEGER,
    volume_m3        DOUBLE PRECISION,
    mileage_km       INTEGER,
    image_url        VARCHAR(500),
    plate            VARCHAR(40) NOT NULL UNIQUE,
    type             VARCHAR(20) NOT NULL CHECK (type IN ('TRUCK', 'VAN', 'CAR', 'MOTO')),
    driver_id        UUID,
    active           BOOLEAN NOT NULL DEFAULT true,
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    vehicle_status   VARCHAR(30) NOT NULL DEFAULT 'AVAILABLE'
);
CREATE INDEX idx_vehicles_driver_id ON vehicles(driver_id);
-- 8. ROUTES (Tournees)
CREATE TABLE routes (
    id                            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                          VARCHAR(150) NOT NULL,
    driver_id                     UUID NOT NULL,
    vehicle_id                    UUID REFERENCES vehicles(id) ON DELETE SET NULL,
    version                       INTEGER DEFAULT 0,
    date                          DATE NOT NULL,
    planned_start_time            TIME NOT NULL DEFAULT TIME '08:00',
    planned_end_time              TIME NOT NULL DEFAULT TIME '18:00',
    city                          VARCHAR(100),
    status                        VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'VALIDATED', 'IN_PROGRESS', 'CLOSED', 'CANCELLED')),
    created_by                    VARCHAR(100) NOT NULL,
    created_at                    TIMESTAMP NOT NULL DEFAULT NOW(),
    validated_at                  TIMESTAMP,
    closed_at                     TIMESTAMP,
    updated_at                    TIMESTAMP NOT NULL DEFAULT NOW(),
    parent_route_id               UUID,
    route_version                 INTEGER NOT NULL DEFAULT 1,
    depot_id                      UUID REFERENCES depots(id) ON DELETE SET NULL,
    departure_time                TIMESTAMP,
    total_duration_seconds        INTEGER,
    total_distance_meters         INTEGER,
    is_optimized                  BOOLEAN NOT NULL DEFAULT false,
    route_geometry                TEXT,
    started_at                    TIMESTAMP,
    cumulative_delay_minutes      INTEGER,
    route_on_time_completion_rate NUMERIC(5,2),
    zone_id                       UUID REFERENCES zones(id) ON DELETE SET NULL,
    locked                        BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX idx_routes_driver_date ON routes(driver_id, date);
CREATE INDEX idx_routes_status ON routes(status);
-- 9. ROUTE STOPS
CREATE TABLE route_stops (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    route_id                 UUID NOT NULL REFERENCES routes(id) ON DELETE CASCADE,
    delivery_id              UUID NOT NULL UNIQUE REFERENCES deliveries(id) ON DELETE CASCADE,
    stop_order               INTEGER NOT NULL,
    status                   VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN (
        'PENDING', 'SCHEDULED', 'PICKED_UP', 'IN_TRANSIT', 'ARRIVED', 'COMPLETED', 'FAILED', 'PARTIAL',
        'FAILED_ATTEMPT', 'REMOVED_REPLANNED', 'REMOVED_CANCELLED'
    )),
    arrived_at               TIMESTAMP,
    completed_at             TIMESTAMP,
    notes                    TEXT,
    start_time_window        TIME,
    end_time_window          TIME,
    buffer_minutes           INTEGER NOT NULL DEFAULT 30,
    created_at               TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMP NOT NULL DEFAULT NOW(),
    eta_at                   TIMESTAMP,
    sla_deadline             TIMESTAMP,
    actual_arrival_at        TIMESTAMP,
    sla_status               VARCHAR(20) CHECK (sla_status IN ('ON_TIME', 'EARLY', 'LATE')),
    drive_duration_seconds   INTEGER,
    drive_distance_meters    INTEGER,
    dwell_minutes            INTEGER NOT NULL DEFAULT 10,
    removed_at               TIMESTAMP,
    removed_reason           TEXT,
    removed_by               VARCHAR(100),
    route_geometry           TEXT,
    actual_dwell_minutes     INTEGER,
    completion_status        VARCHAR(10),
    requires_handoff         BOOLEAN NOT NULL DEFAULT FALSE,
    handoff_from_driver_id   UUID,
    handoff_to_driver_id     UUID,
    handoff_confirmed_at     TIMESTAMP,
    handoff_token            VARCHAR(100),
    handoff_token_expires_at TIMESTAMP
);
CREATE INDEX idx_route_stops_route_id_order ON route_stops(route_id, stop_order);
CREATE INDEX idx_route_stops_eta ON route_stops(eta_at) WHERE eta_at IS NOT NULL;
-- 10. PROOF OF DELIVERY (POD)
CREATE TABLE proof_of_delivery (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id             UUID NOT NULL UNIQUE REFERENCES deliveries(id) ON DELETE CASCADE,
    comment                 TEXT,
    collected_at            TIMESTAMP NOT NULL DEFAULT NOW(),
    lat                     NUMERIC(10,7),
    lng                     NUMERIC(10,7),
    signature_url           VARCHAR(500),
    photo_url               VARCHAR(500),
    bon_livraison_photo_url VARCHAR(500)
);
CREATE INDEX idx_pod_delivery_id ON proof_of_delivery(delivery_id);
-- 11. ROUTE ALERTS
CREATE TABLE route_alerts (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    route_id     UUID NOT NULL REFERENCES routes(id) ON DELETE CASCADE,
    stop_id      UUID REFERENCES route_stops(id) ON DELETE SET NULL,
    stop_order   INTEGER,
    alert_type   VARCHAR(20) NOT NULL CHECK (alert_type IN ('APPROACHING', 'AT_RISK', 'BREACHED')),
    message      TEXT NOT NULL,
    acknowledged BOOLEAN NOT NULL DEFAULT false,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_route_alerts_route_id ON route_alerts(route_id);
CREATE INDEX idx_route_alerts_unacked  ON route_alerts(route_id, acknowledged) WHERE acknowledged = false;
-- 12. TIME SLOTS
CREATE TABLE time_slots (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    zone_id        UUID REFERENCES zones(id) ON DELETE SET NULL,
    slot_date      DATE NOT NULL,
    start_time     TIME NOT NULL,
    end_time       TIME NOT NULL,
    max_deliveries INTEGER,
    current_count  INTEGER NOT NULL DEFAULT 0,
    status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CLOSED', 'CANCELLED')),
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_time_slots_date_zone ON time_slots(slot_date, zone_id);
CREATE INDEX idx_time_slots_status ON time_slots(status);
-- 13. SLOT ASSIGNMENTS
CREATE TABLE slot_assignments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id     UUID NOT NULL UNIQUE REFERENCES deliveries(id) ON DELETE CASCADE,
    slot_id         UUID NOT NULL REFERENCES time_slots(id) ON DELETE CASCADE,
    assigned_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    assigned_by     VARCHAR(100),
    status          VARCHAR(20) NOT NULL DEFAULT 'ASSIGNED' CHECK (status IN ('ASSIGNED', 'FULFILLED', 'VIOLATED', 'CANCELLED')),
    compliance_note TEXT,
    override_used   BOOLEAN NOT NULL DEFAULT false,
    override_reason TEXT,
    override_by     VARCHAR(100),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_slot_assignments_slot_id ON slot_assignments(slot_id);
CREATE INDEX idx_slot_assignments_status ON slot_assignments(status);
-- 14. SYSTEM SETTINGS
CREATE TABLE system_settings (
    setting_key   VARCHAR(191) PRIMARY KEY,
    setting_value TEXT NOT NULL,
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW()
);
-- 15. AUDIT LOGS
CREATE TABLE audit_logs (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_name    VARCHAR(255) NOT NULL,
    actor_role    VARCHAR(255) NOT NULL,
    action        VARCHAR(255) NOT NULL,
    target_entity VARCHAR(50),
    resource_id   TEXT,
    details       TEXT,
    ip_address    VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
-- 16. VEHICLE INSPECTIONS
CREATE TABLE vehicle_inspections (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    vehicle_id        UUID NOT NULL REFERENCES vehicles(id) ON DELETE CASCADE,
    driver_id         UUID NOT NULL,
    odometer_reading  INTEGER,
    fuel_level        INTEGER,
    tires_status      VARCHAR(20),
    brakes_status     VARCHAR(20),
    lights_status     VARCHAR(20),
    notes             VARCHAR(500),
    inspected_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_vehicle_inspections_vehicle_id ON vehicle_inspections(vehicle_id);
CREATE INDEX idx_vehicle_inspections_driver_id  ON vehicle_inspections(driver_id);
-- 17. PROCESSED REQUESTS (Idempotency)
CREATE TABLE processed_requests (
    idempotency_key VARCHAR(100) PRIMARY KEY,
    response_status INTEGER NOT NULL,
    response_body   TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_processed_requests_created_at ON processed_requests(created_at);
-- 18. OUTBOX EVENTS
CREATE TABLE outbox_event (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type    VARCHAR(50) NOT NULL,
    payload       TEXT NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count   INT NOT NULL DEFAULT 0,
    last_error    TEXT,
    created_at    TIMESTAMP NOT NULL,
    processed_at  TIMESTAMP,
    next_retry_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_outbox_status_next_retry ON outbox_event(status, next_retry_at);
-- 19. ROUTE REPORTS
CREATE TABLE route_report (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    route_id     UUID NOT NULL UNIQUE REFERENCES routes(id) ON DELETE CASCADE,
    payload      JSONB NOT NULL,
    generated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
-- 20. TRACKING
CREATE TABLE tracking (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id UUID NOT NULL REFERENCES deliveries(id) ON DELETE CASCADE,
    lat         NUMERIC(10,7) NOT NULL,
    lng         NUMERIC(10,7) NOT NULL,
    timestamp   TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_tracking_delivery_id ON tracking(delivery_id);
CREATE INDEX idx_tracking_timestamp   ON tracking(timestamp DESC);
-- 21. DELIVERY REPORTS
CREATE TABLE delivery_reports (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id UUID REFERENCES deliveries(id) ON DELETE SET NULL,
    driver_id   UUID NOT NULL,
    report_type VARCHAR(30) NOT NULL CHECK (report_type IN (
        'ADDRESS_NOT_FOUND',
        'CUSTOMER_UNREACHABLE',
        'CUSTOMER_REFUSED',
        'DAMAGED_PACKAGE',
        'PAYMENT_ISSUE',
        'OTHER'
    )),
    description TEXT,
    lat         DOUBLE PRECISION,
    lng         DOUBLE PRECISION,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_delivery_reports_delivery_id ON delivery_reports(delivery_id);
-- 22. DELIVERY REPORT PHOTOS (Collection table for DeliveryReport photoUrls)
CREATE TABLE delivery_report_photos (
    report_id UUID NOT NULL REFERENCES delivery_reports(id) ON DELETE CASCADE,
    photo_url VARCHAR(512) NOT NULL
);
