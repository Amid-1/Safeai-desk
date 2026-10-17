# SafeAI Desk — модуль `chat`

**Статус:** архитектурный и статический code review на 16.10.2026.  
**Расположение:** `backend/src/main/java/ru/safeai/gateway/chat/`  
**Стек:** Java 21, Spring Boot, Spring MVC/Security/Data JPA/JDBC/Scheduling, PostgreSQL, Redis, Micrometer, Flyway.  
**Область рассмотрения:** все Java-классы пакета `ru.safeai.gateway.chat`, предоставленные в запросе; выборочно сверены с 
веткой `main` GitHub и миграциями V58/V60/V67/V72. Наличие одноимённых тестов проверено по дереву репозитория. **Компиляция, 
полный `mvn verify`, реальные вызовы провайдера и нагрузочные тесты в рамках этого ревью не выполнялись.**

> **Задача документа:** описать, что реально реализовано, где находятся границы корректности, какие решения сильные, какие 
> недостатки требуют исправлений, и что необходимо проверить до production. Документ не изменяет исходный код и не утверждает, что все упомянутые риски воспроизводятся на production: наблюдения по коду отделены от гипотез, требующих интеграционной проверки.

---

## 1. Назначение и функциональность

`chat` обеспечивает работу персональных чатов внутри организации (tenant): создание и переименование, архивирование, 
просмотр списка и сообщений, отправку запросов к AI, ограничение расходов и нагрузки, RAG (Retrieval-Augmented Generation — генерация 
с использованием знаний), восстановление оборвавшихся запросов, повторное получение результата без нового обращения к провайдеру и аудит.

Текущие возможности:

1. **Изоляция данных:** сессии принадлежат конкретным `user_id` и `organization_id`; перед доступом проверяется владение чатом. Платформенный `SUPER_ADMIN` намеренно не должен получать доступ к tenant-chat через прикладной фасад.
2. **Идемпотентность:** клиент задаёт `clientRequestId`, который обязан повторяться при сетевом ретрае того же сообщения. Тот же ключ с другим нормализованным содержимым/knowledge-scope отвергается.
3. **Управление жизненным циклом:** отдельная запись `chat_turns` содержит состояние обработки, identity провайдера, токен владения, срок аренды и финальные сведения.
4. **Защита от одновременной обработки:** Redis-lock, PostgreSQL advisory lock, ограничения БД и условные переходы состояния дополняют друг друга.
5. **Управляемый выбор модели:** решение маршрутизации (`model_route_decision_id`) фиксируется до физического вызова, а реальный запрос сверяется с зарезервированным envelope (верхней границей входа).
6. **Обогащение знаниями:** `KnowledgeRagService` подготавливает контекст до обращения к провайдеру и формирует `AnswerPassport` после ответа.
7. **Учёт:** резервирование запросов/токенов/денег на уровне организации и пользователя; сохранение фактических `usage` и `pricing`-сведений.
8. **Восстановление:** периодический поиск просроченных `PROCESSING` и разделение безопасно не начатого вызова (`FAILED`) и потенциально выполненного (`AMBIGUOUS`).
9. **Наблюдаемость:** метрики жизненного цикла и восстановления с низкой кардинальностью, аудит действий без записи полного текста сообщений в audit payload.
10. **Названия чатов:** ручное переименование и детерминированная политика автоматического заголовка без дополнительного LLM-вызова; **автоматическое применение при первом сообщении не подтверждено показанным call path** (см. F-03).

## 2. Слои и зависимости

```text
HTTP /api/chats
  ├─ ChatController ────────── ChatService (CRUD, ownership, read)
  │                            └─ ChatTurnExecutionCoordinator (вне @Transactional)
  │                                 ├─ ChatLockService (Redis)
  │                                 ├─ ChatTurnReservationService (@Transactional)
  │                                 │    ├─ ChatTurnMutexRepository (pg_advisory_xact_lock)
  │                                 │    ├─ ChatHistoryRepository / AiHistoryBuilder
  │                                 │    ├─ ModelRouting* + sealed request
  │                                 │    ├─ ChatQuotaService / repository
  │                                 │    └─ audit + rate limit
  │                                 ├─ ChatTurnLeaseService (lease watchdog)
  │                                 ├─ KnowledgeRagService (prepare / complete)
  │                                 ├─ AiExecutionService (physical attempt / provider)
  │                                 └─ ChatTurnFinalizationService (@Transactional)
  │                                      ├─ ChatTurnRepository (fenced UPDATE)
  │                                      ├─ ChatMessageRepository
  │                                      ├─ AnswerPassportService
  │                                      ├─ quota + audit + metrics
  │                                      └─ ChatTurnReadSupport
  ├─ ChatTitleController ──── ChatTitleService (manual rename)
  └─ ChatTurnRecoveryScheduler ─ ChatTurnRecoveryService (@Transactional)
                                    └─ ChatTurnRecoveryCoordinator
                                         ├─ turn transition
                                         ├─ quota terminalization
                                         └─ durable audit / metrics
```

**Разграничение транзакций:** транзакция №1 резервирует и фиксирует `PROCESSING`; затем внешний провайдер вызывается **без 
длительной транзакции БД**; транзакция №2 фиксирует `SUCCEEDED`/`FAILED`/`AMBIGUOUS`. Это сильное архитектурное решение. 
Redis-lock — не единственная гарантия корректности: решающее право финализации проверяется условным `UPDATE` в PostgreSQL.

## 3. API и контракты

Все маршруты начинаются с `/api/chats`. Для обоих контроллеров задано `@PreAuthorize("hasAnyRole('ADMIN', 'USER')")`.

| Метод | Маршрут | Реализация | Результат |
|---|---|---|---|
| `GET` | `/capabilities` | `ChatController.capabilities()` | Эффективные лимиты сервера для frontend. |
| `POST` | `/` | `ChatService.create()` | Создаёт чат; `201 Created`. |
| `GET` | `/` | `ChatService.findAll()` | `Slice` чатов без архивных, постранично. |
| `GET` | `/{chatId}` | `ChatService.findById()` | Чат + последние N сообщений, выведенных хронологически. |
| `DELETE` | `/{chatId}` | `ChatService.archive()` | Логическое архивирование, `204 No Content`; физического удаления нет. |
| `POST` | `/{chatId}/messages` | `ChatService.sendMessage()` | Обработка AI-хода либо replay/ошибка состояния. |
| `GET` | `/{chatId}/messages` | `ChatService.findMessages()` | Постраничный список пользовательских/AI-сообщений. |
| `GET` | `/{chatId}/turns/by-client-request/{clientRequestId}` | `ChatService.findTurnStatus()` | Статус по idempotency key. |
| `GET` | `/{chatId}/turns/{clientRequestId}` | тот же метод | Legacy alias для rolling deployment. |
| `PATCH` | `/{chatId}` | `ChatTitleService.rename()` | Ручное переименование с аудитом. |

### Важные контракты

- `clientRequestId` — обязательный UUID, генерируется до первой отправки и **не должен меняться при повторе того же запроса**.
- `content` нормализуется (`CRLF/CR` → `LF`), проверяется по UTF-16 code units, валидности суррогатных пар и управляющим символам. Исторические данные и knowledge-mode входят в маршрутизацию, а content hash связывает пользовательский текст с knowledge-scope.
- Список возвращается через `Slice` (`hasNext`, `hasPrevious`), а **не** через `Page` с `totalElements`/`totalPages`.
- `/capabilities` отдаёт лимиты выполнения, но **не раскрывает** тарифный/пользовательский остаток квот.
- `SendMessageResponse` представляет **успешно завершённый** ход: `assistantMessage` и `completedAt` обязательны. `PROCESSING`, `FAILED`, `AMBIGUOUS` представляются статусным endpoint и/или типизированной HTTP-ошибкой.
- Ошибка `409 CHAT_TURN_IN_PROGRESS` может содержать `Retry-After`; `409 AI_OUTCOME_AMBIGUOUS` **не разрешает** слепой повтор физического запроса.
- Сортировка списка чатов ограничена `updatedAt`, `createdAt`, `title`, `id`; добавлен `id` как вторичный ключ стабильной сортировки. Сообщения сортируются по `createdAt DESC, id DESC`.

## 4. Жизненный цикл одного сообщения

1. `ChatController.sendMessage()` вызывает `ChatService.sendMessage()`; подтверждается принадлежность неархивного чата пользователю и организации.
2. `ChatTurnExecutionCoordinator.execute()` пытается взять Redis-lock с уникальным `ownerToken` и TTL.
3. `ChatTurnReservationService.reserveOrReplay()` в короткой транзакции нормализует текст, вычисляет hash, берёт `pg_advisory_xact_lock(chatId)`, ищет предшествующий запрос с тем же ключом и активный ход.
4. Если ход уже `SUCCEEDED`, возвращается replay без нового вызова AI; `FAILED`/`AMBIGUOUS` возвращают исходное финальное состояние через исключение; `PROCESSING` возвращает ожидание либо запускает recovery по истечении lease.
5. Для нового хода извлекается история только из успешных полных пар USER/ASSISTANT, выбирается физическая модель, сохраняются USER-message, `PROCESSING`-turn, квота, audit intent и immutable route/request evidence. Затем проверяется Redis rate limit. Транзакция коммитится **до** вызова провайдера.
6. Запускается watchdog lease, RAG подготавливает запрос; `ModelRouteExecutionGuard` контролирует фактический envelope. При потере владения до физического STARTED попытка должна завершаться как ошибка без выдуманного вызова провайдера.
7. `AiExecutionService` запускает физическую попытку. Миграции V67/V72 обеспечивают DB-проверки live turn и auth epoch непосредственно перед допуском STARTED.
8. По возвращении ответа выполняется post-processing RAG, повторная проверка lock/lease и транзакционная финализация: `chat_turns` → `SUCCEEDED`, assistant-message, `AnswerPassport`, фактическая квота, аудит, обновление времени чата.
9. После коммита проверяется активность пользователя/организации; ответ не возвращается отозванному пользователю, однако сохранённое evidence остаётся для учёта.
10. В `finally` закрывается DB-lease watchdog и снимается Redis-lock с проверкой `ownerToken`.

### Состояния `ChatTurnState`

| Состояние | Значение | Повтор по тому же ключу |
|---|---|---|
| `NEW` | Подготовительное/историческое состояние | Не начинает второй физический вызов. |
| `PROCESSING` | Обрабатывается; хранит `processingToken`, `leaseUntil` | `409` с ожиданием либо recovery, если просрочен. |
| `SUCCEEDED` | Зафиксированы ответ и usage | **Replay** существующего ответа. |
| `FAILED` | Достоверно ошибочный / не начатый вызов | Возврат failure; тот же ключ не выполняется повторно. |
| `AMBIGUOUS` | Неизвестно, завершил ли провайдер операцию / списал ли средства | Возврат неоднозначности; слепой retry запрещён. |

`ChatQuotaReservationState`: `RESERVED` → `SETTLED` / `UNPRICED` / `AMBIGUOUS` / `RELEASED`. Привязка состояния квоты к состоянию хода должна быть атомарной внутри транзакции.

## 5. Каталог классов и методов

Ниже перечислены **все 75 Java-типов**, входящие в представленный модуль, с их ролью и основными методами. Стандартные accessor-методы 
Java `record`, Lombok getters/setters, `equals/hashCode/toString` не расписываются по одному, если в них нет прикладной логики.

### 5.1. `config/` — параметры среды (5 классов)

| Класс | Основные методы и ответственность |
|---|---|
| `ChatConfiguration` | Включает scheduling и регистрирует четыре `@ConfigurationProperties`. Сам прикладной логики не содержит. |
| `ChatProperties` | Compact constructor валидирует размеры выдачи, лимит текста, `processingLease`, количество потоков и watchdog. `leaseRenewalInterval()` возвращает `max(1s, processingLease/3)`. |
| `ChatLockProperties` | Валидирует Redis key-prefix, TTL 30 s–30 min, потоки, активные locks, timeout остановки; `renewalInterval()` задаёт треть TTL, минимум 1 s; `normalizePrefix()` удаляет хвостовые двоеточия. |
| `ChatQuotaProperties` | Настройки статического резервирования и периода. `isEnabled()` — флаг квот; `effectivePeriodZone()` — нормализованная временная зона. `validateReservationCost()` проверяет формат PostgreSQL `numeric(30,12)`. |
| `ChatRecoveryProperties` | `enabled`, `batchSize`, `maxBatchesPerRun`, cron. `isEnabled()`, `boundedBatchParameter()`, `normalizeAndValidateCron()`; cron проверяется через Spring `CronExpression.parse()`. |

**Базовые значения:** `maxMessageChars=16000`, максимум 100000; `historyTurnLimit=50`, максимум 200; `detailsMessageLimit=50`, 
максимум 200; размер page не более 100; `processingLease=3m`, `chat.lock.ttl=4m`; recovery каждую минуту, до 20 батчей по 100. Это лимиты **по умолчанию** из constructors — реально развернутые значения могут отличаться.

### 5.2. `controller/` — HTTP-вход (2 класса)

| Класс | Методы |
|---|---|
| `ChatController` | `capabilities`, `create`, `findAll`, `findById`, `archive`, `sendMessage`, `findMessages`, `findTurnStatus`. Получает `SafeAiUserPrincipal` через `@AuthenticationPrincipal`. Делегирует бизнес-логику сервису. |
| `ChatTitleController` | `rename` — `PATCH /api/chats/{chatId}`; передаёт запрос в `ChatTitleService`. |

### 5.3. `dto/` — контракт запросов и ответов (13 типов)

| Класс | Поля / методы / проверки |
|---|---|
| `ChatCapabilitiesResponse` | Четыре действующих ограничения; compact constructor требует положительные значения. |
| `ChatDetailsResponse` | Метаданные сессии + список сообщений; `List.copyOf()` защищает коллекцию от внешнего изменения. |
| `ChatErrorResponse` | Чат-специфичная ошибка: время, HTTP status, code, message, три UUID, `retryAfterSeconds`. |
| `ChatPageRequest` | `page/size/sortBy/direction`; `toPageable(configuredMaxSize)` валидирует whitelist сортировки, ограничивает размер и добавляет детерминированный tie-breaker `id`. |
| `ChatPageResponse<T>` | Метаданные `Slice`; static `from(Slice<T>)`; неизменяемая копия `content`, проверка page/size. |
| `ChatResponse` | `id`, `title`, `createdAt`, `updatedAt`. |
| `ChatTurnStatusResponse` | Подробное состояние turn: provider/model/ошибки/lease/времена и связанные сообщения. |
| `CreateChatRequest` | Необязательное название; domain validation выполняется в `ChatTitlePolicy`. |
| `MessagePageRequest` | `page/size`; `toPageable(configuredMaxSize)` — сортировка по времени и UUID по убыванию. |
| `MessageResponse` | Контент и технические метаданные provider, model, tokens, usage, cost и pricing. |
| `SendMessageRequest` | `content`, `clientRequestId`, необязательные `knowledgeBaseId/knowledgeMode`. Compact constructor подставляет GENERAL/KNOWLEDGE_ASSISTED, проверяет совместимость режима и базы. Двухаргументный конструктор — совместимость с вызовами без RAG. |
| `SendMessageResponse` | Успешный результат хода: UUID, `replay`, сообщения, даты, `AnswerPassport`; обязательные значения проверяются. Конструктор без passport — backward compatibility. |
| `UpdateChatTitleRequest` | `@NotBlank title`; максимальная длина и Unicode проверяются доменной политикой, не `@Size` Java UTF-16. |

### 5.4. `entity/` — долговременная модель (7 типов)

| Класс | Основные методы / инварианты |
|---|---|
| `ChatMessageEntity` | Фабрики `user()`, `completedAssistant()`, `system()`, внутренние `base()`, `validateNonAiMessage()`. `validateInvariant()` вызывается `@PrePersist/@PreUpdate` и из фабрик; `validateUser/Assistant/System`, `validateUsage`, `validatePricing`, `requireNoAiMetadata`, `normalizeOptional`, `normalizeCurrency` контролируют тип сообщения и provider evidence. |
| `ChatMessageRole` | `USER`, `ASSISTANT`, `SYSTEM`. |
| `ChatMessageStatus` | `PENDING`, `COMPLETED`, `FAILED`; фабрики USER и SYSTEM создают `COMPLETED`. |
| `ChatQuotaReservationState` | `RESERVED`, `SETTLED`, `UNPRICED`, `AMBIGUOUS`, `RELEASED`. |
| `ChatSessionEntity` | `create(user,title,now)` с `titleSource`; `touch(now)`; `assignAutomaticTitle(title,now)` только при DEFAULT; `renameByUser(title,now)` переводит в USER; `archive(userId,now)` — soft delete; `validateInvariant()` защищает provenance, даты, tenant-отношение. Обычный setter для `title/titleSource` закрыт. |
| `ChatTurnEntity` | Перегрузки фабрики `processing(...)`: legacy и V45+ с `modelRouteDecisionId`. `validateInvariant()` проверяет UUID, hash SHA-256, время, knowledge-scope, поля и допустимые комбинации состояний; `requireNonTerminal`, `requireTerminal`, `normalize`, `validateKnowledgeScope`. При резервировании фиксирует `authorizedOrganizationAuthVersion`. |
| `ChatTurnState` | `NEW`, `PROCESSING`, `SUCCEEDED`, `FAILED`, `AMBIGUOUS`. |

### 5.5. `exception/` — контракт с клиентом (13 типов)

| Класс | HTTP / значение |
|---|---|
| `ChatApiException` | База доменных ошибок: HTTP status, публичный code, UUID идентификаторы, optional `retryAfter`. |
| `AiOutcomeAmbiguousException` | `409 AI_OUTCOME_AMBIGUOUS`: результат провайдера неизвестен; запрет blind retry. |
| `ChatAccessRevokedException` | `403 CHAT_ACCESS_REVOKED_DURING_PROCESSING`: доступ отозван после начала обработки. |
| `ChatBusyException` | `409 CHAT_BUSY` через общий `ConflictException`: другой запрос уже использует чат. |
| `ChatLeaseUnavailableException` | `503 CHAT_LEASE_UNAVAILABLE`: не удалось обслужить DB watchdog. |
| `ChatLockUnavailableException` | Ошибка инфраструктуры Redis-lock, обработчик формирует `503`. |
| `ChatQuotaExceededException` | `429 AI_QUOTA_EXCEEDED`: исчерпан request/token/cost лимит. |
| `ChatStaleProcessorException` | `409 CHAT_PROCESSOR_FENCED`: потеря права финализировать. |
| `ChatTurnFailedException` | `422` с failure code: окончательно неуспешная обработка. |
| `ChatTurnInProgressException` | `409 CHAT_TURN_IN_PROGRESS` и `Retry-After`: тот же ключ ещё обрабатывается. |
| `IdempotencyKeyReusedException` | `409 IDEMPOTENCY_KEY_REUSED`: повтор UUID для другого содержимого/scope. |
| `ChatApiExceptionHandler` | `handle(ChatApiException)` формирует `ChatErrorResponse`; `resolveRetryAfterSeconds()` округляет миллисекунды вверх. Advice назначен **только** `ChatController`. |
| `ChatInfrastructureExceptionHandler` | `handleChatBusy()` и `handleChatLockUnavailable()` формируют общий `ApiErrorResponse`, пишут requestId/path в лог при сбое Redis. Advice глобальный. |

### 5.6. `observability/` — метрики (1 класс)

| Класс | Основные методы |
|---|---|
| `ChatMetrics` | `recordReservedAfterCommit`, `recordReplay`, `recordTerminalAfterCommit` (две перегрузки), `recordRecoveryBatchAfterCommit`, `recordOwnershipLoss`; `afterCommit` регистрирует callback синхронизации транзакции, `increment` считает lifecycle events. Метки: `state/event/layer`, без tenant/user/chat IDs. |

### 5.7. `quota/` — доменные объекты квот (3 типа)

| Класс | Методы / контракт |
|---|---|
| `ChatQuotaConsumption` | Счётчики запросов/токенов/стоимости + `usageUnverifiable`, `costUnverifiable`. Compact constructor проверяет неотрицательность; legacy constructor из четырёх полей ставит оба флага `false`. |
| `ChatQuotaPolicy` | Политика организации/пользователя с месячными лимитами; `unlimited()` возвращает выключенную политику. |
| `ChatQuotaReservation` | Объект **route-bound** резерва с `modelRouteDecisionId`, входными/выходными единицами, доказуемым верхним пределом стоимости; `databaseReservedCostUsd()` кодирует unknown как 0 только совместно с `costVerifiable=false`. `setScale(12, CEILING)` не допускает округления верхней границы вниз. **Текущий `ChatQuotaService.reserve` этот тип не использует.** |

### 5.8. `repository/` — хранение и атомарные изменения (10 типов)

| Класс | Методы и SQL-механизм |
|---|---|
| `ChatHistoryRepository` | `findNewestSucceededTurns(sessionId,orgId,userId,limit)`: JDBC SQL JOIN только полных SUCCEEDED пар; сортировка newest-first. |
| `ChatHistoryTurn` | Неизменяемая проекция `turnId/userContent/assistantContent`. |
| `ChatMessageRepository` | `findVisibleBySessionId`, `findOwnedMessage`: JPA запросы для чтения сообщения/видимой истории. |
| `ChatQuotaRepository` | `lockOrganizationPolicy`, `lockUserPolicy` — `FOR UPDATE`; `organizationConsumption`, `userConsumption` — агрегирование; `insertReservation`, `settleSuccess`, `releaseFailure`, `markAmbiguous` — обновление состояний. Private `lockPolicy`, `queryConsumption`, `mapPolicy`, `mapConsumption`, `resolveSuccessfulReservationState`, `nullableLong`, `requireExactlyOneUpdate`, `requireAtMostOneUpdate`. Основной проблемный участок F-01/F-02. |
| `ChatSecurityStateRepository` | `isActive(userId,orgId)`: PostgreSQL `EXISTS` с проверкой `users.enabled` и `organizations.enabled`. |
| `ChatSessionRepository` | `findByUser_IdAndOrganization_IdAndArchivedAtIsNull`, `findByIdAndUser_IdAndOrganization_IdAndArchivedAtIsNull`, `existsBy...`, `touchOwned` — UPDATE даты владельца. |
| `ChatTurnMutexRepository` | `lockChat(chatId)` вызывает `pg_advisory_xact_lock(bigint)`; `key(chatId)` хеширует UUID SHA-256 и берёт первые 64 бита. Коллизии крайне маловероятны, но advisory lock **не является** единственным источником корректности. |
| `ChatTurnRecoveryRepository` | `recoverExpired(now,batchSize)`: `FOR UPDATE SKIP LOCKED` в CTE, пакетный `UPDATE ... RETURNING`; начатый physical call → AMBIGUOUS, неначатый → FAILED. |
| `ChatTurnRepository` | `findByIdempotencyKeyForUpdate`, `findSessionTurnByStateForUpdate`, владельческие lookups; `renewLease`, `markSucceeded`, `markFailed`, `markAmbiguous`, `markFailedBeforeProviderCall`, `markExpiredBeforeProviderFailed`, `markExpiredProcessingAmbiguous`. Нативные conditional UPDATE защищают от stale writer посредством `processing_token` и состояния. |
| `RecoveredChatTurn` | Проекция восстановленного хода: IDs, `state`, failure code, ambiguous flag. |

**Предпосылки корректности:** `lockChat()` вызывается **в действительной транзакции PostgreSQL**; `turn` уникален по `(session_id, client_request_id)`; не более одного PROCESSING на сессию; state transitions и tenant FK защищены миграциями и проверяются интеграционными тестами. В одной только Java-модели этих предпосылок недостаточно.

### 5.9. `service/` — прикладная логика (18 типов)

| Класс | Основные методы и взаимодействия |
|---|---|
| `AiHistoryBuilder` | `build(newestFirst)` проверяет полноту пар USER/ASSISTANT, обращает порядок в chronological order; токеновое усечение делегировано слою AI context window. |
| `ChatContentNormalizer` | `normalize(content)` (новые строки, UTF-16 limit, Unicode, controls), `sha256(normalizedContent)` (strict UTF-8), `requestHash(content,knowledgeBaseId,knowledgeMode)` (scope в hash); private `validateUnicode`, `validateControls`, `strictUtf8`. Реализует `ModelRequestContentHashPort`. |
| `ChatDatabaseClockRepository` | `now()` читает `SELECT clock_timestamp()` — единый источник DB wall-clock. В основных показанных алгоритмах lease/expiration не используется. |
| `ChatLockService` | `lock(chatId)` — Redis `SET NX PX` с ownerToken, лимит активных блокировок; `ensureValid`, `unlock`, `unlockQuietly`; private `renew` (Lua `GET` + `PEXPIRE`), `markLockAsLost`, `releaseOwnedKeyQuietly`, `buildKey`, `renewalThreadFactory`, `createScript`; `shutdown`. Вложенный `ChatLock` несёт lock state. |
| `ChatMapper` | `toChatResponse`, `toMessageResponse`, `toChatDetailsResponse`, `toTurnStatusResponse`, `enumName` — преобразует JPA entities в JSON-friendly DTO. |
| `ChatProcessingContext` | Неизменяемый record для выполнения/replay; constructor проверяет relationship между UUID, `aiRequest`, RAG scope, `replay`; `withAiRequest(request)` заменяет подготовленный RAG-request, не позволяя менять replay. Legacy constructors сохранены. |
| `ChatQuotaService` | `reserve` читает policies/consumption, проверяет пределы и создаёт резерв; `settleSuccess`, `releaseFailure`, `markAmbiguous`; private `validate` и `exceeds`. В текущей реализации использует фиксированный config estimate — F-01. |
| `ChatSecurityStateService` | `assertStillActive(...)` перепроверяет пользователя/организацию **после** сохранения ответа; при отзыве блокирует выдачу caller. |
| `ChatService` | `create`, `findAll`, `archive`, `findById`, `sendMessage`, `findMessages`, `findTurnStatus`; private `assertOwnedChatExists`, `findOwnedSession`, `requireCurrentUser` (tenant roles, запрет `SUPER_ADMIN`). CRUD/read через Spring transactions; execution делегирован coordinator. |
| `ChatTurnAuditDetailsFactory` | `success`/`failure` — формирует стабильные audit details без содержимого промпта. |
| `ChatTurnExecutionCoordinator` | `execute` управляет всем ходом. Private `startLeaseWatch`, `prepareRag`, `enforceReservedRouteEnvelope`, `failRouteEnvelope`, `persistPreProviderFailureQuietly`, `ensureOwnershipBeforeProvider`, `ensureOwnershipAfterProvider`, `invokeProvider`, `completeRag`, `persistSuccess`, `markAmbiguousQuietly`, `ambiguous`. Координатор не Spring bean и не держит внешние вызовы внутри DB transaction. |
| `ChatTurnFinalizationService` | `failBeforeProviderCall`, `succeed`, `succeedRag`, `failUnambiguous`, `markAmbiguous`, `markAmbiguousAfterPersistenceFailure` (`REQUIRES_NEW`), `replaySucceeded`, `status`, private `touchChat`, `stale`. Формирует terminal updates, ответ, quota, audit, metrics в отдельных транзакциях. |
| `ChatTurnLeaseService` | `watch` запускает таймер, `ensureValid`, `close`; private `renew` вызывает `ChatTurnRepository.renewLease`, `threadFactory`, `shutdown`. Вложенный `LeaseWatch` фиксирует permission state. |
| `ChatTurnReadSupport` | `replaySucceeded`, `status`, `ownedTurn`, `ownedMessage` — единственное место реконструкции read/replay данных. Вызывается в транзакциях внешнего finalization service. |
| `ChatTurnRecoveryCoordinator` | `recoverInline`, `completeScheduledBatch` с `MANDATORY`; private `validateInlineRecoveryCandidate`, `recoverInlineTurn`, `completeRecovery`, `completeRecoveredTurn`, `recordRecoveryAudit`, `recoveryAuditDetails`, `requireExactlyOneRecoveryUpdate`, `firstNonBlank`. Сводит turn+quota+audit в один коммит. |
| `ChatTurnRecoveryScheduler` | `recover()` по cron запускает batch loop, не дольше `maxBatchesPerRun`, логирует исключения, чтобы scheduler не умер. |
| `ChatTurnRecoveryService` | `recoverExpiredBatch()` — одна транзакция, один батч `SKIP LOCKED`, потом quota/audit/metrics. |
| `ChatTurnReservationService` | `reserveOrReplay` — основная reservation-транзакция. Private `resolveExisting`, `recoverExpired`, `throwInProgress`, `findOwnedSession`, `requireArguments`. Связывает idempotency, историю, model governance, квоту, аудит, sealed AI request и rate limit. |

### 5.10. `title/` — названия чатов (3 типа)

| Класс | Методы и гарантии |
|---|---|
| `ChatTitlePolicy` | `normalizeCreationTitle`, `normalizeStoredTitle`, `normalizeManualTitle`, `automaticTitle`. Private `normalizeExplicitTitle`, `validateUnicodeAndControls`, `normalizeWhitespace`, `firstUsefulSentence`, `truncateAutomaticTitle`. До 255 Unicode code points при ручном вводе и до 72 при автоматическом; убирает Markdown-маркеры и приветствия, старается сохранить осмысленное первое предложение/границу слова. Вызовов LLM нет. |
| `ChatTitleService` | `rename` — transaction + advisory mutex, ownership check, нормализация, идемпотентный no-op для неизменённого USER title, переход provenance в USER и аудит длин; `codePointLength` — подсчёт code points. |
| `ChatTitleSource` | `DEFAULT` (плейсхолдер), `AUTO` (производный заголовок), `USER` (явное действие человека). |

## 6. Что сделано хорошо

| Решение | Почему это правильно |
|---|---|
| **Два уровня координации Redis + PostgreSQL** | Redis снижает конкурентную нагрузку, но ошибку/истечение его TTL компенсирует DB fencing. |
| **Отдельные reservation/finalization транзакции** | Нет удержания строки/соединения БД на весь внешний HTTP-вызов AI. |
| **`processingToken` и conditional UPDATE** | Старый узел не должен перезаписать итог более нового состояния turn. |
| **`AMBIGUOUS` вместо слепого ретрая** | Защищает от повторного платного вызова, когда факт выполнения неизвестен. |
| **V67/V72 physical STARTED evidence и auth epoch** | Факт старта и актуальная эпоха организации проверяются на границе БД; старый запрос не должен получить право начать физическую операцию после revoke. |
| **Immutable governance/route evidence** | Физическую модель, envelope и provenance нельзя незаметно заменить подготовленным RAG-текстом. |
| **Idempotency hash включает knowledge-scope** | Тот же текст с другой базой знаний не считается тем же запросом. |
| **Fail-fast configuration validation** | Неверные лимиты/TTL/cron останавливают запуск, а не дают тихо работать в неопределённой конфигурации. |
| **Многоуровневая валидация entity + SQL/Flyway** | Доменная логика и БД проверяют разные классы нарушений. |
| **Проверка tenant ownership и soft archive** | Персональные данные и история не доступны по одному знанию чужого UUID. |
| **Audit без текста сообщений** | Меньше риск раскрыть промпты и персональные данные в журналах аудита. |
| **Метрики без высококардинальных labels** | Не создают серии с уникальными user/chat/turn ID. |
| **`SKIP LOCKED` и батчирование восстановления** | Несколько узлов могут конкурентно забирать работу без последовательного сканирования всех записей. |
| **Title provenance** | Ручное название пользователя не должно перезаписываться автоматическим; V58 фиксирует это в БД. |
| **В проекте есть модульные и PostgreSQL integration test классы** | Реальный старт для регрессионной защиты, хотя само наличие файлов не означает, что все проверки прошли сейчас. |

## 7. Findings: критично, серьёзно, улучшения

**Приоритет:** P0 — немедленно блокирует безопасную эксплуатацию конкретного контракта; P1 — высокая вероятность ошибок 
в production / нарушение объявленной гарантии; P2 — важная надёжность/сопровождение; P3 — косметика. Здесь *P0* указан только для случаев, когда система заявляет именно **жёсткий денежный лимит**, а не best-effort estimate.

### F-01 — P0/P1. Quota reserve не привязан к выбранной модели и физическому расходу

**Факт по коду:** `ChatQuotaService.reserve()` использует `properties.reservationInputTokens()`, `reservationOutputTokens()`, 
`reservationCostUsd()`; `ChatQuotaRepository.insertReservation()` записывает `reservation_version=1`, `cost_verifiable=false`, 
не записывает `model_route_decision_id`; при этом определён `ChatQuotaReservation` с route-bound cost upper bound, а V67 ввела V2-формат и `chat_physical_spend_v67`.

**Последствие:** размер резерва не следует из реального выбранного маршрута. `AiChatResponse` одного завершённого хода также не 
обязательно выражает суммарный расход всех физических попыток (например, provider retries). Жёсткий денежный и token cap может 
оказаться нарушенным. Фиксированная сумма — **оценка**, а не доказанная верхняя граница.

**Что сделать:** в reservation flow передавать **immutable** `ChatQuotaReservation` из route decision/price scheme; 
сохранять `reservation_version=2`, `model_route_decision_id` и `cost_verifiable`; агрегировать физические попытки, unknown 
spend и незавершённые обязательства. Пока верхняя граница недоказуема, **fail-closed для hard-cost quota** либо явно объявленный 
и принятый бизнесом best-effort режим. Сначала определить контракт, затем менять алгоритм и делать миграцию; не переписывать V67.

**Тесты:** route A/B с разной стоимостью, несколько физических попыток одного turn, `UNPRICED`, `AMBIGUOUS`, провайдер вернул 
неполный usage, новая цена во время запроса, параллельные пользователи одной организации.

### F-02 — P1. Признаки непроверяемого потребления фактически теряются

**Факт:** `ChatQuotaConsumption` имеет поля `usageUnverifiable/costUnverifiable`; обе SQL-агрегации `ChatQuotaRepository` 
возвращают только суммы. `mapConsumption` вызывает старый четырёхаргументный конструктор, который **всегда ставит оба флага false**. `coalesce(actual_..., reserved_...)` замещает отсутствующий факт оценкой, а состояние непроверяемости результата не переносится в hard-limit policy.

**Последствие:** неизвестный расход может трактоваться как достаточно проверенный. Особо опасно для ограничения финансового 
обязательства, когда физическая попытка уже состоялась, а стоимость неполная/неизвестная.

**Что сделать:** использовать V67 physical spend view или эквивалентный доказуемый read model, вычислять quality flags, учитывать 
ledger всех физически начатых попыток и заблокировать новый платный вызов при непроверяемом расходе, если политика требует строгого лимита.

### F-03 — P1 для UX. Не виден вызов автоматического заголовка первого сообщения

**Факт:** `ChatSessionEntity.assignAutomaticTitle()` и `ChatTitlePolicy.automaticTitle()` реализованы; `ChatTurnReservationService.reserveOrReplay()` 
после `userMessage` выполняет `session.touch(now)`, но **не вызывает** присвоение automatic title. В `ChatTitleService` реализован только 
ручной `rename()`; в представленных 18 service-классах другого инициатора нет. V58 содержит лишь одноразовый SQL-backfill исторических чатов.

**Последствие:** вновь созданный чат с `DEFAULT` может оставаться «Новый чат» после первого сообщения, если в другом непоказанном участке нет 
самостоятельного обработчика.

**Что сделать:** подтвердить отсутствие отдельного hook по всему проекту. Если его нет — в той же транзакции первого **принятого** 
USER-сообщения (после проверки idempotency, до коммита) при `titleSource == DEFAULT` вызвать `assignAutomaticTitle(ChatTitlePolicy.automaticTitle(content), now)`. Сохранить семантику: `AUTO`/`USER` никогда не перезаписываются; при rollback/rate-limit заголовок тоже откатывается; вручную переименованный чат всегда выигрывает; audit при необходимости отдельный и без исходного текста.

### F-04 — P1. Часы PostgreSQL и JVM не используются единообразно

**Факт:** `ChatDatabaseClockRepository.now()` возвращает `clock_timestamp()` БД; `ChatTurnLeaseService.renew()` и 
`ChatTurnRecoveryService.recoverExpiredBatch()` берут `clock.instant()` приложения; `ChatTurnReservationService` и `ChatTurnFinalizationService` 
тоже опираются на JVM `Clock`. Физический STARTED в V67 проверяет `clock_timestamp()` непосредственно в PostgreSQL.

**Последствие:** смещение часов у реплик приложения относительно БД может вызвать ложный expire/ownership loss, ошибку `lease_until >= completedAt` 
или чрезмерно долгую жизнь PROCESSING. Именно распределённый lease особенно чувствителен к этому.

**Что сделать:** определить PostgreSQL как authoritative source для lease/fencing; использовать его время в SQL `UPDATE ... RETURNING`, 
проверке expiry и recovery; либо явно обеспечить и измерять допустимый bound на skew с защитой. Важно не смешивать два источника в одной state machine. 
Изменение делать небольшими проверяемыми шагами.

### F-05 — P1/P2. Неопознанные ошибки до STARTED попадают в ветку AMBIGUOUS

**Факт:** `ChatTurnExecutionCoordinator.invokeProvider()` оборачивает **также** `requestedPhysicalModel`/`targetFor(model)` и вызов 
`AiExecutionService.execute()` общим `catch (RuntimeException)` → `markAmbiguousQuietly()`. Однако `ChatTurnRepository.markAmbiguous()` 
требует `provider_call_started_at IS NOT NULL`.

**Последствие:** если исключение возникает **до фиксации STARTED** (например, lookup модели/проверка execution binding), попытка пометить 
AMBIGUOUS изменит 0 строк, ошибка сохранения будет подавлена, исходный `PROCESSING` останется до recovery. Клиент получит `AI_OUTCOME_AMBIGUOUS` 
даже при физически не начатом запросе. После recovery результат может стать `FAILED`, что создаст временно противоречивые состояния.

**Что сделать:** выделить `pre-admission` и `post-STARTED` фазы, не пытаясь определять их только по классу Java-исключения. Факт STARTED 
читать из **зафиксированного DB evidence**, а до STARTED использовать `failBeforeProviderCall` при доказанном отсутствии physical attempt. 
При невозможности доказать — безопасно удерживать unknown state до reconciler, не объявляя факт provider execution. Тестировать сбои `targetFor`, 
binding, INSERT STARTED, сеть и response parsing раздельно.

### F-06 — P1/P2. Длительный внешний вызов и полная корректность watchdog при завершении

**Факт:** `ChatTurnLeaseService` продлевает lease планировщиком с JVM-временем; после терминального `markSucceeded` watchdog может 
выполнить ещё один scheduled tick, получить `updated=0` и зарегистрировать `ownership.loss`, хотя завершение штатное. `ensureValid()` 
проверяет локальный `AtomicBoolean` и не гарантирует, что lease всё ещё действителен в ту же наносекунду; реальную защиту обеспечивает только БД.

**Что сделать:** не считать каждый `updated=0` системным инцидентом без классификации terminal/superseded/expired; по возможности 
останавливать watch перед финализацией, **не нарушая защиту** в момент commit; следить за нагрузкой write-renewal и задержками scheduler. 
Приоритет F-04 выше.

### F-07 — P1/P2. Резервирование Redis rate limit не транзакционно с PostgreSQL

**Факт:** `rateLimitService.checkAiMessageAllowed()` запускается последним внутри DB-транзакции, после `validateAllowedTurnLinkBeforeExternalSideEffects()`, но **до фактического commit** Spring `@Transactional`.

**Последствие:** откат транзакции из-за deadlock, constraint на commit, потери соединения и т. п. может оставить потреблённый слот 
Redis без сохранённого `ChatTurn`. Принудительная проверка одного deferred constraint существенно уменьшает окно, но **не делает две системы атомарными**.

**Что сделать:** либо документировать приемлемую модель «небольшое переучитывание rate limit» (обычно терпимо для abuse-rate-limit), 
либо внедрить reserve/confirm/cancel с compensation/idempotent key. Не пытаться добиться фиктивной распределённой транзакции.

### F-08 — P2. Несовпадающие часы финансового периода

**Факт:** `ChatQuotaProperties.periodZone` допускает любую `ZoneId`; `ChatQuotaService.reserve()` вычисляет `YearMonth` в ней. 
Отдельный financial read model `model_cost_commitment_ledger_v60` построен вокруг **UTC**.

**Последствие:** отчётный месяц, месяц hard quota и месяц физического spend могут расходиться на границе суток. Это не обязательно ошибка, 
если два периода специально разные, но такой контракт должен быть явным.

**Что сделать:** централизовать определения *quota month*, *ledger month* и *billing month*, либо документировать обоснованное различие, 
добавить тесты пересечения `UTC ± offset`, начала месяца, DST.

### F-09 — P2. Проверка денежного формата в assistant entity неполная

**Факт:** `ChatMessageEntity.validatePricing()` проверяет `costUsd >= 0` и `scale <= 12`, но не ограничивает **18 цифр слева** для `numeric(30,12)`. 
`ChatQuotaProperties` такую проверку содержит, то есть правила разошлись.

**Последствие:** ошибка обнаружится только при записи в БД, уже после получения ответа провайдера; корректный response превратится в проблему 
финализации и потенциальную AMBIGUOUS.

**Что сделать:** общая domain validation/нормализация валютного значения с точным соответствием SQL type и pricing provider, 
включая `precision`, `scale`, rounded upper-bound versus actual cost.

### F-10 — P2. Форматы ошибок двух контроллеров и полей различаются

**Факт:** `ChatApiExceptionHandler` ограничен `assignableTypes=ChatController.class`, а `ChatTitleController` обслуживается global advice.
Инфраструктурные исключения возвращают `ApiErrorResponse`, turn-specific — `ChatErrorResponse`.

**Последствие:** frontend обязан корректно поддерживать разные error DTO; переименование и отправка сообщения могут давать ошибки с разными 
кодами/структурой. Не найдено доказательства прямой уязвимости, но контракт хрупкий.

**Что сделать:** зафиксировать единый REST error envelope для новых API (или адаптер без breaking change), покрыть status/code/retryAfter 
контрактными тестами с frontend. Сохранять legacy до завершения rolling deployment.

### F-11 — P2. Условие отзыва доступа проверяет только текущее `enabled`

**Факт:** `ChatSecurityStateRepository.isActive` проверяет `users.enabled` + `organizations.enabled`, не сверяет snapshot `auth_version` caller. 
Физический STARTED отдельно защищён V72. После физического старта возможна ситуация disable → re-enable до `assertStillActive`.

**Последствие:** post-response predicate может увидеть организацию активной и вернуть контент по старому principal, если правила 
токенов/ротации auth epoch вне модуля это не исключают. **Не является подтверждённым обходом авторизации:** надо сверить полный security 
filter/epoch enforcement.

**Что сделать:** тестировать disable/re-enable между STARTED и ответом, отзыв user session и поведение JWT epoch. При требовании 
strict in-flight revoke сравнивать текущее поколение доступа, а не только `enabled`.

### F-12 — P2/P3. Сложность сопровождения и перегрузка классов

**Факт:** `ChatTurnReservationService` и `ChatTurnExecutionCoordinator` объединяют множество стратегий/портов; у `ChatTurnReservationService` 
длинный constructor. В `ChatService` coordinator создаётся вручную через `new` (без отдельной Spring AOP-границы; сейчас транзакции на нём и не нужны). 
В коде сохранены deprecated legacy overloads.

**Что сделать:** не проводить массовый рефакторинг вслепую. После закрытия F-01–F-05 выделять независимые компоненты по устойчивым границам 
(request admission, quota, RAG policy, failure classification) только при сохранении транзакционных инвариантов и с регрессионными тестами. Legacy API удалять лишь после проверки всех тестовых и продовых потребителей.

### F-13 — P2. Возможные пограничные случаи в нормализации и API validation

**Факты:** `SendMessageRequest.@Size(max=100000)` проверяет **сырой** Java String, а `ChatContentNormalizer` — уже преобразованные переводы 
строк и конфигурируемый лимит; `ChatTitlePolicy` считает Unicode code points, `maxMessageChars` — UTF-16 units (и это прямо обозначено). `ChatPageRequest` валидация `@Valid @ModelAttribute` зависит от корректной настройки MVC Bean Validation.

**Что сделать:** документировать именно это различие, проверить server/frontend на emoji и surrogate pairs, `CRLF` при 100k, невалидный UUID, 
отрицательные page/size, invalid sort/direction, extreme page offset. Не менять семантику лимитов без API-миграции.

### F-14 — P2. Поведение архивирования при in-flight turn нужно зафиксировать

**Факт:** `ChatService.sendMessage()` проверяет активную сессию до запуска; финальный `ChatSessionRepository.touchOwned()` не проверяет `archivedAt IS NULL`. Архивирование может произойти после reservation, до финализации.

**Последствие:** ранее принятый запрос может закончиться и сохранить ответ уже в архивированный чат. Это может быть допустимой бизнес-политикой, 
но она неочевидна.

**Что сделать:** решить контракт: архивирование запрещает новые turn, но разрешает завершение уже начатых; или отменяет выдачу/завершение. 
Тестировать последовательность `reserve → archive → provider response` без потери audit/quota evidence.

### Что **не** следует считать дефектами без дополнительных данных

- `@Transactional(REQUIRES_NEW)` на `markAmbiguousAfterPersistenceFailure` **не теряется** из-за self-invocation: внешний вызов через Spring bean попадает на аннотированный метод, после чего внутренний `markAmbiguous()` выполняется в уже открытой новой транзакции.
- `ChatTurnMutexRepository` использует 64-bit hash UUID для **сериализации**, не для идентификации объекта/безопасности. Коллизия приведёт к избыточной сериализации, а не автоматически к смешиванию tenant-данных.
- Отсутствие явного `save(session)` после `session.touch(now)` внутри активной JPA-транзакции само по себе не ошибка: dirty checking сохранит изменение, если entity остаётся managed.
- Необходимость `ChatLockService` одновременно с DB mutex не означает дублирование бизнес-корректности: они выполняют разные задачи и имеют разные области действия.
- `costVerifiable=false` сам по себе не значит, что уже произошло списание неверной суммы: это сигнал **отсутствия доказанного upper bound** и повод изменить правила hard quota.

## 8. DB constraints и миграции, от которых зависит безопасность

| Миграция | Что подтверждено |
|---|---|
| `V14__chat_turn_idempotency_and_integrity.sql` | Базовая схема идемпотентности и constraints. |
| `V32__chat_turn_state_machine.sql`, `V33__chat_turn_indexes.sql`, `V35__chat_turn_composite_integrity.sql` | Эволюция state machine, индексов и composite integrity; точные constraints нужно подтверждать против живой схемы, а не выводить только из имён. |
| `V43__archive_chat_sessions.sql` | Схема soft archive. |
| `V45__model_control_plane.sql` | Durable decision/evidence для маршрутизации. |
| `V58__chat_title_lifecycle.sql` | `title_source`, CHECK provenance, historical title backfill, событие `CHAT_RENAMED`. **Не запускает** Java automatic naming новых turn. |
| `V60__monthly_cost_commitment_ledger.sql` | UTC read model физического расхода с явным `unknown_cost_count`. |
| `V67__chat_execution_quota_and_evidence_hardening.sql` | Immutable terminal turn/message guards, physical-attempt admission, V1/V2 quota reservation fields, `chat_physical_spend_v67`, DB time function. |
| `V72__organization_auth_epoch_physical_attempt_gate.sql` | Guard auth-version в момент reservation и при физическом STARTED с блокировкой organization row; разрешает завершать уже допущенную физическую попытку. |

**Важно:** Flyway-миграции, уже применённые к окружениям, нельзя редактировать задним числом. Для исправлений нужны **новые** миграции, 
обновление JPA/JDBC и migration/integration regression suite.

## 9. Тестирование: что есть и что добавить

В репозитории существуют модульные тесты `ChatLockServiceTest`, `ChatTurnLeaseServiceTest`, `ChatQuotaServiceTest`, `ChatTurnReservationServiceTest`,
`ChatTurnFinalizationServiceTest`, `ChatServiceTest`, `ChatTitlePolicyTest` и другие. Есть PostgreSQL integration suites `ChatConcurrencyIntegrationTest`,
`ChatTenantIsolationIntegrationTest`, `ChatTurnRecoveryIntegrationTest`, `ChatTurnStateMachineIntegrationTest`, `ChatUsageQualityIntegrationTest`, 
`ChatDatabaseStateConstraintIntegrationTest` и миграционные тесты. **Наличие файлов — не подтверждение их успешного запуска в этом ревью.**

### Проверки, которые должны стать acceptance criteria

| ID | Сценарий | Ожидаемый результат |
|---|---|---|
| T-01 | 30 конкурентных запросов в один чат с разными ключами | Ровно один допустимый `PROCESSING`; нет двойного физического STARTED. |
| T-02 | Повтор одного `clientRequestId` с тем же текстом и scope | Один physical attempt, replay терминального результата. |
| T-03 | Тот же ключ с другим текстом/KB/mode | `409 IDEMPOTENCY_KEY_REUSED`; ни нового провайдера, ни квоты. |
| T-04 | Redis TTL истёк; старый processor продолжает работу | Fencing DB отвергает устаревшую запись. |
| T-05 | Разный `Clock` у приложения и PostgreSQL: `±30 s / ±5 min` | Lease/recovery не делают ложных выводов из skew; DB остаётся authoritative. |
| T-06 | Произошёл сбой **до** STARTED и после STARTED | До — достоверный failure; после — appropriate AMBIGUOUS без blind retry. |
| T-07 | Цена модели выше статической конфигурации, несколько физических попыток | Hard quota использует доказанный расход/upper bound, не фиксированный guess. |
| T-08 | Успешный provider response с `PARTIAL/MISSING` usage и `UNPRICED` | Unknown flags остаются true; соблюдается fail-closed policy. |
| T-09 | Резервирование последней доступной доли лимита с двух узлов | Суммарный actual+reserved не перескакивает hard quota. |
| T-10 | Отмена/ошибка после Redis rate-limit, до commit | Явно протестирован accounting gap / compensation. |
| T-11 | Новый DEFAULT чат получает первое принятое USER-сообщение | Название автоматически установлено; при rollback не установлено. |
| T-12 | `rename(USER)` конкурентно с первым сообщением | Пользовательский заголовок при любом порядке событий не теряется. |
| T-13 | Первый заголовок: emoji, длинные слова, Markdown, unicode/control chars | Code-point bound, валидный Unicode, детерминированный результат. |
| T-14 | `reserve → organization disable → STARTED` | V72 отвергает физическую попытку; provider не вызывается. |
| T-15 | `STARTED → disable → re-enable → response` | Поведение выдачи ответа соответствует auth-epoch policy, а ledger сохраняется. |
| T-16 | `reserve → archive → response` | Документированный контракт архивирования соблюдён. |
| T-17 | Recovery 2+ репликами с `SKIP LOCKED`, retry аудита, сбой квоты | Turn+quota+audit переходят атомарно; нет двойной финализации. |
| T-18 | PostgreSQL `numeric(30,12)` на максимуме и чуть выше | Ошибка определяется предсказуемо до некорректной записи, без нарушения ledger. |
| T-19 | Старый frontend/backend во время rolling upgrade | Canonical/legacy endpoints, DTO JSON и error codes совместимы. |
| T-20 | Replay исторического V44/V45 и V72 null auth epoch | Исторические результаты читаются, новый физический вызов по ним не начинается. |

### Команды для локального выполнения

```bash
# Из корня проекта:
cd backend
./mvnw clean verify

# Для ускоренного исследования конкретных тестов:
./mvnw -Dtest=ChatQuotaServiceTest,ChatTurnLeaseServiceTest,ChatTurnReservationServiceTest,ChatTitlePolicyTest test

# PostgreSQL integration-тесты запускайте в окружении, которое
# удовлетворяет существующей инфраструктуре проекта (контейнеры/БД).
# Не запускайте destructive migration/restore на production ради проверки.
```

Выполненные мной операции: **чтение/сопоставление исходников и миграций**, без реальных тестовых запусков и без изменений репозитория.

## 10. Порядок исправлений без регрессий

1. **Сначала квоты (F-01/F-02/F-08):** описать строгий финансовый инвариант; привязать резерв к route decision, physical attempt ledger и 
2. unknown spend; подготовить миграцию V73+ при необходимости; покрыть конкуренцией, retry и month boundary.
2. **Затем время (F-04/F-06):** унифицировать PostgreSQL timestamp для lease/expiry/finalization и проверить skew/дедлоки/нагрузку.
3. **Затем pre/post STARTED (F-05):** формально разделить фазу admission и реальную неизвестность исхода, сохранить durable audit и отсутствие 
4. blind retry.
4. **Отдельный небольшой change автоматических заголовков (F-03):** привязать к первой commit-успешной reservation и проверить гонку с `rename`.
5. **После стабилизации:** согласовать `ApiErrorResponse`, policy для archive in-flight, security epoch после ответа и валидацию стоимости.
6. **В последнюю очередь:** сокращать конструкторам число параметров, выносить повторяющуюся валидацию и удалять legacy overloads — только после 
7. проверки всех клиентов и тестов.

Каждый пункт лучше выпускать отдельной веткой и PR: сначала regression tests, затем минимальное изменение реализации, `./mvnw clean verify`, 
проверка миграций на новой и существующей базе, `git diff --check`, ручная проверка поведения frontend и staging. Не объединять исправление денежной 
квоты с косметическим переписыванием всего `chat`.

## 11. Итоговая оценка

**Сильная сторона:** модуль уже имеет зрелую модель идемпотентности, fencing, audit/observability, организации, контроля AI-попыток и 
восстановление после сбоев. Это значительно лучше тривиального `Controller → LLM → save(message)`.

**Критическое ограничение:** **текущую реализацию квоты нельзя по одному этому коду представлять как доказанный строгий потолок затрат на AI**. 
Наличие нового `ChatQuotaReservation`, флагов качества и физических ledger view не означает, что приложение их реально использует. 
Вторая приоритетная область — единый источник времени для распределённого lease.

**Решение:** сохранить существующую архитектуру, сначала устранить несоответствия между заявленными и фактическими гарантиями, 
затем дополнять UX-функции. До завершения интеграционных и конкурентных тестов разумно характеризовать модуль как **архитектурно сильный, но требующий целевого production hardening**, а не как полностью проверенный к эксплуатации.

---

### Ссылки на относящиеся к проверке файлы

- [Исходники `chat` в репозитории](https://github.com/Amid-1/Safeai-desk/tree/main/backend/src/main/java/ru/safeai/gateway/chat)
- [`ChatQuotaService`](https://github.com/Amid-1/Safeai-desk/blob/main/backend/src/main/java/ru/safeai/gateway/chat/service/ChatQuotaService.java)
- [`ChatQuotaRepository`](https://github.com/Amid-1/Safeai-desk/blob/main/backend/src/main/java/ru/safeai/gateway/chat/repository/ChatQuotaRepository.java)
- [`ChatTurnLeaseService`](https://github.com/Amid-1/Safeai-desk/blob/main/backend/src/main/java/ru/safeai/gateway/chat/service/ChatTurnLeaseService.java)
- [`ChatTurnReservationService`](https://github.com/Amid-1/Safeai-desk/blob/main/backend/src/main/java/ru/safeai/gateway/chat/service/ChatTurnReservationService.java)
- [`ChatTurnExecutionCoordinator`](https://github.com/Amid-1/Safeai-desk/blob/main/backend/src/main/java/ru/safeai/gateway/chat/service/ChatTurnExecutionCoordinator.java)
- [`ChatTitlePolicy`](https://github.com/Amid-1/Safeai-desk/blob/main/backend/src/main/java/ru/safeai/gateway/chat/title/ChatTitlePolicy.java)
- [V58 — названия чатов](https://github.com/Amid-1/Safeai-desk/blob/main/backend/src/main/resources/db/migration/V58__chat_title_lifecycle.sql)
- [V60 — месячный cost ledger](https://github.com/Amid-1/Safeai-desk/blob/main/backend/src/main/resources/db/migration/V60__monthly_cost_commitment_ledger.sql)
- [V67 — квоты и физические попытки](https://github.com/Amid-1/Safeai-desk/blob/main/backend/src/main/resources/db/migration/V67__chat_execution_quota_and_evidence_hardening.sql)
- [V72 — защита auth epoch](https://github.com/Amid-1/Safeai-desk/blob/main/backend/src/main/resources/db/migration/V72__organization_auth_epoch_physical_attempt_gate.sql)
