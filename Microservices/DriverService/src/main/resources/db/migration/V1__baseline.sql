-- Baseline of driver_db, captured from the live ddl-auto:update schema (2026-07-18).
-- Driver moves to Flyway so tenant schemas can be provisioned identically to delivery/appbackend.
-- Unqualified names: Flyway runs this inside the target (tenant) schema via search_path.

CREATE TABLE driver_audit_logs (
    id uuid NOT NULL,
    action character varying(80) NOT NULL,
    actor_id uuid,
    actor_name character varying(150),
    actor_role character varying(50),
    created_at timestamp(6) without time zone NOT NULL,
    details text,
    resource_id uuid
);
CREATE TABLE driver_history (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    delivery_id character varying(255) NOT NULL,
    driver_id uuid NOT NULL,
    status character varying(255) NOT NULL
);
CREATE TABLE driver_invite_tokens (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    driver_id uuid NOT NULL,
    expires_at timestamp(6) without time zone NOT NULL,
    token uuid NOT NULL,
    used boolean NOT NULL
);
CREATE TABLE driver_otp (
    id uuid NOT NULL,
    code character varying(6) NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    expires_at timestamp(6) without time zone NOT NULL,
    phone character varying(20) NOT NULL,
    used boolean NOT NULL
);
CREATE TABLE driver_stats (
    id uuid NOT NULL,
    cancelled integer,
    delivered integer,
    driver_id uuid NOT NULL,
    failed integer,
    total_deliveries integer,
    updated_at timestamp(6) without time zone
);
CREATE TABLE drivers (
    id uuid NOT NULL,
    account_status character varying(30) NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    current_lat numeric(10,7),
    current_lng numeric(10,7),
    email character varying(255),
    fcm_token character varying(500),
    is_registered boolean NOT NULL,
    last_location_at timestamp(6) without time zone,
    name character varying(100) NOT NULL,
    onboarding_status character varying(30),
    online_status character varying(20) NOT NULL,
    phone character varying(20) NOT NULL,
    photo_status character varying(20),
    photo_updated_at timestamp(6) without time zone,
    photo_url character varying(500),
    photo_version integer,
    suspended_reason character varying(500),
    updated_at timestamp(6) without time zone NOT NULL,
    CONSTRAINT drivers_account_status_check CHECK (((account_status)::text = ANY ((ARRAY['PENDING_SETUP'::character varying, 'ACTIVE'::character varying, 'SUSPENDED'::character varying])::text[]))),
    CONSTRAINT drivers_online_status_check CHECK (((online_status)::text = ANY ((ARRAY['OFFLINE'::character varying, 'ONLINE'::character varying, 'ON_BREAK'::character varying])::text[])))
);
CREATE TABLE outbox_event (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    event_type character varying(50) NOT NULL,
    last_error text,
    next_retry_at timestamp(6) without time zone NOT NULL,
    payload text NOT NULL,
    processed_at timestamp(6) without time zone,
    retry_count integer,
    status character varying(20) NOT NULL
);
ALTER TABLE ONLY driver_audit_logs
    ADD CONSTRAINT driver_audit_logs_pkey PRIMARY KEY (id);
ALTER TABLE ONLY driver_history
    ADD CONSTRAINT driver_history_pkey PRIMARY KEY (id);
ALTER TABLE ONLY driver_invite_tokens
    ADD CONSTRAINT driver_invite_tokens_pkey PRIMARY KEY (id);
ALTER TABLE ONLY driver_otp
    ADD CONSTRAINT driver_otp_pkey PRIMARY KEY (id);
ALTER TABLE ONLY driver_stats
    ADD CONSTRAINT driver_stats_pkey PRIMARY KEY (id);
ALTER TABLE ONLY drivers
    ADD CONSTRAINT drivers_pkey PRIMARY KEY (id);
ALTER TABLE ONLY outbox_event
    ADD CONSTRAINT outbox_event_pkey PRIMARY KEY (id);
ALTER TABLE ONLY drivers
    ADD CONSTRAINT ukk8g8tftyclmpgp3a5l0ni1nhk UNIQUE (phone);
ALTER TABLE ONLY driver_invite_tokens
    ADD CONSTRAINT ukq6s18ew5930fsrc84f87i6ldl UNIQUE (token);
CREATE INDEX idx_driver_audit_action ON driver_audit_logs USING btree (action);
CREATE INDEX idx_driver_audit_created ON driver_audit_logs USING btree (created_at DESC);
CREATE INDEX idx_driver_audit_resource ON driver_audit_logs USING btree (resource_id, created_at DESC);
CREATE INDEX idx_invite_token_driver ON driver_invite_tokens USING btree (driver_id);
CREATE INDEX idx_invite_token_expires ON driver_invite_tokens USING btree (expires_at);
