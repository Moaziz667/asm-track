-- =============================================================================
-- Failure reasons catalog (driver-facing motifs that roll up to FailureCode).
-- The entity + controller existed but no migration created the table; this adds
-- it and seeds a starter set for the default company.
-- =============================================================================
CREATE TABLE IF NOT EXISTS failure_reasons (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id  UUID         NOT NULL,
    code        VARCHAR(60)  NOT NULL,
    label       VARCHAR(160) NOT NULL,
    category    VARCHAR(30)  NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT true,
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_failure_reasons_company_code UNIQUE (company_id, code)
);
CREATE INDEX IF NOT EXISTS idx_failure_reasons_company_active
    ON failure_reasons(company_id, active);

-- Seed 14 reasons for the default single-tenant company, spread across the 5
-- canonical categories. Idempotent on (company_id, code).
INSERT INTO failure_reasons (company_id, code, label, category, sort_order) VALUES
  ('00000000-0000-0000-0000-000000000001','ABSENT_NO_ANSWER',   'Client injoignable (pas de réponse)',        'CLIENT_ABSENT', 10),
  ('00000000-0000-0000-0000-000000000001','ABSENT_NOT_PRESENT', 'Client absent au passage',                   'CLIENT_ABSENT', 20),
  ('00000000-0000-0000-0000-000000000001','ABSENT_CLOSED',      'Local / commerce fermé',                     'CLIENT_ABSENT', 30),
  ('00000000-0000-0000-0000-000000000001','REFUSED_CHANGED',    'Refus — client a changé d''avis',            'REFUSED',       40),
  ('00000000-0000-0000-0000-000000000001','REFUSED_NOT_CONFORM','Refus — produit non conforme',               'REFUSED',       50),
  ('00000000-0000-0000-0000-000000000001','REFUSED_PAYMENT',    'Refus — litige de paiement',                 'REFUSED',       60),
  ('00000000-0000-0000-0000-000000000001','ADDR_NOT_FOUND',     'Adresse introuvable',                        'WRONG_ADDRESS', 70),
  ('00000000-0000-0000-0000-000000000001','ADDR_INCOMPLETE',    'Adresse incorrecte / incomplète',            'WRONG_ADDRESS', 80),
  ('00000000-0000-0000-0000-000000000001','ADDR_OUT_OF_ZONE',   'Hors zone de livraison',                     'WRONG_ADDRESS', 90),
  ('00000000-0000-0000-0000-000000000001','DMG_BROKEN',         'Colis endommagé / cassé',                    'DAMAGED',       100),
  ('00000000-0000-0000-0000-000000000001','DMG_WET',            'Colis détérioré (humidité)',                 'DAMAGED',       110),
  ('00000000-0000-0000-0000-000000000001','OTHER_WEATHER',      'Météo / route bloquée',                      'OTHER',         120),
  ('00000000-0000-0000-0000-000000000001','OTHER_VEHICLE',      'Panne véhicule',                             'OTHER',         130),
  ('00000000-0000-0000-0000-000000000001','OTHER_TIME',         'Fin de tournée — temps insuffisant',         'OTHER',         140)
ON CONFLICT (company_id, code) DO NOTHING;
