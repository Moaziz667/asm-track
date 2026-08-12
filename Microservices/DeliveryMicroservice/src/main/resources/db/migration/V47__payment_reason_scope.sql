-- =============================================================================
-- Motifs d'encaissement : une portée à eux.
--
-- La carte d'encaissement filtrait les motifs sur `coversDelivery()`, donc elle
-- proposait le catalogue de livraison entier. Un livreur qui rapporte 108 DT au
-- lieu de 140 devait choisir entre « adresse introuvable », « panne du véhicule »
-- et « conditions météo » — aucun ne parle d'argent. Les deux motifs qui
-- convenaient étaient noyés au milieu de vingt qui ne convenaient pas, et c'est
-- ainsi qu'un manque de liquide s'est retrouvé enregistré sous « rupture de
-- stock ».
--
-- Les deux motifs de paiement existants basculent en PAYMENT, et quatre autres
-- couvrent ce que le terrain produit réellement : la promesse de payer plus tard
-- (le cas le plus courant, et celui qui n'était pas exprimable du tout), le
-- chèque refusé par le livreur, le règlement déjà fait par virement, et la
-- somme partielle acceptée d'un commun accord.
--
-- Un code plutôt qu'un commentaire libre, à dessein : un code s'agrège
-- (« 38 % des refus sont pour manque de liquide »), une phrase non.
--
-- Idempotent : rejouer ne duplique rien et ne réécrit pas un libellé renommé.
-- =============================================================================

UPDATE failure_reasons SET scope = 'PAYMENT', updated_at = now()
 WHERE code IN ('REF_NO_CASH', 'REF_AMOUNT');

INSERT INTO failure_reasons (code, label, category, scope, sort_order) VALUES
  ('PAY_PROMISE',      'Client promet de régler plus tard',           'REFUSED', 'PAYMENT', 350),
  ('PAY_CHEQUE_REFUSED','Chèque refusé par le livreur',               'REFUSED', 'PAYMENT', 360),
  ('PAY_TRANSFER',     'Client dit avoir réglé par virement',         'REFUSED', 'PAYMENT', 370),
  ('PAY_PARTIAL_AGREED','Versement partiel accepté d''un commun accord','REFUSED', 'PAYMENT', 380)
ON CONFLICT (code) DO NOTHING;
