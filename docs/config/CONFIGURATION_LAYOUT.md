# SafeAI Desk — canonical configuration topology (V84 readiness)

## Правило выбора источника истины

| Назначение | Каноническая директория / файл | Автозагрузка |
|---|---|---|
| Spring Boot defaults | `backend/src/main/resources/application.yml` | Да |
| Spring Boot production overrides | `backend/src/main/resources/application-prod.yml` | Да, профиль `prod` |
| Spring Boot local overrides | `backend/src/main/resources/application-local.yml` | Да, профиль `local` |
| Local reverse-proxy profile | `backend/src/main/resources/application-local-nginx.yml` | Да, профиль `local-nginx` |
| JUnit-only overrides | `backend/src/test/resources/application-test.yml`, `application-auth-postgres-it.yml`, `junit-platform.properties` | Только тесты |
| Deployment composition (prod) | `infra/docker-compose.yml` | Только операторский Compose |
| Deployment composition (local) | `infra/docker-compose.local.yml` | Только локальный Compose |
| Prometheus main config | `infra/prometheus/prometheus.yml` | Да, через Compose bind mount |
| Audit alerts | `infra/prometheus/rules/safeai-audit-outbox.yml` | Да, через `rule_files` |
| S3 reconciliation alerts | `infra/prometheus/rules/safeai-knowledge-review.yml` | Да |
| Erasure alerts | `infra/prometheus/rules/safeai-knowledge-erasure.yml` | Да, после установки этого патча |
| Erasure rule regression tests | `infra/prometheus/tests/knowledge-erasure.test.yml` | Только `promtool test rules`; не загружается сервером |
| External production pricing | `/etc/safeai/config/pricing.yml` | Да, read-only bind mount |
| External production secrets | `/etc/safeai/secrets/` | Да, scoped file-backed secrets |
| Versioned secret template | `infra/secrets/*.example` | Нет |
| Versioned pricing / host Nginx template | `infra/config/*.example`, `infra/host-nginx/*.example` | Нет |
| Example env | `backend/.env.example`, `backend/.env.prod.example` | Нет, источник шаблона |
| Actual env | `backend/.env`, `backend/.env.prod` | Только локально/на целевом host; **НЕ** в Git |
| Documentation-only examples | `backend/docs/config/*.example.{yml,yaml}`, `backend/src/main/resources/*example.yml` | Нет |

### Что делать с внешне похожими каталогами

- `backend/deploy/prometheus/`: устаревший альтернативный комплект. В проверенной конфигурации `infra/docker-compose*.yml` НЕ монтирует его, поэтому изменения там не активируют алерты. Его можно удалить отдельным чистящим коммитом только после проверки всех ссылок в CI, документации и скриптах; патч не удаляет его автоматически.
- `ops/prometheus/knowledge-erasure-alerts.yml` из предыдущего ZIP V84 — источник примера, **не** смонтированный текущим Compose. Вместо него используется канонический `infra/prometheus/rules/safeai-knowledge-erasure.yml`. Не добавляйте обе копии.
- В IntelliJ `deploy.prometheus` и `docs.config` — результат включённой функции **Compact Middle Packages**; на диске это вложенные каталоги `deploy/prometheus` и `docs/config`.
- Имена `application-prod-knowledge-reconciliation.example.yml` и `application-security-example.yaml` — документация, не часть автоматически загружаемых профилей. Их нельзя включать в production без отдельного review.

## Безопасность и release-процесс

1. Сохраните свой checkout и текущее состояние Git. Старые Flyway V1–V84 не меняйте.
2. Установите новый canonical erasure alert file и скрипты проверки; затем примените отдельно поставляемый точечный patch к **существующим** `backend/.env.prod.example`, `infra/docker-compose.yml`, `infra/scripts/validate-prod.sh`, `infra/scripts/deploy-prod.sh`.
3. При production rollout заполните `SAFEAI_PROMETHEUS_IMAGE` **по проверенному digest** `@sha256:<64hex>`; версию тега нельзя считать неизменяемой.
4. `SAFEAI_AUTH_ACCESS_COOKIE_NAME` и `SAFEAI_AUTH_REFRESH_COOKIE_NAME` должны оставаться пустыми в production: проект генерирует защищённые cookie names.
5. Проверьте шаблон production env. Исторические локальные `.env.prod` с обычными названиями cookie, `SAFEAI_SECRET_GID=10001` или без digest должны быть исправлены оператором отдельно. Файл с секретами/host-настройками не включён в этот патч.
6. Выполните `infra/scripts/check-config-topology.sh` и `infra/scripts/check-prometheus-config.sh` (проверяет `promtool check config --lint-fatal` и `promtool test rules`; не запускает приложение, SQL, удаление или миграции).
7. Production release обязан пройти `infra/scripts/validate-prod.sh <env>`; в нём проверяется Compose, подписи image digest, все правила Prometheus и структура.
8. `infra/scripts/deploy-prod.sh` теперь запускает `prometheus` наряду с backend/nginx (при условии успешного preflight). Настройка **Alertmanager / notification routing** остаётся отдельным обязательным эксплуатационным шагом.
9. Все destructive deletion flags, reconciliation and operator recovery flags должны оставаться выключенными до отдельной приёмки и approval.

## Необходимые команды

Из корня репозитория, на машине с Docker:

```bash
bash infra/scripts/check-config-topology.sh
bash infra/scripts/check-prometheus-config.sh

# Когда config changes запланированы для production, сначала на staging/CI:
# bash infra/scripts/validate-prod.sh /absolute/path/to/.env.prod
# НЕ запускайте deploy-prod.sh лишь ради проверки конфигурации.
```

### Примечание про IDE inspections

`ports: - "${SAFEAI_PUBLIC_BIND:?SAFEAI_PUBLIC_BIND is required}:8080"` — корректный Compose short-port mapping, если `SAFEAI_PUBLIC_BIND=127.0.0.1:8080`, без пробелов. Docker Compose официально поддерживает `HOST_IP:HOST_PORT:CONTAINER_PORT`. Реальная проверка: `docker compose ... config --quiet`, а не цвет IDE. Ошибка `Single image reference expected` на `FROM ${BUILD_IMAGE}` может возникать при анализе `ARG` IDE; реальное поведение проверяется Docker build с production `--build-arg` / pins.
