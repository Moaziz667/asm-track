-- outbox_events was created by V27 as an alias/duplicate of outbox_event (singular).
-- The application uses outbox_event exclusively. Drop the unused table.
DROP TABLE IF EXISTS outbox_events;
