-- ============================================================
-- ASM Track — driver_db schema
-- Database:   postgres-driver (port 5437)
-- User:       driver
-- Service:    DriverService
-- Generated:  pg_dump --schema-only, PostgreSQL 16.13
-- Note:       Drivers are platform-owned (shared across companies).
--             company_id is nullable — drivers are NOT bound to one company.
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
-- drivers
-- ------------------------------------------------------------
CREATE TABLE public.drivers (
    id               uuid    NOT NULL,
    name             character varying(100) NOT NULL,
    phone            character varying(20)  NOT NULL,
    password_hash    character varying(255) NOT NULL,
    active           boolean NOT NULL,
    available        boolean NOT NULL,
    current_lat      numeric(10,7),
    current_lng      numeric(10,7),
    last_location_at timestamp(6),
    fcm_token        character varying(500),    -- Firebase push notification token
    company_id       uuid,                      -- null = shared across all companies
    created_at       timestamp(6) NOT NULL,
    updated_at       timestamp(6) NOT NULL
);

ALTER TABLE ONLY public.drivers ADD CONSTRAINT drivers_pkey        PRIMARY KEY (id);
ALTER TABLE ONLY public.drivers ADD CONSTRAINT drivers_phone_unique UNIQUE (phone);

-- ------------------------------------------------------------
-- driver_stats  (lifetime delivery counters per driver)
-- ------------------------------------------------------------
CREATE TABLE public.driver_stats (
    id               uuid NOT NULL,
    driver_id        uuid NOT NULL,
    delivered        integer,
    failed           integer,
    cancelled        integer,
    total_deliveries integer,
    updated_at       timestamp(6)
);

ALTER TABLE ONLY public.driver_stats ADD CONSTRAINT driver_stats_pkey PRIMARY KEY (id);

-- ------------------------------------------------------------
-- driver_history  (delivery event log per driver)
-- ------------------------------------------------------------
CREATE TABLE public.driver_history (
    id          uuid                   NOT NULL,
    driver_id   uuid                   NOT NULL,
    delivery_id character varying(255) NOT NULL,
    status      character varying(255) NOT NULL,
    created_at  timestamp(6)           NOT NULL
);

ALTER TABLE ONLY public.driver_history ADD CONSTRAINT driver_history_pkey PRIMARY KEY (id);

-- ------------------------------------------------------------
-- driver_otp  (phone verification codes for driver auth)
-- ------------------------------------------------------------
CREATE TABLE public.driver_otp (
    id         uuid                  NOT NULL,
    phone      character varying(20) NOT NULL,
    code       character varying(6)  NOT NULL,
    expires_at timestamp(6)          NOT NULL,
    used       boolean               NOT NULL,
    created_at timestamp(6)          NOT NULL
);

ALTER TABLE ONLY public.driver_otp ADD CONSTRAINT driver_otp_pkey PRIMARY KEY (id);
