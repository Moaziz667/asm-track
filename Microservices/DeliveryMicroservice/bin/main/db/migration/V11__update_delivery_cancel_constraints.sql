-- V11: Relax cancelled_by check constraint on deliveries
-- Allows 'ADMIN' as a valid canceller (used by the dispatch panel)

DO $$ 
BEGIN
    -- Drop the old constraint
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'deliveries_cancelled_by_check') THEN
        ALTER TABLE deliveries DROP CONSTRAINT deliveries_cancelled_by_check;
    END IF;

    -- Add the updated constraint including 'ADMIN'
    ALTER TABLE deliveries ADD CONSTRAINT deliveries_cancelled_by_check 
        CHECK (cancelled_by IN ('CLIENT', 'DRIVER', 'SYSTEM', 'ADMIN'));
END $$;
