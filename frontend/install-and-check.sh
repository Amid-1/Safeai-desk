#!/usr/bin/env bash
# frontend/install-and-check.sh
# Полный локальный прогон без удаления исходников и без автоматического коммита.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
node_major="$(node -p 'process.versions.node.split(".")[0]')"
npm_major="$(npm -v | cut -d. -f1)"
if [[ "$node_major" != 24 || "$npm_major" != 11 ]]; then
  printf 'Требуются Node.js 24 и npm 11. Сейчас: Node %s, npm %s\n' "$(node -v)" "$(npm -v)" >&2
  exit 2
fi
npm ci
npm run architecture:check
npm run typecheck
npm run lint
npm run test
npm run test:coverage
npm run build
