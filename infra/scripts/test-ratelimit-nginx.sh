#!/usr/bin/env bash
# Isolated HTTP contract; does not access deployment containers or secrets.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
nginx_image="$(sed -n 's/^ARG NGINX_IMAGE=//p' "$root/infra/nginx/Dockerfile")"
test -n "$nginx_image"
client_image="${SAFEAI_RATE_LIMIT_TEST_CLIENT_IMAGE:-python:3.13-alpine}"
tmp="$(mktemp -d "$root/infra/tests/ratelimit/.edge-test.XXXXXXXX")"
name="safeai-ratelimit-test-$(basename "$tmp" | tr -cd '[:alnum:]')"
network="$name-net"
cleanup() {
    docker rm -f "$name-edge" "$name-backend" "$name-trusted" "$name-untrusted" >/dev/null 2>&1 || true
    docker network rm "$network" >/dev/null 2>&1 || true
    rm -f "$tmp/edge.conf"
    rmdir "$tmp"
}
trap cleanup EXIT
docker network create "$network" >/dev/null
docker run -d --name "$name-backend" --network "$network" --network-alias backend \
    --mount "type=bind,src=$root/infra/tests/ratelimit/nginx-backend.conf,dst=/etc/nginx/conf.d/default.conf,readonly" \
    "$nginx_image" >/dev/null
for role in trusted untrusted; do
    docker run -d --name "$name-$role" --network "$network" \
        --mount "type=bind,src=$root/infra/tests/ratelimit/nginx-edge-probe.py,dst=/probe.py,readonly" \
        "$client_image" sleep 300 >/dev/null
done
trusted_ip="$(docker inspect -f "{{(index .NetworkSettings.Networks \"$network\").IPAddress}}" "$name-trusted")"
test -n "$trusted_ip"
grep -qx 'set_real_ip_from 172\.28\.0\.1;' "$root/infra/nginx/nginx.conf"
# The production trust boundary is one specific IP. Only substitute the
# isolated fixture's trusted client IP, never broaden to the whole subnet.
sed "s/^set_real_ip_from 172\.28\.0\.1;$/set_real_ip_from $trusted_ip;/" \
    "$root/infra/nginx/nginx.conf" > "$tmp/edge.conf"
chmod 644 "$tmp/edge.conf"
docker run -d --name "$name-edge" --network "$network" --network-alias edge \
    --mount "type=bind,src=$tmp/edge.conf,dst=/etc/nginx/conf.d/default.conf,readonly" \
    --mount "type=bind,src=$root/infra/nginx/security-headers.conf,dst=/etc/nginx/snippets/safeai-security-headers.conf,readonly" \
    "$nginx_image" >/dev/null
docker exec "$name-edge" nginx -t
docker exec "$name-trusted" python /probe.py ready
docker exec "$name-trusted" python /probe.py trusted
# Allow the aggregate bucket to drain; independent per-IP spoof test.
sleep 4
docker exec "$name-untrusted" python /probe.py untrusted
