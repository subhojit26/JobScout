#!/usr/bin/env bash
# Restore the JobWatch database from a backup made by backup.sh.
#   sudo bash restore.sh backups/jobwatch-YYYYMMDD-HHMMSS.db.gz
# Stops the app, keeps the current database as data/jobwatch.db.before-restore-<time>,
# puts the backup in place and starts the app again.
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="${1:?usage: sudo bash restore.sh <backup .db.gz file>}"
[ -f "$SRC" ] || { echo "No such file: $SRC" >&2; exit 1; }

TMP="$(mktemp)"
gunzip -c "$SRC" > "$TMP"
[ "$(sqlite3 "$TMP" 'PRAGMA integrity_check;')" = "ok" ] || { echo "Backup failed integrity check" >&2; exit 1; }

cd "$DIR"
docker compose stop app
STAMP="$(date -u +%Y%m%d-%H%M%S)"
for f in data/jobwatch.db data/jobwatch.db-wal data/jobwatch.db-shm; do
  [ -f "$f" ] && mv "$f" "$f.before-restore-$STAMP"
done
mv "$TMP" data/jobwatch.db
docker compose start app
echo "Restored $SRC. Previous database kept as data/jobwatch.db.before-restore-$STAMP"
