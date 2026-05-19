-- ============================================================
-- ASM Track — delivery_db schema
-- Database:   postgres-delivery (port 5434)
-- User:       delivery
-- Generated:  pg_dump --schema-only, PostgreSQL 16.13
-- ============================================================

SET statement_timeout = 0;
SET lock_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET row_security = off;
SET default_table_access_method = heap;

-- ------------------------------------------------------------
-- companies
-- ------------------------------------------------------------
CREATE TABLE public.companies (
    id             uuid DEFAULT gen_random_uuid() NOT NULL,
    name           character varying(255)         NOT NULL,
    logo_url       character varying(512),
    address        character varying(512),
    primary_color  character varying(7)           DEFAULT '#FF5722',
    erp_type       character varying(20)          DEFAULT 'NONE',
    erp_api_url    character varying(512),
    erp_api_key    character varying(512),
    erp_db_name    character varying(255),
    erp_username   character varying(255),
    erp_uid        integer,
    active         boolean                        DEFAULT true,
    support_email  character varying(255),
    created_at     timestamp                      DEFAULT now()
);

ALTER TABLE ONLY public.companies ADD CONSTRAINT companies_pkey PRIMARY KEY (id);

-- ------------------------------------------------------------
-- orders
-- ------------------------------------------------------------
CREATE TABLE public.orders (
    id                    uuid DEFAULT gen_random_uuid() NOT NULL,
    source                character varying(10)  NOT NULL,
    schema_version        character varying(10)  DEFAULT '1.0.0' NOT NULL,
    company_id            uuid                   NOT NULL,
    client_id             character varying(100),
    client_name           character varying(100) NOT NULL,
    client_phone          character varying(20),
    client_email          character varying(100),
    erp_order_id          character varying(100),
    erp_external_ref      character varying(100),
    -- Origin (warehouse / depot)
    origin_name           character varying(100),
    origin_address        text,
    origin_city           character varying(100),
    origin_postal_code    character varying(20),
    origin_country_code   character varying(2),
    origin_contact_name   character varying(100),
    origin_contact_phone  character varying(20),
    origin_contact_email  character varying(100),
    -- Dropoff (customer)
    dropoff_address       text                   NOT NULL,
    dropoff_city          character varying(100),
    dropoff_postal_code   character varying(20),
    dropoff_country_code  character varying(2)   DEFAULT 'TN',
    dropoff_lat           numeric(10,7),
    dropoff_lng           numeric(10,7),
    delivery_instructions text,
    -- Financial
    total_amount          numeric(10,3)          NOT NULL,
    currency              character varying(3)   DEFAULT 'TND' NOT NULL,
    payment_type          character varying(10),
    amount_to_collect     numeric(10,3)          DEFAULT 0 NOT NULL,
    is_cod                boolean                DEFAULT false NOT NULL,
    -- Scheduling
    scheduled_at          timestamp,
    priority              character varying(10)  DEFAULT 'NORMAL' NOT NULL,
    -- Items (JSONB)
    items                 jsonb                  NOT NULL,
    total_quantity        integer                DEFAULT 0 NOT NULL,
    total_weight_kg       numeric(10,3)          DEFAULT 0 NOT NULL,
    -- ERP sync
    odoo_sync_status      character varying(40)  DEFAULT 'SYNCED',
    odoo_backorder_id     integer,
    parent_order_id       uuid,
    sync_retry_count      integer                DEFAULT 0 NOT NULL,
    next_sync_retry_at    timestamp,
    -- Status & zone
    status                character varying(20)  DEFAULT 'PENDING' NOT NULL,
    zone_id               uuid,
    last_synced_at        timestamp,
    erp_client_id         character varying(100),
    client_odoo_partner_id integer,
    created_at            timestamp              DEFAULT now() NOT NULL,
    updated_at            timestamp              DEFAULT now() NOT NULL,
    CONSTRAINT ck_orders_status   CHECK (status   IN ('PENDING','DELIVERED','PARTIALLY_DELIVERED','CANCELLED')),
    CONSTRAINT ck_orders_priority CHECK (priority IN ('NORMAL','HIGH')),
    CONSTRAINT ck_orders_source   CHECK (source   IN ('APP','ODOO')),
    CONSTRAINT ck_orders_payment  CHECK (payment_type IN ('COD','PREPAID'))
);

ALTER TABLE ONLY public.orders ADD CONSTRAINT orders_pkey                        PRIMARY KEY (id);
ALTER TABLE ONLY public.orders ADD CONSTRAINT orders_erp_order_id_company_id_key UNIQUE (erp_order_id, company_id);

CREATE INDEX idx_orders_company    ON public.orders USING btree (company_id);
CREATE INDEX idx_orders_erp_order  ON public.orders USING btree (erp_order_id);
CREATE INDEX idx_orders_status     ON public.orders USING btree (status);
CREATE INDEX idx_orders_source     ON public.orders USING btree (source);
CREATE INDEX idx_orders_client_id  ON public.orders USING btree (client_id);
CREATE INDEX idx_orders_created_at ON public.orders USING btree (created_at DESC);

-- ------------------------------------------------------------
-- deliveries
-- ------------------------------------------------------------
CREATE TABLE public.deliveries (
    id                          uuid DEFAULT gen_random_uuid() NOT NULL,
    order_id                    uuid                   NOT NULL,
    company_id                  uuid                   NOT NULL,
    driver_id                   uuid,
    status                      character varying(20)  DEFAULT 'UNSCHEDULED' NOT NULL,
    -- Lifecycle timestamps
    assigned_at                 timestamp,
    picked_up_at                timestamp,
    in_transit_at               timestamp,
    completed_at                timestamp,
    failed_at                   timestamp,
    cancelled_at                timestamp,
    -- Failure / cancellation
    failure_code                character varying(30),
    fail_reason                 text,
    cancel_reason               text,
    cancelled_by                character varying(10),
    -- SLA timing (minutes elapsed between transitions)
    waiting_sla_minutes         integer,
    assign_sla_minutes          integer,
    pickup_sla_minutes          integer,
    transit_sla_minutes_computed integer,
    -- OSRM route geometry
    route_geometry              text,
    route_distance_km           numeric(10,3),
    route_duration_minutes      integer,
    route_eta_at                timestamp,
    route_last_computed_at      timestamp,
    route_provider              character varying(20),
    -- COD
    cod_collected               boolean,
    cod_amount_collected        numeric(10,3),
    return_to_origin            boolean                DEFAULT false NOT NULL,
    route_stop_id               uuid,
    -- Optimistic lock
    version                     bigint                 DEFAULT 0,
    created_at                  timestamp              DEFAULT now() NOT NULL,
    updated_at                  timestamp              DEFAULT now() NOT NULL,
    CONSTRAINT ck_deliveries_status       CHECK (status IN (
        'UNSCHEDULED','SCHEDULED','PICKED_UP','IN_TRANSIT',
        'DELIVERED','PARTIALLY_DELIVERED','FAILED','CANCELLED')),
    CONSTRAINT ck_deliveries_cancelled_by CHECK (cancelled_by IN ('CLIENT','DRIVER','SYSTEM','ADMIN'))
);

ALTER TABLE ONLY public.deliveries ADD CONSTRAINT deliveries_pkey         PRIMARY KEY (id);
ALTER TABLE ONLY public.deliveries ADD CONSTRAINT deliveries_order_id_key UNIQUE (order_id);

CREATE INDEX idx_deliveries_company   ON public.deliveries USING btree (company_id);
CREATE INDEX idx_deliveries_driver_id ON public.deliveries USING btree (driver_id);
CREATE INDEX idx_deliveries_status    ON public.deliveries USING btree (status);

-- ------------------------------------------------------------
-- delivery_status_history
-- ------------------------------------------------------------
CREATE TABLE public.delivery_status_history (
    id              uuid DEFAULT gen_random_uuid() NOT NULL,
    delivery_id     uuid                   NOT NULL,
    status          character varying(20)  NOT NULL,
    changed_by      character varying(100),
    changed_by_role character varying(10),
    note            text,
    changed_at      timestamp              DEFAULT now() NOT NULL,
    CONSTRAINT ck_history_role   CHECK (changed_by_role IN ('CLIENT','DRIVER','DISPATCHER','MANAGER','ADMIN','SYSTEM')),
    CONSTRAINT ck_history_status CHECK (status IN (
        'UNSCHEDULED','SCHEDULED','PICKED_UP','IN_TRANSIT',
        'DELIVERED','PARTIALLY_DELIVERED','FAILED','CANCELLED'))
);

ALTER TABLE ONLY public.delivery_status_history ADD CONSTRAINT delivery_status_history_pkey PRIMARY KEY (id);
CREATE INDEX idx_history_delivery_id ON public.delivery_status_history USING btree (delivery_id);

-- ------------------------------------------------------------
-- routes
-- ------------------------------------------------------------
CREATE TABLE public.routes (
    id                            uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id                    uuid                   NOT NULL,
    name                          character varying(150) NOT NULL,
    driver_id                     uuid                   NOT NULL,
    vehicle_id                    uuid,
    depot_id                      uuid,
    zone_id                       uuid,
    date                          date                   NOT NULL,
    status                        character varying(20)  DEFAULT 'DRAFT' NOT NULL,
    planned_start_time            time                   DEFAULT '08:00:00' NOT NULL,
    planned_end_time              time                   DEFAULT '18:00:00' NOT NULL,
    city                          character varying(100),
    -- Execution
    started_at                    timestamp,
    departure_time                timestamp,
    validated_at                  timestamp,
    closed_at                     timestamp,
    -- OSRM results
    total_duration_seconds        integer,
    total_distance_meters         integer,
    route_geometry                text,
    is_optimized                  boolean                DEFAULT false NOT NULL,
    -- KPIs
    cumulative_delay_minutes      integer,
    route_on_time_completion_rate numeric(5,2),
    -- Metadata
    route_version                 integer                DEFAULT 1 NOT NULL,
    parent_route_id               uuid,
    version                       integer                DEFAULT 0,
    locked                        boolean                DEFAULT false NOT NULL,
    created_by                    character varying(100) NOT NULL,
    created_at                    timestamp              DEFAULT now() NOT NULL,
    updated_at                    timestamp              DEFAULT now() NOT NULL,
    CONSTRAINT routes_status_check CHECK (status IN ('DRAFT','VALIDATED','IN_PROGRESS','CLOSED','CANCELLED'))
);

ALTER TABLE ONLY public.routes ADD CONSTRAINT routes_pkey PRIMARY KEY (id);
CREATE INDEX idx_routes_company     ON public.routes USING btree (company_id);
CREATE INDEX idx_routes_driver_date ON public.routes USING btree (driver_id, date);
CREATE INDEX idx_routes_status      ON public.routes USING btree (status);

-- ------------------------------------------------------------
-- route_stops
-- ------------------------------------------------------------
CREATE TABLE public.route_stops (
    id                      uuid DEFAULT gen_random_uuid() NOT NULL,
    route_id                uuid                   NOT NULL,
    delivery_id             uuid                   NOT NULL,
    stop_order              integer                NOT NULL,
    status                  character varying(20)  DEFAULT 'PENDING' NOT NULL,
    -- Time windows
    start_time_window       time,
    end_time_window         time,
    eta_at                  timestamp,
    sla_deadline            timestamp,
    -- Execution
    arrived_at              timestamp,
    actual_arrival_at       timestamp,
    completed_at            timestamp,
    actual_dwell_minutes    integer,
    dwell_minutes           integer                DEFAULT 10 NOT NULL,
    buffer_minutes          integer                DEFAULT 30,
    -- SLA outcome
    sla_status              character varying(20),
    completion_status       character varying(10),
    -- OSRM leg
    drive_duration_seconds  integer,
    drive_distance_meters   integer,
    route_geometry          text,
    -- Removal
    removed_at              timestamp,
    removed_reason          text,
    removed_by              character varying(100),
    -- QR handoff
    requires_handoff        boolean                DEFAULT false NOT NULL,
    handoff_from_driver_id  uuid,
    handoff_to_driver_id    uuid,
    handoff_confirmed_at    timestamp,
    handoff_token           character varying(100),
    handoff_token_expires_at timestamp,
    notes                   text,
    created_at              timestamp              DEFAULT now() NOT NULL,
    updated_at              timestamp              DEFAULT now() NOT NULL,
    CONSTRAINT ck_route_stops_status     CHECK (status IN (
        'PENDING','SCHEDULED','PICKED_UP','IN_TRANSIT','ARRIVED',
        'COMPLETED','FAILED','PARTIAL','FAILED_ATTEMPT',
        'REMOVED_REPLANNED','REMOVED_CANCELLED')),
    CONSTRAINT ck_route_stops_sla_status CHECK (sla_status IN ('ON_TIME','EARLY','LATE'))
);

ALTER TABLE ONLY public.route_stops ADD CONSTRAINT route_stops_pkey PRIMARY KEY (id);
CREATE INDEX idx_route_stops_route_id_order ON public.route_stops USING btree (route_id, stop_order);
CREATE INDEX idx_route_stops_eta            ON public.route_stops USING btree (eta_at) WHERE eta_at IS NOT NULL;

-- ------------------------------------------------------------
-- outbox_event
-- ------------------------------------------------------------
CREATE TABLE public.outbox_event (
    id           uuid         NOT NULL,
    event_type   character varying(50)  NOT NULL,
    payload      text         NOT NULL,
    status       character varying(20)  DEFAULT 'PENDING' NOT NULL,
    retry_count  integer      DEFAULT 0 NOT NULL,
    last_error   text,
    created_at   timestamp    NOT NULL,
    processed_at timestamp
);

ALTER TABLE ONLY public.outbox_event ADD CONSTRAINT outbox_event_pkey PRIMARY KEY (id);
CREATE INDEX idx_outbox_status_created ON public.outbox_event USING btree (status, created_at);

-- ------------------------------------------------------------
-- processed_requests  (idempotency layer)
-- ------------------------------------------------------------
CREATE TABLE public.processed_requests (
    idempotency_key character varying(100) NOT NULL,
    response_status integer                NOT NULL,
    response_body   text,
    created_at      timestamp              DEFAULT CURRENT_TIMESTAMP NOT NULL
);

ALTER TABLE ONLY public.processed_requests ADD CONSTRAINT processed_requests_pkey PRIMARY KEY (idempotency_key);
CREATE INDEX idx_processed_requests_created_at ON public.processed_requests USING btree (created_at);

-- ------------------------------------------------------------
-- proof_of_delivery
-- ------------------------------------------------------------
CREATE TABLE public.proof_of_delivery (
    id                       uuid DEFAULT gen_random_uuid() NOT NULL,
    delivery_id              uuid                   NOT NULL,
    bon_livraison_photo_url  character varying(500),
    photo_url                character varying(500),
    signature_url            character varying(500),
    comment                  text,
    lat                      numeric(10,7),
    lng                      numeric(10,7),
    collected_at             timestamp              DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.proof_of_delivery ADD CONSTRAINT proof_of_delivery_pkey         PRIMARY KEY (id);
ALTER TABLE ONLY public.proof_of_delivery ADD CONSTRAINT proof_of_delivery_delivery_id_key UNIQUE (delivery_id);
CREATE INDEX idx_pod_delivery_id ON public.proof_of_delivery USING btree (delivery_id);

-- ------------------------------------------------------------
-- route_report
-- ------------------------------------------------------------
CREATE TABLE public.route_report (
    id           uuid DEFAULT gen_random_uuid() NOT NULL,
    route_id     uuid  NOT NULL,
    company_id   uuid  NOT NULL,
    payload      jsonb NOT NULL,
    generated_at timestamp DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.route_report ADD CONSTRAINT route_report_pkey         PRIMARY KEY (id);
ALTER TABLE ONLY public.route_report ADD CONSTRAINT route_report_route_id_key UNIQUE (route_id);
CREATE INDEX idx_route_report_company ON public.route_report USING btree (company_id);

-- ------------------------------------------------------------
-- vehicles
-- ------------------------------------------------------------
CREATE TABLE public.vehicles (
    id              uuid DEFAULT gen_random_uuid() NOT NULL,
    name            character varying(100) NOT NULL,
    plate           character varying(40)  NOT NULL,
    type            character varying(20)  NOT NULL,
    driver_id       uuid,
    active          boolean                DEFAULT true NOT NULL,
    make            character varying(60),
    model           character varying(60),
    manufacture_year integer,
    color           character varying(40),
    vin             character varying(40),
    fuel_type       character varying(30),
    payload_kg      integer,
    volume_m3       double precision,
    mileage_km      integer,
    image_url       character varying(500),
    vehicle_status  character varying(30)  DEFAULT 'AVAILABLE' NOT NULL,
    created_at      timestamp              DEFAULT now() NOT NULL,
    updated_at      timestamp              DEFAULT now() NOT NULL,
    CONSTRAINT vehicles_type_check CHECK (type IN ('TRUCK','VAN','CAR','MOTO'))
);

ALTER TABLE ONLY public.vehicles ADD CONSTRAINT vehicles_pkey      PRIMARY KEY (id);
ALTER TABLE ONLY public.vehicles ADD CONSTRAINT vehicles_plate_key UNIQUE (plate);
CREATE INDEX idx_vehicles_driver_id ON public.vehicles USING btree (driver_id);

-- ------------------------------------------------------------
-- depots
-- ------------------------------------------------------------
CREATE TABLE public.depots (
    id         uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id uuid                   NOT NULL,
    name       character varying(150) NOT NULL,
    address    text,
    latitude   double precision       NOT NULL,
    longitude  double precision       NOT NULL,
    is_active  boolean                DEFAULT true NOT NULL,
    created_at timestamp              DEFAULT now() NOT NULL,
    updated_at timestamp              DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.depots ADD CONSTRAINT depots_pkey PRIMARY KEY (id);
CREATE INDEX idx_depots_company   ON public.depots USING btree (company_id);
CREATE INDEX idx_depots_is_active ON public.depots USING btree (is_active);

-- ------------------------------------------------------------
-- zones
-- ------------------------------------------------------------
CREATE TABLE public.zones (
    id           uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id   uuid                   NOT NULL,
    name         character varying(150) NOT NULL,
    color        character varying(7),
    description  text,
    cities       jsonb                  DEFAULT '[]' NOT NULL,
    postal_codes jsonb                  DEFAULT '[]' NOT NULL,
    geometry     text,
    is_active    boolean                DEFAULT true NOT NULL,
    created_at   timestamp              DEFAULT now() NOT NULL,
    updated_at   timestamp              DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.zones ADD CONSTRAINT zones_pkey PRIMARY KEY (id);
CREATE INDEX idx_zones_company   ON public.zones USING btree (company_id);
CREATE INDEX idx_zones_is_active ON public.zones USING btree (is_active);

-- ------------------------------------------------------------
-- tracking
-- ------------------------------------------------------------
CREATE TABLE public.tracking (
    id          uuid DEFAULT gen_random_uuid() NOT NULL,
    delivery_id uuid           NOT NULL,
    lat         numeric(10,7)  NOT NULL,
    lng         numeric(10,7)  NOT NULL,
    "timestamp" timestamp      DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.tracking ADD CONSTRAINT tracking_pkey PRIMARY KEY (id);
CREATE INDEX idx_tracking_delivery_id ON public.tracking USING btree (delivery_id);
CREATE INDEX idx_tracking_timestamp   ON public.tracking USING btree ("timestamp" DESC);

-- ------------------------------------------------------------
-- audit_logs
-- ------------------------------------------------------------
CREATE TABLE public.audit_logs (
    id            uuid DEFAULT gen_random_uuid() NOT NULL,
    company_id    uuid,
    actor_name    character varying(255) NOT NULL,
    actor_role    character varying(255) NOT NULL,
    action        character varying(255) NOT NULL,
    target_entity character varying(50),
    resource_id   text,
    details       text,
    ip_address    character varying(255) NOT NULL,
    created_at    timestamp              DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE ONLY public.audit_logs ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (id);
CREATE INDEX idx_audit_logs_company ON public.audit_logs USING btree (company_id);

-- ------------------------------------------------------------
-- delivery_reports
-- ------------------------------------------------------------
CREATE TABLE public.delivery_reports (
    id          uuid DEFAULT gen_random_uuid() NOT NULL,
    delivery_id uuid                   NOT NULL,
    driver_id   uuid                   NOT NULL,
    report_type character varying(30)  NOT NULL,
    description text,
    lat         numeric(10,7),
    lng         numeric(10,7),
    photo_urls  jsonb,
    created_at  timestamp              DEFAULT now() NOT NULL,
    CONSTRAINT delivery_reports_type_check CHECK (report_type IN (
        'ADDRESS_NOT_FOUND','CUSTOMER_UNREACHABLE','CUSTOMER_REFUSED',
        'DAMAGED_PACKAGE','PAYMENT_ISSUE','OTHER'))
);

ALTER TABLE ONLY public.delivery_reports ADD CONSTRAINT delivery_reports_pkey PRIMARY KEY (id);

-- ------------------------------------------------------------
-- route_alerts
-- ------------------------------------------------------------
CREATE TABLE public.route_alerts (
    id           uuid DEFAULT gen_random_uuid() NOT NULL,
    route_id     uuid                  NOT NULL,
    stop_id      uuid,
    stop_order   integer,
    alert_type   character varying(20) NOT NULL,
    message      text                  NOT NULL,
    acknowledged boolean               DEFAULT false NOT NULL,
    created_at   timestamp             DEFAULT now() NOT NULL,
    CONSTRAINT route_alerts_type_check CHECK (alert_type IN ('APPROACHING','AT_RISK','BREACHED'))
);

ALTER TABLE ONLY public.route_alerts ADD CONSTRAINT route_alerts_pkey PRIMARY KEY (id);
CREATE INDEX idx_route_alerts_route_id ON public.route_alerts USING btree (route_id);
CREATE INDEX idx_route_alerts_unacked  ON public.route_alerts USING btree (route_id, acknowledged) WHERE acknowledged = false;

-- ------------------------------------------------------------
-- system_settings
-- ------------------------------------------------------------
CREATE TABLE public.system_settings (
    setting_key   character varying(191) NOT NULL,
    setting_value text                   NOT NULL,
    updated_at    timestamp              DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.system_settings ADD CONSTRAINT system_settings_pkey PRIMARY KEY (setting_key);

-- ------------------------------------------------------------
-- time_slots
-- ------------------------------------------------------------
CREATE TABLE public.time_slots (
    id             uuid DEFAULT gen_random_uuid() NOT NULL,
    zone_id        uuid,
    slot_date      date                   NOT NULL,
    start_time     time                   NOT NULL,
    end_time       time                   NOT NULL,
    max_deliveries integer,
    current_count  integer                DEFAULT 0 NOT NULL,
    status         character varying(20)  DEFAULT 'ACTIVE' NOT NULL,
    created_at     timestamp              DEFAULT now() NOT NULL,
    updated_at     timestamp              DEFAULT now() NOT NULL,
    CONSTRAINT time_slots_status_check CHECK (status IN ('ACTIVE','CLOSED','CANCELLED'))
);

ALTER TABLE ONLY public.time_slots ADD CONSTRAINT time_slots_pkey PRIMARY KEY (id);
CREATE INDEX idx_time_slots_date_zone ON public.time_slots USING btree (slot_date, zone_id);
CREATE INDEX idx_time_slots_status    ON public.time_slots USING btree (status);

-- ------------------------------------------------------------
-- slot_assignments
-- ------------------------------------------------------------
CREATE TABLE public.slot_assignments (
    id               uuid DEFAULT gen_random_uuid() NOT NULL,
    delivery_id      uuid                   NOT NULL,
    slot_id          uuid                   NOT NULL,
    assigned_at      timestamp              DEFAULT now() NOT NULL,
    assigned_by      character varying(100),
    status           character varying(20)  DEFAULT 'ASSIGNED' NOT NULL,
    compliance_note  text,
    override_used    boolean                DEFAULT false NOT NULL,
    override_reason  text,
    override_by      character varying(100),
    created_at       timestamp              DEFAULT now() NOT NULL,
    updated_at       timestamp              DEFAULT now() NOT NULL,
    CONSTRAINT slot_assignments_status_check CHECK (status IN ('ASSIGNED','FULFILLED','VIOLATED','CANCELLED'))
);

ALTER TABLE ONLY public.slot_assignments ADD CONSTRAINT slot_assignments_pkey              PRIMARY KEY (id);
ALTER TABLE ONLY public.slot_assignments ADD CONSTRAINT slot_assignments_delivery_id_key   UNIQUE (delivery_id);
CREATE INDEX idx_slot_assignments_slot_id ON public.slot_assignments USING btree (slot_id);
CREATE INDEX idx_slot_assignments_status  ON public.slot_assignments USING btree (status);

-- ------------------------------------------------------------
-- vehicle_inspections
-- ------------------------------------------------------------
CREATE TABLE public.vehicle_inspections (
    id               uuid DEFAULT gen_random_uuid() NOT NULL,
    vehicle_id       uuid                   NOT NULL,
    driver_id        uuid                   NOT NULL,
    odometer_reading integer,
    fuel_level       integer,
    tires_status     character varying(20),
    brakes_status    character varying(20),
    lights_status    character varying(20),
    notes            character varying(500),
    inspected_at     timestamp              DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.vehicle_inspections ADD CONSTRAINT vehicle_inspections_pkey PRIMARY KEY (id);
CREATE INDEX idx_vehicle_inspections_vehicle_id ON public.vehicle_inspections USING btree (vehicle_id);
CREATE INDEX idx_vehicle_inspections_driver_id  ON public.vehicle_inspections USING btree (driver_id);

-- ------------------------------------------------------------
-- Foreign key constraints
-- ------------------------------------------------------------
ALTER TABLE ONLY public.deliveries       ADD CONSTRAINT deliveries_company_id_fkey         FOREIGN KEY (company_id)    REFERENCES public.companies(id);
ALTER TABLE ONLY public.deliveries       ADD CONSTRAINT deliveries_order_id_fkey            FOREIGN KEY (order_id)      REFERENCES public.orders(id);
ALTER TABLE ONLY public.delivery_status_history ADD CONSTRAINT dsh_delivery_id_fkey        FOREIGN KEY (delivery_id)   REFERENCES public.deliveries(id);
ALTER TABLE ONLY public.delivery_reports ADD CONSTRAINT delivery_reports_delivery_id_fkey  FOREIGN KEY (delivery_id)   REFERENCES public.deliveries(id);
ALTER TABLE ONLY public.orders           ADD CONSTRAINT orders_company_id_fkey              FOREIGN KEY (company_id)    REFERENCES public.companies(id);
ALTER TABLE ONLY public.orders           ADD CONSTRAINT orders_zone_id_fkey                 FOREIGN KEY (zone_id)       REFERENCES public.zones(id);
ALTER TABLE ONLY public.orders           ADD CONSTRAINT orders_parent_order_id_fkey         FOREIGN KEY (parent_order_id) REFERENCES public.orders(id);
ALTER TABLE ONLY public.proof_of_delivery ADD CONSTRAINT pod_delivery_id_fkey              FOREIGN KEY (delivery_id)   REFERENCES public.deliveries(id);
ALTER TABLE ONLY public.routes           ADD CONSTRAINT routes_company_id_fkey              FOREIGN KEY (company_id)    REFERENCES public.companies(id);
ALTER TABLE ONLY public.routes           ADD CONSTRAINT routes_vehicle_id_fkey              FOREIGN KEY (vehicle_id)    REFERENCES public.vehicles(id);
ALTER TABLE ONLY public.routes           ADD CONSTRAINT routes_depot_id_fkey                FOREIGN KEY (depot_id)      REFERENCES public.depots(id);
ALTER TABLE ONLY public.routes           ADD CONSTRAINT routes_zone_id_fkey                 FOREIGN KEY (zone_id)       REFERENCES public.zones(id);
ALTER TABLE ONLY public.route_stops      ADD CONSTRAINT route_stops_route_id_fkey           FOREIGN KEY (route_id)      REFERENCES public.routes(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.route_stops      ADD CONSTRAINT route_stops_delivery_id_fkey        FOREIGN KEY (delivery_id)   REFERENCES public.deliveries(id);
ALTER TABLE ONLY public.route_report     ADD CONSTRAINT route_report_route_id_fkey          FOREIGN KEY (route_id)      REFERENCES public.routes(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.depots           ADD CONSTRAINT depots_company_id_fkey              FOREIGN KEY (company_id)    REFERENCES public.companies(id);
ALTER TABLE ONLY public.zones            ADD CONSTRAINT zones_company_id_fkey               FOREIGN KEY (company_id)    REFERENCES public.companies(id);
ALTER TABLE ONLY public.tracking         ADD CONSTRAINT tracking_delivery_id_fkey           FOREIGN KEY (delivery_id)   REFERENCES public.deliveries(id);
ALTER TABLE ONLY public.time_slots       ADD CONSTRAINT fk_time_slots_zone_id               FOREIGN KEY (zone_id)       REFERENCES public.zones(id);
ALTER TABLE ONLY public.slot_assignments ADD CONSTRAINT slot_assignments_delivery_id_fkey   FOREIGN KEY (delivery_id)   REFERENCES public.deliveries(id);
ALTER TABLE ONLY public.slot_assignments ADD CONSTRAINT slot_assignments_slot_id_fkey       FOREIGN KEY (slot_id)       REFERENCES public.time_slots(id);
ALTER TABLE ONLY public.vehicle_inspections ADD CONSTRAINT vi_vehicle_id_fkey               FOREIGN KEY (vehicle_id)    REFERENCES public.vehicles(id);
