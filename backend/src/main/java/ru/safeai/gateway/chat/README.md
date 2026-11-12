# SafeAI Desk — модуль `chat`

`ru.safeai.gateway.chat` — Java 21, Spring Boot, PostgreSQL, Redis, Micrometer и Flyway.

## 1. Назначение и функциональность

`chat` обеспечивает работу персональных чатов внутри организации (tenant): создание и переименование, архивирование,
просмотр списка и сообщений, отправку запросов к AI, ограничение расходов и нагрузки, RAG (Retrieval-Augmented
Generation — генерация
с использованием знаний), восстановление оборвавшихся запросов, повторное получение результата без нового обращения к
провайдеру и аудит.

Текущие возможности:

1. **Изоляция данных:** сессии принадлежат конкретным `user_id` и `organization_id`; перед доступом проверяется владение
   чатом. Платформенный `SUPER_ADMIN` намеренно не должен получать доступ к tenant-chat через прикладной фасад.
2. **Идемпотентность:** клиент задаёт `clientRequestId`, который обязан повторяться при сетевом ретрае того же
   сообщения. Тот же ключ с другим нормализованным содержимым/knowledge-scope отвергается.
3. **Управление жизненным циклом:** отдельная запись `chat_turns` содержит состояние обработки, identity провайдера,
   токен владения, срок аренды и финальные сведения.
4. **Защита от одновременной обработки:** Redis-lock, PostgreSQL advisory lock, ограничения БД и условные переходы
   состояния дополняют друг друга.
5. **Управляемый выбор модели:** решение маршрутизации (`model_route_decision_id`) фиксируется до физического вызова, а
   реальный запрос сверяется с зарезервированным envelope (верхней границей входа).
6. **Обогащение знаниями:** `KnowledgeRagService` подготавливает контекст до обращения к провайдеру и формирует
   `AnswerPassport` после ответа.
7. **Учёт:** резервирование запросов/токенов/денег на уровне организации и пользователя; сохранение фактических `usage`
   и `pricing` -сведений.
8. **Восстановление:** периодический поиск просроченных `PROCESSING` и разделение безопасно не начатого вызова
   (`FAILED`) и потенциально выполненного (`AMBIGUOUS`).
9. **Наблюдаемость:** метрики жизненного цикла и восстановления с низкой кардинальностью, аудит действий без записи
   полного текста сообщений в audit payload.
10. **Названия чатов:** ручное переименование и детерминированная политика автоматического заголовка без дополнительного
    LLM-вызова; автоматический заголовок задаётся при первом принятом сообщении.

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
  │                                 ├─ ChatSecurityStateService (проверки доступа)
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

**Разграничение транзакций:** транзакция №1 резервирует и фиксирует `PROCESSING`; затем внешний провайдер вызывается
**без
длительной транзакции БД**; транзакция №2 фиксирует `SUCCEEDED`/`FAILED`/`AMBIGUOUS`. Это сильное архитектурное решение.
Redis-lock — не единственная гарантия корректности: решающее право финализации проверяется условным `UPDATE` в
PostgreSQL.

## Структура исходников

```text
chat/
├── config/
│   ├── ChatConfiguration.java
│   ├── ChatLockProperties.java
│   ├── ChatProperties.java
│   ├── ChatQuotaProperties.java
│   └── ChatRecoveryProperties.java
├── controller/
│   ├── ChatController.java
│   └── ChatTitleController.java
├── dto/
│   ├── ChatCapabilitiesResponse.java
│   ├── ChatDetailsResponse.java
│   ├── ChatErrorResponse.java
│   ├── ChatPageRequest.java
│   ├── ChatPageResponse.java
│   ├── ChatResponse.java
│   ├── ChatTurnStatusResponse.java
│   ├── CreateChatRequest.java
│   ├── MessagePageRequest.java
│   ├── MessageResponse.java
│   ├── SendMessageRequest.java
│   ├── SendMessageResponse.java
│   └── UpdateChatTitleRequest.java
├── entity/
│   ├── ChatMessageEntity.java
│   ├── ChatMessageRole.java
│   ├── ChatMessageStatus.java
│   ├── ChatQuotaReservationState.java
│   ├── ChatSessionEntity.java
│   ├── ChatTurnEntity.java
│   └── ChatTurnState.java
├── exception/
│   ├── AiOutcomeAmbiguousException.java
│   ├── ChatAccessRevokedException.java
│   ├── ChatApiException.java
│   ├── ChatApiExceptionHandler.java
│   ├── ChatBusyException.java
│   ├── ChatInfrastructureExceptionHandler.java
│   ├── ChatLeaseUnavailableException.java
│   ├── ChatLockUnavailableException.java
│   ├── ChatQuotaExceededException.java
│   ├── ChatStaleProcessorException.java
│   ├── ChatTurnFailedException.java
│   ├── ChatTurnInProgressException.java
│   └── IdempotencyKeyReusedException.java
├── observability/
│   └── ChatMetrics.java
├── quota/
│   ├── ChatQuotaConsumption.java
│   ├── ChatQuotaPolicy.java
│   └── ChatQuotaReservation.java
├── repository/
│   ├── ChatHistoryRepository.java
│   ├── ChatHistoryTurn.java
│   ├── ChatMessageRepository.java
│   ├── ChatQuotaRepository.java
│   ├── ChatSecurityStateRepository.java
│   ├── ChatSessionRepository.java
│   ├── ChatTurnMutexRepository.java
│   ├── ChatTurnRecoveryRepository.java
│   ├── ChatTurnRepository.java
│   └── RecoveredChatTurn.java
├── service/
│   ├── AiHistoryBuilder.java
│   ├── ChatContentNormalizer.java
│   ├── ChatDatabaseClockRepository.java
│   ├── ChatLockService.java
│   ├── ChatMapper.java
│   ├── ChatProcessingContext.java
│   ├── ChatQuotaService.java
│   ├── ChatSecurityStateService.java
│   ├── ChatService.java
│   ├── ChatTurnAuditDetailsFactory.java
│   ├── ChatTurnExecutionCoordinator.java
│   ├── ChatTurnFinalizationService.java
│   ├── ChatTurnLeaseService.java
│   ├── ChatTurnReadSupport.java
│   ├── ChatTurnRecoveryCoordinator.java
│   ├── ChatTurnRecoveryScheduler.java
│   ├── ChatTurnRecoveryService.java
│   └── ChatTurnReservationService.java
├── title/
│   ├── ChatTitlePolicy.java
│   ├── ChatTitleService.java
│   └── ChatTitleSource.java
└── README.md
```

## 3. API и контракты

Все маршруты начинаются с `/api/chats`. Для обоих контроллеров задано `@PreAuthorize("hasAnyRole('ADMIN', 'USER')")`.

- **`GET`**
  - Маршрут — `/capabilities`
  - Реализация — `ChatController.capabilities()`
  - Результат — Эффективные лимиты сервера для frontend.
- **`POST`**
  - Маршрут — `/`
  - Реализация — `ChatService.create()`
  - Результат — Создаёт чат; `201 Created`.
- **`GET`**
  - Маршрут — `/`
  - Реализация — `ChatService.findAll()`
  - Результат — `Slice` чатов без архивных, постранично.
- **`GET`**
  - Маршрут — `/{chatId}`
  - Реализация — `ChatService.findById()`
  - Результат — Чат + последние N сообщений, выведенных хронологически.
- **`DELETE`**
  - Маршрут — `/{chatId}`
  - Реализация — `ChatService.archive()`
  - Результат — Логическое архивирование, `204 No Content`; физического удаления нет.
- **`POST`**
  - Маршрут — `/{chatId}/messages`
  - Реализация — `ChatService.sendMessage()`
  - Результат — Обработка AI-хода либо replay/ошибка состояния.
- **`GET`**
  - Маршрут — `/{chatId}/messages`
  - Реализация — `ChatService.findMessages()`
  - Результат — Постраничный список пользовательских/AI-сообщений.
- **`GET`**
  - Маршрут — `/{chatId}/turns/by-client-request/{clientRequestId}`
  - Реализация — `ChatService.findTurnStatus()`
  - Результат — Статус по idempotency key.
- **`GET`**
  - Маршрут — `/{chatId}/turns/{clientRequestId}`
  - Реализация — тот же метод
  - Результат — Legacy alias для rolling deployment.
- **`PATCH`**
  - Маршрут — `/{chatId}`
  - Реализация — `ChatTitleService.rename()`
  - Результат — Ручное переименование с аудитом.

### Важные контракты

- `clientRequestId` — обязательный UUID, генерируется до первой отправки и
  **не должен меняться при повторе того же запроса**.
- `content` нормализуется (`CRLF/CR` → `LF`), проверяется по UTF-16 code units, валидности суррогатных пар и управляющим
  символам. Исторические данные и knowledge-mode входят в маршрутизацию, а content hash связывает пользовательский текст
  с knowledge-scope.
- Список возвращается через `Slice` (`hasNext`, `hasPrevious`), а **не** через `Page` с `totalElements`/`totalPages`.
- `/capabilities` отдаёт лимиты выполнения, но **не раскрывает** тарифный/пользовательский остаток квот.
- `SendMessageResponse` представляет **успешно завершённый** ход: `assistantMessage` и `completedAt` обязательны.
  `PROCESSING`, `FAILED`, `AMBIGUOUS` представляются статусным endpoint и/или типизированной HTTP-ошибкой.
- Ошибка `409 CHAT_TURN_IN_PROGRESS` может содержать `Retry-After`; `409 AI_OUTCOME_AMBIGUOUS` **не разрешает** слепой
  повтор физического запроса.
- Сортировка списка чатов ограничена `updatedAt`, `createdAt`, `title`, `id`; добавлен `id` как вторичный ключ
  стабильной сортировки. Сообщения сортируются по `createdAt DESC, id DESC`.

## 4. Жизненный цикл одного сообщения

1. `ChatController.sendMessage()` вызывает `ChatService.sendMessage()`; подтверждается принадлежность неархивного чата
   пользователю и организации.
2. `ChatTurnExecutionCoordinator.execute()` пытается взять Redis-lock с уникальным `ownerToken` и TTL.
3. `ChatTurnReservationService.reserveOrReplay()` в короткой транзакции нормализует текст, вычисляет hash, берёт
   `pg_advisory_xact_lock(chatId)`, ищет предшествующий запрос с тем же ключом и активный ход.
4. Если ход уже `SUCCEEDED`, возвращается replay без нового вызова AI; `FAILED` /`AMBIGUOUS` возвращают исходное
   финальное состояние через исключение; `PROCESSING` возвращает ожидание либо запускает recovery по истечении lease.
5. Для нового хода извлекается история только из успешных полных пар USER/ASSISTANT, выбирается физическая модель,
   сохраняются USER-message, `PROCESSING` -turn, квота, audit intent и immutable route/request evidence. Затем
   проверяется Redis rate limit. Транзакция коммитится **до** вызова провайдера.
6. Запускается watchdog lease, RAG подготавливает запрос; `ModelRouteExecutionGuard` контролирует фактический envelope.
   При потере владения до физического STARTED попытка должна завершаться как ошибка без выдуманного вызова провайдера.
7. Перед вызовом AI сверяются активность, `tokenVersion` и `organizationAuthVersion` с данными principal.
   При отказе ход завершается до обращения к провайдеру. V67 проверяет состояние хода при записи `STARTED`,
   а V72 проверяет актуальность версии организации в той же транзакции.
8. По возвращении ответа выполняется post-processing RAG, повторная проверка lock/lease и транзакционная финализация:
   `chat_turns` → `SUCCEEDED`, assistant-message, `AnswerPassport`, фактическая квота, аудит, обновление времени чата.
9. После коммита снова проверяются активность и обе версии доступа. При отзыве текущий HTTP-запрос получает
   `CHAT_ACCESS_REVOKED_DURING_PROCESSING`. Записи о результате и расходе сохраняются.
10. В `finally` закрывается DB-lease watchdog и снимается Redis-lock с проверкой `ownerToken`.

### Состояния `ChatTurnState`

- **`NEW`**
  - Значение — Подготовительное/историческое состояние
  - Повтор по тому же ключу — Не начинает второй физический вызов.
- **`PROCESSING`**
  - Значение — Обрабатывается; хранит `processingToken`, `leaseUntil`
  - Повтор по тому же ключу — `409` с ожиданием либо recovery, если просрочен.
- **`SUCCEEDED`**
  - Значение — Зафиксированы ответ и usage
  - Повтор по тому же ключу — **Replay** существующего ответа.
- **`FAILED`**
  - Значение — Достоверно ошибочный / не начатый вызов
  - Повтор по тому же ключу — Возврат failure; тот же ключ не выполняется повторно.
- **`AMBIGUOUS`**
  - Значение — Неизвестно, завершил ли провайдер операцию / списал ли средства
  - Повтор по тому же ключу — Возврат неоднозначности; слепой retry запрещён.

`ChatQuotaReservationState`: `RESERVED` → `SETTLED` / `UNPRICED` / `AMBIGUOUS` / `RELEASED`. Привязка состояния квоты к
состоянию хода должна быть атомарной внутри транзакции.

## 5. Каталог классов и методов

Ниже перечислены **все 75 Java-типов**, входящие в представленный модуль, с их ролью и основными методами. Стандартные
accessor-методы
Java `record`, Lombok getters/setters, `equals/hashCode/toString` не расписываются по одному, если в них нет прикладной
логики.

### 5.1. `config/` — параметры среды (5 классов)

- **`ChatConfiguration`**
  - Основные методы и ответственность — Включает scheduling и регистрирует четыре `@ConfigurationProperties`. Сам
    прикладной логики не содержит.
- **`ChatProperties`**
  - Основные методы и ответственность — Compact constructor валидирует размеры выдачи, лимит текста, `processingLease`,
    количество потоков и watchdog. `leaseRenewalInterval()` возвращает `max(1s, processingLease/3)`.
- **`ChatLockProperties`**
  - Основные методы и ответственность — Валидирует Redis key-prefix, TTL 30 s–30 min, потоки, активные locks, timeout
    остановки; `renewalInterval()` задаёт треть TTL, минимум 1 s; `normalizePrefix()` удаляет хвостовые двоеточия.
- **`ChatQuotaProperties`**
  - Основные методы и ответственность — Настройки статического резервирования и периода. `isEnabled()` — флаг квот;
    `effectivePeriodZone()` — нормализованная временная зона. `validateReservationCost()` проверяет формат PostgreSQL
    `numeric(30,12)`.
- **`ChatRecoveryProperties`**
  - Основные методы и ответственность — `enabled`, `batchSize`, `maxBatchesPerRun`, cron. `isEnabled()`,
    `boundedBatchParameter()`, `normalizeAndValidateCron()`; cron проверяется через Spring `CronExpression.parse()`.

**Базовые значения:** `maxMessageChars=16000`, максимум 100000; `historyTurnLimit=50`, максимум 200;
`detailsMessageLimit=50`,
максимум 200; размер page не более 100; `processingLease=3m`, `chat.lock.ttl=4m`; recovery каждую минуту, до 20 батчей
по 100. Это лимиты **по умолчанию** из constructors — реально развернутые значения могут отличаться.

### 5.2. `controller/` — HTTP-вход (2 класса)

- **`ChatController`**
  - Методы — `capabilities`, `create`, `findAll`, `findById`, `archive`, `sendMessage`, `findMessages`, `findTurnStatus`
    . Получает `SafeAiUserPrincipal` через `@AuthenticationPrincipal`. Делегирует бизнес-логику сервису.
- **`ChatTitleController`**
  - Методы — `rename` — `PATCH /api/chats/{chatId}`; передаёт запрос в `ChatTitleService`.

### 5.3. `dto/` — контракт запросов и ответов (13 типов)

- **`ChatCapabilitiesResponse`**
  - Поля / методы / проверки — Четыре действующих ограничения; compact constructor требует положительные значения.
- **`ChatDetailsResponse`**
  - Поля / методы / проверки — Метаданные сессии + список сообщений; `List.copyOf()` защищает коллекцию от внешнего
    изменения.
- **`ChatErrorResponse`**
  - Поля / методы / проверки — Чат-специфичная ошибка: время, HTTP status, code, message, три UUID, `retryAfterSeconds`.
- **`ChatPageRequest`**
  - Поля / методы / проверки — `page/size/sortBy/direction`; `toPageable(configuredMaxSize)` валидирует whitelist
    сортировки, ограничивает размер и добавляет детерминированный tie-breaker `id`.
- **`ChatPageResponse<T>`**
  - Поля / методы / проверки — Метаданные `Slice`; static `from(Slice<T>)`; неизменяемая копия `content`, проверка
    page/size.
- **`ChatResponse`**
  - Поля / методы / проверки — `id`, `title`, `createdAt`, `updatedAt`.
- **`ChatTurnStatusResponse`**
  - Поля / методы / проверки — Подробное состояние turn: provider/model/ошибки/lease/времена и связанные сообщения.
- **`CreateChatRequest`**
  - Поля / методы / проверки — Необязательное название; domain validation выполняется в `ChatTitlePolicy`.
- **`MessagePageRequest`**
  - Поля / методы / проверки — `page/size`; `toPageable(configuredMaxSize)` — сортировка по времени и UUID по убыванию.
- **`MessageResponse`**
  - Поля / методы / проверки — Контент и технические метаданные provider, model, tokens, usage, cost и pricing.
- **`SendMessageRequest`**
  - Поля / методы / проверки — `content`, `clientRequestId`, необязательные `knowledgeBaseId/knowledgeMode`. Compact
    constructor подставляет GENERAL/KNOWLEDGE_ASSISTED, проверяет совместимость режима и базы. Двухаргументный
    конструктор — совместимость с вызовами без RAG.
- **`SendMessageResponse`**
  - Поля / методы / проверки — Успешный результат хода: UUID, `replay`, сообщения, даты, `AnswerPassport`; обязательные
    значения проверяются. Конструктор без passport — backward compatibility.
- **`UpdateChatTitleRequest`**
  - Поля / методы / проверки — `@NotBlank title`; максимальная длина и Unicode проверяются доменной политикой, не
    `@Size` Java UTF-16.

### 5.4. `entity/` — долговременная модель (7 типов)

- **`ChatMessageEntity`**
  - Основные методы / инварианты — Фабрики `user()`, `completedAssistant()`, `system()`, внутренние `base()`,
    `validateNonAiMessage()`. `validateInvariant()` вызывается `@PrePersist/@PreUpdate` и из фабрик;
    `validateUser/Assistant/System`, `validateUsage`, `validatePricing`, `requireNoAiMetadata`, `normalizeOptional`,
    `normalizeCurrency` контролируют тип сообщения и provider evidence.
- **`ChatMessageRole`**
  - Основные методы / инварианты — `USER`, `ASSISTANT`, `SYSTEM`.
- **`ChatMessageStatus`**
  - Основные методы / инварианты — `PENDING`, `COMPLETED`, `FAILED`; фабрики USER и SYSTEM создают `COMPLETED`.
- **`ChatQuotaReservationState`**
  - Основные методы / инварианты — `RESERVED`, `SETTLED`, `UNPRICED`, `AMBIGUOUS`, `RELEASED`.
- **`ChatSessionEntity`**
  - Основные методы / инварианты — `create(user,title,now)` с `titleSource`; `touch(now)`;
    `assignAutomaticTitle(title,now)` только при DEFAULT; `renameByUser(title,now)` переводит в USER;
    `archive(userId,now)` — soft delete; `validateInvariant()` защищает provenance, даты, tenant-отношение. Обычный
    setter для `title/titleSource` закрыт.
- **`ChatTurnEntity`**
  - Основные методы / инварианты — Перегрузки фабрики `processing(...)`: legacy и V45+ с `modelRouteDecisionId`.
    `validateInvariant()` проверяет UUID, hash SHA-256, время, knowledge-scope, поля и допустимые комбинации состояний;
    `requireNonTerminal`, `requireTerminal`, `normalize`, `validateKnowledgeScope`. При резервировании фиксирует
    `authorizedOrganizationAuthVersion`.
- **`ChatTurnState`**
  - Основные методы / инварианты — `NEW`, `PROCESSING`, `SUCCEEDED`, `FAILED`, `AMBIGUOUS`.

### 5.5. `exception/` — контракт с клиентом (13 типов)

- **`ChatApiException`**
  - HTTP / значение — База доменных ошибок: HTTP status, публичный code, UUID идентификаторы, optional `retryAfter`.
- **`AiOutcomeAmbiguousException`**
  - HTTP / значение — `409 AI_OUTCOME_AMBIGUOUS`: результат провайдера неизвестен; запрет blind retry.
- **`ChatAccessRevokedException`**
  - HTTP / значение — `403 CHAT_ACCESS_REVOKED_DURING_PROCESSING`: доступ отозван после начала обработки.
- **`ChatBusyException`**
  - HTTP / значение — `409 CHAT_BUSY` через общий `ConflictException`: другой запрос уже использует чат.
- **`ChatLeaseUnavailableException`**
  - HTTP / значение — `503 CHAT_LEASE_UNAVAILABLE`: не удалось обслужить DB watchdog.
- **`ChatLockUnavailableException`**
  - HTTP / значение — Ошибка инфраструктуры Redis-lock, обработчик формирует `503`.
- **`ChatQuotaExceededException`**
  - HTTP / значение — `429 AI_QUOTA_EXCEEDED`: исчерпан request/token/cost лимит.
- **`ChatStaleProcessorException`**
  - HTTP / значение — `409 CHAT_PROCESSOR_FENCED`: потеря права финализировать.
- **`ChatTurnFailedException`**
  - HTTP / значение — `422` с failure code: окончательно неуспешная обработка.
- **`ChatTurnInProgressException`**
  - HTTP / значение — `409 CHAT_TURN_IN_PROGRESS` и `Retry-After`: тот же ключ ещё обрабатывается.
- **`IdempotencyKeyReusedException`**
  - HTTP / значение — `409 IDEMPOTENCY_KEY_REUSED`: повтор UUID для другого содержимого/scope.
- **`ChatApiExceptionHandler`**
  - HTTP / значение — `handle(ChatApiException)` формирует `ChatErrorResponse`; `resolveRetryAfterSeconds()` округляет
    миллисекунды вверх. Advice назначен **только** `ChatController`.
- **`ChatInfrastructureExceptionHandler`**
  - HTTP / значение — `handleChatBusy()` и `handleChatLockUnavailable()` формируют общий `ApiErrorResponse`, пишут
    requestId/path в лог при сбое Redis. Advice глобальный.

### 5.6. `observability/` — метрики (1 класс)

- **`ChatMetrics`**
  - Основные методы — `recordReservedAfterCommit`, `recordReplay`, `recordTerminalAfterCommit` (две перегрузки),
    `recordRecoveryBatchAfterCommit`, `recordOwnershipLoss`; `afterCommit` регистрирует callback синхронизации
    транзакции, `increment` считает lifecycle events. Метки: `state/event/layer`, без tenant/user/chat IDs.

### 5.7. `quota/` — доменные объекты квот (3 типа)

- **`ChatQuotaConsumption`**
  - Методы / контракт — Счётчики запросов/токенов/стоимости + `usageUnverifiable`, `costUnverifiable`. Compact
    constructor проверяет неотрицательность; legacy constructor из четырёх полей ставит оба флага `false`.
- **`ChatQuotaPolicy`**
  - Методы / контракт — Политика организации/пользователя с месячными лимитами; `unlimited()` возвращает выключенную
    политику.
- **`ChatQuotaReservation`**
  - Методы / контракт — Объект **route-bound** резерва с `modelRouteDecisionId`, входными/выходными единицами,
    доказуемым верхним пределом стоимости; `databaseReservedCostUsd()` кодирует unknown как 0 только совместно с
    `costVerifiable=false`. `setScale(12, CEILING)` не допускает округления верхней границы вниз.
    **Текущий `ChatQuotaService.reserve` этот тип не использует.**

### 5.8. `repository/` — хранение и атомарные изменения (10 типов)

- **`ChatHistoryRepository`**
  - Методы и SQL-механизм — `findNewestSucceededTurns(sessionId,orgId,userId,limit)`: JDBC SQL JOIN только полных
    SUCCEEDED пар; сортировка newest-first.
- **`ChatHistoryTurn`**
  - Методы и SQL-механизм — Неизменяемая проекция `turnId/userContent/assistantContent`.
- **`ChatMessageRepository`**
  - Методы и SQL-механизм — `findVisibleBySessionId`, `findOwnedMessage`: JPA запросы для чтения сообщения/видимой
    истории.
- **`ChatQuotaRepository`**
  - Методы и SQL-механизм — `lockOrganizationPolicy`, `lockUserPolicy` — `FOR UPDATE`; `organizationConsumption`,
    `userConsumption` — агрегирование; `insertReservation`, `settleSuccess`, `releaseFailure`, `markAmbiguous` —
    обновление состояний. Private `lockPolicy`, `queryConsumption`, `mapPolicy`, `mapConsumption`,
    `resolveSuccessfulReservationState`, `nullableLong`, `requireExactlyOneUpdate`, `requireAtMostOneUpdate`. Резерв V2
    и флаги качества расхода описаны в F-01–F-03.
- **`ChatSecurityStateRepository`**
  - Методы и SQL-механизм — `isActive(userId, orgId, tokenVersion, organizationAuthVersion)`: PostgreSQL `EXISTS` с
    проверкой активности и версий безопасности пользователя и организации.
- **`ChatSessionRepository`**
  - Методы и SQL-механизм — `findByUser_IdAndOrganization_IdAndArchivedAtIsNull`,
    `findByIdAndUser_IdAndOrganization_IdAndArchivedAtIsNull`, `existsBy...`, `touchOwned` — UPDATE даты владельца.
- **`ChatTurnMutexRepository`**
  - Методы и SQL-механизм — `lockChat(chatId)` вызывает `pg_advisory_xact_lock(bigint)`; `key(chatId)` хеширует UUID
    SHA-256 и берёт первые 64 бита. Коллизии крайне маловероятны, но advisory lock **не является** единственным
    источником корректности.
- **`ChatTurnRecoveryRepository`**
  - Методы и SQL-механизм — `recoverExpired(now,batchSize)`: `FOR UPDATE SKIP LOCKED` в CTE, пакетный
    `UPDATE ... RETURNING`; начатый physical call → AMBIGUOUS, неначатый → FAILED.
- **`ChatTurnRepository`**
  - Методы и SQL-механизм — `findByIdempotencyKeyForUpdate`, `findSessionTurnByStateForUpdate`, владельческие lookups;
    `renewLease`, `markSucceeded`, `markFailed`, `markAmbiguous`, `markFailedBeforeProviderCall`,
    `markExpiredBeforeProviderFailed`, `markExpiredProcessingAmbiguous`. Нативные conditional UPDATE защищают от stale
    writer посредством `processing_token` и состояния.
- **`RecoveredChatTurn`**
  - Методы и SQL-механизм — Проекция восстановленного хода: IDs, `state`, failure code, ambiguous flag.

**Предпосылки корректности:** `lockChat()` вызывается **в действительной транзакции PostgreSQL**; `turn` уникален по
`(session_id, client_request_id)`; не более одного PROCESSING на сессию; state transitions и tenant FK защищены
миграциями и проверяются интеграционными тестами. В одной только Java-модели этих предпосылок недостаточно.

### 5.9. `service/` — прикладная логика (18 типов)

- **`AiHistoryBuilder`**
  - Основные методы и взаимодействия — `build(newestFirst)` проверяет полноту пар USER/ASSISTANT, обращает порядок в
    chronological order; токеновое усечение делегировано слою AI context window.
- **`ChatContentNormalizer`**
  - Основные методы и взаимодействия — `normalize(content)` (новые строки, UTF-16 limit, Unicode, controls),
    `sha256(normalizedContent)` (strict UTF-8), `requestHash(content,knowledgeBaseId,knowledgeMode)` (scope в hash);
    private `validateUnicode`, `validateControls`, `strictUtf8`. Реализует `ModelRequestContentHashPort`.
- **`ChatDatabaseClockRepository`**
  - Основные методы и взаимодействия — `now()` читает `SELECT clock_timestamp()` — единый источник DB wall-clock.
    Используется, в частности, для квот и финализации.
- **`ChatLockService`**
  - Основные методы и взаимодействия — `lock(chatId)` — Redis `SET NX PX` с ownerToken, лимит активных блокировок;
    `ensureValid`, `unlock`, `unlockQuietly`; private `renew` (Lua `GET` + `PEXPIRE`), `markLockAsLost`,
    `releaseOwnedKeyQuietly`, `buildKey`, `renewalThreadFactory`, `createScript`; `shutdown`. Вложенный `ChatLock` несёт
    lock state.
- **`ChatMapper`**
  - Основные методы и взаимодействия — `toChatResponse`, `toMessageResponse`, `toChatDetailsResponse`,
    `toTurnStatusResponse`, `enumName` — преобразует JPA entities в JSON-friendly DTO.
- **`ChatProcessingContext`**
  - Основные методы и взаимодействия — Неизменяемый record для выполнения/replay; constructor проверяет relationship
    между UUID, `aiRequest`, RAG scope, `replay`; `withAiRequest(request)` заменяет подготовленный RAG-request, не
    позволяя менять replay. Legacy constructors сохранены.
- **`ChatQuotaService`**
  - Основные методы и взаимодействия — `reserve` читает policies/consumption, проверяет пределы и создаёт резерв;
    `settleSuccess`, `releaseFailure`, `markAmbiguous`; private `validate` и `exceeds`. Штатный путь использует резерв
    выбранного маршрута.
- **`ChatSecurityStateService`**
  - Основные методы и взаимодействия — `assertStillActive(...)` перепроверяет пользователя/организацию **после**
    сохранения ответа; при отзыве блокирует выдачу caller.
- **`ChatService`**
  - Основные методы и взаимодействия — `create`, `findAll`, `archive`, `findById`, `sendMessage`, `findMessages`,
    `findTurnStatus`; private `assertOwnedChatExists`, `findOwnedSession`, `requireCurrentUser` (tenant roles, запрет
    `SUPER_ADMIN`). CRUD/read через Spring transactions; execution делегирован coordinator.
- **`ChatTurnAuditDetailsFactory`**
  - Основные методы и взаимодействия — `success`/`failure` — формирует стабильные audit details без содержимого промпта.
- **`ChatTurnExecutionCoordinator`**
  - Основные методы и взаимодействия — `execute` управляет всем ходом. Private `startLeaseWatch`, `prepareRag`,
    `enforceReservedRouteEnvelope`, `failRouteEnvelope`, `persistPreProviderFailureQuietly`,
    `ensureOwnershipBeforeProvider`, `ensureOwnershipAfterProvider`, `invokeProvider`, `completeRag`, `persistSuccess`,
    `markAmbiguousQuietly`, `ambiguous`. Координатор не Spring bean и не держит внешние вызовы внутри DB transaction.
- **`ChatTurnFinalizationService`**
  - Основные методы и взаимодействия — `failBeforeProviderCall`, `succeed`, `succeedRag`, `failUnambiguous`,
    `markAmbiguous`, `markAmbiguousAfterPersistenceFailure` (`REQUIRES_NEW`), `replaySucceeded`, `status`, private
    `touchChat`, `stale`. Формирует terminal updates, ответ, quota, audit, metrics в отдельных транзакциях.
- **`ChatTurnLeaseService`**
  - Основные методы и взаимодействия — `watch` запускает таймер, `ensureValid`, `close`; private `renew` вызывает
    `ChatTurnRepository.renewLease`, `threadFactory`, `shutdown`. Вложенный `LeaseWatch` фиксирует permission state.
- **`ChatTurnReadSupport`**
  - Основные методы и взаимодействия — `replaySucceeded`, `status`, `ownedTurn`, `ownedMessage` — единственное место
    реконструкции read/replay данных. Вызывается в транзакциях внешнего finalization service.
- **`ChatTurnRecoveryCoordinator`**
  - Основные методы и взаимодействия — `recoverInline`, `completeScheduledBatch` с `MANDATORY`; private
    `validateInlineRecoveryCandidate`, `recoverInlineTurn`, `completeRecovery`, `completeRecoveredTurn`,
    `recordRecoveryAudit`, `recoveryAuditDetails`, `requireExactlyOneRecoveryUpdate`, `firstNonBlank`. Сводит
    turn+quota+audit в один коммит.
- **`ChatTurnRecoveryScheduler`**
  - Основные методы и взаимодействия — `recover()` по cron запускает batch loop, не дольше `maxBatchesPerRun`, логирует
    исключения, чтобы scheduler не умер.
- **`ChatTurnRecoveryService`**
  - Основные методы и взаимодействия — `recoverExpiredBatch()` — одна транзакция, один батч `SKIP LOCKED`, потом
    quota/audit/metrics.
- **`ChatTurnReservationService`**
  - Основные методы и взаимодействия — `reserveOrReplay` — основная reservation-транзакция. Private `resolveExisting`,
    `recoverExpired`, `throwInProgress`, `findOwnedSession`, `requireArguments`. Связывает idempotency, историю, model
    governance, квоту, аудит, sealed AI request и rate limit.

### 5.10. `title/` — названия чатов (3 типа)

- **`ChatTitlePolicy`**
  - Методы и гарантии — `normalizeCreationTitle`, `normalizeStoredTitle`, `normalizeManualTitle`, `automaticTitle`.
    Private `normalizeExplicitTitle`, `validateUnicodeAndControls`, `normalizeWhitespace`, `firstUsefulSentence`,
    `truncateAutomaticTitle`. До 255 Unicode code points при ручном вводе и до 72 при автоматическом; убирает
    Markdown-маркеры и приветствия, старается сохранить осмысленное первое предложение/границу слова. Вызовов LLM нет.
- **`ChatTitleService`**
  - Методы и гарантии — `rename` — transaction + advisory mutex, ownership check, нормализация, идемпотентный no-op для
    неизменённого USER title, переход provenance в USER и аудит длин; `codePointLength` — подсчёт code points.
- **`ChatTitleSource`**
  - Методы и гарантии — `DEFAULT` (плейсхолдер), `AUTO` (производный заголовок), `USER` (явное действие человека).

## 6. Что сделано хорошо

- ****Два уровня координации Redis + PostgreSQL****
  - Почему это правильно — Redis снижает конкурентную нагрузку, но ошибку/истечение его TTL компенсирует DB fencing.
- ****Отдельные reservation/finalization транзакции****
  - Почему это правильно — Нет удержания строки/соединения БД на весь внешний HTTP-вызов AI.
- ****`processingToken` и conditional UPDATE****
  - Почему это правильно — Старый узел не должен перезаписать итог более нового состояния turn.
- ****`AMBIGUOUS` вместо слепого ретрая****
  - Почему это правильно — Защищает от повторного платного вызова, когда факт выполнения неизвестен.
- ****V67/V72 physical STARTED evidence и auth epoch****
  - Почему это правильно — Факт старта и актуальная эпоха организации проверяются на границе БД; старый запрос не должен
    получить право начать физическую операцию после revoke.
- ****Immutable governance/route evidence****
  - Почему это правильно — Физическую модель, envelope и provenance нельзя незаметно заменить подготовленным
    RAG-текстом.
- ****Idempotency hash включает knowledge-scope****
  - Почему это правильно — Тот же текст с другой базой знаний не считается тем же запросом.
- ****Fail-fast configuration validation****
  - Почему это правильно — Неверные лимиты/TTL/cron останавливают запуск, а не дают тихо работать в неопределённой
    конфигурации.
- ****Многоуровневая валидация entity + SQL/Flyway****
  - Почему это правильно — Доменная логика и БД проверяют разные классы нарушений.
- ****Проверка tenant ownership и soft archive****
  - Почему это правильно — Персональные данные и история не доступны по одному знанию чужого UUID.
- ****Audit без текста сообщений****
  - Почему это правильно — Меньше риск раскрыть промпты и персональные данные в журналах аудита.
- ****Метрики без высококардинальных labels****
  - Почему это правильно — Не создают серии с уникальными user/chat/turn ID.
- ****`SKIP LOCKED` и батчирование восстановления****
  - Почему это правильно — Несколько узлов могут конкурентно забирать работу без последовательного сканирования всех
    записей.
- ****Title provenance****
  - Почему это правильно — Ручное название пользователя не должно перезаписываться автоматическим; V58 фиксирует это в
    БД.
- ****В проекте есть модульные и PostgreSQL integration test классы****
  - Почему это правильно — Реальный старт для регрессионной защиты, хотя само наличие файлов не означает, что все
    проверки прошли сейчас.

## 7. Findings: критично, серьёзно, улучшения

**Приоритет:** P0 — немедленно блокирует безопасную эксплуатацию конкретного контракта; P1 — высокая вероятность ошибок
в production / нарушение объявленной гарантии; P2 — важная надёжность/сопровождение; P3 — косметика. Здесь *P0* указан
только для случаев, когда система заявляет именно **жёсткий денежный лимит**, а не best-effort estimate.

### F-01–F-03 — действующие резервы, качество учёта и автоназвание

Штатный ход создаёт резерв V2 по неизменяемому route decision. Денежный предел использует
проверенную схему тарифа и сведения о физических попытках.
`ChatQuotaRepository` читает `usageUnverifiable` и `costUnverifiable`. Неопределённый
расход блокирует последующие запросы при соответствующем строгом лимите.
Первое принятое сообщение задаёт автоназвание только для чата с источником заголовка `DEFAULT`.

### F-04 — время PostgreSQL и JVM

`ChatDatabaseClockRepository.now()` читает `clock_timestamp()`. В рабочей конфигурации
`ChatQuotaService` и `ChatTurnFinalizationService` используют время PostgreSQL.
Проверки физического `STARTED` также выполняются в БД. Приёмочные сценарии должны
проверить продление lease, recovery и финализацию при смещении часов между репликами.

### F-05 — P1/P2. Неопознанные ошибки до STARTED попадают в ветку AMBIGUOUS

**Факт:** `ChatTurnExecutionCoordinator.invokeProvider()` оборачивает **также** `requestedPhysicalModel`
/`targetFor(model)` и вызов
`AiExecutionService.execute()` общим `catch (RuntimeException)` → `markAmbiguousQuietly()`. Однако
`ChatTurnRepository.markAmbiguous()`
требует `provider_call_started_at IS NOT NULL`.

**Последствие:** если исключение возникает **до фиксации STARTED** (например, lookup модели/проверка execution binding),
попытка пометить
AMBIGUOUS изменит 0 строк, ошибка сохранения будет подавлена, исходный `PROCESSING` останется до recovery. Клиент
получит `AI_OUTCOME_AMBIGUOUS`
даже при физически не начатом запросе. После recovery результат может стать `FAILED`, что создаст временно
противоречивые состояния.

**Что сделать:** выделить `pre-admission` и `post-STARTED` фазы, не пытаясь определять их только по классу
Java-исключения. Факт STARTED
читать из **зафиксированного DB evidence**, а до STARTED использовать `failBeforeProviderCall` при доказанном отсутствии
physical attempt.
При невозможности доказать — безопасно удерживать unknown state до reconciler, не объявляя факт provider execution.
Тестировать сбои `targetFor`,
binding, INSERT STARTED, сеть и response parsing раздельно.

### F-06 — P1/P2. Длительный внешний вызов и полная корректность watchdog при завершении

**Факт:** `ChatTurnLeaseService` продлевает lease планировщиком с JVM-временем; после терминального `markSucceeded`
watchdog может
выполнить ещё один scheduled tick, получить `updated=0` и зарегистрировать `ownership.loss`, хотя завершение штатное.
`ensureValid()`
проверяет локальный `AtomicBoolean` и не гарантирует, что lease всё ещё действителен в ту же наносекунду; реальную
защиту обеспечивает только БД.

**Что сделать:** не считать каждый `updated=0` системным инцидентом без классификации terminal/superseded/expired; по
возможности
останавливать watch перед финализацией, **не нарушая защиту** в момент commit; следить за нагрузкой write-renewal и
задержками scheduler.
Приоритет F-04 выше.

### F-07 — P1/P2. Резервирование Redis rate limit не транзакционно с PostgreSQL

**Факт:** `rateLimitService.checkAiMessageAllowed()` запускается последним внутри DB-транзакции, после
`validateAllowedTurnLinkBeforeExternalSideEffects()`, но **до фактического commit** Spring `@Transactional`.

**Последствие:** откат транзакции из-за deadlock, constraint на commit, потери соединения и т. п. может оставить
потреблённый слот
Redis без сохранённого `ChatTurn`. Принудительная проверка одного deferred constraint существенно уменьшает окно, но
**не делает две системы атомарными**.

**Что сделать:** либо документировать приемлемую модель «небольшое переучитывание rate limit» (обычно терпимо для
abuse-rate-limit),
либо внедрить reserve/confirm/cancel с compensation/idempotent key. Не пытаться добиться фиктивной распределённой
транзакции.

### F-08 — P2. Несовпадающие часы финансового периода

**Факт:** `ChatQuotaProperties.periodZone` допускает любую `ZoneId`; `ChatQuotaService.reserve()` вычисляет `YearMonth`
в ней.
Отдельный financial read model `model_cost_commitment_ledger_v60` построен вокруг **UTC**.

**Последствие:** отчётный месяц, месяц hard quota и месяц физического spend могут расходиться на границе суток. Это не
обязательно ошибка,
если два периода специально разные, но такой контракт должен быть явным.

**Что сделать:** централизовать определения *quota month*, *ledger month* и *billing month*, либо документировать
обоснованное различие,
добавить тесты пересечения `UTC ± offset`, начала месяца, DST.

### F-09 — P2. Проверка денежного формата в assistant entity неполная

**Факт:** `ChatMessageEntity.validatePricing()` проверяет `costUsd >= 0` и `scale <= 12`, но не ограничивает
**18 цифр слева** для `numeric(30,12)`.
`ChatQuotaProperties` такую проверку содержит, то есть правила разошлись.

**Последствие:** ошибка обнаружится только при записи в БД, уже после получения ответа провайдера; корректный response
превратится в проблему
финализации и потенциальную AMBIGUOUS.

**Что сделать:** общая domain validation/нормализация валютного значения с точным соответствием SQL type и pricing
provider,
включая `precision`, `scale`, rounded upper-bound versus actual cost.

### F-10 — P2. Форматы ошибок двух контроллеров и полей различаются

**Факт:** `ChatApiExceptionHandler` ограничен `assignableTypes=ChatController.class`, а `ChatTitleController`
обслуживается global advice.
Инфраструктурные исключения возвращают `ApiErrorResponse`, turn-specific — `ChatErrorResponse`.

**Последствие:** frontend обязан корректно поддерживать разные error DTO; переименование и отправка сообщения могут
давать ошибки с разными
кодами/структурой. Не найдено доказательства прямой уязвимости, но контракт хрупкий.

**Что сделать:** зафиксировать единый REST error envelope для новых API (или адаптер без breaking change), покрыть
status/code/retryAfter
контрактными тестами с frontend. Сохранять legacy до завершения rolling deployment.

### F-11 — отзыв доступа во время обработки

Перед вызовом модели и после сохранения результата сверяются активность и обе версии доступа
из principal. Сохранённая попытка и учёт расхода остаются доступны для финансовой сверки.
Предварительная проверка пользователя выполняет чтение состояния. Миграция V72 дополнительно
сериализует изменение версии организации с записью физического STARTED.

### F-12 — P2/P3. Сложность сопровождения и перегрузка классов

**Факт:** `ChatTurnReservationService` и `ChatTurnExecutionCoordinator` объединяют множество стратегий/портов; у
`ChatTurnReservationService`
длинный constructor. В `ChatService` coordinator создаётся вручную через `new` (без отдельной Spring AOP-границы; сейчас
транзакции на нём и не нужны).
В коде сохранены deprecated legacy overloads.

**Что сделать:** не проводить массовый рефакторинг вслепую. После закрытия F-01–F-05 выделять независимые компоненты по
устойчивым границам
(request admission, quota, RAG policy, failure classification) только при сохранении транзакционных инвариантов и с
регрессионными тестами. Legacy API удалять лишь после проверки всех тестовых и продовых потребителей.

### F-13 — P2. Возможные пограничные случаи в нормализации и API validation

**Факты:** `SendMessageRequest.@Size(max=100000)` проверяет **сырой** Java String, а `ChatContentNormalizer` — уже
преобразованные переводы
строк и конфигурируемый лимит; `ChatTitlePolicy` считает Unicode code points, `maxMessageChars` — UTF-16 units (и это
прямо обозначено). `ChatPageRequest` валидация `@Valid @ModelAttribute` зависит от корректной настройки MVC Bean
Validation.

**Что сделать:** документировать именно это различие, проверить server/frontend на emoji и surrogate pairs, `CRLF` при
100k, невалидный UUID,
отрицательные page/size, invalid sort/direction, extreme page offset. Не менять семантику лимитов без API-миграции.

### F-14 — P2. Поведение архивирования при in-flight turn нужно зафиксировать

**Факт:** `ChatService.sendMessage()` проверяет активную сессию до запуска; финальный
`ChatSessionRepository.touchOwned()` не проверяет `archivedAt IS NULL`. Архивирование может произойти после reservation,
до финализации.

**Последствие:** ранее принятый запрос может закончиться и сохранить ответ уже в архивированный чат. Это может быть
допустимой бизнес-политикой,
но она неочевидна.

**Что сделать:** решить контракт: архивирование запрещает новые turn, но разрешает завершение уже начатых; или отменяет
выдачу/завершение.
Тестировать последовательность `reserve → archive → provider response` без потери audit/quota evidence.

### Что **не** следует считать дефектами без дополнительных данных

- `@Transactional(REQUIRES_NEW)` на `markAmbiguousAfterPersistenceFailure` **не теряется** из-за self-invocation:
  внешний вызов через Spring bean попадает на аннотированный метод, после чего внутренний `markAmbiguous()` выполняется
  в уже открытой новой транзакции.
- `ChatTurnMutexRepository` использует 64-bit hash UUID для **сериализации**, не для идентификации объекта/безопасности.
  Коллизия приведёт к избыточной сериализации, а не автоматически к смешиванию tenant-данных.
- Отсутствие явного `save(session)` после `session.touch(now)` внутри активной JPA-транзакции само по себе не ошибка:
  dirty checking сохранит изменение, если entity остаётся managed.
- Необходимость `ChatLockService` одновременно с DB mutex не означает дублирование бизнес-корректности: они выполняют
  разные задачи и имеют разные области действия.
- `costVerifiable=false` сам по себе не значит, что уже произошло списание неверной суммы: это сигнал
  **отсутствия доказанного upper bound** и повод изменить правила hard quota.

## 8. DB constraints и миграции, от которых зависит безопасность

- **`V14__chat_turn_idempotency_and_integrity.sql`**
  - Что подтверждено — Базовая схема идемпотентности и constraints.
- **`V32__chat_turn_state_machine.sql`, `V33__chat_turn_indexes.sql`, `V35__chat_turn_composite_integrity.sql`**
  - Что подтверждено — Эволюция state machine, индексов и composite integrity; точные constraints нужно подтверждать
    против живой схемы, а не выводить только из имён.
- **`V43__archive_chat_sessions.sql`**
  - Что подтверждено — Схема soft archive.
- **`V45__model_control_plane.sql`**
  - Что подтверждено — Durable decision/evidence для маршрутизации.
- **`V58__chat_title_lifecycle.sql`**
  - Что подтверждено — `title_source`, CHECK provenance, historical title backfill, событие `CHAT_RENAMED`.
    **Не запускает** Java automatic naming новых turn.
- **`V60__monthly_cost_commitment_ledger.sql`**
  - Что подтверждено — UTC read model физического расхода с явным `unknown_cost_count`.
- **`V67__chat_execution_quota_and_evidence_hardening.sql`**
  - Что подтверждено — Immutable terminal turn/message guards, physical-attempt admission, V1/V2 quota reservation
    fields, `chat_physical_spend_v67`, DB time function.
- **`V72__organization_auth_epoch_physical_attempt_gate.sql`**
  - Что подтверждено — Guard auth-version в момент reservation и при физическом STARTED с блокировкой organization row;
    разрешает завершать уже допущенную физическую попытку.

**Важно:** Flyway-миграции, уже применённые к окружениям, нельзя редактировать задним числом. Для исправлений нужны
**новые** миграции,
обновление JPA/JDBC и migration/integration regression suite.

## 9. Тестирование: что есть и что добавить

### Структура тестов

```text
chat/
├── config/
│   └── ChatConfigurationPropertiesTest.java
├── controller/
│   ├── ChatControllerContractTest.java
│   └── ChatControllerSecurityTest.java
├── dto/
│   └── ChatDtoContractTest.java
├── entity/
│   ├── ChatEntityInvariantTest.java
│   └── ChatTurnOrganizationEpochTest.java
├── exception/
│   ├── ChatExceptionAdviceMvcTest.java
│   └── ChatInfrastructureExceptionHandlerTest.java
├── integration/
│   ├── AbstractChatPostgresIntegrationTest.java
│   ├── ChatConcurrencyIntegrationTest.java
│   ├── ChatDatabaseStateConstraintIntegrationTest.java
│   ├── ChatExplainPlanIntegrationTest.java
│   ├── ChatHistoryIntegrationTest.java
│   ├── ChatIntegrationClockConfiguration.java
│   ├── ChatLegacyUpgradeMigrationIntegrationTest.java
│   ├── ChatMigrationIntegrityIntegrationTest.java
│   ├── ChatTenantIsolationIntegrationTest.java
│   ├── ChatTurnRecoveryIntegrationTest.java
│   ├── ChatTurnRepositoryTransactionIntegrationTest.java
│   ├── ChatTurnStateMachineIntegrationTest.java
│   └── ChatUsageQualityIntegrationTest.java
├── observability/
│   └── ChatMetricsTest.java
├── service/
│   ├── AiHistoryBuilderTest.java
│   ├── ChatContentNormalizerTest.java
│   ├── ChatLockServiceTest.java
│   ├── ChatProcessingContextTest.java
│   ├── ChatProcessingContextTestFixtures.java
│   ├── ChatQuotaServiceTest.java
│   ├── ChatServiceTest.java
│   ├── ChatTurnFinalizationServiceTest.java
│   ├── ChatTurnLeaseServiceTest.java
│   ├── ChatTurnRecoverySchedulerTest.java
│   ├── ChatTurnRecoveryServiceTest.java
│   └── ChatTurnReservationServiceTest.java
├── testsupport/
│   └── ChatTestFixtures.java
└── title/
    ├── ChatSessionTitleLifecycleTest.java
    └── ChatTitlePolicyTest.java
```

`ChatServiceTest` проверяет отказ до обращения к модели и отзыв доступа во время ожидания ответа
от тестового провайдера. При позднем отзыве финализация предшествует отказу в выдаче.

`ChatTurnStateMachineIntegrationTest` содержит сценарии для смены версии пользователя или
организации после физического старта, отказа `STARTED` при устаревшей версии организации
и блокировки следующего платного запроса при неизвестном расходе.

В последнем прогоне прошли 22 кейса `ChatServiceTest`. PostgreSQL-сценарии скомпилировались.
Testcontainers не нашёл Docker, поэтому результаты проверки с PostgreSQL ещё предстоит получить.

### Приёмочные сценарии

- **T-01**
  - Сценарий — 30 конкурентных запросов в один чат с разными ключами
  - Ожидаемый результат — Ровно один допустимый `PROCESSING`; нет двойного физического STARTED.
- **T-02**
  - Сценарий — Повтор одного `clientRequestId` с тем же текстом и scope
  - Ожидаемый результат — Один physical attempt, replay терминального результата.
- **T-03**
  - Сценарий — Тот же ключ с другим текстом/KB/mode
  - Ожидаемый результат — `409 IDEMPOTENCY_KEY_REUSED`; ни нового провайдера, ни квоты.
- **T-04**
  - Сценарий — Redis TTL истёк; старый processor продолжает работу
  - Ожидаемый результат — Fencing DB отвергает устаревшую запись.
- **T-05**
  - Сценарий — Разный `Clock` у приложения и PostgreSQL: `±30 s / ±5 min`
  - Ожидаемый результат — Lease/recovery не делают ложных выводов из skew; DB остаётся authoritative.
- **T-06**
  - Сценарий — Произошёл сбой **до** STARTED и после STARTED
  - Ожидаемый результат — До — достоверный failure; после — appropriate AMBIGUOUS без blind retry.
- **T-07**
  - Сценарий — Цена модели выше статической конфигурации, несколько физических попыток
  - Ожидаемый результат — Hard quota использует доказанный расход/upper bound, не фиксированный guess.
- **T-08**
  - Сценарий — Успешный provider response с `PARTIAL/MISSING` usage и `UNPRICED`
  - Ожидаемый результат — Unknown flags остаются true; соблюдается fail-closed policy.
- **T-09**
  - Сценарий — Резервирование последней доступной доли лимита с двух узлов
  - Ожидаемый результат — Суммарный actual+reserved не перескакивает hard quota.
- **T-10**
  - Сценарий — Отмена/ошибка после Redis rate-limit, до commit
  - Ожидаемый результат — Явно протестирован accounting gap / compensation.
- **T-11**
  - Сценарий — Новый DEFAULT чат получает первое принятое USER-сообщение
  - Ожидаемый результат — Название автоматически установлено; при rollback не установлено.
- **T-12**
  - Сценарий — `rename(USER)` конкурентно с первым сообщением
  - Ожидаемый результат — Пользовательский заголовок при любом порядке событий не теряется.
- **T-13**
  - Сценарий — Первый заголовок: emoji, длинные слова, Markdown, unicode/control chars
  - Ожидаемый результат — Code-point bound, валидный Unicode, детерминированный результат.
- **T-14**
  - Сценарий — `reserve → organization disable → STARTED`
  - Ожидаемый результат — V72 отвергает физическую попытку; provider не вызывается.
- **T-15**
  - Сценарий — `STARTED → disable → re-enable → response`
  - Ожидаемый результат — Поведение выдачи ответа соответствует auth-epoch policy, а ledger сохраняется.
- **T-16**
  - Сценарий — `reserve → archive → response`
  - Ожидаемый результат — Документированный контракт архивирования соблюдён.
- **T-17**
  - Сценарий — Recovery 2+ репликами с `SKIP LOCKED`, retry аудита, сбой квоты
  - Ожидаемый результат — Turn+quota+audit переходят атомарно; нет двойной финализации.
- **T-18**
  - Сценарий — PostgreSQL `numeric(30,12)` на максимуме и чуть выше
  - Ожидаемый результат — Ошибка определяется предсказуемо до некорректной записи, без нарушения ledger.
- **T-19**
  - Сценарий — Старый frontend/backend во время rolling upgrade
  - Ожидаемый результат — Canonical/legacy endpoints, DTO JSON и error codes совместимы.
- **T-20**
  - Сценарий — Replay исторического V44/V45 и V72 null auth epoch
  - Ожидаемый результат — Исторические результаты читаются, новый физический вызов по ним не начинается.

### Команды для локального выполнения

Из корня проекта с Java 21 и Maven Wrapper:

```bash
cd backend
./mvnw -Dtest=ChatServiceTest,ChatQuotaServiceTest,ChatTurnReservationServiceTest test

# Для следующих команд требуется запущенный Docker.
./mvnw -Dtest=ChatTurnStateMachineIntegrationTest,ChatConcurrencyIntegrationTest test
./mvnw clean verify
```

## 10. Проверки перед выпуском

1. Прогнать полный набор тестов с Docker, включая новые SQL-сценарии, восстановление и конкурентные запросы.
2. На HTTPS-стенде проверить, что запрос со старой версией доступа не получает ответ после повторного включения
   организации или смены версии пользователя.
3. Сверить платный маршрут, резерв и фактические данные провайдера. Отдельно проверить случай, когда стоимость
   после `STARTED` неизвестна.
4. Проверить гонку ручного переименования с первым сообщением и контракт архивации во время активного хода.

## Связанные исходники и миграции

- [Исходники `chat` в репозитории](.)
- [`ChatQuotaService`](service/ChatQuotaService.java)
- [`ChatQuotaRepository`](repository/ChatQuotaRepository.java)
- [`ChatTurnLeaseService`](service/ChatTurnLeaseService.java)
- [`ChatTurnReservationService`](service/ChatTurnReservationService.java)
- [`ChatTurnExecutionCoordinator`](service/ChatTurnExecutionCoordinator.java)
- [`ChatTitlePolicy`](title/ChatTitlePolicy.java)
- [V58 — названия чатов](../../../../../resources/db/migration/V58__chat_title_lifecycle.sql)
- [V60 — месячный cost ledger](../../../../../resources/db/migration/V60__monthly_cost_commitment_ledger.sql)
- [V67](../../../../../resources/db/migration/V67__chat_execution_quota_and_evidence_hardening.sql)
- [V72](../../../../../resources/db/migration/V72__organization_auth_epoch_physical_attempt_gate.sql)
