#!/usr/bin/env bash
set -Eeuo pipefail

base_url="${1:-http://127.0.0.1:8080}"
work_dir="$(mktemp -d)"

cleanup() {
    rm -rf "$work_dir"
}

trap cleanup EXIT

frontend_headers="$work_dir/frontend.headers"
frontend_body="$work_dir/frontend.body"
api_headers="$work_dir/api.headers"
api_body="$work_dir/api.body"

frontend_status="$(
    curl \
        --silent \
        --show-error \
        --output "$frontend_body" \
        --dump-header "$frontend_headers" \
        --write-out '%{http_code}' \
        "$base_url/admin/audit"
)"

if [[ "$frontend_status" != "200" ]]; then
    echo "Expected /admin/audit to return 200, got $frontend_status" >&2
    exit 1
fi

grep -Eiq '^content-type:.*text/html' "$frontend_headers" || {
    echo "/admin/audit did not return text/html" >&2
    exit 1
}

grep -Fq '<div id="root"></div>' "$frontend_body" || {
    echo "/admin/audit did not return frontend index.html" >&2
    exit 1
}

curl \
    --silent \
    --show-error \
    --output "$api_body" \
    --dump-header "$api_headers" \
    "$base_url/api/__frontend-routing-smoke__"

if grep -Fq '<div id="root"></div>' "$api_body"; then
    echo "/api/** incorrectly returned frontend index.html" >&2
    exit 1
fi

if grep -Eiq '^content-type:.*text/html' "$api_headers"; then
    echo "/api/** unexpectedly returned text/html" >&2
    exit 1
fi

echo "Frontend Nginx routing smoke test passed."
