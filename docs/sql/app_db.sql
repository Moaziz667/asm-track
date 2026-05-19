-- ============================================================
-- ASM Track — app_db schema
-- Database:   postgres-app (port 5435)
-- User:       app_user
-- Services:   AppBackend, AuthServer
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

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;

-- ------------------------------------------------------------
-- admin_users  (company admin accounts — ADMIN / DISPATCHER / MANAGER / SUPER_ADMIN)
-- ------------------------------------------------------------
CREATE TABLE public.admin_users (
    id            uuid DEFAULT gen_random_uuid() NOT NULL,
    name          character varying(100) NOT NULL,
    email         character varying(100) NOT NULL,
    password_hash character varying(255) NOT NULL,
    role          character varying(20)  NOT NULL,
    active        boolean                DEFAULT true NOT NULL,
    company_id    uuid,                              -- null = SUPER_ADMIN (platform owner)
    created_at    timestamp              DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.admin_users ADD CONSTRAINT admin_users_pkey      PRIMARY KEY (id);
ALTER TABLE ONLY public.admin_users ADD CONSTRAINT admin_users_email_key UNIQUE (email);

-- ------------------------------------------------------------
-- clients  (end-customer accounts)
-- ------------------------------------------------------------
CREATE TABLE public.clients (
    id               uuid DEFAULT gen_random_uuid() NOT NULL,
    name             character varying(100) NOT NULL,
    phone            character varying(20)  NOT NULL,
    password_hash    character varying(255) NOT NULL,
    email            character varying(100),
    address          character varying(255),
    phone_verified   boolean                DEFAULT false NOT NULL,
    odoo_partner_id  integer,
    company_id       uuid,
    created_at       timestamp              DEFAULT now() NOT NULL,
    updated_at       timestamp              DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.clients ADD CONSTRAINT clients_pkey      PRIMARY KEY (id);
ALTER TABLE ONLY public.clients ADD CONSTRAINT clients_phone_key UNIQUE (phone);

CREATE INDEX idx_clients_phone ON public.clients USING btree (phone);

-- ------------------------------------------------------------
-- client_otp  (phone verification codes for client auth)
-- ------------------------------------------------------------
CREATE TABLE public.client_otp (
    id         uuid DEFAULT gen_random_uuid() NOT NULL,
    phone      character varying(20) NOT NULL,
    code       character varying(6)  NOT NULL,
    expires_at timestamp             NOT NULL,
    used       boolean               DEFAULT false NOT NULL,
    created_at timestamp             DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.client_otp ADD CONSTRAINT client_otp_pkey PRIMARY KEY (id);
CREATE INDEX idx_client_otp_phone      ON public.client_otp USING btree (phone);
CREATE INDEX idx_client_otp_created_at ON public.client_otp USING btree (created_at DESC);
