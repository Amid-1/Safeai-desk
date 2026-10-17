#!/usr/bin/env bash
# SafeAI-Desk/infra/scripts/validate-prod.sh
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
infra_dir="$(cd -- "$script_dir/.." && pwd)"
repo_dir="$(cd -- "$infra_dir/.." && pwd)"
env_file="${1:-$repo_dir/backend/.env.prod}"
compose_file="$infra_dir/docker-compose.yml"

fail() { printf 'Production preflight failed: %s\n' "$*" >&2; exit 1; }

for cmd in docker grep stat sort awk; do
    command -v "$cmd" >/dev/null 2>&1 || fail "Missing command: $cmd"
done

docker compose version --short >/dev/null 2>&1 || fail "Docker Compose V2 is required"
compose_version="$(docker compose version --short | sed 's/^v//')"
minimum_compose_version="2.24.0"
if ! printf '%s\n%s\n' "$minimum_compose_version" "$compose_version" | sort -V -C; then
    fail "Docker Compose >= $minimum_compose_version required; found $compose_version"
fi
[[ -f "$env_file" && ! -L "$env_file" ]] || fail "Missing/unsafe env file: $env_file"

# Production environment files must be controlled by a trusted deploy operator.
# No secrets may be placed in this file: all secrets are file-backed.
set -a
# shellcheck disable=SC1090
source "$env_file"
set +a

env_file="$(cd -- "$(dirname -- "$env_file")" && pwd)/$(basename -- "$env_file")"
export SAFEAI_RUNTIME_ENV_FILE="$env_file"

required_variables=(
    SAFEAI_BACKEND_IMAGE SAFEAI_NGINX_IMAGE POSTGRES_IMAGE REDIS_IMAGE
    SAFEAI_PUBLIC_BIND SAFEAI_SECRETS_DIR SAFEAI_CONFIG_DIR SAFEAI_SECRET_GID
    POSTGRES_DB POSTGRES_BOOTSTRAP_USER SAFEAI_DB_MIGRATOR_USER SAFEAI_DB_APP_USER
    SAFEAI_AI_PROVIDER SAFEAI_KNOWLEDGE_EMBEDDING_PROVIDER
    SAFEAI_CORS_ALLOWED_ORIGINS SAFEAI_JWT_ISSUER SAFEAI_KNOWLEDGE_STORAGE_ENDPOINT
    SAFEAI_SECURITY_PASSWORD_ALLOW_LEGACY_UNPREFIXED_BCRYPT
    SAFEAI_SECURITY_CLIENT_IP_TRUSTED_PROXY_CIDRS
)
for name in "${required_variables[@]}"; do
    [[ -n "${!name:-}" ]] || fail "Required variable is empty: $name"
    [[ "${!name}" != *REPLACE_WITH* && "${!name}" != *REPLACE_ME* ]] || fail "Placeholder remains: $name"
done

# Catch forgotten placeholders even in a less common setting, but ignore comments.
placeholder_name="$(awk -F= '/^[[:space:]]*[A-Za-z_][A-Za-z_0-9]*=/ && $0 ~ /REPLACE_(WITH|ME)/ {print $1; exit}' "$env_file")"
[[ -z "$placeholder_name" ]] || fail "Placeholder remains in $placeholder_name"

[[ "${SPRING_PROFILES_ACTIVE:-}" == prod ]] || fail "SPRING_PROFILES_ACTIVE=prod required"
[[ "${SAFEAI_PUBLIC_BIND:-}" =~ ^127\.0\.0\.1:[0-9]{1,5}$ ]] || fail "SAFEAI_PUBLIC_BIND must be 127.0.0.1:<port>"
[[ "${SAFEAI_SECRET_GID:-}" == "21001" ]] || fail "SAFEAI_SECRET_GID=21001 required to match hardened image"
[[ "${SAFEAI_REQUIRE_IMMUTABLE_IMAGES:-}" == true ]] || fail "Immutable release images are mandatory"
[[ "${SAFEAI_CORS_ALLOWED_ORIGINS:-}" == https://* && "${SAFEAI_CORS_ALLOWED_ORIGINS:-}" != *REPLACE_ME* && "${SAFEAI_CORS_ALLOWED_ORIGINS:-}" != "*" ]] || fail "HTTPS CORS origin required"
[[ "${SAFEAI_JWT_ISSUER:-}" == https://* ]] || fail "HTTPS JWT issuer required"
[[ "${SAFEAI_AUTH_COOKIES_SECURE:-}" == true ]] || fail "HTTPS-only cookies required"
[[ "${SAFEAI_AUTH_COOKIES_DOMAIN:-}" == "" ]] || fail "Host-only cookies require blank Domain"
[[ "${SAFEAI_AUTH_ACCESS_COOKIE_NAME:-}" == "" && "${SAFEAI_AUTH_REFRESH_COOKIE_NAME:-}" == "" ]] || fail "Keep cookie name overrides blank for hardened generated names"
[[ "${SAFEAI_HSTS_ENABLED:-}" == false && "${SAFEAI_HSTS_EDGE_MANAGED:-}" == true ]] || fail "HSTS must be edge-managed for this Compose deployment"
[[ "${SAFEAI_RATE_LIMIT_LOGIN_ENABLED:-}" == true ]] || fail "Login limiter cannot be disabled"
[[ "${SAFEAI_RATE_LIMIT_REFRESH_ENABLED:-}" == true ]] || fail "Refresh limiter cannot be disabled"
[[ "${SAFEAI_RATE_LIMIT_AI_MESSAGES_ENABLED:-}" == true ]] || fail "Paid AI limiter cannot be disabled"
[[ "${SAFEAI_RATE_LIMIT_PRODUCTION_ALLOW_UNLIMITED_AI_TRAFFIC:-false}" == false ]] || fail "Unlimited AI escape hatch is forbidden"
[[ "${SAFEAI_AUTH_REUSE_DETECTION_RETENTION:-}" != "0d" ]] || fail "Refresh reuse evidence retention cannot be zero"
[[ "${SAFEAI_SECURITY_PASSWORD_ALLOW_LEGACY_UNPREFIXED_BCRYPT:-}" == false || "${SAFEAI_SECURITY_PASSWORD_ALLOW_LEGACY_UNPREFIXED_BCRYPT:-}" == true ]] || fail "Legacy password compatibility must be declared explicitly"

# These four image references are deployed directly; require cryptographic digests.
for name in SAFEAI_BACKEND_IMAGE SAFEAI_NGINX_IMAGE POSTGRES_IMAGE REDIS_IMAGE; do
    [[ "${!name}" =~ @sha256:[0-9a-fA-F]{64}$ ]] || fail "$name must be pinned to a 64-hex SHA256 digest"
done

pricing_file="$SAFEAI_CONFIG_DIR/pricing.yml"
[[ -f "$pricing_file" && ! -L "$pricing_file" ]] || fail "Missing/unsafe pricing config: $pricing_file"
pricing_uid="$(stat -c '%u' "$pricing_file")"
pricing_mode="$(stat -c '%a' "$pricing_file")"
[[ "$pricing_uid" == 0 ]] || fail "Pricing config must be owned by root"
(( (8#$pricing_mode & 8#022) == 0 )) || fail "Pricing config must not be writable by group/other"
! grep -Eq 'REPLACE_(WITH|ME)' "$pricing_file" || fail "Pricing config has placeholders"

secret_files=(
    postgres_bootstrap_password db_migrator_password db_app_password
    redis_password jwt-keys.yml rate_limit_hmac_secret
    openai_api_key anthropic_api_key knowledge_ocr_api_key
    knowledge_storage_access_key knowledge_storage_secret_key
)
for name in "${secret_files[@]}"; do
    file="$SAFEAI_SECRETS_DIR/$name"
    [[ -f "$file" && ! -L "$file" ]] || fail "Missing/unsafe secret file: $name"
    mode="$(stat -c '%a' "$file")"
    uid="$(stat -c '%u' "$file")"
    gid="$(stat -c '%g' "$file")"
    [[ "$mode" == 640 && "$uid" == 0 && "$gid" == "$SAFEAI_SECRET_GID" ]] || fail "Secret $name must be root:$SAFEAI_SECRET_GID with mode 0640"
done

for name in jwt-keys.yml knowledge_storage_access_key knowledge_storage_secret_key rate_limit_hmac_secret; do
    [[ -s "$SAFEAI_SECRETS_DIR/$name" ]] || fail "Required production secret empty: $name"
done
! grep -Eq 'REPLACE_(WITH|ME)' "$SAFEAI_SECRETS_DIR/jwt-keys.yml" || fail "JWT key ring has placeholders"

case "$SAFEAI_AI_PROVIDER" in
    openai)
        provider_secret="$SAFEAI_SECRETS_DIR/openai_api_key"
        selected_model="${OPENAI_MODEL:-}"
        ;;
    anthropic)
        provider_secret="$SAFEAI_SECRETS_DIR/anthropic_api_key"
        selected_model="${ANTHROPIC_MODEL:-}"
        ;;
    *) fail "Production provider must be openai or anthropic" ;;
esac
[[ -n "$selected_model" && -s "$provider_secret" ]] || fail "Selected provider model or secret missing"
grep -Fq -- "$selected_model" "$pricing_file" || fail "Selected provider model absent in pricing config"

case "${SAFEAI_KNOWLEDGE_EMBEDDING_PROVIDER:-}" in
    openai)
        [[ -s "$SAFEAI_SECRETS_DIR/openai_api_key" ]] || fail "OpenAI embedding credentials required"
        ;;
    *) fail "Production knowledge embedding provider must be openai in this deployment" ;;
esac

# The rules directory is mounted into the Prometheus container by Compose.
rules="$infra_dir/prometheus/rules/safeai-audit-outbox.yml"
conf="$infra_dir/prometheus/prometheus.yml"
[[ -s "$rules" && -s "$conf" ]] || fail "Prometheus audit rules/config missing"
grep -Fq 'SafeAiAuditDeadLettersPresent' "$rules" || fail "Critical dead-letter alert missing"
grep -Fq '/etc/prometheus/rules/*.yml' "$conf" || fail "Rule loader is not configured"
grep -Fq 'backend:9091' "$conf" || fail "Backend management scrape target missing"

# Syntax lint is mandatory at release time, not dependent on external internet.
# In deployments without a host promtool, run the documented container check
# with the pinned Prometheus image BEFORE deploying changes.
if command -v promtool >/dev/null 2>&1; then
    # The host does not mount /etc/prometheus/rules from the container;
    # config lint must run inside the Prometheus image (see README).
    promtool check rules "$rules" >/dev/null || fail "Prometheus rules are invalid"
else
    printf 'WARNING: promtool unavailable on host; run the container-based promtool checks before deployment.\n' >&2
fi

docker compose --env-file "$env_file" -f "$compose_file" config --quiet || fail "Docker Compose configuration invalid"
printf 'Production configuration preflight passed (Alertmanager delivery must be separately verified).\n'
