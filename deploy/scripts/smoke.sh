#!/usr/bin/env sh
set -eu
BASE_URL=${1:-http://127.0.0.1:18080}
test "$(curl -fsS "$BASE_URL/gateway-health")" = "ok"
curl -fsS "$BASE_URL/actuator/health/liveness" | grep -q '"status":"UP"'
curl -fsS "$BASE_URL/actuator/health/readiness" | grep -q '"status":"UP"'
curl -fsS "$BASE_URL/" | grep -q '<div id="app"></div>'
echo "SMOKE PASS $BASE_URL"
