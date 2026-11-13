#!/usr/bin/env bash
# SafeAI-Desk/infra/scripts/check-config-topology.sh
# Read-only configuration topology checks; never reads secrets or env values.
set -Eeuo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
infra_dir="$(cd -- "$script_dir/.." && pwd)"
root_dir="$(cd -- "$infra_dir/.." && pwd)"
fail() { printf 'Configuration topology failure: %s\n' "$*" >&2; exit 1; }

canonical="$infra_dir/prometheus/rules/safeai-knowledge-erasure.yml"
audit="$infra_dir/prometheus/rules/safeai-audit-outbox.yml"
config="$infra_dir/prometheus/prometheus.yml"
compose="$infra_dir/docker-compose.yml"
local_compose="$infra_dir/docker-compose.local.yml"
validator="$infra_dir/scripts/validate-prod.sh"
deployer="$infra_dir/scripts/deploy-prod.sh"
example="$root_dir/backend/.env.prod.example"

for filename in "$canonical" "$audit" "$config" "$compose" "$local_compose" "$validator" "$deployer" "$example"; do
    [[ -s "$filename" ]] || fail "Required file is absent or empty: $filename"
done

grep -Fq '/etc/prometheus/rules/*.yml' "$config" || fail 'Canonical Prometheus rule_files glob is absent'
grep -Fq 'source: ./prometheus/rules' "$compose" || fail 'Production Compose does not mount canonical rule directory'
grep -Fq 'source: ./prometheus/rules' "$local_compose" || fail 'Local Compose does not mount canonical rule directory'
grep -Fq 'SafeAIKnowledgeErasureNeedsReview' "$canonical" || fail 'Missing needs-review alert'
grep -Fq 'SafeAIKnowledgeErasureRetentionBlocked' "$canonical" || fail 'Missing verified-retention alert'
grep -Fq 'SafeAIKnowledgeErasureMetricsAbsent' "$canonical" || fail 'Missing absent-metrics alert'
grep -Fq 'SafeAiAuditDeadLettersPresent' "$audit" || fail 'Missing generic dead-letter alert'
if grep -Eq '^[[:space:]]*- alert: .*Audit.*(Dead|Letter)' "$canonical"; then
    fail 'Dead-letter alert duplicates generic audit rules'
fi
if grep -Eq '^SAFEAI_AUTH_(ACCESS|REFRESH)_COOKIE_NAME=[^[:space:]#]+' "$example"; then
    fail 'Production template overrides hardened auth cookie names'
fi
if grep -Fq 'SafeAIKnowledgeErasureNeedsReview' "$root_dir/backend/deploy/prometheus/rules/safeai-audit-outbox.yml" 2>/dev/null; then
    fail 'Erasure alert is duplicated under the obsolete backend/deploy tree'
fi
printf 'Canonical config layout verified. Legacy backend/deploy examples (if present) were not modified.\n'
