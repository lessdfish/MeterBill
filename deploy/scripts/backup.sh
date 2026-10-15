#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
ENV_FILE=${ENV_FILE:-"$ROOT_DIR/deploy/.env"}
COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-meterbill}
BACKUP_DIR=${BACKUP_DIR:-"$ROOT_DIR/deploy/backups"}
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
BACKUP_FILE="$BACKUP_DIR/meterbill-$STAMP.sql"

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"
docker compose -p "$COMPOSE_PROJECT_NAME" --env-file "$ENV_FILE" -f "$ROOT_DIR/deploy/compose.yaml" exec -T mysql \
  sh -c 'exec mysqldump --single-transaction --routines --triggers --no-tablespaces --set-gtid-purged=OFF -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"' \
  > "$BACKUP_FILE"
test -s "$BACKUP_FILE"
sha256sum "$BACKUP_FILE" > "$BACKUP_FILE.sha256"
chmod 600 "$BACKUP_FILE" "$BACKUP_FILE.sha256"
printf '%s\n' "$BACKUP_FILE"
