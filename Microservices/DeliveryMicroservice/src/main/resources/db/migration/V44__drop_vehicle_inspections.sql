-- V44 — Retirer la table des inspections de véhicule.
--
-- La fonctionnalité n'a jamais été terminée. L'endpoint POST /api/v1/driver/vehicles/inspection
-- existait, mais rien ne l'appelait : ni l'application livreur, ni le back-office. Le service était
-- même injecté dans RouteExecutionService sans y être utilisé une seule fois — un champ mort, ce qui
-- dit assez que le contrôle "inspection avant départ" a été câblé puis abandonné.
--
-- Vérifié avant suppression : 0 ligne dans les 8 schémas (7 clients + public). Aucune donnée perdue.
--
-- La table appartient à V1__initial_schema.sql ; cette migration la retire sans le réécrire, pour que
-- l'historique reste rejouable tel qu'il s'est produit.

DROP TABLE IF EXISTS vehicle_inspections;
