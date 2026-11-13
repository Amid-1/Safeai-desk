<!-- SafeAI-Desk/docs/CI_MODULE_COVERAGE.md -->
# SafeAI Desk: охват модулей CI/CD (ревизия 07.11.2026)

## Инвентаризация вложенных архивов

Просмотрены 14 предоставленных ZIP-архивов. В них содержится 673 Java-файла
в следующих пакетах исходного backend (подсчитаны по декларациям `package`):

| Пакет | Java-файлов | Основные риски для эксплуатационных проверок |
|---|---:|---|
| `common` | 36 | конфигурация, общие инварианты |
| `auth` | 50 | CSRF/JWT, сессии, гонки и полномочия |
| `user` | 42 | разграничение прав, персональные данные |
| `organization` | 24 | изоляция арендаторов, ACL, конкурентные изменения |
| `ratelimit` | 20 | Redis, лимиты и отказ Redis |
| `usage` | 42 | SQL, агрегации, admission control, 366 дней SLA |
| `audit` | 53 | полнота и неизменность истории |
| `model` | 78 | маршрутизация и квоты провайдеров |
| `ai` | 80 | внешние вызовы, повторы, таймауты и неопределённые исходы |
| `chat` | 75 | lease/fencing, state machine и RAG-контекст |
| `knowledge` | 171 | S3 Object Lock, удаление, удержание, RAG, восстановление |
| `identity` | 2 | отзыв сессии и границы доверия |

Дополнительно: 85 миграций Flyway `V1..V85`, конфигурации `application*.yml`
и логирование. Это **инвентарь архивов**, а не доказательство полного чтения
всех путей выполнения и не полный тестовый каталог GitHub.

## Техническое покрытие, усиленное этим комплектом

- **Java / интеграции**: весь Maven `clean verify`; отдельные PostgreSQL/Redis
  concurrency тесты; fail-closed проверка Surefire-отчётов.
- **Usage**: все тесты с `@Tag("performance")` с неизменённым порогом SLA;
  SQL EXPLAIN сохраняется при ошибке.
- **Frontend**: архитектура, ESLint, Vitest с 4 порогами покрытия, TypeScript,
  production bundle и полный npm audit.
- **Supply chain**: Trivy images, Trivy dependencies и secrets, CodeQL, Qodana,
  Dependency Review, actionlint, digest образов, SBOM и provenance.
- **Релиз**: однозначный SHA main, обязательные статусы, approval production,
  SSH host key pinning, проверка готовности после деплоя.

## Неподтверждённое, что нельзя выдавать за пройденные тесты

- Реальные многоузловые AI/RAG lease и fencing, разрывы сети при SQL commit.
- Реальные S3 политики IAM + WORM COMPLIANCE, legal hold, retention, versionId.
- Restore drill PostgreSQL и S3 без возвращения ранее удалённых данных.
- Браузерные end-to-end тесты полного пути (login, upload, RAG, deletion).
- Секреты Environment, Rulesets, ручное одобрение и живые показатели
  observability, алертинг, отказоустойчивость и соответствие RTO/RPO.

Эти проверки требуют стенда/операторского подтверждения; нельзя подменять
их моками и выдавать зелёный GitHub workflow за полноценную сертификацию.
