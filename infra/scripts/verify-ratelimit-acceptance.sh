#!/usr/bin/env bash
# Local Redis-only matrix. Never use an external production Redis endpoint.
set -Eeuo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
gate="$root/.github/scripts/check-ratelimit-acceptance.py"
python3 -m unittest discover -s "$root/.github/scripts" -p 'test_ratelimit_acceptance.py' -v
docker info --format '{{.ServerVersion}}'
classes="$(python3 "$gate" --classes)"
redis_args=()
if [[ -n "${SAFEAI_RATELIMIT_TEST_REDIS_IMAGE:-}" ]]; then
    redis_args+=("-Dsafeai.ratelimit.test.redis-image=$SAFEAI_RATELIMIT_TEST_REDIS_IMAGE")
fi
(
    cd "$root/backend"
    ./mvnw --batch-mode --no-transfer-progress "-Dtest=$classes" "${redis_args[@]}" clean test
)
python3 "$gate" "$root/backend/target/surefire-reports"
