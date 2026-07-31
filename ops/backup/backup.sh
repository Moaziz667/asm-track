#!/usr/bin/env bash
#
# ASM Track — full backup of every stateful component.
#
#   ./backup.sh [destination-root]        default: <repo>/backups, or $BACKUP_DIR
#
# Produces one dated directory:
#   <db>.dump            pg_dump custom format, one per database
#   minio_data.tar.gz    POD photos and signatures
#   erp-adapter.tar.gz   ERP field mappings + idempotency store
#   MANIFEST             what was taken, when, and the verification result
#
# Runs against the containers, not the host: nothing here needs a database password
# (the official postgres image trusts local-socket connections) and nothing needs the
# MinIO credentials (the volume is read through --volumes-from). A backup script that
# has to be handed secrets ends up with those secrets in a crontab.
#
# See README.md for the operating procedure.

set -euo pipefail

# Git Bash rewrites arguments that look like Unix paths before handing them to a native
# program, so `-C /data` would reach the container as `C:/Program Files/Git/data`. Both
# variables are inert on Linux, where the script actually runs in production.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$MODULE_DIR/../.." && pwd)"

DEST_ROOT="${1:-${BACKUP_DIR:-$REPO_ROOT/backups}}"
STAMP="$(date +%Y%m%d-%H%M%S)"
DEST="$DEST_ROOT/$STAMP"
KEEP="${BACKUP_KEEP:-14}"           # dated directories to retain
REMOTE="${BACKUP_REMOTE:-}"         # rclone target, e.g. b2:asm-backups — empty = local only

# container:user:database — the four databases holding business or identity data.
DATABASES=(
  "postgres-delivery:delivery:delivery_db"
  "postgres-app:app_user:app_db"
  "postgres-driver:driver:driver_db"
  "postgres-keycloak:keycloak:keycloak_db"
)

# container:mountpoint:archive-name
VOLUMES=(
  "minio:/data:minio_data"
  "erp-adapter:/data:erp-adapter"
)

log() { printf '%s  %s\n' "$(date +%H:%M:%S)" "$*"; }

# The state file is what turns a silent cron failure into something visible: the System Health
# page reads it, so a backup that stopped running four days ago shows up on screen instead of
# being discovered the day it is needed. Written on failure too — that is the whole point.
state() {
  local status="$1" detail="$2"
  mkdir -p "$DEST_ROOT"
  cat > "$DEST_ROOT/last-backup.json" <<EOF
{
  "status": "$status",
  "finishedAt": "$(date -Iseconds)",
  "directory": "$STAMP",
  "detail": "$detail",
  "offsite": $( [ -n "$REMOTE" ] && echo true || echo false )
}
EOF
}

fail() { log "FAILED: $*"; state failed "$*"; exit 1; }
trap 'fail "interrupted"' INT TERM

for c in postgres-delivery postgres-app postgres-driver postgres-keycloak minio erp-adapter; do
  docker inspect -f '{{.State.Running}}' "$c" 2>/dev/null | grep -q true \
    || fail "container '$c' is not running — start the stack before backing up"
done

mkdir -p "$DEST"
log "backing up to $DEST"

{
  echo "ASM Track backup"
  echo "taken_at: $(date -Iseconds)"
  echo "host: $(hostname)"
  echo
} > "$DEST/MANIFEST"

# ── databases ────────────────────────────────────────────────────────────────
for entry in "${DATABASES[@]}"; do
  IFS=: read -r container user db <<< "$entry"
  out="$DEST/$db.dump"
  log "dumping $db"
  docker exec "$container" pg_dump -U "$user" -d "$db" -Fc > "$out" || fail "pg_dump of $db"

  # Verify before declaring success: pg_restore -l reads the archive's table of contents, so a
  # truncated or corrupt dump is caught now rather than on the day it is needed.
  docker exec -i "$container" pg_restore -l > /dev/null < "$out" \
    || fail "$db dumped but the archive is unreadable"

  echo "$db.dump  $(wc -c < "$out") bytes  verified" >> "$DEST/MANIFEST"
done

# ── volumes ──────────────────────────────────────────────────────────────────
# Streamed through stdout rather than a bind mount: keeps the script portable to Git Bash on
# Windows, where a host path in `docker run -v` gets rewritten.
for entry in "${VOLUMES[@]}"; do
  IFS=: read -r container path name <<< "$entry"
  out="$DEST/$name.tar.gz"
  log "archiving $container:$path"
  docker run --rm --volumes-from "$container" alpine:3 \
    tar czf - -C "$path" . > "$out" || fail "archive of $container:$path"
  gzip -t "$out" || fail "$name archived but the tarball is corrupt"

  echo "$name.tar.gz  $(wc -c < "$out") bytes  verified" >> "$DEST/MANIFEST"
done

# ── base backup + WAL journals (delivery only) ───────────────────────────────
# The logical dumps above cannot be combined with the WAL journals: pg_dump produces SQL
# statements, the journals describe physical page changes, and one cannot be replayed on top
# of the other. Point-in-time recovery therefore needs its own *physical* base backup — that
# is what this step takes, and why delivery_db is covered twice.
#
# Which one to use on the day:
#   delivery_db.dump      one tenant, or a readable schema-level restore   → RPO 24 h
#   delivery_base.tar.gz  the whole cluster replayed to a chosen minute    → RPO ~5 min
if docker exec postgres-delivery psql -U delivery -d delivery_db -tAc "SHOW archive_mode" \
     2>/dev/null | grep -q on; then

  log "base backup of delivery (physical)"
  # -X fetch collects the WAL generated during the copy, so the archive stands on its own even
  # if the journals around it were pruned. --checkpoint=fast avoids waiting for the next
  # scheduled checkpoint, which can be minutes away on an idle server.
  docker exec postgres-delivery pg_basebackup -U delivery -D - -Ft -z -X fetch \
    --checkpoint=fast > "$DEST/delivery_base.tar.gz" || fail "pg_basebackup of delivery"
  gzip -t "$DEST/delivery_base.tar.gz" || fail "base backup taken but the tarball is corrupt"
  echo "delivery_base.tar.gz  $(wc -c < "$DEST/delivery_base.tar.gz") bytes  verified" >> "$DEST/MANIFEST"

  log "archiving WAL journals"
  docker run --rm --volumes-from postgres-delivery alpine:3 \
    tar czf - -C /wal-archive . > "$DEST/wal-archive.tar.gz" || fail "archive of the WAL journals"
  gzip -t "$DEST/wal-archive.tar.gz" || fail "WAL journals archived but the tarball is corrupt"
  echo "wal-archive.tar.gz  $(wc -c < "$DEST/wal-archive.tar.gz") bytes  verified" >> "$DEST/MANIFEST"

  # The journals accumulate forever otherwise. Anything older than the oldest base backup we
  # still keep can never be replayed onto anything, so it is dead weight.
  docker exec postgres-delivery find /wal-archive -type f -mtime "+$KEEP" -delete \
    || log "warning: could not prune old WAL journals"

  archiver=$(docker exec postgres-delivery psql -U delivery -d delivery_db -tAc \
    "SELECT failed_count FROM pg_stat_archiver" | tr -d '[:space:]')
  # A non-zero failure count means journals are being written but not archived: the RPO is
  # silently back to 24 h. Worth saying out loud rather than discovering it during a recovery.
  [ "$archiver" = "0" ] || log "warning: pg_stat_archiver reports $archiver failed archive attempts"
  echo "wal_archiver_failures: ${archiver:-unknown}" >> "$DEST/MANIFEST"
else
  log "archive_mode is off — skipping the physical base backup (RPO stays at 24 h)"
  echo "wal_archiving: off" >> "$DEST/MANIFEST"
fi

# ── off-site copy ────────────────────────────────────────────────────────────
# A backup sitting on the disk it protects survives a bad DELETE, not a dead disk and not
# ransomware — which encrypts the backups along with everything else. Configure the remote
# with `rclone config`; no credential ever passes through this script.
if [ -n "$REMOTE" ]; then
  command -v rclone > /dev/null || fail "BACKUP_REMOTE is set but rclone is not installed"
  log "copying to $REMOTE"
  rclone copy "$DEST" "$REMOTE/$STAMP" || fail "off-site copy to $REMOTE"
  echo "offsite: $REMOTE/$STAMP" >> "$DEST/MANIFEST"
fi

# ── retention ────────────────────────────────────────────────────────────────
# Oldest first, drop everything past $KEEP. Only removes directories this script created —
# they match the timestamp pattern — never the destination root itself.
mapfile -t old < <(find "$DEST_ROOT" -maxdepth 1 -type d -name '20*-*' | sort | head -n -"$KEEP")
for dir in "${old[@]:-}"; do
  [ -n "$dir" ] || continue
  log "pruning $(basename "$dir")"
  rm -rf "$dir"
done

size="$(du -sh "$DEST" | cut -f1)"
state ok "$size"
log "done — $size in $DEST"

if [ -z "$REMOTE" ]; then
  echo
  echo "BACKUP_REMOTE is not set: this copy lives on the same machine as the data it protects."
  echo "Set it (see README.md) or copy $DEST off-host by hand."
fi
