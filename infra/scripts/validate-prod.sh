#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
infra_dir="$(cd -- "$script_dir/.." && pwd)"
repo_dir="$(cd -- "$infra_dir/.." && pwd)"
env_file="${1:-$repo_dir/backend/.env.prod}"
compose_file="$infra_dir/docker-compose.yml"

for cmd in docker grep stat sort; do
    command -v "$cmd" >/dev/null 2>&1 || { echo "Required command is missing: $cmd" >&2; exit 1; }
done
compose_version="$(docker compose version --short | sed 's/^v//')"
minimum_compose_version="2.24.0"
if ! printf '%s\n%s\n' "$minimum_compose_version" "$compose_version" \
        | sort -V -C; then
    echo "Docker Compose >= $minimum_compose_version is required; found $compose_version" >&2
    exit 1
fi
[[ -f "$env_file" ]] || { echo "Production env file does not exist: $env_file" >&2; exit 1; }

set -a
# shellcheck disable=SC1090
source "$env_file"
set +a

required_variables=(
    SAFEAI_BACKEND_IMAGE SAFEAI_NGINX_IMAGE POSTGRES_IMAGE REDIS_IMAGE
    SAFEAI_PUBLIC_BIND SAFEAI_SECRETS_DIR SAFEAI_CONFIG_DIR SAFEAI_SECRET_GID POSTGRES_DB POSTGRES_BOOTSTRAP_USER
    SAFEAI_DB_MIGRATOR_USER SAFEAI_DB_APP_USER SAFEAI_AI_PROVIDER
    SAFEAI_CORS_ALLOWED_ORIGINS
)
for name in "${required_variables[@]}"; do
    [[ -n "${!name:-}" ]] || { echo "Required variable is empty: $name" >&2; exit 1; }
    [[ "${!name}" != *REPLACE_WITH* ]] || { echo "Placeholder remains in $name" >&2; exit 1; }
done

[[ "$SAFEAI_PUBLIC_BIND" == 127.0.0.1:* ]] || {
    echo "SAFEAI_PUBLIC_BIND must be loopback-only: 127.0.0.1:<port>" >&2
    exit 1
}
[[ "$SAFEAI_SECRET_GID" == "21001" ]] || {
    echo "SAFEAI_SECRET_GID must be 21001 to match the hardened PostgreSQL image" >&2
    exit 1
}
[[ "$SAFEAI_CORS_ALLOWED_ORIGINS" == https://* ]] || {
    echo "Production CORS origins must use https" >&2
    exit 1
}
[[ "$SAFEAI_CORS_ALLOWED_ORIGINS" != *your-domain.com* && "$SAFEAI_CORS_ALLOWED_ORIGINS" != "*" ]] || {
    echo "Replace the CORS placeholder with the real HTTPS origin" >&2
    exit 1
}
[[ "${SAFEAI_AUTH_COOKIES_SECURE:-}" == "true" ]] || {
    echo "SAFEAI_AUTH_COOKIES_SECURE must be true in production" >&2
    exit 1
}
[[ "${SAFEAI_HSTS_ENABLED:-}" == "true" ]] || {
    echo "SAFEAI_HSTS_ENABLED must be true for HTTPS API responses" >&2
    exit 1
}

pricing_file="$SAFEAI_CONFIG_DIR/pricing.yml"
[[ -f "$pricing_file" ]] || {
    echo "Missing production pricing config: $pricing_file" >&2
    exit 1
}
pricing_uid="$(stat -c '%u' "$pricing_file")"
pricing_mode="$(stat -c '%a' "$pricing_file")"
[[ "$pricing_uid" == "0" ]] || {
    echo "Production pricing config must be owned by root: $pricing_file (uid=$pricing_uid)" >&2
    exit 1
}
(( (8#$pricing_mode & 8#022) == 0 )) || {
    echo "Production pricing config must not be group/world writable: $pricing_file ($pricing_mode)" >&2
    exit 1
}
if grep -q 'REPLACE_WITH' "$pricing_file"; then
    echo "Pricing placeholders remain in: $pricing_file" >&2
    exit 1
fi

if [[ "${SAFEAI_REQUIRE_IMMUTABLE_IMAGES:-true}" == "true" ]]; then
    for name in SAFEAI_BACKEND_IMAGE SAFEAI_NGINX_IMAGE POSTGRES_IMAGE REDIS_IMAGE; do
        [[ "${!name}" == *@sha256:* ]] || {
            echo "$name must contain @sha256 when immutable images are required" >&2
            exit 1
        }
    done
fi

secret_files=(
    postgres_bootstrap_password db_migrator_password db_app_password
    redis_password jwt_secret rate_limit_hmac_secret openai_api_key anthropic_api_key
)
for name in "${secret_files[@]}"; do
    file="$SAFEAI_SECRETS_DIR/$name"
    [[ -f "$file" ]] || { echo "Missing secret file: $file" >&2; exit 1; }
    mode="$(stat -c '%a' "$file")"
    uid="$(stat -c '%u' "$file")"
    gid="$(stat -c '%g' "$file")"
    [[ "$mode" == "640" ]] || {
        echo "Secret must have mode 0640: $file ($mode)" >&2
        exit 1
    }
    [[ "$uid" == "0" ]] || {
        echo "Secret must be owned by root: $file (uid=$uid)" >&2
        exit 1
    }
    [[ "$gid" == "$SAFEAI_SECRET_GID" ]] || {
        echo "Secret must have gid $SAFEAI_SECRET_GID: $file ($gid)" >&2
        exit 1
    }
done

case "$SAFEAI_AI_PROVIDER" in
    openai)
        provider_secret="$SAFEAI_SECRETS_DIR/openai_api_key"
        selected_model="${OPENAI_MODEL:-}"
        [[ -n "$selected_model" ]] || { echo "OPENAI_MODEL is required" >&2; exit 1; }
        ;;
    anthropic)
        provider_secret="$SAFEAI_SECRETS_DIR/anthropic_api_key"
        selected_model="${ANTHROPIC_MODEL:-}"
        [[ -n "$selected_model" ]] || { echo "ANTHROPIC_MODEL is required" >&2; exit 1; }
        ;;
    *) echo "Production provider must be openai or anthropic" >&2; exit 1 ;;
esac
[[ -s "$provider_secret" ]] || { echo "Selected provider secret is empty: $provider_secret" >&2; exit 1; }
grep -Fq "$selected_model" "$pricing_file" || {
    echo "Selected model is absent from production pricing config: $selected_model" >&2
    exit 1
}

docker compose --env-file "$env_file" -f "$compose_file" config --quiet
echo "Production configuration validation passed."
