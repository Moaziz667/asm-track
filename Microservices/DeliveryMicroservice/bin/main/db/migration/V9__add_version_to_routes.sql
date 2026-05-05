-- V9: Add version column to routes for optimistic locking support
ALTER TABLE routes ADD COLUMN IF NOT EXISTS version INTEGER DEFAULT 0;
