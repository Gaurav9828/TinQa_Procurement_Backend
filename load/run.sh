#!/bin/sh
# Load / stress tests against a running environment (see load/k6.js for the profiles and the variables it reads).
#   load/run.sh <smoke|load|stress|spike|soak> <API base URL>
# Uses a local k6 if installed, otherwise the k6 Docker image. The summary goes to target/load/<profile>.json.
set -e
PROFILE=${1:?profile: smoke | load | stress | spike | soak}
BASE_URL=${2:?API base URL, e.g. https://uat-api.example.com/api}
export PROFILE BASE_URL
DIR=$(cd "$(dirname "$0")" && pwd)
OUT="$DIR/../target/load"
mkdir -p "$OUT"
if command -v k6 >/dev/null 2>&1; then
  exec k6 run --summary-export "$OUT/$PROFILE.json" "$DIR/k6.js"
fi
exec docker run --rm -i --network host \
  -e PROFILE -e BASE_URL -e ALLOW_PRODUCTION -e K6_EMAIL -e K6_PASSWORD -e K6_USERNAME \
  -v "$DIR:/load:ro" -v "$OUT:/out" \
  grafana/k6:1.3.0 run --summary-export "/out/$PROFILE.json" /load/k6.js
