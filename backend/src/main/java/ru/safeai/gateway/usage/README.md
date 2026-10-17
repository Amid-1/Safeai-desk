# SafeAI Desk — модуль `ru.safeai.gateway.usage`

> Техническое описание, аудит исходного кода и план повышения надёжности. 17 октября 2026 года. Целевой стек: Java 21, 
> Spring Boot / Spring Security / Spring JDBC / Spring Data, PostgreSQL, Flyway, HikariCP, Micrometer, JUnit 5, Mockito.
>
> **Область проверки:** 37 Java-файлов `backend/src/main/java/ru/safeai/gateway/usage/` в `Amid-1/Safeai-desk`, 
> ветка `main`, присланный текст модуля, тестовые классы `backend/src/test/java/ru/safeai/gateway/usage/` и 
> связанные SQL-миграции. Файлы сервисов, отсутствовавшие в сообщении, изучены из GitHub. Код **не изменялся**. 
> Здесь приведены результаты статического анализа; полная Maven-сборка и выполнение PostgreSQL-интеграционных тестов 
> в рамках этой проверки **не производились**.

---

## 1. Executive summary — что делает модуль и можно ли его считать завершённым

`usage` — подсистема аналитики использования AI, не самостоятельный процессор вызовов модели. Она формирует административные 
отчёты по пользователям, организациям, моделям и UTC-дням, суммирует известные токены и стоимость, сообщает об отсутствии 
либо неполноте данных, хранит дневные материализованные агрегаты (`rollup`), автоматически пересчитывает изменившуюся историю 
и ограничивает ресурсоёмкость запросов.

**Вердикт:** архитектура достаточно современная для 2026–2027 годов и существенно выше уровня типового CRUD: выделены 
критерии запросов и план выполнения, JDBC-агрегация, DTO с инвариантами, явная семантика `PARTIAL/MISSING/UNPRICED`, 
гибрид `rollup + live`, PostgreSQL advisory lock, watermark, инвалидация dirty dates, привязка отчетов к организации, 
наблюдаемость и интеграционные тесты. Убирать эти механизмы ради упрощения не следует.

**Но:** отчёты сейчас нельзя без дополнительной сверки считать достоверным счётом поставщика AI или доказанной основой 
для финансового списания. Основной факт отчётности — сохранённое сообщение `chat_messages` с ролью `ASSISTANT`, тогда 
как физические вызовы и их независимое подтверждение стоимости существуют также в `model_execution_attempts`. 
Платёжный/финансовый реестр обязан учитывать каждую физическую попытку и её определённость; это отдельная модель учёта. 
Отсутствующая или непроверяемая стоимость никогда не должна автоматически интерпретироваться как ноль.

### 1.1. Краткий статус

| Направление | Статус | Комментарий |
|---|---|---|
| Разграничение доступа | Хорошо | `ADMIN` видит собственную организацию, `SUPER_ADMIN` — глобальные и явно выбранные срезы. Проверки на уровне контроллера и сервиса. |
| API и валидация | Хорошо с оговорками | `Instant`, ограничение периода, размер страницы, запрет произвольной сортировки. У некоторых DTO самостоятельная валидация минимальна. |
| Известные vs неизвестные токены | Хорошо | `AVAILABLE`, `PARTIAL`, `MISSING`, `NOT_APPLICABLE` разнесены. Число токенов из `PARTIAL` не смешивается с подтверждённым. |
| Известная vs неизвестная стоимость | Хорошо для аналитики | `knownCostUsd` — только известная часть; полнота отражается счётчиками. Не финальное подтверждение полной физической себестоимости. |
| Дневные агрегаты | Хорошо | UTC, компактные SQL-таблицы, watermark, lookback, dirty-date queue. |
| Многорепличный rollup | Хорошо по конструкции | PostgreSQL advisory lock сериализует scheduler; результат зависит от надёжности соединения и транзакций. |
| Нагрузка и тайм-ауты | Нужны проверки | Semaphore локален для JVM; начало `@Transactional` предшествует допуску к работе. |
| Историческая пересборка | Хорошо с редкими границами | V71 ставит грязные даты в исходной транзакции; есть потенциальный граничный сценарий транзакции через UTC-полночь. |
| Финансовая полнота физического usage | **Не доказана** | Сообщения ≠ все физические попытки провайдера. Нужна сверка с `model_execution_attempts`, `chat_turns` и price book. |

### 1.2. Термины

- **Usage** — потребление AI: токены, число ответов, признаки полноты, стоимость.
- **Rollup** — предрассчитанный агрегат за закрытые UTC-дни; вместо повторного сканирования всех сообщений.
- **Live** — прямой SQL-расчёт по исходным записям для непокрытого rollup диапазона.
- **Watermark** — последняя UTC-дата, для которой последовательный первичный расчёт завершён.
- **Dirty date** — ранее закрытая дата, источник которой изменился и требует повторного расчёта.
- **Advisory lock** — сессионная кооперативная блокировка PostgreSQL для координации нескольких экземпляров приложения.
- **Tenant scope** — ограничение записей конкретной организации (`organization_id`).
- **Fail-closed** — при невозможности гарантировать требуемую безопасность или лимит операция отклоняется, а не незаметно разрешается.
- **Known cost** — достоверно классифицированная стоимость `PRICED` или `FREE`, а не сумма всех потенциальных затрат.
- **Physical attempt** — отдельный фактический вызов провайдера; один логический chat turn способен содержать несколько попыток при разрешённом retry.

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

1. `chat_messages` содержит сохранённые ответы ассистента, модель, состояние, usage/pricing status, подтверждённые/частичные токены и известную цену.
2. `chat_turns` предоставляет данные о логической операции и состоянии `AMBIGUOUS`; эти операции учитываются в **quality**, но не как полноценные оплаченные физические attempts.
3. `model_execution_plans` / `model_execution_attempts` (появились в V50, далее ужесточались V53 и V66) хранят physical-attempt evidence; текущая usage-аналитика напрямую их не агрегирует.
4. `usage_daily_user_model_rollups`, `usage_daily_org_model_rollups`, `usage_daily_quality_rollups` — производные таблицы, которые можно пересчитывать из исходников в рамках поддерживаемой модели.
5. `usage_rollup_state` хранит непрерывный watermark. `usage_dirty_dates` — ревизионную очередь повторных пересчётов.

Важно: **usage-аналитика, финансовое ledger и ограничение квот — разные задачи**. Не нужно подменять one-way аналитический `knownCostUsd` доказательством фактического долга клиента.

### 2.2. Правильная временная семантика

Вход — `Instant` (UTC-направленная точка времени). Интервал в SQL полуоткрытый: `[dateFrom, dateTo)`; граничное событие 
входит ровно в один интервал. Rollup выбирается только для **целых** UTC-дней, а частичные дни читаются live. 
`UsageDailySummaryResponse.aggregationZone` жёстко фиксирует `UTC`. Это правильный подход для серверной аналитики, 
если интерфейс явно показывает используемый часовой пояс.

---

## 3. Контракты REST API

Базовый путь: `/api/admin/usage`; общая защита контроллера `@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")`.

| HTTP | Endpoint | Назначение и контракт |
|---|---|---|
| GET | `/summary` | `Slice` потребления по `userId + model`, фильтр дат/модели, `page/size`. |
| GET | `/users` | `Slice` сгруппированного потребления по пользователю. |
| GET | `/models` | Полный список сводок по моделям, без постраничности. |
| GET | `/daily` | Дневная UTC-сводка, без постраничности. |
| GET | `/by-user/{userId}` | Потребление конкретного пользователя; ADMIN дополнительно ограничен своей организацией. |
| GET | `/by-organization/{organizationId}` | Потребление организации по пользователю/модели. |
| GET | `/by-organization/{organizationId}/users` | Пользователи явно выбранной организации. |
| GET | `/by-organization/{organizationId}/models` | Модели выбранной организации. |
| GET | `/by-organization/{organizationId}/daily` | UTC-дни выбранной организации. |
| GET | `/data-quality` | Полнота usage, pricing, ambiguity и топ-20 проблемных моделей. |
| POST | `/rebuild?date=YYYY-MM-DD` | Запрос принудительного пересчёта **закрытого** UTC-дня, **SUPER_ADMIN**. Ответ `202 Accepted`; запрос ставится в очередь, это не сообщение о завершённом пересчёте. |

`dateFrom/dateTo` принимаются в ISO DATE_TIME как `Instant`, отсутствующие границы разрешаются сервисом: конец — текущие 
часы приложения, начало — конец минус `defaultRange`. `UsagePageRequest`: `page` по умолчанию 0, `size` — 50, максимум 200. 
Для `models`, `daily` и списка проблемных моделей собственная пагинация отсутствует (у проблемных моделей SQL `LIMIT 20`).

**Авторизация:** наличие HTTP `@PreAuthorize` само по себе недостаточно для защиты от неконтроллерных вызовов; правильно, 
что `UsageQueryService` дополнительно проверяет tenant scope и имеет class-level `@PreAuthorize`. `UsageForcedRebuildService` 
тоже проверяет `SUPER_ADMIN` на сервисе. Для аутентификации используется `SafeAiUserPrincipal`, который не должен доверяться 
из параметров запроса, а создаётся Spring Security. Контроллер использует `@AuthenticationPrincipal` без `errorOnInvalidType=true`;
это скорее диагностическое улучшение, чем доказанная брешь.

**Семантика `Slice`:** не предоставляет общего количества страниц или записей. `first`, `last`, `hasNext`, 
`hasPrevious` — навигационные флаги, не `Page` с `totalElements`. Это снижает стоимость запросов, но клиент не должен 
рассчитывать абсолютное количество результатов.

---

## 4. Полный каталог Java-классов — 37 файлов

Ниже назначение, ключевые поля, методы, инварианты и точечные замечания по каждому исходнику. Простые getter-аксессоры 
record не описываются по одной строке; анализируются их контракт и вычисляемые поля.

### 4.1. `usage.config` — 4 файла

#### 4.1.1. `UsageConfiguration.java`

- `@Configuration(proxyBeanMethods=false)` — современная облегчённая конфигурация; проксирование вызовов `@Bean` внутри 
- класса здесь не требуется.
- `@EnableConfigurationProperties(UsageProperties.class)` — регистрация типизированных свойств конфигурации.
- `@Bean UsageJdbcClients usageJdbcClients(DataSource, UsageProperties)` — создаёт специфичную для usage обёртку без добавления 
- второго `JdbcTemplate` как самостоятельного бина.
- **Верно:** не создаёт неявную двусмысленность autowiring существующих JDBC-клиентов.
- **Риск:** переданный `DataSource` должен быть именно транзакционно управляемым, общий datasource Spring; сам класс это 
- не проверяет.

#### 4.1.2. `UsageHikariPoolInvariantVerifier.java`

- `ApplicationRunner.run(@NonNull ApplicationArguments)` — проверяет pool на старте приложения, только если активен `prod/production` и включён rollup.
- Поля `dataSource`, `environment`, `usageProperties` валидируются через `Objects.requireNonNull`.
- Требует `dataSource instanceof HikariDataSource` и `maximumPoolSize >= 3`.
- **Верно:** fail-fast/ fail-closed при неподтверждённом pool. Отдельная сессия advisory lock плюс транзакция worker действительно 
- требуют нескольких соединений.
- **Ограничение:** размер 3 — необходимое минимальное предположение, а **не доказательство** отсутствия starvation, когда 
- параллельно идут отчёты, HTTP и прочие background jobs. Wrapped datasource (`LazyConnectionDataSourceProxy`, другие обёртки) 
- может быть корректным, но отвергнутым этим `instanceof` — осознанная жёсткая политика, требующая проверки инфраструктуры.
- **Рекомендация:** integration-тест на реальный пул при `maxConcurrentReports`, нескольких репликах и активном rollup; 
- измерять `hikaricp.connections.pending`, время ожидания и connection acquisition timeout.

#### 4.1.3. `UsageJdbcClients.java`

- Поля `NamedParameterJdbcTemplate query` и `JdbcTemplate rollup` ссылаются на один `DataSource`, но имеют **разные конфигурации** `queryTimeout` и `fetchSize`.
- Конструктор валидирует зависимости, задаёт `queryTemplate.setQueryTimeout(properties.queryTimeoutSeconds())`, `setFetchSize(properties.fetchSize())`, аналогично timeout для rollup.
- `query()` / `rollup()` — открывают соответствующую абстракцию репозиториям.
- **Верно:** JDBC для агрегирующих запросов с параметрами, отсутствие SQL-инъекции через bind-параметры, разграничение тайм-аутов.
- **Ограничение:** JDBC statement timeout — тайм-аут конкретного SQL statement, а не жёсткий deadline всего отчёта с подготовкой, 
- ожиданием пула, несколькими SQL-запросами и сериализацией ответа. `fetchSize` не гарантирует server-side streaming при всех 
- условиях драйвера и transaction/autocommit.

#### 4.1.4. `UsageProperties.java` и вложенный `Rollup`

- Типизированный immutable record свойств `safeai.usage`; канонический конструктор выставляет дефолты и валидирует положительные
- диапазоны, связанные ограничения, размер страницы, fetchSize и максимум конкурентных отчётов.
- `defaultRange=30d`, `maxRange=366d`, `maxLiveRange=31d`; `maxPageSize=200`; `queryTimeout=10s`, `slowQueryThreshold=2s`, `fetchSize=200`, `maxConcurrentReports=4`.
- Методы `queryTimeoutSeconds()` и `rollupStatementTimeoutSeconds()` переводят `Duration` в секунды **с округлением вверх**: частично заданная секунда не ослабляет deadline до меньшего целого; выход за `int` отклоняется.
- Вспомогательные `positiveDuration`, `atLeastOneSecond`, `boundedPositiveInt`, `durationSecondsCeil` — проверка входной конфигурации, `Math.addExact/toIntExact` предотвращает молчаливое переполнение.
- Вложенный record `Rollup`: `enabled=true`, `cron="0 */10 * * * *"`, `lookbackDays=3`, `backfillDaysPerRun=31`, `advisoryLockKey=8_026_001`, `statementTimeout=2min`. `defaults()` и `isEnabled()`.
- **Плюс:** несогласованность `defaultRange > maxRange` и `maxLiveRange > maxRange` отклоняется на старте.
- **Замечание:** сам record не валидирует синтаксис CRON при создании; Spring Scheduling обычно выявляет ошибку регистрации расписания. `advisoryLockKey` может совпасть с ключом другой задачи при ручной конфигурации. `Duration.toNanos()` у slow-threshold при экстремальном значении может переполниться — не подтверждённый эксплуатационный дефект при обычных настройках, но нужна верхняя разумная граница.

### 4.2. `usage.controller` — 2 файла

#### 4.2.1. `UsageController.java`

- Поле `usageQueryService` — один application-service вход для всех отчётов.
- Методы `summary`, `users`, `models`, `usersByOrganization`, `modelsByOrganization`, `daily`, `dailyByOrganization`, `byUser`, `byOrganization`, `dataQuality`: маппинг конкретных REST endpoints без SQL и бизнес-логики.
- `@Validated` и `@Valid @ModelAttribute` проверяют фильтры; `@PreAuthorize` ограничивает роли. `@AuthenticationPrincipal` передаёт principal в сервис; не принимает organization ID из тела без последующей проверки.
- **Хорошо:** тонкий контроллер; `PagedResponse.from` отделяет Spring Data `Slice` от внешнего JSON-контракта.
- **Проверить:** HTTP 400/403/503 контракт при некорректном `Instant`, `@ModelAttribute` и особых кодировках имени модели; тесты покрытия security есть. По дополнительной защите типов можно применить `@AuthenticationPrincipal(errorOnInvalidType=true)` как в `user`/`organization`, но это не обязательное исправление безопасности.

#### 4.2.2. `UsageRebuildController.java`

- `@PreAuthorize("hasRole('SUPER_ADMIN')")` на уровне класса, `requestRebuild(LocalDate date)` в POST `/api/admin/usage/rebuild`.
- `@DateTimeFormat(iso=DATE)` разбирает дату, `@ResponseStatus(ACCEPTED)` правильно обещает только постановку задачи.
- Сервис повторно проверяет роль и закрытый UTC-день.
- **Улучшение:** добавить структурированный аудит административного rebuild-запроса (кто/когда/дата/причина) и HTTP-отображение `IllegalArgumentException` в понятную 400, если оно ещё не централизовано глобальным обработчиком.

### 4.3. `usage.dto` — 14 файлов

#### 4.3.1. `PagedResponse<T>.java`

- Поля `content, page, size, first, last, hasNext, hasPrevious`.
- Компактный конструктор `List.copyOf` исключает изменение списка после формирования ответа; проверяет `page >= 0`, `size >= 1`.
- `from(Slice<T>)` отображает навигационную метаинформацию Spring Data без expensive COUNT. Корректно.
- **Нюанс:** `List.copyOf` запрещает `null`-элементы. Это полезная fail-fast политика.

#### 4.3.2. `UsageCostSummary.java`

- `knownCostUsd`: только доказанная сумма, nonnegative; если null, заменяется **на ноль известной части**, не на утверждение об общей цене. Этот нюанс обязателен в интерфейсе.
- `currency` — строго `USD`, `pricedMessages`, `freeMessages`, `unpricedMessages`, `pricingFailedMessages`, `pricingNotApplicableMessages` — независимые неотрицательные классификаторы.
- Компактный конструктор вычисляет `pricingComplete = (unpriced == 0 && pricingFailed == 0)`; переданный boolean намеренно не считается доверенным.
- Дополнительный конструктор без currency/complete — совместимость, всегда выбирает USD.
- `classifiedMessages()` — `Math.addExact` исключает переполнение счётчиков.
- **Верно:** `unpriced` и `calculation failed` не замаскированы под FREE.
- **UX-опасность:** финансовое приложение не должно показывать `knownCostUsd = 0` без индикаторов неполноты/фактических attempts. При обновлении контрактов полезны `costCompleteness` и `sourceOfCost`, но требуется сквозное изменение SQL/API.

#### 4.3.3. `UsageDailySummaryResponse.java`

- `usageDate` и `aggregationZone="UTC"`, `responses`, `usage`, `cost`.
- Проверяет дату, строго поддерживаемый UTC и все агрегаты; `UsageSummaryInvariants.validate` требует согласованную классификацию сообщений. Правильно.

#### 4.3.4. `UsageDataQualityResponse.java`

- Содержит число сообщений ассистента, stored completed/failed, отсутствие модели, число ambiguous операций, usage/cost, проценты покрытия и проблемные модели.
- В конструкторе проверяются все счётчики; `UsageSummaryInvariants` согласует количество классификаций.
- `usageCoveragePercent = available / (available + partial + missing)`, `pricingCoveragePercent = (priced + free) / (priced + free + unpriced + failed)`; округление `HALF_UP`, 2 знака.
- Если applicable = 0, возвращается 100.00% — **математическая конвенция**, но в интерфейсе может выглядеть как «полнота подтверждена», когда данных вообще нет. Нужны `applicableCount` или статус `NO_DATA`, при сохранении обратной совместимости.
- `problemModels = List.copyOf` — immutable.
- **Критически:** `ambiguousProviderOperations` в этом DTO относится к логическим turns в состоянии `AMBIGUOUS`, а не ко всем physical attempts с неизвестным итогом; не выдавать этот показатель за полное финансовое покрытие.

#### 4.3.5. `UsageDateFilter.java`

- `Instant dateFrom/dateTo` с `@DateTimeFormat(ISO.DATE_TIME)`; null разрешён намеренно — default range устанавливает сервис.
- Сам DTO не проверяет порядок дат: это делает `UsageQueryService.normalizeRange` с общей конфигурацией.

#### 4.3.6. `UsageDateModelFilter.java`

- То же, плюс `@Size(max=100) String model`. `normalizedModel()` делает `trim()` и возвращает null для blank.
- На уровне сервиса повторная проверка длины нужна для programmatic вызовов. Проверено: она есть.
- Изменение Unicode-whitespace при `trim` vs `strip` может быть заметно для необычных значений — если модель является идентификатором, следует выбрать одну стабильную каноникализацию; без смены контракта автоматически не менять.

#### 4.3.7. `UsageModelSummaryResponse.java`

- `model` непустой, `trim`, nonnull `responses/usage/cost`; межобъектные инварианты.
- Чистая DTO, хорошо.

#### 4.3.8. `UsagePageRequest.java`

- `page`: `@Min(0)`; `size`: `@Min(1) @Max(200)`; default 0/50.
- `toPageable()` вызывает `PageRequest.of`, который откажет при неверном числе даже при обходе Bean Validation. Сервис дополнительно проверяет `properties.maxPageSize()` и запрет сортировки.
- Возможное несоответствие: аннотация верхнего предела захардкожена `200`, а эксплуатационная конфигурация может снижать `maxPageSize`; сервис компенсирует, но клиент получит разные сообщения об ошибке в зависимости от лимита.

#### 4.3.9. `UsageProblemModelResponse.java`

- `model` непустая, `trim`, `usageProblems` и `pricingProblems` неотрицательны.
- SQL возвращает максимум 20, отсортированных по количеству проблем; это **top-N**, не полный список проблемных моделей.

#### 4.3.10. `UsageResponseSummary.java`

- Разделяет `assistantMessages`, `completedResponses`, `refusedResponses`, `incompleteResponses`, `failedMessages`; вычисляет `unclassifiedMessages` как остаток.
- `Math.addExact` проверяет переполнение; классифицированных записей не может быть больше `assistantMessages`.
- **Правильно:** «неизвестная классификация» остаётся видимой, а не становится COMPLETED.

#### 4.3.11. `UsageSummaryInvariants.java`

- Package-private utility с двумя `validate` overload: проверяет `usage.classifiedMessages() == assistantMessages` и `cost.classifiedMessages() == assistantMessages`.
- **Хорошо:** обнаруживает потерю категории в SQL/RowMapper, но не гарантирует, что каждый исходный физический вызов представлен в отчёте.

#### 4.3.12. `UsageSummaryResponse.java`

- `(userId, currentUserEmail, model, responses, usage, cost)`; `userId` — неизменяемый исторический идентификатор, email — **текущее** отображаемое значение.
- Проверка nonnull/nonblank, trim и агрегатных инвариантов.
- **Конфиденциальность:** email — PII. API обязательно должен оставаться под авторизацией; логи/report query tags не должны содержать адресов пользователей.

#### 4.3.13. `UsageTokenSummary.java`

- Отдельно хранит `confirmedInput/Output/Total`, `partialKnownInput/Output/Total`, четыре класса completeness и вычисленный boolean `usageComplete`.
- `confirmedTotalTokens` и `partialKnownTotalTokens` **пересчитываются** через `Math.addExact`, не доверяют входным готовым суммам.
- `usageComplete = partial == 0 && missing == 0`. Это хорошая система выразительных DTO, где неизвестное usage не приравнено к нулевому.

#### 4.3.14. `UsageUserSummaryResponse.java`

- `(userId, currentUserEmail, responses, usage, cost)`; проверки userId, email, nonnull агрегатов и межобъектных инвариантов.
- Плюс: не смешивает email как mutable display field с стабильным `userId`.

### 4.4. `usage.repository` — 11 файлов

#### 4.4.1. `JdbcUsageQueryRepository.java`

- Реализация `UsageQueryRepository` на `NamedParameterJdbcTemplate`.
- `findSummary` собирает facts, группирует `user_id + current_user_email + model`, сортирует по `user_id, model`, выдаёт Slice.
- `findUsers`: группирует по пользователю, `ORDER BY user_id`.
- `findModels`: группирует по модели, `ORDER BY model`. Не пагинируется.
- `findDaily`: группирует по UTC-дате, `ORDER BY usage_date DESC`. Не пагинируется.
- `findDataQuality`: выполняет минимум **два SQL-запроса** — aggregate и `findProblemModels`. Если транзакционная изоляция READ COMMITTED и между запросами появились новые записи, снимки могут различаться. Для финального финансового отчёта требуется единая согласованная snapshot-изоляция; для обзорной панели надо документировать допустимую кратковременную расходимость.
- `findProblemModels`: считает partial/missing и unpriced/calculation-failed по моделям, `LIMIT 20`. SQL согласован с семантикой известных/неизвестных записей.
- `addPage`: `LIMIT pageSize+1`, `OFFSET pageable.getOffset()`, проверка переполнения страницы через `Math.addExact`.
- `slice`: по лишней строке вычисляет `hasNext` и возвращает `SliceImpl`.
- `executeInternallyGeneratedQuery` и `executeRequiredInternallyGeneratedQueryForObject` — централизованные JDBC-boundary. Вложенные SQL-куски статичны, user values передаются через `MapSqlParameterSource`.
- **Риски:** OFFSET на глубокой странице — линейная потеря производительности; `getOffset` long, PostgreSQL умеет большой OFFSET, но запрос способен быть тяжёлым. На будущее keyset/cursor pagination и строгая верхняя глубина.
- **Security:** `@SuppressWarnings(SqlResolve/SqlNoDataSourceInspection)` снимает подсказки IDEA только для этого класса, не является эквивалентом SQL-тестов; динамическая SQL-композиция требует snapshot-тестов, объяснённых ниже.

#### 4.4.2. `UsageDirtyDateRepository.java`

- `findPending(latestClosedDate, limit)`: читает ограниченную пачку грязных дат, не блокируя всё при простом чтении.
- `lockDate(date)`: `SELECT ... FOR UPDATE` в отдельной транзакции.
- `acknowledgeIfUnchanged(DirtyDate)`: `DELETE` только при совпадении `revision`, ожидает ровно одну затронутую строку.
- `requestRebuild(date)`: UPSERT в `usage_dirty_dates`, увеличивает revision и заполняет `updated_at` через `clock_timestamp()`.
- `DirtyDate(date, revision)` — nonnull date и revision >= 1.
- **Верно:** ревизия устраняет lost-update при изменении источника во время повторного построения; если rebuilding fails, dirty marker не пропадает.
- **Проверить:** вызовы `acknowledgeIfUnchanged` вне одной транзакции с `lockDate + rebuildDay` запрещены контрактом, но репозиторий не может доказать это одной сигнатурой. Сервис использует корректный транзакционный контекст.

#### 4.4.3. `UsageFactQueryBuilder.java`

- Основной генератор SQL-плана: `userFacts`, `organizationFacts`, `qualityFacts` объединяют rollup-фрагмент и один/два live-фрагмента в `WITH facts AS (...)` с `UNION ALL`.
- `baseParameters` регистрирует optional organizationId, userId, model; `appendRollupScope` и `appendLiveScope` выстраивают фильтры.
- `appendUserLiveFragments`: `chat_messages m` JOIN `chat_sessions s` JOIN `users u`, только `m.role='ASSISTANT'`; grouping по `s.user_id`, текущему email и модели.
- `appendOrganizationLiveFragments`: только `chat_messages`, фильтр по организации и UTC grouping для daily.
- `qualityFacts`: сводки `usage_daily_quality_rollups`, live counts для ASSISTANT и отдельных `AMBIGUOUS` turns, не смешивая «logical ambiguity» с количеством сообщений.
- `facts(...)` отклоняет пустой план; имена bind-параметров `liveFrom0`, `...1` вычисляются из внутреннего индекса и не зависят от пользовательского SQL-текста.
- `UNATTRIBUTED_MODEL="__unattributed__"` используется при `m.model IS NULL` как synthetic label.
- **Очень важно:** synthetic label потенциально совпадёт с настоящим именем модели, если такой идентификатор когда-либо разрешён. Нужен строгий invariant «модель не может буквально называться `__unattributed__`» или отдельное поле атрибуции, прежде чем этот label использовать как устойчивый аналитический ключ.
- **Проверка:** порядок/типы столбцов во всех ветвях `UNION ALL`, особенно `qualityFacts`, должны совпадать — для этого нужны реальные PostgreSQL integration tests, а не только компиляция Java.

#### 4.4.4. `UsageFactSql.java`

- Package-private `record(String cte, MapSqlParameterSource parameters)` — простой перенос SQL+bind values из builder в executor.
- **Рекомендация:** immutable sql text плюс копия параметров при передаче, если появятся переиспользование одного объекта между потоками. Сейчас параметры создаются локально на запрос и переиспользование не просматривается.

#### 4.4.5. `UsageInstantRange.java`

- `record(Instant from, Instant to)` — nonnull и строго `from < to`.
- Инвариант исключает пустые и обратные интервалы, помогает корректному расчёту live-срезов.

#### 4.4.6. `UsageQueryCriteria.java`

- Обязательные `dateFrom/dateTo`, опциональные `organizationId/userId/model`.
- Проверяет порядок дат, trim/nullable model. Нет встроенной проверки роли — она **должна** проходить в `UsageQueryService` до вызова репозитория.
- Обращать внимание: произвольный программный вызов repository в другом сервисе, минуя application authorization, потенциально способен сформировать глобальный запрос. Это доверенная внутренняя граница, а не публичный repository API для любого модуля.

#### 4.4.7. `UsageQueryPlan.java`

- `(rollupFrom, rollupToExclusive, liveRanges)`; оба rollup-предела обязаны присутствовать совместно; начальная дата меньше конца.
- `List.copyOf` и `hasRollupRange()` обеспечивают простой immutable-план.
- **Не проверяет самостоятельно**, что liveRanges не пересекаются друг с другом или с rollup. Сейчас план строится контролируемым `UsageQueryService`; опасность возникнет при появлении второго конструктора плана. Можно добавить `validateNoOverlap()` для fail-fast.

#### 4.4.8. `UsageQueryRepository.java`

- Интерфейс аналитического чтения: `findSummary`, `findUsers`, `findModels`, `findDaily`, `findDataQuality`.
- **Хорошо:** сервис не знает о JDBC, builder не знает о REST, возможны альтернативные query backends без изменения API.

#### 4.4.9. `UsageRollupStateRepository.java`

- `findLastCompletedDate`: читает watermark по константе `JOB_NAME=usage-daily-rollup`.
- `findEarliestUsageDate`: берёт минимум UTC-даты по `ASSISTANT chat_messages` и завершённым `AMBIGUOUS chat_turns`.
- `markCompleted`: UPSERT с условием «та же дата/предыдущая дата или следующий непрерывный UTC-день», не допускает пропусков внутри уже инициализированного watermark; бросает exception, если `affectedRows != 1`.
- `executeWithAdvisoryLock(long lockKey, Runnable action)`: берёт выделенное соединение, через общий `PostgresAdvisoryLockExecutor.tryExecute` удерживает сессионную блокировку, вызывает action, затем закрывает соединение.
- **Верно:** ни один из нескольких экземпляров scheduler не должен одновременно перестраивать один и тот же день при одинаковом advisoryLockKey.
- **Риск:** отдельное connection остаётся занятым во время выполнения полного backfill/lookback/dirty rebuild, а worker берёт другое; учитывайте нагрузку на Hikari.
- **Границы:** при новой пустой установке допускается инициализация watermark на последнем закрытом дне без предыдущих дней, потому что источников для них не обнаружено. Позднее появление исторических данных должно попадать в V71 dirty queue; это нужно регулярно проверять integration-тестом.

#### 4.4.10. `UsageRowMapperSupport.java`

- Статические singleton `RowMapper` для summary, user summary, model summary, daily summary, problem models, quality aggregate.
- `mapResponses`, `mapUsage`, `mapCost` централизуют чтение классификаторов, суммы known cost и токенов. `mapDailySummary` жёстко проставляет `UTC`.
- Внутренний `QualityAggregate` — carrier промежуточного результата, не API.
- **Хорошо:** DRY-мэппинг и DTO-инварианты ловят классификационные ошибки.
- **Замечание:** `ResultSet.getLong` возвращает 0 при SQL NULL; агрегирующие `coalesce(sum(...),0)` защищают текущие запросы. Если SQL изменится, нужно проверять `wasNull()` для полей, где NULL означает ошибку, а не легальный ноль.

#### 4.4.11. `UsageSqlFragments.java`

- Константа `UNATTRIBUTED_MODEL`, четыре крупные **статические** SQL-проекции: `AGGREGATE_COLUMNS`, `LIVE_AGGREGATE_COLUMNS`, `AGGREGATE_COLUMNS_WITHOUT_ASSISTANT`, `LIVE_AGGREGATE_COLUMNS_WITHOUT_ASSISTANT`.
- `assistant_message_count` — количество сообщений с ролью ASSISTANT; ответы классифицируются в COMPLETED/REFUSED/INCOMPLETE/FAILED, остальные остаются `unclassified`.
- `input_tokens/output_tokens` учитываются как **подтверждённые** только для `usage_status='AVAILABLE'`; `PARTIAL` вынесен в отдельные поля.
- `known_cost_usd` суммирует `cost_usd` только при `pricing_status IN ('PRICED','FREE')`; остальная стоимость неизвестна и выражена счётчиками.
- **Верно:** нет некорректного «неизвестно = бесплатно» на уровне понятий DTO.
- **Системный предел:** SQL считает *сообщения*; стоимость физической попытки, не породившей корректный `chat_messages`-ответ, в денежный итог не попадёт. При использовании в биллинге это серьёзный разрыв.
- **Поддерживаемость:** большие повторяющиеся SQL-фрагменты требуют автоматических регрессионных сценариев для каждой категории и единой схемы типов `UNION ALL`; вручную менять их опасно.

### 4.5. `usage.service` — 6 файлов

#### 4.5.1. `UsageDirtyDateProcessor.java`

- `rebuildIfPending(LocalDate date)` с `@Transactional(REQUIRES_NEW, isolation=READ_COMMITTED)`.
- `dirtyDates.lockDate` берёт FOR UPDATE; отсутствующий marker → false.
- `dayProcessor.rebuildDay(date, false)` пересчитывает агрегаты **в этой же транзакции**; `acknowledgeIfUnchanged` сверяет revision и подтверждает погашение dirty marker.
- **Хорошо:** атомарность «обновить результат + удалить уведомление», отсутствие бесследной потери dirty-наблюдения.
- **Граница:** при массовых dirty-датах длительность ожидания lock и нагрузка на пул требуют метрик.

#### 4.5.2. `UsageForcedRebuildService.java`

- `request(LocalDate usageDate)` с `@PreAuthorize(SUPER_ADMIN)` и `@Transactional`.
- Через внедрённый `Clock` и UTC проверяет, что дата строго раньше сегодняшней; вызывает `dirtyDates.requestRebuild`.
- **Правильно:** не перестраивать открытый UTC-день как закрытый материализованный агрегат, не обещать синхронного rebuild.
- **Потенциальное улучшение:** темп/лимит массовых rebuild запросов, аудит акторов, граф очереди. Сравнение дня с `Clock` приложения не тождественно времени БД — существенный риск только при плохой синхронизации NTP/подменённом Clock, что нужно тестировать.

#### 4.5.3. `UsageQueryService.java`

- `@Service` + class-level `@PreAuthorize(ADMIN/SUPER_ADMIN)`.
- Запросы: `getUsageSummary`, `getUsageByUsers`, `getUsageByModels`, `getUsageDaily`, `getUsageByUsersForOrganization`, `getUsageByModelsForOrganization`, `getUsageDailyForOrganization`, `getUsageByUserId`, `getUsageByOrganizationId`, `getDataQuality`.
- Вспомогательные `normalizeRange`, `organizationCriteria`, `requireOrganizationAccess`, `buildPlan`, `buildLiveRanges`, `liveOnlyPlan`, `validateLiveFallback`, `ceilUtcDay`, `floorUtcDay`, `min`, `normalizeModel`, `requireCurrentUser`, `requirePageable`, `isSuperAdmin`.
- `requireOrganizationAccess` исключает случайное чтение чужого tenant у ADMIN; global scope даётся лишь SUPER_ADMIN. `getUsageByUserId` у ADMIN устанавливает одновременно `userId` и собственный `organizationId` — корректно.
- `normalizeRange`: оба `Instant`, настройка дефолтного диапазона, `maxRange`, reject on invalid order.
- `buildPlan`: если rollup выключен или watermark отсутствует, только live; иначе вырезает **внутренний закрытый UTC-период**, подтверждённый watermark, а края обрабатывает live. Нет двойного подсчёта границ.
- `validateLiveFallback`: суммирует длительность live-диапазонов; при превышении `maxLiveRange` возвращает 503, вместо внезапного тяжелого scan на год.
- `requirePageable`: диапазон size, запрет сортировки клиента — стабильные серверные ORDER BY, предотвращение SQL-конкатенации произвольных колонок.
- **Очень важный риск ресурсов:** здесь же стоят `@Transactional(readOnly=true)` на методах. Spring начинает transaction advice **до входа в тело метода**, а `reportExecutor.execute(... tryAcquire semaphore)` вызывается позже. В зависимости от TransactionManager и lazy acquisition соединение может быть запрошено **до локального admission control**. При высокой конкуренции semaphore не обязательно защищает Hikari/DB. Избежать этого можно, переставив admission control на внешнюю не-транзакционную границу, а read-only transaction выполнять уже после успешного получения permit (например отдельный proxied component/TransactionTemplate), сохранив все проверки tenant scope; это изменение требует интеграционных тестов.
- **Согласованность:** `getDataQuality` вызывает два SELECT в `@Transactional(readOnly=true)` без явной `REPEATABLE_READ`, значит под PostgreSQL READ COMMITTED snapshots могут различаться. Это не доказанная регулярная ошибка, но риск противоречивых счетчиков при высокой записи.

#### 4.5.4. `UsageReportExecutor.java`

- `Semaphore(properties.maxConcurrentReports(), true)` ограничивает конкурентные отчёты в рамках **одной JVM**. `tryAcquire` не ставит пользовательский запрос в бесконечную очередь, а возвращает 429.
- `execute(report, Supplier<T>)`: нормализует имя отчёта, получает permit, `Timer.Sample`, выполняет action, различает `QueryTimeoutException`/другую RuntimeException, всегда `permits.release()` в finally.
- `timers`, `rejectedCounters`, `timeoutCounters`, `errorCounters` — concurrent maps Micrometer instruments, теги только по типу отчёта.
- Gauge `available_permits`, `Timer` с percentile histogram, counters rejected/timeout/error; ошибки метрик логируются, не ломая безопасность передачи permit.
- `slowQueryThresholdNanos` + `System.nanoTime` оценивает длительность, предупреждает о медленных отчётах.
- **Плюс:** нет произвольных пользовательских UUID/email в метриках, кардинальность bounded при использовании фиксированных labels сервиса.
- **Ограничение:** cluster concurrency = число реплик * config. 429 не гарантирует глобальный предел; при росте кластера нужен DB workload governance или распределённый admission. На уровне JVM semaphore не предотвращает тяжёлые `findModels/daily`, сканирующие все доступные группы за период.

#### 4.5.5. `UsageRollupDayProcessor.java`

- `rebuildDay(LocalDate usageDate, boolean advanceWatermark)`: `@Transactional`; вычисляет полуоткрытые UTC-границы, удаляет агрегаты **именно одной даты** из трёх таблиц, вставляет пересчитанные user/org/quality rollups, дополняет ambiguous counts; при первичном backfill с `advanceWatermark=true` двигает watermark.
- `insertUserRollups`: группировка по `organizationId + userId + model` из `chat_messages`/`chat_sessions`; `coalesce(model, '__unattributed__')`.
- `insertOrganizationRollups`: группировка по `organizationId + model`.
- `insertQualityRollups`: качество usage/pricing по организации и дню.
- `upsertAmbiguousTurnCounts`: сохраняет агрегат количества `chat_turns.state='AMBIGUOUS'` по `completed_at`; не интерпретирует физическую стоимость.
- **Хорошо:** delete+insert+watermark под одной транзакцией не показывают полупостроенную дату при нормальных transaction semantics PostgreSQL.
- **Риски:** полный rebuild закрытого дня дорог на крупных данных; нужны индексы/EXPLAIN ANALYZE, monitoring длительности, deadlocks/lock waits и growth dirty queue. `@Transactional` даёт атомарность только при вызове через Spring proxy; реальные вызовы из scheduler идут через отдельный bean, что корректно.

#### 4.5.6. `UsageRollupScheduler.java`

- `@ConditionalOnProperty(enabled=true)` и `@Scheduled(cron="${safeai.usage.rollup.cron:0 */10 * * * *}", zone="UTC")`.
- `rebuild()`: берёт advisory lock, вычисляет вчерашний UTC-день, выполняет `backfill`, `reconcileLookback`, `rebuildDirtyDates`.
- `backfill`: определяет следующую последовательную дату из watermark или самого раннего источника; не больше `backfillDaysPerRun`; при пустой истории инициализирует закрытый watermark.
- `reconcileLookback`: пересчитывает последние `lookbackDays`, исключая только что построенные даты.
- `rebuildDirtyDates`: читает bounded queue до watermark и вызывает отдельный `UsageDirtyDateProcessor` transaction per day.
- **Верно:** multi-replica mutex, bounded work per run, повторение короткого исторического окна и дополнительные trigger-driven invalidations.
- **Ограничение:** lookback и dirty batch вместе с backfill могут выполняться долго, удерживая advisory lock connection. Планирование каждые 10 минут не является гарантией, что latency/свежесть rollup составит 10 минут.

---

## 5. Взаимодействие с PostgreSQL: миграции, индексы, ограничения

| Миграция | Фактическая роль в usage |
|---|---|
| `V7__usage_quotas_and_rollups.sql` | Базовые таблицы квот и дневных rollup, составные ключи и основные индексы. |
| `V8__usage_chat_messages_indexes.sql` | Индексы для аналитического чтения исходных сообщений. |
| `V10__schema_hardening_timestamps_audit_rollups.sql` | Значения/constraints агрегатов, временные поля. |
| `V16__preserve_usage_history_on_user_delete.sql` | `chat_sessions.user_id` с `ON DELETE RESTRICT`: исторические данные не должны теряться при физическом удалении пользователя. |
| `V17__ai_usage_and_pricing_metadata.sql` | Явные статусы usage/pricing, nullable cost при неизвестной цене, SQL-инварианты. |
| `V28__usage_analytics_hardening.sql` | Богатые категориальные rollup counters, `numeric(30,12)` для cost, quality-таблица, согласованность сумм и счётчиков. |
| `V29__usage_analytics_indexes.sql` | Индексы live/quality дат и организаций. |
| `V30__validate_usage_constraints.sql` | Валидация накопленных usage constraints. |
| `V31__usage_user_report_index.sql` | Индексы пользовательских срезов. |
| `V34__chat_ambiguous_usage_quality.sql` | `ambiguous_provider_operation_count` в quality rollup. |
| `V50__ai_execution_attempt_usage_evidence.sql` | Физические attempt evidence; текущие агрегаты usage читают их не напрямую. |
| `V53__physical_attempt_transition_and_pricing_integrity.sql` | Строгие переходы physical attempts, защитные pricing invariants. |
| `V66__physical_response_and_pricing_scheme.sql` | Уточнение physical response/pricing scheme; важно для будущего reconciliation. |
| `V71__usage_dirty_dates_and_recalculation_queue.sql` | Очередь dirty UTC dates, database triggers после INSERT/UPDATE/DELETE сообщений и turns. |

### 5.1. Непустые инварианты схемы, которые нельзя ослаблять

1. `chat_messages.usage_status`/`pricing_status` должны сохранять различие AVAILABLE/PARTIAL/MISSING/NOT_APPLICABLE и PRICED/FREE/UNPRICED/CALCULATION_FAILED/NOT_APPLICABLE.
2. `cost_usd IS NULL` при неопределённой цене в исходной записи, а не `0` с мнимой определённостью.
3. `usage_daily_*_rollups.total_tokens = input_tokens + output_tokens` и счётчики категорий согласованы с количеством assistant messages.
4. `usage_dirty_dates.revision > 0`, delete-if-revision-current.
5. Не редактировать Flyway V7–V71 задним числом; исправления — только новой миграцией с preflight и тестами обновления.

### 5.2. Особый race на UTC-полуночи — гипотеза для воспроизведения

V71 проверяет закрытый день через `transaction_timestamp() AT TIME ZONE 'UTC'`, то есть фиксированное **время начала текущей транзакции**. Допустим, транзакция началась незадолго до полуночи UTC, а запись за этот день завершилась и была закоммичена уже после полуночи, когда scheduler успел материализовать вчерашние данные. Trigger при проверке `p_date >= transaction_timestamp()::date` может решить, что день ещё текущий, **не поставить dirty marker**, хотя после commit дата уже закрыта. Тогда materialized rollup может остаться устаревшим до lookback или ручного rebuild; за пределами lookback ошибка может быть долгоживущей. Это **вывод из конкретного SQL и временной семантики PostgreSQL, требующий воспроизведения** на тестовой БД.

**Рекомендуемое исправление:** пересмотреть правило dirty-marking: безопаснее ставить dirty marker для любой затронутой даты (с bounded cleanup/coalescing) либо для даты, уже попавшей под watermark, не полагаясь только на transaction start date; обязательно учесть lock ordering и возможность дедлока при пересечении с `UsageDirtyDateProcessor.lockDate`. Перед изменением нужны тесты транзакции, пересекающей UTC-полночь, и гонки scheduler vs source commit.

---

## 6. Приоритетный реестр рисков и недостатков

Статусы: **Подтверждено** — следует непосредственно из прочитанного кода/SQL; **зависит от окружения** — необходима integration validation; **дизайн-ограничение** — не баг при документированной сфере применения. `P0` — релиз-блокирующий для *конкретного* сценария, `P1` — высокий, `P2` — средний, `P3` — улучшение.

| ID | Приоритет | Статус | Где | Описание / действие |
|---|---|---|---|---|
| U-01 | **P0, если usage используется для биллинга** | Подтверждённый разрыв модели данных | `UsageSqlFragments`, `UsageRollupDayProcessor`, `UsageFactQueryBuilder` | Основная стоимость агрегируется по ASSISTANT `chat_messages`, физические `model_execution_attempts` в totals не участвуют. Для фактической себестоимости/денежных списаний разработать отдельно reconciled physical-attempt ledger; `knownCostUsd` оставить как аналитический показатель. |
| U-02 | **P1** | Зависит от transaction manager | `UsageQueryService` + `UsageReportExecutor` | Внешний `@Transactional(readOnly=true)` начинается до `Semaphore.tryAcquire`; admission может не защищать пул от сотен конкурирующих запросов. Переместить admission наружу transaction после доказательства поведения connection acquisition. |
| U-03 | **P1** | Вывод из V71, воспроизвести | `V71` dirty trigger | `transaction_timestamp()` на границе UTC-дня способна пропустить dirty-marker при очень долгой source transaction. Нужна тестовая репродукция и новая миграция. |
| U-04 | **P1** | Подтверждённое ограничение | `UsageReportExecutor` | Semaphore только для одной JVM; глобальная нагрузка растёт с числом реплик. Добавить cluster-wide workload budget / DB resource governance при необходимости. |
| U-05 | **P1/P2** | Зависит от SLA | `JdbcUsageQueryRepository.findDataQuality()` | Два SQL statement могут прочитать разные PostgreSQL snapshots при READ COMMITTED. Единый SELECT или `REPEATABLE_READ` для строго согласованного отчёта. |
| U-06 | **P2** | Подтверждённое ограничение | `UsageQueryService`, `UsageReportExecutor` | Statement timeout не равен total request deadline; отдельные query и ожидание соединения суммируются. Задать deadline на уровне сервиса/HTTP, измерять end-to-end latency. |
| U-07 | **P2** | Подтверждённое ограничение | `JdbcUsageQueryRepository` | OFFSET-pagination дорогая на глубоких страницах. Ввести cursor pagination и limit глубины при росте данных. |
| U-08 | **P2** | Подтверждённое ограничение | `UsageFactQueryBuilder` | `__unattributed__` — sentinel, который можно спутать с реальным model id. Зарезервировать label или добавить отдельное поле `modelAttribution`. |
| U-09 | **P2** | Подтверждённое ограничение | `UsageDataQualityResponse` | 100% coverage при нуле applicable — не обязательно признак хорошего качества. UI должен показывать `NO_DATA`/denominator. |
| U-10 | **P2** | Эксплуатационный риск | `UsageRollupScheduler`, `UsageRollupStateRepository`, Hikari verifier | Длительное удержание advisory lock connection; размер пула >=3 сам по себе не исключает starvation. Нагрузочные тесты с concurrent reporting и backfill. |
| U-11 | **P2** | Подтверждённая сложность | `UsageFactQueryBuilder`, `UsageSqlFragments`, `UsageRollupDayProcessor` | Три слоя SQL-представлений с повторяющимися CASE; при изменении статусов легко разойтись live и rollup. Обязательные паритетные тесты на всех статусах и границах времени. |
| U-12 | **P2** | Зависит от эксплуатации | `UsageForcedRebuildService`, `UsageRebuildController` | Нет явного usage-specific audit в показанном коде для ручного rebuild. Нужны аудит и policy на массовое повторение запросов; проверить общий gateway audit. |
| U-13 | **P2** | Подтверждённое ограничение | `UsageQueryRepository.findModels/findDaily` | Нет пагинации, число групп зависит от диапазона/каталога. Не допускать бесконтрольной нагрузки при тысячах моделей или широких диапазонах. |
| U-14 | **P2/P3** | Зависит от точного плана выполнения | `UsageJdbcClients` | `setFetchSize` и statement timeout не дают строгой гарантии streaming/deadline для любого режима подключения. Контролировать на реальном pgjdbc. |
| U-15 | **P3** | Подтверждённое различие конфигураций | `UsagePageRequest`, `UsageProperties` | DTO фиксирует `@Max(200)`, property может задавать меньший предел. Можно централизовать policy и сообщения API. |
| U-16 | **P3** | Разработка / поддерживаемость | `UsageQueryPlan` | Запрет пересечения live/rollup интервалов только в builder, а не в record; дополнить инвариант конструктора без нарушения контракта. |

**Оговорка о P0:** для **аналитического dashboard** U-01 — документированная функциональная граница и приоритет на развитие отчёта, а не доказанная ошибка денежного списания. Но если кто-либо называет данные `/api/admin/usage` юридически/финансово полным биллингом, это **релиз-блокирующее утверждение**, пока не введена сверка всех физических attempts.

---

## 7. Детальный анализ вычисления usage и стоимости

### 7.1. Подсчёт токенов

Для каждого сообщения ASSISTANT:

| `usage_status` | Содержимое отчёта |
|---|---|
| `AVAILABLE` | `input_tokens`, `output_tokens` входят в `confirmed*`; `availableUsageMessages +1`. |
| `PARTIAL` | Известные части входят только в `partialKnown*`, статус `partialUsageMessages +1`; подтверждёнными итоговыми токенами не считаются. |
| `MISSING` | Токены не выдумываются, `missingUsageMessages +1`. |
| `NOT_APPLICABLE` | Счётчик отдельно; в применимый denominator покрытия не включается. |

`confirmedTotalTokens = confirmedInputTokens + confirmedOutputTokens`. Не нужно переименовывать эту сумму в «суммарное потребление провайдера по всем попыткам». Для специальных типов токенов (cache write, cached input, extended dimensions) текущая форма DTO не даёт полной многоразмерной себестоимости physical provider usage.

### 7.2. Денежная цена

| `pricing_status` | Смысл |
|---|---|
| `PRICED` | Цена вычислена; `cost_usd` включается в `knownCostUsd`. |
| `FREE` | Нулевая стоимость подтверждена правилами; входит в классификацию известных. |
| `UNPRICED` | Цена неизвестна; **не** считать бесплатным. |
| `CALCULATION_FAILED` | Ошибка расчёта; **не** считать бесплатным. |
| `NOT_APPLICABLE` | Цена не применима к записи, отдельно от `FREE`. |

`knownCostUsd` не означает «полная стоимость по всем attempts»; `pricingComplete` означает лишь отсутствие UNPRICED/CALCULATION_FAILED среди **классифицированных сообщений**, а не доказанность цепочки физического billing reconciliation.

### 7.3. Отказы и неопределённые исходы

`ai_response_status='REFUSED'` — это **ответ** провайдера в успешной ветви по HTTP/API, который может потреблять оплачиваемые токены; не равен ошибке запроса или бесплатности. `INCOMPLETE` может расходовать токены. `chat_turns.state='AMBIGUOUS'` не доказывает ни успех, ни отсутствие списания; текущая data-quality метрика учитывает такие turns отдельно. В физической модели есть также STARTED/AMBIGUOUS/PROTOCOL_REJECTED attempts и reconciliation queue (V66); их суммарную стоимость обязательно рассматривать отдельно для финансовой полноты.

### 7.4. Паритет live и rollup

Тестировать равенство не только известных сумм, но и **каждой классификации** по модель/организация/пользователь/дата. В частности при смеси AVAILABLE/PARTIAL/MISSING, PRICED/FREE/UNPRICED/FAILED, REFUSED/INCOMPLETE, null model, AMBIGUOUS turns, повторно записанных сообщений и исторических изменений.

---

## 8. Безопасность и multi-tenancy

1. У `UsageController` стоит `@PreAuthorize(ADMIN/SUPER_ADMIN)`. У `UsageQueryService` class-level то же самое; он не полагается на проверку только в HTTP.
2. У ADMIN `organizationId = currentUser.getOrganizationId()` в criteria независимо от userId. Для явного доступа к другой организации `requireOrganizationAccess` выбрасывает `ForbiddenOperationException`.
3. У SUPER_ADMIN возможен намеренный глобальный scope `organizationId=null`; не нужно автоматически заменять его на платформенную организацию, иначе dashboard потеряет реальную глобальную аналитику.
4. Все SQL-фильтры bind-параметризованы; model/user ID не конкатенируются в SQL. Названия SQL-колонок и ORDER BY заданы статически.
5. `@PreAuthorize` при непосредственном вызове через `new UsageQueryService()` в unit тестах не срабатывает как AOP; полноценные security integration тесты проверяют путь через Spring proxy.
6. `currentUserEmail` — персональные данные; защищать логи, выгрузки, кеши и сторонний мониторинг от случайного распространения. Нельзя использовать email как high-cardinality metric tag.
7. Доверенные репозитории с необязательным organizationId предназначены для вызовов только из авторизованного application слоя. Полезно ограничить их применение package/architecture tests.
8. Запрос ручного rebuild не должен принимать произвольный SQL, только LocalDate; это условие соблюдено.

**Предлагаемые security-тесты:** `ADMIN` не видит чужие данные через summary/users/models/daily/by-user/data-quality; `SUPER_ADMIN` корректно смотрит global и explicit organization; plain USER и anonymous отклоняются; сериализация email не протекает в логи ошибок; spoofed organizationId не меняет tenant scope.

---

## 9. Производительность, расписание и конкуренция

### 9.1. Реальное время выполнения отчёта

Выделять и измерять четыре фазы: (1) security/range validation, (2) получение транзакции/соединения и permit, (3) выполнение одного/двух SQL statement, (4) mapping/serialization. `safeai.usage.query-timeout` сам по себе покрывает не все фазы. Привязать Micrometer `duration` к типу отчёта и размеру группы, **без** user/organization UUID в tags.

### 9.2. Rollup и блокировки

Advisory lock сериализует scheduler только для одного выбранного PostgreSQL lockKey; ручной `UsageDirtyDateRepository.requestRebuild` не выполняет rebuild синхронно. Внутри dirty-processing `SELECT FOR UPDATE` защищает конкретную дату. `rebuildDay` удаляет/записывает агрегаты транзакционно. При штатном `READ COMMITTED` читатель видит старую завершённую версию или новую после commit, но не половину перестроенного дня.

**Предупреждение:** если есть сторонние SQL-writers в rollup-таблицы, гарантия нарушается; приложение должно быть единственным авторизованным writer или права PostgreSQL должны запрещать сторонние изменения.

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

В репозитории обнаружены следующие тестовые файлы (присутствие тестов не означает, что они выполнялись в рамках этого аудита):

**config:** `UsageJdbcClientsTest`, `UsagePropertiesTest`.

**controller:** `UsageControllerContractTest`, `UsageControllerSecurityTest`.

**dto:** `UsagePagingContractTest`, `UsageSummaryValueObjectsTest`.

**repository:** `UsageCoverageIntegrationTest`, `UsageEntityMappingContractTest`, `UsageExplainAnalyzePerformanceIntegrationTest`, `UsageExplainPlanIntegrationTest`, `UsageMigrationIntegrityIntegrationTest`, `UsagePaginationIntegrationTest`, `UsagePerformanceIntegrationTest`, `UsagePricingCorrectnessIntegrationTest`, `UsageQueryContractTest`, `UsageQueryRepositoryTest`, `UsageRollupAdvisoryLockIntegrationTest`, `UsageRollupReconciliationIntegrationTest`, `UsageTenantIsolationIntegrationTest`, `UsageUtcAggregationIntegrationTest`.

**service:** `UsageQueryServiceTest`, `UsageReportExecutorTest`, `UsageRollupDayProcessorTest`, `UsageRollupSchedulerTest`.

В существующих unit-тестах подтверждено наличие сценариев: tenant isolation, SUPER_ADMIN global scope, отказ чужой организации, диапазоны и pagination, гибрид whole-day UTC + live edges, запрет oversized live fallback, watermark continuity/backfill, lock contention, dirty-date processing, rollup atomicity. В репозитории имеются отдельные PostgreSQL integration тесты, в том числе для конкурентного rollup и ценовой корректности.

### 10.1. Что добавить в первую очередь

1. **Physical billing gap:** одна логическая операция, несколько реальных attempts, один persisted assistant message; сравнить `knownCostUsd` отчёта с physical-attempt ledger и запретить использовать первое как счёт без reconciliation.
2. **Admission-vs-transaction:** одновременно вызвать много отчётов при Hikari pool=3 и `maxConcurrentReports=4`; удостовериться, что rejected 429 не удерживают соединение до отказа. Измерить число реально открытых DB connections.
3. **Dirty date/UTC midnight:** длинная транзакция начинается до 00:00 UTC, COMMIT после 00:00 UTC; в период между датами scheduler rebuilds same day. Проверить наличие marker и итоговый rollup.
4. **Transactional snapshots:** конкурентное изменение data source между quality aggregate и top-N SQL; договориться о требуемой консистентности.
5. **Live ↔ rollup parity:** randomized status matrix, timestamps ровно на 00:00:00, 23:59:59.999..., null model, multiple tenants.
6. **`__unattributed__` collision:** реальная модель с таким именем и `NULL` model в одном диапазоне — решить policy.
7. **Stress:** 5–10 параллельных отчётов и scheduler; измерять исчерпание Hikari, lock waits, честность 429.
8. **Dirty date late changes:** UPDATE, DELETE, change of created_at / organization_id / model после watermark; revision races, retry после failed rebuild.
9. **Paginated consistency:** глубокие OFFSET, переполнение max-page-size/offset, поведение без доступных строк.
10. **Data quality 0/0:** проверить значение 100% и убедиться, что UI/документация не обещают покрытие без данных.

### 10.2. Локальные команды для проверки

```bash
cd backend
mvn -Dtest=UsagePropertiesTest,UsageJdbcClientsTest,UsageQueryServiceTest,UsageReportExecutorTest,UsageRollupSchedulerTest,UsageRollupDayProcessorTest test
```

Для PostgreSQL-интеграционных тестов нужна инфраструктура, предусмотренная проектом (возможны Testcontainers, локальный Docker и/или Maven profile). Не следует считать тесты завершёнными только по факту компиляции. Сначала изучить annotations и конфигурацию конкретных integration test классов. При полном прогоне стандартный старт:

```bash
cd backend
mvn verify
```

Эти команды **не запускались** при подготовке README; это инструкция владельцу репозитория, а не отчёт о прохождении CI.

---

## 11. Рекомендуемый план модернизации без регрессий

### Этап A — правильная терминология и финансовая гарантия

- Описать `/api/admin/usage` как **аналитику сохранённых ответов и доступных статусных данных**, не invoice API.
- Установить чёткую boundary с `model_execution_attempts`: финансовый отчёт должен агрегировать independent physical attempt evidence, pricing scheme, uncertainty flags и историю пересчёта.
- Не вводить `coalesce(unknown_cost, 0)` как итоговую стоимость; при неизвестных попытках показывать явный `unreconciled` и запрещать hard billing settlement до согласования.

### Этап B — admission и транзакции

- Зафиксировать в тестах, когда Spring transaction реально извлекает соединение.
- Если перед semaphore берётся соединение, вынести выдачу permit до открытия транзакции через отдельный bean/TransactionTemplate, сохранив `@PreAuthorize` и tenant scope. Не отключать `@Transactional` без эквивалентной замены.
- При росте реплик определить cluster-wide DB workload limit и политику fair-share между tenant.

### Этап C — долговечность rollup

- Воспроизвести midnight-случай V71; исправить **новой** Flyway миграцией только после оценки lock ordering/стоимости trigger.
- Ввести alert на старейшую dirty date и устойчивость watermark, добавить ежедневный reconciliation sample.
- Прогнать конкурентный тест trigger insert vs dirty-date lock vs full-date rebuild.

### Этап D — API, UX и производительность

- Добавить семантику пустого denominator для coverage.
- Разрешить вопрос synthetic `__unattributed__` model ID.
- Проработать paging для `models`/`daily`, при необходимости cursor для глубоких выборок.
- Согласовать наименования `knownCostUsd`, `pricingComplete` с тем, что измеряется в реальности.
- Добавить runbook, SLO и целевые p95/p99.

---

## 12. Runbook: как проверять usage после инцидента

1. Проверить `usage_rollup_state.last_completed_date`, UTC-вчера и число дней отставания.
2. Проверить `usage_dirty_dates`: длину очереди, возраст oldest date, повторные revisions.
3. Убедиться, что scheduler запускается, advisory lock не занят зависшим процессом, а Hikari pool не исчерпан.
4. Сверить выборочный UTC-день тремя способами: `chat_messages` live, `usage_daily_org_model_rollups`, `usage_daily_quality_rollups`.
5. Сверить partial/missing usage и UNPRICED/CALCULATION_FAILED с data-quality API.
6. Для спорных денежных сумм просмотреть **отдельно** `model_execution_plans/attempts`, а не считать `knownCostUsd` абсолютной стоимостью.
7. При пересчёте отправить `POST /api/admin/usage/rebuild?date=YYYY-MM-DD` от SUPER_ADMIN и контролировать dirty marker до погашения; `202` не гарантирует завершения.
8. При отсутствии улучшения посмотреть slow SQL, EXPLAIN ANALYZE, блокировки, pg_stat_statements, Micrometer report duration и Hikari pending.
9. Не редактировать уже применённые Flyway миграции и не чистить dirty queue вручную до выяснения причины рассинхронизации.

---

## 13. Границы проверки, оговорки и источники

- **Рассмотрены все 37 Java-файлов** из пяти пакетов `usage`: `config` (4), `controller` (2), `dto` (14), `repository` (11), `service` (6), включая сервисы, отсутствующие в присланном тексте, но доступные в ветке `main`.
- Проверены названия и соответствующая логика миграций V7, V10, V16, V17, V28, V29, V34, V50, V53, V66, V71; схема рассматривается именно в рамках методов и таблиц, используемых модулем usage.
- Проверены состав тестов и основные сценарии `UsageQueryServiceTest`, `UsageRollupSchedulerTest`, `UsageRollupDayProcessorTest`; наличие других integration-тестов подтверждено по дереву репозитория. Не каждый integration test читался построчно и не выполнялся.
- **Не выполнялись:** реальное подключение к PostgreSQL, `mvn verify`, SQL EXPLAIN ANALYZE, нагрузочные тесты, инспекция runtime-флагов CI/CD/production. Поэтому гипотезы U-02/U-03/U-05 требуют воспроизведения прежде, чем их считать окончательно подтверждёнными эксплуатационными дефектами.
- Часть кода в пользовательском сообщении была отображена с HTML-entity/Markdown экранированием (`&#x20;`, `*METHOD*`); семантику оценивали по чистым Java-файлам GitHub, а не по некорректно отформатированному представлению текста.
- Современность на 2026–2027 годы оценивается по архитектурным принципам и проверенному стеку Java 21/Spring/PostgreSQL, **не** означает, что проверены все последние патч-релизы, CVE и даты поддержки каждой установленной зависимости.

**Исходный код:** `https://github.com/Amid-1/Safeai-desk/tree/main/backend/src/main/java/ru/safeai/gateway/usage`  
**Тесты:** `https://github.com/Amid-1/Safeai-desk/tree/main/backend/src/test/java/ru/safeai/gateway/usage`  
**Миграции:** `https://github.com/Amid-1/Safeai-desk/tree/main/backend/src/main/resources/db/migration`

### Финальный вывод

Модуль `usage` **хорошо спроектирован как административная аналитика**: он аккуратно отделяет известный расход от неизвестного, умеет комбинировать актуальные и материализованные данные и серьёзно относится к многопользовательской безопасности и конкурентности фоновых процессов. Оставить как основу. **Не объявлять его полностью проверенным биллингом**, пока не появится непротиворечивый учёт стоимости всех физических вызовов провайдера, сверка неопределённых attempts и сквозные performance/transactional тесты.
