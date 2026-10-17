# `common` — общая инфраструктура и контракты SafeAI Desk

> **Актуальность анализа:** 15 октября 2026 года.  
> **Основа:** присланные исходники `ru.safeai.gateway.common`, сопоставленные с публичным GitHub `Amid-1/Safeai-desk`, 
> ветка `main`, коммит `ec2596ed079423d7103fa3218b51a0ce6cd46df0` (PR #16, 11 октября 2026).  
> **Рекомендуемое расположение:** `backend/src/main/java/ru/safeai/gateway/common/README.md`.  
> **Статус:** техническая документация и статическое код-ревью, **не** свидетельство прохождения нового набора тестов, 
> полного security pentest или production-приёмки.

## 1. Краткий вывод

`common` — **внутренний инфраструктурный пакет (логический модуль) Java backend**, предоставляющий другим частям 
приложения общие средства: HTTP-контракт ошибок, общую идентичность и роли, защитные проверки конфигурации, определение 
IP клиента, `requestId`, безопасную пагинацию, классификацию ошибок PostgreSQL, advisory locks, UTC-время и валидацию Unicode.

**`common` не равен `auth`.** Авторизация и аутентификация — предмет модуля `auth`; общие понятия «роль», «субъект access 
JWT», «безопасный principal», «ошибка API» и «правило защиты production-конфигурации» обоснованно расположены в `common`. 
Это позволяет `auth`, `user`, `organization`, `chat`, `audit` и другим пакетам переиспользовать их **без циклических 
зависимостей**.

В репозитории один Maven backend `safeai-backend`: `common` и `auth` — **не самостоятельные Maven-модули, не отдельные 
сервисы и не приложения с собственным запуском**. Spring сканирует классы общего Java-приложения.

**Оценка на осень 2026:** архитектура и API в целом соответствуют современному стеку Java/Spring; перед выпуском т
ребуется подтвердить несколько специфичных рисков реальными интеграционными проверками. По одному исходному коду **не 
установлена воспроизводимая критическая уязвимость**. При этом «все тесты проходят» нельзя приравнивать к «реальная сеть, 
пул JDBC, прокси и production полностью безопасны».

## 2. Архитектура и правила зависимостей

```text
                        ┌───────────────────────┐
                        │  auth / user / chat   │
                        │  organization / audit │
                        │  ai / knowledge / ... │
                        └──────────┬────────────┘
                                   │ используют
                                   ▼
                     ru.safeai.gateway.common
                     ├── config
                     ├── exception
                     ├── pagination
                     ├── persistence
                     ├── platform
                     ├── security
                     └── validation

           common ─────X─────> auth, user, organization и прочие модули
```

Существующий `CommonDependencyArchitectureTest` (ArchUnit — инструмент тестирования архитектуры Java-классов) запрещает 
классам `ru.safeai.gateway.common..` зависеть от классов других пакетов `ru.safeai.gateway..`. Это **архитектурное правило, 
а не просто соглашение из README**.

Смысл распределения обязанностей:

| Область | Владелец | Почему |
|---|---|---|
| Значения системных ролей и Spring authorities | `common.security` | Нужны и аутентификации, и проверке пользователей, и прочим сервисам |
| Данные субъекта JWT и общее представление principal | `common.security` | Общие контракты без зависимости от БД и `auth.service` |
| Выдача JWT, проверка access/refresh token, логин, logout, сессии | `auth` | Это сценарии и состояние конкретной подсистемы аутентификации |
| Состояние пользователя и его роль в БД | `user` | Это бизнес-данные пользователя |
| Состояние организации и tenant isolation | `organization` и сервисы-потребители | Это бизнес-правила организаций |
| Унифицированный JSON ошибок и fallback Spring MVC | `common.exception` | Общий публичный HTTP-контракт |
| Специализированные ошибки чата, входа, лимитов | Соответствующие модули | Их предметная область не должна переезжать в `common` |
| Защита конфигурации общего приложения | `common.security` | Относится ко всему backend, не только ко входу |

**Не переносить всё `common.security` в `auth`:** это заставит базовые пользовательские и организационные модули зависеть 
от реализации подсистемы входа либо породит дополнительные интерфейсы только ради обхода циклов.

## 3. Технологии и подходы

| Технология / подход | Где используется | Оценка на октябрь 2026 |
|---|---|---|
| **Java 21 LTS** | `record`, `sealed`-совместимые API, `List.getFirst`, pattern matching, неизменяемые коллекции | Современная поддерживаемая LTS. Java 25 — более новая LTS, но переход не требуется ради модернизации одного пакета |
| **Spring Boot 4.1.1 / Spring Framework 7** | DI, properties binding, MVC, scheduling, conditional beans, error handling | Актуальная современная линия; версии взяты из текущего `pom.xml` |
| **Spring Security 7 / Servlet filters** | `SecurityFilterChain`, `UserDetails`, bearer challenge, security headers | Правильный современный подход для servlet-based backend |
| **Jakarta Servlet, Jakarta Validation, Jakarta Persistence** | HTTP-фильтры, validation, optimistic lock | Актуальное пространство имён `jakarta.*` |
| **JSpecify** | `@NullMarked`, `@NullUnmarked`, `@Nullable` | Современный способ формализовать nullable-контракты; одного наличия аннотаций недостаточно для полной статической проверки |
| **Jackson 3** | `tools.jackson.databind.json.JsonMapper` | Актуальный для Spring Boot 4 вариант сериализации |
| **PostgreSQL / JDBC / SQLSTATE** | Блокировки и классификация ошибок ограничений | Подходит для транзакционных гарантий; требует проверки с реальным драйвером и пулом |
| **SLF4J MDC** | `requestId` в контексте логирования | Стандартная практика; контекст не распространяется автоматически на любой новый поток |
| **ArchUnit, JUnit 5, AssertJ, Mockito, Testcontainers** | Архитектурные, unit- и интеграционные тесты | Уместные инструменты; для сетевых и JDBC edge cases важны реальные интеграционные тесты |
| **`Clock` dependency injection** | Детерминированное время | Хорошая практика, особенно для тестов |
| **Fail-fast / fail-closed** | Конфигурация production и обработка неоднозначных ошибок | Правильное направление для security-critical компонентов |

**Замечание про Java 21:** Oracle изменила условия для обновлений своего JDK 21 после сентября 2026 года. Это **не означает**, 
что Java 21 небезопасна либо что нужно срочно переписывать код на Java 25. Надо отдельно выбрать поддерживаемый дистрибутив JDK, 
отслеживать обновления и учитывать его лицензию.

## 4. Состав модуля

По `main` на указанном коммите: **36 Java-файлов в семи пакетах**, включая `security/package-info.java` (файл аннотаций пакета, 
а не самостоятельный класс). В `exception/` также есть собственный `README.md`, его не следует удалять или подменять этим документом.

```text
common/
├── config/
│   ├── SchedulingConfiguration.java
│   └── TimeConfiguration.java
├── exception/
│   ├── ApiErrorCode.java
│   ├── ApiErrorResponse.java
│   ├── ApiErrorResponseFactory.java
│   ├── ApiErrorResponseWriter.java
│   ├── ApiException.java
│   ├── ApiExceptionResponseSupport.java
│   ├── BadRequestException.java
│   ├── ConflictException.java
│   ├── ForbiddenOperationException.java
│   ├── GlobalExceptionHandler.java
│   ├── ResourceNotFoundException.java
│   ├── ValidationErrorExtractor.java
│   └── README.md                   # Уже существует, специализированная документация
├── pagination/
│   └── StablePageableNormalizer.java
├── persistence/
│   ├── DatabaseConstraintClassifier.java
│   └── PostgresAdvisoryLockExecutor.java
├── platform/
│   ├── PlatformProperties.java
│   └── PlatformPropertiesConfiguration.java
├── security/
│   ├── AccessTokenSubject.java
│   ├── ClientIpProperties.java
│   ├── ClientIpResolver.java
│   ├── CorsProperties.java
│   ├── JwtProperties.java
│   ├── ManagementSecurityConfig.java
│   ├── NonProductionEnvironmentCondition.java
│   ├── ProductionEnvironmentCondition.java
│   ├── ProductionSecurityInvariantValidator.java
│   ├── RequestIdFilter.java
│   ├── RoleAuthorityMapper.java
│   ├── SafeAiUserPrincipal.java
│   ├── SecurityIdentityValidator.java
│   ├── SecurityPropertiesConfiguration.java
│   ├── SystemRole.java
│   └── package-info.java
└── validation/
    └── UnicodeValidationSupport.java
```

---

## 5. `common.config` — общая конфигурация

### 5.1. `SchedulingConfiguration`

**Для чего:** подключает выполнение фоновых задач с `@Scheduled` через `@EnableScheduling`. Аннотация `@Configuration(proxyBeanMethods = false)` 
позволяет Spring не создавать прокси конфигурационного класса без необходимости.

**Состав:** публичный класс без собственных прикладных методов; поведение задаётся аннотациями. Число потоков берётся из конфигурации 
`spring.task.scheduling.pool.size`; здесь scheduler вручную не создаётся.

**Гарантии и пределы:** `ProductionSecurityInvariantValidator.validateSchedulerPool()` требует в production не менее двух потоков. 
Это защищает от полного голодания планировщика, когда одна медленная задача занимает единственный поток, **но не обеспечивает distributed lock**. 
В нескольких экземплярах backend задача выполняется конкурентно; сама задача должна быть безопасной либо использовать распределённую блокировку. 
В документации приведён пример cleanup с `FOR UPDATE SKIP LOCKED` и `REQUIRES_NEW`.

### 5.2. `TimeConfiguration`

**Для чего:** регистрирует единый `Clock` для сервисов, которым нужно текущее время.

- `clock()` — Spring `@Bean`, возвращает `Clock.systemUTC()`. В тестах этот bean можно заменить фиксированными часами (`Clock.fixed(...)`).

**Почему хорошо:** воспроизводимые тесты и отсутствие скрытой зависимости бизнес-кода от `Instant.now()`; UTC — подходящий 

стандарт серверного времени.

## 6. `common.exception` — единый контракт HTTP-ошибок

### 6.1. Общий поток ошибок

```text
Controller / Service
  ├── domain exception ──────> module-specific @RestControllerAdvice
  └── framework/unknown ────> GlobalExceptionHandler (последний fallback)
                                        │
                              ApiExceptionResponseSupport
                                        │
                              ApiErrorResponseFactory
                                        │
                              ApiErrorResponse (JSON)

SecurityFilterChain (ошибка ещё до контроллера)
  └── AuthenticationEntryPoint / AccessDeniedHandler
       └── ApiErrorResponseWriter ──> ApiErrorResponseFactory ──> JSON
```

Это принципиальное разделение: `@RestControllerAdvice` не перехватывает все ошибки внутри Spring Security filter chain. 
Для последних нужен отдельный writer.

Пример ответа (иллюстрация формата, не результат реального вызова):

```json
{
  "timestamp": "2026-10-15T12:00:00Z",
  "status": 409,
  "error": "CONFLICT",
  "message": "Конфликт данных",
  "path": "/api/example",
  "requestId": "f8614f28-c61e-47a7-a069-d08998624234",
  "fieldErrors": {}
}
```

### 6.2. `ApiErrorCode` — стабильные машинные коды

Enum без методов; его значения входят во **внешний API-контракт**, поэтому их переименование — изменение совместимости с 
frontend и клиентами API.

| Группа | Значения |
|---|---|
| Общие HTTP | `BAD_REQUEST`, `VALIDATION_ERROR`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `CONFLICT`, `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`, `NOT_ACCEPTABLE`, `INTERNAL_SERVER_ERROR` |
| Безопасность | `TOKEN_REVOKED`, `AUTH_SERVICE_UNAVAILABLE`, `EXPIRED_REFRESH_TOKEN`, `INVALID_REFRESH_TOKEN` |
| Конкурентность/версии | `USER_VERSION_CONFLICT`, `ORGANIZATION_VERSION_CONFLICT`, `CHAT_BUSY`, `CHAT_LOCK_UNAVAILABLE` |
| Rate limit | `RATE_LIMIT_EXCEEDED`, `RATE_LIMIT_UNAVAILABLE` |
| AI | `AI_EXECUTION_RECONCILIATION_REQUIRED`, `AI_PROVIDER_TIMEOUT`, `AI_PROVIDER_RATE_LIMITED`, `AI_PROVIDER_OVERLOADED`, `AI_PROVIDER_UNAVAILABLE`, `AI_PROVIDER_ERROR` |

**Особенно важно:** `AI_EXECUTION_RECONCILIATION_REQUIRED` — неоднозначное завершение операции провайдера. Повторять 
физический AI-вызов вслепую нельзя; клиент должен перечитать/опрашивать уже существующий `ChatTurn`.

### 6.3. `ApiErrorResponse` — неизменяемая форма ответа

`record` с компонентами `timestamp`, `status`, `error`, `message`, `path`, `requestId`, `fieldErrors`.

- **Канонический конструктор:** требует timestamp, валидный HTTP-статус 100–599, непустые код и сообщение; пустой путь 
- заменяет на `/`; пустой request ID превращает в `null`; нормализует поля валидации.
- `requireText(value, fieldName)` — проверка непустой строки + `trim`.
- `normalizeFieldErrors(source)` — нормализация имён полей, пропуск пустых списков, объединение ключей, совпавших после 
- очистки; возвращает неизменяемую карту.
- `normalizeMessages(source)` — удаление пустых сообщений и дубликатов с сохранением порядка.
- `mergeMessages(first, second)` — объединение списков без повторов.

**Контракт:** вызывающий код не может изменить `fieldErrors` или внутренние списки через возвращённые коллекции. Однако 
`record` сам по себе не гарантирует глубокую неизменяемость произвольных ссылочных типов: она здесь специально обеспечивается для `fieldErrors`.

### 6.4. `ApiErrorResponseFactory`

- **Конструктор** принимает `Clock`.
- `create(status, error, message, request, fieldErrors)` — берёт время из `clock.instant()`, путь из request URI, `requestId` 
- из серверного атрибута `RequestIdFilter.REQUEST_ID_ATTRIBUTE` и создаёт `ApiErrorResponse`.

**Плюс:** формат и время ошибки едины для MVC и security filters.

### 6.5. `ApiErrorResponseWriter`

**Назначение:** записывает JSON-ошибки непосредственно из цепочки servlet-фильтров; использует Jackson 3 `JsonMapper`.

- `write(request, response, status, error, message)` — обычный ответ без Bearer challenge.
- `writeBearerUnauthorized(request, response, error, message)` — 401 c `WWW-Authenticate: Bearer`, только для Bearer/resource-server сценария.
- `writeInternal(...)` — проверяет `isCommitted`, сохраняет только разрешённые заголовки, делает `response.reset()`, выставляет статус, 
- `Cache-Control: no-store`, UTF-8, JSON, сериализует ответ.
- `snapshotSafeHeaders(...)` — сохраняет разрешённый перечень correlation/CORS/security headers; если `X-Request-Id` отсутствует, 
- берёт его из серверного request attribute.
- `restoreSafeHeaders(...)` — возвращает сохранённые значения, включая многозначные заголовки.

**Почему это важно:** 401/403 не должны случайно наследовать чужой `Location`, `Set-Cookie`, `ETag` или чувствительные заголовки. 
Для cookie-authentication не нужно автоматически изображать OAuth2 Bearer-аутентификацию.

**Контрольная точка:** проверить, что разрешённый `X-Request-Id` никогда не подменяется downstream-кодом, если заголовок 
уже установлен (см. риски ниже).

### 6.6. `ApiException`

Базовое `abstract RuntimeException` для контролируемых ошибок. Хранит HTTP-статус, машинный код и **публичное** сообщение 
отдельно от **внутреннего** сообщения `Throwable.getMessage()`.

- Перегруженные `protected` конструкторы: три аргумента; плюс `cause`; либо отдельные `publicMessage`, `internalMessage`, `cause`.
- `getStatus()` — HTTP-статус.
- `getErrorCode()` — публичный `ApiErrorCode`.
- `getPublicMessage()` — безопасный текст для клиента.
- `requireMessage(...)` — обязательная непустая строка.

**Правило:** клиенту возвращается `getPublicMessage()`, а не техническая причина ошибки. Пароли, JWT, cookie и секреты 
запрещено вставлять даже во внутреннее исключение без необходимости.

### 6.7. `ApiExceptionResponseSupport`

Сервисный помощник для MVC advice; отличается от writer тем, что **строит `ResponseEntity`**, а не пишет в servlet response stream.

- `validationResponse(request, fieldErrors)` — 400 `VALIDATION_ERROR`, подставляет fallback-поле для пустого набора ошибок.
- `internalServerError(request)` — безопасный 500 с фиксированным общим текстом.
- `build(status, error, message, request, fieldErrors, headers)` — единообразный ответ, нужные HTTP headers, `Cache-Control: no-store`, фабрика.
- `codeForStatus(status)` — сопоставляет типовым статусам (`400`, `401`, `403`, `404`, `405`, `406`, `409`, `415`, `429`) 
- машинные коды; прочее → общий код по классу ошибки.
- `messageForStatus(status)` — дефолтные русскоязычные сообщения.
- `safeMessage(message, fallback)` — замена пустого текста на fallback; **не является санитайзером секретов**.
- `requestId(request)` — извлекает серверный ID либо строку `missing` для диагностических логов.

### 6.8. Базовые конкретные исключения

| Класс | Конструкторы / методы | Семантика |
|---|---|---|
| `BadRequestException` | `(publicMessage)`, `(publicMessage, cause)` | 400 `BAD_REQUEST` |
| `ConflictException` | `(publicMessage)`, `(publicMessage, cause)`, защищённый `(errorCode, publicMessage, cause)` | 409; производные классы могут специализировать код |
| `ForbiddenOperationException` | `(publicMessage)` | 403 `FORBIDDEN` |
| `ResourceNotFoundException` | `(publicMessage)` | 404 `NOT_FOUND` |

Эти типы не содержат бизнес-правил конкретной организации, пользователя или чата.

### 6.9. `GlobalExceptionHandler` — обработчик ошибок Spring MVC

`@RestControllerAdvice`, реализует `Ordered`. **`getOrder()` возвращает `LOWEST_PRECEDENCE`**, чтобы специальные обработчики 
других модулей имели приоритет.

| Метод | Что обрабатывает / результат |
|---|---|
| `getOrder()` | Приоритет глобального fallback |
| `handleBadRequest(...)` | `BadRequestException` → 400 |
| `handleResourceNotFound(...)` | `ResourceNotFoundException` → 404 |
| `handleConflict(...)` | `ConflictException` → 409 или заданный общий статус |
| `handleForbiddenOperation(...)` | `ForbiddenOperationException` → 403 |
| `handleAccessDenied(...)` | `AccessDeniedException` → 403 без технических подробностей |
| `handleApiException(...)` | Другие `ApiException` → объявленные статус/код/publicMessage |
| `handleOptimisticLock(...)` | `OptimisticLockException`, `OptimisticLockingFailureException` → 409, просьба обновить данные |
| `handleValidation(...)` | `MethodArgumentNotValidException` → field errors |
| `handleBindException(...)` | `BindException` → field errors |
| `handleMethodValidation(...)` | `HandlerMethodValidationException`; ошибочное **возвращаемое значение** контроллера → 500, ошибочный вход → 400 |
| `handleConstraintViolation(...)` | `jakarta.validation.ConstraintViolationException` → 400 |
| `handleUnreadableBody(...)` | Некорректный JSON/body → 400 |
| `handleMissingParameter(...)` | Отсутствующий request parameter → 400 |
| `handleMissingHeader(...)` | Отсутствующий request header → 400 |
| `handleTypeMismatch(...)` | Неверный тип параметра → 400 |
| `handleMethodNotAllowed(...)` | Неверный HTTP-метод → 405, с headers исключения (например, `Allow`) |
| `handleUnsupportedMediaType(...)` | Неподдерживаемый `Content-Type` → 415 |
| `handleNotAcceptable(...)` | Неподдерживаемый `Accept` → 406 |
| `handleNoResource(...)` | Не найден статический ресурс → 404 |
| `handleResponseStatusException(...)` | Статус из Spring; для 5xx не раскрывает exception reason |
| `handleExpectedAuthenticationFailure(...)` | Типовые ошибки credentials/account → 401 без раскрытия причины |
| `handleAuthenticationInfrastructure(...)` | Сбой authentication infrastructure → 503 `AUTH_SERVICE_UNAVAILABLE` |
| `handleUnexpectedAuthenticationFailure(...)` | Неожиданная authentication error → 500 |
| `handleDataIntegrityViolation(...)` | Неожиданная DB constraint/data integrity error → 500 |
| `handleUnexpected(...)` | Последний catch-all `Exception` → 500 |

**Отдельное правило:** `DataIntegrityViolationException` нельзя автоматически превращать в 409 во всём приложении. 
Неожиданное нарушение SQL-ограничения часто означает дефект или несогласованность данных; ожидаемые конфликты должны разбираться там, где понятен конкретный constraint.

### 6.10. `ValidationErrorExtractor`

Package-private помощник (`GlobalExceptionHandler`), выделяет структурированные ошибки проверки.

- `bindingErrors(bindingResult)` — собирает полевые и глобальные ошибки Spring binding.
- `addError(errors, field, message)` — нормализует имя и текст, не добавляет повторные сообщения.
- `parameterName(result)` — имя параметра, индекс или ключ элемента контейнера.
- `normalizeConstraintPath(path)` — собирает путь из узлов `PROPERTY`/`CONTAINER_ELEMENT` API Jakarta Validation, не зависит от `Path.toString()`.
- `defaultMessage(error)` — получает стандартное сообщение либо безопасный fallback.

**Замечание:** сейчас `handleBadRequest`/`handleResourceNotFound` получают `getMessage()`; в действующей реализации оно совпадает 
с публичным текстом этих простых исключений, но единообразнее использовать `getPublicMessage()` для сохранения базового контракта 
при будущих изменениях.

**Дополнительная документация:** `common/exception/README.md` уже содержит отдельное подробное описание HTTP error contract 
и специальных advice. Его сведения о старой ветке/коммите следует проверять по текущему `main`.

---

## 7. `common.pagination` — стабильные страницы и сортировка

### `StablePageableNormalizer`

**Задача:** дать endpoint контролируемую пагинацию, защититься от произвольных свойств `sort`, чрезмерно больших страниц 
и глубокого `OFFSET`, сохранить детерминированный порядок результатов.

- `DEFAULT_MAX_OFFSET = 1_000_000L` — совместимый верхний предел по умолчанию, **не обещание высокой производительности** на таком offset.
- `TieBreakerDirectionPolicy.DESCENDING` — обязательный tie-breaker всегда `DESC`.
- `TieBreakerDirectionPolicy.FOLLOW_LAST_SORT_DIRECTION` — повторяет направление последнего активного sort order.
- `normalize(pageable, maxPageSize, allowedSortProperties, defaultSort, tieBreakerProperty, policy)` — вариант с default max offset.
- `normalize(pageable, maxPageSize, maxOffset, allowedSortProperties, defaultSort, tieBreakerProperty, policy)` — основной алгоритм с явным лимитом.
- `validateOffset(pageNumber, pageSize, maxOffset)` — вычисляет `(long) pageNumber * pageSize`, отвергает слишком глубокий offset.
- `validateRequestedSort(sort, allowedSortProperties)` — разрешает только whitelist свойств.
- `containsProperty(sort, property)` — ищет поле tie-breaker среди уже заданных сортировок.
- `resolveTieBreakerDirection(sort, policy)` — выбирает направление стабилизирующего поля.

**Пример:** при сортировке по `createdAt DESC` два объекта могут иметь одинаковое время. Добавление `id DESC` обеспечивает однозначный порядок.

**Ограничение:** `OFFSET` на больших таблицах всё равно дорогой. Для истории сообщений, аудита и иных крупных выборок предпочтительна 
cursor/keyset pagination с серверной проверкой cursor и tenant context. Этот helper её не реализует.

## 8. `common.persistence` — PostgreSQL

### 8.1. `DatabaseConstraintClassifier`

**Назначение:** определять известные конфликты БД не по тексту локализованного сообщения, а по PostgreSQL SQLSTATE и (для unique)
**конкретному имени ограничения**.

- `isUniqueViolation(throwable, acceptedConstraintNames...)` — проверяет `23505` + точное имя допустимого уникального ограничения; 
- при неизвестном имени возвращает `false`.
- `isForeignKeyViolation(throwable)` — ищет SQLSTATE `23503`.
- `normalizeConstraintNames(...)` — требует непустые имена без внешних пробелов, терпит дубли.
- `hasSqlState(throwable, expectedSqlState)` — поиск SQLSTATE в дереве причин/следующих SQL-исключений.
- `findConstraintName(throwable)` — извлекает имя из Hibernate `ConstraintViolationException` либо PostgreSQL metadata.
- `findPostgresConstraintName(exception)` — рефлексия `getServerErrorMessage().getConstraint()` без compile-time зависимости `common` 
- от pgJDBC (драйвер в POM имеет `runtime` scope).
- `throwableChain(throwable)` — проходит `cause` и `SQLException.nextException`, отслеживая уже посещённые объекты, чтобы не зациклиться.

**Почему хорошо:** SQLSTATE устойчивее, чем парсинг строки вида `duplicate key`; whitelist constraint помогает не маскировать неожиданные 
нарушения как бизнес-конфликт.

**Риск:** поиск кода SQLSTATE и поиск имени ограничения выполняются двумя независимыми проходами. В экзотической цепочке `SQLException` 
они теоретически могут совпасть **на разных исключениях**, создав ложное соответствие. Требуется отдельный регрессионный тест и, если 
воспроизводится, атомарная проверка кода + имени **на одном соответствующем исключении** (P2).

### 8.2. `PostgresAdvisoryLockExecutor`

**Назначение:** выполнять `Supplier<T>` только после успешного захвата **session-level PostgreSQL advisory lock** в той же 
JDBC session и затем отпускать его.

- `tryExecute(connection, lockKey, lockDescription, action)` — точка входа. Если блокировку получить нельзя, возвращает `LockExecution.notAcquired()`; 
- иначе выполняет callback, освобождает lock, возвращает результат.
- `tryAcquire(...)` — `SELECT pg_try_advisory_lock(?)` через `PreparedStatement`; при неопределённом результате пытается **прервать физическую сессию**.
- `releaseAfterActionFailure(...)` — освобождение lock при исключении callback, добавление ошибки cleanup как `suppressed`.
- `releaseOrAbort(...)` — освобождение lock после успешной операции либо аварийный abort.
- `release(...)` — `SELECT pg_advisory_unlock(?)`, проверяет серверное подтверждение `true`.
- `abortConnection(...)` — вызывает JDBC `Connection.abort(...)`; если abort не удался, пытается `close()`.
- `dataAccessFailure(...)` — создаёт `DataAccessResourceFailureException` для Spring DAO слоя.
- `LockExecution<T>` — record `acquired` + `result`; фабрики `notAcquired()` и `acquired(result)`.

**Ключевой нюанс:** session-level lock **не освобождается обычным `ROLLBACK`**. Он действует до явного unlock или завершения 
**PostgreSQL-сессии**. Это объясняет решение abort при неясном исходе запроса.

**P1 — проверить с реальным пулом соединений:** в двух существующих unit-тестах используются Mockito-моки `Connection`. 
Тест `abortFailureFallsBackToClose` удостоверяется лишь, что вызывается `close()`. Если реальный JDBC proxy при `close()` 
**возвращает соединение в pool, не закрывая физическую PostgreSQL session**, неопределённо захваченный advisory lock может 
пережить операцию. Нужен интеграционный тест с текущим пулом (Hikari), pgJDBC, двумя JDBC sessions, принудительной ошибкой 
acquire/unlock и проверкой `pg_locks`/повторного захвата. **Это потенциальный риск, не подтверждённая в текущем проекте 
эксплуатационная уязвимость.**

**План архитектуры:** если для конкретной задачи подходит transaction-level lock (`pg_try_advisory_xact_lock`), у него 
иная семантика завершения; менять существующий session-level контракт без аудита всех call sites нельзя.

## 9. `common.platform` — системная организация

### 9.1. `PlatformProperties`

`@ConfigurationProperties(prefix = "safeai.platform")` связывает конфигурацию с `UUID organizationId`.

- `DEFAULT_PLATFORM_ORGANIZATION_ID` — документированный системный UUID `00000000-0000-0000-0000-000000000001`.
- **Компактный конструктор record** — подставляет системный UUID, если `organizationId == null`.
- `organizationId()` — сгенерированный accessor record.

Это **параметр платформы**, не JPA-сущность и не реализация логики управления организацией.

### 9.2. `PlatformPropertiesConfiguration`

`@EnableConfigurationProperties(PlatformProperties.class)` — регистрирует binding properties в Spring. Собственных методов нет.

## 10. `common.security` — общие security-контракты и защита среды

### 10.1. `SystemRole`

Enum `SUPER_ADMIN`, `ADMIN`, `USER` — системный источник допустимых ролей.

- `roleName()` — имя роли без префикса (`ADMIN`).
- `authority()` — Spring authority с префиксом (`ROLE_ADMIN`).
- `parse(value)` — допускает нормализацию регистра и вход вида `ADMIN`, `role_admin`, `ROLE_ADMIN`; чужие роли отклоняет.

**Важно:** существующая модель предполагает **ровно одну системную роль на principal/access subject**. Переход к multi-role 
RBAC потребует отдельного изменения контрактов, JWT, UI, БД и тестов. Делать это автоматически не нужно.

### 10.2. `SecurityIdentityValidator` (package-private)

Внутренний validator согласованности безопасной идентичности:

- `requireCanonicalEmail(email)` — требует уже нормализованный lowercase email без внешних пробелов и длиной до 255 UTF-16 code units; 
- **это не полноценный валидатор RFC email**.
- `requireNonNegativeVersion(value, fieldName)` — проверка версий security state.
- `normalizeAuthorityNames(authorities)` — принимает ровно одну Spring authority, преобразует к каноническому `ROLE_*`.
- `normalizeRoleNames(roles)` — ровно одна системная роль без Spring-префикса, immutable result.
- `invalidEmail()` / `exactlyOneRoleRequired()` — типизированные причины нарушения инвариантов.

### 10.3. `AccessTokenSubject`

Java record — минимальный набор claims, на основании которого создаётся access JWT: `userId`, `organizationId`, `tokenVersion`, 
`organizationAuthVersion`, `roles`.

- **Канонический конструктор** — non-null идентификаторы, неотрицательные версии, ровно одна допустимая роль.
- Сгенерированные record accessors `userId()`, `organizationId()`, `tokenVersion()`, `organizationAuthVersion()`, `roles()`.

**Хорошее решение:** не включать email и прочую PII в access JWT без нужды. Поле `organizationAuthVersion` позволяет согласовать 
инвалидирование токенов при изменении security state организации. Здесь описаны только **claims**; фактическую проверку подписи 
и свежести состояния делает другой код, преимущественно `auth`.

### 10.4. `SafeAiUserPrincipal`

`final` адаптер к `Spring Security UserDetails` и `CredentialsContainer`.

- Приватный конструктор — проверяет UUID, canonical email при наличии, версии, authorities; формирует immutable списки.
- `passwordPrincipal(...)` — для credential-based login; временно содержит email и password hash.
- `accessTokenPrincipal(...)` — для JWT; не содержит email и password hash.
- `getEmail()` — nullable только там, где это ожидается.
- `authorityNames()` / `getAuthorities()` — роли/authorities.
- `getPassword()` — возвращает временный хеш либо `null` после очистки.
- `getUsername()` — email для password-auth, UUID для JWT-auth.
- `isEnabled()` — состояние principal.
- `eraseCredentials()` — обнуляет `passwordHash` после аутентификации.
- `toString()` — выводит только безопасные идентификаторы и роли, исключая email/hash.
- `createAuthorities(...)` — создаёт `SimpleGrantedAuthority` из канонических имён.
- Сгенерированные Lombok `@Getter`: `getId()`, `getOrganizationId()`, `getTokenVersion()`, `getOrganizationAuthVersion()`.

**Не путать:** этот principal — не таблица пользователей, не JPA-сущность, не проверка permissions в бизнес-методах и не 
самостоятельная авторизация.

### 10.5. `RoleAuthorityMapper`

- `toAuthorities(roles)` — перевод `USER` / `role_user` / `ROLE_USER` в immutable `Set<SimpleGrantedAuthority>` с `ROLE_`.
- `toRoleNames(authorities)` — обратное преобразование Spring authorities в список системных ролей.
- `requireAuthorityName(authority)` — reject null authority/null name.

Методы работают с коллекциями в общем виде, но модель `AccessTokenSubject`/`SafeAiUserPrincipal` отдельно требует одну роль: 
это **не противоречие**, а разные уровни контракта.

### 10.6. `JwtProperties`

`@ConfigurationProperties(prefix = "app.security.jwt")`, record параметров RS256 JWT:

- **Канонический конструктор** — `expirationMinutes` 1–60, HTTP(S) `issuer`, непустой `audience` до 255, валидный active 
- key ID, непустой key ring, обязательный private key только для активного ключа.
- `toString()` — скрывает материал ключей (`<redacted>`).
- `normalizeKeys(keys)` — проверяет элементы, уникальность ID, возвращает immutable список.
- `validateIssuer(value)` — требует абсолютный HTTP(S) URI без userInfo/query/fragment.
- `requireKeyId(...)` — разрешает ограниченный формат ID ключа.
- `requireText(...)` — непустое значение и отсутствие внешних пробелов.
- Вложенный `KeyEntry` record: конструктор нормализует `id`, публичную и необязательную приватную часть; собственный 
- `toString()` скрывает оба PEM-поля.

**Ротация:** предыдущие ключи могут оставаться в key ring только с public key; конкретное требование отсутствия лишнего 
private key дополнительно проверяет production-validator.

**Разделение:** `JwtProperties` хранит **конфигурационный контракт**, а не генерирует JWT. Настоящие signing/verification 
services — вне данного класса.

### 10.7. `SecurityPropertiesConfiguration`

`@EnableConfigurationProperties` регистрирует `JwtProperties`, `CorsProperties`, `ClientIpProperties`. Методов нет.

### 10.8. `CorsProperties`

`@ConfigurationProperties(prefix = "safeai.cors")` — allowlist точных browser origins для credentialed requests.

- Конструктор — вызывает `normalizeAllowedOrigins`.
- `normalizeAllowedOrigins(...)` — убирает пустые значения, дубликаты, возвращает immutable список.
- `normalizeOrigin(origin)` — строгая URI-проверка и канонизация scheme/host/port.
- `validateOriginStructure(uri, origin)` — отклоняет wildcard, user-info, query, fragment и **любой** path (даже `/`).
- `normalizePort(scheme, port)` — скрывает стандартные 80/443.
- `invalidOrigin(...)` — две фабрики исключений при неправильном origin.

**Разница:** `https://app.example.com` — origin; `https://app.example.com/` уже содержит path и отклоняется исходным кодом. 
Production-validator дополнительно требует HTTPS.

### 10.9. `ClientIpProperties`

Список `trustedProxyCidrs` для доверенных reverse proxy.

- Конструктор — нормализует, очищает и дедуплицирует CIDR.
- `validateCidr(cidr)` — проверяет синтаксис через Spring Security `IpAddressMatcher`.

Пустой allowlist в локальных сценариях означает «не доверять никаким forwarded headers», а в production запрещён отдельным валидатором.

### 10.10. `ClientIpResolver`

**Задача:** извлекать IP клиента, не доверяя подделанным `X-Forwarded-For`/`X-Real-IP` от произвольного интернет-клиента.

- Конструктор — строит matchers для явно настроенных доверенных CIDR.
- `resolve(request)` — нормализует socket peer (`getRemoteAddr()`); forwarded headers учитывает **только при доверенном peer**; 
- при корректном `X-Forwarded-For` ищет ближайший недоверенный IP справа налево; при ошибочном заголовке остаётся на remote peer; 
- `X-Real-IP` используется только как fallback при отсутствии пригодной XFF-цепочки.
- `parseForwardedFor(rawHeader)` — разбирает до 32 hops, требует валидный IP в каждом; иначе вся цепочка считается недействительной.
- `nearestUntrustedFromRight(chain)` — выбирает границу доверия.
- `isUntrustedAddress(ip)` — сверяет IP с CIDR trusted proxies.
- `normalizeIp(rawValue)` — отвергает hostname и прочие значения до DNS; поддерживает IPv4 и IPv6 literals.
- `normalizeIpv4(value)` — проверяет четыре октета, возвращает канонический IPv4.
- `stripIpv4Port(value)` — разбирает IPv4:port.
- `stripBracketedIpv6Port(value)` — разбирает `[IPv6]:port` или `[IPv6]`.
- Вложенный `ForwardedChainResult`: immutable record `present`, `valid`, `addresses`; статические фабрики `absent()`, `invalid()`, `valid(addresses)`.

**Ограничение среды:** корректность зависит от конфигурации Nginx/балансировщика: кто устанавливает и очищает forwarded headers, 
какие именно подсети объявлены доверенными, не доступны ли внутренние proxy напрямую извне. Это нужно проверять end-to-end, 
а не только через `MockHttpServletRequest`.

### 10.11. `RequestIdFilter`

`OncePerRequestFilter` с приоритетом `HIGHEST_PRECEDENCE`.

- `doFilterInternal(...)` — **всегда создаёт свой UUID** для серверного `requestId`; входящий `X-Request-Id` рассматривает 
- только как отдельный `clientRequestId`; записывает значения в request attributes, response header и SLF4J MDC, затем
- восстанавливает прежний MDC в `finally`.
- `normalizeClientRequestId(...)` — допускает ограниченный ASCII-алфавит и длину не более 128 символов.
- `restoreMdc(key, previousValue)` — возвращает предыдущий контекст потока либо удаляет ключ.

**Почему важно:** нельзя принимать произвольный клиентский request ID за доверенный серверный correlation identifier; 
это усложнило бы расследование событий и атрибуцию логов.

**Граница:** обычный MDC работает в текущем потоке; для `@Async`, отдельного executor, реактивного pipeline или события 
после перехода потока нужен отдельный явно проверенный механизм передачи контекста.

### 10.12. `ManagementSecurityConfig`

`@Configuration` отдельной цепочки Spring Security для Actuator.

- `managementSecurityFilterChain(HttpSecurity http)` — `@Bean` с высшим приоритетом; ограничивает matcher до Actuator, 
- разрешает `health`, `info`, `prometheus`, остальные Actuator endpoints запрещает. Отключает CSRF, login, basic и sessions 
- именно для предназначенных технических probe-запросов.

**Критичная зависимость от инфраструктуры:** отсутствие логина на разрешённых endpoints приемлемо **только если management 
port действительно недоступен из публичной сети**. Проверки Java и private IP не заменяют Docker/Kubernetes/firewall/network-policy правила.

### 10.13. `ProductionEnvironmentCondition`

Spring `Condition` для единого определения production.

- `matches(context, metadata)` — получает Spring `Environment`, вызывает `isProduction`.
- `isProduction(environment)` — ищет явное `safeai.environment=production` и активные production-профили.
- `isProductionName(value)` — распознаёт `prod`, `production` и варианты `prod-k8s`, `production-eu` и др.

**Зачем:** нельзя случайно пропустить строгие проверки, назвав профиль не ровно `prod`.

### 10.14. `NonProductionEnvironmentCondition`

- `matches(context, metadata)` — инверсия `ProductionEnvironmentCondition`. Нужна для компонентов, предназначенных лишь 
- для local/test, например mock AI provider.

**Правило:** определение production должно оставаться централизованным; не размножать собственные выражения `@Profile("!prod")`.

### 10.15. `ProductionSecurityInvariantValidator`

`@Component`, активируется в production через `@Conditional(ProductionEnvironmentCondition.class)`, 
реализует `SmartInitializingSingleton` — **fail-fast при запуске приложения**.

- Конструктор — принимает `CorsProperties`, `ClientIpProperties`, `JwtProperties`, `Environment`.
- `afterSingletonsInstantiated()` — последовательно запускает проверки ниже.
- `validateCors()` — непустой allowlist, только HTTPS.
- `validateTrustedProxies()` — требуется доверенный proxy CIDR; запрет доверия всему IPv4/IPv6 интернету.
- `validateIssuer()` — JWT issuer в production должен быть HTTPS.
- `validateForwardedHeadersStrategy()` — требуется `server.forward-headers-strategy=none`, так как IP определяется собственным `ClientIpResolver`.
- `validateManagementServer()` — management port задан, валиден и отличается от основного; адрес — внутренний.
- `resolveManagementAddress(address)` — перевод адреса management server в `InetAddress` либо fail-fast.
- `isInternalManagementAddress(address)` — разрешены loopback/site-local/link-local/IPv6 ULA, запрещены public, multicast, wildcard.
- `isIpv6UniqueLocalAddress(address)` — проверка диапазона `fc00::/7`.
- `validateJwtRotationKeyMaterial()` — предыдущие JWT-ключи не должны сохранять private key.
- `validateSchedulerPool()` — `spring.task.scheduling.pool.size` явно не менее двух.
- `validateLegacyPasswordCompatibilityDeclaration()` — явная настройка режима миграции старых BCrypt-хешей; отсутствие настройки = отказ старта.
- `validateHsts()` — должен быть объявлен HSTS в приложении (`enabled=true`) **либо** на доверенном TLS edge (`edge-managed=true`).

**Важное различие:** проверка `edge-managed=true` подтверждает **заявление о конфигурации**, а не факт наличия HSTS в 
публичном HTTPS-ответе. Последнее проверяется отдельным deployment smoke test.

### 10.16. `security/package-info.java`

`@NullMarked` для `common.security` — по умолчанию ссылки считаются non-null, если явно не отмечены `@Nullable`/`@NullUnmarked`. 
Это не Java-класс и методов не содержит.

`@NullUnmarked` на некоторых configuration-properties records используется для гибкого Spring binding с потенциально 
отсутствующими входными значениями. Для полноценного статического контроля JSpecify по всему приложению понадобится 
отдельная компиляционная проверка (например, NullAway), а не просто IDE-подсказки.

---

## 11. `common.validation` — корректность UTF-16

### `UnicodeValidationSupport`

- `requireWellFormedUtf16(value, fieldName)` — проходит Java `String` и отвергает **непарные UTF-16 surrogate code units**: 
- high surrogate без следующего low surrogate или low surrogate без соответствующего high. Корректную surrogate pair пропускает целиком.
- `invalidUnicode(fieldName)` — стандартная ошибка валидации.

**Зачем:** некорректные Java-строки не должны незаметно попадать в JSON, нормализацию, хеширование, БД, аудиторские снимки или AI-контракты.

Метод **не выполняет NFC/NFKC-нормализацию** — это правильное разделение ответственности: алгоритм нормализации зависит 
от назначения поля и должен выбираться вызывающим кодом.

## 12. Аудит ошибок и рисков

### 12.1. Что подтверждено

1. **Архитектура:** `common` не зависит от `auth` и других прикладных Java-пакетов; правило `CommonDependencyArchitectureTest` существует в `main`.
2. **Защита HTTP-ответа:** `ApiErrorResponseWriter` действительно удаляет посторонние прежние заголовки через `response.reset()`; тест проверяет отсутствие `Location`, `Set-Cookie`, `ETag` и сохранение разрешённых заголовков.
3. **Защита конфигурации:** production определяется не только по точному имени профиля, и проверяются HSTS declaration, scheduler pool, JWT key ring, HTTPS issuer и management interface.
4. **Изоляция роли `common`:** прикладной процесс login/token rotation сюда не перенесён; граница соблюдается.
5. **Тестовый пробел:** обнаруженные unit-тесты `PostgresAdvisoryLockExecutorTest` используют mocked JDBC `Connection`; сами по себе они не подтверждают сброс физического advisory lock после ошибки в реальном pool.

### 12.2. Приоритеты проверки (не смешивать гипотезу и доказанный дефект)

| Приоритет | Где | Риск / проблема | Статус | Практическое действие |
|---|---|---|---|---|
| **P1** | `PostgresAdvisoryLockExecutor.abortConnection()` | Если `abort()` не сработает, `close()` на pool proxy **может** вернуть физическую session с lock в pool | **Риск, не доказан текущим тестом** | Интеграционный тест с Hikari + pgJDBC + PostgreSQL; измерить реально высвобождение `pg_locks` |
| **P1 / deployment** | `ManagementSecurityConfig`, `ProductionSecurityInvariantValidator.validateHsts()` | Внутренняя доступность Actuator и HSTS на TLS edge обеспечиваются реальной сетью, не только Java | **Зависимость от внешней инфраструктуры** | Реальные сетевые/HTTPS проверки в staging; `/actuator` снаружи закрыт, HSTS есть на HTTPS |
| **P2** | `DatabaseConstraintClassifier.isUniqueViolation()` | Код SQLSTATE и constraint name могут быть извлечены из **разных** звеньев exception graph | **Вывод из исходного алгоритма; нуждается в регрессии** | Добавить тест на смешанные `SQLException` и при необходимости связать две проверки |
| **P2** | `ApiErrorResponseWriter.snapshotSafeHeaders()` | Если downstream ранее переопределил уже существующий `X-Request-Id`, метод сохранит его вместо эталонного request attribute | **Условный риск нарушения server-owned correlation** | Проверить сценарий подменённого заголовка; при необходимости всегда брать серверное значение из request attribute |
| **P2** | `ApiExceptionResponseSupport.build()`, `GlobalExceptionHandler.handleResponseStatusException()` | Headers из исключений переносятся в ответ; нужно убедиться, что пользовательские/нестандартные `ResponseStatusException` не передают нежелательные заголовки | **Не найден подтверждённый exploit** | Анализ call sites и тест на `Set-Cookie`, `Location`, `WWW-Authenticate` для небезопасных источников |
| **P2** | `ClientIpResolver` + Nginx | Trusted proxy list может быть правильным в unit-test и неправильным в real deployment | **Инфраструктурный риск** | Проверить цепочку proxy, очистку входящих headers, прямой доступ и IPv4/IPv6 |
| **P3** | `GlobalExceptionHandler` | Два обработчика простых исключений читают `getMessage()`, хотя базовый API выделяет `getPublicMessage()` | **Техническая несогласованность без текущей утечки в этих конструкторах** | Небольшое улучшение после тестов контракта |
| **P3** | `StablePageableNormalizer` | Высокий допустимый OFFSET не равен быстрой пагинации | **Известное эксплуатационное ограничение** | Для тяжёлых endpoint применять keyset/cursor pagination |
| **P3** | `security/package-info.java` | Не весь backend обязательно проверяется NullAway | **Не подтверждена сквозная build-time nullness проверка** | При необходимости постепенно подключать nullness checking; не менять всё одновременно |

**Критическая ошибка P0:** статический анализ присланного `common` и его открытых тестов **не позволил подтвердить P0-дефект**. 
Это не сертификат отсутствия уязвимостей. Приоритеты P1/P2 выше — рекомендации по проверке, а не утверждение о реально состоявшемся инциденте.

### 12.3. Почему не нужно срочно рефакторить всё

Большая часть решений обоснованна: immutable records, явные guards, централизованный публичный error contract, deny-by-default в production, 
строгие поля и роли, часовой `Clock`, bounded pagination, вынос низкоуровневого JDBC в общий пакет. Сплошной перенос файлов или переписывание 
Spring Security увеличат регрессионный риск и не исправят конкретных эксплуатационных проблем.

## 13. Тестирование и как повторить

В репозитории есть выделенные тесты для `common`:

| Пакет тестов | Проверяемые классы/сценарии |
|---|---|
| `common.exception` | `ApiErrorResponseFactoryTest`, `ApiErrorResponseTest`, `ApiErrorResponseWriterTest`, `GlobalExceptionHandlerAccessDeniedTest`, `GlobalExceptionHandlerIntegrationTest`, `GlobalExceptionHandlerTest` |
| `common.pagination` | `StablePageableNormalizerTest` |
| `common.persistence` | `DatabaseConstraintClassifierTest`, `PostgresAdvisoryLockExecutorTest` |
| `common.security` | `AccessTokenSubjectTest`, `ClientIpPropertiesTest`, `ClientIpResolverTest`, `CorsPropertiesTest`, `JwtBearerSecurityIntegrationTest`, `JwtConfigurationBindingTest`, `JwtPropertiesTest`, `ProductionEnvironmentConditionTest`, `ProductionJwtConfigDataTest`, `ProductionSecurityInvariantValidatorTest`, `ProductionSecurityStartupContractTest`, `RequestIdFilterTest`, `RoleAuthorityMapperTest`, `SafeAiUserPrincipalTest`, `SecurityErrorResponseIntegrationTest`, `SensitiveValueToStringTest` |
| `common.validation` | `UnicodeValidationSupportTest` |
| `architecture` | `CommonDependencyArchitectureTest` — dependency boundary |

**Примеры команд** из каталога `backend`:

```bash
# Архитектурная граница common
./mvnw -Dtest=CommonDependencyArchitectureTest test

# Несколько адресных тестов по security, error response и JDBC
./mvnw -Dtest=ApiErrorResponseWriterTest,GlobalExceptionHandlerTest,ClientIpResolverTest,PostgresAdvisoryLockExecutorTest,DatabaseConstraintClassifierTest test

# Весь проект с компиляцией и общими проверками
./mvnw clean verify
```

Полная проверка может использовать Docker/Testcontainers. **В рамках составления этого README команды выше заново не выполнялись.** Присланный справочник после PR #16 документирует исторический успешный прогон **2299 backend-тестов, 0 failures, 0 errors, 3 skipped**; это результат предыдущего запуска, а не новый отчёт за 15 октября.

### Тесты, которые целесообразно добавить перед production

1. `PostgresAdvisoryLockExecutorIT` на реальном PostgreSQL + Hikari: ambiguous acquire, отказ unlock, проверка исчезновения 
2. lock после abort и того, что connection не возвращается в пул с lock.
2. `DatabaseConstraintClassifierTest` на цепочки ошибок, в которых SQLSTATE и имена constraints различаются по узлам.
3. `ApiErrorResponseWriterTest` на изменение `X-Request-Id` после `RequestIdFilter`.
4. Полный HTTP smoke-test `Actuator + Nginx + Spring Security`: снаружи нет Actuator, внутри доступны только разрешённые метрики/здоровье.
5. End-to-end trusted proxy тесты с различными XFF headers и непрямым соединением.
6. Сценарии cookie-auth и bearer-auth для `401`, `403`, `WWW-Authenticate`, `Cache-Control`, CORS.

## 14. Правила внесения изменений

- **Не добавлять в `common` импорты из `auth`, `user`, `organization`, `chat`, `ai` и других прикладных модулей.** Это сломает существующую 
- архитектурную защиту и создаст риск циклов.
- **Не переименовывать `ApiErrorCode` без API-миграции** и синхронизации с frontend.
- **Не менять семантику JWT, ролей, `organizationAuthVersion`, `tokenVersion` и revoke/fail-closed поведения без end-to-end тестов.**
- **Не «упрощать» защиту PostgreSQL advisory lock за счёт обычного `rollback()`**: session-level lock от него не освобождается.
- **Не подменять проверку HSTS внутри Spring декларацией безопасности публичной сети** — HTTPS/HSTS и закрытие Actuator должны 
- контролироваться и на внешнем edge.
- **Не добавлять ручную нормализацию email/Unicode в общий helper без требований предметной области**: canonicalization и validation — разные операции.
- **Каждое изменение — небольшой связный PR**: проверка причины → targeted regression → полный тестовый прогон → ревью diff → merge.

## 15. Итоговое архитектурное решение

**Оставить `common` отдельным логическим модулем внутри backend.** `auth` не нужен, чтобы `common` мог компилироваться как 
часть приложения, но для полноценной проверки логина, JWT lifecycle и управления сессиями необходимо анализировать **сам 
`auth`** и связанные вызовы `user`/`organization`.

- Для документации перечисленных 36 файлов достаточно исходников `common` и тестов.
- Для выводов о **правильности интеграции** аутентификации, cookie, CSRF, refresh token, ролей и revoke нужен cross-module 
- security audit.
- Для выводов о **production-ready** нужны дополнительно staging, TLS/Nginx, PostgreSQL, Redis, тестирование восстановления, 
- CI/CD и сети.

**Рекомендация на октябрь 2026:** не выполнять массовый рефакторинг `common` ради нового названия или более «чистой» архитектуры. 
Сначала закрыть подтверждаемые тестами P1/P2-гипотезы, затем возвращаться к развитию пользовательских функций SafeAI Desk.

## 16. Источники и актуализация

**Код проекта (зафиксированная ревизия):**

- [`common` в GitHub](https://github.com/Amid-1/Safeai-desk/tree/ec2596ed079423d7103fa3218b51a0ce6cd46df0/backend/src/main/java/ru/safeai/gateway/common)
- [`CommonDependencyArchitectureTest`](https://github.com/Amid-1/Safeai-desk/blob/ec2596ed079423d7103fa3218b51a0ce6cd46df0/backend/src/test/java/ru/safeai/gateway/architecture/CommonDependencyArchitectureTest.java)
- [`PostgresAdvisoryLockExecutorTest`](https://github.com/Amid-1/Safeai-desk/blob/ec2596ed079423d7103fa3218b51a0ce6cd46df0/backend/src/test/java/ru/safeai/gateway/common/persistence/PostgresAdvisoryLockExecutorTest.java)
- [`backend/pom.xml`](https://github.com/Amid-1/Safeai-desk/blob/ec2596ed079423d7103fa3218b51a0ce6cd46df0/backend/pom.xml)
- `common/exception/README.md` — существующий расширенный обзор ошибок; у него своя историческая дата и commit baseline.

**Официальная документация для оценки технологий и эксплуатационных рисков:**

- [Spring Boot 4.1.1 — системные требования](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Security — архитектура Servlet FilterChain](https://docs.spring.io/spring-security/reference/servlet/architecture.html)
- [Spring Framework 7 — JSpecify/null-safety](https://docs.spring.io/spring-framework/reference/core/null-safety.html)
- [PostgreSQL — Advisory Locks](https://www.postgresql.org/docs/current/explicit-locking.html#ADVISORY-LOCKS)
- [Oracle — Java SE Support Roadmap](https://www.oracle.com/java/technologies/java-se-support-roadmap.html)

---

*Этот README описывает существующую реализацию. Он не предлагает перенести `common` в `auth` или менять бизнес-логику без подтверждённых дефектов.*
