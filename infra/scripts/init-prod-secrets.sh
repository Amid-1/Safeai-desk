#!/usr/bin/env bash
set -Eeuo pipefail

secrets_dir="${1:-/etc/safeai/secrets}"
provider="${2:-openai}"
secret_gid="${SAFEAI_SECRET_GID:-21001}"
case "$provider" in
    openai|anthropic) ;;
    *) echo "Usage: $0 [secrets-dir] [openai|anthropic]" >&2; exit 1 ;;
esac
[[ "$secret_gid" =~ ^[0-9]+$ ]] || { echo "SAFEAI_SECRET_GID must be numeric" >&2; exit 1; }
command -v openssl >/dev/null 2>&1 || { echo "openssl is required" >&2; exit 1; }

umask 077
install -d -m 0750 -o root -g "$secret_gid" "$secrets_dir"

secure_file() {
    local file="$1"
    chown root:"$secret_gid" "$file"
    chmod 0640 "$file"
}

write_random_secret() {
    local name="$1"
    local bytes="$2"
    local file="$secrets_dir/$name"
    if [[ -e "$file" ]]; then
        secure_file "$file"
        echo "Keeping existing secret: $file"
        return
    fi
    openssl rand -base64 "$bytes" | tr -d '\r\n' > "$file"
    secure_file "$file"
    echo "Created: $file"
}

write_random_secret postgres_bootstrap_password 48
write_random_secret db_migrator_password 48
write_random_secret db_app_password 48
write_random_secret redis_password 48
write_random_secret jwt_secret 64
write_random_secret rate_limit_hmac_secret 64

touch "$secrets_dir/openai_api_key" "$secrets_dir/anthropic_api_key"
secure_file "$secrets_dir/openai_api_key"
secure_file "$secrets_dir/anthropic_api_key"
selected_file="$secrets_dir/${provider}_api_key"

if [[ ! -s "$selected_file" ]]; then
    if [[ ! -t 0 ]]; then
        echo "Set the provider API key in: $selected_file" >&2
        exit 1
    fi
    read -r -s -p "Enter ${provider} API key: " provider_key
    echo
    [[ -n "$provider_key" ]] || { echo "API key must not be empty" >&2; exit 1; }
    printf '%s' "$provider_key" > "$selected_file"
    secure_file "$selected_file"
fi

echo "Secrets initialized in: $secrets_dir"
echo "Owner: root, group: $secret_gid, mode: 0640"
