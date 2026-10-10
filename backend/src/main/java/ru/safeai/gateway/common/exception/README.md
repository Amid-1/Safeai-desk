# Обработка ошибок HTTP API — SafeAI Desk

> **Состояние:** 10 октября 2026 года, ветка `refactor/common-exception-boundary`, коммит `cacb269`.
> **Технологии:** Java 21, Spring Boot 4.1.1, Spring MVC, Spring Security.
> **Область документа:** `backend/src/main/java/ru/safeai/gateway/common/exception/` и связанные обработчики исключений модулей.
>
> Точные сигнатуры Java-методов и HTTP-контракты определяются исходным кодом и тестами. Это архитектурный справочник, а не замена тестов.

## 1. Назначение и принцип границ модулей

`common.exception` содержит **универсальные контракты** HTTP-ошибок, фабрику и запись ответов, базовые исключения и последнюю линию обработки исключений Spring MVC. Он **не содержит** исключений, принадлежащих `auth`, `chat`, `ratelimit`, `user` или `organization`, и не импортирует Java-типы из этих модулей.

Модули владеют собственными исключениями и специализированными обработчиками. Они могут использовать общие `ApiException`, `ApiErrorCode`, `ApiErrorResponse` и `ApiExceptionResponseSupport`. Обратное направление зависимости — из `common.exception` в бизнес-модуль — запрещено.

```text
                        common.exception
                ┌──────────────────────────────┐
                │ ApiException                 │
                │ ApiErrorCode                 │
                │ ApiErrorResponse             │
                │ ApiErrorResponseFactory      │
                │ ApiExceptionResponseSupport  │
                │ ApiErrorResponseWriter       │
                │ GlobalExceptionHandler       │
                └──────────────▲───────────────┘
                               │ используют
       ┌──────────┬──────────┬──┴──────┬──────────┬─────────────┐
       │ auth     │ chat     │ratelimit │ user     │organization │
       └──────────┴──────────┴──────────┴──────────┴─────────────┘
```

### Фактический состав `common.exception`

```text
common/exception/
├── ApiErrorCode.java
├── ApiErrorResponse.java
├── ApiErrorResponseFactory.java
├── ApiErrorResponseWriter.java
├── ApiException.java
├── ApiExceptionResponseSupport.java
├── BadRequestException.java
├── ConflictException.java
├── ForbiddenOperationException.java
├── GlobalExceptionHandler.java
├── ResourceNotFoundException.java
├── ValidationErrorExtractor.java
└── README.md
```

`README.md` — документация; Java-классов в этом каталоге **12**.

## 2. Где находятся специализированные исключения

| Модуль | Исключения и сопутствующие классы | Обработчик |
|---|---|---|
| `auth.exception` | `AuthServiceUnavailableException`, `ExpiredRefreshTokenException`, `InvalidRefreshTokenException`, `RefreshTokenReuseDetectedException` | `AuthExceptionHandler` |
| `chat.exception` | `ChatBusyException`, `ChatLockUnavailableException`, `ChatApiException` и другие chat-specific exceptions | `ChatInfrastructureExceptionHandler`, `ChatApiExceptionHandler` |
| `ratelimit.exception` | `RateLimitExceededException`, `RateLimitUnavailableException`, `RateLimitUnavailableLogger` | `RateLimitExceptionHandler` |
| `user.exception` | `UserVersionConflictException` | `UserExceptionHandler` |
| `organization.exception` | `OrganizationVersionConflictException` | `OrganizationExceptionHandler` |

`RateLimitUnavailableLogger` сохраняет ограничение частоты повторных сообщений об инфраструктурных сбоях. Он принадлежит RateLimit-модулю, а не общему пакету.

Примечание: таблица перечисляет типы, непосредственно участвовавшие в данном рефакторинге, а не все исключения всех модулей (`ai`, `model`, `knowledge` и др.).

## 3. Разрешение исключений Spring MVC

В Spring MVC при наличии нескольких `@RestControllerAdvice` **приоритет advice** имеет значение. Даже специфичный обработчик может быть обойден более ранним advice, если тот перехватывает общий тип. Поэтому специализированные обработчики имеют явно заданный порядок, а глобальный fallback исполняется последним.

| Приоритет | Обработчик | Область действия |
|---|---|---|
| `Ordered.HIGHEST_PRECEDENCE` | `ChatApiExceptionHandler` | Только `ChatController` (`assignableTypes`) |
| `Ordered.HIGHEST_PRECEDENCE + 1` | `ChatInfrastructureExceptionHandler` | Специфичные chat infrastructure exceptions |
| `Ordered.HIGHEST_PRECEDENCE + 2` | `AuthExceptionHandler` | Специфичные auth exceptions |
| `Ordered.HIGHEST_PRECEDENCE + 3` | `RateLimitExceptionHandler` | Специфичные rate-limit exceptions |
| `Ordered.HIGHEST_PRECEDENCE + 10` | `UserExceptionHandler` | Конфликт версии пользователя |
| `Ordered.HIGHEST_PRECEDENCE + 11` | `OrganizationExceptionHandler` | Конфликт версии организации |
| `Ordered.LOWEST_PRECEDENCE` | `GlobalExceptionHandler` | Общие исключения, validation, fallback |

Большинство специализированных advice не ограничены контроллером через `assignableTypes`: исключение модуля может быть выброшено по пути другого MVC-контроллера. Их `@ExceptionHandler` должны оставаться **узко типизированными**; не следует добавлять в них `@ExceptionHandler(Exception.class)`.

```text
HTTP / Spring MVC
    └─ Controller → Service / Repository
            ├─ ChatApiException (ChatController)
            │      └─ ChatApiExceptionHandler → ChatErrorResponse
            ├─ module-specific exception
            │      └─ соответствующий module ExceptionHandler
            └─ generic / framework exception
                   └─ GlobalExceptionHandler
                              ↓
                   ApiExceptionResponseSupport
                              ↓
                   ApiErrorResponseFactory
                              ↓
                   ApiErrorResponse (JSON)
```

**Исключение из общего JSON-формата:** `ChatApiExceptionHandler` возвращает специализированный `ChatErrorResponse` для `ChatController`. Перенос обычных Chat infrastructure exceptions в `chat.exception` **не должен менять** этот отдельный контракт.

### Что осталось в `GlobalExceptionHandler`

- общие `BadRequestException`, `ResourceNotFoundException`, `ConflictException`, `ForbiddenOperationException`;
- общий `ApiException` с `status`, `errorCode` и безопасным `publicMessage`;
- оптимистическая блокировка JPA/Spring (`OptimisticLockException`, `OptimisticLockingFailureException`) → обычный `409 CONFLICT`;
- ошибки валидации запроса и DTO; ошибки method/return-value validation;
- ошибки HTTP-метода/Content-Type/Accept/JSON и типовых параметров запроса;
- ошибки доступа или аутентификации, дошедшие до Spring MVC;
- `DataIntegrityViolationException`, непредвиденные `Exception` → безопасный `500`.

`GlobalExceptionHandler` реализует `Ordered` и возвращает `Ordered.LOWEST_PRECEDENCE` через `getOrder()`.

## 4. Общий HTTP error contract

`ApiErrorResponse` — immutable Java record со следующими полями:

```json
{
  "timestamp": "2026-10-10T18:00:00Z",
  "status": 409,
  "error": "CONFLICT",
  "message": "Конфликт данных",
  "path": "/api/example",
  "requestId": "e313fb32-bdca-4b2b-8f3a-7fdfd78ddca6",
  "fieldErrors": {}
}
```

- `timestamp`: время из injected `Clock`, а не из системного времени внутри каждого handler;
- `status`: HTTP-статус;
- `error`: стабильный код из `ApiErrorCode`, на который может ориентироваться frontend;
- `message`: только разрешенное для клиента сообщение;
- `path`: request URI;
- `requestId`: значение server-owned `RequestIdFilter.REQUEST_ID_ATTRIBUTE`, может быть `null`, если отсутствует;
- `fieldErrors`: всегда объект; значения нормализованы, дубликаты сообщений удалены, результат immutable.

**Не использовать `message` как программный код** и не переименовывать существующие `ApiErrorCode` без согласованной API-миграции. Новые коды для модулей остаются в центральном enum как **внешний API-контракт**, даже если сами исключения живут в модулях.

`ApiException` различает `publicMessage` и `internalMessage`. HTTP-ответ должен использовать `getPublicMessage()`, а внутреннюю причину нельзя отправлять клиенту.

`ApiExceptionResponseSupport` позволяет нескольким MVC-обработчикам согласованно создавать ответы через фабрику и выставлять `Cache-Control: no-store`.

### Основные статусы и коды

| Сценарий | HTTP | `error` |
|---|---:|---|
| Некорректный запрос | 400 | `BAD_REQUEST` |
| Ошибка валидации | 400 | `VALIDATION_ERROR` |
| Требуется аутентификация | 401 | `UNAUTHORIZED` |
| Истекший refresh token | 401 | `EXPIRED_REFRESH_TOKEN` |
| Недействительный refresh token / reuse | 401 | `INVALID_REFRESH_TOKEN` |
| Доступ запрещен | 403 | `FORBIDDEN` |
| Ресурс не найден | 404 | `NOT_FOUND` |
| Общий конфликт или optimistic locking | 409 | `CONFLICT` |
| Конфликт версии пользователя | 409 | `USER_VERSION_CONFLICT` |
| Конфликт версии организации | 409 | `ORGANIZATION_VERSION_CONFLICT` |
| Chat занят | 409 | `CHAT_BUSY` |
| Превышение лимита запросов | 429 | `RATE_LIMIT_EXCEEDED` |
| Chat lock недоступен | 503 | `CHAT_LOCK_UNAVAILABLE` |
| Rate-limit infrastructure недоступна | 503 | `RATE_LIMIT_UNAVAILABLE` |
| Auth infrastructure недоступна | 503 | `AUTH_SERVICE_UNAVAILABLE` |
| Необработанная серверная ошибка | 500 | `INTERNAL_SERVER_ERROR` |

Дополнительно сохраняются `TOKEN_REVOKED`, коды AI/provider и прочие значения `ApiErrorCode`: они не удалялись при переносе ownership исключений.

### Обязательные дополнительные HTTP-семантики

- При HTTP **429** возвращается `Retry-After`, если `retryAfterSeconds > 0`; правило округления длительности, реализованное в `RateLimitExceededException`, сохраняется.
- HTTP **405** должен сохранять `Allow`.
- Указанные MVC-обработчики формируют `Cache-Control: no-store`.
- Auth reuse detection не раскрывает token hash, семейство токенов и внутренние детали через публичное сообщение, но сохраняет предназначенные для аудита данные на сервере.
- Для user/organization version conflict клиент получает отдельный машинный код `409`; сведения об ожидаемой и текущей версиях используются в серверной диагностике, а не как доверенный источник для повторного применения изменений.

## 5. Ошибки до Spring MVC: security filter chain

Не все ошибки достигают `@RestControllerAdvice`. Например, ошибки authentication/authorization внутри servlet security filters обрабатываются другим механизмом:

```text
HTTP request
    ↓
Spring Security filter chain
    ├─ RestAuthenticationEntryPoint
    └─ RestAccessDeniedHandler
             ↓
       ApiErrorResponseWriter
             ↓
       ApiErrorResponseFactory
             ↓
       ApiErrorResponse (JSON)
```

`ApiErrorResponseWriter` проверяет `response.isCommitted()`, полностью сбрасывает неотправленный ответ, восстанавливает лишь явно разрешенные correlation/CORS/security headers, устанавливает JSON/UTF-8 и `Cache-Control: no-store`. Это защищает от случайного наследования заголовков вроде `Set-Cookie` после ошибки доступа.

Для cookie-based authentication обычный `401` **не должен** автоматически содержать `WWW-Authenticate: Bearer`. Отдельный метод `writeBearerUnauthorized` добавляет challenge только для соответствующего Bearer/resource-server сценария.

Spring MVC advice **не заменяет** исключения, формируемые в фильтрах, и наоборот.

## 6. Безопасность, журналирование и observability

**В HTTP JSON запрещены:** stack trace, SQL/Redis internal details, JWT, refresh/access tokens, Cookie/Authorization, credentials, provider secrets и приватные debug-данные.

Логи должны содержать достаточный технический контекст (`requestId`, path, error type; для version conflicts — идентификатор сущности и номера версий, если это допускает политика безопасности). Audit повторного использования refresh-токена должен оставаться **обязательным в той же транзакции**, что и завершение token family. Не заменять эту запись best-effort callback или Mockito-заглушкой в интеграционной проверке.

**Отдельный риск:** отсутствие секрета в HTTP-ответе не гарантирует отсутствие чувствительных значений в серверных stack traces / nested exception messages. Редактирование или санитаризация диагностического логирования — самостоятельная задача безопасности, а не часть данного рефакторинга исключений.

## 7. Регрессионные тесты и статус проверки

Тестовое покрытие для переноса исключений включает:

- MVC выбор специфичных advice (`ChatExceptionAdviceMvcTest`, `AuthControllerSecurityTest`, `GlobalExceptionHandlerIntegrationTest`);
- модульные обработчики (`AuthExceptionHandlerTest`, `RateLimitExceptionHandlerTest`, `UserExceptionHandlerTest`, `OrganizationExceptionHandlerTest`);
- общий fallback и фабрику ответов (`GlobalExceptionHandlerTest`, `ApiErrorResponseFactoryTest`);
- проверку security filter-chain отдельно от MVC;
- PostgreSQL concurrency и Redis atomic fixed-window integration-тесты.

Из каталога `backend/`:

```bash
./mvnw test

./mvnw \
  -Dtest=AuthPostgresConcurrencyIT,OrganizationSecurityGateConcurrencyIT,RedisFixedWindowRateLimiterIT \
  test
```

Подтвержденный локальный результат на коммите `cacb269` (10.10.2026):

| Запуск | Фактический результат |
|---|---|
| `./mvnw test` | **2256** тестов; 0 failures; 0 errors; 3 skipped; `BUILD SUCCESS` |
| Три интеграционных класса `*IT` | **27** тестов; 0 failures; 0 errors; 0 skipped; `BUILD SUCCESS` |
| Стабильность этих `*IT` | Четыре последовательных успешных прогона, включая итоговый |

**Важно:** обычный `./mvnw test` не обнаруживает по стандартным include patterns классы `*IT` автоматически. Их запускали явным параметром `-Dtest`. Maven-профиль `rate-limit-topology-it` предназначен для отдельного Failsafe-пути, включая тесты внешних Redis Cluster и Redis Sentinel; без подготовленных соответствующих endpoints и переменных окружения их успешное выполнение **не подтверждено**.

Описанные результаты не равнозначны `./mvnw clean test`, полному deployment smoke-тесту или production-ready сертификации.

### Проверка обратных зависимостей

Из корня репозитория:

```bash
git grep -n -E \
  'import ru\.safeai\.gateway\.(auth|chat|ratelimit|user|organization)\.' \
  -- backend/src/main/java/ru/safeai/gateway/common/exception
```

В проверенной ветке эта команда не находит импортов из перечисленных модулей. Для нового бизнес-модуля следует расширить регулярное выражение или внедрить автоматическую архитектурную проверку (например, ArchUnit).

## 8. Правила дальнейшего развития

1. **Исключение живет в модуле-владельце.** Общий пакет не импортирует бизнес-модули.
2. **Модульный `@RestControllerAdvice` перехватывает конкретные типы.** Общий fallback `Exception.class` остается только у `GlobalExceptionHandler`.
3. **Контракт HTTP устойчив.** Не менять status, `ApiErrorCode`, безопасные messages или headers в refactor-only задачах.
4. **Фабрика ответа общая.** Использовать `ApiErrorResponseFactory` / `ApiExceptionResponseSupport` вместо копирования логики JSON и request ID.
5. **Security filter chain отдельно.** Контролируемые security HTTP-ошибки должны идти через штатные entry point / access-denied handler и `ApiErrorResponseWriter`.
6. **Тестировать MVC-выбор, а не только прямой вызов метода advice.** Для конфликтов приоритетов нужны MockMvc-тесты.
7. **Не нарушать транзакционную безопасность.** `RefreshTokenReuseDetectedException` и `noRollbackFor` должны сохранять atomic family termination + mandatory audit.
8. **Не путать причины отзыва.** `ROTATED` у предшественника сохраняется; действующие члены семейства получают причину их фактического отзыва.
9. **Не отправлять stack traces клиенту.** Защищать и HTTP-ответы, и серверные логи от чувствительных данных.
10. **При изменении архитектуры обновлять документацию и тесты в том же отдельном коммите.**

---

Исторический документ описывал модель с единственным `GlobalExceptionHandler` и доменными исключениями в `common.exception`; эта модель заменена модульными обработчиками в рефакторинге `502495c`, `2259623`, `9951138`, `7867243`. Тестовая конфигурация `AuthPostgresConcurrencyIT` уточнена отдельным коммитом `cacb269`.
