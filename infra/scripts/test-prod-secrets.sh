#!/usr/bin/env bash
set -Eeuo pipefail

repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
init_script="$repo_dir/infra/scripts/init-prod-secrets.sh"
image="${1:-safeai-postgres:contract-test}"

command -v docker >/dev/null 2>&1 || { echo 'Docker is required' >&2; exit 1; }
[[ -f "$init_script" ]] || { echo "Missing $init_script" >&2; exit 1; }
docker image inspect "$image" >/dev/null 2>&1 || {
    echo "Missing test Docker image: $image" >&2
    exit 1
}

docker run --rm \
    --network none \
    --read-only \
    --user 0:0 \
    --tmpfs /run/test-secrets:size=8m,mode=0700 \
    --tmpfs /tmp:size=4m,mode=0700 \
    --mount "type=bind,src=$init_script,dst=/init-prod-secrets.sh,readonly" \
    --entrypoint /bin/bash \
    "$image" -ceu '
        secrets=/run/test-secrets
        command -v openssl >/dev/null
        command -v sha256sum >/dev/null
        printf "%s\n" "test-provider-key-not-real" > "$secrets/openai_api_key"

        bash /init-prod-secrets.sh "$secrets" openai > /tmp/first.log 2>&1 || {
            cat /tmp/first.log >&2
            exit 1
        }
        echo "PASS: initial production secret generation"

        for name in postgres_bootstrap_password db_migrator_password \
                    db_app_password redis_password rate_limit_hmac_secret \
                    jwt-keys.yml openai_api_key anthropic_api_key \
                    knowledge_ocr_api_key knowledge_storage_access_key \
                    knowledge_storage_secret_key; do
            file="$secrets/$name"
            test -f "$file"
            test ! -L "$file"
            test "$(stat -c %a "$file")" = 640
            test "$(stat -c %u "$file")" = 0
            test "$(stat -c %g "$file")" = 21001
        done
        echo "PASS: secret files and root:21001/0640 permissions"

        ring="$secrets/jwt-keys.yml"
        awk "/^          public-key: [|]-$/ {take=1;next} /^          private-key: [|]-$/ {take=0} take {sub(/^            /,\"\");print}" \
            "$ring" > /tmp/public.pem
        awk "/^          private-key: [|]-$/ {take=1;next} take {sub(/^            /,\"\");print}" \
            "$ring" > /tmp/private.pem

        openssl pkey -in /tmp/private.pem -check -noout > /dev/null 2>&1
        key_description="$(openssl pkey -in /tmp/private.pem -text -noout | sed -n "1p")"
        [[ "$key_description" == "Private-Key: (3072 bit"* ]] || {
            echo "FAIL: expected a 3072-bit RSA private key" >&2
            exit 1
        }
        openssl pkey -in /tmp/private.pem -pubout -out /tmp/derived-public.pem > /dev/null 2>&1
        cmp -s /tmp/public.pem /tmp/derived-public.pem
        echo "PASS: valid RSA-3072 private/public key pair in YAML"

        first_ring="$(sha256sum "$ring" | cut -d " " -f1)"
        first_db="$(sha256sum "$secrets/db_app_password" | cut -d " " -f1)"
        bash /init-prod-secrets.sh "$secrets" openai > /tmp/second.log 2>&1 || {
            cat /tmp/second.log >&2
            exit 1
        }
        test "$first_ring" = "$(sha256sum "$ring" | cut -d " " -f1)"
        test "$first_db" = "$(sha256sum "$secrets/db_app_password" | cut -d " " -f1)"
        echo "PASS: repeat initialization preserves JWT and DB secrets"

        rm "$secrets/knowledge_ocr_api_key"
        ln -s "$secrets/openai_api_key" "$secrets/knowledge_ocr_api_key"
        if bash /init-prod-secrets.sh "$secrets" openai > /tmp/symlink.log 2>&1; then
            echo "FAIL: symlink secret was accepted" >&2
            exit 1
        fi
        echo "PASS: symlink secret is rejected"
        echo "PASS: isolated production secrets integration test complete"
    '
