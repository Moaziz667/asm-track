-- =============================================================================
-- Retrait de la note du comptage, ajoutee a la version precedente.
--
-- Elle etait facultative, personne ne la remplissait, et rien ne l'agregeait.
-- Deux champs libres sur la meme ligne -- l'un obligatoire, l'autre decoratif --
-- apprennent surtout au caissier que les notes sont decoratives.
--
-- Ce qui restait a proteger, c'est l'explication de l'ecart : elle est
-- obligatoire, et c'est la seule chose qui distingue un ecart explique d'un
-- ecart dissimule. Avec un seul redacteur, elle ne peut plus etre ecrasee -- le
-- probleme que la colonne separee reglait disparait avec elle.
--
-- La colonne est vide en pratique (une version l'a separee, celle-ci la
-- supprime), donc rien d'ecrit par un humain n'est perdu.
-- =============================================================================

ALTER TABLE cash_remittances DROP COLUMN IF EXISTS count_note;
