#!/usr/bin/env bash
# SafeAI-Desk/infra/scripts/check-prometheus-config.sh
# Fail-closed Prometheus config+rules verification using the very image selected
# for production, or the compose-local default for developer checks.
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
infra_dir="$(cd -- "$script_dir/.." && pwd)"
prometheus_dir="$infra_dir/prometheus"
prometheus_image="${SAFEAI_PROMETHEUS_IMAGE:-prom/prometheus:v3.13.2}"

fail() { printf 'Prometheus preflight failed: %s\n' "$*" >&2; exit 1; }
command -v docker >/dev/null 2>&1 || fail 'Docker is required to run promtool from the Prometheus image'
[[ -s "$prometheus_dir/prometheus.yml" ]] || fail 'Missing prometheus.yml'
[[ -s "$prometheus_dir/rules/safeai-audit-outbox.yml" ]] || fail 'Missing canonical audit rules'
[[ -s "$prometheus_dir/rules/safeai-knowledge-review.yml" ]] || fail 'Missing storage review rules'
[[ -s "$prometheus_dir/rules/safeai-knowledge-erasure.yml" ]] || fail 'Missing erasure rules'
[[ -s "$prometheus_dir/tests/knowledge-erasure.test.yml" ]] || fail 'Missing erasure alert unit tests'
[[ "$prometheus_image" != *REPLACE_ME* && "$prometheus_image" != *REPLACE_WITH* ]] || fail 'Prometheus image is a placeholder'
if [[ "${SAFEAI_REQUIRE_IMMUTABLE_IMAGES:-false}" == true ]]; then
    [[ "$prometheus_image" =~ @sha256:[0-9a-fA-F]{64}$ ]] || fail 'Production Prometheus image must be pinned by digest'
fi

# Do not grant network access, writable mounts, capabilities or privileges to lint.
# promtool check config parses every rule_files entry; --lint-fatal rejects lint issues.
docker run --rm \
    --network none --read-only --tmpfs /tmp:rw,nosuid,noexec,size=64m,mode=1777 \
    --cap-drop ALL --security-opt no-new-privileges \
    --mount "type=bind,source=$prometheus_dir,target=/etc/prometheus,readonly" \
    --entrypoint /bin/promtool \
    "$prometheus_image" \
    check config --lint-fatal /etc/prometheus/prometheus.yml \
    || fail 'promtool check config failed'

docker run --rm \
    --network none --read-only --tmpfs /tmp:rw,nosuid,noexec,size=64m,mode=1777 \
    --cap-drop ALL --security-opt no-new-privileges \
    --mount "type=bind,source=$prometheus_dir,target=/etc/prometheus,readonly" \
    --entrypoint /bin/promtool \
    "$prometheus_image" \
    test rules /etc/prometheus/tests/knowledge-erasure.test.yml \
    || fail 'Prometheus erasure alert unit tests failed'

printf 'Prometheus configuration, canonical rules and erasure unit tests verified.\n'
