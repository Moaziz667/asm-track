SLA status migration

Purpose
- Convert persisted SLA enum values `AT_RISK` and `BREACHED` to the new `LATE` value before deploying backend code that removes those enum values.

Preflight
1. Backup your database (required):
   - PostgreSQL: `pg_dump -Fc -f backup.dump your_db`
2. Check current counts:
   - `SELECT sla_status, count(*) FROM route_stops GROUP BY sla_status;`

Run migration
- If you use Flyway, place `V2__convert_sla_status_to_late.sql` in `src/main/resources/db/migration` (or your Flyway folder) and run the migration as part of your deployment.
- Or run directly using psql:
  - `psql -d your_db -f path/to/V2__convert_sla_status_to_late.sql`

Verify
- Confirm there are no legacy values left:
  - `SELECT count(*) FROM route_stops WHERE sla_status IN ('AT_RISK','BREACHED');`  -- should return 0

Rollback
- If needed, restore from the DB backup.

Notes / cautions
- Ensure migration runs before you deploy the backend binary that has removed `AT_RISK` and `BREACHED` from the `SlaStatus` enum. If the backend starts and JPA encounters unknown enum values, it will fail to read entities.
- Also update any other tables that may store `sla_status` as strings.
- After migration + backend deploy, update frontend types and UI to remove `AT_RISK`/`BREACHED` usages.
