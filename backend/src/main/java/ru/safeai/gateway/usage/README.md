# Usage — отчёты по сообщениям и вызовам провайдера

## Структура

```text
usage/
├── config/
│   ├── UsageConfiguration.java
│   ├── UsageHikariPoolInvariantVerifier.java
│   ├── UsageJdbcClients.java
│   └── UsageProperties.java
├── controller/
│   ├── UsageController.java
│   ├── UsagePhysicalSpendController.java
│   └── UsageRebuildController.java
├── dto/
│   ├── PagedResponse.java
│   ├── UsageCostSummary.java
│   ├── UsageDailySummaryResponse.java
│   ├── UsageDataQualityResponse.java
│   ├── UsageDateFilter.java
│   ├── UsageDateModelFilter.java
│   ├── UsageModelSummaryResponse.java
│   ├── UsagePageRequest.java
│   ├── UsagePhysicalReconciliationResponse.java
│   ├── UsagePhysicalSpendResponse.java
│   ├── UsageProblemModelResponse.java
│   ├── UsageResponseSummary.java
│   ├── UsageSummaryInvariants.java
│   ├── UsageSummaryResponse.java
│   ├── UsageTokenSummary.java
│   └── UsageUserSummaryResponse.java
├── repository/
│   ├── JdbcUsageQueryRepository.java
│   ├── UsageDirtyDateRepository.java
│   ├── UsageFactQueryBuilder.java
│   ├── UsageFactSql.java
│   ├── UsageInstantRange.java
│   ├── UsagePhysicalSpendRepository.java
│   ├── UsageQueryCriteria.java
│   ├── UsageQueryPlan.java
│   ├── UsageQueryRepository.java
│   ├── UsageRollupStateRepository.java
│   ├── UsageRowMapperSupport.java
│   └── UsageSqlFragments.java
├── service/
│   ├── UsageDirtyDateProcessor.java
│   ├── UsageForcedRebuildService.java
│   ├── UsagePhysicalSpendService.java
│   ├── UsageQueryService.java
│   ├── UsageReportExecutor.java
│   ├── UsageRollupDayProcessor.java
│   └── UsageRollupScheduler.java
└── README.md
```

[Тесты Usage](../../../../../../test/java/ru/safeai/gateway/usage/README.md).

## Источники и границы отчётов

| Отчёт | Источник | Критерий периода |
| --- | --- | --- |
| По сообщениям | `chat_messages`, `chat_turns` и UTC-rollup | Время факта, полуоткрытый интервал. |
| Физические расходы | `model_execution_attempts` и организация плана | `started_at >= from AND started_at < to`. |
| Сверка | Все попытки выбранного плана и связанное сообщение ASSISTANT | Достаточно одной попытки внутри периода. |

Отчёты по сообщениям показывают сохранённые ответы Chat. Физические отчёты учитывают
каждый вызов отдельно, включая повторы. Сверка сначала выбирает уникальные планы
по интервалу, затем суммирует всю историю попыток каждого выбранного плана, включая
попытки до и после его границ. Обе физические выборки применяют tenant scope к плану.

План считается совпавшим, когда известны цены физических попыток и завершённого
сообщения, а суммы равны. При двух известных разных суммах план классифицируется
как расхождение. При неизвестной стоимости либо отсутствии пригодного сообщения
план остаётся непроверяемым. Пустая выборка возвращает `fullyReconciled=false`.
Сверка сравнивает внутренние данные SafeAI Desk. Внешние счета провайдера требуют
отдельного источника и процедуры сверки.

## Токены, стоимость и качество данных

- `AVAILABLE` входит в подтверждённые токены. `PARTIAL` отражается отдельно.
  `MISSING` сохраняет признак отсутствия данных. `NOT_APPLICABLE` учитывается отдельно.
- `PRICED` и `FREE` входят в известную стоимость USD. `UNPRICED` и
  `CALCULATION_FAILED` снижают полноту. Нулевая известная сумма может сопровождаться
  неполным покрытием.
- Для физических попыток `KNOWN_NOT_EXECUTED` исключается из счётчиков неизвестных
  затрат и токенов. `STARTED` и `AMBIGUOUS` отражаются как неполные данные.
- `__unattributed__` — ключ группы сообщений с `model IS NULL`. V28 зарезервировала
  значение в `chat_messages`, а V30 валидировала ограничение для исторических строк.
  В каталоге моделей для маршрутов, сохраняющих сообщения Chat, используйте другие ID.
- Запрос data quality вычисляет общие показатели и проблемные модели в одном снимке
  PostgreSQL. Состояние rollup и данные фактов читаются в той же транзакции.

## API

Все пути ниже относительны к `/api/admin/usage`. ADMIN ограничен собственной
организацией. SUPER_ADMIN может читать глобальные отчёты и отчёты организации.
Фильтры SQL передаются параметрами. Списки используют навигацию Slice без общего
количества элементов. Параметры дат и пагинации валидируются перед запросом.

| Метод | Путь | Назначение |
| --- | --- | --- |
| GET | `/summary`, `/daily`, `/data-quality` | Итоги, дневные группы и качество данных. |
| GET | `/users`, `/models` | Группировка по пользователям и моделям. |
| GET | `/by-user/{userId}` | Отчёт по пользователю. |
| GET | `/by-organization/{organizationId}` | Итоги организации. |
| GET | `/by-organization/{organizationId}/{users,models,daily}` | Разрезы организации. |
| GET | `/physical-attempts` | Покрытие и известная стоимость вызовов по интервалу. |
| GET | `/physical-attempts/reconciliation` | Сверка целых планов. |
| POST | `/rebuild` | Запрос пересчёта, только SUPER_ADMIN. Возвращает 202. |

Для физических отчётов `dateFrom` и `dateTo` передаются как ISO-8601 timestamps.
При насыщении отчётных слотов executor отклоняет новые запросы с HTTP 429.

## Транзакции и UTC-rollup

`UsageReportExecutor` сначала захватывает локальный permit. Затем открывает
read-only `REPEATABLE READ` и получает один общий PostgreSQL advisory xact slot.
Слот освобождается с завершением транзакции, локальный permit — в `finally`.
Для общего лимита все реплики должны иметь одинаковый `max-concurrent-reports`.

Исторические закрытые UTC-дни могут читаться из rollup. Непокрытые, частичные и
требующие пересчёта интервалы планируются с чтением исходных фактов. Полные rollup
и live-границы одного отчёта не пересекаются. Ручной пересчёт ставит даты в очередь.

V74 добавляет в очередь и текущую UTC-дату в исходной транзакции. Это сохраняет
маркер при коммите после полуночи. Worker обрабатывает даты до watermark закрытых
дней. Ревизия маркера защищает повторное изменение даты от удаления обработчиком.
V74 также добавляет индексы для времени начала попытки и организации плана.

## Конфигурация

| Свойство под `safeai.usage` | По умолчанию |
| --- | --- |
| `default-range`, `max-range`, `max-live-range` | 30d, 366d, 31d |
| `max-page-size`, `fetch-size` | 200, 200 |
| `query-timeout`, `slow-query-threshold` | 10s, 2s |
| `max-concurrent-reports` | 4 |
| `rollup.enabled`, `rollup.cron` | true, `0 */10 * * * *` |
| `rollup.lookback-days`, `rollup.backfill-days-per-run` | 3, 31 |
| `rollup.advisory-lock-key`, `rollup.statement-timeout` | 8026001, 2m |

При включённом production-rollup проверяется минимальный размер Hikari pool.
При приёмке измеряйте ожидание соединения и p95/p99 отчётов при работающем rollup,
а также планы физических запросов на объёме, близком к production. Проверяйте
watermark и возраст dirty dates после восстановления PostgreSQL. При расхождении
используйте пересчёт производных агрегатов с сохранением исходных фактов.


## 1. Назначение и границы отчётности

`usage` — подсистема аналитики использования AI, не самостоятельный процессор вызовов модели. Она формирует
административные
отчёты по пользователям, организациям, моделям и UTC-дням, суммирует известные токены и стоимость, сообщает об
отсутствии
либо неполноте данных, хранит дневные материализованные агрегаты (`rollup`), автоматически пересчитывает
изменившуюся историю
и ограничивает ресурсоёмкость запросов.

Отчётность сочетает UTC-rollup и чтение исходных фактов, использует watermark,
очередь пересчёта и ограничение конкуренции. У сообщений и физических попыток
свои источники и правила периода. Внутренняя сверка опирается на данные проекта.
Сопоставление со счетами поставщика требует отдельного источника.

### 1.1. Краткий статус

- Разграничение доступа
  - **Статус** — Хорошо
  - **Комментарий** — `ADMIN` видит собственную организацию, `SUPER_ADMIN` — глобальные и явно выбранные срезы.
    Проверки на уровне контроллера и сервиса.
- API и валидация
  - **Статус** — Хорошо с оговорками
  - **Комментарий** — `Instant`, ограничение периода, размер страницы, запрет произвольной сортировки. У некоторых
    DTO самостоятельная валидация минимальна.
- Известные vs неизвестные токены
  - **Статус** — Хорошо
  - **Комментарий** — `AVAILABLE`, `PARTIAL`, `MISSING`, `NOT_APPLICABLE` разнесены. Число токенов из `PARTIAL` не
    смешивается с подтверждённым.
- Известная vs неизвестная стоимость
  - **Статус** — Хорошо для аналитики
  - **Комментарий** — `knownCostUsd` — только известная часть; полнота отражается счётчиками. Не финальное
    подтверждение полной физической себестоимости.
- Дневные агрегаты
  - **Статус** — Хорошо
  - **Комментарий** — UTC, компактные SQL-таблицы, watermark, lookback, dirty-date queue.
- Многорепличный rollup
  - **Статус** — Хорошо по конструкции
  - **Комментарий** — PostgreSQL advisory lock сериализует scheduler; результат зависит от надёжности соединения и
    транзакций.
- Допуск
  - **Статус** — Реализован
  - **Комментарий** — Локальный permit и общий PostgreSQL-слот в read-only транзакции.
- Пересборка
  - **Статус** — Реализована
  - **Комментарий** — V74 включает текущую UTC-дату в очередь пересчёта.
- Физические расходы
  - **Статус** — Реализованы
  - **Комментарий** — Есть покрытие попыток и внутренняя сверка планов.

### 1.2. Термины

- **Usage** — потребление AI: токены, число ответов, признаки полноты, стоимость.
- **Rollup** — предрассчитанный агрегат за закрытые UTC-дни; вместо повторного сканирования всех сообщений.
- **Live** — прямой SQL-расчёт по исходным записям для непокрытого rollup диапазона.
- **Watermark** — последняя UTC-дата, для которой последовательный первичный расчёт завершён.
- **Dirty date** — ранее закрытая дата, источник которой изменился и требует повторного расчёта.
- **Advisory lock** — сессионная кооперативная блокировка PostgreSQL для координации нескольких экземпляров приложения.
- **Tenant scope** — ограничение записей конкретной организации (`organization_id`).
- **Fail-closed** — при невозможности гарантировать требуемую безопасность или лимит операция отклоняется, а не
  незаметно разрешается.
- **Known cost** — достоверно классифицированная стоимость `PRICED` или `FREE`, а не сумма всех потенциальных затрат.
- **Physical attempt** — отдельный фактический вызов провайдера; один логический chat turn способен содержать
  несколько попыток при разрешённом retry.

---

## 2. Структура, зависимости и поток данных

```text
HTTP client
  └─ /api/admin/usage/**
       ├─ UsageController / UsageRebuildController
       │    └─ Spring Security role checks / @Valid
       ├─ UsageQueryService
       │    ├─ validate date range, page, model and tenant scope
       │    ├─ UsageRollupStateRepository → completed UTC watermark
       │    ├─ UsageQueryPlan → full-day rollup + exact live edges
       │    └─ UsageReportExecutor → per-JVM semaphore, metrics
       │         └─ UsageQueryRepository
       │             └─ JdbcUsageQueryRepository
       │                 ├─ UsageFactQueryBuilder
       │                 ├─ UsageSqlFragments
       │                 ├─ UsageRowMapperSupport
       │                 └─ PostgreSQL
       └─ UsageForcedRebuildService → usage_dirty_dates

Scheduled every 10 minutes (UTC by default)
  └─ UsageRollupScheduler
       └─ UsageRollupStateRepository.executeWithAdvisoryLock()
           ├─ UsageRollupDayProcessor.rebuildDay(date, advanceWatermark)
           │    ├─ DELETE old aggregates for UTC date
           │    ├─ INSERT user/model, org/model, quality rollups
           │    ├─ UPSERT count of ambiguous turns
           │    └─ markCompleted(date) when backfilling
           ├─ lookback reconciliation
           └─ UsageDirtyDateRepository.findPending()
                └─ UsageDirtyDateProcessor.rebuildIfPending()
                     ├─ new transaction + SELECT FOR UPDATE
                     ├─ rebuildDay(date, false)
                     └─ DELETE dirty marker if revision unchanged

PostgreSQL V71 triggers:
  chat_messages / chat_turns mutation → usage_dirty_dates
```

### 2.1. Источники истины и границы ответственности

1. `chat_messages` содержит сохранённые ответы ассистента, модель, состояние, usage/pricing status,
   подтверждённые/частичные токены и известную цену.
2. `chat_turns` предоставляет данные о логической операции и состоянии `AMBIGUOUS`; эти операции учитываются в
   **quality**, но не как полноценные оплаченные физические attempts.
3. `model_execution_plans` / `model_execution_attempts` (появились в V50, далее ужесточались V53 и V66) хранят
   physical-attempt evidence; текущая usage-аналитика напрямую их не агрегирует.
4. `usage_daily_user_model_rollups`, `usage_daily_org_model_rollups`, `usage_daily_quality_rollups` — производные
   таблицы, которые можно пересчитывать из исходников в рамках поддерживаемой модели.
5. `usage_rollup_state` хранит непрерывный watermark. `usage_dirty_dates` — ревизионную очередь повторных пересчётов.

Важно: **usage-аналитика, финансовое ledger и ограничение квот — разные задачи**. Не нужно подменять one-way
аналитический `knownCostUsd` доказательством фактического долга клиента.

### 2.2. Правильная временная семантика

Вход — `Instant` (UTC-направленная точка времени). Интервал в SQL полуоткрытый: `[dateFrom, dateTo)`; граничное событие
входит ровно в один интервал. Rollup выбирается только для **целых** UTC-дней, а частичные дни читаются live.
`UsageDailySummaryResponse.aggregationZone` жёстко фиксирует `UTC`. Это правильный подход для серверной аналитики,
если интерфейс явно показывает используемый часовой пояс.

---

## 3. Контракты REST API

Базовый путь: `/api/admin/usage`; общая защита контроллера `@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")`.

- GET
  - **Endpoint** — `/summary`
  - **Назначение и контракт** — `Slice` потребления по `userId + model`, фильтр дат/модели, `page/size`.
- GET
  - **Endpoint** — `/users`
  - **Назначение и контракт** — `Slice` сгруппированного потребления по пользователю.
- GET
  - **Endpoint** — `/models`
  - **Назначение и контракт** — Полный список сводок по моделям, без постраничности.
- GET
  - **Endpoint** — `/daily`
  - **Назначение и контракт** — Дневная UTC-сводка, без постраничности.
- GET
  - **Endpoint** — `/by-user/{userId}`
  - **Назначение и контракт** — Потребление конкретного пользователя; ADMIN дополнительно ограничен своей
    организацией.
- GET
  - **Endpoint** — `/by-organization/{organizationId}`
  - **Назначение и контракт** — Потребление организации по пользователю/модели.
- GET
  - **Endpoint** — `/by-organization/{organizationId}/users`
  - **Назначение и контракт** — Пользователи явно выбранной организации.
- GET
  - **Endpoint** — `/by-organization/{organizationId}/models`
  - **Назначение и контракт** — Модели выбранной организации.
- GET
  - **Endpoint** — `/by-organization/{organizationId}/daily`
  - **Назначение и контракт** — UTC-дни выбранной организации.
- GET
  - **Endpoint** — `/data-quality`
  - **Назначение и контракт** — Полнота usage, pricing, ambiguity и топ-20 проблемных моделей.
- POST
  - **Endpoint** — `/rebuild?date=YYYY-MM-DD`
  - **Назначение и контракт** — Запрос принудительного пересчёта **закрытого** UTC-дня, **SUPER_ADMIN**. Ответ
    `202 Accepted`; запрос ставится в очередь, это не сообщение о завершённом пересчёте.
- GET
  - **Endpoint** — `/physical-attempts`
  - **Назначение и контракт** — Попытки с `started_at` в полуоткрытом диапазоне.
- GET
  - **Endpoint** — `/physical-attempts/reconciliation`
  - **Назначение и контракт** — Сверка всех попыток выбранных планов.

`dateFrom/dateTo` принимаются в ISO DATE_TIME как `Instant`, отсутствующие границы разрешаются сервисом: конец — текущие
часы приложения, начало — конец минус `defaultRange`. `UsagePageRequest`: `page` по умолчанию 0, `size` — 50,
максимум 200.
Для `models`, `daily` и списка проблемных моделей собственная пагинация отсутствует (у проблемных моделей SQL
`LIMIT 20`).

**Авторизация:** наличие HTTP `@PreAuthorize` само по себе недостаточно для защиты от неконтроллерных вызовов;
правильно,
что `UsageQueryService` дополнительно проверяет tenant scope и имеет class-level `@PreAuthorize`.
`UsageForcedRebuildService`
тоже проверяет `SUPER_ADMIN` на сервисе. Для аутентификации используется `SafeAiUserPrincipal`, который не должен
доверяться
из параметров запроса, а создаётся Spring Security. Контроллер использует `@AuthenticationPrincipal` без
`errorOnInvalidType=true`;
это скорее диагностическое улучшение, чем доказанная брешь.

**Семантика `Slice`:** не предоставляет общего количества страниц или записей. `first`, `last`, `hasNext`,
`hasPrevious` — навигационные флаги, не `Page` с `totalElements`. Это снижает стоимость запросов, но клиент не должен
рассчитывать абсолютное количество результатов.

---

## 4. Полный каталог Java-классов — 42 файла

Ниже назначение, ключевые поля, методы, инварианты и точечные замечания по каждому исходнику. Простые getter-аксессоры
record не описываются по одной строке; анализируются их контракт и вычисляемые поля.

### 4.1. `usage.config` — 4 файла

#### 4.1.1. `UsageConfiguration.java`

- `@Configuration(proxyBeanMethods=false)` — современная облегчённая конфигурация; проксирование вызовов `@Bean` внутри
- класса здесь не требуется.
- `@EnableConfigurationProperties(UsageProperties.class)` — регистрация типизированных свойств конфигурации.
- `@Bean UsageJdbcClients usageJdbcClients(DataSource, UsageProperties)` — создаёт специфичную для usage обёртку
  без добавления
- второго `JdbcTemplate` как самостоятельного бина.
- **Верно:** не создаёт неявную двусмысленность autowiring существующих JDBC-клиентов.
- **Риск:** переданный `DataSource` должен быть именно транзакционно управляемым, общий datasource Spring; сам класс это
- не проверяет.

#### 4.1.2. `UsageHikariPoolInvariantVerifier.java`

- `ApplicationRunner.run(@NonNull ApplicationArguments)` — проверяет pool на старте приложения, только если
  активен `prod/production` и включён rollup.
- Поля `dataSource`, `environment`, `usageProperties` валидируются через `Objects.requireNonNull`.
- Требует `dataSource instanceof HikariDataSource` и `maximumPoolSize >= 3`.
- **Верно:** fail-fast/ fail-closed при неподтверждённом pool. Отдельная сессия advisory lock плюс транзакция
  worker действительно
- требуют нескольких соединений.
- **Ограничение:** размер 3 — необходимое минимальное предположение, а **не доказательство** отсутствия
  starvation, когда
- параллельно идут отчёты, HTTP и прочие background jobs. Wrapped datasource (`LazyConnectionDataSourceProxy`,
  другие обёртки)
- может быть корректным, но отвергнутым этим `instanceof` — осознанная жёсткая политика, требующая проверки
  инфраструктуры.
- **Рекомендация:** integration-тест на реальный пул при `maxConcurrentReports`, нескольких репликах и активном rollup;
- измерять `hikaricp.connections.pending`, время ожидания и connection acquisition timeout.

#### 4.1.3. `UsageJdbcClients.java`

- Поля `NamedParameterJdbcTemplate query` и `JdbcTemplate rollup` ссылаются на один `DataSource`, но имеют
  **разные конфигурации** `queryTimeout` и `fetchSize`.
- Конструктор валидирует зависимости, задаёт `queryTemplate.setQueryTimeout(properties.queryTimeoutSeconds())`,
  `setFetchSize(properties.fetchSize())`, аналогично timeout для rollup.
- `query()` / `rollup()` — открывают соответствующую абстракцию репозиториям.
- **Верно:** JDBC для агрегирующих запросов с параметрами, отсутствие SQL-инъекции через bind-параметры,
  разграничение тайм-аутов.
- **Ограничение:** JDBC statement timeout — тайм-аут конкретного SQL statement, а не жёсткий deadline всего отчёта
  с подготовкой,
- ожиданием пула, несколькими SQL-запросами и сериализацией ответа. `fetchSize` не гарантирует server-side
  streaming при всех
- условиях драйвера и transaction/autocommit.

#### 4.1.4. `UsageProperties.java` и вложенный `Rollup`

- Типизированный immutable record свойств `safeai.usage`; канонический конструктор выставляет дефолты и валидирует
  положительные
- диапазоны, связанные ограничения, размер страницы, fetchSize и максимум конкурентных отчётов.
- `defaultRange=30d`, `maxRange=366d`, `maxLiveRange=31d`; `maxPageSize=200`; `queryTimeout=10s`,
  `slowQueryThreshold=2s`, `fetchSize=200`, `maxConcurrentReports=4`.
- Методы `queryTimeoutSeconds()` и `rollupStatementTimeoutSeconds()` переводят `Duration` в секунды **с
  округлением вверх**: частично заданная секунда не ослабляет deadline до меньшего целого; выход за `int`
  отклоняется.
- Вспомогательные `positiveDuration`, `atLeastOneSecond`, `boundedPositiveInt`, `durationSecondsCeil` — проверка
  входной конфигурации, `Math.addExact/toIntExact` предотвращает молчаливое переполнение.
- Вложенный record `Rollup`: `enabled=true`, `cron="0 */10 * * * *"`, `lookbackDays=3`, `backfillDaysPerRun=31`,
  `advisoryLockKey=8_026_001`, `statementTimeout=2min`. `defaults()` и `isEnabled()`.
- **Плюс:** несогласованность `defaultRange > maxRange` и `maxLiveRange > maxRange` отклоняется на старте.
- **Замечание:** сам record не валидирует синтаксис CRON при создании; Spring Scheduling обычно выявляет ошибку
  регистрации расписания. `advisoryLockKey` может совпасть с ключом другой задачи при ручной конфигурации.
  `Duration.toNanos()` у slow-threshold при экстремальном значении может переполниться — не подтверждённый
  эксплуатационный дефект при обычных настройках, но нужна верхняя разумная граница.

### 4.2. `usage.controller` — 3 файла

#### 4.2.1. `UsageController.java`

- Поле `usageQueryService` — один application-service вход для всех отчётов.
- Методы `summary`, `users`, `models`, `usersByOrganization`, `modelsByOrganization`, `daily`,
  `dailyByOrganization`, `byUser`, `byOrganization`, `dataQuality`: маппинг конкретных REST endpoints без SQL и
  бизнес-логики.
- `@Validated` и `@Valid @ModelAttribute` проверяют фильтры; `@PreAuthorize` ограничивает роли.
  `@AuthenticationPrincipal` передаёт principal в сервис; не принимает organization ID из тела без последующей
  проверки.
- **Хорошо:** тонкий контроллер; `PagedResponse.from` отделяет Spring Data `Slice` от внешнего JSON-контракта.
- **Проверить:** HTTP 400/403/503 контракт при некорректном `Instant`, `@ModelAttribute` и особых кодировках имени
  модели; тесты покрытия security есть. По дополнительной защите типов можно применить
  `@AuthenticationPrincipal(errorOnInvalidType=true)` как в `user`/`organization`, но это не обязательное
  исправление безопасности.

#### 4.2.2. `UsageRebuildController.java`

- `@PreAuthorize("hasRole('SUPER_ADMIN')")` на уровне класса, `requestRebuild(LocalDate date)` в POST
  `/api/admin/usage/rebuild`.
- `@DateTimeFormat(iso=DATE)` разбирает дату, `@ResponseStatus(ACCEPTED)` правильно обещает только постановку задачи.
- Сервис повторно проверяет роль и закрытый UTC-день.
- **Улучшение:** добавить структурированный аудит административного rebuild-запроса (кто/когда/дата/причина) и
  HTTP-отображение `IllegalArgumentException` в понятную 400, если оно ещё не централизовано глобальным
  обработчиком.

#### UsagePhysicalSpendController.java

Публикует отчёт по попыткам и сверку. Передаёт principal и границы периода сервису.

### 4.3. `usage.dto` — 16 файлов

#### 4.3.1. `PagedResponse<T>.java`

- Поля `content, page, size, first, last, hasNext, hasPrevious`.
- Компактный конструктор `List.copyOf` исключает изменение списка после формирования ответа; проверяет `page >=
  0`, `size >= 1`.
- `from(Slice<T>)` отображает навигационную метаинформацию Spring Data без expensive COUNT. Корректно.
- **Нюанс:** `List.copyOf` запрещает `null`-элементы. Это полезная fail-fast политика.

#### 4.3.2. `UsageCostSummary.java`

- `knownCostUsd`: только доказанная сумма, nonnegative; если null, заменяется **на ноль известной части**, не на
  утверждение об общей цене. Этот нюанс обязателен в интерфейсе.
- `currency` — строго `USD`, `pricedMessages`, `freeMessages`, `unpricedMessages`, `pricingFailedMessages`,
  `pricingNotApplicableMessages` — независимые неотрицательные классификаторы.
- Компактный конструктор вычисляет `pricingComplete = (unpriced == 0 && pricingFailed == 0)`; переданный boolean
  намеренно не считается доверенным.
- Дополнительный конструктор без currency/complete — совместимость, всегда выбирает USD.
- `classifiedMessages()` — `Math.addExact` исключает переполнение счётчиков.
- **Верно:** `unpriced` и `calculation failed` не замаскированы под FREE.
- **UX-опасность:** финансовое приложение не должно показывать `knownCostUsd = 0` без индикаторов
  неполноты/фактических attempts. При обновлении контрактов полезны `costCompleteness` и `sourceOfCost`, но
  требуется сквозное изменение SQL/API.

#### 4.3.3. `UsageDailySummaryResponse.java`

- `usageDate` и `aggregationZone="UTC"`, `responses`, `usage`, `cost`.
- Проверяет дату, строго поддерживаемый UTC и все агрегаты; `UsageSummaryInvariants.validate` требует
  согласованную классификацию сообщений. Правильно.

#### 4.3.4. `UsageDataQualityResponse.java`

- Содержит число сообщений ассистента, stored completed/failed, отсутствие модели, число ambiguous операций,
  usage/cost, проценты покрытия и проблемные модели.
- В конструкторе проверяются все счётчики; `UsageSummaryInvariants` согласует количество классификаций.
- `usageCoveragePercent = available / (available + partial + missing)`, `pricingCoveragePercent = (priced + free)
  / (priced + free + unpriced + failed)`; округление `HALF_UP`, 2 знака.
- Если applicable = 0, возвращается 100.00% — **математическая конвенция**, но в интерфейсе может выглядеть как
  «полнота подтверждена», когда данных вообще нет. Нужны `applicableCount` или статус `NO_DATA`, при сохранении
  обратной совместимости.
- `problemModels = List.copyOf` — immutable.
- **Критически:** `ambiguousProviderOperations` в этом DTO относится к логическим turns в состоянии `AMBIGUOUS`, а
  не ко всем physical attempts с неизвестным итогом; не выдавать этот показатель за полное финансовое покрытие.

#### 4.3.5. `UsageDateFilter.java`

- `Instant dateFrom/dateTo` с `@DateTimeFormat(ISO.DATE_TIME)`; null разрешён намеренно — default range
  устанавливает сервис.
- Сам DTO не проверяет порядок дат: это делает `UsageQueryService.normalizeRange` с общей конфигурацией.

#### 4.3.6. `UsageDateModelFilter.java`

- То же, плюс `@Size(max=100) String model`. `normalizedModel()` делает `trim()` и возвращает null для blank.
- На уровне сервиса повторная проверка длины нужна для programmatic вызовов. Проверено: она есть.
- Изменение Unicode-whitespace при `trim` vs `strip` может быть заметно для необычных значений — если модель
  является идентификатором, следует выбрать одну стабильную каноникализацию; без смены контракта автоматически не
  менять.

#### 4.3.7. `UsageModelSummaryResponse.java`

- `model` непустой, `trim`, nonnull `responses/usage/cost`; межобъектные инварианты.
- Чистая DTO, хорошо.

#### 4.3.8. `UsagePageRequest.java`

- `page`: `@Min(0)`; `size`: `@Min(1) @Max(200)`; default 0/50.
- `toPageable()` вызывает `PageRequest.of`, который откажет при неверном числе даже при обходе Bean Validation.
  Сервис дополнительно проверяет `properties.maxPageSize()` и запрет сортировки.
- Возможное несоответствие: аннотация верхнего предела захардкожена `200`, а эксплуатационная конфигурация может
  снижать `maxPageSize`; сервис компенсирует, но клиент получит разные сообщения об ошибке в зависимости от
  лимита.

#### 4.3.9. `UsageProblemModelResponse.java`

- `model` непустая, `trim`, `usageProblems` и `pricingProblems` неотрицательны.
- SQL возвращает максимум 20, отсортированных по количеству проблем; это **top-N**, не полный список проблемных моделей.

#### 4.3.10. `UsageResponseSummary.java`

- Разделяет `assistantMessages`, `completedResponses`, `refusedResponses`, `incompleteResponses`,
  `failedMessages`; вычисляет `unclassifiedMessages` как остаток.
- `Math.addExact` проверяет переполнение; классифицированных записей не может быть больше `assistantMessages`.
- **Правильно:** «неизвестная классификация» остаётся видимой, а не становится COMPLETED.

#### 4.3.11. `UsageSummaryInvariants.java`

- Package-private utility с двумя `validate` overload: проверяет `usage.classifiedMessages() == assistantMessages`
  и `cost.classifiedMessages() == assistantMessages`.
- **Хорошо:** обнаруживает потерю категории в SQL/RowMapper, но не гарантирует, что каждый исходный физический
  вызов представлен в отчёте.

#### 4.3.12. `UsageSummaryResponse.java`

- `(userId, currentUserEmail, model, responses, usage, cost)`; `userId` — неизменяемый исторический идентификатор,
  email — **текущее** отображаемое значение.
- Проверка nonnull/nonblank, trim и агрегатных инвариантов.
- **Конфиденциальность:** email — PII. API обязательно должен оставаться под авторизацией; логи/report query tags
  не должны содержать адресов пользователей.

#### 4.3.13. `UsageTokenSummary.java`

- Отдельно хранит `confirmedInput/Output/Total`, `partialKnownInput/Output/Total`, четыре класса completeness и
  вычисленный boolean `usageComplete`.
- `confirmedTotalTokens` и `partialKnownTotalTokens` **пересчитываются** через `Math.addExact`, не доверяют
  входным готовым суммам.
- `usageComplete = partial == 0 && missing == 0`. Это хорошая система выразительных DTO, где неизвестное usage не
  приравнено к нулевому.

#### 4.3.14. `UsageUserSummaryResponse.java`

- `(userId, currentUserEmail, responses, usage, cost)`; проверки userId, email, nonnull агрегатов и межобъектных
  инвариантов.
- Плюс: не смешивает email как mutable display field с стабильным `userId`.

#### DTO физических отчётов

`UsagePhysicalSpendResponse` описывает расходы и признаки полноты по попыткам.
`UsagePhysicalReconciliationResponse` разделяет планы на совпавшие,
расходящиеся и непроверяемые.

### 4.4. `usage.repository` — 12 файлов

#### 4.4.1. `JdbcUsageQueryRepository.java`

- Реализация `UsageQueryRepository` на `NamedParameterJdbcTemplate`.
- `findSummary` собирает facts, группирует `user_id + current_user_email + model`, сортирует по `user_id, model`,
  выдаёт Slice.
- `findUsers`: группирует по пользователю, `ORDER BY user_id`.
- `findModels`: группирует по модели, `ORDER BY model`. Не пагинируется.
- `findDaily`: группирует по UTC-дате, `ORDER BY usage_date DESC`. Не пагинируется.
- `findDataQuality` читает aggregate и проблемные модели в одном снимке транзакции executor.
- `findProblemModels`: считает partial/missing и unpriced/calculation-failed по моделям, `LIMIT 20`. SQL
  согласован с семантикой известных/неизвестных записей.
- `addPage`: `LIMIT pageSize+1`, `OFFSET pageable.getOffset()`, проверка переполнения страницы через `Math.addExact`.
- `slice`: по лишней строке вычисляет `hasNext` и возвращает `SliceImpl`.
- `executeInternallyGeneratedQuery` и `executeRequiredInternallyGeneratedQueryForObject` — централизованные
  JDBC-boundary. Вложенные SQL-куски статичны, user values передаются через `MapSqlParameterSource`.
- **Риски:** OFFSET на глубокой странице — линейная потеря производительности; `getOffset` long, PostgreSQL умеет
  большой OFFSET, но запрос способен быть тяжёлым. На будущее keyset/cursor pagination и строгая верхняя глубина.
- **Security:** `@SuppressWarnings(SqlResolve/SqlNoDataSourceInspection)` снимает подсказки IDEA только для этого
  класса, не является эквивалентом SQL-тестов; динамическая SQL-композиция требует snapshot-тестов, объяснённых
  ниже.

#### 4.4.2. `UsageDirtyDateRepository.java`

- `findPending(latestClosedDate, limit)`: читает ограниченную пачку грязных дат, не блокируя всё при простом чтении.
- `lockDate(date)`: `SELECT ... FOR UPDATE` в отдельной транзакции.
- `acknowledgeIfUnchanged(DirtyDate)`: `DELETE` только при совпадении `revision`, ожидает ровно одну затронутую строку.
- `requestRebuild(date)`: UPSERT в `usage_dirty_dates`, увеличивает revision и заполняет `updated_at` через
  `clock_timestamp()`.
- `DirtyDate(date, revision)` — nonnull date и revision >= 1.
- **Верно:** ревизия устраняет lost-update при изменении источника во время повторного построения; если rebuilding
  fails, dirty marker не пропадает.
- **Проверить:** вызовы `acknowledgeIfUnchanged` вне одной транзакции с `lockDate + rebuildDay` запрещены
  контрактом, но репозиторий не может доказать это одной сигнатурой. Сервис использует корректный транзакционный
  контекст.

#### 4.4.3. `UsageFactQueryBuilder.java`

- Основной генератор SQL-плана: `userFacts`, `organizationFacts`, `qualityFacts` объединяют rollup-фрагмент и
  один/два live-фрагмента в `WITH facts AS (...)` с `UNION ALL`.
- `baseParameters` регистрирует optional organizationId, userId, model; `appendRollupScope` и `appendLiveScope`
  выстраивают фильтры.
- `appendUserLiveFragments`: `chat_messages m` JOIN `chat_sessions s` JOIN `users u`, только `m.role='ASSISTANT'`;
  grouping по `s.user_id`, текущему email и модели.
- `appendOrganizationLiveFragments`: только `chat_messages`, фильтр по организации и UTC grouping для daily.
- `qualityFacts`: сводки `usage_daily_quality_rollups`, live counts для ASSISTANT и отдельных `AMBIGUOUS` turns,
  не смешивая «logical ambiguity» с количеством сообщений.
- `facts(...)` отклоняет пустой план; имена bind-параметров `liveFrom0`, `...1` вычисляются из внутреннего индекса
  и не зависят от пользовательского SQL-текста.
- `UNATTRIBUTED_MODEL="__unattributed__"` используется при `m.model IS NULL` как synthetic label.
- V28 резервирует значение в `chat_messages`. V30 валидирует ограничение для исторических строк.
- **Проверка:** порядок/типы столбцов во всех ветвях `UNION ALL`, особенно `qualityFacts`, должны совпадать — для
  этого нужны реальные PostgreSQL integration tests, а не только компиляция Java.

#### 4.4.4. `UsageFactSql.java`

- Package-private `record(String cte, MapSqlParameterSource parameters)` — простой перенос SQL+bind values из
  builder в executor.
- **Рекомендация:** immutable sql text плюс копия параметров при передаче, если появятся переиспользование одного
  объекта между потоками. Сейчас параметры создаются локально на запрос и переиспользование не просматривается.

#### 4.4.5. `UsageInstantRange.java`

- `record(Instant from, Instant to)` — nonnull и строго `from < to`.
- Инвариант исключает пустые и обратные интервалы, помогает корректному расчёту live-срезов.

#### 4.4.6. `UsageQueryCriteria.java`

- Обязательные `dateFrom/dateTo`, опциональные `organizationId/userId/model`.
- Проверяет порядок дат, trim/nullable model. Нет встроенной проверки роли — она **должна** проходить в
  `UsageQueryService` до вызова репозитория.
- Обращать внимание: произвольный программный вызов repository в другом сервисе, минуя application authorization,
  потенциально способен сформировать глобальный запрос. Это доверенная внутренняя граница, а не публичный
  repository API для любого модуля.

#### 4.4.7. `UsageQueryPlan.java`

- `(rollupFrom, rollupToExclusive, liveRanges)`; оба rollup-предела обязаны присутствовать совместно; начальная
  дата меньше конца.
- `List.copyOf` и `hasRollupRange()` обеспечивают простой immutable-план.
- **Не проверяет самостоятельно**, что liveRanges не пересекаются друг с другом или с rollup. Сейчас план строится
  контролируемым `UsageQueryService`; опасность возникнет при появлении второго конструктора плана. Можно добавить
  `validateNoOverlap()` для fail-fast.

#### 4.4.8. `UsageQueryRepository.java`

- Интерфейс аналитического чтения: `findSummary`, `findUsers`, `findModels`, `findDaily`, `findDataQuality`.
- **Хорошо:** сервис не знает о JDBC, builder не знает о REST, возможны альтернативные query backends без изменения API.

#### 4.4.9. `UsageRollupStateRepository.java`

- `findLastCompletedDate`: читает watermark по константе `JOB_NAME=usage-daily-rollup`.
- `findEarliestUsageDate`: берёт минимум UTC-даты по `ASSISTANT chat_messages` и завершённым `AMBIGUOUS chat_turns`.
- `markCompleted`: UPSERT с условием «та же дата/предыдущая дата или следующий непрерывный UTC-день», не допускает
  пропусков внутри уже инициализированного watermark; бросает exception, если `affectedRows != 1`.
- `executeWithAdvisoryLock(long lockKey, Runnable action)`: берёт выделенное соединение, через общий
  `PostgresAdvisoryLockExecutor.tryExecute` удерживает сессионную блокировку, вызывает action, затем закрывает
  соединение.
- **Верно:** ни один из нескольких экземпляров scheduler не должен одновременно перестраивать один и тот же день
  при одинаковом advisoryLockKey.
- **Риск:** отдельное connection остаётся занятым во время выполнения полного backfill/lookback/dirty rebuild, а
  worker берёт другое; учитывайте нагрузку на Hikari.
- **Границы:** при новой пустой установке допускается инициализация watermark на последнем закрытом дне без
  предыдущих дней, потому что источников для них не обнаружено. Позднее появление исторических данных должно
  попадать в V71 dirty queue; это нужно регулярно проверять integration-тестом.

#### 4.4.10. `UsageRowMapperSupport.java`

- Статические singleton `RowMapper` для summary, user summary, model summary, daily summary, problem models,
  quality aggregate.
- `mapResponses`, `mapUsage`, `mapCost` централизуют чтение классификаторов, суммы known cost и токенов.
  `mapDailySummary` жёстко проставляет `UTC`.
- Внутренний `QualityAggregate` — carrier промежуточного результата, не API.
- **Хорошо:** DRY-мэппинг и DTO-инварианты ловят классификационные ошибки.
- **Замечание:** `ResultSet.getLong` возвращает 0 при SQL NULL; агрегирующие `coalesce(sum(...),0)` защищают
  текущие запросы. Если SQL изменится, нужно проверять `wasNull()` для полей, где NULL означает ошибку, а не
  легальный ноль.

#### 4.4.11. `UsageSqlFragments.java`

- Константа `UNATTRIBUTED_MODEL`, четыре крупные **статические** SQL-проекции: `AGGREGATE_COLUMNS`,
  `LIVE_AGGREGATE_COLUMNS`, `AGGREGATE_COLUMNS_WITHOUT_ASSISTANT`, `LIVE_AGGREGATE_COLUMNS_WITHOUT_ASSISTANT`.
- `assistant_message_count` — количество сообщений с ролью ASSISTANT; ответы классифицируются в
  COMPLETED/REFUSED/INCOMPLETE/FAILED, остальные остаются `unclassified`.
- `input_tokens/output_tokens` учитываются как **подтверждённые** только для `usage_status='AVAILABLE'`; `PARTIAL`
  вынесен в отдельные поля.
- `known_cost_usd` суммирует `cost_usd` только при `pricing_status IN ('PRICED','FREE')`; остальная стоимость
  неизвестна и выражена счётчиками.
- **Верно:** нет некорректного «неизвестно = бесплатно» на уровне понятий DTO.
- **Системный предел:** SQL считает *сообщения*; стоимость физической попытки, не породившей корректный
  `chat_messages`-ответ, в денежный итог не попадёт. При использовании в биллинге это серьёзный разрыв.
- **Поддерживаемость:** большие повторяющиеся SQL-фрагменты требуют автоматических регрессионных сценариев для
  каждой категории и единой схемы типов `UNION ALL`; вручную менять их опасно.

#### UsagePhysicalSpendRepository.java

Агрегирует попытки по `started_at` и ограничивает выборку организацией плана.
Для сверки сначала выбирает планы, затем читает все их попытки.

### 4.5. `usage.service` — 7 файлов

#### 4.5.1. `UsageDirtyDateProcessor.java`

- `rebuildIfPending(LocalDate date)` с `@Transactional(REQUIRES_NEW, isolation=READ_COMMITTED)`.
- `dirtyDates.lockDate` берёт FOR UPDATE; отсутствующий marker → false.
- `dayProcessor.rebuildDay(date, false)` пересчитывает агрегаты **в этой же транзакции**; `acknowledgeIfUnchanged`
  сверяет revision и подтверждает погашение dirty marker.
- **Хорошо:** атомарность «обновить результат + удалить уведомление», отсутствие бесследной потери dirty-наблюдения.
- **Граница:** при массовых dirty-датах длительность ожидания lock и нагрузка на пул требуют метрик.

#### 4.5.2. `UsageForcedRebuildService.java`

- `request(LocalDate usageDate)` с `@PreAuthorize(SUPER_ADMIN)` и `@Transactional`.
- Через внедрённый `Clock` и UTC проверяет, что дата строго раньше сегодняшней; вызывает `dirtyDates.requestRebuild`.
- **Правильно:** не перестраивать открытый UTC-день как закрытый материализованный агрегат, не обещать синхронного
  rebuild.
- **Потенциальное улучшение:** темп/лимит массовых rebuild запросов, аудит акторов, граф очереди. Сравнение дня с
  `Clock` приложения не тождественно времени БД — существенный риск только при плохой синхронизации
  NTP/подменённом Clock, что нужно тестировать.

#### 4.5.3. `UsageQueryService.java`

- `@Service` + class-level `@PreAuthorize(ADMIN/SUPER_ADMIN)`.
- Запросы: `getUsageSummary`, `getUsageByUsers`, `getUsageByModels`, `getUsageDaily`,
  `getUsageByUsersForOrganization`, `getUsageByModelsForOrganization`, `getUsageDailyForOrganization`,
  `getUsageByUserId`, `getUsageByOrganizationId`, `getDataQuality`.
- Вспомогательные `normalizeRange`, `organizationCriteria`, `requireOrganizationAccess`, `buildPlan`,
  `buildLiveRanges`, `liveOnlyPlan`, `validateLiveFallback`, `ceilUtcDay`, `floorUtcDay`, `min`, `normalizeModel`,
  `requireCurrentUser`, `requirePageable`, `isSuperAdmin`.
- `requireOrganizationAccess` исключает случайное чтение чужого tenant у ADMIN; global scope даётся лишь
  SUPER_ADMIN. `getUsageByUserId` у ADMIN устанавливает одновременно `userId` и собственный `organizationId` —
  корректно.
- `normalizeRange`: оба `Instant`, настройка дефолтного диапазона, `maxRange`, reject on invalid order.
- `buildPlan`: если rollup выключен или watermark отсутствует, только live; иначе вырезает **внутренний закрытый
  UTC-период**, подтверждённый watermark, а края обрабатывает live. Нет двойного подсчёта границ.
- `validateLiveFallback`: суммирует длительность live-диапазонов; при превышении `maxLiveRange` возвращает 503,
  вместо внезапного тяжелого scan на год.
- `requirePageable`: диапазон size, запрет сортировки клиента — стабильные серверные ORDER BY, предотвращение
  SQL-конкатенации произвольных колонок.
- Executor получает локальный permit до транзакции и общий слот внутри неё.
- Data quality читает итог и проблемные модели в одном снимке `REPEATABLE READ`.

#### 4.5.4. `UsageReportExecutor.java`

- Локальный semaphore отклоняет запрос с 429 до открытия транзакции.
- Executor открывает read-only `REPEATABLE READ` и берёт один общий advisory xact slot.
- В конце транзакции PostgreSQL освобождает слот. Локальный permit освобождается в finally.
- `timers`, `rejectedCounters`, `timeoutCounters`, `errorCounters` — concurrent maps Micrometer instruments, теги
  только по типу отчёта.
- Gauge `available_permits`, `Timer` с percentile histogram, counters rejected/timeout/error; ошибки метрик
  логируются, не ломая безопасность передачи permit.
- `slowQueryThresholdNanos` + `System.nanoTime` оценивает длительность, предупреждает о медленных отчётах.
- **Плюс:** нет произвольных пользовательских UUID/email в метриках, кардинальность bounded при использовании
  фиксированных labels сервиса.
- Общий бюджет действует на реплики, подключённые к той же БД с одинаковой конфигурацией.

#### 4.5.5. `UsageRollupDayProcessor.java`

- `rebuildDay(LocalDate usageDate, boolean advanceWatermark)`: `@Transactional`; вычисляет полуоткрытые
  UTC-границы, удаляет агрегаты **именно одной даты** из трёх таблиц, вставляет пересчитанные user/org/quality
  rollups, дополняет ambiguous counts; при первичном backfill с `advanceWatermark=true` двигает watermark.
- `insertUserRollups`: группировка по `organizationId + userId + model` из `chat_messages`/`chat_sessions`;
  `coalesce(model, '__unattributed__')`.
- `insertOrganizationRollups`: группировка по `organizationId + model`.
- `insertQualityRollups`: качество usage/pricing по организации и дню.
- `upsertAmbiguousTurnCounts`: сохраняет агрегат количества `chat_turns.state='AMBIGUOUS'` по `completed_at`; не
  интерпретирует физическую стоимость.
- **Хорошо:** delete+insert+watermark под одной транзакцией не показывают полупостроенную дату при нормальных
  transaction semantics PostgreSQL.
- **Риски:** полный rebuild закрытого дня дорог на крупных данных; нужны индексы/EXPLAIN ANALYZE, monitoring
  длительности, deadlocks/lock waits и growth dirty queue. `@Transactional` даёт атомарность только при вызове
  через Spring proxy; реальные вызовы из scheduler идут через отдельный bean, что корректно.

#### 4.5.6. `UsageRollupScheduler.java`

- `@ConditionalOnProperty(enabled=true)` и `@Scheduled(cron="${safeai.usage.rollup.cron:0 */10 * * * *}", zone="UTC")`.
- `rebuild()`: берёт advisory lock, вычисляет вчерашний UTC-день, выполняет `backfill`, `reconcileLookback`,
  `rebuildDirtyDates`.
- `backfill`: определяет следующую последовательную дату из watermark или самого раннего источника; не больше
  `backfillDaysPerRun`; при пустой истории инициализирует закрытый watermark.
- `reconcileLookback`: пересчитывает последние `lookbackDays`, исключая только что построенные даты.
- `rebuildDirtyDates`: читает bounded queue до watermark и вызывает отдельный `UsageDirtyDateProcessor`
  transaction per day.
- **Верно:** multi-replica mutex, bounded work per run, повторение короткого исторического окна и дополнительные
  trigger-driven invalidations.
- **Ограничение:** lookback и dirty batch вместе с backfill могут выполняться долго, удерживая advisory lock
  connection. Планирование каждые 10 минут не является гарантией, что latency/свежесть rollup составит 10 минут.

---

#### UsagePhysicalSpendService.java

Проверяет область организации и диапазон. Вызывает repository через общий executor.

## 5. Взаимодействие с PostgreSQL: миграции, индексы, ограничения

- `V7__usage_quotas_and_rollups.sql`
  - **Фактическая роль в usage** — Базовые таблицы квот и дневных rollup, составные ключи и основные индексы.
- `V8__usage_chat_messages_indexes.sql`
  - **Фактическая роль в usage** — Индексы для аналитического чтения исходных сообщений.
- `V10__schema_hardening_timestamps_audit_rollups.sql`
  - **Фактическая роль в usage** — Значения/constraints агрегатов, временные поля.
- `V16__preserve_usage_history_on_user_delete.sql`
  - **Фактическая роль в usage** — `chat_sessions.user_id` с `ON DELETE RESTRICT`: исторические данные не должны
    теряться при физическом удалении пользователя.
- `V17__ai_usage_and_pricing_metadata.sql`
  - **Фактическая роль в usage** — Явные статусы usage/pricing, nullable cost при неизвестной цене,
    SQL-инварианты.
- `V28__usage_analytics_hardening.sql`
  - **Фактическая роль в usage** — Богатые категориальные rollup counters, `numeric(30,12)` для cost,
    quality-таблица, согласованность сумм и счётчиков.
- `V29__usage_analytics_indexes.sql`
  - **Фактическая роль в usage** — Индексы live/quality дат и организаций.
- `V30__validate_usage_constraints.sql`
  - **Фактическая роль в usage** — Валидация накопленных usage constraints.
- `V31__usage_user_report_index.sql`
  - **Фактическая роль в usage** — Индексы пользовательских срезов.
- `V34__chat_ambiguous_usage_quality.sql`
  - **Фактическая роль в usage** — `ambiguous_provider_operation_count` в quality rollup.
- `V50__ai_execution_attempt_usage_evidence.sql`
  - **Фактическая роль в usage** — Данные физических попыток для отдельного отчёта и сверки.
- `V53__physical_attempt_transition_and_pricing_integrity.sql`
  - **Фактическая роль в usage** — Строгие переходы physical attempts, защитные pricing invariants.
- `V66__physical_response_and_pricing_scheme.sql`
  - **Фактическая роль в usage** — Статусы физических ответов и схема ценообразования.
- `V71__usage_dirty_dates_and_recalculation_queue.sql`
  - **Фактическая роль в usage** — Очередь dirty UTC dates, database triggers после INSERT/UPDATE/DELETE сообщений
    и turns.
- V74
  - **Фактическая роль в usage** — Маркировка текущего UTC-дня и индексы физических отчётов.

### 5.1. Непустые инварианты схемы, которые нельзя ослаблять

1. `chat_messages.usage_status`/`pricing_status` должны сохранять различие
   AVAILABLE/PARTIAL/MISSING/NOT_APPLICABLE и PRICED/FREE/UNPRICED/CALCULATION_FAILED/NOT_APPLICABLE.
2. `cost_usd IS NULL` при неопределённой цене в исходной записи, а не `0` с мнимой определённостью.
3. `usage_daily_*_rollups.total_tokens = input_tokens + output_tokens` и счётчики категорий согласованы с
   количеством assistant messages.
4. `usage_dirty_dates.revision > 0`, delete-if-revision-current.
5. Не редактировать Flyway V7–V71 задним числом; исправления — только новой миграцией с preflight и тестами обновления.

### 5.2. Запись на границе UTC-полуночи

V74 регистрирует текущую дату в dirty queue в исходной транзакции. Маркер
становится видимым после commit, даже если дата успела закрыться.
Обработчик проверяет закрытие даты и подтверждает обработанную ревизию.
Проверку долгих транзакций и конкуренции выполняйте на PostgreSQL.

## 6. Приоритеты и эксплуатационные ограничения

### Реализованные гарантии

- Локальный permit берётся до открытия транзакции. Внутри read-only
  `REPEATABLE READ` берётся общий PostgreSQL advisory xact slot.
- V74 ставит в очередь в том числе текущую UTC-дату в исходной транзакции.
- Data quality читает итоговые показатели и проблемные модели в одном снимке.
- Буквальный `__unattributed__` запрещён V28, ограничение валидировано V30.
- Физические расходы и сверка целого плана доступны отдельными endpoint.

### Что измерять и сохранять при доработках

- Statement timeout действует на SQL. Полная задержка включает ожидание пула,
  несколько запросов и сериализацию. Измеряйте HTTP latency отдельно.
- Глубокий OFFSET и непагинированные models/daily требуют замеров.
- При нулевом знаменателе coverage показывайте отсутствие применимых записей.
- Session-level lock rollup удерживает соединение. Минимальный пул не исключает
  конкуренцию с отчётами и backfill.
- При изменении статусов повторяйте паритет live и rollup.
- Для ручного rebuild отдельно оценивайте операционное логирование и частоту вызовов.
- При изменении плана запросов сохраняйте непересекающиеся интервалы live и rollup.

---

## 7. Детальный анализ вычисления usage и стоимости

### 7.1. Подсчёт токенов

Для каждого сообщения ASSISTANT:

- `AVAILABLE`
  - **Содержимое отчёта** — `input_tokens`, `output_tokens` входят в `confirmed*`; `availableUsageMessages +1`.
- `PARTIAL`
  - **Содержимое отчёта** — Известные части входят только в `partialKnown*`, статус `partialUsageMessages +1`;
    подтверждёнными итоговыми токенами не считаются.
- `MISSING`
  - **Содержимое отчёта** — Токены не выдумываются, `missingUsageMessages +1`.
- `NOT_APPLICABLE`
  - **Содержимое отчёта** — Счётчик отдельно; в применимый denominator покрытия не включается.

`confirmedTotalTokens = confirmedInputTokens + confirmedOutputTokens`. Не нужно переименовывать эту сумму в
«суммарное потребление провайдера по всем попыткам». Для специальных типов токенов (cache write, cached input,
extended dimensions) текущая форма DTO не даёт полной многоразмерной себестоимости physical provider usage.

### 7.2. Денежная цена

| `pricing_status` | Смысл |
|---|---|
| `PRICED` | Цена вычислена; `cost_usd` включается в `knownCostUsd`. |
| `FREE` | Нулевая стоимость подтверждена правилами; входит в классификацию известных. |
| `UNPRICED` | Цена неизвестна; **не** считать бесплатным. |
| `CALCULATION_FAILED` | Ошибка расчёта; **не** считать бесплатным. |
| `NOT_APPLICABLE` | Цена не применима к записи, отдельно от `FREE`. |

`knownCostUsd` не означает «полная стоимость по всем attempts»; `pricingComplete` означает лишь отсутствие
UNPRICED/CALCULATION_FAILED среди **классифицированных сообщений**, а не доказанность цепочки физического billing
reconciliation.

### 7.3. Отказы и неопределённые исходы

`ai_response_status='REFUSED'` — это **ответ** провайдера в успешной ветви по HTTP/API, который может потреблять
оплачиваемые токены; не равен ошибке запроса или бесплатности. `INCOMPLETE` может расходовать токены.
`chat_turns.state='AMBIGUOUS'` не доказывает ни успех, ни отсутствие списания; текущая data-quality метрика
учитывает такие turns отдельно. В физической модели есть также STARTED/AMBIGUOUS/PROTOCOL_REJECTED attempts и
reconciliation queue (V66); их суммарную стоимость обязательно рассматривать отдельно для финансовой полноты.

### 7.4. Паритет live и rollup

Тестировать равенство не только известных сумм, но и **каждой классификации** по
модель/организация/пользователь/дата. В частности при смеси AVAILABLE/PARTIAL/MISSING,
PRICED/FREE/UNPRICED/FAILED, REFUSED/INCOMPLETE, null model, AMBIGUOUS turns, повторно записанных сообщений и
исторических изменений.

---

## 8. Безопасность и multi-tenancy

1. У `UsageController` стоит `@PreAuthorize(ADMIN/SUPER_ADMIN)`. У `UsageQueryService` class-level то же самое; он
   не полагается на проверку только в HTTP.
2. У ADMIN `organizationId = currentUser.getOrganizationId()` в criteria независимо от userId. Для явного доступа
   к другой организации `requireOrganizationAccess` выбрасывает `ForbiddenOperationException`.
3. У SUPER_ADMIN возможен намеренный глобальный scope `organizationId=null`; не нужно автоматически заменять его
   на платформенную организацию, иначе dashboard потеряет реальную глобальную аналитику.
4. Все SQL-фильтры bind-параметризованы; model/user ID не конкатенируются в SQL. Названия SQL-колонок и ORDER BY
   заданы статически.
5. `@PreAuthorize` при непосредственном вызове через `new UsageQueryService()` в unit тестах не срабатывает как
   AOP; полноценные security integration тесты проверяют путь через Spring proxy.
6. `currentUserEmail` — персональные данные; защищать логи, выгрузки, кеши и сторонний мониторинг от случайного
   распространения. Нельзя использовать email как high-cardinality metric tag.
7. Доверенные репозитории с необязательным organizationId предназначены для вызовов только из авторизованного
   application слоя. Полезно ограничить их применение package/architecture tests.
8. Запрос ручного rebuild не должен принимать произвольный SQL, только LocalDate; это условие соблюдено.

**Предлагаемые security-тесты:** `ADMIN` не видит чужие данные через
summary/users/models/daily/by-user/data-quality; `SUPER_ADMIN` корректно смотрит global и explicit organization;
plain USER и anonymous отклоняются; сериализация email не протекает в логи ошибок; spoofed organizationId не
меняет tenant scope.

---

## 9. Производительность, расписание и конкуренция

### 9.1. Реальное время выполнения отчёта

Выделять и измерять четыре фазы: (1) security/range validation, (2) получение транзакции/соединения и permit, (3)
выполнение одного/двух SQL statement, (4) mapping/serialization. `safeai.usage.query-timeout` сам по себе
покрывает не все фазы. Привязать Micrometer `duration` к типу отчёта и размеру группы, **без** user/organization
UUID в tags.

### 9.2. Rollup и блокировки

Advisory lock сериализует scheduler только для одного выбранного PostgreSQL lockKey; ручной
`UsageDirtyDateRepository.requestRebuild` не выполняет rebuild синхронно. Внутри dirty-processing `SELECT FOR
UPDATE` защищает конкретную дату. `rebuildDay` удаляет/записывает агрегаты транзакционно. При штатном `READ
COMMITTED` читатель видит старую завершённую версию или новую после commit, но не половину перестроенного дня.

**Предупреждение:** если есть сторонние SQL-writers в rollup-таблицы, гарантия нарушается; приложение должно быть
единственным авторизованным writer или права PostgreSQL должны запрещать сторонние изменения.

### 9.3. Что считать хорошими SLO

- `usage_report_p95/p99_duration` по типам без персональных tags;
- `rollup_last_completed_utc_date` и текущий лаг в закрытых днях;
- `usage_dirty_dates_pending` и возраст самой старой даты;
- время удержания advisory lock, доля пропусков из-за lock contention;
- число backfill дней за прогон, retries после SQL failure;
- Hikari active/idle/pending, DB connection acquisition time, lock waits;
- data quality: доля PARTIAL/MISSING и UNPRICED/CALCULATION_FAILED;
- physical attempts reconciliation backlog (это уже соседний модуль и отдельный ledger).

---

## 10. Имеющиеся тесты и недостающее покрытие

В репозитории обнаружены следующие тестовые файлы (присутствие тестов не означает, что они выполнялись в рамках
этого аудита):

**config:** `UsageJdbcClientsTest`, `UsagePropertiesTest`.

**controller:** `UsageControllerContractTest`, `UsageControllerSecurityTest`.

**dto:** `UsagePagingContractTest`, `UsageSummaryValueObjectsTest`.

**repository:** `UsageCoverageIntegrationTest`, `UsageEntityMappingContractTest`,
`UsageExplainAnalyzePerformanceIntegrationTest`, `UsageExplainPlanIntegrationTest`,
`UsageMigrationIntegrityIntegrationTest`, `UsagePaginationIntegrationTest`, `UsagePerformanceIntegrationTest`,
`UsagePricingCorrectnessIntegrationTest`, `UsageQueryContractTest`, `UsageQueryRepositoryTest`,
`UsageRollupAdvisoryLockIntegrationTest`, `UsageRollupReconciliationIntegrationTest`,
`UsageTenantIsolationIntegrationTest`, `UsageUtcAggregationIntegrationTest`.

**service:** `UsageQueryServiceTest`, `UsageReportExecutorTest`, `UsageRollupDayProcessorTest`,
`UsageRollupSchedulerTest`.

В существующих unit-тестах подтверждено наличие сценариев: tenant isolation, SUPER_ADMIN global scope, отказ чужой
организации, диапазоны и pagination, гибрид whole-day UTC + live edges, запрет oversized live fallback, watermark
continuity/backfill, lock contention, dirty-date processing, rollup atomicity. В репозитории имеются отдельные
PostgreSQL integration тесты, в том числе для конкурентного rollup и ценовой корректности.

### 10.1. Проверки корректности и приёмка

1. Физические отчёты: `UsagePhysicalSpendRepositoryIntegrationTest` исполняет SQL
   на изолированных таблицах PostgreSQL. Проверяет две организации, границы периода,
   несколько попыток плана, неизвестную цену и сверку всех попыток выбранного плана.
2. `UsageCoverageIntegrationTest` проверяет отказ сохранения буквального
   `__unattributed__` на схеме с миграциями Flyway.
3. Проверить общий admission и занятость пула при нагрузке с нескольких реплик.
4. Воспроизвести commit после UTC-полуночи и обработку маркера по V74.
5. Проверить единый снимок при конкурентной записи между запросами data quality.
6. Сверить live и rollup на матрице статусов и точных границах UTC.
7. Проверить изменения и удаления после watermark, ревизии и повтор после сбоя.
8. Измерить глубокий OFFSET и проверить UX при отсутствии применимых записей.

### 10.2. Локальные команды для проверки

Из каталога `backend` с Java 21 и Docker:

```bash
./mvnw test '-Dtest=ru.safeai.gateway.usage.**.*Test'
./mvnw test -Dtest=UsagePhysicalSpendRepositoryIntegrationTest,UsageCoverageIntegrationTest
./mvnw clean verify
```

Сверяйте число выполненных и пропущенных тестов в `target/surefire-reports`.

---

## 11. Рекомендуемый план модернизации без регрессий

### Финансовая сверка

Используйте отчёт по сообщениям для продуктовой аналитики, а отчёты по попыткам
и reconciliation для внутренней сверки. Сумма известной стоимости сопровождается
признаками полноты. Для расчёта по счетам поставщика требуется внешний источник.

### Пул и транзакции

Измерьте ожидание Hikari при одновременной работе нескольких реплик и rollup.
Сверьте `max-concurrent-reports` на всех репликах и проверьте общий бюджет PostgreSQL.

### Очередь пересчёта

Проверяйте возраст старейшей dirty date, движение watermark и повтор после сбоя.
При проверке UTC-полуночи воспроизведите commit после закрытия дня с V74.

### API и производительность

Показывайте знаменатель coverage рядом с процентом. Измерьте планы глубокого OFFSET,
непагинированных отчётов по моделям и датам и физической сверки.

---

## 12. Runbook: как проверять usage после инцидента

1. Проверить `usage_rollup_state.last_completed_date`, UTC-вчера и число дней отставания.
2. Проверить `usage_dirty_dates`: длину очереди, возраст oldest date, повторные revisions.
3. Убедиться, что scheduler запускается, advisory lock не занят зависшим процессом, а Hikari pool не исчерпан.
4. Сверить выборочный UTC-день тремя способами: `chat_messages` live, `usage_daily_org_model_rollups`,
   `usage_daily_quality_rollups`.
5. Сверить partial/missing usage и UNPRICED/CALCULATION_FAILED с data-quality API.
6. Для спорных денежных сумм просмотреть **отдельно** `model_execution_plans/attempts`, а не считать
   `knownCostUsd` абсолютной стоимостью.
7. При пересчёте отправить `POST /api/admin/usage/rebuild?date=YYYY-MM-DD` от SUPER_ADMIN и контролировать dirty
   marker до погашения; `202` не гарантирует завершения.
8. При отсутствии улучшения посмотреть slow SQL, EXPLAIN ANALYZE, блокировки, pg_stat_statements, Micrometer
   report duration и Hikari pending.
9. Не редактировать уже применённые Flyway миграции и не чистить dirty queue вручную до выяснения причины
   рассинхронизации.

---

## 13. Границы проверки

Документ сверён с рабочим деревом проекта. Каталог охватывает 42 Java-класса.
Проверены исходники, имена тестов и связанные миграции.
Результаты Maven, PostgreSQL и нагрузочных сценариев требуют отдельного прогона.

[Тесты Usage](../../../../../../test/java/ru/safeai/gateway/usage/README.md).
