#!/usr/bin/env bash
# Local database commands for this backend. DEV ONLY.
#
#   scripts/dev/db.sh [update]        back up, check, migrate, then start the backend      (keeps your data)
#   scripts/dev/db.sh status          list applied and pending migrations
#   scripts/dev/db.sh backup          back up the database to backups/ (the newest 10 are kept)
#   scripts/dev/db.sh restore FILE    put a backup back (replaces the current data)
#   scripts/dev/db.sh repair          re-record checksums of edited migrations / remove failed rows
#   scripts/dev/db.sh reset           drop everything and migrate an empty database       (deletes ALL data)
#
# Options: --yes (do not ask), --no-backup, --no-start (update: migrate only).
# Settings: scripts/dev/db.conf. Override any of them with DB_HOST, DB_PORT, DB_NAME, DB_USER or DB_PASSWORD.
#
# The same file is in TinQa_Procurement_Backend and TinQa_Ecommerce_BE; change both together. Only db.conf differs.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR/../.."

# ---- Configuration ------------------------------------------------------------------------------------------------
# shellcheck source=/dev/null
[ -f "$SCRIPT_DIR/db.conf" ] && . "$SCRIPT_DIR/db.conf"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:?set DB_NAME in scripts/dev/db.conf}"
DB_USER="${DB_USER:-tinqa_app}"
DB_PASSWORD="${DB_PASSWORD:-tinqa_dev_password}"
MIGRATIONS_DIR="src/main/resources/db/migration"
BACKUP_DIR="backups"
BACKUPS_KEPT=10
export PGPASSWORD="$DB_PASSWORD"

ASSUME_YES=false
TAKE_BACKUP=true
START_APP=true

# ---- Output -------------------------------------------------------------------------------------------------------
step() { printf '\n==> %s\n' "$1"; }
die() { printf '%s\n' "$@" >&2; exit 1; }

confirm() { # confirm "question" -> continues on yes, stops on anything else
  $ASSUME_YES && return 0
  local answer
  read -r -p "$1 [y/N] " answer
  case "$answer" in y | Y | yes | YES) ;; *) die "Stopped. Nothing was changed." ;; esac
}

# ---- PostgreSQL ---------------------------------------------------------------------------------------------------
pg_require() {
  pg_isready -h "$DB_HOST" -p "$DB_PORT" -q ||
    die "Postgres is not accepting connections on $DB_HOST:$DB_PORT. Start it and run again."
}

pg_query() { psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -At -v ON_ERROR_STOP=1 -c "$1"; }

pg_backup() {
  $TAKE_BACKUP || { echo "Skipping the backup (--no-backup)."; return 0; }
  step "Backing up $DB_NAME"
  mkdir -p "$BACKUP_DIR"
  local file="$BACKUP_DIR/${DB_NAME}_$(date +%Y%m%d_%H%M%S).dump"
  if ! pg_dump -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -Fc -f "$file"; then
    rm -f "$file"
    die "Backup failed, so nothing was changed. Fix the error above, or run with --no-backup."
  fi
  echo "Saved $file"
  # shellcheck disable=SC2012
  ls -1t "$BACKUP_DIR/${DB_NAME}"_*.dump | tail -n +$((BACKUPS_KEPT + 1)) | while read -r old; do rm -f "$old"; done
}

pg_restore_file() {
  pg_restore --clean --if-exists --no-owner -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" "$1"
}

# ---- Flyway (the pom's flyway-maven-plugin, same version as the app) ----------------------------------------------
flyway() { # flyway <goal> [-Dflyway.x=y ...]
  local mvn=mvn
  [ -x ./mvnw ] && mvn=./mvnw
  local status
  set +e # keep Maven's exit code; the filter below only hides Maven's own banner lines
  "$mvn" -B -ntp -Dstyle.color=never "flyway:$1" "${@:2}" \
    -Dflyway.url="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME" \
    -Dflyway.user="$DB_USER" -Dflyway.password="$DB_PASSWORD" 2>&1 |
    grep -v -E '^\[INFO\] (Scanning|Building|-+|BUILD SUCCESS|Total time|Finished at| *$)' |
    sed -E 's/^\[(INFO|WARNING)\] //'
  status=${PIPESTATUS[0]}
  set -e
  return "$status"
}

flyway_validate() {
  step "Checking applied migrations were not edited"
  flyway validate -Dflyway.ignoreMigrationPatterns='*:pending' && return 0
  die "" \
    "Flyway validation failed (see above). Usually an applied migration was edited (checksum mismatch):" \
    "  - not on purpose: undo the edit (git checkout -- <file>) and put the change in a new V{n}__*.sql;" \
    "  - on purpose, and the database already has the result: scripts/dev/db.sh repair" \
    "A FAILED migration: fix its file, run scripts/dev/db.sh repair, then update again."
}

# ---- Migration files ----------------------------------------------------------------------------------------------
pending_migrations() { # prints the V*__*.sql files newer than the database (Flyway skips older ones), in version order
  local current file version
  current="$(pg_query "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL" 2>/dev/null |
    sort -V | tail -n 1 || true)" # the newest applied or baseline version; empty for a new database
  for file in "$MIGRATIONS_DIR"/V*__*.sql; do
    [ -e "$file" ] || continue
    version="$(basename "$file")"; version="${version#V}"; version="${version%%__*}"
    if [ -z "$current" ] || [ "$(printf '%s\n%s\n' "$current" "$version" | sort -V | tail -n 1)" != "$current" ]; then
      printf '%s\t%s\n' "$version" "$file"
    fi
  done | sort -V | cut -f2
}

# Each check prints what it found and returns 0 when the migration needs confirming before it runs.
sql_lines() { grep -n "" "$1" | grep -v -E '^[0-9]+:[[:space:]]*--' || true; } # numbered, no comment lines

check_removes_data() {
  local hits
  hits="$(sql_lines "$1" | grep -i -E 'DROP[[:space:]]+(TABLE|COLUMN)|TRUNCATE|DELETE[[:space:]]+FROM' || true)"
  [ -n "$hits" ] || return 1
  printf '\n  ! %s removes data:\n' "$(basename "$1")"
  printf '%s\n' "$hits" | sed 's/^/      /'
  if sql_lines "$1" | grep -q -i -E 'ADD[[:space:]]+COLUMN'; then
    echo "    It also adds a column. If that is a rename, use ALTER TABLE ... RENAME COLUMN old TO new;"
    echo "    instead, or the old values are lost."
  fi
}

check_not_null_without_default() {
  local hits
  hits="$(sql_lines "$1" | grep -i -E 'ADD[[:space:]]+COLUMN.*NOT[[:space:]]+NULL' | grep -v -i 'DEFAULT' || true)"
  [ -n "$hits" ] || return 1
  printf '\n  ! %s adds a NOT NULL column without a DEFAULT; this fails on a table that has rows:\n' "$(basename "$1")"
  printf '%s\n' "$hits" | sed 's/^/      /'
  echo "    Add it as NULL (or with a DEFAULT), fill it with UPDATE, then SET NOT NULL."
}

MIGRATION_CHECKS="check_removes_data check_not_null_without_default" # add a check: write it, list it here

review_pending() { # prints the plan; returns 0 when there is something to migrate
  step "Looking for new migrations"
  local pending file check risky=false
  pending="$(pending_migrations)"
  [ -n "$pending" ] || { echo "None. The database schema is up to date."; return 1; }
  echo "Will run:"
  printf '%s\n' "$pending" | while read -r file; do echo "  $(basename "$file")"; done
  [ -n "$(pg_query "SELECT to_regclass('flyway_schema_history')")" ] || return 0 # new database: no data to lose
  while read -r file; do
    for check in $MIGRATION_CHECKS; do "$check" "$file" && risky=true; done
  done <<<"$pending"
  if $risky; then
    echo
    confirm "Run these migrations? Removed data comes back only from the backup."
  fi
}

# ---- Commands -----------------------------------------------------------------------------------------------------
cmd_update() {
  pg_require
  pg_backup
  flyway_validate
  if review_pending; then
    step "Migrating (each migration runs in its own transaction; a failed one is rolled back)"
    flyway migrate || die "Migration failed and was rolled back. Fix the file, run scripts/dev/db.sh repair, then update again."
  fi
  $START_APP || return 0
  step "Rebuilding and starting the backend"
  local mvn=mvn
  [ -x ./mvnw ] && mvn=./mvnw
  exec "$mvn" clean spring-boot:run # clean removes renamed or deleted migrations from target/classes
}

cmd_status() { pg_require; flyway info; }

cmd_backup() { pg_require; TAKE_BACKUP=true; pg_backup; }

cmd_restore() {
  local file="${1:-}"
  [ -f "$file" ] || die "Usage: scripts/dev/db.sh restore backups/${DB_NAME}_<date>.dump" \
    "$(ls -1t "$BACKUP_DIR/${DB_NAME}"_*.dump 2>/dev/null | head -5 | sed 's/^/  /')"
  pg_require
  confirm "Replace ALL data in $DB_NAME with $file?"
  pg_backup
  step "Restoring $file"
  pg_restore_file "$file"
  echo "Restored. Run scripts/dev/db.sh to apply migrations newer than the backup."
}

cmd_repair() {
  pg_require
  echo "repair re-records the checksum of every applied migration from its current file and removes failed rows."
  echo "Use it only when the database already has the result of the edited file (or after fixing a failed one)."
  confirm "Repair the Flyway history of $DB_NAME?"
  pg_backup
  step "Repairing"
  flyway repair
  flyway_validate
}

cmd_reset() {
  pg_require
  echo "This DROPS every table in $DB_NAME on $DB_HOST:$DB_PORT and migrates an empty database."
  if ! $ASSUME_YES; then
    local answer
    read -r -p "Type the database name to confirm: " answer
    [ "$answer" = "$DB_NAME" ] || die "Stopped. Nothing was changed."
  fi
  pg_backup
  step "Dropping everything in $DB_NAME"
  flyway clean -Dflyway.cleanDisabled=false
  step "Migrating the empty database"
  flyway migrate
}

cmd_help() { awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0"; }

# ---- Entry point --------------------------------------------------------------------------------------------------
main() {
  local command=update args=()
  local arg
  for arg in "$@"; do
    case "$arg" in
      --yes | -y) ASSUME_YES=true ;;
      --no-backup) TAKE_BACKUP=false ;;
      --no-start) START_APP=false ;;
      --help | -h) command=help ;;
      -*) die "Unknown option: $arg (use --help)" ;;
      *) args+=("$arg") ;;
    esac
  done
  if [ ${#args[@]} -gt 0 ] && [ "$command" != help ]; then command="${args[0]}"; args=("${args[@]:1}"); fi
  declare -F "cmd_$command" >/dev/null || die "Unknown command: $command (use --help)"
  "cmd_$command" "${args[@]+"${args[@]}"}"
}

main "$@"
