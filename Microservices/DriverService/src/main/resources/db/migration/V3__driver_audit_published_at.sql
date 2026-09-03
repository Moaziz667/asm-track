-- Le journal d'audit devient une table unique, tenue par le service Livraison et alimentée par tous.
-- Ce service gardait les siens pour lui et la console allait les chercher en HTTP pour les fusionner
-- en mémoire, ce qui rendait toute pagination correcte impossible.
--
-- published_at dit si la ligne a rejoint ce journal partagé. NULL vaut « due », que la publication
-- ait échoué ou que la ligne soit antérieure à ce changement : le rattrapage au démarrage les
-- reprend toutes, sans distinguer les deux cas.
ALTER TABLE driver_audit_logs ADD COLUMN published_at TIMESTAMP;

-- Toutes les lignes existantes sont dues. L'index ne porte que sur celles-là, et se vide à mesure
-- que le rattrapage avance : un index partiel reste petit même quand la table ne l'est plus.
CREATE INDEX idx_driver_audit_unpublished
    ON driver_audit_logs (created_at)
    WHERE published_at IS NULL;
