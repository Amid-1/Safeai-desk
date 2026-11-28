#!/usr/bin/env bash
# Локальные проверки только в изолированной копии проекта; ничего не развёртывает.
set -Eeuo pipefail
umask 077
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
root="$PWD"
mode="${1:---static}"
case "$mode" in
  --static) [[ $# -le 1 ]] ;;
  --local) [[ $# -eq 2 && "$2" == --confirm-isolated ]] ;;
  *) echo 'Использование: --static | --local --confirm-isolated' >&2; exit 2 ;;
esac
evidence="$(mktemp -d "${TMPDIR:-/tmp}/safeai-audit.XXXXXXXX")"
echo "Закрытый каталог отчётов: $evidence"
preview_pid=''
cleanup() {
  if [[ -n "$preview_pid" ]]; then
    kill "$preview_pid" 2>/dev/null || true
    wait "$preview_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT
trap 'echo "NO GO: локальная проверка не завершена; отчёты: $evidence" >&2' ERR
run() {
  local name="$1"; shift
  echo "=== $name ==="
  "$@" 2>&1 | tee "$evidence/$name.log"
}
run static-infra python3 .github/scripts/check-infra-security-policy.py
run static-java python3 .github/scripts/check-java-style.py
run audit-contract python3 .github/scripts/check-audit-event-contract.py
python3 .github/scripts/check-audit-event-contract.py --inventory >"$evidence/event-inventory.json"
run ci-regressions python3 -m unittest discover -s .github/scripts -p 'test_*.py'
run migration-sequence python3 .github/scripts/check-flyway-migrations.py
run diff-check git diff --check
if [[ "$mode" == --static ]]; then
  echo 'STATIC PASS — не является разрешением на production.'
  exit 0
fi

# Запускать лишь в отдельном checkout и Docker context без production-доступа.
if [[ -n "${SPRING_PROFILES_ACTIVE:-}${SPRING_DATASOURCE_URL:-}${SAFEAI_E2E_BASE_URL:-}${SAFEAI_E2E_USER_PASSWORD:-}${SAFEAI_E2E_APPROVED:-}${DATABASE_URL:-}" ]]; then
  echo 'BLOCKED: обнаружена внешняя конфигурация приложения или staging.' >&2
  exit 1
fi
command -v java >/dev/null
command -v node >/dev/null
command -v npm >/dev/null
command -v docker >/dev/null
command -v curl >/dev/null
[[ -f backend/mvnw && -f scripts/npm-audit-gate.cjs ]]
node -e 'if (process.versions.node.split(".")[0] !== "24") process.exit(1)'
[[ "$(npm --version)" == 11.* ]]
java -version 2>&1 | tee "$evidence/java.log"
grep -Eq 'version "21([.]|")' "$evidence/java.log"
run docker docker info --format '{{.ServerVersion}}'
run git-revision git rev-parse HEAD origin/main
run migration-history python3 .github/scripts/check-flyway-migrations.py --base origin/main
(
  cd "$root/frontend"
  run npm-install npm ci
  run npm-production-audit npm run audit:prod
  run npm-gate-tests node --test ../scripts/npm-audit-gate.test.cjs
  run npm-gate node ../scripts/npm-audit-gate.cjs
  run frontend-ci npm run ci
)
run frontend-coverage python3 .github/scripts/check-frontend-coverage.py --check
(
  cd "$root/backend"
  run maven bash ./mvnw --batch-mode --no-transfer-progress clean verify
)
run maven-reports python3 .github/scripts/check-maven-reports.py standard
run backend-discovery python3 .github/scripts/check-backend-test-discovery.py
run backend-coverage python3 .github/scripts/check-backend-coverage.py --check
cp -R backend/target/surefire-reports "$evidence/standard-surefire"
if [[ -d backend/target/failsafe-reports ]]; then
  cp -R backend/target/failsafe-reports "$evidence/standard-failsafe"
fi
(
  cd "$root/backend"
  run concurrency bash ./mvnw --batch-mode --no-transfer-progress \
    -Dtest=AuthPostgresConcurrencyIT,OrganizationSecurityGateConcurrencyIT,RedisFixedWindowRateLimiterIT test
  run tagged-performance bash ./mvnw --batch-mode --no-transfer-progress \
    -Dgroups=performance -Dsafeai.usage.performance=true -Dsafeai.erasure.load-tests=true clean test
)
run tagged-reports python3 .github/scripts/check-maven-reports.py performance
cp -R backend/target/surefire-reports "$evidence/tagged-performance-surefire"
(
  cd "$root/backend"
  run performance bash ./mvnw --batch-mode --no-transfer-progress \
    -Dsafeai.erasure.load-tests=true -Dsafeai.usage.performance=true \
    -Dtest=ChatErasureBatchBoundaryV86IntegrationTest,UsageExplainAnalyzePerformanceIntegrationTest,UsagePerformanceIntegrationTest \
    clean test
)
run performance-reports python3 .github/scripts/check-performance-reports.py backend/target/surefire-reports
cp -R backend/target/surefire-reports "$evidence/performance-surefire"
if [[ -d backend/target/usage-explain ]]; then
  cp -R backend/target/usage-explain "$evidence/usage-explain"
fi
cd "$root/frontend"
run chromium npx --no-install playwright install chromium
if curl -s --max-time 2 http://127.0.0.1:4173/ >/dev/null; then
  echo 'BLOCKED: порт 4173 уже занят; чужой процесс не останавливаем.' >&2
  exit 1
fi
node node_modules/vite/bin/vite.js preview --host 127.0.0.1 --port 4173 --strictPort \
  >"$evidence/preview.log" 2>&1 &
preview_pid=$!
ready=false
for ((i=0; i<30; i++)); do
  kill -0 "$preview_pid"
  if curl --fail --silent --max-time 2 http://127.0.0.1:4173/ >/dev/null; then
    ready=true
    break
  fi
  sleep 1
done
[[ "$ready" == true ]]
export SAFEAI_E2E_BASE_URL=http://127.0.0.1:4173
export SAFEAI_E2E_APPROVED=false
export CI=true
run browser npx --no-install playwright test --config=e2e/playwright.config.cjs --grep-invert @staging
echo "LOCAL PASS — CI, staging, реальный провайдер и восстановление ещё обязательны: $evidence"
