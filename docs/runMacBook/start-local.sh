#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
BACKEND_DIR="${PROJECT_ROOT}/backend"
FRONTEND_DIR="${PROJECT_ROOT}/frontend"
COMPOSE_FILE="${PROJECT_ROOT}/infra/docker-compose.local.yml"
RUNTIME_DIR="${PROJECT_ROOT}/.local-run"
BACKEND_PID_FILE="${RUNTIME_DIR}/backend.pid"
FRONTEND_PID_FILE="${RUNTIME_DIR}/frontend.pid"
BACKEND_LOG="${RUNTIME_DIR}/backend.log"
FRONTEND_LOG="${RUNTIME_DIR}/frontend.log"

mkdir -p "${RUNTIME_DIR}"

log() {
  printf '[SafeAI] %s\n' "$*"
}

fail() {
  printf '[SafeAI] ERROR: %s\n' "$*" >&2
  exit 1
}

command -v docker >/dev/null 2>&1 || fail "Docker не найден."
command -v java >/dev/null 2>&1 || fail "Java не найдена."
command -v npm >/dev/null 2>&1 || fail "npm не найден."

docker info >/dev/null 2>&1 || fail "Docker Desktop не запущен."

[[ -f "${COMPOSE_FILE}" ]] || fail "Не найден ${COMPOSE_FILE}"
[[ -f "${BACKEND_DIR}/mvnw" ]] || fail "Не найден backend/mvnw"
[[ -f "${FRONTEND_DIR}/package.json" ]] || fail "Не найден frontend/package.json"

process_is_running() {
  local pid_file="$1"
  [[ -f "${pid_file}" ]] || return 1

  local pid
  pid="$(cat "${pid_file}" 2>/dev/null || true)"
  [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null
}

log "Запускаю PostgreSQL и Redis..."
docker compose -f "${COMPOSE_FILE}" up -d postgres redis

log "Ожидаю готовность PostgreSQL и Redis..."
for _ in {1..60}; do
  postgres_status="$(docker compose -f "${COMPOSE_FILE}" ps --format json postgres 2>/dev/null || true)"
  redis_status="$(docker compose -f "${COMPOSE_FILE}" ps --format json redis 2>/dev/null || true)"

  if [[ "${postgres_status}" == *'"Health":"healthy"'* && "${redis_status}" == *'"Health":"healthy"'* ]]; then
    break
  fi

  sleep 1
done

docker compose -f "${COMPOSE_FILE}" ps

if ! process_is_running "${BACKEND_PID_FILE}"; then
  log "Запускаю backend..."
  chmod +x "${BACKEND_DIR}/mvnw"

  (
    cd "${BACKEND_DIR}"

    export SPRING_PROFILES_ACTIVE=local

    export SAFEAI_JWT_SECRET="safeai-local-development-secret-key-change-this-value-please-123456789"
    export SAFEAI_JWT_EXPIRATION_MINUTES=15
    export SAFEAI_JWT_ISSUER="safeai-desk"

    export SAFEAI_AUTH_COOKIES_SECURE=false
    export SAFEAI_AUTH_COOKIES_SAME_SITE=Lax
    export SAFEAI_AUTH_ACCESS_TOKEN_MAX_AGE=15m
    export SAFEAI_AUTH_REFRESH_TOKEN_MAX_AGE=30d

    export REDIS_HOST=localhost
    export REDIS_PORT=6379
    export REDIS_PASSWORD="safeai_redis_password"

    export SAFEAI_RATE_LIMIT_REDIS_KEY_PREFIX="safeai:local"
    export SAFEAI_RATE_LIMIT_LOGIN_ENABLED=true
    export SAFEAI_RATE_LIMIT_AI_MESSAGES_ENABLED=true

    nohup ./mvnw spring-boot:run >"${BACKEND_LOG}" 2>&1 &
    echo $! >"${BACKEND_PID_FILE}"
  )

  log "Backend PID: $(cat "${BACKEND_PID_FILE}")"
else
  log "Backend уже запущен, PID: $(cat "${BACKEND_PID_FILE}")"
fi

if ! process_is_running "${FRONTEND_PID_FILE}"; then
  log "Запускаю frontend..."

  (
    cd "${FRONTEND_DIR}"

    if [[ ! -d node_modules ]]; then
      log "Папка node_modules отсутствует — выполняю npm install..."
      npm install
    fi

    nohup npm run dev >"${FRONTEND_LOG}" 2>&1 &
    echo $! >"${FRONTEND_PID_FILE}"
  )

  log "Frontend PID: $(cat "${FRONTEND_PID_FILE}")"
else
  log "Frontend уже запущен, PID: $(cat "${FRONTEND_PID_FILE}")"
fi

log "Проект запущен."
log "Backend: http://localhost:8080"
log "Frontend: смотри адрес в ${FRONTEND_LOG}"
log "Лог backend: ${BACKEND_LOG}"
log "Лог frontend: ${FRONTEND_LOG}"
log "Просмотр логов: tail -f \"${BACKEND_LOG}\" или tail -f \"${FRONTEND_LOG}\""
