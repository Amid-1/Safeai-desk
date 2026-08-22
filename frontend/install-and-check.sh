#!/usr/bin/env bash
set -Eeuo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

node_version="$(node --version | sed 's/^v//')"
node_major="${node_version%%.*}"

if [[ "$node_major" != "24" ]]; then
    echo "Required Node.js 24.x. Current version: $node_version" >&2
    exit 1
fi

echo "[1/5] Regenerating package-lock.json..."
npm install

echo "[2/5] Clean reproducible install..."
rm -rf node_modules
npm ci

echo "[3/5] Quality gate..."
npm run check

echo "[4/5] Coverage..."
npm run test:coverage

echo "[5/5] Production dependency audit..."
npm run audit:prod

echo "Frontend verification completed successfully."
