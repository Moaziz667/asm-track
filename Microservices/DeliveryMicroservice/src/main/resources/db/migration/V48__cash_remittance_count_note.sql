-- =============================================================================
-- La note du comptage cesse d'etre ecrasee par celle du responsable.
--
-- Les deux etapes ecrivaient dans la meme colonne `note`. Un caissier notait
-- « billet de 50 dechire » en comptant ; le responsable justifiait l'ecart une
-- heure plus tard et son explication remplacait l'observation -- c'est-a-dire
-- le seul temoignage de premiere main sur l'ecart, detruit au moment precis ou
-- quelqu'un cherchait a l'expliquer.
--
-- Les notes deja enregistrees ne sont pas recopiees : impossible de savoir
-- laquelle des deux etapes les a ecrites, et deviner reviendrait a attribuer a
-- un caissier des phrases ecrites par un responsable. Elles restent donc dans
-- `note`, ou l'ecran les affiche deja.
-- =============================================================================

ALTER TABLE cash_remittances ADD COLUMN IF NOT EXISTS count_note TEXT;
