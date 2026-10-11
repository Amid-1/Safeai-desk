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
[[ "$EUID" -eq 0 ]] || {
    echo "Production secrets must be initialized as root on the deployment host" >&2
    exit 1
}
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
    [[ ! -L "$file" ]] || {
        echo "Symlink is not allowed for secret: $file" >&2
        exit 1
    }
    if [[ -e "$file" ]]; then
        secure_file "$file"
        echo "Keeping existing secret: $file"
        return
    fi
    openssl rand -base64 "$bytes" | tr -d '\r\n' > "$file"
    secure_file "$file"
    echo "Created: $file"
}

create_jwt_keyring() (
    local target="$secrets_dir/jwt-keys.yml"

    if [[ -e "$target" || -L "$target" ]]; then
        [[ -f "$target" && ! -L "$target" && -s "$target" ]] || {
            echo "Existing JWT key ring is invalid: $target" >&2
            exit 1
        }
        secure_file "$target"
        echo "Keeping existing JWT key ring: $target"
        return 0
    fi

    local work
    work="$(mktemp -d "$secrets_dir/.jwt-init.XXXXXXXX")"
    trap 'rm -rf -- "$work"' EXIT

    openssl genpkey -algorithm RSA \
        -pkeyopt rsa_keygen_bits:3072 \
        -out "$work/private.pem"

    openssl pkey \
        -in "$work/private.pem" \
        -pubout \
        -out "$work/public.pem"

    local key_id
    key_id="prod-$(date -u +%Y%m%dT%H%M%SZ)"

    {
        printf 'app:\n  security:\n    jwt:\n'
        printf '      active-key-id: %s\n' "$key_id"
        printf '      keys:\n        - id: %s\n' "$key_id"
        printf '          public-key: |-\n'
        sed 's/^/            /' "$work/public.pem"
        printf '          private-key: |-\n'
        sed 's/^/            /' "$work/private.pem"
    } > "$work/jwt-keys.yml"

    chown root:"$secret_gid" "$work/jwt-keys.yml"
    chmod 0640 "$work/jwt-keys.yml"

    [[ ! -e "$target" && ! -L "$target" ]] || {
        echo "JWT key ring appeared during initialization: $target" >&2
        exit 1
    }

    mv -- "$work/jwt-keys.yml" "$target"
    echo "Created RSA JWT key ring: $target"
)

write_random_secret postgres_bootstrap_password 48
write_random_secret db_migrator_password 48
write_random_secret db_app_password 48
write_random_secret redis_password 48
create_jwt_keyring
write_random_secret rate_limit_hmac_secret 64

for name in     openai_api_key     anthropic_api_key     knowledge_ocr_api_key     knowledge_storage_access_key     knowledge_storage_secret_key
do
    file="$secrets_dir/$name"

    [[ ! -L "$file" ]] || {
        echo "Symlink is not allowed for secret: $file" >&2
        exit 1
    }

    if [[ ! -e "$file" ]]; then
        install -m 0640 -o root -g "$secret_gid" /dev/null "$file"
    else
        [[ -f "$file" ]] || {
            echo "Secret path is not a regular file: $file" >&2
            exit 1
        }
        secure_file "$file"
    fi
done
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

for name in knowledge_storage_access_key knowledge_storage_secret_key; do
    if [[ ! -s "$secrets_dir/$name" ]]; then
        echo "ACTION REQUIRED: configure S3 credential: $name" >&2
    fi
done

echo "Secrets initialized in: $secrets_dir"
echo "Owner: root, group: $secret_gid, mode: 0640"
