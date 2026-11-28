<!-- SafeAI-Desk/docs/PRODUCTION_GATES_2026_2027.md -->
# SafeAI Desk: правила выпуска и эксплуатации (07.11.2026–2027)

## Назначение

Этот комплект добавляет обязательные проверки к существующим шести GitHub Actions workflows.
Он **не включает удаление в production**, не изменяет S3 Object Lock, retention, legal hold,
tenant isolation, restore gate, feature flags, SQL-миграции и доступы пользователей.
Локальные зелёные тесты, CI/CD и полноценная эксплуатационная сертификация являются **разными** уровнями подтверждения.

## Необходимые настройки GitHub (не делаются изменением YAML)

1. Settings → Advanced Security → **Dependency graph: Enable**.
   Иначе `actions/dependency-review-action@v5` будет падать с ошибкой
   `Dependency review is not supported on this repository`.
   Не заменять его фальшивой успешной проверкой и не добавлять `continue-on-error`.
2. Включить Dependabot alerts и, по необходимости, автоматическую отправку зависимостей Maven.
3. Settings → Rules / Rulesets для **main**: запрет прямого push, обязательный PR,
   review, актуальность ветки перед merge и обязательные check-runs:
   - `Java 21, Maven, архитектура и интеграционные тесты`
   - `Usage PostgreSQL / performance SLA`
   - `Архитектура, ESLint, тесты и сборка`
   - `Dockerfiles, Compose и image scan`
   - `CodeQL / java-kotlin` и `CodeQL / javascript-typescript`
   - `Trivy / зависимости и секреты`
   - `Проверка новых зависимостей в PR` (PR-only)
   - `qodana`
   - `GitHub Actions / actionlint`
   Зафиксировать администраторов, обходы (bypass) и запрет force-push согласно политике команды.
4. Settings → Environments → `production`: настроить **required reviewers**,
   ограничить ветку `main`, определить окно развёртывания. Создание environment в YAML само по себе **не** требует ручного подтверждения.
5. В production secrets: `PRODUCTION_SSH_KEY`, `PRODUCTION_KNOWN_HOSTS`,
   `PRODUCTION_HOST`, `PRODUCTION_USER`, `PRODUCTION_SSH_PORT`, `PRODUCTION_APP_DIR`,
   `GHCR_USERNAME`, `GHCR_PULL_TOKEN`. Использовать отдельную учётную запись deploy,
   строго ограниченные права и SSH host-key pinning.
6. Создать отдельные ограничения доступа к GitHub Actions, секретам, правам `GITHUB_TOKEN`, GHCR.
   Для внешних actions рассмотреть dependency lockfile GitHub либо фиксацию по проверенным полным commit SHA.

## CI: какие инварианты проверяются

- Java 21 / Maven clean verify / Testcontainers, плюс критичные PostgreSQL/Redis IT.
- Surefire XML должен содержать **не менее 2000** тестов; в стандартном прогоне разрешены
  только три *конкретных* `performance`-пропуска. Новые пропуски блокируют merge.
- Все JUnit-тесты с тегом `performance` запускаются отдельным job с исходными SLA.
  Проверка требует выполнения трёх текущих базовых тестов без skipped.
- Frontend: проверка архитектуры, ESLint, Vitest coverage, TypeScript, build.
  Минимальные покрытия statements/branches/functions/lines по 70% (сохранено базовое покрытие).
  Эти пороги надо **повышать**, не снижать, после расширения тестов.
- `npm audit --audit-level=low` + production audit.
- Docker: topology, сборка backend/Nginx/PostgreSQL, проверка не-root для двух app runtime,
  Trivy HIGH/CRITICAL с исправлениями, а также все CRITICAL, включая unfixable. Trivy секреты и все CRITICAL уязвимости — отдельно блокирующие.
- Qodana полный анализ, CodeQL Java/JS, Dependency Review с порогом LOW (требует Dependency Graph).
- `actionlint` проверяет все workflows; бинарный артефакт сверяется по опубликованному SHA-256.

## Важное ограничение проверки модулей

При подготовке изучены реальные конфигурации GitHub и предоставленные архивы отдельных
модулей Java (auth, organization, usage, audit, models, AI, chat, knowledge, rate limit),
85 миграций Flyway и Spring application-конфигурации.
Подробный инвентарь приведён в `docs/CI_MODULE_COVERAGE.md`. Наличие CI-проверок не означает,
что протестированы все пользовательские и эксплуатационные сценарии: в частности,
полноценные браузерные E2E, реальный S3/IAM и disaster recovery здесь не заменены
моделированием. Добавление новых тестов и инфраструктуры отдельно согласуется с командами.

## Release / CD

1. Workflow `production-release.yml` допускает публикацию **только из main** и только по ручному запросу.
2. Preflight проверяет, что SHA = текущий HEAD main, а девять обязательных check-runs
   завершились успехом **на этом SHA**, без `skipped` и `neutral`.
   Требование Dependency Review на PR обеспечивается отдельно Ruleset, потому что на push в main
   PR-only job не запускается.
3. Production release сам пересобирает Maven/Frontend; образы используют digest закреплённых в момент сборки баз.
4. Trivy сканирует три образа; SBOM CycloneDX сохраняется отдельным artifact.
5. После публикации трех образов по immutable digest `actions/attest@v4` создаёт
   SLSA provenance в GHCR (SBOM хранится отдельно как GitHub artifact); затем
   `gh attestation verify` проверяет источник и SHA provenance.
   **Подпись происхождения НЕ доказывает отсутствие уязвимостей**.
6. Production environment approval обязателен настройкой на GitHub, а не самоописанием.
7. Перед SSH-деплоем сверяется main HEAD. Развёртываются именно digest образов
   и именно SHA исходников. Скрипт `deploy-prod.sh` проверяет `/healthz` и readiness.

## Важные испытания, которые не подменяются зелёным CI

Эти **блокеры реального включения опасных production-функций** требуют отдельной среды,
учётных записей и документированных протоколов с операторским подтверждением:

- Проверка S3 Object Lock COMPLIANCE (реальные политики IAM, versionId, retention, legal hold).
- Восстановление PostgreSQL на изолированном стенде, сверка с независимым WORM-журналом
  и историческими S3-версиями; отсутствие повторного появления удалённых данных.
- Отказ сети/AI-провайдера в точках между SQL commit и HTTP, lease/fencing на нескольких JVM.
- Проверка миграций Flyway и отката **операционного релиза** с совместимостью схемы.
- Настоящие браузерные E2E (логин, разграничение ролей, загрузка, запрос, удаление).
- План резервного копирования и регулярные restore drills, алертинг, RTO/RPO,
  нагрузочные испытания, наблюдаемость, схемы обслуживания.

## Смена версий в течение 2027 года

- Обновлять pinned версии через Dependabot PR с обязательными CI и ручным review.
- При добавлении нового критичного workflow обновлять `REQUIRED` в `check-release-ci.py`
  **и** обязательные Checks в Ruleset, иначе release gate не знает о нём.
- При добавлении новых performance-тестов ставить `@Tag("performance")`.
- Если обычный тест осознанно пропускается, требуется явное review списка допустимых
  `skipped`, а не удаление `check-maven-reports.py`.
- Проверять актуальность зафиксированных версий образов и CVE на каждом релизе.
- Никаких автоматических `npm audit fix --force`, отключения Trivy, `continue-on-error`,
  `-DskipTests` в обязательном production gate или ослабления SLA для получения зелёного CI.
