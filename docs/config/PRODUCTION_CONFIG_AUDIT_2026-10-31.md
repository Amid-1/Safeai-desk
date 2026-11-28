# SafeAI Desk — аудит production-конфигураций на 31.10.2026

**Основание:** пользовательские фрагменты и скриншоты локального checkout, доступные файлы V84, а также проверенная ветка GitHub `main` (`1aa0cdcb` на момент исследования). Наличие более поздних непушенных изменений локально не подтверждено; `git apply --check` защищает от их перезаписи.

## Подтверждённые дефекты и исправления

| Приоритет | Область | Проблема | Решение |
|---|---|---|---|
| P0 | Prometheus V84 | Правила в `ops/prometheus` не подключаются текущим Compose | Единственный canonical erasure rule file в `infra/prometheus/rules` |
| P1 | Audit outbox | В V84-примере продублирован существующий dead-letter alert | Исключён из нового файла; сохранён canonical audit alert |
| P1 | Production release | Скрипт деплоя не запускал Prometheus после preflight | Добавлен сервис в `up -d --wait`, после запуска backend и Nginx |
| P1 | Production release | `validate-prod.sh` проверял только audit rules и мог продолжить без promtool | Обязательный `promtool check config --lint-fatal` в Prometheus image и проверка структуры всех правил |
| P1 | Production env example | Обычные имена cookies противоречили validate-prod | Пустые значения, жёсткие cookie names назначаются runtime |
| P1 | Supply chain | Prometheus image был только pinned by mutable tag, без обязательного digest | Продуктовый Compose использует `SAFEAI_PROMETHEUS_IMAGE`; validator требует sha256 digest |
| P2 | Документация | `backend/deploy/prometheus` выглядит как активная конфигурация | Помечен legacy; удалять только после проверки внутренних ссылок |

## Что осознанно сохранено

- `infra/prometheus/prometheus.yml` (private network `backend:9091`, 15s scrape, rule glob).
- `infra/prometheus/rules/safeai-audit-outbox.yml` и `safeai-knowledge-review.yml`.
- `backend/src/main/resources/application*.yml` — разные Spring profiles, не мусор и не дубли Compose.
- `backend/src/test/resources/*.yml` — тестовые настройки; тестовые RSA fixtures не должны переиспользоваться в production.
- `infra/docker-compose.local.yml` — отдельный локальный stack с MinIO/Testcontainers-подобным окружением; он не заменяет production Compose.
- `.gitignore`, `.dockerignore`, `backend/Dockerfile`, `infra/nginx`, Postgres bootstrap и секретные пути: для них нет доказанного безопасного повода массово менять поведение в этом выпуске.

## Открытые задачи, блокирующие полную production-приёмку

1. Убедиться, что **локальный** `backend/.env.prod` не наследует устаревшие значения из старого шаблона. В предоставленном фрагменте встречались несовместимые с preflight GID, непустые cookie names и tag-only references. Этот файл не коммитить.
2. Проверить актуальные ревизии `infra/scripts/build-prod.sh`, CI, hardening Postgres images, Compose runnability and image digests. Проверка YAML без Docker/Prometheus не доказывает runtime readiness.
3. Настроить доставку уведомлений через Alertmanager и тестовое оповещение; правило, загруженное Prometheus, не равнозначно отправленному уведомлению.
4. Сделать отдельную зачистку legacy `backend/deploy/prometheus` и неиспользуемых документационных примеров только после `git grep` и согласования ссылок, не совмещая с security patch.
5. Запустить `./mvnw clean verify` и frontend CI, затем проверить Flyway V84 только на **disposable staging/Testcontainers PostgreSQL**.
6. Защитные флаги физического удаления оставить выключенными до отдельного эксплуатационного разрешения.

## Замечания по алертам

- Каждый backend replica измеряет общую PostgreSQL очередь. В группировке используется `max`, а не `sum`, чтобы не удваивать backlog.
- `DELETING` age в V84 измеряется от `created_at`, а не от последнего worker progress: это осознанно консервативный сигнал оператора. Он может оставаться высоким после ручного resume; не трактовать это как доказанный deadlock без диагностики.
- Отсутствие метрик проверяется отдельным `absent`; stale и число ошибок refresh — отдельные сигналы.
- Audit dead-letter rule в отдельном canonical файле уже есть; дополнительная копия исключена.
- Существующие `up{job="safeai-backend"}` / missing target rules остаются в audit rules. Не создавайте второй alert с тем же смыслом в erasure rules.
