# SafeAI Desk — AI Runtime, Providers & Execution Ledger

> **Модуль:** `ru.safeai.gateway.ai`  
> **Проект:** SafeAI Desk / backend (Java, Spring Boot, PostgreSQL)  
> **Срез исходников:** `main`, commit [`ec2596ed`](https://github.com/Amid-1/Safeai-desk/commit/ec2596ed079423d7103fa3218b51a0ce6cd46df0) 
> от **11.10.2026**  
> **Дата аудита документации:** **16.10.2026**  
> **Статус:** описание реализованного функционала + аудит рисков; **не сертификат production-ready**  
> **Рекомендуемый путь файла в репозитории:** `backend/src/main/java/ru/safeai/gateway/ai/README.md`

## 1. Назначение и границы ответственности

Модуль `ai` выполняет контролируемые обращения к внешним AI-провайдерам **OpenAI** (Responses API) и **Anthropic** 
(Messages API), а также содержит тестовый локальный `mock`-адаптер. Это **не просто HTTP-клиент**: модуль соединяет подготовку 
запроса, проверки допустимой модели, физическое ограничение выходных токенов, детерминированную идентификацию запроса, 
управление попытками, учёт реального provider usage, получение цены и журналирование исходов в PostgreSQL.

Ключевая архитектурная идея — **логический запрос чата и фактическая отправка HTTP-запроса провайдеру не одно и то же**. 
Один логический запрос связан с неизменяемым решением маршрутизации; каждая фактическая попытка обращения к API отдельно 
фиксируется в базе. Это нужно для безопасности повторов, идемпотентности, финансового учёта и разбирательства после тайм-аутов.

**Входит в модуль:**

- DTO входного и выходного AI-контракта, история сообщений, роли, метаданные ответа;
- ограничение длины и приблизительного размера AI-контекста, усечение истории целыми парами;
- формирование фактического OpenAI/Anthropic payload и лимитов генерации;
- HTTP-транспорт с запретом перенаправлений, connect/read timeout, лимитом размера тела ответа;
- классификация сбоев, ограниченные повторы (`retry`), наблюдение и фиксация каждой физической попытки;
- хранение планов и попыток AI-выполнения, проверка разрешённой модели;
- извлечение и сохранение provider-reported tokens, cached/cache-write usage и ценовых метаданных;
- базовое ценообразование и мост к версионированному каталогу цен;
- преобразование AI-ошибок в ответы REST API.

**За пределами модуля:** организация пользователей, права доступа к чату, создание и финализация `ChatTurn`, 
резервирование и списание пользовательских квот, выбор разрешённой модели и политики организации, RAG/knowledge retrieval, 
управление версионированным каталогом и общим бюджетом. Эти обязанности принадлежат соответственно пакетам `chat`, `model`, 
`knowledge`, `auth`, `usage` и общей инфраструктуре. Модуль `ai` интегрируется с ними, но **не заменяет** их инварианты.

### Термины

| Термин | Что означает здесь |
|---|---|
| **Provider** | Внешний AI-поставщик: OpenAI либо Anthropic; `mock` — локальная имитация. |
| **Model route / decision** | Однократно принятое и сохранённое решение, какая физическая модель разрешена данному запросу. |
| **Execution plan** | Долговечная запись одной логической AI-операции перед реальным сетевым обращением. |
| **Physical attempt** | Одна конкретная попытка отправить запрос провайдеру; у каждой свой UUID и номер. |
| **Governed execution** | Выполнение только при наличии проверенной маршрутизации, связанной операции и разрешённых ограничений. |
| **Fail-closed** | При невозможности доказать безопасность/стоимость система отклоняет операцию или фиксирует неопределённость, а не предполагает успех или нулевую стоимость. |
| **Outcome ambiguous** | Нельзя достоверно установить, обработал ли внешний провайдер запрос; слепой повтор может вызвать двойной расход. |
| **Usage** | Количество токенов, сообщённое самим провайдером, с признаками полноты. |
| **Pricing scheme** | Идентификатор подтверждённого алгоритма тарификации, сохраняемый вместе с версией каталога. |
| **RAG** | Retrieval-Augmented Generation: добавление найденных материалов из базы знаний перед AI-запросом. |
| **Retry** | Повтор API-запроса после сбоя **только тогда**, когда это разрешено записанными доказательствами. |
| **Idempotency** | Повтор одного логического запроса не должен создавать новую независимую операцию или дублирующий внешний вызов. |

---

## 2. Архитектура и потоки

### 2.1. Основной путь

```text
HTTP-запрос пользователя
   │
   ▼
Chat / security / organization / model policy       [другие модули]
   │ сохранение ChatTurn + неизменяемое ModelRouteDecision
   │ ограничение квот до начала внешнего API-вызова
   ▼
AiChatRequest + AiExecutionIdentity + ProviderExecutionTarget
   │
   ▼
AiExecutionService.execute(AiExecutionRequest)
   ├─ AiModelExecutionPort.authorizePhysicalExecution(...)
   ├─ проверка зарегистрированного физического target (текущая single-provider модель)
   ├─ AiModelExecutionPort.requiresSinglePhysicalAttempt(...)
   └─ AiExecutionPlanService.createOrRead(...)  → model_execution_plans
         │
         ▼
      AiExecutionAttemptScope.execute(...)
         │
         ▼
      OpenAiProvider / AnthropicProvider / MockAiProvider
         ├─ AiContextWindowService.prepare(...)
         ├─ AiChatRequest.effectiveMaxOutputTokens(...)
         ├─ составление provider payload
         └─ AiProviderRetryExecutor.execute(...)
               │ проверяет активный execution scope
               ├─ observer.started(...) → отдельная DB-транзакция
               │                         → model_execution_attempts STARTED
               ├─ реальный HTTP POST / mock
               ├─ AiProviderSupport.extractTokenUsage(...)
               ├─ AiResponseMetadataService.extract(...)
               │     └─ @Primary VersionedCatalogPricingResolver
               ├─ observer.succeeded / failed / ambiguous
               │     └─ обновление отдельного physical attempt в БД
               └─ возвращает AiChatResponse либо исключение
         │
         ▼
      AiExecutionResult
         │
         ▼
ChatTurn finalization / quota settlement             [другие модули]
```

**Принципиально:** `AiContextWindowService` и провайдер работают с текстом, но **право на физическую отправку** определяется н
е текстом, а durable decision + identity + operation. В `AiExecutionService` проверка разрешения выполняется до создания physical attempt.

### 2.2. Путь ошибки, тайм-аута и повтора

1. Перед отправкой `AiProviderRetryExecutor` вызывает `attemptScope.started(...)`. `AiExecutionAttemptRecorder` в `REQUIRES_NEW` сохраняет `STARTED` в `model_execution_attempts`.
2. Если вернулся валидный ответ, фиксируется `SUCCEEDED`, usage и pricing, после чего проверяется соответствие фактической модели разрешённой цели.
3. При документированном отказе фиксируется `FAILED` с типом сбоя и доказанной/недоказанной определённостью исхода.
4. При неопределённости фиксируется `AMBIGUOUS`. Повтор не должен выдаваться за безопасный.
5. Повтор разрешён только если локальная политика, тип ошибки, состояние предыдущей попытки и ограничения срока согласованы. Для жёсткого денежного enforcement `AiExecutionService` ограничивает количество физических попыток одним через `requiresSinglePhysicalAttempt`.
6. Если ответ провайдера уже получен, но запись терминального состояния в БД не удалась, формируется `PhysicalAttemptReconciliationRequiredException`. Это не «обычная 500 с безопасным retry».
7. Внешняя ошибка переводится в HTTP-ответ с помощью `AiExceptionHandler`; клиент должен ориентироваться на состояние исходного `ChatTurn`, а не отправлять новое сообщение вслепую.

### 2.3. Граница транзакций

- **План выполнения** создаётся/читается через `AiExecutionPlanService.createOrRead` с `@Transactional(REQUIRES_NEW)`.
- **Каждое физическое событие** (`started`, `succeeded`, `failed`, `ambiguous`) сохраняется через `AiExecutionAttemptRecorder` с `REQUIRES_NEW`.
- **Сетевой вызов не должен удерживать общую транзакцию чата**. Отдельный durable ledger нужен для записи фактов даже при откате другой бизнес-операции.
- `ModelExecutionAttemptRepository.organizationEpochCurrent(...)` — предварительная проверка доступа организации; окончательная конкурентная защита заявлена триггером миграции **V72** при записи `STARTED`.
- Конкурентный порядок попыток и их переходы дополнительно ограничены серверной схемой/триггерами **V53**. Наличие Java-проверок не отменяет необходимости проверять миграции и права доступа к таблицам.

### 2.4. Состояния

| Объект | Состояния / статус | Смысл |
|---|---|---|
| Physical attempt | `STARTED`, `SUCCEEDED`, `FAILED`, `AMBIGUOUS`, `PROTOCOL_REJECTED` | Фактическое событие у провайдера, не эквивалентно состоянию чата. |
| Outcome certainty | `KNOWN_NOT_EXECUTED`, `KNOWN_REJECTED`, `KNOWN_EXECUTED`, `AMBIGUOUS` | Что действительно доказано об исходе. |
| Retry safety | `SAME_TARGET_RETRY_ALLOWED`, `SAME_TARGET_RETRY_FORBIDDEN` | Допуск повторной отправки **того же** target. |
| Fallback safety | `FALLBACK_ALLOWED`, `FALLBACK_FORBIDDEN` | В модели есть понятие fallback; переключение на другой target пока **не реализовано**. |
| AI response | `COMPLETED`, `REFUSED`, `INCOMPLETE` | Ответ провайдера завершён, отказан либо оборван ограничением/условием. |
| Usage | `AVAILABLE`, `PARTIAL`, `MISSING`, `NOT_APPLICABLE` | Насколько полны токены. `NOT_APPLICABLE` запрещён для реального provider response. |
| Price | `PRICED`, `FREE`, `UNPRICED`, `CALCULATION_FAILED`, `NOT_APPLICABLE` | Доказана ли цена, бесплатна ли модель или расчёт невозможен. |

**Важно:** `UNPRICED` — **не $0**, а неизвестная или неподтверждённая стоимость; `AMBIGUOUS` — **не доказанный отказ**. Эти различия должны сохраняться при расчёте квот, отчётах и UI.

---

## 3. Полный каталог классов и ключевых методов

Исходный каталог модуля проверен по `main`: `config`, `dto`, `exception`, `execution`, `input`, `metadata`, `pricing`, 
`provider`, `web`, включая подпакеты `provider/openai`, `provider/anthropic`, `provider/mock`. **В таблицах перечислены все 
Java-файлы, а для крупных классов — публичный контракт и существенные внутренние методы; не перечисляются автоматически 
генерируемые getters и очевидные private helpers.**

### 3.1. `config/` — создание компонентов и стартовые проверки

| Класс | Назначение и ключевые методы |
|---|---|
| `AiConfiguration` | `@Configuration`, подключает `AiProviderProperties`, `AiRetryProperties`, `AiContextWindowProperties`, `ModelPricingProperties` через `@EnableConfigurationProperties`. |
| `AiProductionConfigurationValidator` | `validate()` (`@PostConstruct`) — запрещает `mock` и неизвестный provider в production; `normalizeProvider`, `displayValue` — представление настройки. |
| `AiRetryTimeoutBudgetValidator` | `validate()` — проверяет выбранного провайдера; `validateBudget()` — `connectTimeout + readTimeout <= totalTimeout`; `safeAdd()` — защита от переполнения `Duration`; `requireSelectedProviderProperties()` — наличие нужного bean. |
| `OpenAiProviderConfiguration` | Условно регистрирует настройки `OpenAiProperties` при `safeai.ai.provider=openai`. Сам `OpenAiProvider` — `@Service`. |
| `AnthropicProviderConfiguration` | Условно создаёт `anthropicRestClient()` и `anthropicProvider()` при `provider=anthropic`, передаёт общий retry, context-window и `Clock`. |

**Поведение production:** из `application-prod.yml` параметр `safeai.ai.provider` обязателен; `AiProductionConfigurationValidator` принимает только `openai` или `anthropic`. Нет скрытого возврата к `mock` при невалидном production provider.

### 3.2. `dto/` — запрос и ответ

| Класс | Назначение и ключевые методы |
|---|---|
| `AiChatRequest` | Неизменяемый `record` с user/org/chat/operation identity, system/developer instructions, сообщением, историей, `reservedInputTokens`, `maxOutputTokens`. Компактный конструктор валидирует размеры, canonical-history и лимиты; совместимый старый конструктор не содержит governance caps. `withInstructions(...)`, `withHistory(...)` сохраняют identity и ограничения; `effectiveMaxOutputTokens(runtimeMaximum)` не превышает разрешённый лимит маршрута. |
| `AiChatResponse` | Структурированный `record`: текст, запрошенная и реальная модель, provider IDs, finish reason, токены, cached/cache-write размеры, usage status, цена, валюта, price version, calculation time. `fromProvider(...)` (несколько перегрузок), `determineUsageStatus(...)`, встроенные `validateToken`, `validateUsageStatus`, `validateCost`, `validatePricing`. |
| `AiMessage` | `record(role, content)`; проверка роли, непустого текста и абсолютной длины; конструктор со строковой ролью вызывает `AiMessageRole.parse`. |
| `AiMessageRole` | `USER`, `ASSISTANT`, `SYSTEM`, `DEVELOPER`; `parse(value)` и `providerValue()`. В **history** допускаются только пользователь/ассистент; system/developer идут отдельно. |

**Инварианты `AiChatRequest`:** история либо пустая, либо начинается с `USER`, чередуется `USER/ASSISTANT` и оканчивается `ASSISTANT`. Срезается только предыдущая завершённая история; текущее сообщение передаётся отдельно. Ограничены размеры каждого текста, числа сообщений и суммарного текста. Источник безопасности — проверка неизменяемых identity/caps и поздний governance guard, а не только эти текстовые лимиты.

### 3.3. `metadata/` — классификация результата

| Класс | Назначение и ключевые методы |
|---|---|
| `AiResponseStatus` | Enum: `COMPLETED`, `REFUSED`, `INCOMPLETE`. |
| `UsageStatus` | Enum: `NOT_APPLICABLE`, `AVAILABLE`, `MISSING`, `PARTIAL`. |
| `PricingStatus` | Enum: `NOT_APPLICABLE`, `PRICED`, `FREE`, `UNPRICED`, `CALCULATION_FAILED`. |
| `AiTokenUsage` | `record` с обычными и специализированными счётчиками; `basic(input, output)` создаёт простой снимок; `usageStatus()` вычисляет полноту; `hasSpecializedBillableTokens()` выявляет реальные cache/cached counters; конструктор отсекает отрицательные значения и противоречивые признаки. |

### 3.4. `input/` — единая модель входных единиц и отпечатки

| Класс | Назначение и ключевые методы |
|---|---|
| `AiInputUnitEstimator` | `estimateBaseRequest(message, history)`, `estimatePreparedRequest(request)`, `utf8Length(value)`. Версия `UTF8_STRUCTURAL_UNITS_V2`. Измеряет **структурные единицы**: число UTF-8 байт плюс allowance на сообщение, а не доказанное количество токенов провайдера. Влияет на routing/governance bounds. |
| `AiRequestFingerprint` | `baseSha256(request)` — отпечаток исходного текстового состава; `preparedSha256(request)` — отпечаток identity, caps и подготовленного текста. Внутренние `digest`, `write`, `writeHistory` сериализуют поля с указанием длины, защищая границы конкатенации. SHA-256 — целостность/идентификация, **не шифрование**. |

### 3.5. `execution/` — строгое выполнение и долговечный журнал

| Класс | Назначение и ключевые методы |
|---|---|
| `AiExecutionService` | Центральная точка: `execute(AiExecutionRequest)` — авторизация, проверка target, плана и выполнение; `bindExecution(...)` — выпуск DB-backed identity; `targetFor(requestedPhysicalModel)` — проверка активной модели; внутренний `Observer.started/succeeded/failed/ambiguous` направляет события в recorder. |
| `AiExecutionRequest` | `record` из chatTurnId, modelRouteDecisionId, `AiChatRequest`, target, executionIdentity и sealed pre-RAG request. Конструктор требует согласованных identity. |
| `AiExecutionResult` | `record(executionPlanId, response)` — ссылка на план и результат AI. |
| `AiExecutionIdentity` | Интерфейс `decisionId()`, `chatTurnId()`: минимальная привязка к разрешённому маршруту. |
| `AiModelExecutionPort` | Межмодульный порт: `bindExecution`, `authorizePhysicalExecution`, `requiresSinglePhysicalAttempt`; реализация находится за пределами `ai` в model control plane. |
| `AiKnowledgeExecutionMode` | Интерфейс `usesKnowledge()` — признак режима работы с RAG. |
| `AiExecutionPlanService` | `createOrRead(request)` — отдельная транзакция, проверяет identity уже созданного плана; возобновление разрешается, только когда нет ни одной физической попытки. |
| `ModelExecutionPlanEntity` | JPA-объект `model_execution_plans`; `create(...)`, `requireMatches(request)`; содержит providerOperationId, chatTurnId, orgId, decisionId, requested model. |
| `ModelExecutionPlanRepository` | `findByProviderOperationId(...)`; JPA CRUD. |
| `AiExecutionAttemptScope` | Защищённый `ThreadLocal` context: `execute(...)`, `requireActive(...)`, `started`, `succeeded`, `failed`, `ambiguous`, `active`, `permittedMaxAttempts`, `hasActiveAttempt`. Запрещает вложенные scope, повторный STARTED при незавершённом предшественнике, чужой operation ID и пропущенные физические события. |
| `AiExecutionAttemptObserver` | Интерфейс событий `started`, `succeeded`, `failed`, `ambiguous`; получает `AiProviderAttemptContext`. |
| `AiExecutionAttemptRecorder` | `started(planId, target, attempt)`, `succeeded(id, response)`, `failed(id, exception)`, `ambiguous(id, exception)` — отдельные DB-транзакции; проверяет authorization epoch, последовательность попыток, допустимость повтора и точный target. |
| `ModelExecutionAttemptEntity` | JPA-объект `model_execution_attempts`; фабрика `started(...)`; методы переходов `succeeded`, `failed`, `ambiguous`; хранит certainty, retry/fallback flags, provider IDs, usage, cache-метрики и price evidence. |
| `ModelExecutionAttemptRepository` | `findByProviderAttemptId`, `findTopByExecutionPlanIdOrderByAttemptNumberDesc`, `organizationEpochCurrent(planId)`; последний — предварительная, а не окончательная конкурентная защита. |
| `ProviderExecutionTarget` | Неизменяемая идентичность physical endpoint: provider/config/version/deployment/model/allowed resolved models; `staticTarget`, `requireApprovedResolvedModel`, `requireApprovedResponse`. Контроль фактической модели не допускает незаметной подмены. |
| `ProviderFailureCertainty` | `classify(AiProviderException)` — определяет, что достоверно известно о сбое; неоднозначные сетевые ошибки не признаёт доказанным отказом. |
| `ProviderUsageEvidence` | `record` provider-reported input/cache/cache-write/output usage; `from(response)`; валидация счётчиков. |
| `ProviderPricingEvidence` | `record` статуса расчёта, стоимости и идентификаторов тарифа; `from(response)`. |
| `ExecutionAttemptOutcome` | Enum: `STARTED`, `SUCCEEDED`, `FAILED`, `AMBIGUOUS`, `PROTOCOL_REJECTED`. |
| `OutcomeCertainty` | Enum: `KNOWN_NOT_EXECUTED`, `KNOWN_REJECTED`, `KNOWN_EXECUTED`, `AMBIGUOUS`. |
| `RetrySafety` | Enum допуска повторной попытки того же target. |
| `FallbackSafety` | Enum готовности к переключению target; фактический fallback пока не реализован. |
| `ResponseValidationStatus` | Enum результата проверки ответа: accepted/protocol-rejected/unsupported-tool/invalid-schema. |
| `AiOrganizationExecutionDeniedException` | Исключение отказа авторизации организации до следующего `STARTED`. |
| `PhysicalAttemptReconciliationRequiredException` | Содержит `providerAttemptId()`: ответ физически получен, но терминальное evidence не удалось надёжно записать; необходима reconciliation, не повтор. |
| `ResolvedModelMismatchException` | Исключение при физической модели ответа, не входящей в разрешённый route/target; реальная уже записанная успешная попытка остаётся evidence. |

**Особая гарантия:** `AiExecutionAttemptRecorder.started(...)` сравнивает номер попытки с предыдущей и разрешает повтор только 
после известного терминального `FAILED`, `SAME_TARGET_RETRY_ALLOWED` и строгого совпадения параметров target. Это не означает, 
что все неизвестные сетевые исходы доказанно оплачены; неопределённость должна дополнительно учитываться финансовыми механизмами.

### 3.6. `provider/` — интерфейсы, контекст, транспорт, retry

| Класс | Назначение и ключевые методы |
|---|---|
| `AiProvider` | Интерфейс `sendMessage(request)`, `executionTarget()`, `recordsPhysicalAttempts()`. Реальный адаптер должен декларировать поддержку durable evidence. |
| `AiProviderProperties` | Настройка `safeai.ai.provider`, допустимо `mock/openai/anthropic`, по умолчанию `mock` для непроизводственных окружений. |
| `AiProviderAttemptContext` | `record(operationId, attemptId, attemptNumber, maxAttempts)`; валидация последовательности и идентификаторов. |
| `AiProviderRetryExecutor` | `execute(provider, model, operationId, attemptTimeout, action)` — физические попытки внутри активного scope; `shouldRetry`, `retryDelay`, `fitsDeadline`, `sleep`, `withJitter`, расчёт backoff. Старый `execute(provider, model, Supplier)` deprecated и **не создаёт scope**, поэтому сам по себе не открывает путь к сетевому вызову. |
| `AiRetryProperties` | `isEnabled`, `effectiveMaxAttempts`, `effectiveInitialBackoff`, `effectiveMaxBackoff`, `effectiveMaxRetryAfter`, `effectiveTotalTimeout`; жёсткая валидация диапазонов. |
| `AiContextWindowService` | `prepare(request, maxInputTokens, reservedOutputTokens)` — контроль обязательного текста и усечение истории; вспомогательные `calculateTokenBudget`, `estimateMandatoryTokens`, `estimateTextMessageTokens`, `mandatoryChars`. |
| `AiContextWindowProperties` | Максимум текста/истории, параметры грубой оценки токенов и safety margin; `defaults()`. |
| `AiResponseMetadataService` | `extract(...)` с/без `providerOperationId`, `AiResponseMetadata.usageEvidence()`; связывает реальный ответ и `PricingResolver`. |
| `AiProviderSupport` | `buildOpenAiInput`, `buildAnthropicMessages`, `buildAnthropicSystem`, `extractTokenUsage`, `extractInputTokens`, `extractOutputTokens`, `resolvedModel`, `extractProviderRequestId`, `extractProviderError`, `extractRetryAfter`, `requireValidContent`, `appendBoundedText`, определение сетевых ошибок. |
| `AiJsonNodeSupport` | `textOrNull`, `parseOrNull`; безопасное чтение некоторых JSON-полей, недопустимый JSON возвращает `null`. |
| `AiProviderExceptionFactory` | `fromResourceAccess`, `unknownFailure`, `parsingFailure` — связывает HTTP/IO и типизированные исключения, неопределённость outcome. |
| `AiRestClientFactory` | `create(baseUrl, connectTimeout, readTimeout[, maxResponseBodyBytes])`; JDK `HttpClient`, redirect `NEVER`, bounded-response interceptor; внутренние `LimitedClientHttpResponse`, `LimitedInputStream`. |
| `AiResponseTooLargeIOException` | Специальный IO-сигнал превышенного размера HTTP-ответа. |
| `ProviderEvidenceText` | `optional`, `required` — ограничение и нейтрализация управляющих символов во внешних ID/коротких метаданных. |
| `ProviderPropertyValidator` | `requireAllowedHttpsBaseUrl`, `requireConnectTimeout`, `requireReadTimeout`, `requireIntRange`, `requireLongRange`, `requireSecret`, `requireString`; allowlist HTTPS URL. |
| `ProviderRegistry` | Собирает immutable `TargetKey → AiProvider`; `require(target)`, `registeredTargetCount()`. **Реальная основная исполнительная цепочка пока напрямую использует один внедрённый `AiProvider`, а не этот registry.** |

**Контекст:** лимит `AiContextWindowService` — предварительная эвристическая оценка для ограничения объёма. Дополнительно существуют `AiInputUnitEstimator` и проверка подготовленного запроса в model control plane. Эти компоненты не следует считать полностью эквивалентным provider tokenizer без отдельных доказательств и тестов.

### 3.7. `provider/openai/`, `provider/anthropic/`, `provider/mock/`

| Класс | Назначение и ключевые методы |
|---|---|
| `OpenAiProperties` | Конфигурация base URL, API key, model, входных/выходных лимитов, body-size, `store`, connect/read timeout; `effectiveStore()` совместим со старым кодом. `store=false` по умолчанию. |
| `OpenAiProvider` | `executionTarget`, `recordsPhysicalAttempts`, `sendMessage`; внутренние `doSendMessage`, `createResponse`, `parseResponse`, `extractOutputText`, `mapHttpException`, `attemptTimeout`. Отправляет `POST /responses`, обязательный `max_output_tokens` и `X-Client-Request-Id` конкретной попытки. |
| `AnthropicProperties` | Настройки Messages API: base URL, key, model, API version, `maxTokens`, body/response limits и тайм-ауты. |
| `AnthropicProvider` | `executionTarget`, `recordsPhysicalAttempts`, `sendMessage`; `sendAttempt`, `createPayload`, `createResponse`, `parseResponse`, `mapStopReason`, `extractTextContent`, `mapHttpException`, `calculateAttemptTimeout`. Отправляет `POST /messages`, задаёт `anthropic-version` и `max_tokens`. |
| `MockAiProvider` | Только non-production: `executionTarget`, `recordsPhysicalAttempts`, `sendMessage`, `sendAttempt`; выдаёт детерминированный текст и локально оценивает токены. Участвует в общем attempt ledger, а не обходит его. |

**Статусы ответа:** OpenAI анализирует `status`, `output_text`/`refusal`, а Anthropic — `stop_reason`, `content` и тип ответа. `tool_use` не поддерживается в данном текстовом потоке и отклоняется. Ни один из адаптеров не должен молча заменить отсутствующий `response.model` на запрошенную модель.

### 3.8. `pricing/` — цены и их доказательство

| Класс | Назначение и ключевые методы |
|---|---|
| `PricingResolver` | Порт `resolve(target, resolvedModel, usage)` и перегрузка `resolve(operationId, target, resolvedModel, usage)`. Важно: default-перегрузка интерфейса без переопределения **теряет идентичность операции**. |
| `StaticConfigurationPricingResolver` | `@Service`; `resolve(...)` делегирует статическому `ModelPricingService`. Оставлен как переходный/совместимый адаптер, не приоритетный governed-resolver. |
| `ModelPricingService` | `calculate(resolvedModel, tokens..., usageStatus)` и `calculate(resolvedModel, AiTokenUsage)`; обычные тарифы из `ModelPricingProperties`. Не объявляет вычисление доказанным при cache billing, для которого статическая схема не описывает отдельную ставку. |
| `ModelPricingProperties` | Конфигурируемая `models` карта, `find(resolvedModel)`, `models()`; вложенный `ModelPrice` (ставка input/output на 1 млн токенов, валюта, версия, `free`). |
| `ModelPricingScheme` | Enum `FLAT_TOKEN_V1`, `LEGACY_FLAT_UNVERIFIED_V1`, `UNSUPPORTED_COMPLEX_V1`; `supportedForAutomaticSettlement`, `databaseValue`, `fromDatabaseValue`. Задуман как инвариант V66 — **на текущем read/settlement пути ещё не внедрён** (см. P0 ниже). |
| `PricingResult` | `record(status, costUsd, currency, priceVersion, calculatedAt)`; `unpriced`, `calculationFailed`, строгая валидация сочетаний статуса и метаданных. |

**Межмодульный основной резолвер:** [`ru.safeai.gateway.model.pricing.VersionedCatalogPricingResolver`](../model/pricing/VersionedCatalogPricingResolver.java) аннотирован `@Primary @Service`. Именно он при стандартном Spring DI выбирается для `AiResponseMetadataService`. Его `resolve(operationId, ...)` сверяет сохранённый execution plan, route decision, версию модели каталога и её ставки; для OpenAI учитывает cached-токены как часть total input, для Anthropic — отдельно. При неподтверждённых данных возвращает `UNPRICED`/`CALCULATION_FAILED`. **Проблема `pricing_scheme` описана в разделе 8: не все задекларированные в миграции V66 проверки доведены до этого резолвера.**

### 3.9. `exception/` — исключения

| Класс | Назначение |
|---|---|
| `AiContextLimitException` | Превышен допустимый AI-контекст. |
| `AiProviderException` | Базовое типизированное исключение с provider/model/status/requestId/errorCode, `retryRecommended`, `outcomeAmbiguous`, `retryAfter`; `isRetryable()` требует рекомендованного повтора и доказанной неамбивалентности. |
| `AiProviderErrorType` | Типы: network/connect, timeout, rate-limit, overload, quota, billing, auth, permission, bad request, слишком большой ответ, protocol, server и unknown. |
| `AiProviderBillingException` | Ошибка оплаты/учётной записи провайдера. |
| `AiProviderQuotaExceededException` | Внешний лимит расхода/квота API-поставщика. |
| `AiProviderRateLimitedException` | Временный 429 и возможное `Retry-After`. |
| `AiProviderOverloadedException` | Сервис провайдера перегружен, исход консервативно неопределён. |
| `AiProviderResponseTooLargeException` | Невозможность принять oversized response без нарушения ограничений. |
| `AiProviderTimeoutException` | Тайм-аут, включая неопределённый исход после отправки запроса. |
| `AiProviderUnavailableException` | Недоступность соединения/провайдера. |

### 3.10. `web/` — HTTP-граница

| Класс | Назначение и ключевые методы |
|---|---|
| `AiExceptionHandler` | `@RestControllerAdvice @Order(0)`. Обработчики `handleContextLimit`, `handleReconciliationRequired`, `handleTimeout`, `handleRateLimited`, `handleOverloaded`, `handleProviderAccountFailure`, `handleUnavailable`, `handleProvider`; служебные `logProviderError`, `buildResponse`. Возвращает `ApiErrorResponse`, no-store; иногда `Retry-After`. |

**Основные HTTP-сопоставления:** `AiContextLimitException` → 400, reconciliation → 409, timeout → 504, rate limit → 429, 
overloaded/account/unavailable → 503, прочие ошибки провайдера → 502. Это описание реально присутствующих handlers; общая обработка ошибок остальных модулей находится за пределами `ai`.

---

## 4. Конфигурация и эксплуатационные параметры

Источники: `backend/src/main/resources/application.yml`, `application-local.yml`, `application-prod.yml`, свойства классов 
и стартовые валидаторы. Приведены **значения YAML для base-конфигурации**, если профиль не переопределяет настройку. Настройки классов иногда имеют **другие дефолты при прямом конструировании**; пример — `AiRetryProperties.totalTimeout` (65 s в record, 75 s в базовом YAML).

| Параметр | Базовое значение | Значение/ограничение |
|---|---|---|
| `safeai.ai.provider` | `mock` | `mock`, `openai`, `anthropic`; в prod только реальные провайдеры. |
| `safeai.ai.retry.enabled` | `false` | Безопасный обычный режим без автоматических повторов. |
| `safeai.ai.retry.max-attempts` | `1` | 1…3, при `enabled=false` ровно 1. Hard enforcement может дополнительно ограничить до 1. |
| `safeai.ai.retry.initial-backoff` | `250ms` | Начальная задержка повторов, случайный jitter. |
| `safeai.ai.retry.max-backoff` | `2s` | Верхняя граница удвоения задержки. |
| `safeai.ai.retry.max-retry-after` | `10s` | Максимальный принимаемый `Retry-After`. |
| `safeai.ai.retry.total-timeout` | `75s` | Бюджет планирования повторов; **не гарантирует принудительное прерывание уже начатого HTTP I/O**. `application-local.yml` задаёт `65s`. |
| `safeai.ai.context.max-user-message-chars` | `16000` | Максимальная длина нового сообщения для context preparation. |
| `safeai.ai.context.max-instruction-chars` | `32000` | Отдельный лимит каждого блока instructions. |
| `safeai.ai.context.max-history-messages` | `100` | При усечении сохраняются полные пары USER/ASSISTANT. |
| `safeai.ai.context.max-message-chars` | `50000` | Лимит одного исторического сообщения. |
| `safeai.ai.context.max-total-input-chars` | `250000` | Лимит совокупного текста в подготовленном AI-контексте. |
| `safeai.ai.context.chars-per-estimated-token` | `3` | Только эвристика размера, не официальный tokenizer. |
| `safeai.ai.context.message-overhead-tokens` | `8` | Эвристика форматирования сообщений. |
| `safeai.ai.context.safety-margin-tokens` | `1024` | Запас контекстного окна. |
| `safeai.ai.openai.base-url` | `https://api.openai.com/v1` | URL проверяется против allowlist; custom hosts не разрешены. |
| `safeai.ai.openai.model` | переменная `OPENAI_MODEL` | Требуется для активного OpenAI provider. |
| `safeai.ai.openai.max-input-tokens` | `64000` | Физическое ограничение адаптера. |
| `safeai.ai.openai.max-output-tokens` | `2048` | Передаётся как `max_output_tokens` после `min()` с route cap. |
| `safeai.ai.openai.store` | `false` | Сохранение ответа на стороне OpenAI отключено по умолчанию. |
| `safeai.ai.openai.max-response-body-bytes` | `2097152` (2 MiB) | Ограничение чтения ответа. |
| `safeai.ai.openai.connect-timeout` / `read-timeout` | `5s` / `60s` | Сумма одного attempt budget — 65 s. |
| `safeai.ai.anthropic.base-url` | `https://api.anthropic.com/v1` | Аналогичный URL allowlist. |
| `safeai.ai.anthropic.model` | переменная `ANTHROPIC_MODEL` | Требуется для активного Anthropic provider. |
| `safeai.ai.anthropic.version` | `2023-06-01` | HTTP header `anthropic-version`. |
| `safeai.ai.anthropic.max-input-tokens` | `64000` | Физическое ограничение адаптера. |
| `safeai.ai.anthropic.max-tokens` | `2048` | Передаётся как `max_tokens` после `min()` с route cap. |
| `safeai.ai.anthropic.max-response-body-bytes` | `2097152` (2 MiB) | Ограничение чтения ответа. |
| `safeai.ai.anthropic.connect-timeout` / `read-timeout` | `5s` / `60s` | Сумма одного attempt budget — 65 s. |
| `safeai.ai.pricing.models` | три static example rates | Локальные/совместимые тарифы; не источник истины для governed production settlement с `@Primary` resolver. |

**Секреты:** `OPENAI_API_KEY` и `ANTHROPIC_API_KEY` подаются через окружение/secret management и никогда не должны попадать в 
`README`, тестовые логи, git diff или HTTP error body. Запрещено подменять реальный API key placeholder-значением в production. 
Перед release нужно проверить `SAFEAI_AI_PROVIDER`, выбранную модель, её snapshot в каталоге, active organization policy и её разрешённые ограничения.

**Важные стартовые инварианты:** `AiRetryTimeoutBudgetValidator` требует, чтобы `retry.total-timeout >= provider.connect-timeout + provider.read-timeout`.
При base-конфигурации 75 s ≥ 65 s. Для `mock` внешний HTTP timeout не проверяется. Предельно допустимые настройки валидируются при создании `@ConfigurationProperties`.

---

## 5. Модель данных, миграции и ownership

| Таблица / сущность | Владелец и значение |
|---|---|
| `model_route_decisions` | Model Control Plane. Неизменяемое решение, разрешённая модель, каталог и бюджетные сведения. |
| `model_catalog_entries` | Версионированные модели/ставки; V66 добавляет `pricing_scheme`, определяющий проверенный алгоритм ценообразования. |
| `model_execution_plans` | AI execution: providerOperationId, chatTurnId, organizationId, modelRouteDecisionId и физическая модель. |
| `model_execution_attempts` | AI execution: номер и UUID физической попытки, исход, certainty, retry safety, vendor request IDs, token usage, price evidence. |
| `chat_turns` | Владелец — `chat`: исход логического запроса и связь с model route; не тождественен отдельному physical attempt. |
| `chat_quota_reservations` | Владелец — `chat`: лимиты и резервирование. Доказательство денежных hard limits требует совместного аудита с AI ledger и model pricing. |

Связанные миграции: `V51__ai_execution_attempt_integrity_hardening.sql`, `V52__model_strict_execution_and_modality_integrity.sql`, 
`V53__physical_attempt_transition_and_pricing_integrity.sql`, `V64__model_control_plane_completion.sql`, `V65__model_execution_binding_enforcement.sql`, `V66__physical_response_and_pricing_scheme.sql`, `V67__chat_execution_quota_and_evidence_hardening.sql`, `V72__organization_auth_epoch_physical_attempt_gate.sql`.

**Нельзя** редактировать исторические immutable snapshots ради исправления тарифа. Для новой поддерживаемой модели/цены нужен
**новый аудируемый каталог-версия с явной схемой расчёта**. Исправление чтения и проверки `pricing_scheme` должно работать с уже существующими 
immutable rows, включая legacy.

---

## 6. Что уже хорошо реализовано

1. **Защита перед I/O:** `AiExecutionService` обязан вызвать `AiModelExecutionPort.authorizePhysicalExecution`, иначе не должен начинаться план/попытка/провайдерный HTTP.
2. **Durable provenance:** отдельные `model_execution_plans` и `model_execution_attempts`, UUID операции и каждой физической попытки, модель/провайдер/версия конфигурации.
3. **Консервативные повторы:** если результат сомнителен, он не считается доказанным отказом. `AiProviderRetryExecutor` работает только внутри `AiExecutionAttemptScope`, а `AiExecutionAttemptRecorder` защищает переходы на уровне БД.
4. **Сквозной output cap:** `AiChatRequest.effectiveMaxOutputTokens` учитывается непосредственно в payload OpenAI/Anthropic и в контекстном бюджете.
5. **История безопасной формы:** поддерживаются только полные пары USER/ASSISTANT, `SYSTEM`/`DEVELOPER` отделены от истории.
6. **Контроль размеров:** лимиты текста и HTTP response, обработка слишком больших ответов, запрет redirect, HTTPS allowlist для API URLs.
7. **Управляемость окружения:** real providers обязательны в production, mock изолирован non-production condition, настройки timeout/secret/model валидируются fail-fast.
8. **Разделение финансовых состояний:** `UNPRICED` ≠ `FREE`; provider usage и pricing evidence хранятся отдельно от текстового ответа.
9. **Внешняя модель не подменяется:** проверяются requested/resolved provider models, и ошибки протокола не превращаются в правдоподобный успешный ответ.
10. **Версионированное ценообразование присутствует:** `VersionedCatalogPricingResolver` уже `@Primary`, привязан к плану, decision и immutable catalog snapshot; cache input semantics разделены для OpenAI/Anthropic.
11. **Контроль отключённой организации:** `organizationEpochCurrent` и V72 DB trigger ограждают новое событие `STARTED` от устаревшей авторизации.
12. **Чёткие REST ошибки:** тайм-аут, 429, external quota, reconciliation и generic provider failure представлены разными состояниями.

Оговорка: перечисленные свойства **обнаружены в коде**; end-to-end correctness при конкурентной нагрузке и реальных провайдерах требует отдельных интеграционных проверок.

---

## 7. Что сейчас не реализовано либо сознательно ограничено

- **Не multi-provider runtime.** `ProviderRegistry` существует, но `AiExecutionService` напрямую получает один `AiProvider` и проверяет его `executionTarget()`. Маршрут на другой активный адаптер не переключает. Наличие класса registry не означает, что диспетчеризация внедрена.
- **Нет cross-provider fallback.** Статусы `FallbackSafety` описаны, но реального перехода между провайдерами нет; same-target retry ограничен.
- **Нет подтверждённой поддержки tool calls, vision, audio и structured output** в данном text-only исполнении. Модель маршрутизации предусматривает capabilities, но физический data plane их не исполняет.
- **Нет гарантированного точного подсчёта provider tokens из символов.** `AiContextWindowService` оценивает их эвристически; `AiInputUnitEstimator` использует собственную единицу UTF-8+overhead; окончательные токены приходят только из provider usage.
- **Не реализован потоковый (streaming) ответ в показанных адаптерах.** Основная реализация — синхронный `RestClient` и единый `AiChatResponse`.
- **ThreadLocal execution scope — синхронная граница.** При миграции на async/reactive/virtual-thread switch без переноса контекста такой scope не будет автоматически работать. Сейчас это защитное ограничение, а не самостоятельный дефект.
- **Существуют совместимые/переходные API.** Старые конструкторы DTO и deprecated `execute` оставлены для source compatibility; использовать их как обход governed pipeline запрещено.
- **Значимые финансовые гарантии — межмодульные.** AI ledger сам по себе не закрывает все вопросы о сумме резервов, реальных списаниях и неопределённых платежах.

---

## 8. Аудит рисков и обнаруженных дефектов

Приоритеты: **P0** — может нарушить денежную/безопасностную гарантию и блокирует признание соответствующего функционала production-ready; **P1** — высокий риск, требующий отдельного PR и регрессионных проверок; **P2** — техническое ограничение или усиление; **P3** — улучшение качества. Каждый пункт помечен как **подтверждено кодом**, **требует проверки межмодульного поведения** или **ограничение по замыслу**.

### P0 — `pricing_scheme` не участвует в доказательстве цены (подтверждено кодом)

**Факты:**

1. Миграция `V66__physical_response_and_pricing_scheme.sql` вводит обязательное `model_catalog_entries.pricing_scheme`. Исторические `CONFIGURED` получают `LEGACY_FLAT_UNVERIFIED_V1`, `FREE` — `FLAT_TOKEN_V1`; комментарий миграции прямо требует отказа от автоматического settlement по legacy до новой проверенной версии каталога.
2. `ModelCatalogRepository.insert(entry)` сознательно сохраняет `CONFIGURED` по совместимому методу как `LEGACY_FLAT_UNVERIFIED_V1`.
3. `ModelCatalogRepository.SELECT_COLUMNS` **не содержит** `pricing_scheme`, а `ModelCatalogEntry` не несёт это свойство.
4. `VersionedCatalogPricingResolver.resolve` проверяет `pricingStatus`, `pricingComplete`, rates/version, но **не проверяет** `ModelPricingScheme.supportedForAutomaticSettlement()`; код может вернуть `PRICED` для snapshot с недоказанным `LEGACY_FLAT_UNVERIFIED_V1`.
5. `ModelPricingScheme` существует, но не подключён к реальному read/settlement контракту.

**Последствие:** часть цен может получить формально доказанный статус `PRICED` вопреки инварианту V66. Это **не автоматически доказанный перерасход**, 
но достоверный разрыв в финансовой цепочке доверия; при строгих квотах может стать критичным.

**Правильное исправление:** добавить `pricing_scheme` в `SELECT_COLUMNS`, в `ModelCatalogEntry` и маппинг `ModelCatalogRepository`, включить 
его в проверку `VersionedCatalogPricingResolver` и при необходимости в snapshot fingerprint/receipt. При `LEGACY_FLAT_UNVERIFIED_V1` и 
`UNSUPPORTED_COMPLEX_V1` возвращать `UNPRICED`/недоказанное состояние; `FLAT_TOKEN_V1` разрешать лишь при корректных тарифах и зафиксированных provider-specific правилах. Новые `CONFIGURED` версии должны создаваться через явный audited scheme, а не через compatibility default. Нельзя переписывать старые версионные записи.

**Тесты:** создать две разные версии одной модели с одинаковыми ставками, но разными `pricing_scheme`; доказать, что legacy не получает `PRICED`, 
поддерживаемая получает, а неверная схема/недостающая ставка fail-closed. Проверить DB migration и репозитории, не только Mockito unit-тест resolver.

**Файлы:** `model/repository/ModelCatalogRepository.java`, `model/domain/ModelCatalogEntry.java`, `model/pricing/VersionedCatalogPricingResolver.java`, 
`ai/pricing/ModelPricingScheme.java`, Flyway V66, тесты обоих модулей.

### P1 — сквозное доказательство денежных hard quotas (требует межмодульной проверки)

`AiExecutionService.requiresSinglePhysicalAttempt` — сильное ограничение для hard enforcement. Но для строгого денежного лимита ещё необходимо доказать:

- запись резерва **по конкретному route decision**, а не по статическому тарифу;
- использование правильного envelope входа и физического `max_output_tokens`/`max_tokens`;
- расчёт цены по поддерживаемой immutable pricing scheme;
- учёт **всех** физически начатых и потенциально платных попыток, включая отсутствие provider usage, `AMBIGUOUS`, protocol mismatch и 
- пересохранение ответа;
- атомарное недопущение одновременных запросов двух worker/организационных клиентов поверх одного бюджетного лимита;
- корректное поведение при несовпадении model route и runtime price или ошибке settlement.

До end-to-end проверки `chat/quota` + `model/pricing` + `ai/execution` нельзя обещать, что заявленная денежная верхняя граница математически доказана. 
Особенно опасно превращать `null`/`UNPRICED` в ноль.

**Тесты:** concurrent reservations на одном месячном бюджете, unknown cost, ошибка записи `SUCCEEDED`, последующая reconciliation, 
success с model mismatch, один/несколько physical attempts, отказ организации между authorizing и `STARTED`. Нужен PostgreSQL Testcontainers, 
не только in-memory mocks.

### P1 — обработка неизвестно оплаченных неуспешных вызовов (требует проверки)

`ModelExecutionAttemptEntity` записывает реальные usage/price evidence по `succeeded(response)`. При `failed(exception)` и `ambiguous(exception)` 
сведения о стоимости обычно неизвестны. Ошибки протокола после получения тела ответа могут означать, что провайдер **уже обработал** запрос, 
хотя валидный `AiChatResponse` не сформирован.

**Риск:** отчёт или квота, которые суммируют только `SUCCEEDED.cost_usd`, могут недоучесть реальный расход. Это не доказанный дефект одного 
`ai`-класса: надо проверить SQL-агрегации и правила `usageUnverifiable/costUnverifiable` в остальных модулях.

**Мера:** для `STARTED` без полной конечной billable evidence хранить признак **неопределённого расхода**; запретить доказывать соблюдение 
hard monetary quota по сумме известных `cost_usd`, пока неопределённости не разрешены. Вести reconciliation по provider billing без автоматического 
повторного I/O.

### P1 — классификация всех HTTP 4xx как `KNOWN_REJECTED` (условный контракт, нуждается в доказательстве)

`ProviderFailureCertainty.classify` для любых HTTP 400–499, кроме 408, при `outcomeAmbiguous=false` возвращает `KNOWN_REJECTED`; 
для connect-failure и 5xx возвращает `AMBIGUOUS`. При этом `AiProviderRateLimitedException` допускает retry для 429, когда 
прежняя попытка признана `KNOWN_REJECTED`.

**Риск:** финансовая семантика отдельных provider-specific 4xx может зависеть от API и endpoint; одного HTTP status не всегда 
достаточно для доказательства «не было биллинга». Код защищён от многих опасных случаев, но жёсткую классификацию следует подтвердить 
контрактными тестами/документацией для OpenAI Responses и Anthropic Messages.

**Мера:** таблица разрешённых terminal rejection codes с отдельно проверенными evidence; всё непроверенное — `AMBIGUOUS`, 
без автоматического повтора. Не менять поведение необдуманно, чтобы не сломать корректную обработку 429; зафиксировать expected outcomes тестами.

### P2 — `ProviderRegistry` не подключён к основному dispatch (подтверждено кодом)

`ProviderRegistry.require(target)` умеет выбирать точный физический адаптер, но `AiExecutionService` внедряет `AiProvider provider` 
и сравнивает один `provider.executionTarget()` с маршрутом. Это корректно для deliberate single-provider конфигурации, но **не реализует** 
заявляемый по имени registry-driven multi-provider routing.

**Мера:** явно оставить single-provider контракт в README/UI или отдельным PR ввести injection registry и предварительную целевую resolution. 
Мультипровайдерность нельзя включать «просто сняв» проверку target: нужны контроль конфигурации, авторизация и перенос план/attempt provenance.

### P2 — `retry.total-timeout` не является жёстким deadline физического I/O (подтверждено кодом)

В `AiProviderRetryExecutor` дедлайн через `System.nanoTime()` ограничивает возможность начать **следующую** попытку (`fitsDeadline`). 
Он не гарантирует прерывание уже запущенного HTTP вызова точно в `total-timeout`. Ограничение выполнения в основном делегировано 
HTTP connect/read тайм-аутам, которые имеют собственную семантику.

**Мера:** документировать два разных бюджета (attempt transport vs aggregate retry scheduling); при необходимости внедрять 
deadline-aware client/cancellation с корректной классификацией `AMBIGUOUS` — отмена клиента не доказывает отмену провайдера. 
Поддержать тестами длительных/зависших response body и backoff.

### P2 — двухуровневые эвристики размера контекста (подтверждено кодом, ограничение)

`AiContextWindowService` оценивает `ceil(chars / charsPerEstimatedToken) + overhead`, а `AiInputUnitEstimator` рассчитывает 
`UTF8 bytes + framing`. Это разные единицы. `maxInputTokens` — лимит конкретного провайдера; превышение реального токенизатора 
может проявиться только при provider I/O. Governed preflight дополнительно сверяет свои единицы.

**Мера:** согласовать и документировать политику верхней границы по provider-specific tokenizer и tokenizer drift; отдельно 
тестировать кириллицу, эмодзи, редкие символы, большие system instructions, RAG-добавления и exact model caps. Не называть 
UTF-8 единицы точными AI-токенами.

### P2 — `PricingResolver` допускает identity-less overload (подтверждено кодом, управляемый риск)

Интерфейс содержит default `resolve(operationId, ...)`, которая вызывает старую перегрузку без `operationId`; реализация `VersionedCatalogPricingResolver` переопределяет её, а также `@Primary` помечает себя основным bean. Однако новый resolver, реализующий только базовый метод, мог бы случайно потерять операционную привязку.

**Мера:** долгосрочно разделить legacy/non-governed API и обязательный operation-bound контракт, или проверять, что основной 
production bean реализует строгий overload. Сохранить совместимость до миграции существующих тестов и адаптеров.

### P2 — протокольные и финансовые сценарии требуют отрицательных тестов (требует проверки)

Нужны adversarial tests для некорректных `usage` counters (отрицательные, дробные, переполненные, missing, конфликтующие 
с cache counters), недопустимых resolved models, oversized chunked/unknown-length bodies, несоответствия `response.status`
и текста, 429/502/503/529, успешного provider response при падении DB recorder, 4xx с нестандартным provider error payload. 
Часть тестов уже существует, но **полнота матрицы** без прогона всей suite не доказана.

### P3 — устаревшие совместимые методы и документационная неоднозначность

`ModelPricingService` и `StaticConfigurationPricingResolver`, legacy DTO constructor, deprecated overload retry и 
`ModelPricingScheme` с `@SuppressWarnings("unused")` могут ошибочно восприниматься как один равноправный production-путь. 
Явно пометить, какие API нельзя использовать для governed routes, затем удалять только после подтверждения call sites и тестов.

---

## 9. Минимальные инварианты для code review

Каждый новый PR в `ai` следует проверять по списку:

- [ ] Не появляется новый прямой вызов provider HTTP за пределами `AiExecutionAttemptScope` и `AiExecutionService`.
- [ ] `providerOperationId`, `chatTurnId`, `decisionId` совпадают в запросе, плане, route binding и attempt ledger.
- [ ] Параметры `provider/config/version/deployment/model` не меняются между решением и реальным вызовом.
- [ ] Каждая попытка пишет `STARTED` **до I/O**, затем строго один терминальный исход либо остаётся явно неразрешённой.
- [ ] Неопределённый исход никогда не становится «точно не выполнено» без доказательств.
- [ ] Hard budget допускает физический вызов только с доказанным reserve и разрешённым количеством попыток.
- [ ] `max_output_tokens`/`max_tokens` физического payload не превышает route cap.
- [ ] `withHistory`/`withInstructions` не изменяют identity, reserved input и output cap.
- [ ] `pricing_scheme` извлекается из immutable каталога и допускает автоматический settlement **только** для поддерживаемых алгоритмов.
- [ ] Cached/cache-write counters учитываются с правильной семантикой провайдера и без двойного счёта.
- [ ] `UNPRICED`, `CALCULATION_FAILED`, `MISSING`, `PARTIAL`, `AMBIGUOUS` сохраняют свою семантику в БД/квотах/отчётах.
- [ ] Нет логирования API keys, содержимого секретных инструкций, prompt body или полного ответа по умолчанию.
- [ ] Любой новый API endpoint не ослабляет HTTPS allowlist, redirect policy, body-size и тайм-ауты.
- [ ] Новые Spring beans не создают двусмысленный `PricingResolver`/`AiProvider` injection.
- [ ] Добавлены regressions на новый класс ошибок, а не только happy path.

---

## 10. Тестирование и проверка перед PR

### 10.1. Что уже есть в репозитории

Проверены имена существующих тестов для AI: `AiChatResponseTest`, `AiMessageAndRequestTest`, `AiContextWindowServiceTest`, 
`AiProviderRetryExecutorTest`, `AiProviderSupportTest`, `AiRestClientFactoryTest`, `ModelPricingPropertiesTest`, `ModelPricingServiceTest`, `PricingResultTest`, `OpenAiPropertiesTest`, `AnthropicPropertiesTest`, `MockAiProviderTest`, `AiExceptionHandlerTest`, а также тесты в `ai/config`, `ai/dto`, `ai/execution`, `ai/input`, `ai/pricing`, `ai/provider`, `ai/web`.

В `ai/execution` дополнительно существуют `AiExecutionAttemptOrganizationGateTest`, `AiExecutionAttemptRecorderTest`, `AiExecutionAttemptScopeTest`, 
`AiExecutionPlanServiceTest`, `AiExecutionServiceTest`, `ExecutionEvidenceContractTest`, `ModelExecutionAttemptEntityTest`, `ProviderFailureCertaintyTest`. В `model/pricing` — `VersionedCatalogPricingResolverTest`. Это подтверждает **наличие** тестов, но не означает, что они были запущены при составлении документа.

### 10.2. Рекомендуемые наборы

| Область | Что проверять |
|---|---|
| DTO и history | Null/malformed Unicode, историю нечётной длины, role violation, суммарный лимит, сохранение caps в `with*`. |
| Context | Усечение по парам, длинное первое сообщение, Unicode/emoji, обязательные инструкции, RAG preparation. |
| Provider adapters | Точный physical payload и output cap, разброс provider statuses, missing/invalid model, refusal/incomplete/tool-use. |
| HTTP | HTTPS allowlist, redirect disabled, chunked oversized success/4xx, 429 `Retry-After`, connect/read timeout. |
| Ledger | Параллельные STARTED, orphan events, duplicate UUID, fail-before-I/O, previous-FAILED safety, mutable organization epoch. |
| Retry | 429 safe, 503/timeout ambiguous, second attempt after known rejection, no retry after success/ambiguous, interruption. |
| Pricing | Все `pricing_scheme`, cache pricing OpenAI/Anthropic, unknown dimensions, price version/resolved model mismatch, overflow. |
| Квоты | Одна операция vs все физические попытки, uncertain billable attempts, конкурентные резервы, rollback/reconciliation. |
| REST | HTTP 400/409/429/502/503/504, cache-control no-store, `Retry-After`, безопасные логи. |

### 10.3. Локальная проверка

На чистой ветке, из корня проекта:

```bash
cd /Users/amid/Workspace/Projects/Products/SafeAI-Desk

git status -sb

cd backend
./mvnw clean verify
```

Для адресного набора тестов через Maven Surefire (названия проверены по дереву репозитория):

```bash
./mvnw -Dtest=AiExecutionServiceTest,AiExecutionAttemptScopeTest,AiProviderRetryExecutorTest,VersionedCatalogPricingResolverTest test
```

**В этом документе тесты не запускались**: результаты и число пройденных тестов нельзя считать подтверждёнными. Для доказательства 
ограничений денежных квот нужны интеграционные тесты против **PostgreSQL с Flyway миграциями**, желательно Testcontainers, и моделирование 
ошибок внешнего провайдера. `git diff --check` и `./mvnw verify` сами по себе не подтверждают корректность внешнего биллинга.

### 10.4. Рекомендуемая последовательность исправлений

1. **PR AI-P0 Pricing Scheme:** обеспечить чтение `pricing_scheme` → `ModelCatalogEntry` → `VersionedCatalogPricingResolver` → тесты V66 legacy/verified.
2. **PR AI-P1 Financial Evidence:** проверить совместно `ai/execution`, `model` и `chat/quota` учёт всех физических попыток и cost-unverifiable при AMBIGUOUS/failed protocol; связать с hard-limit.
3. **PR AI-P1 Retry Evidence:** зафиксировать provider-specific 4xx/429 evidence policy и негативные тесты безопасности повторов.
4. **PR AI-P2 Timeouts:** формализовать aggregate deadline и тестировать зависшие HTTP bodies; не проводить слепую переотправку при cancellation.
5. **PR AI-P2 Provider Dispatch:** либо явно закрепить single-provider, либо полноценно довести registry dispatch и тесты exact target.

Не смешивать все изменения в одном PR: денежный контур и durable evidence должны проверяться изолированно и обратимо.

---

## 11. Безопасность, наблюдаемость и эксплуатационные действия

**Что логировать:** operationId, attemptId, chatTurnId (если это допустимо по политике проекта), provider/type/model, результат validation, 
внешний status, outcome certainty, latency, status usage/pricing, correlation/requestId, отказ authorization epoch. **Чего не логировать:** ключи API, credentials, полные prompts/history, RAG-документы, системные инструкции, содержимое ответа, пользовательские персональные данные. Предусмотреть retention и ограничение доступа к таблице попыток.

**Мониторинг:** прирост `AMBIGUOUS` и orphan `STARTED`, частота 429/5xx, `UNPRICED`, `CALCULATION_FAILED`, доля responses без token usage, 
mismatch resolved models, рост ошибок reconciliation, превышение body size, потери связи с провайдером, ошибки БД до STARTED и при терминальном 
обновлении. Для hard quotas полезна отдельная метрика количества операций с неизвестным billable cost.

**При инциденте:**

1. По `providerOperationId`/`chatTurnId` найти `model_execution_plans` и связанные `model_execution_attempts`.
2. Сверить `outcome`, `outcome_certainty`, наличие `started_at`, `finished_at`, `provider_request_id`, `usage_status`, `pricing_status`.
3. Если `STARTED`/`AMBIGUOUS` или terminal persistence failed — **не делать новый provider call по той же логической операции**, пока состояние не согласовано.
4. Проверить биллинг провайдера и внутренние записи расхода, затем выполнить предусмотренную ручную/автоматическую reconciliation процедуру с аудитом, не редактируя immutable evidence напрямую.
5. Если `pricing_scheme` legacy или missing, считать денежное доказательство неполным; не объявлять стоимость бесплатной.

**Осторожно с диагностикой:** вывод `toString()` DTO может содержать `AiChatRequest.userMessage`, history и инструкции. Для structured logging использовать явный whitelist полей, а не сериализацию всего `record`.

---

## 12. Критерии готовности модуля

Модуль можно считать *функционально реализованным* для одно-провайдерного синхронного текстового вызова с durable attempt ledger. Однако **для признания полноценного production-ready AI billing/hard quota нужно закрыть P0**, подтвердить корректное поведение по неизвестным расходам и выполнить интеграционные испытания с Flyway/PostgreSQL и реальными/контрактно смоделированными provider responses.

**Текущая инженерная оценка:** архитектурный фундамент серьёзный; важнейший недоведённый инвариант — **проверка `pricing_scheme` V66 в реальном automatic settlement**. Это конкретная проблема, а не вопрос косметического оформления. После её устранения следующий по важности участок — сквозная цепочка **route estimate → quota reservation → physical attempt → settlement/reconciliation**. Ни `ProviderRegistry`, ни статический `ModelPricingService` сами по себе не дают гарантии корректного списания.

### Источники и воспроизводимость

- Присланные пользователем исходники `ru.safeai.gateway.ai` (вставка в чат).
- Репозиторий [`Amid-1/Safeai-desk`](https://github.com/Amid-1/Safeai-desk/tree/ec2596ed079423d7103fa3218b51a0ce6cd46df0/backend/src/main/java/ru/safeai/gateway/ai), commit `ec2596ed` (`main` на момент проверки).
- [`VersionedCatalogPricingResolver`](https://github.com/Amid-1/Safeai-desk/blob/ec2596ed079423d7103fa3218b51a0ce6cd46df0/backend/src/main/java/ru/safeai/gateway/model/pricing/VersionedCatalogPricingResolver.java).
- [`ModelCatalogRepository`](https://github.com/Amid-1/Safeai-desk/blob/ec2596ed079423d7103fa3218b51a0ce6cd46df0/backend/src/main/java/ru/safeai/gateway/model/repository/ModelCatalogRepository.java) и [`ModelCatalogEntry`](https://github.com/Amid-1/Safeai-desk/blob/ec2596ed079423d7103fa3218b51a0ce6cd46df0/backend/src/main/java/ru/safeai/gateway/model/domain/ModelCatalogEntry.java).
- [`V66__physical_response_and_pricing_scheme.sql`](https://github.com/Amid-1/Safeai-desk/blob/ec2596ed079423d7103fa3218b51a0ce6cd46df0/backend/src/main/resources/db/migration/V66__physical_response_and_pricing_scheme.sql), [`application.yml`](https://github.com/Amid-1/Safeai-desk/blob/ec2596ed079423d7103fa3218b51a0ce6cd46df0/backend/src/main/resources/application.yml) и `application-local.yml`/`application-prod.yml`.

> **Ограничение аудита:** выполнена статическая сверка исходников и миграций по GitHub. Не проводились локальная компиляция всего модуля, запуск тестовой suite, проверка реального PostgreSQL и live billing API. Вставка пользователя содержит артефакты форматирования и дублированные фрагменты `AiProviderRetryExecutor`/`AiChatResponse`; окончательное описание составлено по нормальным Java-файлам репозитория, без предположения, что дубли находятся в исходниках.
