#!/usr/bin/env sh
set -eu

if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
  echo "usage: $0 <backup.sql>" >&2
  exit 2
fi

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
ENV_FILE=${ENV_FILE:-"$ROOT_DIR/deploy/.env"}
COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-meterbill}
BACKUP_FILE=$(CDPATH= cd -- "$(dirname -- "$1")" && pwd)/$(basename -- "$1")
REPORT="$BACKUP_FILE.restore-report.txt"
COUNTS_SQL="SELECT (SELECT COUNT(*) FROM mb_tenant),(SELECT COUNT(*) FROM mb_user),(SELECT COUNT(*) FROM mb_subscription),(SELECT COUNT(*) FROM mb_usage_event),(SELECT COUNT(*) FROM mb_invoice),(SELECT COUNT(*) FROM mb_ledger_transaction),(SELECT COUNT(*) FROM mb_reconciliation_difference),(SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1);"

SOURCE=$(docker compose -p "$COMPOSE_PROJECT_NAME" --env-file "$ENV_FILE" -f "$ROOT_DIR/deploy/compose.yaml" exec -T mysql \
  sh -c 'mysql -N -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -e "$0"' "$COUNTS_SQL")

cleanup() {
  docker compose -p "$COMPOSE_PROJECT_NAME" --env-file "$ENV_FILE" -f "$ROOT_DIR/deploy/compose.yaml" exec -T mysql \
    sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "DROP DATABASE IF EXISTS meterbill_restore_drill;"' \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

docker compose -p "$COMPOSE_PROJECT_NAME" --env-file "$ENV_FILE" -f "$ROOT_DIR/deploy/compose.yaml" exec -T mysql \
  sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "DROP DATABASE IF EXISTS meterbill_restore_drill; CREATE DATABASE meterbill_restore_drill CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"'
docker compose -p "$COMPOSE_PROJECT_NAME" --env-file "$ENV_FILE" -f "$ROOT_DIR/deploy/compose.yaml" exec -T mysql \
  sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" meterbill_restore_drill' < "$BACKUP_FILE"

RESTORED=$(docker compose -p "$COMPOSE_PROJECT_NAME" --env-file "$ENV_FILE" -f "$ROOT_DIR/deploy/compose.yaml" exec -T mysql \
  sh -c 'mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" meterbill_restore_drill -e "$0"' "$COUNTS_SQL")

{
  echo "backup=$BACKUP_FILE"
  echo "source=$SOURCE"
  echo "restored=$RESTORED"
  if [ "$SOURCE" = "$RESTORED" ]; then echo "result=PASS"; else echo "result=FAIL"; fi
} > "$REPORT"

test "$SOURCE" = "$RESTORED"
printf '%s\n' "$REPORT"
