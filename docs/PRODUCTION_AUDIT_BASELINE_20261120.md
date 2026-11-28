# SafeAI Desk — первичный аудит 20.11.2026

## Вердикт

**NO GO для production.** Это не означает, что платформа небезопасна:
обязательные доказательства ещё не получены, а серверное подтверждение правил AI
не реализовано в проверенном срезе. Разрешена работа с изолированным staging.

## Состав проверенного среза

Шесть архивов с backend/src, Maven POM/Dockerfile, frontend/src, package lock,
конфигурацией и E2E, infra и .github. Исходники Java: 705 основных файлов,
444 тестовых исходника (включая support-классы). SQL: 92 последовательные миграции.
Frontend после патча: 161 тестовый файл. Полного checkout и Git SHA в архиве
нет, отсутствуют также отдельные root scripts, Maven Wrapper и ESLint config.
Не смешивали устаревшие исходники с новым срезом. Для frontend использовали
готовые node_modules с тем же package-lock.json.

## Результаты выполненных проверок

| Проверка | Результат |
| --- | --- |
| Frontend Vitest | PASS: 1 527 тестов, 161 файл |
| TypeScript typecheck | PASS |
| Vite build | PASS; внешний root bundle-verifier не запускался |
| CI Python regression suites | PASS: 99 тестов |
| Infrastructure security policy | PASS: статический контракт |
| Java style | PASS: новых исключений нет |
| Flyway sequence | PASS: V1–V92; история Git не проверена |
| Audit Java/TS/SQL literals | PASS: 59 из 59 |
| Browser Playwright | BLOCKED: Chromium socket() Operation not permitted |
| Maven, PostgreSQL, Redis, S3 | NOT RUN: нет Maven Wrapper/Docker в среде |
| Trivy образов, CodeQL, release | NOT RUN: не выдаём статический gate за скан |
| Staging, провайдер, load, restore | NOT RUN: стенд не предоставлен |

## Замечания и действия

| ID | Приоритет | Факт | Решение |
| --- | --- | --- | --- |
| F-01 | P1 | API save не требует proof preview | 7.5B, отдельная реализация |
| F-02 | P2 | 20 из 59 типов не локализованы | Исправлено в патче |
| F-03 | P2 | Recovery outbox отображается как ошибка | Исправлено в патче |
| F-04 | P1 | Нет полного доказательства записи всех типов | Инвентарь + интеграционные тесты |
| F-05 | P1 | Нет общего серверного поиска по correlation ID | 7.5B/7.6 |
| F-06 | P1 | Нет server severity/outcome/category и агрегатов | 7.5B/7.6 |
| F-07 | P2 | Staging E2E ожидает устаревшую главную страницу | Исправлено; staging не запущен |
| F-08 | P1 | Нет доказательства MFA привилегированных аккаунтов | Ревью и подтверждение |
| F-09 | P1 | Нет свежих доказательств полного runtime/restore | 7.5C |

Для F-01: `OrganizationModelPolicyService#createVersion` проверяет права,
конкурентную версию, формальные ограничения и записывает аудит. Сохранение
не связано с конкретной ранее завершённой симуляцией. Это усиление от ошибок
администратора, а не доказанный обход RBAC. Необходимо реализовать протокол
по программе приёмки, не меняя действующих защит и истории версий.

Для F-04: контракт Java/TypeScript/SQL добавлен в CI. В новый тест
`AuditMigrationIntegrityIntegrationTest#everyJavaEventTypeIsRegisteredInPostgres`
заложена проверка физических строк справочника БД после применения Flyway.
Этот новый Java-тест ещё не запускался.

## Покрытие событий — первый инвентарь

До исправления: 39/59 локализованы. После: 59/59 имеют текстовую подпись
и встречаются в SQL. Текстовые ссылки найдены для 46 типов в Java runtime
(без самого enum), для 35 в Java test. Остальные не считаем отсутствующими
событиями: есть SQL-переходы, вызовы через DTO/переменные и тесты с динамическими
типами. Для 24 типов без точного текстового совпадения в Java-тестах требуется
отдельно найти assertions либо дописать сценарии.

```bash
python3 .github/scripts/check-audit-event-contract.py
python3 .github/scripts/check-audit-event-contract.py --inventory
```

Режим inventory выдаёт для каждого кода пути и номера строк Java runtime,
тестов и миграций. Ни содержимое строк, ни реальные payload не выгружаются.
Это индекс для ревью, не динамическое покрытие.

Примеры уже имеющейся защиты (тесты найдены, но backend-прогон не выполнен):

| Область | Имеющиеся классы тестов |
| --- | --- |
| Audit и tenant | AuditTenantIsolationIntegrationTest, AuditCursorPaginationIntegrationTest |
| Надёжность | AuditOutboxIntegrationTest, RequiredAuditPolicyIntegrationTest |
| Санитизация | AuditDetailsSanitizerTest, AuditActorSnapshotIntegrationTest |
| Retention | AuditRetentionIntegrationTest, AuditRetentionLockIntegrationTest |
| Auth | AuthPostgresConcurrencyIT, AuthControllerSecurityTest |
| Redis | RedisFixedWindowRateLimiterIT, RedisClusterRateLimitIT |
| AI | ModelPolicyPreviewServiceTest, ProviderDataDispatchGuardTest |
| Бюджет | ModelBudgetCommitmentV64PostgresIntegrationTest |
| Аналитика | UsageTenantIsolationIntegrationTest, UsagePricingCorrectnessIntegrationTest |
| RAG | KnowledgeRetrievalRepositoryIntegrationTest, KnowledgeIngestionQueueIntegrationTest |
| Performance | UsageExplainAnalyzePerformanceIntegrationTest |

## Интерфейс Audit

В новом срезе уже есть `collapseToFooter` в AdminAuditPageView и тест Playwright
перетаскивания разделителя до подвала, проверки кнопок «Назад», «Вперёд» и
восстановления двойным кликом. Повторно не меняем рабочую механику без
воспроизводимого дефекта. Браузерный тест в текущей среде не выполнился;
визуальное подтверждение на пользовательском стенде обязательно.

Существующая защита экспорта и области запросов не изменена. Счётчики по
severity/outcome и фильтр Correlation ID нельзя честно добавить как frontend-only:
они должны работать по всей серверной выборке и согласовываться с CSV.

## Уже существующие release-контроли

CodeQL, строгий Trivy в CI/release, проверка инфраструктурной policy, контроль
Maven отчётов и coverage, независимые performance и provider assurance gates,
подписанный manifest, контроль миграций, ручной Redis topology gate,
staging acceptance, WORM/backup restore контуры, Audit Outbox метрики и alerts.
Ни один порог не снижен. Перед GO необходима фактическая проверка каждого
контроля для конкретного SHA/digest и актуального vulnerability DB.
