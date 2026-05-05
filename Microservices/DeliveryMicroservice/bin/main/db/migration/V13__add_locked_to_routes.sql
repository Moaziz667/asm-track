-- V13: Add `locked` flag to routes.
-- Locked routes are excluded from batch optimization (when the dispatcher
-- selects multiple routes and triggers "Optimiser la sélection"). The flag is
-- toggled per-route from the dispatcher console.

ALTER TABLE routes ADD COLUMN IF NOT EXISTS locked BOOLEAN NOT NULL DEFAULT FALSE;
