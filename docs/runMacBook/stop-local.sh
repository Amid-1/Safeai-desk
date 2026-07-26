#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${PROJECT_ROOT}/infra/docker-compose.local.yml"
RUNTIME_DIR="${PROJECT_ROOT}/.local-run"
BACKEND_PID_FILE="${RUNTIME_DIR}/backend.pid"
FRONTEND_PID_FILE="${RUNTIME_DIR}/frontend.pid"

log() {
  printf '[SafeAI] %s\n' "$*"
}

stop_process() {
  local name="$1"
  local pid_file="$2"

  if [[ ! -f "${pid_file}" ]]; then
    log "${name}: PID-файл не найден — вероятно, процесс уже остановлен."
    return
  fi

  local pid
  pid="$(cat "${pid_file}" 2>/dev/null || true)"

  if [[ -z "${pid}" ]]; then
    rm -f "${pid_file}"
    return
  fi

  if kill -0 "${pid}" 2>/dev/null; then
    log "Останавливаю ${name}, PID ${pid}..."
    kill "${pid}" 2>/dev/null || true

    for _ in {1..20}; do
      kill -0 "${pid}" 2>/dev/null || break
      sleep 0.5
    done

    if kill -0 "${pid}" 2>/dev/null; then
      log "${name} не завершился вовремя — отправляю SIGKILL."
      kill -9 "${pid}" 2>/dev/null || true
    fi
  else
    log "${name}: процесс PID ${pid} уже не работает."
  fi

  rm -f "${pid_file}"
}

stop_process "frontend" "${FRONTEND_PID_FILE}"
stop_process "backend" "${BACKEND_PID_FILE}"

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  if [[ -f "${COMPOSE_FILE}" ]]; then
    log "Останавливаю PostgreSQL и Redis..."
    docker compose -f "${COMPOSE_FILE}" stop postgres redis
  fi
else
  log "Docker Desktop не запущен — контейнеры не изменялись."
fi

log "SafeAI Desk остановлен."
