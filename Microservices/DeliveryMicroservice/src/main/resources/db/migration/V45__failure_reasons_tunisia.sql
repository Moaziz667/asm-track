-- =============================================================================
-- Catalogue de motifs d'échec réaliste pour la livraison en Tunisie.
--
-- Le catalogue livré ne contenait que trois motifs, tous de catégorie MISSING
-- (rupture, colis non chargé, colis introuvable) : de quoi expliquer un article
-- manquant, jamais une visite qui échoue. Un livreur devant un rideau baissé
-- n'avait donc que « Autre motif », et l'analyse d'échecs qui en découle ne dit
-- rien d'exploitable — c'est le motif le plus fréquent qui devient le moins
-- renseigné.
--
-- Les motifs ci-dessous sont ceux qui reviennent réellement sur le terrain :
-- commerces fermés à l'heure du passage, téléphone éteint, rue prise par le
-- souk hebdomadaire, et surtout le client qui n'a pas la somme au moment
-- d'encaisser — le premier motif d'échec d'une livraison contre remboursement.
--
-- `scope` reste cohérent avec la catégorie : seules REFUSED et DAMAGED peuvent
-- qualifier une ligne d'articles autant qu'une visite entière ; un client absent
-- ou une adresse fausse ne se rapportent qu'à la visite.
--
-- Idempotent sur `code` : rejouer la migration ne duplique rien, et un client
-- qui a renommé un libellé le garde.
-- =============================================================================

INSERT INTO failure_reasons (code, label, category, scope, sort_order) VALUES

  -- Client absent -----------------------------------------------------------
  ('ABS_NO_ANSWER',     'Client absent, ne répond pas au téléphone',   'CLIENT_ABSENT', 'DELIVERY', 100),
  ('ABS_PHONE_OFF',     'Téléphone éteint ou injoignable',             'CLIENT_ABSENT', 'DELIVERY', 110),
  ('ABS_SHOP_CLOSED',   'Commerce fermé à l''heure du passage',        'CLIENT_ABSENT', 'DELIVERY', 120),
  ('ABS_RESCHEDULE',    'Client absent, a demandé de repasser',        'CLIENT_ABSENT', 'DELIVERY', 130),
  ('ABS_TRAVEL',        'Client en déplacement ou en congé',           'CLIENT_ABSENT', 'DELIVERY', 140),

  -- Adresse -----------------------------------------------------------------
  ('ADDR_NOT_FOUND',    'Adresse introuvable ou incomplète',           'WRONG_ADDRESS', 'DELIVERY', 200),
  ('ADDR_MOVED',        'Client a déménagé',                           'WRONG_ADDRESS', 'DELIVERY', 210),
  ('ADDR_OUT_OF_ZONE',  'Adresse hors de la zone desservie',           'WRONG_ADDRESS', 'DELIVERY', 220),
  ('ADDR_INACCESSIBLE', 'Rue inaccessible (travaux, souk, véhicule)',  'WRONG_ADDRESS', 'DELIVERY', 230),

  -- Refus -------------------------------------------------------------------
  ('REF_NO_CASH',       'Client n''a pas la somme à régler',           'REFUSED',       'DELIVERY', 300),
  ('REF_AMOUNT',        'Client conteste le montant à encaisser',      'REFUSED',       'DELIVERY', 310),
  ('REF_NOT_ORDERED',   'Client déclare ne pas avoir commandé',        'REFUSED',       'BOTH',     320),
  ('REF_LATE',          'Refus : délai de livraison dépassé',          'REFUSED',       'BOTH',     330),
  ('REF_WRONG_ITEM',    'Refus : article non conforme à la commande',  'REFUSED',       'BOTH',     340),

  -- Marchandise -------------------------------------------------------------
  ('DMG_TRANSPORT',     'Colis endommagé pendant le transport',        'DAMAGED',       'BOTH',     400),
  ('DMG_PACKAGING',     'Emballage ouvert ou déchiré',                 'DAMAGED',       'BOTH',     410),

  -- Terrain -----------------------------------------------------------------
  ('OTH_VEHICLE',       'Panne ou accident du véhicule',               'OTHER',         'DELIVERY', 500),
  ('OTH_ROAD_BLOCKED',  'Route bloquée ou circulation interrompue',    'OTHER',         'DELIVERY', 510),
  ('OTH_WEATHER',       'Conditions météo empêchant la livraison',     'OTHER',         'DELIVERY', 520),
  ('OTH_END_OF_DAY',    'Fin de tournée — arrêt non atteint à temps',  'OTHER',         'DELIVERY', 530)

ON CONFLICT (code) DO NOTHING;
