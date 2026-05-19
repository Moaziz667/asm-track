CREATE TABLE companies (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(255) NOT NULL,
    logo_url      VARCHAR(512),
    address       VARCHAR(512),
    primary_color VARCHAR(7)   DEFAULT '#FF5722',
    erp_type      VARCHAR(20)  DEFAULT 'NONE',
    erp_api_url   VARCHAR(512),
    erp_api_key   VARCHAR(512),
    erp_db_name   VARCHAR(255),
    erp_username  VARCHAR(255),
    erp_uid       INTEGER,
    active        BOOLEAN      DEFAULT true,
    created_at    TIMESTAMP    DEFAULT NOW()
);

-- Default ASM company — all existing data is assigned to this
INSERT INTO companies (id, name, primary_color)
VALUES ('00000000-0000-0000-0000-000000000001', 'ASM Track', '#FF5722');
