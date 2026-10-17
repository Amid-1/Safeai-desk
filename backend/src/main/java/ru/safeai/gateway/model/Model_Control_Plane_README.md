<!-- SafeAI-Desk/backend/src/main/java/ru/safeai/gateway/model/README.md -->
# Model Control Plane — технический аудит

**SafeAI Desk · Java 21 / Spring Boot · 17 октября 2026 года**

Документ составлен по девяти приложенным архивам/файлам: controller, dto, service, domain,
exception, repository, ModelRoutingEnvelopeProperties, VersionedCatalogPricingResolver,
ModelRequestContentHashPort. Исследованы 78 исходных `.java` файлов и явно объявленные методы,
включая внутренние helper-методы и вложенные records.

> **Статус:** статический code review приложенного среза, НЕ подтверждение
> успешной сборки, исполнения SQL/интеграционных тестов или production deployment.
> Указанные SQL-инварианты V45–V72 требуют отдельной сверки с реальной БД.

> **Ширина:** все строки README ограничены 100 символами для IntelliJ IDEA.

## Содержание

- [1. Назначение и границы](#назначение-и-границы)
- [2. Архитектура и сценарии](#архитектура-и-сценарии)
- [3. HTTP API и RBAC](#http-api-и-rbac)
- [4. Data / budget / pricing](#data--budget--pricing)
- [5. Проверенные сильные стороны](#проверенные-сильные-стороны)
- [6. Риски P1/P2/P3](#риски-p1p2p3)
- [7. Каталог классов и методов](#каталог-классов-и-методов)
- [8. Тест-план и release gates](#тест-план-и-release-gates)
- [9. Что менять первым](#что-менять-первым)
- [10. Границы доказательств](#границы-доказательств)

---

## Назначение и границы

`ru.safeai.gateway.model` — governance/control plane поверх текущего AI runtime. Он отвечает за
выбор разрешённой версии модели, ограничения tenant policy, оценку input envelope и расходов,
зафиксированное решение и защищённый execution binding. Он НЕ является полноценным multi-provider
router. На данном срезе `RuntimeModelStatusService` описывает один настроенный provider/model на
экземпляр приложения.

Основные ownership: `domain` — immutable states; `dto` — wire contracts; `controller` — REST/RBAC;
`service` — decisions, replay, preview и execution fence; `repository` — JDBC/locks/SQL evidence;
`pricing` — физическое settlement; `spi` — внешний semantic hash port; `config` — input envelope.

## Архитектура и сценарии

```text
Tenant ADMIN/SUPER_ADMIN
       |
       +--> Model Catalog (append-only modelKey/version)
       |
       +--> Organization Policy (append-only tenant/version)
       |
       +--> Preview (read-only, no provider call)

Chat reservation (MANDATORY transaction)
       |
       +--> ModelRoutingService.decide()
       |       +--> request mutex / replay check
       |       +--> effective catalog / tenant policy
       |       +--> price/budget/limits / ALLOWED or DENIED
       |       +--> route SHA-256 + mandatory Audit
       |
       +--> sealed base-request SHA-256
               |
Knowledge RAG: exact retrieval/context receipt (where applicable)
               |
       prepared request SHA-256 + binding_version=2
               |
       validated Model execution identity
               |
       ExecutionPlan -> physical STARTED (SQL fencing)
               |
       external provider I/O -> physical outcome/usage/pricing evidence
```

### Каталог и политика

`ModelCatalogService.createVersion()` и `importRuntime()` добавляют неизменяемые версии после
проверки прав, canonical modelKey, данных каталога и advisory lock.
`OrganizationModelPolicyService.createVersion()` выполняет tenant row lock, optimistic check
`expectedPreviousVersion`, сохраняет policy и обязательный audit. Обновление существующей immutable
версии не предусмотрено.

### Маршрутизация и replay

`ModelRoutingService.decide()` требует внешнюю транзакцию (`MANDATORY`). Для пары
`chatId/clientRequestId` захватывается mutex до поиска replay; повтор проверяется по identity и
semantic payload, а новые решения фиксируются с SHA-256 integrity v3. `DENIED` — полноценное durable
evidence: `noRollbackFor=ModelRouteDeniedException` позволяет сохранить отказ и Audit, если
вызывающая транзакция сама затем не будет откатана по иной причине.

### Execution binding

Маршрут разрешает ПОТЕНЦИАЛЬНЫЙ вызов, но сам по себе не отправляет HTTP. Для реального I/O
используются immutable base seal, Knowledge RAG receipt (когда требуется), финальный
prepared-request hash и повторная проверка execution identity. Реальный physical STARTED gate
дополнительно зависит от `ai`/`chat` и триггеров PostgreSQL V65/V67/V72 — не подтверждается одним
Model-кодом.

## HTTP API и RBAC

```http
GET  /api/admin/models/catalog
GET  /api/admin/models/catalog/effective
GET  /api/admin/models/catalog/versions?modelKey=...
POST /api/admin/models/catalog
POST /api/admin/models/catalog/import-runtime
GET  /api/admin/models/policies/{organizationId}
POST /api/admin/models/policies/{organizationId}
POST /api/admin/models/policies/{organizationId}/preview
GET  /api/admin/models/route-decisions/{decisionId}
GET  /api/admin/models/runtime
POST /api/admin/models/runtime/probe
```

Чтение каталога, policy, decision и runtime доступно ADMIN/SUPER_ADMIN. Публикация глобального
каталога, импорт runtime и metadata probe требуют SUPER_ADMIN. Создание tenant policy разрешено
ADMIN только в своём tenant, а SUPER_ADMIN может работать с другими организациями. Route evidence
чужого tenant для ADMIN маскируется как `404`. В методах есть повторные проверки RBAC помимо
аннотаций.

## Data / budget / pricing

`ModelRouteDecision` хранит разрешённую версию каталога/policy, `requestContentHash`, версии input
accounting, модель и причину решения. `ModelRoutingCostPolicy` рассчитывает предельный flat-token
envelope, принимает минимум из runtime/catalog/policy limits и отдельно учитывает `actual_cost_usd`,
`reserved_cost_usd`, `unknown_cost_count` из V62 ledger.

Для HARD budget неизвестная стоимость не может становиться нулём: применяется
`MONTHLY_BUDGET_UNVERIFIABLE`. Доказанное превышение известной нижней границы бюджета остаётся
превышением даже при дополнительной unknown liability. `SOFT` может зафиксировать риск, не блокируя
маршрут.

`VersionedCatalogPricingResolver` получает физический `operationId`, проверяет execution plan,
decision, selected catalog и фактическую модель. Он учитывает различие cached-token семантики OpenAI
и Anthropic. Но текущий read model каталога не включает `pricing_scheme`, поэтому автоматическое
settlement не доказано безопасным для legacy/unsupported schemes.

## Проверенные сильные стороны

- **Fail-closed:** нет policy -> DENIED; нет valid route evidence -> нет правомерного physical I/O.
- **Data isolation:** tenant checks в сервисах и SQL joins/replay identity.
- **Immutable decisions:** SHA-256 v3 и осторожное чтение исторических digest.
- **Optimistic versioning:** append-only model catalog/policy с lock на запись.
- **Retry safety:** sealed base/final requests + сравнение replay evidence.
- **Budget quality:** разделены actual/reserved/unknown, SOFT/HARD и proven lower bound.
- **Preview:** нет отправки provider requests, нет записи decision и audit.
- **Input accounting:** единый versioned детерминированный контракт.
- **Runtime probe:** HTTPS, отключённые redirects, discard body, bounded timeouts.
- **RBAC:** SUPER_ADMIN-only изменения каталога и cross-tenant policy.
- **JDBC:** параметры через prepared statement, bounded cursor pagination.
- **Audit:** route decisions, policy/catalog versions проходят required intent.

## Риски P1/P2/P3

### P1 / M-01 — Не сквозной контракт `pricing_scheme`

**Наблюдение в коде.**
`ModelCatalogRepository.SELECT_COLUMNS` и `map()` не загружают `pricing_scheme` V66; в
`ModelCatalogEntry` нет этого поля. `VersionedCatalogPricingResolver.resolve()`/`calculate()`
проверяют `pricingStatus/pricingComplete`, но не доказывают `FLAT_TOKEN_V1` через persisted
immutable pricing scheme.

**Последствие или граница риска.**
Риск некорректного автоматического settlement legacy/unsupported тарифа и завышенного качества
pricing evidence. V66 хранит algorithm identity, но из Java call path он теряется.

**Корректирующее действие.**
Добавить `ModelPricingScheme` в catalog SELECT/domain/projections, читать exact persisted field; в
settlement и в worst-case budget проверять supported scheme. Unsupported/legacy ->
UNPRICED/непроверяемый cost, не число. Покрыть V66 seeded tests.

### P1 / M-02 — Создание CONFIGURED может сохранить legacy scheme

**Наблюдение в коде.**
`ModelCatalogService.createVersion()` вызывает `repository.insert(entry)`, а default-overload
`ModelCatalogRepository.insert(entry)` присваивает `CONFIGURED -> LEGACY_FLAT_UNVERIFIED_V1`.
Request DTO не содержит явного выбора scheme; `importRuntime()` также вызывает этот overload.

**Последствие или граница риска.**
Новая вручную созданная версия не получает доказанную flat scheme автоматически; в сочетании с M-01
возможен конфликт с заверениями `pricingComplete=true`.

**Корректирующее действие.**
Не редактировать исторические snapshots. Для НОВОЙ версии потребовать явный scheme или сформировать
строго проверенный FLAT_TOKEN_V1 из bounded правил; legacy оставлять unsupported. Добавить
contract/test на insert/read/settlement.

### P1 / M-03 — Бюджет до provider I/O не учитывает scheme как доказательство

**Наблюдение в коде.**
`ModelRoutingCostPolicy.estimateCost()` формирует `PricingEstimate(..., entry.pricingComplete())`;
field scheme не передаётся. При `requireCompletePricing` или HARD budget решение может опираться на
неподтверждённый тариф.

**Последствие или граница риска.**
Неочевидное обещание upper bound при provider-specific тарифах и иных billing dimensions. Класс
корректен как flat-token estimate, не как универсальный bill.

**Корректирующее действие.**
Единый проверенный pricing capability/versioned scheme должен использоваться и budget preflight, и
физическим resolver; unsupported -> fail-closed для обязательных бюджетных ограничений.

### P2 / M-04 — Стартап проверяет наличие, но не полную активность DB инвариантов

**Наблюдение в коде.**
`ModelControlPlaneSchemaVerifier.afterSingletonsInstantiated()` ищет таблицы, колонки и deferrable
trigger, однако не проверяет `pg_trigger.tgenabled`, `pg_constraint.convalidated`, validity
критических индексов и состав всех physical STARTED fences.

**Последствие или граница риска.**
Схема может быть частично повреждена, а startup-check сообщит успех.

**Корректирующее действие.**
Добавить строгие schema assertions: enabled trigger, validated constraints, `indisvalid/indisready`,
V65/V67/V72 triggers, pricing scheme column/check.

### P2 / M-05 — Расчёт preview не резервирует бюджет

**Наблюдение в коде.**
`ModelPolicyPreviewService.loadEffectiveCatalog()` читает до 5000 моделей;
`loadBudgetContextForPreview()` намеренно без эксклюзивной budget блокировки. Временной snapshot
может устареть к моменту маршрута.

**Последствие или граница риска.**
`wouldLockOutOrganization` и модельные статусы — диагностический dry-run, не гарантия следующего
разрешения.

**Корректирующее действие.**
Документировать as-of snapshot, добавить сравнение preview/decide на устойчивом dataset и
concurrency тесты; не превращать preview в write path.

### P2 / M-06 — Metadata probe подтверждает только HTTP metadata status

**Наблюдение в коде.**
`RuntimeModelProbeHttpSupport.executeProbe()` трактует любой 2xx от `GET /models/{id}` как
AVAILABLE, игнорируя тело.

**Последствие или граница риска.**
200 от metadata endpoint не доказывает разрешение на реальный inference, достаточность баланса или
корректность pricing/retention claims.

**Корректирующее действие.**
Не использовать probe как data-plane authorization/SLA; отделить `METADATA_AVAILABLE` от настоящей
synthetic inference readiness, если она позже понадобится.

### P2 / M-07 — Сложность keyset catalog и массового preview зависит от индексов

**Наблюдение в коде.**
`ModelCatalogRepository` использует DISTINCT ON (model_key), `effective_from`, версию и runtime
filters. `ModelPolicyPreviewService` перебирает до 5000 snapshots.

**Последствие или граница риска.**
При больших каталогах возможны дорогие sort/scan и задержки в API.

**Корректирующее действие.**
Проверить PostgreSQL EXPLAIN (ANALYZE, BUFFERS), реальные индексы и лимиты read-only query/statement
timeout; сравнить latest/effective plans.

### P2 / M-08 — Потенциальный drift provider usage и pricing semantics

**Наблюдение в коде.**
`VersionedCatalogPricingResolver.calculate()` жёстко разделяет OpenAI и Anthropic и предполагает
конкретные семантики cached/read/write usage.

**Последствие или граница риска.**
Изменения API/планов биллинга или новые billing dimensions могут сделать условно complete usage
неполной.

**Корректирующее действие.**
Ввести versioned provider usage schema + pricing scheme per adapter; проверять реальные response
fixtures и несовместимые dimensions -> UNPRICED.

### P2 / M-09 — Статические input units не являются provider tokenizer upper bound

**Наблюдение в коде.**
`ModelInputTokenEstimator`, `ModelRoutingEnvelopeService` и `ModelRouteExecutionGuard` используют
versioned deterministic input accounting.

**Последствие или граница риска.**
Гарантия укладывания в provider-native context window математически не следует из приблизительного
accounting, особенно для RAG/высоких лимитов.

**Корректирующее действие.**
Не объявлять estimate native tokenizer count; добавить provider-aware validation или доказанный
conservative upper bound и тесты на Unicode/RAG.

### P3 / M-10 — Большая плотность бизнес-логики в отдельных сервисах

**Наблюдение в коде.**
`ModelRoutingService`, `ModelRoutingCostPolicy`, `ModelCatalogService`,
`ModelRouteExecutionIdentity` и `ModelRouteDecisionIntegrity` достаточно объёмны.

**Последствие или граница риска.**
Высокая стоимость изменений и риска несовместимости legacy hashing.

**Корректирующее действие.**
Рефакторить только с golden-vector/integration тестами, не менять digest или публичные DTO при
косметической декомпозиции.

### Приоритет и степень доказанности

M-01 и M-02 подтверждены явными SQL/Java read/write paths в приложенных файлах. M-03 — их
непосредственное следствие для budget accounting. M-04 подтверждён неполным перечнем проверок в
конкретном verifier, но факт повреждения БД НЕ установлен. M-05–M-09 — архитектурные/нагрузочные
ограничения, а не утверждение о зафиксированном инциденте. Ни один вывод не должен исправляться
снятием ограничений `MANDATORY`, DB triggers или разрешением UNKNOWN=0.

## Каталог классов и методов

Ниже перечислены ВСЕ 78 входных файлов, объявленные в них методы/конструкторы (включая
package-private helpers), а для record/enum указана их роль. Неявные record accessors,
`equals/hashCode/toString`, generated Lombok и методы `java.lang.Object` не перечислены по одному,
так как не объявлены в исходниках. В скобках `L` — строка внутри файла из архива.

### Пакет `config` — 1 файлов

#### `ModelRoutingEnvelopeProperties.java`

Конфигурация потолков добавочных input units; проверяет legacy-алиасы и границы.

Поля record: `systemAndDeveloperInputUnits`, `ragContextInputUnits`, `toolSchemaInputUnits`,
`systemAndDeveloperTokens`, `ragContextTokens`, `toolSchemaTokens`.

**Объявленные методы:**

- `L30 ModelRoutingEnvelopeProperties()` — Проверил инварианты параметров record.
- `L61 resolve()` — Разрешил effective значение, identity или физический тариф.

### Пакет `controller` — 5 файлов

#### `ModelCatalogController.java`

HTTP API чтения каталога и создания/импорта версий с разделением прав.

**Объявленные методы:**

- `L30 ModelCatalogController()` — Инициализировал зависимости/объект и проверил обязательные
  значения.
- `L41 latest()` · @GetMapping — HTTP-версия принимает cursor+limit; helper-версия использует
  default page.
- `L59 latest()` — HTTP-версия принимает cursor+limit; helper-версия использует default page.
- `L70 effective()` · @GetMapping — HTTP-версия принимает cursor+limit; helper-версия использует
  default page.
- `L88 effective()` — HTTP-версия принимает cursor+limit; helper-версия использует default page.
- `L98 history()` · @GetMapping — HTTP-версия принимает beforeVersion; helper сохраняет legacy API.
- `L118 history()` — HTTP-версия принимает beforeVersion; helper сохраняет legacy API.
- `L130 createVersion()` · @PostMapping — Проверил права, ожидаемую версию и создал append-only
  запись с audit.
- `L143 importRuntime()` · @PostMapping — Импортировал статически настроенный runtime как версию
  каталога.

#### `ModelPolicyPreviewController.java`

HTTP API dry-run проверки предлагаемой политики организации.

**Объявленные методы:**

- `L26 ModelPolicyPreviewController()` — Инициализировал зависимости/объект и проверил обязательные
  значения.
- `L30 preview()` · @PostMapping — Выполнил dry-run policy без сохранения её в БД и вызова
  провайдера.

#### `ModelRouteDecisionController.java`

HTTP API просмотра сохранённого решения маршрутизации.

**Объявленные методы:**

- `L23 ModelRouteDecisionController()` — Инициализировал зависимости/объект и проверил обязательные
  значения.
- `L27 findById()` · @GetMapping — Передал вызов tenant-aware service для чтения route decision.

#### `ModelRuntimeController.java`

HTTP API текущего runtime и административного metadata probe.

**Объявленные методы:**

- `L23 ModelRuntimeController()` — Инициализировал зависимости/объект и проверил обязательные
  значения.
- `L31 runtime()` · @GetMapping — Вернул публичный снимок настроенной модели без раскрытия
  credentials.
- `L38 probe()` · @PostMapping — Запустил административную проверку provider metadata endpoint.

#### `OrganizationModelPolicyController.java`

HTTP API просмотра и публикации версий tenant model policy.

**Объявленные методы:**

- `L27 OrganizationModelPolicyController()` — Инициализировал зависимости/объект и проверил
  обязательные значения.
- `L31 current()` · @GetMapping — Вернул актуальный runtime или текущую policy без изменения
  состояния.
- `L40 createVersion()` · @PostMapping — Проверил права, ожидаемую версию и создал append-only
  запись с audit.

### Пакет `dto` — 10 файлов

#### `CreateModelCatalogVersionRequest.java`

Request DTO с Bean Validation на ручную новую версию каталога.

Record содержит 23 компонентов. Точные field/annotation contracts сохранены в исходном `.java`; при
изменении DTO отдельно проверить Bean Validation и JSON compatibility.

Явно объявленных методов нет. Поведение задаётся enum values, record-generated accessors или Bean
Validation DTO.

#### `CreateOrganizationModelPolicyVersionRequest.java`

Request DTO новой tenant policy с expectedPreviousVersion.

Record содержит 13 компонентов. Точные field/annotation contracts сохранены в исходном `.java`; при
изменении DTO отдельно проверить Bean Validation и JSON compatibility.

Явно объявленных методов нет. Поведение задаётся enum values, record-generated accessors или Bean
Validation DTO.

#### `ModelCatalogEntryResponse.java`

Публичная проекция версии каталога; денежные значения строками.

Record содержит 27 компонентов. Точные field/annotation contracts сохранены в исходном `.java`; при
изменении DTO отдельно проверить Bean Validation и JSON compatibility.

**Объявленные методы:**

- `L52 from()` — Преобразовал domain объект в API-ответ.
- `L86 decimal()` — Преобразовал BigDecimal в строковую форму без float-ошибок.

#### `ModelPolicyPreviewItemResponse.java`

Оценка одной модели в dry-run, включая cost и причины отказа.

Record содержит 21 компонентов. Точные field/annotation contracts сохранены в исходном `.java`; при
изменении DTO отдельно проверить Bean Validation и JSON compatibility.

**Объявленные методы:**

- `L35 decimal()` — Преобразовал BigDecimal в строковую форму без float-ошибок.

#### `ModelPolicyPreviewResponse.java`

Результат preview политики со списком моделей и оценкой lockout.

Поля record: `organizationId`, `basePolicyVersion`, `enabled`, `evaluatedAt`, `runtimeProvider`,
`runtimeModel`, `automaticOutcome`, `automaticReason`, `automaticModelKey`, `executableModelCount`,
`wouldLockOutOrganization`, `models`.

**Объявленные методы:**

- `L28 ModelPolicyPreviewResponse()` — Проверил инварианты параметров record.

#### `ModelRouteDecisionResponse.java`

Административная проекция immutable route/budget evidence.

Record содержит 39 компонентов. Точные field/annotation contracts сохранены в исходном `.java`; при
изменении DTO отдельно проверить Bean Validation и JSON compatibility.

**Объявленные методы:**

- `L60 from()` — Преобразовал domain объект в API-ответ.
- `L119 decimal()` — Преобразовал BigDecimal в строковую форму без float-ошибок.

#### `OrganizationModelPolicyResponse.java`

Ответ API по текущей или ещё не созданной tenant policy.

Record содержит 18 компонентов. Точные field/annotation contracts сохранены в исходном `.java`; при
изменении DTO отдельно проверить Bean Validation и JSON compatibility.

**Объявленные методы:**

- `L32 from()` — Преобразовал domain объект в API-ответ.
- `L57 unconfigured()` — Сформировал ответ о tenant policy без сохранённой версии.
- `L82 decimal()` — Преобразовал BigDecimal в строковую форму без float-ошибок.

#### `RuntimeModelProbeResponse.java`

Короткий безопасный HTTP-ответ административного probe.

Поля record: `provider`, `model`, `status`, `checkedAt`, `latencyMs`, `httpStatus`, `message`.

**Объявленные методы:**

- `L20 from()` — Преобразовал domain объект в API-ответ.

#### `RuntimeModelStatusResponse.java`

Внутренний read model runtime provider/model и ценовых метаданных.

Record содержит 15 компонентов. Точные field/annotation contracts сохранены в исходном `.java`; при
изменении DTO отдельно проверить Bean Validation и JSON compatibility.

Явно объявленных методов нет. Поведение задаётся enum values, record-generated accessors или Bean
Validation DTO.

#### `RuntimeModelStatusWireResponse.java`

Безопасная публичная проекция runtime без credentials и URL.

Record содержит 15 компонентов. Точные field/annotation contracts сохранены в исходном `.java`; при
изменении DTO отдельно проверить Bean Validation и JSON compatibility.

**Объявленные методы:**

- `L23 from()` — Преобразовал domain объект в API-ответ.
- `L45 decimal()` — Преобразовал BigDecimal в строковую форму без float-ошибок.

### Пакет `domain` — 20 файлов

#### `BudgetEnforcement.java`

Режим HARD/SOFT применения месячного бюджета.

Варианты: `SOFT`, `HARD`.

Явно объявленных методов нет. Поведение задаётся enum values, record-generated accessors или Bean
Validation DTO.

#### `ModelBudgetCommitmentSnapshot.java`

Раздельное immutable evidence известных actual, reserved и unknown обязательств.

Поля record: `actualCostUsd`, `reservedCostUsd`, `unknownCostCount`, `commitmentState`.

**Объявленные методы:**

- `L20 ModelBudgetCommitmentSnapshot()` — Проверил инварианты параметров record.
- `L67 committedCostUsd()` — Сложил known actual и reserved компоненты.

#### `ModelCapability.java`

Словарь capability: tools, vision и structured output.

Варианты: `TOOLS`, `VISION`, `STRUCTURED_OUTPUT`.

Явно объявленных методов нет. Поведение задаётся enum values, record-generated accessors или Bean
Validation DTO.

#### `ModelCatalogEntry.java`

Immutable версия каталога: модель, модальности, безопасность, лимиты и цены.

Record содержит 27 компонентов. Точные field/annotation contracts сохранены в исходном `.java`; при
изменении DTO отдельно проверить Bean Validation и JSON compatibility.

**Объявленные методы:**

- `L39 ModelCatalogEntry()` — Проверил инварианты параметров record.
- `L57 immutableEnumSet()` — Скопировал enum-множество в immutable defensive snapshot.

#### `ModelCatalogSource.java`

Происхождение записи каталога (ручная, импорт или миграция).

Варианты: `MANUAL`, `RUNTIME_IMPORT`, `MIGRATED`.

Явно объявленных методов нет. Поведение задаётся enum values, record-generated accessors или Bean
Validation DTO.

#### `ModelLifecycle.java`

Состояния жизненного цикла модели.

Варианты: `ACTIVE`, `DEPRECATED`, `DISABLED`, `RETIRED`.

Явно объявленных методов нет. Поведение задаётся enum values, record-generated accessors или Bean
Validation DTO.

#### `ModelModality.java`

Типы входной и выходной модальности.

Варианты: `TEXT`, `IMAGE`, `AUDIO`.

Явно объявленных методов нет. Поведение задаётся enum values, record-generated accessors или Bean
Validation DTO.

#### `ModelPricingStatus.java`

Классификация наличия/полноты тарифа.

Варианты: `UNPRICED`, `FREE`, `CONFIGURED`, `INCOMPLETE`.

Явно объявленных методов нет. Поведение задаётся enum values, record-generated accessors или Bean
Validation DTO.

#### `ModelRetentionStatus.java`

Декларируемая поставщиком политика хранения данных.

Варианты: `NOT_DECLARED`, `STANDARD`, `ZERO_DATA_RETENTION`, `CUSTOM`.

Явно объявленных методов нет. Поведение задаётся enum values, record-generated accessors или Bean
Validation DTO.

#### `ModelRouteDecision.java`

Immutable evidence одного routing decision с бюджетом и SHA-256.

Record содержит 33 компонентов. Точные field/annotation contracts сохранены в исходном `.java`; при
изменении DTO отдельно проверить Bean Validation и JSON compatibility.

**Объявленные методы:**

- `L54 ModelRouteDecision()` — Проверил инварианты параметров record.
- `L105 monthlyCostState()` — Вывел исторический KNOWN/UNKNOWN/NOT_EVALUATED state.
- `L117 monthlyCommitmentState()` — Вывел V62 commitment state для route evidence.
- `L142 monthlyCommittedUsd()` — Получил известное обязательство actual + reserved, где применимо.

#### `ModelRouteOutcome.java`

Результат разрешения маршрута: ALLOWED или DENIED.

Варианты: `ALLOWED`, `DENIED`.

Явно объявленных методов нет. Поведение задаётся enum values, record-generated accessors или Bean
Validation DTO.

#### `ModelRouteReason.java`

Машинно-читаемая причина разрешения/отказа.

Варианты: `REQUESTED_MODEL`, `POLICY_DEFAULT`, `RUNTIME_ONLY_MATCH`, `LEGACY_RUNTIME_FALLBACK`,
`MODEL_NOT_ALLOWED`, `MODEL_DENIED`, `MODEL_NOT_FOUND`, `AMBIGUOUS_RUNTIME_MAPPING`,
`MODEL_DISABLED`, `RUNTIME_MISMATCH`, `CAPABILITY_UNSUPPORTED`, `INPUT_LIMIT_EXCEEDED`,
`OUTPUT_LIMIT_EXCEEDED`, `PRICING_INCOMPLETE`, `TRAINING_POLICY_UNSATISFIED`,
`RETENTION_POLICY_UNSATISFIED`, `REQUEST_COST_LIMIT_EXCEEDED`, `MONTHLY_BUDGET_EXCEEDED`,
`MONTHLY_BUDGET_UNVERIFIABLE`.

Явно объявленных методов нет. Поведение задаётся enum values, record-generated accessors или Bean
Validation DTO.

#### `ModelRouteRequest.java`

Запрос governance: principal, chat, semantic hash, контекст и envelope.

Поля record: `organizationId`, `userId`, `chatId`, `plannedTurnId`, `clientRequestId`,
`requestContentHash`, `requestedModelKey`, `userMessage`, `history`, `requiredCapabilities`,
`additionalInputUnitUpperBound`.

**Объявленные методы:**

- `L27 ModelRouteRequest()` — Проверил инварианты параметров record.

#### `ModelRouteResult.java`

Промежуточный результат маршрутизации для вызывающего Chat слоя.

Record содержит 14 компонентов. Точные field/annotation contracts сохранены в исходном `.java`; при
изменении DTO отдельно проверить Bean Validation и JSON compatibility.

Явно объявленных методов нет. Поведение задаётся enum values, record-generated accessors или Bean
Validation DTO.

#### `ModelTrainingUseStatus.java`

Декларируемый режим использования данных для обучения.

Варианты: `NOT_DECLARED`, `NOT_USED`, `MAY_BE_USED`, `CONTRACTUAL_NO_TRAINING`.

Явно объявленных методов нет. Поведение задаётся enum values, record-generated accessors или Bean
Validation DTO.

#### `MonthlyCommitmentState.java`

Состояние точности и достаточности бюджетных обязательств.

Варианты: `NOT_EVALUATED`, `KNOWN_WITHIN`, `KNOWN_EXCEEDED`, `UNKNOWN_WITHIN_LOWER_BOUND`,
`UNKNOWN_EXCEEDED_LOWER_BOUND`, `UNVERIFIABLE`.

Явно объявленных методов нет. Поведение задаётся enum values, record-generated accessors или Bean
Validation DTO.

#### `MonthlyCostState.java`

Укрупнённое состояние KNOWN/UNKNOWN месячной стоимости.

Варианты: `NOT_EVALUATED`, `KNOWN`, `UNKNOWN`.

Явно объявленных методов нет. Поведение задаётся enum values, record-generated accessors или Bean
Validation DTO.

#### `OrganizationModelPolicy.java`

Immutable политика tenant по моделям, ценам, бюджету и data governance.

Record содержит 17 компонентов. Точные field/annotation contracts сохранены в исходном `.java`; при
изменении DTO отдельно проверить Bean Validation и JSON compatibility.

**Объявленные методы:**

- `L29 OrganizationModelPolicy()` — Проверил инварианты параметров record.
- `L34 immutableSortedSet()` — Вернул отсортированную защищённую копию model keys.

#### `RuntimeModelProbeResult.java`

Immutable результат последней проверки доступности provider/model.

Поля record: `provider`, `model`, `status`, `checkedAt`, `latencyMs`, `httpStatus`, `message`.

**Объявленные методы:**

- `L22 RuntimeModelProbeResult()` — Проверил инварианты параметров record.
- `L67 requireText()` — Проверил непустой текст.

#### `RuntimeModelProbeStatus.java`

Классификация ответа metadata probe.

Варианты: `AVAILABLE`, `AUTH_ERROR`, `RATE_LIMITED`, `MODEL_NOT_FOUND`, `UNAVAILABLE`,
`CONFIGURATION_MISMATCH`, `ERROR`.

Явно объявленных методов нет. Поведение задаётся enum values, record-generated accessors или Bean
Validation DTO.

### Пакет `exception` — 2 файлов

#### `ModelRouteDeniedException.java`

Домена отказа маршрутизации с ID сохранённого решения.

**Объявленные методы:**

- `L26 ModelRouteDeniedException()` — Инициализировал зависимости/объект и проверил обязательные
  значения.

#### `ModelRouteEnvelopeExceededException.java`

Отказ при превышении выделенного input envelope до provider I/O.

**Объявленные методы:**

- `L28 ModelRouteEnvelopeExceededException()` — Инициализировал зависимости/объект и проверил
  обязательные значения.
- `L51 decisionId()` — Вернул ID маршрута, вызвавшего отказ execution envelope.
- `L58 reservedInputUnits()` — Вернул зарезервированный input envelope.
- `L65 actualEstimatedInputUnits()` — Вернул измеренный deterministic input-unit объём.
- `L69 message()` — Сформировал публичное сообщение с учётом превышения envelope.

### Пакет `spi` — 1 файлов

#### `ModelRequestContentHashPort.java`

Контракт semantic hash из Chat для проверки модели без зависимости от Chat.

**Объявленные методы:**

- `L16 requestHash()` — Определил semantic hash для запроса от Chat.

### Пакет `pricing` — 1 файлов

#### `VersionedCatalogPricingResolver.java`

Основной PricingResolver физического вызова с immutable catalog evidence.

**Объявленные методы:**

- `L48 VersionedCatalogPricingResolver()` — Инициализировал зависимости/объект и проверил
  обязательные значения.
- `L60 resolve()` · @Override — Перегрузки: без operationId возвращает UNPRICED; с ID ищет exact
  plan/route/catalog.
- `L66 resolve()` · @Override — Перегрузки: без operationId возвращает UNPRICED; с ID ищет exact
  plan/route/catalog.
- `L117 calculate()` — Рассчитал цену из physical usage и версионированного каталога.
- `L173 charge()` — Применил ставку USD за миллион единиц.
- `L179 version()` — Создал hash тарифных полей; текущая реализация НЕ включает pricing_scheme.

**Связанный риск:** M-01/M-08.

### Пакет `repository` — 6 файлов

#### `JdbcTextArrayReader.java`

Десериализует PostgreSQL text[] в Java; валидирует элементы.

**Объявленные методы:**

- `L13 JdbcTextArrayReader()` — Инициализировал зависимости/объект и проверил обязательные значения.
- `L16 read()` — Прочитал SQL array с проверкой допустимых элементов.

#### `ModelCatalogRepository.java`

JDBC: версионированный каталог, keyset pages, блокировка ключа и INSERT.

**Объявленные методы:**

- `L80 ModelCatalogRepository()` — Инициализировал зависимости/объект и проверил обязательные
  значения.
- `L85 findById()` — Прочитал immutable версию по ID; tenant authorization здесь НЕ выполняется.
- `L92 lockModelKey()` · @Transactional — Поставил transaction-scoped advisory lock на ключ модели.
- `L106 findLatest()` — Загрузил последнюю созданную версию (включая будущие).
- `L114 findVersions()` — Прочитал ограниченную историю версий по cursor.
- `L130 findLatestPage()` — Вернул bounded keyset страницу по modelKey.
- `L140 findEffective()` — Загрузил effective-версию по времени.
- `L149 findEffectivePage()` — Вернул bounded keyset effective страницу.
- `L161 findEffectiveByRuntime()` — Сначала выбрал effective-версии, затем сопоставил runtime.
- `L167 hasEffectiveHistoryByRuntime()` — Проверил наличие исторического effective runtime matching.
- `L182 insert()` — Два пути: default классифицирует CONFIGURED как legacy, explicit принимает
  scheme.
- `L196 insert()` — Два пути: default классифицирует CONFIGURED как legacy, explicit принимает
  scheme.
- `L248 validatePage()` — Проверил keyset cursor и bounded limit репозитория.
- `L257 setNullableText()` — Записал nullable строковый JDBC-параметр.
- `L263 setNullableInteger()` — Записал nullable integer JDBC-параметр.
- `L269 map()` — Собрал domain record из ResultSet.

**Связанный риск:** M-01/M-02.

#### `ModelRouteDecisionRepository.java`

JDBC: routing evidence, monthly commitments, locks и budget snapshot.

**Объявленные методы:**

- `L72 ModelRouteDecisionRepository()` — Инициализировал зависимости/объект и проверил обязательные
  значения.
- `L76 lockOrganizationBudget()` · @Transactional — Сериализовал изменение budget commitment в
  рамках транзакции.
- `L96 validateAllowedTurnLinkBeforeExternalSideEffects()` · @Transactional — Принудительно проверил
  deferred SQL constraint до Redis/I/O.
- `L103 findByRequest()` — Загрузил ранее сохранённое решение по semantic replay key.
- `L114 findById()` — Прочитал route decision по ID; tenant authorization выполняет caller.
- `L133 loadCommittedMonthlyCostSnapshot()` — Прочитал actual/reserved/unknown из V62 ledger для
  UTC-месяца.
- `L175 requiresSinglePhysicalAttempt()` — Проверил запрет повтора при immutable
  HARD/max-request-cost policy.
- `L188 insertBudgetCommitmentSnapshot()` — Сохранил immutable route-time budget evidence.
- `L221 findBudgetCommitmentSnapshot()` — Загрузил сохранённый route-time actual/reserved/unknown
  snapshot.
- `L242 insert()` — Вставил immutable запись; проверил количество затронутых строк.
- `L252 prepareInsert()` — Создал PreparedStatement с типизированной привязкой SQL-полей.
- `L320 map()` — Собрал ModelRouteDecision с полями integrity и budget из ResultSet.
- `L358 readBudgetEnforcement()` — Прочитал nullable budget enforcement enum из JDBC ResultSet.
- `L363 capabilityArray()` — Преобразовал capability set в SQL array.
- `L374 capabilities()` — Прочитал SQL array обратно в capability set.
- `L386 setNullableInteger()` — Записал nullable integer JDBC-параметр.
- `L398 setNullableLong()` — Записал nullable bigint JDBC-параметр.
- `L416 ModelRouteDecisionRepository()` — Инициализировал зависимости/объект и проверил обязательные
  значения.
- `L420 ModelRouteDecisionRepository()` — Инициализировал зависимости/объект и проверил обязательные
  значения.

#### `ModelRouteRequestBindingRepository.java`

JDBC: sealed base request, prepared receipt и Knowledge RAG receipt.

**Объявленные методы:**

- `L22 ModelRouteRequestBindingRepository()` — Инициализировал зависимости/объект и проверил
  обязательные значения.
- `L26 insertReserved()` — Записал immutable hash исходного запроса до финальной сборки.
- `L59 findReservedHash()` — Прочитал digest previously sealed base request.
- `L84 findKnowledgeRagReceipt()` — Получил доверенный receipt точного Knowledge retrieval/context.
- `L130 insertPreparedIfAbsent()` — Идемпотентно записал final prepared request receipt.
- `L174 findPrepared()` — Прочитал persisted prepared-request seal.
- `L224 ModelRouteRequestBindingRepository()` — Инициализировал зависимости/объект и проверил
  обязательные значения.
- `L251 ModelRouteRequestBindingRepository()` — Инициализировал зависимости/объект и проверил
  обязательные значения.
- `L297 requireSha256()` — Проверил канонический lowercase SHA-256.
- `L305 requireSafeText()` — Проверил текстовую метку по длине/управляющим символам.

#### `ModelRouteRequestMutexRepository.java`

Детерминированная сериализация одного chat/clientRequestId в БД.

**Объявленные методы:**

- `L19 ModelRouteRequestMutexRepository()` — Инициализировал зависимости/объект и проверил
  обязательные значения.
- `L28 lock()` · @Transactional — Сериализовал route request по deterministic transaction-level
  lock.

#### `OrganizationModelPolicyRepository.java`

JDBC: доступ к policy, row lock tenant, append-only версии.

**Объявленные методы:**

- `L30 OrganizationModelPolicyRepository()` — Инициализировал зависимости/объект и проверил
  обязательные значения.
- `L43 organizationExists()` — Проверил существование целевой organization.
- `L55 lockOrganization()` · @Transactional — Заблокировал tenant row до публикации новой policy.
- `L68 findLatest()` — Загрузил последнюю созданную версию (включая будущие).
- `L92 insert()` — Вставил immutable запись; проверил количество затронутых строк.
- `L112 prepareInsert()` — Создал PreparedStatement с типизированной привязкой SQL-полей.
- `L162 map()` — Собрал domain record из ResultSet.
- `L189 textArray()` — Создал SQL text[] из set policy model keys.
- `L202 stringSet()` — Восстановил Java Set из SQL text[] без невалидных значений.
- `L210 setNullableInteger()` — Записал nullable integer JDBC-параметр.

### Пакет `service` — 32 файлов

#### `AnthropicRuntimeModelHealthProbe.java`

Адаптер административного metadata probe Anthropic.

**Объявленные методы:**

- `L30 AnthropicRuntimeModelHealthProbe()` — Инициализировал зависимости/объект и проверил
  обязательные значения.
- `L44 provider()` · @Override — Возвратил идентификатор адаптера provider.
- `L49 probe()` · @Override — Сделал ограниченный HTTPS metadata GET Anthropic.

#### `DatabaseTimestampNormalizer.java`

Приводит время к точности PostgreSQL для стабильного evidence/hash.

**Объявленные методы:**

- `L9 DatabaseTimestampNormalizer()` — Инициализировал зависимости/объект и проверил обязательные
  значения.
- `L11 normalize()` — Привёл timestamp к точности persisted DB.

#### `MockRuntimeModelHealthProbe.java`

Локальный контролируемый probe mock provider.

**Объявленные методы:**

- `L27 MockRuntimeModelHealthProbe()` — Инициализировал зависимости/объект и проверил обязательные
  значения.
- `L36 provider()` · @Override — Возвратил идентификатор адаптера provider.
- `L41 probe()` · @Override — Сформировал локальный mock результат без внешнего HTTP.

#### `ModelCatalogAuditDetails.java`

Типизированная проекция деталей аудита новой версии каталога.

Record содержит 18 компонентов. Точные field/annotation contracts сохранены в исходном `.java`; при
изменении DTO отдельно проверить Bean Validation и JSON compatibility.

**Объявленные методы:**

- `L40 ModelCatalogAuditDetails()` — Проверил инварианты параметров record.
- `L65 toMap()` · @Override — Преобразовал typed AuditDetails в структуру допустимых audit fields.

#### `ModelCatalogAuditDetailsFactory.java`

Создаёт AuditDetails из immutable записи каталога.

**Объявленные методы:**

- `L11 ModelCatalogAuditDetailsFactory()` — Инициализировал зависимости/объект и проверил
  обязательные значения.
- `L14 create()` — Сконструировал типизированные AuditDetails из immutable source.

#### `ModelCatalogRules.java`

Проверки канонических ключей, денежных значений, ценовых схем и JSON.

**Объявленные методы:**

- `L26 ModelCatalogRules()` — Инициализировал зависимости/объект и проверил обязательные значения.
- `L29 normalizeMoney()` — Проверил BigDecimal и согласовал numeric(30,12).
- `L34 validateCatalogSemantics()` — Проверил сочетания lifecycle/modalities/prices/retention.
- `L172 validateExtraPricingJson()` — Проверил JSON-object дополнительных тарифных полей.
- `L194 normalizeModelKey()` — Проверил/канонизировал modelKey.
- `L208 normalizeProvider()` — Нормализовал provider identity.
- `L225 normalizeRequired()` — Проверил обязательную текстовую метку.
- `L243 normalizeText()` — Убрал краевые пробелы и проверил допустимый текст.
- `L263 rejectControlCharacters()` — Отверг control characters.
- `L278 normalizePricingVersion()` — Проверил и нормализовал идентификатор ценовой версии.

#### `ModelCatalogService.java`

Оркестратор чтения и транзакционной публикации версий каталога.

**Объявленные методы:**

- `L39 ModelCatalogService()` — Инициализировал зависимости/объект и проверил обязательные значения.
- `L63 findLatest()` · @Transactional — Загрузил последнюю созданную версию (включая будущие).
- `L82 findEffective()` · @Transactional — Загрузил effective-версию по времени.
- `L104 findHistory()` · @Transactional — Прочитал bounded историю каталога.
- `L125 createVersion()` · @Transactional — Проверил права, ожидаемую версию и создал append-only
  запись с audit.
- `L323 importRuntime()` · @Transactional — Импортировал статически настроенный runtime как версию
  каталога.
- `L572 sameRuntimeSnapshot()` — Сравнил импорт runtime с последней immutable записью.
- `L648 moneyEquals()` — Сравнил BigDecimal математически без scale-ложных отличий.
- `L661 recordCatalogAudit()` — Зафиксировал обязательный audit новой версии каталога.
- `L673 validateLimit()` — Ограничил допустимую глубину/размер API-страницы.
- `L681 normalizeCursor()` — Привёл курсор к безопасному каноническому виду.
- `L687 normalizeModelKey()` — Проверил/канонизировал modelKey.

**Связанный риск:** M-02.

#### `ModelControlPlaneAccess.java`

Проверки RBAC и tenant boundary на уровне сервисов.

**Объявленные методы:**

- `L16 ModelControlPlaneAccess()` — Инициализировал зависимости/объект и проверил обязательные
  значения.
- `L19 requireAdminOrSuperAdmin()` — Отверг роль ниже ADMIN.
- `L47 requireSuperAdmin()` — Разрешил изменять global catalog только SUPER_ADMIN.
- `L72 isTenantScopeRestricted()` — Определил, разрешён ли текущему principal доступ к чужому
  tenant.
- `L86 hasAuthority()` — Сопоставил GrantedAuthority с required role.

#### `ModelControlPlaneNumericValidation.java`

Валидация money по границам PostgreSQL numeric(30,12).

**Объявленные методы:**

- `L20 ModelControlPlaneNumericValidation()` — Инициализировал зависимости/объект и проверил
  обязательные значения.
- `L23 requireNonNegativeNumeric30Scale12()` — Проверил неотрицательность и диапазон money.
- `L43 normalizeNonNegativeNumeric30Scale12()` — Преобразовал money к точной PostgreSQL scale без
  округления.
- `L60 requireWorstCaseCostFitsNumeric30Scale12()` — Отверг переполнение расчётного worst-case cost.
- `L93 violatesNonNegativeNumeric30Scale12()` — Определил выход значения за границы PostgreSQL
  numeric.
- `L117 nextVersion()` — Без переполнения вычислил следующую integer-версию.
- `L124 tokenCost()` — Пересчитал token units в USD по цене за миллион.

#### `ModelControlPlaneSchemaVerifier.java`

Fail-fast проверка критических SQL-объектов при старте приложения.

**Объявленные методы:**

- `L20 ModelControlPlaneSchemaVerifier()` — Инициализировал зависимости/объект и проверил
  обязательные значения.
- `L24 afterSingletonsInstantiated()` · @Override — Проверил схему, но не все enabled/validated
  состояния DB-trigger/index.

**Связанный риск:** M-04.

#### `ModelInputTokenEstimator.java`

Единый адаптер детерминированной оценки input units.

**Объявленные методы:**

- `L18 ModelInputTokenEstimator()` — Инициализировал зависимости/объект и проверил обязательные
  значения.
- `L21 version()` — Вернул version идентификатор evidence/алгоритма.
- `L25 estimateBaseRequest()` — Оценил input units sealed pre-RAG запроса.
- `L33 estimateBaseRequest()` — Оценил input units sealed pre-RAG запроса.
- `L41 estimateRouteRequest()` — Оценил input units маршрутизируемого запроса.
- `L48 estimatePreparedRequest()` — Оценил input units подготовленного запроса.

#### `ModelPolicyPreviewService.java`

Read-only оценка черновой policy без записи route/без provider I/O.

**Объявленные методы:**

- `L46 ModelPolicyPreviewService()` — Инициализировал зависимости/объект и проверил обязательные
  значения.
- `L79 preview()` · @Transactional — Проверил draft policy без записи и provider call; не является
  reservation.
- `L183 loadEffectiveCatalog()` — Прочитал каталог страницами с hard limit.
- `L207 evaluateEntry()` — Проверил отдельную модель по draft policy и budget snapshot.
- `L315 evaluateAutomaticSelection()` — Сопоставил default selection с dry-run выводом.
- `L381 syntheticRequest()` — Создал минимальный запрос для предварительной оценки.
- `L400 resolveVisibleOrganization()` — Проверил права чтения tenant.

#### `ModelRouteDecisionAuditDetails.java`

Типизированный аудит routing decision с budget evidence.

Поля record: `decision`, `budget`.

**Объявленные методы:**

- `L19 ModelRouteDecisionAuditDetails()` — Проверил инварианты параметров record.
- `L30 toMap()` · @Override — Преобразовал typed AuditDetails в структуру допустимых audit fields.
- `L161 putIfNonNull()` — Добавил разрешённый audit field только при наличии значения.

#### `ModelRouteDecisionFactory.java`

Создание ALLOWED/DENIED evidence, replay и публичных причин.

**Объявленные методы:**

- `L22 buildDecision()` — Сформировал и sealed новое immutable ALLOWED/DENIED решение.
- `L109 replayDecision()` — Проверил semantic identity при повторе clientRequestId.
- `L163 toResult()` — Собрал результат маршрутизации для вызывающего кода.
- `L203 normalizeMoney()` — Проверил BigDecimal и согласовал numeric(30,12).
- `L211 publicDenialMessage()` — Сформировал контролируемое публичное сообщение об отказе.

#### `ModelRouteDecisionIntegrity.java`

SHA-256 от фиксированного набора полей, legacy совместимость v1/v2/v3.

**Объявленные методы:**

- `L23 ModelRouteDecisionIntegrity()` — Инициализировал зависимости/объект и проверил обязательные
  значения.
- `L26 seal()` — Вычислил persisted SHA-256 или сохранил base-request evidence.
- `L36 requireValid()` — Проверил неизменность route decision через SHA-256.
- `L67 calculateSha256()` — Рассчитал canonical route digest для версии integrity.
- `L78 requireValidV1()` — Проверил исторические допустимые V1 digest варианты.
- `L109 calculateV2Sha256()` — Рассчитал SHA-256 версии integrity V2.
- `L118 calculateV3Sha256()` — Рассчитал SHA-256 версии integrity V3.
- `L122 calculateLengthPrefixedSha256()` — Закодировал поля с префиксом длины для hash.
- `L182 calculateV1Sha256()` — Поддержал legacy историческую схему хеширования.
- `L234 matchesLegacyTimestampPrecision()` — Сопоставил исторический digest при разной точности
  timestamp.
- `L262 writeValue()` — Сериализовал поле с явным nullable кодированием.
- `L266 writeDecimal()` — Сериализовал decimal в canonical формат.
- `L270 writeUtf8()` — Сериализовал UTF-8 строку для digest.
- `L280 sha256()` — Рассчитал SHA-256 от байтов.
- `L289 hashMatches()` — Сопоставил сохранённое и рассчитанное значение.
- `L296 integrityFailure()` — Создал ошибку нарушенного evidence integrity.
- `L302 copyWithHash()` — Создал копию immutable record с заданным hash.
- `L343 copyWithCreatedAt()` — Создал копию с нормализованной точностью времени.
- `L384 nullable()` — Преобразовал nullable numeric/field в ожидаемую форму для audit.
- `L388 decimal()` — Преобразовал BigDecimal в строковую форму без float-ошибок.

#### `ModelRouteExecutionBindingService.java`

AI execution port: выпуск/повторная проверка identity, final seal.

**Объявленные методы:**

- `L27 ModelRouteExecutionBindingService()` — Инициализировал зависимости/объект и проверил
  обязательные значения.
- `L48 bind()` · @Transactional — Связал execution identity с persisted route и seals.
- `L124 authorizePhysicalExecution()` · @Override — Проверил execution identity из Model и повторно
  сверил persist evidence.
- `L153 bindExecution()` · @Override — Адаптировал AI port к DB-backed Model identity.
- `L183 requireExecutable()` · @Transactional — Повторно проверил route/request/target и сохранял
  final prepared seal.
- `L239 requiresSinglePhysicalAttempt()` · @Override — Проверил запрет повтора при immutable
  HARD/max-request-cost policy.
- `L251 readDecision()` — Прочитал persisted decision и проверил SHA-256.

#### `ModelRouteExecutionGuard.java`

Финальная проверка базового запроса и upper input envelope.

**Объявленные методы:**

- `L20 ModelRouteExecutionGuard()` — Инициализировал зависимости/объект и проверил обязательные
  значения.
- `L36 assertWithinReservedInputEnvelope()` — Запретил несоответствие base request или превышение
  input units.

#### `ModelRouteExecutionIdentity.java`

Immutable execution capability с семантическими и SHA-привязками.

Record содержит 22 компонентов. Точные field/annotation contracts сохранены в исходном `.java`; при
изменении DTO отдельно проверить Bean Validation и JSON compatibility.

**Объявленные методы:**

- `L49 ModelRouteExecutionIdentity()` — Проверил инварианты параметров record.
- `L107 ModelRouteExecutionIdentity()` — Проверил инварианты параметров record.
- `L154 bind()` — Связал execution identity с persisted route и seals.
- `L241 requireSameDecision()` — Сопоставил immutable identity и persisted route decision.
- `L268 requireSameBase()` — Проверил неизменность pre-RAG запроса.
- `L282 requirePreparedBase()` — Проверил что prepared request не меняет защищённую основу.
- `L318 requireTrustedPreparedProvenance()` — Проверил доверенный receipt итогового запроса.
- `L346 requireAppendOnly()` — Проверил append-only правило подготовки контекста.
- `L363 differentLong()` — Безопасно сравнил nullable long value.
- `L367 differentInteger()` — Безопасно сравнил nullable integer value.
- `L371 preparedSha256()` — Рассчитал hash готового provider request.
- `L375 baseSha256()` — Рассчитал hash базового запроса.
- `L379 isSha256()` — Проверил синтаксис digest.

#### `ModelRouteReservedRequestService.java`

Запись и проверка base/prepared seals и точного Knowledge RAG provenance.

**Объявленные методы:**

- `L29 ModelRouteReservedRequestService()` — Инициализировал зависимости/объект и проверил
  обязательные значения.
- `L42 seal()` · @Transactional — Вычислил persisted SHA-256 или сохранил base-request evidence.
- `L110 requireKnowledgeReceipt()` · @Transactional — Доказал совпадение identity Knowledge receipt.
- `L134 bindPrepared()` · @Transactional — Создал final V2 sealed request с exact RAG receipt.
- `L211 requireSame()` · @Transactional — Сверил переданный исходный запрос с persisted digest.
- `L235 readDecision()` — Прочитал persisted decision и проверил SHA-256.
- `L244 requireKnowledgeScopeShape()` — Проверил согласование режима Knowledge и KB ID.
- `L260 requireEquivalentPreparedBinding()` — Сравнил новый final receipt с существующим при
  retry/race.

#### `ModelRoutingCostPolicy.java`

Оценка предельной цены, actual/reserved/unknown budget и HARD/SOFT.

**Объявленные методы:**

- `L20 ModelRoutingCostPolicy()` — Инициализировал зависимости/объект и проверил обязательные
  значения.
- `L24 evaluateBudget()` — Рассчитал бюджетный отказ/состояние с known/unknown liability.
- `L43 loadBudgetContext()` — Загрузил актуальные месячные обязательства.
- `L62 loadBudgetContextForPreview()` — Прочитал бюджет без exclusive data-plane lock.
- `L77 loadBudgetContext()` — Загрузил актуальные месячные обязательства.
- `L125 evaluateBudget()` — Рассчитал бюджетный отказ/состояние с known/unknown liability.
- `L223 knownCommitmentExceedsBudget()` — Проверил превышение лимита по известной нижней границе.
- `L248 unverifiableBudget()` — Создал conservative unknown budget state.
- `L272 effectiveInputLimit()` — Вычислил минимум input limits runtime/catalog/policy.
- `L288 effectiveOutputLimit()` — Вычислил минимум output limits runtime/catalog/policy.
- `L304 estimateInputTokens()` — Детерминированно оценил input units route.
- `L309 estimateCost()` — Применил worst-case flat-token ставки; полноценная scheme не проверяется.
- `L370 calculateWorstCaseCost()` — Рассчитал предельную цену с округлением вверх.
- `L387 ModelRoutingCostPolicy()` — Инициализировал зависимости/объект и проверил обязательные
  значения.
- `L405 ModelRoutingCostPolicy()` — Инициализировал зависимости/объект и проверил обязательные
  значения.
- `L422 committedCostUsd()` — Сложил known actual и reserved компоненты.
- `L426 none()` — Создал отсутствие оцененного budget.
- `L442 ModelRoutingCostPolicy()` — Инициализировал зависимости/объект и проверил обязательные
  значения.
- `L561 monthlyCommittedUsd()` — Получил известное обязательство actual + reserved, где применимо.
- `L572 none()` — Создал отсутствие оцененного budget.

**Связанный риск:** M-03/M-09.

#### `ModelRoutingEnvelopeService.java`

Расчёт дополнительного input-unit budget под RAG/tools/system.

**Объявленные методы:**

- `L17 ModelRoutingEnvelopeService()` — Инициализировал зависимости/объект и проверил обязательные
  значения.
- `L26 additionalInputUnitUpperBound()` — Сложил резерв под system/developer, RAG и tool schema.

#### `ModelRoutingExecutionCapabilityGate.java`

Fail-closed запрет пока не реализованных data-plane возможностей.

**Объявленные методы:**

- `L20 ModelRoutingExecutionCapabilityGate()` — Инициализировал зависимости/объект и проверил
  обязательные значения.
- `L23 hasUnsupported()` — Определил невыполненные data-plane capabilities.

#### `ModelRoutingSelectionPolicy.java`

Deterministic выбор catalog version и application policy отказа.

**Объявленные методы:**

- `L26 ModelRoutingSelectionPolicy()` — Инициализировал зависимости/объект и проверил обязательные
  значения.
- `L33 selectCatalog()` — Выбрал explicit/default/runtime model с защитой от неоднозначности.
- `L123 validateCatalogAndPolicy()` — Проверил lifecycle, runtime match, allow/deny и data
  governance.
- `L175 isAllowedByLists()` — При выключенной policy не ограничивает списками; отказ решается выше.
- `L190 runtimeMissesCapability()` — Проверил поддержку запрошенной capability runtime.
- `L207 normalizeNullableKey()` — Нормализовал optional model key.
- `L213 runtimeKey()` — Сформировал диагностическую runtime identity.
- `L225 resolved()` — Создал успешный Selection.
- `L240 policyUnconfigured()` — Создал fail-closed результат отсутствия policy.
- `L251 modelNotFound()` — Создал Selection с причиной отсутствия модели.

#### `ModelRoutingService.java`

Главная транзакционная граница route, deny/allow и audit.

**Объявленные методы:**

- `L47 ModelRoutingService()` — Инициализировал зависимости/объект и проверил обязательные значения.
- `L75 decide()` · @Transactional — В MANDATORY tx сериализовал replay, выбрал policy/catalog,
  sealed result и audit.
- `L307 validateAllowedTurnLinkBeforeExternalSideEffects()` · @Transactional — Принудительно
  проверил deferred SQL constraint до Redis/I/O.
- `L312 findDecision()` · @Transactional — Прочитал decision c tenant-RBAC и проверкой integrity.
- `L339 enforceRequestPricingPolicy()` — Применил requireCompletePricing и maxRequestCostUsd.
- `L404 persistDenied()` — Создал durable DENIED+audit; требует noRollbackFor у публичного decide().
- `L473 persistBudgetCommitment()` — Записал route-time actual/reserved/unknown evidence.
- `L492 recordDecisionAudit()` — Записал typed обязательный Audit route outcome.
- `L506 validateIdentity()` — Сопоставил пользовательский principal и semantic hash.
- `L527 isCrossTenantDecision()` — Проверил попытку чтения решения чужой организации.

#### `OpenAiRuntimeModelHealthProbe.java`

Адаптер metadata probe OpenAI.

**Объявленные методы:**

- `L30 OpenAiRuntimeModelHealthProbe()` — Инициализировал зависимости/объект и проверил обязательные
  значения.
- `L44 provider()` · @Override — Возвратил идентификатор адаптера provider.
- `L49 probe()` · @Override — Сделал ограниченный HTTPS metadata GET OpenAI.

#### `OrganizationModelPolicyAuditDetailsFactory.java`

Подготовка типизированной версии audit для tenant policy.

**Объявленные методы:**

- `L21 OrganizationModelPolicyAuditDetailsFactory()` — Инициализировал зависимости/объект и проверил
  обязательные значения.
- `L24 createAuditDetails()` — Подготовил детали Audit по версии policy.
- `L29 create()` — Сконструировал типизированные AuditDetails из immutable source.
- `L101 addModelKeySnapshot()` — Добавил безопасную сводку набора allow/deny.
- `L149 sha256ModelKeys()` — Вычислил fingerprint множества модельных ключей.

#### `OrganizationModelPolicyRules.java`

Нормализация/валидация tenant policy и её денежных параметров.

**Объявленные методы:**

- `L19 OrganizationModelPolicyRules()` — Инициализировал зависимости/объект и проверил обязательные
  значения.
- `L22 buildPolicy()` — Создал immutable policy после нормализации.
- `L74 normalizeMoney()` — Проверил BigDecimal и согласовал numeric(30,12).
- `L85 validate()` — Применил набор доменных ограничений.
- `L153 normalizeModelKeys()` — Канонизировал и дедуплицировал model keys.
- `L173 normalizeNullableModelKey()` — Нормализовал optional default key.

#### `OrganizationModelPolicyService.java`

Просмотр и append-only мутация tenant model policy.

**Объявленные методы:**

- `L28 OrganizationModelPolicyService()` — Инициализировал зависимости/объект и проверил
  обязательные значения.
- `L47 current()` · @Transactional — Вернул актуальный runtime или текущую policy без изменения
  состояния.
- `L72 createVersion()` · @Transactional — Проверил права, ожидаемую версию и создал append-only
  запись с audit.
- `L128 resolveVisibleOrganization()` — Проверил права чтения tenant.
- `L153 resolveMutableOrganization()` — Проверил право изменения tenant policy и нужную организацию.
- `L178 isCrossTenantAccess()` — Выявил межтенантный доступ для ADMIN.

#### `RuntimeModelHealthProbe.java`

Контракт адаптера безопасного metadata probe.

**Объявленные методы:**

- `L14 provider()` — Возвратил идентификатор адаптера provider.
- `L16 probe()` — Запустил административную проверку provider metadata endpoint.

#### `RuntimeModelProbeHttpSupport.java`

HTTPS metadata probe, bounded timeout, no redirects, discard body.

**Объявленные методы:**

- `L38 RuntimeModelProbeHttpSupport()` — Инициализировал зависимости/объект и проверил обязательные
  значения.
- `L41 probeModel()` — Создал bounded metadata probe без redirects и raw body.
- `L116 executeProbe()` — Послал metadata GET и классифицировал HTTP status.
- `L152 buildRequest()` — Собрал GET request с timeout и безопасными headers.
- `L182 addValidatedHeader()` — Отверг пустые HTTP headers.
- `L204 modelUri()` — Сформировал endpoint `/models/{id}`.
- `L222 validatedBaseUri()` — Проверил HTTPS/host/absence of query, fragment, userinfo.
- `L239 normalizeBaseUrl()` — Привёл base URL к ожидаемому виду.
- `L250 stripTrailingSlashes()` — Удалил конечные `/` в URL.
- `L277 validateBaseUri()` — Применил безопасные ограничения на URI.
- `L315 encodeModel()` — URL-encoded модельный идентификатор в path segment.
- `L331 requireText()` — Проверил непустой текст.
- `L353 boundedTimeout()` — Ограничил timeout 1..10 секунд.
- `L383 unavailable()` — Собрал результат недоступности без текста исключения.
- `L405 internalError()` — Собрал безопасную внутреннюю ошибку probe.
- `L426 elapsedMillis()` — Измерил длительность monotonic clock.
- `L438 mapStatus()` — Сопоставил HTTP status с model probe статусом.
- `L465 publicMessage()` — Сформировал безопасное сообщение для API.

#### `RuntimeModelProbeService.java`

Оркестрация разрешённого probe и консервативных состояний.

**Объявленные методы:**

- `L24 RuntimeModelProbeService()` — Инициализировал зависимости/объект и проверил обязательные
  значения.
- `L70 probe()` — Запустил административную проверку provider metadata endpoint.
- `L116 publicMessage()` — Сформировал безопасное сообщение для API.
- `L128 normalizeProvider()` — Нормализовал provider identity.

#### `RuntimeModelStatusService.java`

Единственный runtime provider/model на deployment, health cache и prices.

**Объявленные методы:**

- `L45 RuntimeModelStatusService()` — Инициализировал зависимости/объект и проверил обязательные
  значения.
- `L54 RuntimeModelStatusService()` · @Autowired — Инициализировал зависимости/объект и проверил
  обязательные значения.
- `L81 current()` — Вернул актуальный runtime или текущую policy без изменения состояния.
- `L98 fromOpenAi()` — Построил статус настроенного OpenAI runtime.
- `L109 fromAnthropic()` — Построил статус настроенного Anthropic runtime.
- `L120 response()` — Собрал conservative runtime status и static pricing metadata.
- `L171 normalizeRuntimeModel()` — Проверил допустимый model ID.
- `L192 recordProbe()` — Обновил in-memory факт последнего probe.
- `L198 healthStatus()` — Вернул свежую probe оценку либо NOT_PROBED.
- `L209 requiredOpenAi()` — Загрузил OpenAI-конфигурацию или отказал.
- `L219 requiredAnthropic()` — Загрузил Anthropic-конфигурацию или отказал.

## Тест-план и release gates

### P1: pricing identity и финансовая корректность

- `CONFIGURED` manual insert: persisted scheme равен выбранному и читается обратно.
- Legacy V66 `LEGACY_FLAT_UNVERIFIED_V1`: невозможно вернуть PRICED без проверки scheme.
- `UNSUPPORTED_COMPLEX_V1`: complete=false, никаких ложных $0 или verified costs.
- `FLAT_TOKEN_V1`: известная price version и exact provider/model identity.
- OpenAI cached input subset vs Anthropic cache counters: golden fixtures.
- Overflow/zero usage/negative counters/specialized billing: UNPRICED/FAILED.
- HARD budget на unknown: no allow от неподтверждённой цены; SOFT evidence сохраняется.
- `pricing_scheme` включён в persistent price-book identity/версию алгоритма.

### Routing, RBAC и concurrency

- ADMIN не читает route/tenant policy другой организации; SUPER_ADMIN может.
- USER не может вызвать admin endpoints даже при прямом вызове service.
- Concurrent createVersion same key: единственная новая версия; второй -> conflict.
- Concurrent decide same chat/clientRequestId: одна immutable decision, safe replay.
- `DENIED` сохраняется вместе с required audit; проверен caller rollback behavior.
- `MANDATORY` вне business transaction вызывает отказ, а не silent standalone.
- Forged/mismatched execution identity не разрешает физический provider call.
- Prepared receipt with existing mismatched hash -> deterministic conflict.
- Knowledge RAG receipt точно соответствует chat/user/tenant/request hash.
- DB V65/V67/V72 triggers реально enabled, индексы valid/ready.

### Performance / operations

- EXPLAIN ANALYZE BUFFERS для latest/effective/history на населённой DB.
- Preview на 5k моделей, защита от 5001 и performance budget.
- Probe 2xx/401/403/404/429/5xx, timeout, redirect, SSRF/config validation.
- JUnit/JdbcTemplate с PostgreSQL Testcontainers: fresh/upgrade V45–V72.
- Полный backend `./mvnw clean test` и security/latency smoke.
- Подтверждение совместимости runtime provider/model и catalog/policy.

```bash
cd backend
./mvnw clean test

# Выборочные группы после инвентаризации реальных имён классов:
find src/test/java -type f -name '*Model*Test.java' -o -name '*Model*IT.java'

# Сверить в PostgreSQL миграции и состояние критических triggers:
# SELECT installed_rank, version, script, success
# FROM flyway_schema_history ORDER BY installed_rank;
```

## Что менять первым

1. Не меняя применённые миграции, провести сквозной `pricing_scheme` из V66 через JDBC/domain.
2. Выделить explicit supported scheme при создании новой версии каталога; legacy оставить legacy.
3. Связать routing worst-case cost и physical resolver единым fail-closed pricing policy.
4. Добавить golden-vector tests по OpenAI/Anthropic и budget HARD unknown.
5. Усилить startup schema verifier и нагрузочные запросы; проверить Testcontainers.
6. После прохождения backend/DB/integration тестов повторить production-like smoke.

## Границы доказательств

Исследованы именно переданные пользователем 78 Java-файлов. Автоматический JDK 21 compiler parser
успешно построил дерево синтаксиса всех этих файлов (без type-resolve зависимостей). Полную
Maven-компиляцию, PostgreSQL migrations, Redis, Testcontainers, frontend/API integration и
production runtime не запускали. Доказанность конкретных SQL-invariants необходимо подтверждать
текущей веткой и БД. Состав таблиц V73/V74/V75 относится к Organization/Usage/Audit, а не меняет
напрямую устройство Model Control Plane.

Исходные `.java` не исправлялись: данный документ является анализом и планом усиления. Изменять
persisted SHA semantics, исторические версии каталога, V66/V65/V67/V72, tenant/RBAC, immutable
receipts или `UNKNOWN != ZERO` ради «зелёных тестов» недопустимо.
