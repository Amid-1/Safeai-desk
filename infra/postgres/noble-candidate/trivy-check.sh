#!/usr/bin/env bash
set -Eeuo pipefail
image="${1:-safeai-postgres:noble-test}"
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p "$here/reports"
set +e
docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v safeai_trivy_cache:/root/.cache \
  aquasec/trivy:0.76.2 image --image-src docker --scanners vuln \
  --severity CRITICAL --ignore-unfixed=false --exit-code 1 \
  --format table "$image" > "$here/reports/noble-critical.log" 2>&1
critical=$?
docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v safeai_trivy_cache:/root/.cache \
  aquasec/trivy:0.76.2 image --image-src docker --scanners vuln \
  --severity HIGH,CRITICAL --ignore-unfixed=true --exit-code 1 \
  --format table "$image" > "$here/reports/noble-high-critical.log" 2>&1
high=$?
set -e
printf 'CRITICAL exit code: %s\nHIGH/CRITICAL fixed exit code: %s\n' "$critical" "$high"
tail -n 80 "$here/reports/noble-critical.log"
if [[ $critical -ne 0 || $high -ne 0 ]]; then
  echo "FAIL: blocking Trivy findings remain (reports in $here/reports)" >&2
  exit 1
fi
echo 'PASS: both vulnerability gates'
