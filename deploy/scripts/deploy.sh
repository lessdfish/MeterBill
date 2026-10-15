#!/usr/bin/env sh
set -eu
ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
ENV_FILE=${ENV_FILE:-"$ROOT_DIR/deploy/.env"}
COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-meterbill}
NEW_VERSION=${1:?usage: deploy.sh <version>}
PREVIOUS_VERSION=$(sed -n 's/^METERBILL_VERSION=//p' "$ENV_FILE" | head -n 1)
HTTP_PORT=$(sed -n 's/^METERBILL_HTTP_PORT=//p' "$ENV_FILE" | head -n 1)
HTTP_PORT=${HTTP_PORT:-18080}

if [ -z "$PREVIOUS_VERSION" ]; then
  echo "METERBILL_VERSION is missing from $ENV_FILE" >&2
  exit 2
fi

BACKUP_DIR="$ROOT_DIR/deploy/backups" ENV_FILE="$ENV_FILE" COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT_NAME" sh "$ROOT_DIR/deploy/scripts/backup.sh"
sed -i "s/^METERBILL_VERSION=.*/METERBILL_VERSION=$NEW_VERSION/" "$ENV_FILE"

if docker compose -p "$COMPOSE_PROJECT_NAME" --env-file "$ENV_FILE" -f "$ROOT_DIR/deploy/compose.yaml" pull && \
   docker compose -p "$COMPOSE_PROJECT_NAME" --env-file "$ENV_FILE" -f "$ROOT_DIR/deploy/compose.yaml" up -d --remove-orphans && \
   sh "$ROOT_DIR/deploy/scripts/smoke.sh" "http://127.0.0.1:$HTTP_PORT"; then
  echo "DEPLOY PASS version=$NEW_VERSION"
else
  echo "DEPLOY FAILED; rolling back to $PREVIOUS_VERSION" >&2
  sed -i "s/^METERBILL_VERSION=.*/METERBILL_VERSION=$PREVIOUS_VERSION/" "$ENV_FILE"
  docker compose -p "$COMPOSE_PROJECT_NAME" --env-file "$ENV_FILE" -f "$ROOT_DIR/deploy/compose.yaml" up -d --remove-orphans
  sh "$ROOT_DIR/deploy/scripts/smoke.sh" "http://127.0.0.1:$HTTP_PORT"
  exit 1
fi
