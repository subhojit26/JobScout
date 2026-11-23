#!/usr/bin/env bash
# Daily backup of the JobWatch SQLite database.
#
#  - Takes a consistent online snapshot with SQLite's .backup (safe while the app is running).
#  - Verifies it (PRAGMA integrity_check) and gzips it into ./backups/.
#  - Keeps the newest BACKUP_KEEP copies on the server (default 7).
#  - If BACKUP_UPLOAD_URL is set in .env (an Oracle Object Storage pre-authenticated request
#    URL ending in /o/), also uploads the copy off the server.
#
# Installed as a daily cron job by setup.sh. Run by hand any time:  sudo bash backup.sh
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
DB="$DIR/data/jobwatch.db"
OUT="$DIR/backups"
ENV_FILE="$DIR/.env"

env_value() { [ -f "$ENV_FILE" ] && grep -E "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2- || true; }
KEEP="$(env_value BACKUP_KEEP)"; KEEP="${KEEP:-7}"
UPLOAD_URL="$(env_value BACKUP_UPLOAD_URL)"

if [ ! -f "$DB" ]; then
  echo "$(date -u +%FT%TZ) no database at $DB, nothing to back up" >&2
  exit 1
fi

mkdir -p "$OUT"
STAMP="$(date -u +%Y%m%d-%H%M%S)"
SNAP="$OUT/jobwatch-$STAMP.db"

sqlite3 "$DB" ".backup '$SNAP'"
if [ "$(sqlite3 "$SNAP" 'PRAGMA integrity_check;')" != "ok" ]; then
  echo "$(date -u +%FT%TZ) integrity check FAILED for $SNAP" >&2
  rm -f "$SNAP"
  exit 1
fi
gzip -9 "$SNAP"
echo "$(date -u +%FT%TZ) backup ok: $(basename "$SNAP").gz ($(du -h "$SNAP.gz" | cut -f1))"

# Keep only the newest $KEEP local copies.
ls -1t "$OUT"/jobwatch-*.db.gz 2>/dev/null | tail -n +"$((KEEP + 1))" | xargs -r rm -f

if [ -n "$UPLOAD_URL" ]; then
  curl -fsS --retry 3 -T "$SNAP.gz" "${UPLOAD_URL%/}/$(basename "$SNAP.gz")"
  echo "$(date -u +%FT%TZ) uploaded off-site"
fi
