#!/usr/bin/env bash
#
# ASM Track — install the nightly backup on a Linux host.
#
#   sudo ./install-cron.sh              install (or refresh) the schedule
#   sudo ./install-cron.sh --show       print what is currently installed and exit
#   sudo ./install-cron.sh --remove     take it back out
#
# Idempotent: running it twice leaves one entry, not two. It edits only the line it owns,
# marked with the tag below, so anything else in root's crontab is left alone.

set -euo pipefail

TAG="# asm-track-backup"
MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$MODULE_DIR/backup.sh"
LOG="/var/log/asm-backup.log"
SCHEDULE="${BACKUP_SCHEDULE:-30 2 * * *}"

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

current() { crontab -l 2>/dev/null || true; }
without_ours() { current | grep -v -F "$TAG" || true; }

case "${1:-install}" in
  --show)
    line="$(current | grep -F "$TAG" || true)"
    [ -n "$line" ] && echo "$line" || echo "nothing installed"
    exit 0
    ;;
  --remove)
    [ "$(id -u)" = 0 ] || die "run with sudo — the schedule lives in root's crontab"
    without_ours | crontab -
    echo "removed"
    exit 0
    ;;
  install) ;;
  *) die "unknown option '$1' (--show, --remove)" ;;
esac

[ "$(id -u)" = 0 ] || die "run with sudo — the backup talks to the Docker daemon"
[ -x "$SCRIPT" ] || die "$SCRIPT is missing or not executable"
command -v docker > /dev/null || die "docker is not on PATH"

# Refuse a schedule that would never fire rather than install something that looks installed.
grep -qE '^[0-9*/, -]+ [0-9*/, -]+ [0-9*/, -]+ [0-9*/, -]+ [0-9*/, -]+$' <<< "$SCHEDULE" \
  || die "BACKUP_SCHEDULE='$SCHEDULE' is not a five-field cron expression"

touch "$LOG" && chmod 640 "$LOG"

{
  without_ours
  echo "$SCHEDULE $SCRIPT >> $LOG 2>&1  $TAG"
} | crontab -

# Rotate the log, or a year of nightly runs quietly fills the disk the backups are written to.
cat > /etc/logrotate.d/asm-backup <<EOF
$LOG {
    weekly
    rotate 8
    compress
    missingok
    notifempty
}
EOF

cat <<EOF
installed:
  $(current | grep -F "$TAG")

Next:
  1. run it once now to confirm it works end to end:
       $SCRIPT
  2. check tomorrow that the schedule actually fired:
       tail $LOG
  3. the System Health page reports the age of the last backup — that is the alarm
     for a job that stops running. Look at it once a week.

Off-site copies are not configured. Until BACKUP_REMOTE is set (see README.md) every
copy sits on the disk it protects.
EOF
