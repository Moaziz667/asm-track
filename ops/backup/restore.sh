#!/usr/bin/env bash
#
# ASM Track — restore from a backup directory produced by backup.sh.
#
#   ./restore.sh <backup-dir> tenant <schema> [--into <scratch-db>] --yes
#   ./restore.sh <backup-dir> database <delivery|app|driver|keycloak> --yes
#   ./restore.sh <backup-dir> volume <minio|erp-adapter> --yes
#
# The tenant mode is the one that matters day to day. Schema-per-tenant means a customer can
# be repaired while the other tenants keep working — nothing is stopped, nothing else is
# touched. That is why the dumps are taken in pg_dump's custom format: plain SQL could not
# restore a single schema.
#
# --into restores the schema into a throwaway database instead of the live one. That is how
# a restore is rehearsed without putting production data at risk; see README.md.
#
# Nothing runs without --yes. A restore overwrites live data.

set -euo pipefail

# See backup.sh: Git Bash would turn `-C /data` into a Windows path on its way to the container.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

log()  { printf '%s  %s\n' "$(date +%H:%M:%S)" "$*"; }
die()  { printf 'error: %s\n' "$*" >&2; exit 1; }
usage() { sed -n '3,18p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 1; }

# database key → container:superuser:dbname
db_target() {
  case "$1" in
    delivery) echo "postgres-delivery:delivery:delivery_db" ;;
    app)      echo "postgres-app:app_user:app_db" ;;
    driver)   echo "postgres-driver:driver:driver_db" ;;
    keycloak) echo "postgres-keycloak:keycloak:keycloak_db" ;;
    *) die "unknown database '$1' (delivery, app, driver, keycloak)" ;;
  esac
}

# volume key → container:mountpoint:archive-name
vol_target() {
  case "$1" in
    minio)       echo "minio:/data:minio_data" ;;
    erp-adapter) echo "erp-adapter:/data:erp-adapter" ;;
    *) die "unknown volume '$1' (minio, erp-adapter)" ;;
  esac
}

[ $# -ge 3 ] || usage
BACKUP_DIR="$1"; MODE="$2"; TARGET="$3"; shift 3

CONFIRMED=false
SCRATCH=""
while [ $# -gt 0 ]; do
  case "$1" in
    --yes)   CONFIRMED=true; shift ;;
    --into)  SCRATCH="${2:-}"; [ -n "$SCRATCH" ] || die "--into needs a database name"; shift 2 ;;
    *) die "unknown option '$1'" ;;
  esac
done

[ -d "$BACKUP_DIR" ] || die "no such backup directory: $BACKUP_DIR"
[ -f "$BACKUP_DIR/MANIFEST" ] || die "$BACKUP_DIR has no MANIFEST — is it a backup?"

running() { docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null | grep -q true; }

case "$MODE" in

  # ── one tenant schema ──────────────────────────────────────────────────────
  tenant)
    IFS=: read -r container user db <<< "$(db_target delivery)"
    dump="$BACKUP_DIR/$db.dump"
    [ -f "$dump" ] || die "no $db.dump in $BACKUP_DIR"
    running "$container" || die "$container is not running"

    if [ -n "$SCRATCH" ]; then
      $CONFIRMED || die "refusing to run without --yes"
      log "creating throwaway database $SCRATCH"
      docker exec "$container" psql -U "$user" -d postgres -q \
        -c "DROP DATABASE IF EXISTS \"$SCRATCH\"" -c "CREATE DATABASE \"$SCRATCH\""
      # pg_restore -n selects the objects *inside* a schema but not the CREATE SCHEMA itself,
      # so a fresh database needs it created by hand — otherwise every single object fails and
      # pg_restore still exits 0, reporting "errors ignored" in the middle of its output.
      docker exec "$container" psql -U "$user" -d "$SCRATCH" -q \
        -c "CREATE SCHEMA \"$TARGET\"" -c "CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"" \
        -c "CREATE EXTENSION IF NOT EXISTS pgcrypto"

      log "restoring schema $TARGET into $SCRATCH"
      # --exit-on-error is the point of a rehearsal: a restore that half-works must be a
      # failure, not a wall of warnings nobody reads. Ownership and privileges are skipped —
      # a scratch database has no reason to carry the production roles.
      docker exec -i "$container" pg_restore -U "$user" -d "$SCRATCH" \
        -n "$TARGET" --no-owner --no-privileges --exit-on-error < "$dump"
      log "done — inspect with: docker exec -it $container psql -U $user -d $SCRATCH"
      exit 0
    fi

    cat <<EOF
About to restore schema '$TARGET' into the LIVE database $db.
  Everything currently in that schema is replaced by the contents of $BACKUP_DIR.
  Other tenants are not touched and the services keep running.
EOF
    $CONFIRMED || die "refusing to run without --yes"

    log "restoring $TARGET into $db"
    docker exec -i "$container" pg_restore -U "$user" -d "$db" \
      -n "$TARGET" --clean --if-exists --no-owner < "$dump"
    log "restored — verify the row counts before telling anyone it worked"
    ;;

  # ── a whole database ───────────────────────────────────────────────────────
  database)
    IFS=: read -r container user db <<< "$(db_target "$TARGET")"
    dump="$BACKUP_DIR/$db.dump"
    [ -f "$dump" ] || die "no $db.dump in $BACKUP_DIR"
    running "$container" || die "$container is not running"

    cat <<EOF
About to DROP and recreate the database $db.
  Stop the services that use it first, or the restore will fight open connections.
EOF
    $CONFIRMED || die "refusing to run without --yes"

    log "dropping and recreating $db"
    # Existing sessions block DROP DATABASE, and a half-restored database is worse than a
    # refused restore — so evict them explicitly rather than letting the drop fail midway.
    docker exec "$container" psql -U "$user" -d postgres -q -c \
      "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$db' AND pid <> pg_backend_pid()"
    docker exec "$container" psql -U "$user" -d postgres -q \
      -c "DROP DATABASE IF EXISTS \"$db\"" -c "CREATE DATABASE \"$db\" OWNER \"$user\""

    log "restoring $db"
    docker exec -i "$container" pg_restore -U "$user" -d "$db" --no-owner < "$dump"
    log "restored — restart the services now"
    ;;

  # ── a volume (POD media, ERP adapter store) ────────────────────────────────
  volume)
    IFS=: read -r container path name <<< "$(vol_target "$TARGET")"
    archive="$BACKUP_DIR/$name.tar.gz"
    [ -f "$archive" ] || die "no $name.tar.gz in $BACKUP_DIR"
    gzip -t "$archive" || die "$archive is corrupt — do not restore from it"

    cat <<EOF
About to replace the contents of $container:$path.
  The container is stopped during the swap and started again afterwards.
EOF
    $CONFIRMED || die "refusing to run without --yes"

    was_running=false; running "$container" && was_running=true
    # Swapping files under a live process gives a container that is up but serving half a
    # dataset, which looks like success and is not.
    $was_running && { log "stopping $container"; docker stop "$container" > /dev/null; }

    log "restoring $name"
    docker run --rm --volumes-from "$container" -i alpine:3 \
      sh -c "rm -rf ${path:?}/* ${path:?}/..?* 2>/dev/null; tar xzf - -C $path" < "$archive"

    $was_running && { log "starting $container"; docker start "$container" > /dev/null; }
    log "restored"
    ;;

  *) usage ;;
esac
