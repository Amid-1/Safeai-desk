# Production-обработка исключений в SafeAI Gateway

Этот модуль задаёт единый и предсказуемый формат ошибок для REST API проекта **SafeAI Gateway**.

Он решает сразу несколько задач:

- централизует обработку исключений Spring MVC;
- устраняет неоднозначность между несколькими `@RestControllerAdvice`;
- возвращает frontend стабильные машинные коды ошибок;
- одинаково оформляет ошибки контроллеров и Spring Security;
- не раскрывает клиенту внутренние сообщения базы данных, Redis и stack trace;
- сохраняет обязательные HTTP-заголовки, например `Retry-After` и `Allow`;
- корректно обрабатывает ошибки валидации DTO, параметров и возвращаемых значений;
- добавляет `requestId`, по которому ошибку можно найти в логах.

Модуль рассчитан на Java 21 и текущую конфигурацию проекта на Spring Boot 4.0.6, Spring Framework 7 и Jakarta API.
Для `@WebMvcTest` и `@AutoConfigureMockMvc` используются новые пакеты Spring Boot 4: `org.springframework.boot.webmvc.test.autoconfigure`.

---

## 1. Зачем понадобилась переработка

Ранее обработчики были разделены между тремя классами:

```text
ChatAvailabilityExceptionHandler
OptimisticLockExceptionHandler
GlobalExceptionHandler
```

При этом в `GlobalExceptionHandler` находились общие обработчики:

```java
@ExceptionHandler(ConflictException.class)
```

и:

```java
@ExceptionHandler(Exception.class)
```

А специализированные advice обрабатывали более конкретные исключения:

```java
ChatBusyException extends ConflictException
```

```java
OptimisticLockingFailureException
OptimisticLockException
```

Проблема заключается в том, что при наличии нескольких `@ControllerAdvice` или `@RestControllerAdvice` Spring сначала учитывает порядок самих advice-компонентов, а уже затем ищет подходящий метод внутри выбранного advice.

Без явно заданного `@Order` результат мог зависеть от порядка регистрации bean-компонентов.
Например, `ChatBusyException` мог быть обработан общим методом:

```java
@ExceptionHandler(ConflictException.class)
```

и frontend получал бы:

```json
{
  "error": "CONFLICT"
}
```

вместо ожидаемого:

```json
{
  "error": "CHAT_BUSY"
}
```

Аналогичный риск существовал для optimistic locking: специализированное сообщение могло быть заменено общей ошибкой `INTERNAL_SERVER_ERROR` или `CONFLICT`.

### Принятое решение

В проекте оставлен один класс:

```java
@RestControllerAdvice
public class GlobalExceptionHandler
```

В него перенесены все MVC-обработчики исключений.

Это даёт следующие преимущества:

1. Больше нет конкуренции между несколькими advice beans.
2. Не требуется поддерживать набор `@Order(0)`, `@Order(10)` и `Ordered.LOWEST_PRECEDENCE`.
3. Все правила преобразования исключений в HTTP-ответ видны в одном месте.
4. Более конкретный `@ExceptionHandler` стабильно выбирается раньше обработчика родительского типа внутри одного advice.
5. Общий `@ExceptionHandler(Exception.class)` остаётся последней защитной границей, а не случайным конкурентом специализированных advice.

Старые классы необходимо удалить:

```text
ChatAvailabilityExceptionHandler.java
OptimisticLockExceptionHandler.java
```

---

## 2. Общая архитектура

Обработка ошибок разделена на два технических контура.

### 2.1. Ошибки Spring MVC

Исключения, возникшие во время выполнения контроллера, сервиса, валидации аргументов или формирования ответа, обрабатывает:

```text
GlobalExceptionHandler
```

Поток обработки выглядит так:

```text
HTTP-запрос
    ↓
Spring MVC Controller
    ↓
Service / Repository
    ↓
Исключение
    ↓
GlobalExceptionHandler
    ↓
ApiErrorResponseFactory
    ↓
ApiErrorResponse
    ↓
JSON-ответ клиенту
```

### 2.2. Ошибки Spring Security filter chain

Часть ошибок авторизации возникает раньше, чем запрос попадёт в контроллер.
Например:

- отсутствует или недействителен access token;
- пользователь не аутентифицирован;
- у пользователя недостаточно прав;
- доступ отклонён security-фильтром.

Такие ошибки не следует рассчитывать обрабатывать только через `@RestControllerAdvice`, потому что они возникают внутри цепочки servlet-фильтров.

Для них используются:

```text
RestAuthenticationEntryPoint
RestAccessDeniedHandler
ApiErrorResponseWriter
```

Поток обработки:

```text
HTTP-запрос
    ↓
Spring Security filter chain
    ↓
AuthenticationException / AccessDeniedException
    ↓
RestAuthenticationEntryPoint / RestAccessDeniedHandler
    ↓
ApiErrorResponseWriter
    ↓
ApiErrorResponseFactory
    ↓
JSON-ответ клиенту
```

Оба контура используют одну модель ответа и одну фабрику, поэтому формат ошибок не зависит от того, где именно возникла проблема.

---

## 3. Структура модуля

```text
src
├── main/java/ru/safeai/gateway/common
│   ├── config
│   │   └── TimeConfig.java
│   ├── exception
│   │   ├── ApiErrorCode.java
│   │   ├── ApiErrorResponse.java
│   │   ├── ApiErrorResponseFactory.java
│   │   ├── ApiErrorResponseWriter.java
│   │   ├── BadRequestException.java
│   │   ├── ChatBusyException.java
│   │   ├── ChatLockUnavailableException.java
│   │   ├── ConflictException.java
│   │   ├── ExpiredRefreshTokenException.java
│   │   ├── ForbiddenOperationException.java
│   │   ├── GlobalExceptionHandler.java
│   │   ├── InvalidRefreshTokenException.java
│   │   ├── RateLimitExceededException.java
│   │   ├── RateLimitUnavailableException.java
│   │   ├── RefreshTokenReuseDetectedException.java
│   │   └── ResourceNotFoundException.java
│   └── security
│       ├── RestAccessDeniedHandler.java
│       └── RestAuthenticationEntryPoint.java
└── test/java/ru/safeai/gateway/common
    ├── exception
    │   ├── ApiErrorResponseFactoryTest.java
    │   ├── GlobalExceptionHandlerTest.java
    │   └── GlobalExceptionHandlerIntegrationTest.java
    └── security
        └── SecurityErrorResponseIntegrationTest.java
```

В проекте также должен существовать:

```text
ru.safeai.gateway.common.security.RequestIdFilter
```

Он не входит в этот модуль, потому что уже является частью общей security/infrastructure-конфигурации SafeAI Gateway.

---

## 4. Единый контракт ошибки API

Все ошибки возвращаются в формате `ApiErrorResponse`:

```java
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        String requestId,
        Map<String, List<String>> fieldErrors
) {
}
```

Пример обычной ошибки:

```json
{
  "timestamp": "2026-07-21T07:30:00Z",
  "status": 404,
  "error": "NOT_FOUND",
  "message": "Чат не найден",
  "path": "/api/chats/7efb8cc2-126f-4b93-9db0-99f420c94abc",
  "requestId": "7ef13345-3ed5-4dd3-af55-cdca71c15e51",
  "fieldErrors": {}
}
```

Пример ошибки валидации:

```json
{
  "timestamp": "2026-07-21T07:31:00Z",
  "status": 400,
  "error": "VALIDATION_ERROR",
  "message": "Ошибка валидации запроса",
  "path": "/api/auth/login",
  "requestId": "96870cf7-fcb0-4555-aedc-21ddc6c5d11c",
  "fieldErrors": {
    "email": [
      "Некорректный формат электронной почты"
    ],
    "password": [
      "Пароль не должен быть пустым",
      "Пароль должен содержать не менее 8 символов"
    ]
  }
}
```

### Назначение полей

| Поле | Назначение |
|---|---|
| `timestamp` | Время формирования ответа в UTC. |
| `status` | Числовой HTTP-статус, например `400`, `401`, `409` или `500`. |
| `error` | Стабильный машинный код из `ApiErrorCode`. Frontend может использовать его в логике. |
| `message` | Безопасное человекочитаемое сообщение. Может отображаться пользователю. |
| `path` | URI запроса без домена и query string. |
| `requestId` | Идентификатор запроса для сопоставления ответа с серверными логами. Может быть `null`, если фильтр не установил атрибут. |
| `fieldErrors` | Ошибки отдельных полей. Для невалидируемых ошибок всегда `{}`, а не `null`. |

### Почему `error` и `message` разделены

Frontend не должен принимать бизнес-решения на основании текста сообщения:

```javascript
if (response.message === 'Чат занят другим запросом') {
    // Неправильно
}
```

Текст может быть изменён, локализован или уточнён.
Для логики используется стабильный код:

```javascript
if (response.error === 'CHAT_BUSY') {
    // Правильно
}
```

Поле `message` предназначено для отображения или журналирования, а `error` — для программного ветвления.

---

## 5. `ApiErrorCode`: стабильные коды ошибок

Коды определены в enum:

```java
public enum ApiErrorCode {
    BAD_REQUEST,
    VALIDATION_ERROR,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    CHAT_BUSY,
    CHAT_LOCK_UNAVAILABLE,
    RATE_LIMIT_EXCEEDED,
    RATE_LIMIT_UNAVAILABLE,
    EXPIRED_REFRESH_TOKEN,
    INVALID_REFRESH_TOKEN,
    METHOD_NOT_ALLOWED,
    UNSUPPORTED_MEDIA_TYPE,
    NOT_ACCEPTABLE,
    INTERNAL_SERVER_ERROR
}
```

### Почему используется enum

Без enum строки обычно размножаются по проекту:

```java
"BAD_REQUEST"
"BAD-REQUEST"
"BADREQUEST"
```

Опечатка компилируется и становится частью внешнего API.
Enum решает эту проблему на этапе компиляции.

### Правила изменения enum

- Добавлять новый код следует только тогда, когда frontend действительно должен отличать эту ситуацию от существующих.
- Нельзя переименовывать опубликованный код без согласованной миграции frontend.
- Нельзя использовать локализованный текст в качестве кода.
- Нельзя создавать отдельный код для каждого текста ошибки, если клиент обрабатывает их одинаково.
- Для внутренних 5xx-ошибок предпочтителен общий `INTERNAL_SERVER_ERROR`, если клиенту не требуется отдельный сценарий восстановления.

---

## 6. `ApiErrorResponse`: безопасная неизменяемая модель

`ApiErrorResponse` реализован как Java record.
В compact-конструкторе выполняются проверки и нормализация.

### Обязательные значения

Не допускаются `null` для:

- `timestamp`;
- `error`;
- `message`;
- `path`.

Если фабрика попытается создать некорректный ответ, ошибка проявится сразу, а не превратится в непредсказуемый JSON.

### Нормализация `fieldErrors`

Метод `immutableFieldErrors`:

- заменяет `null` и пустую карту на `Map.of()`;
- игнорирует пустые имена полей;
- удаляет `null` и пустые сообщения;
- обрезает пробелы по краям;
- удаляет повторяющиеся сообщения;
- создаёт неизменяемые списки и карту.

Это гарантирует стабильный контракт:

```json
"fieldErrors": {}
```

вместо разных вариантов:

```json
"fieldErrors": null
```

или полного отсутствия поля.

Неизменяемость также не позволяет случайно изменить объект ответа после его создания.

---

## 7. `ApiErrorResponseFactory`: единая точка создания ответа

Фабрика отвечает за заполнение технических полей:

```text
timestamp
status
error
path
requestId
fieldErrors
```

Пример использования:

```java
errorResponseFactory.create(
        HttpStatus.NOT_FOUND,
        ApiErrorCode.NOT_FOUND,
        "Чат не найден",
        request,
        null
);
```

### Почему фабрика лучше ручного создания record

Без фабрики каждый обработчик должен повторять:

```java
new ApiErrorResponse(
        Instant.now(),
        status.value(),
        error.name(),
        message,
        request.getRequestURI(),
        requestId,
        fieldErrors
);
```

Это приводит к расхождениям между обработчиками.
Фабрика обеспечивает один способ формирования ответа.

### Использование `Clock`

Время создаётся через:

```java
Instant.now(clock)
```

а не через прямой вызов:

```java
Instant.now()
```

Это делает код тестируемым.
В тесте можно передать фиксированные часы:

```java
Clock fixedClock = Clock.fixed(
        Instant.parse("2026-07-21T07:30:00Z"),
        ZoneOffset.UTC
);
```

Тогда `timestamp` становится детерминированным.

### Получение `requestId`

Фабрика читает атрибут:

```java
RequestIdFilter.REQUEST_ID_ATTRIBUTE
```

Если атрибут содержит непустую строку, она возвращается клиенту.
Если фильтр не установил значение, `requestId` будет `null`.

Это допустимый резервный сценарий, но в production рекомендуется гарантировать выполнение `RequestIdFilter` для каждого запроса.

---

## 8. `ApiErrorResponseWriter`: ошибки вне Spring MVC

`ApiErrorResponseWriter` напрямую записывает JSON в `HttpServletResponse`.
Он нужен для слоёв, где нельзя вернуть `ResponseEntity`, прежде всего для Spring Security filter chain.

Writer выполняет следующие действия:

1. Проверяет `response.isCommitted()`.
2. Устанавливает HTTP-статус.
3. Устанавливает UTF-8.
4. Устанавливает `Content-Type: application/json`.
5. Создаёт `ApiErrorResponse` через общую фабрику.
6. Сериализует ответ через общий `ObjectMapper` приложения.

Проверка `response.isCommitted()` защищает от попытки повторно изменить уже отправленный ответ.

---

## 9. `GlobalExceptionHandler`: центральный MVC-обработчик

Класс объявлен как:

```java
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler
```

Он перехватывает исключения, дошедшие до Spring MVC, и преобразует их в единый контракт.

### Почему в нём есть `@ExceptionHandler(Exception.class)`

Это последняя защитная граница.
Неизвестное исключение не должно привести к:

- HTML-странице стандартной ошибки;
- возврату stack trace клиенту;
- раскрытию внутреннего имени класса;
- отличающемуся формату JSON.

Общий обработчик:

- пишет полную ошибку в серверный лог;
- возвращает `500 INTERNAL_SERVER_ERROR`;
- не раскрывает реальную причину клиенту.

```json
{
  "status": 500,
  "error": "INTERNAL_SERVER_ERROR",
  "message": "Внутренняя ошибка сервера"
}
```

Специализированные методы находятся в том же advice, поэтому Spring может выбрать наиболее подходящий тип до fallback-обработчика.

---

## 10. Доменные исключения

### 10.1. `ResourceNotFoundException`

Используется, когда запрошенная сущность отсутствует или недоступна в пределах текущего tenant-контекста.

Пример:

```java
ChatSession chat = chatRepository.findByIdAndOrganizationId(
        chatId,
        organizationId
).orElseThrow(() -> new ResourceNotFoundException("Чат не найден"));
```

Ответ:

```http
HTTP/1.1 404 Not Found
```

```json
{
  "error": "NOT_FOUND",
  "message": "Чат не найден"
}
```

В multi-tenant приложении часто правильно возвращать `404`, а не `403`, если раскрытие существования чужого ресурса нежелательно.

### 10.2. `BadRequestException`

Используется для семантически некорректного клиентского запроса, который нельзя выразить только Bean Validation-аннотациями.

Пример:

```java
if (request.startDate().isAfter(request.endDate())) {
    throw new BadRequestException(
            "Дата начала не может быть позже даты окончания"
    );
}
```

Ответ:

```http
HTTP/1.1 400 Bad Request
```

```json
{
  "error": "BAD_REQUEST",
  "message": "Дата начала не может быть позже даты окончания"
}
```

### 10.3. `ForbiddenOperationException`

Используется, когда пользователь аутентифицирован, но конкретная бизнес-операция ему запрещена.

Пример:

```java
if (!currentUser.canManage(targetUser)) {
    throw new ForbiddenOperationException(
            "Недостаточно прав для изменения пользователя"
    );
}
```

Ответ:

```http
HTTP/1.1 403 Forbidden
```

### 10.4. `ConflictException`

Базовое доменное исключение для конфликтов состояния.

Примеры:

- email уже используется;
- организация с таким slug уже существует;
- операция противоречит текущему состоянию сущности;
- повторное выполнение уже завершённой команды.

Ответ:

```http
HTTP/1.1 409 Conflict
```

```json
{
  "error": "CONFLICT",
  "message": "Пользователь с таким email уже существует"
}
```

`ChatBusyException` наследуется от `ConflictException`, но имеет отдельный обработчик и отдельный машинный код.

---

## 11. Блокировка чата

SafeAI Gateway может запрещать параллельную обработку нескольких запросов в одном чате.
Это предотвращает:

- нарушение порядка сообщений;
- одновременную запись двух assistant-ответов;
- конфликт usage accounting;
- повреждение контекста диалога;
- гонки при обновлении chat session.

### 11.1. `ChatBusyException`

Означает, что механизм блокировки работает, но чат уже занят другим запросом.

Это ожидаемый конфликт состояния, поэтому возвращается:

```http
HTTP/1.1 409 Conflict
```

```json
{
  "error": "CHAT_BUSY",
  "message": "Чат занят другим запросом"
}
```

Frontend может обработать код отдельно:

- временно заблокировать кнопку отправки;
- показать сообщение;
- дождаться завершения текущей генерации;
- повторить запрос только после действия пользователя или подтверждённого завершения.

Автоматический немедленный retry без задержки нежелателен: он создаст дополнительную нагрузку и снова попадёт в ту же блокировку.

### 11.2. `ChatLockUnavailableException`

Означает не занятость чата, а недоступность инфраструктуры блокировок, например Redis.

Возвращается:

```http
HTTP/1.1 503 Service Unavailable
```

```json
{
  "error": "CHAT_LOCK_UNAVAILABLE",
  "message": "Сервис блокировки чата временно недоступен"
}
```

Реальное сообщение Redis клиенту не возвращается.
Полная причина и stack trace записываются в лог:

```text
Chat lock service unavailable: path=..., requestId=...
```

Это принцип fail-closed: если система не может гарантировать эксклюзивную обработку чата, она не продолжает операцию в небезопасном режиме.

---

## 12. Ограничение частоты запросов

### 12.1. `RateLimitExceededException`

Исключение содержит:

```java
private final long retryAfterSeconds;
```

Значение вычисляется из `Duration` и не может быть отрицательным.

Пример:

```java
throw new RateLimitExceededException(
        "Превышен лимит запросов",
        Duration.ofSeconds(30)
);
```

Ответ:

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 30
```

```json
{
  "error": "RATE_LIMIT_EXCEEDED",
  "message": "Превышен лимит запросов"
}
```

Заголовок `Retry-After` добавляется только при значении больше нуля.

Frontend должен ориентироваться на заголовок, а не пытаться извлекать время из текста сообщения.

### 12.2. `RateLimitUnavailableException`

Означает отказ инфраструктуры rate limiting, а не превышение лимита пользователем.

Возвращается:

```http
HTTP/1.1 503 Service Unavailable
```

```json
{
  "error": "RATE_LIMIT_UNAVAILABLE",
  "message": "Сервис ограничения запросов временно недоступен"
}
```

Причина логируется как server-side ошибка.

Выбор fail-open или fail-closed должен приниматься в самом rate-limit сервисе в зависимости от endpoint.
Обработчик исключения только стандартизирует уже принятое решение об отказе.

---

## 13. Refresh token

### 13.1. `InvalidRefreshTokenException`

Базовое исключение для недействительного refresh token.

Причинами могут быть:

- подпись не прошла проверку;
- токен отсутствует в хранилище;
- токен отозван;
- token family отозвана;
- token version пользователя изменилась;
- организация или пользователь отключены.

Ответ:

```http
HTTP/1.1 401 Unauthorized
```

```json
{
  "error": "INVALID_REFRESH_TOKEN",
  "message": "Недействительный refresh token"
}
```

Внутренняя причина намеренно не раскрывается клиенту.

### 13.2. `ExpiredRefreshTokenException`

Специализированный наследник для истёкшего refresh token.

Ответ:

```json
{
  "error": "EXPIRED_REFRESH_TOKEN",
  "message": "Refresh token истёк"
}
```

Отдельный код позволяет frontend отличить нормальное истечение сессии от иных проблем токена.

### 13.3. `RefreshTokenReuseDetectedException`

Возникает при обнаружении повторного использования уже ротированного refresh token.
Это security-событие, которое может означать кражу токена или повторную отправку старого токена.

Исключение содержит технические идентификаторы:

```text
userId
organizationId
tokenFamilyId
```

Они записываются в warning-лог, но не возвращаются клиенту.

Клиент получает нейтральный ответ:

```http
HTTP/1.1 401 Unauthorized
```

```json
{
  "error": "INVALID_REFRESH_TOKEN",
  "message": "Недействительный refresh token"
}
```

Это не даёт злоумышленнику определить точную внутреннюю причину отказа.

Само обнаружение reuse обычно должно сопровождаться отзывом всей token family и, при принятой политике безопасности, принудительной повторной аутентификацией.

---

## 14. Валидация запросов

Модуль обрабатывает несколько разных механизмов валидации Spring.
Они похожи с точки зрения клиента, но возникают на разных этапах.

### 14.1. `MethodArgumentNotValidException`

Обычно возникает при `@Valid @RequestBody`.

DTO:

```java
public record CreateUserRequest(
        @NotBlank(message = "Email обязателен")
        @Email(message = "Некорректный формат email")
        String email,

        @NotBlank(message = "Имя обязательно")
        String name
) {
}
```

Контроллер:

```java
@PostMapping
public UserResponse create(
        @Valid @RequestBody CreateUserRequest request
) {
    return userService.create(request);
}
```

Ошибка преобразуется в:

```json
{
  "error": "VALIDATION_ERROR",
  "fieldErrors": {
    "email": [
      "Некорректный формат email"
    ],
    "name": [
      "Имя обязательно"
    ]
  }
}
```

### 14.2. `BindException`

Возникает при привязке и валидации model attributes, form/query объектов и некоторых не-body аргументов.

Обрабатывается тем же механизмом `BindingResult`.

### 14.3. `ConstraintViolationException`

Может возникнуть при method validation в сервисах или при прямой проверке constraints.

Путь вида:

```text
createUser.request.email
```

нормализуется до последнего сегмента:

```text
email
```

Это делает ключ удобным для frontend.

### 14.4. `HandlerMethodValidationException`

Современный Spring MVC может валидировать параметры контроллера без формирования `MethodArgumentNotValidException`.

Обработчик собирает ошибки:

- отдельных параметров;
- полей вложенного объекта;
- глобальных object-level constraints.

Если имя параметра недоступно, используется:

```text
arg0
arg1
```

Чтобы Java сохраняла реальные имена параметров, рекомендуется включить компиляцию с флагом `-parameters`.
Для Maven:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <parameters>true</parameters>
    </configuration>
</plugin>
```

### 14.5. Валидация возвращаемого значения

`HandlerMethodValidationException` может означать не ошибку клиента, а нарушение constraints в возвращаемом значении контроллера.

Например, сервер обещал вернуть непустое поле, но сформировал некорректный response DTO.
Это дефект backend, поэтому обработчик проверяет:

```java
exception.isForReturnValue()
```

и возвращает:

```http
HTTP/1.1 500 Internal Server Error
```

а не `400`.

Полная ошибка логируется.
Клиент получает безопасный общий текст.

### 14.6. Глобальные ошибки DTO

Class-level constraint не относится к одному полю.
Например, проверка соответствия `password` и `passwordConfirmation`.

Такая ошибка помещается под ключ:

```text
_global
```

Пример:

```json
{
  "fieldErrors": {
    "_global": [
      "Пароль и подтверждение пароля не совпадают"
    ]
  }
}
```

Если обработчик не смог определить конкретный ключ, используется:

```text
request
```

---

## 15. Ошибки формата HTTP-запроса

### 15.1. Неверный тип параметра

Например:

```http
GET /api/users?page=abc
```

при ожидаемом `int page`.

Ответ:

```json
{
  "error": "BAD_REQUEST",
  "message": "Некорректный параметр запроса: page"
}
```

### 15.2. Отсутствующий query parameter

Для обязательного параметра:

```java
@RequestParam UUID organizationId
```

возвращается:

```json
{
  "error": "BAD_REQUEST",
  "message": "Не указан обязательный параметр запроса: organizationId"
}
```

### 15.3. Отсутствующий HTTP-заголовок

Для обязательного:

```java
@RequestHeader("Idempotency-Key") String idempotencyKey
```

возвращается:

```json
{
  "error": "BAD_REQUEST",
  "message": "Не указан обязательный заголовок запроса: Idempotency-Key"
}
```

### 15.4. Некорректный JSON

`HttpMessageNotReadableException` возникает, например, если:

- JSON синтаксически сломан;
- строка передана в поле типа UUID;
- неизвестное значение передано в enum;
- дата имеет неверный формат;
- число не помещается в ожидаемый тип.

Клиенту возвращается нейтральное сообщение:

```json
{
  "error": "BAD_REQUEST",
  "message": "Некорректное тело запроса. Проверьте формат JSON и типы полей"
}
```

Детали Jackson exception записываются только в debug-лог.

Не следует возвращать клиенту `exception.getMostSpecificCause().getMessage()`, потому что оно может раскрыть внутренние имена Java-классов, полей или настройки десериализации.

---

## 16. HTTP protocol errors

### 16.1. Неподдерживаемый метод — 405

Например, endpoint принимает только `POST`, а клиент отправил `GET`.

Ответ:

```http
HTTP/1.1 405 Method Not Allowed
Allow: POST
```

```json
{
  "error": "METHOD_NOT_ALLOWED",
  "message": "HTTP-метод не поддерживается для этого endpoint"
}
```

Обработчик использует заголовки исходного Spring exception, поэтому `Allow` не теряется.

### 16.2. Неподдерживаемый Content-Type — 415

Например, endpoint ожидает JSON, а клиент отправляет `text/plain`.

Ответ:

```json
{
  "error": "UNSUPPORTED_MEDIA_TYPE",
  "message": "Тип содержимого запроса не поддерживается"
}
```

Системные заголовки Spring также сохраняются.

### 16.3. Неподдерживаемый Accept — 406

Если клиент запросил формат ответа, который endpoint не умеет производить:

```json
{
  "error": "NOT_ACCEPTABLE",
  "message": "Запрошенный формат ответа не поддерживается"
}
```

### 16.4. Неизвестный endpoint или статический ресурс — 404

Обрабатываются:

```text
NoHandlerFoundException
NoResourceFoundException
```

Ответ имеет тот же формат, что и доменный `ResourceNotFoundException`:

```json
{
  "error": "NOT_FOUND",
  "message": "Ресурс не найден"
}
```

В зависимости от версии и настроек Spring Boot отсутствие handler может представляться разными exception-классами, поэтому поддержаны оба варианта.

---

## 17. Ошибки базы данных и конкурентное обновление

### 17.1. Optimistic locking

Обрабатываются:

```text
OptimisticLockingFailureException
OptimisticLockException
```

Они возникают, когда сущность была изменена между чтением и сохранением, обычно при использовании `@Version`.

Пример сценария:

1. Пользователь A открыл организацию версии 5.
2. Пользователь B изменил её, версия стала 6.
3. Пользователь A отправил сохранение старой версии 5.
4. Hibernate обнаружил конфликт.

Ответ:

```http
HTTP/1.1 409 Conflict
```

```json
{
  "error": "CONFLICT",
  "message": "Данные были изменены другим пользователем. Обновите данные и повторите операцию"
}
```

Это не 500, потому что сервер работает корректно: он обнаружил конфликт конкурентного изменения.

Исключение логируется на уровне `DEBUG`, так как такой конфликт может быть ожидаемой частью многопользовательской работы.

### 17.2. `DataIntegrityViolationException`

Может возникнуть при нарушении:

- unique constraint;
- foreign key constraint;
- not-null constraint;
- check constraint;
- других ограничений базы данных.

Клиент получает общий ответ:

```json
{
  "error": "CONFLICT",
  "message": "Конфликт данных"
}
```

Нельзя без фильтрации возвращать сообщение JDBC/PostgreSQL, потому что оно может содержать:

- имя таблицы;
- имя индекса;
- имя constraint;
- SQL-фрагмент;
- внутреннее значение данных.

Для ожидаемых бизнес-конфликтов предпочтительно заранее проверять условие и бросать доменный `ConflictException` с понятным безопасным текстом.
`DataIntegrityViolationException` остаётся защитой от гонок и непредвиденных нарушений ограничений.

---

## 18. `ResponseStatusException`

Иногда сторонний или инфраструктурный код бросает:

```java
throw new ResponseStatusException(
        HttpStatus.BAD_GATEWAY,
        "AI provider unavailable"
);
```

Обработчик использует `HttpStatusCode`, а не только enum `HttpStatus`.
Это позволяет корректно сохранить числовой статус, даже если он нестандартный.

Правила:

- для 4xx может использоваться безопасный `reason`;
- для 5xx реальный `reason` клиенту не возвращается;
- заголовки исходного исключения сохраняются;
- 5xx логируются как `ERROR`;
- 4xx логируются как `DEBUG`.

Для 5xx ответ всегда нейтрален:

```json
{
  "error": "INTERNAL_SERVER_ERROR",
  "message": "Внутренняя ошибка сервера"
}
```

Это предотвращает утечку внутренних подробностей.

---

## 19. Spring Security: обязательное подключение

Добавить классы в package недостаточно.
Их необходимо подключить к существующему `SecurityFilterChain`.

Пример:

```java
@Bean
SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        RestAuthenticationEntryPoint authenticationEntryPoint,
        RestAccessDeniedHandler accessDeniedHandler
) throws Exception {
    return http
            // Остальная конфигурация безопасности проекта

            .exceptionHandling(exceptionHandling -> exceptionHandling
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler)
            )

            .build();
}
```

### `RestAuthenticationEntryPoint`

Используется, когда пользователь не аутентифицирован.
Возвращает:

```http
HTTP/1.1 401 Unauthorized
```

```json
{
  "error": "UNAUTHORIZED",
  "message": "Требуется авторизация"
}
```

### `RestAccessDeniedHandler`

Используется, когда пользователь аутентифицирован, но не имеет требуемых прав.
Возвращает:

```http
HTTP/1.1 403 Forbidden
```

```json
{
  "error": "FORBIDDEN",
  "message": "Доступ запрещён"
}
```

### Почему аналогичные методы остаются в `GlobalExceptionHandler`

В advice также есть обработчики:

```text
AuthenticationException
AuthorizationDeniedException
AccessDeniedException
```

Они нужны как резервный вариант для исключений, возникших уже в MVC-слое, например при method security или при явном выбрасывании исключения после входа в controller pipeline.

Итого:

- ошибки в filter chain обрабатывают security handlers;
- ошибки, дошедшие до MVC, обрабатывает `GlobalExceptionHandler`.

---

## 20. Требование к `RequestIdFilter`

Модуль ожидает, что `RequestIdFilter` установит идентификатор запроса в атрибут servlet request:

```java
request.setAttribute(
        RequestIdFilter.REQUEST_ID_ATTRIBUTE,
        requestId
);
```

Минимальный контракт класса:

```java
public static final String REQUEST_ID_ATTRIBUTE = "requestId";
```

Рекомендуемый порядок выполнения фильтра — как можно раньше, до security и бизнес-фильтров, чтобы `requestId` присутствовал даже в ответах 401, 403 и 500.

Полезно также:

- принимать доверенный `X-Request-ID` только после валидации либо генерировать UUID самостоятельно;
- возвращать request id в заголовке ответа;
- помещать его в MDC для автоматического добавления во все строки лога;
- очищать MDC в `finally`.

Пример корреляции:

```json
{
  "requestId": "7ef13345-3ed5-4dd3-af55-cdca71c15e51"
}
```

```text
ERROR requestId=7ef13345-3ed5-4dd3-af55-cdca71c15e51 Unexpected request processing error...
```

Так support или разработчик может найти серверную причину по идентификатору из клиентского ответа.

---

## 21. `TimeConfig`

Конфигурация объявляет один bean:

```java
@Bean
public Clock clock() {
    return Clock.systemUTC();
}
```

UTC используется для технических временных меток независимо от timezone сервера.

### Важно

В Spring-контексте должен быть только один подходящий `Clock` bean, если не используются `@Qualifier` или `@Primary`.

Если проект уже содержит собственный `Clock`, файл `TimeConfig.java` копировать не нужно.

В тестах production bean можно заменить фиксированным:

```java
@TestConfiguration
class FixedClockConfig {

    @Bean
    @Primary
    Clock fixedClock() {
        return Clock.fixed(
                Instant.parse("2026-07-21T07:30:00Z"),
                ZoneOffset.UTC
        );
    }
}
```

---

## 22. Матрица основных ошибок

| Ситуация | HTTP | `error` |
|---|---:|---|
| Некорректный бизнес-запрос | 400 | `BAD_REQUEST` |
| Ошибка Bean Validation | 400 | `VALIDATION_ERROR` |
| Пользователь не аутентифицирован | 401 | `UNAUTHORIZED` |
| Refresh token истёк | 401 | `EXPIRED_REFRESH_TOKEN` |
| Refresh token недействителен или повторно использован | 401 | `INVALID_REFRESH_TOKEN` |
| Недостаточно прав | 403 | `FORBIDDEN` |
| Ресурс не найден | 404 | `NOT_FOUND` |
| HTTP-метод не поддерживается | 405 | `METHOD_NOT_ALLOWED` |
| Формат ответа не поддерживается | 406 | `NOT_ACCEPTABLE` |
| Конфликт данных | 409 | `CONFLICT` |
| Чат уже занят | 409 | `CHAT_BUSY` |
| Content-Type не поддерживается | 415 | `UNSUPPORTED_MEDIA_TYPE` |
| Лимит запросов превышен | 429 | `RATE_LIMIT_EXCEEDED` |
| Сервис блокировки чата недоступен | 503 | `CHAT_LOCK_UNAVAILABLE` |
| Rate-limit infrastructure недоступна | 503 | `RATE_LIMIT_UNAVAILABLE` |
| Неожиданная внутренняя ошибка | 500 | `INTERNAL_SERVER_ERROR` |

---

## 23. Что копировать и что удалить

### Удалить из старой реализации

```text
ChatAvailabilityExceptionHandler.java
OptimisticLockExceptionHandler.java
```

Также удалить старую версию `GlobalExceptionHandler`, если она заменяется файлом из этого модуля.

### Скопировать

```text
common/config/TimeConfig.java
common/exception/*
common/security/RestAuthenticationEntryPoint.java
common/security/RestAccessDeniedHandler.java
```

`TimeConfig.java` копируется только при отсутствии существующего `Clock` bean.

### Не создавать второй `RequestIdFilter`

Нужно использовать существующий фильтр проекта.
Он должен содержать публичную константу:

```java
RequestIdFilter.REQUEST_ID_ATTRIBUTE
```

### Изменить существующий `SecurityFilterChain`

Подключить:

```java
.authenticationEntryPoint(authenticationEntryPoint)
.accessDeniedHandler(accessDeniedHandler)
```

Не следует создавать второй конкурирующий `SecurityFilterChain` только ради этих обработчиков.

---

## 24. Зависимости

Основные классы используют:

```text
spring-boot-starter-web
spring-boot-starter-validation
spring-boot-starter-security
spring-boot-starter-data-jpa
Jackson ObjectMapper
Lombok
```

Пример фрагмента `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>

<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

`spring-boot-starter-test` уже включает JUnit 5, AssertJ, Mockito, Spring Test и MockMvc, которые используются в готовых тестах этого комплекта.

В `SecurityErrorResponseIntegrationTest` намеренно не используются `@WithMockUser` и request post-processors из `spring-security-test`: тест проходит через настоящую HTTP Basic-аутентификацию тестового `SecurityFilterChain`. Поэтому отдельная зависимость `spring-security-test` для приложенных тестов не обязательна. Если она уже есть в проекте, удалять её не нужно.

Если JPA в приложении отсутствует, необходимо удалить обработчики и импорты:

```text
OptimisticLockException
OptimisticLockingFailureException
DataIntegrityViolationException
```

Но для текущего SafeAI Gateway с PostgreSQL и Spring Data JPA они нужны.

---

## 25. Как правильно добавлять новое исключение

Допустим, требуется отдельная ошибка для отключённой организации.

### Шаг 1. Определить, нужен ли отдельный клиентский код

Если frontend должен выполнить специальное действие, например принудительно завершить сессию, отдельный код оправдан.

Добавить:

```java
ORGANIZATION_DISABLED
```

в `ApiErrorCode`.

### Шаг 2. Создать исключение

```java
public class OrganizationDisabledException extends RuntimeException {

    public OrganizationDisabledException(String message) {
        super(message);
    }
}
```

### Шаг 3. Добавить конкретный handler выше fallback

```java
@ExceptionHandler(OrganizationDisabledException.class)
public ResponseEntity<ApiErrorResponse> handleOrganizationDisabled(
        OrganizationDisabledException exception,
        HttpServletRequest request
) {
    return buildResponse(
            HttpStatus.FORBIDDEN,
            ApiErrorCode.ORGANIZATION_DISABLED,
            safeMessage(
                    exception.getMessage(),
                    "Организация отключена"
            ),
            request,
            null
    );
}
```

### Шаг 4. Добавить тесты

Проверить:

- HTTP-статус;
- код `error`;
- безопасное сообщение;
- наличие `requestId`;
- `fieldErrors` равно `{}`;
- исключение не попало в общий 500 handler.

### Когда не нужен новый exception-класс

Не следует создавать класс только ради другого текста, если статус и клиентская реакция остаются теми же.
Можно использовать существующий:

```java
throw new ConflictException("Организация уже отключена");
```

---

## 26. Правила безопасных сообщений

Сообщение, возвращаемое клиенту, не должно содержать:

- SQL;
- имена таблиц, колонок и constraints;
- Redis host, port или command;
- stack trace;
- package/class names;
- абсолютные пути файлов;
- access token, refresh token или cookie;
- персональные данные другого пользователя;
- сведения о существовании ресурса другого tenant;
- внутренние идентификаторы security-механизмов без необходимости.

Правильно:

```text
Недействительный refresh token
```

Неправильно:

```text
Refresh token family 0b3... is revoked because hash mismatch in redis node 10.0.1.7
```

Техническая информация должна находиться в структурированных логах, связанных через `requestId`.

---

## 27. Уровни логирования

В текущей реализации используется следующая логика.

### `ERROR`

Для инфраструктурных и внутренних ошибок:

- chat lock service unavailable;
- rate limit service unavailable;
- валидация возвращаемого значения контроллера;
- `ResponseStatusException` с 5xx;
- неизвестное исключение.

### `WARN`

Для событий, требующих внимания, но не обязательно означающих падение сервиса:

- refresh token reuse;
- нарушение integrity constraint.

### `DEBUG`

Для ожидаемых или диагностических клиентских ситуаций:

- malformed JSON;
- authentication/access denied в MVC;
- истёкший или недействительный refresh token;
- optimistic lock conflict;
- `ResponseStatusException` с 4xx.

Не рекомендуется логировать все 4xx на уровне `ERROR`, иначе журналы будут заполнены пользовательскими ошибками и реальная авария потеряется в шуме.

---

## 28. Рекомендации для frontend

Frontend должен использовать:

```typescript
export type ApiErrorResponse = {
    timestamp: string
    status: number
    error: string
    message: string
    path: string
    requestId: string | null
    fieldErrors: Record<string, string[]>
}
```

Пример обработки:

```typescript
switch (error.error) {
    case 'VALIDATION_ERROR':
        form.setErrors(error.fieldErrors)
        break

    case 'CHAT_BUSY':
        showToast('Дождитесь завершения текущего ответа')
        break

    case 'RATE_LIMIT_EXCEEDED':
        showToast(error.message)
        break

    case 'EXPIRED_REFRESH_TOKEN':
    case 'INVALID_REFRESH_TOKEN':
    case 'UNAUTHORIZED':
        redirectToLogin()
        break

    default:
        showToast(error.message)
}
```

Frontend не должен показывать пользователю `requestId` постоянно, но его полезно выводить в раскрываемом блоке «Техническая информация» или рядом с сообщением об ошибке 500.

---

## 29. Готовый комплект unit- и integration-тестов

В комплект уже добавлены четыре тестовых класса:

```text
ApiErrorResponseFactoryTest
GlobalExceptionHandlerTest
GlobalExceptionHandlerIntegrationTest
SecurityErrorResponseIntegrationTest
```

Они проверяют разные уровни системы. Это важно: прямой вызов Java-метода и прохождение исключения через настоящий Spring MVC pipeline — не одно и то же.

### 29.1. `ApiErrorResponseFactoryTest`

Проверяет фабрику и неизменяемость контракта:

- фиксированный `timestamp` через `Clock.fixed(...)`;
- преобразование `ApiErrorCode` в стабильную строку;
- получение `requestId` из request attribute;
- нормализацию и удаление дубликатов в `fieldErrors`;
- пустой объект `{}` вместо `null`;
- невозможность изменить `fieldErrors` после создания ответа;
- запрет пустого пользовательского сообщения.

### 29.2. `GlobalExceptionHandlerTest`

Это быстрые unit-тесты. Они создают `GlobalExceptionHandler` напрямую и вызывают конкретные методы обработчика без запуска Spring context.

Проверяются:

```text
ChatBusyException                 → 409 CHAT_BUSY
ChatLockUnavailableException      → 503 CHAT_LOCK_UNAVAILABLE
OptimisticLockingFailureException → 409 CONFLICT
RuntimeException                  → 500 INTERNAL_SERVER_ERROR
```

Также проверяются:

- `Retry-After` для rate limit;
- недоступность rate-limit storage;
- DTO validation и несколько ошибок одного поля;
- глобальные ошибки объекта через `_global`;
- `ConstraintViolationException`;
- отсутствующий query parameter и header;
- `BadRequestException`, `ResourceNotFoundException`, `ConflictException`;
- запрещённая бизнес-операция;
- expired/invalid/reused refresh token;
- `ResponseStatusException`;
- отсутствие утечки внутренних сообщений Redis, БД и runtime exception.

Unit-тесты нужны для быстрого локального feedback и точной диагностики конкретного метода.

### 29.3. `GlobalExceptionHandlerIntegrationTest`

Это MVC slice integration-тест на `MockMvc` и реальном механизме выбора `@ExceptionHandler`.

Внутри теста есть технический controller, который намеренно выбрасывает исключения. Запрос проходит по цепочке:

```text
MockMvc
    → DispatcherServlet
    → test controller
    → исключение
    → ExceptionHandlerExceptionResolver
    → GlobalExceptionHandler
    → ApiErrorResponseFactory
    → JSON
```

Именно этот тест доказывает, что проблема с конкурирующими advice действительно устранена.

Обязательные сценарии:

| Исключение | Ожидаемый результат |
|---|---|
| `ChatBusyException` | `409`, `CHAT_BUSY` |
| `ChatLockUnavailableException` | `503`, `CHAT_LOCK_UNAVAILABLE` |
| `OptimisticLockingFailureException` | `409`, `CONFLICT`, сообщение о повторной загрузке данных |
| неожиданное `RuntimeException` | `500`, `INTERNAL_SERVER_ERROR`, без исходного текста исключения |

Дополнительно integration-тест проверяет:

- обычный `ConflictException` остаётся `CONFLICT`;
- `@Valid @RequestBody` формирует `fieldErrors`;
- сломанный JSON возвращает безопасный `400 BAD_REQUEST`;
- rate limit сохраняет заголовок `Retry-After`;
- неподдерживаемый HTTP-метод возвращает `405 METHOD_NOT_ALLOWED` и `Allow`;
- ошибка валидации возвращаемого значения controller считается серверной ошибкой `500`.

Для MVC-теста используется:

```java
@AutoConfigureMockMvc(addFilters = false)
```

Это сделано намеренно: данный класс тестирует MVC exception handling, а не Spring Security. Security filter chain проверяется отдельно, иначе отсутствие тестовой аутентификации могло бы вернуть 401 ещё до вызова controller и скрыть тестируемое исключение.

### 29.4. `SecurityErrorResponseIntegrationTest`

Этот тест, наоборот, запускает security filters и проверяет ошибки до MVC controller:

```text
запрос без аутентификации
    → RestAuthenticationEntryPoint
    → 401 UNAUTHORIZED JSON

пользователь ROLE_USER обращается к ADMIN endpoint
    → RestAccessDeniedHandler
    → 403 FORBIDDEN JSON
```

Проверяется:

- статус 401/403;
- `Content-Type: application/json`;
- стабильный `error`;
- безопасный `message`;
- `path` и `requestId`;
- пустой объект `fieldErrors`;
- отсутствие стандартной HTML-страницы Spring Security.

Тест использует отдельного in-memory пользователя только внутри test context:

```text
username: user
password: password
role: USER
```

Эти данные не относятся к production-конфигурации и не попадают в основное приложение.

### 29.5. Почему используется фиксированный `Clock`

Все тесты получают время:

```java
Clock.fixed(
        Instant.parse("2026-06-12T12:00:00Z"),
        ZoneOffset.UTC
)
```

Поэтому поле `timestamp` проверяется точно и тесты не зависят от текущего времени, timezone машины или скорости выполнения CI.

### 29.6. Как запускаются тесты

Все тесты:

```bash
./mvnw test
```

Только unit-тесты handler и factory:

```bash
./mvnw -Dtest=GlobalExceptionHandlerTest,ApiErrorResponseFactoryTest test
```

Только MVC integration-тест:

```bash
./mvnw -Dtest=GlobalExceptionHandlerIntegrationTest test
```

Только security integration-тест:

```bash
./mvnw -Dtest=SecurityErrorResponseIntegrationTest test
```

Для Windows PowerShell:

```powershell
.\mvnw.cmd test
```

### 29.7. Что удалить из старых тестов

Старый тест:

```text
ChatAvailabilityExceptionHandlerTest.java
```

необходимо удалить, потому что production-класса `ChatAvailabilityExceptionHandler` больше нет.

Его сценарии перенесены в:

```text
GlobalExceptionHandlerTest
GlobalExceptionHandlerIntegrationTest
```

Старый `OptimisticLockExceptionHandlerTest`, если он существовал, также следует удалить или заменить integration-сценарием из нового комплекта.

### 29.8. Граница этих integration-тестов

`@WebMvcTest` — это integration-тест web/security slice, а не полный end-to-end тест всего приложения. Он не поднимает PostgreSQL, Redis, Kafka и реальные AI providers.

Для этого блока такой уровень является оптимальным, потому что проверяемая задача — выбор exception handler, HTTP-статус, заголовки и JSON-контракт.

Дополнительно в основном проекте полезно оставить один или два `@SpringBootTest` smoke-теста для проверки, что production `SecurityFilterChain`, `RequestIdFilter` и все реальные beans собираются вместе без конфликтов.

---

## 30. Как устроена MockMvc-проверка выбора handler

Полная реализация находится в файле:

```text
src/test/java/ru/safeai/gateway/common/exception/
GlobalExceptionHandlerIntegrationTest.java
```

Ключевой сценарий выглядит так:

```java
mockMvc.perform(get("/test/errors/chat-busy")
                .requestAttr(
                        RequestIdFilter.REQUEST_ID_ATTRIBUTE,
                        "integration-request-id"
                ))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(
                MediaType.APPLICATION_JSON
        ))
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.error").value("CHAT_BUSY"))
        .andExpect(jsonPath("$.message").value(
                "В этот чат уже отправляется сообщение"
        ));
```

Важно, что тест не вызывает `handleChatBusy(...)` напрямую. Технический controller выбрасывает `ChatBusyException`, после чего Spring самостоятельно должен найти правильный `@ExceptionHandler`.

Если специализированный mapping будет случайно удалён или снова появится конкурирующий advice с неправильным приоритетом, integration-тест получит `CONFLICT` вместо `CHAT_BUSY` и упадёт.

Для `timestamp` используется test-only `Clock.fixed(...)`, объявленный внутри `FixedClockConfiguration`. Production `TimeConfig` в этот test context не импортируется, поэтому конфликта двух `Clock` beans нет.

---

## 31. Типичные ошибки интеграции

### Ошибка 1. Старые advice оставлены в проекте

Симптом: результат снова зависит от порядка обработчиков.

Решение: удалить:

```text
ChatAvailabilityExceptionHandler
OptimisticLockExceptionHandler
```

### Ошибка 2. Security handlers скопированы, но не подключены

Симптом: 401/403 возвращаются в другом формате или пустым ответом.

Решение: добавить их в `.exceptionHandling(...)` существующего `SecurityFilterChain`.

### Ошибка 3. Создан второй `Clock` bean

Симптом:

```text
NoUniqueBeanDefinitionException: expected single matching bean but found 2
```

Решение: оставить один `Clock` или использовать `@Primary`/`@Qualifier`.

### Ошибка 4. Нет `RequestIdFilter.REQUEST_ID_ATTRIBUTE`

Симптом: проект не компилируется или `requestId` всегда `null`.

Решение: привести существующий RequestIdFilter к ожидаемому контракту.

### Ошибка 5. Старая версия Spring Boot

Симптом: отсутствуют классы:

```text
HandlerMethodValidationException
ParameterErrors
```

Решение: обновить Spring Boot до совместимой версии либо адаптировать обработчик method validation под используемую версию Spring.

### Ошибка 6. Frontend ожидает `fieldErrors: null`

Симптом: тесты frontend падают после стандартизации.

Решение: обновить тип на:

```typescript
Record<string, string[]>
```

и всегда ожидать объект.

### Ошибка 7. Доменное сообщение содержит внутренние данные

Симптом: безопасный handler корректно возвращает `exception.getMessage()`, но само сообщение было сформировано небезопасно.

Решение: сообщения доменных исключений должны изначально быть предназначены для клиента. Для технической причины использовать `cause` и логирование.

---

## 32. Итоговая схема поведения

```text
Исключение известно и ожидаемо?
    │
    ├── Да → специализированный @ExceptionHandler
    │          ├── корректный HTTP status
    │          ├── стабильный ApiErrorCode
    │          ├── безопасный message
    │          └── необходимые HTTP headers
    │
    └── Нет → @ExceptionHandler(Exception.class)
               ├── полный stack trace в server log
               ├── path + requestId в log
               └── безопасный 500 клиенту
```

Для security filter chain:

```text
Не аутентифицирован
    → RestAuthenticationEntryPoint
    → 401 UNAUTHORIZED

Аутентифицирован, но доступ запрещён
    → RestAccessDeniedHandler
    → 403 FORBIDDEN
```

---

## 33. Production checklist

Перед merge или deploy проверить:

- [ ] Старые специализированные `@RestControllerAdvice` удалены.
- [ ] В приложении остался один основной `GlobalExceptionHandler`.
- [ ] `RestAuthenticationEntryPoint` подключён к `SecurityFilterChain`.
- [ ] `RestAccessDeniedHandler` подключён к `SecurityFilterChain`.
- [ ] В приложении существует ровно один подходящий `Clock` bean.
- [ ] `RequestIdFilter` выполняется до security/business обработки.
- [ ] `requestId` попадает в JSON и серверные логи.
- [ ] Frontend использует `error`, а не текст `message`, для ветвления.
- [ ] Frontend ожидает `fieldErrors` как объект, а не `null`.
- [ ] Для 429 возвращается `Retry-After`.
- [ ] Для 405 сохраняется `Allow`.
- [ ] Ошибки базы данных и Redis не раскрываются клиенту.
- [ ] `GlobalExceptionHandlerIntegrationTest`: `ChatBusyException → 409 CHAT_BUSY`.
- [ ] `GlobalExceptionHandlerIntegrationTest`: optimistic locking → `409 CONFLICT`.
- [ ] `SecurityErrorResponseIntegrationTest`: JSON-ответы Spring Security 401 и 403.
- [ ] `GlobalExceptionHandlerIntegrationTest`: return-value validation → 500.
- [ ] Unit- и integration-тест подтверждают безопасный fallback 500 без утечки сообщения.

---

## 34. Краткий итог

Главный принцип реализации:

> Один MVC advice, один контракт ошибки, отдельная интеграция с Spring Security и никаких внутренних подробностей в клиентском ответе.

Такой подход делает поведение API:

- предсказуемым для frontend;
- удобным для тестирования;
- безопасным для production;
- наблюдаемым через `requestId`;
- расширяемым без конкуренции между advice beans.


---

## 35. Особенности тестов в Spring Boot 4.0.6

### 35.1. Почему IntelliJ не находил `WebMvcTest`

В Spring Boot 3 тестовые MVC-аннотации находились в старом пакете:

```java
org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
```

В Spring Boot 4 они перенесены в новый модуль и новый пакет:

```java
org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
```

Поэтому старые импорты дают ошибки:

```text
Cannot resolve symbol 'web'
Cannot resolve symbol 'WebMvcTest'
Cannot resolve symbol 'AutoConfigureMockMvc'
```

Правильные импорты для этого проекта:

```java
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
```

### 35.2. Какие зависимости нужны

В текущем `pom.xml` тестовые зависимости указаны правильно:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc-test</artifactId>
    <scope>test</scope>
</dependency>

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security-test</artifactId>
    <scope>test</scope>
</dependency>
```

Назначение зависимостей:

- `spring-boot-starter-test` — JUnit Jupiter, AssertJ, Mockito, Hamcrest и базовая тестовая инфраструктура;
- `spring-boot-starter-webmvc-test` — `@WebMvcTest`, `@AutoConfigureMockMvc`, MockMvc auto-configuration;
- `spring-boot-starter-security-test` — security request post-processors и интеграция Spring Security с MockMvc.

Добавлять старый `spring-boot-starter-web` или возвращать импорты Spring Boot 3 не нужно.

После изменения импортов в IntelliJ выполнить:

```text
Maven tool window → Reload All Maven Projects
```

Затем запустить:

```bash
./mvnw -U test
```

Если Maven Wrapper отсутствует:

```bash
mvn -U test
```

### 35.3. Почему убран ручной Basic Authorization header

Вместо собственного Base64-кодирования:

```java
.header("Authorization", basic("user", "password"))
```

используется штатный security test post-processor:

```java
.with(httpBasic("user", "password"))
```

с импортом:

```java
import static org.springframework.security.test.web.servlet.request
        .SecurityMockMvcRequestPostProcessors.httpBasic;
```

Это убирает вспомогательный метод `basic`, предупреждения о параметрах с постоянными значениями и ручную работу с кодировкой/Base64.

### 35.4. Почему убран `throws Exception` из `SecurityFilterChain` bean

В Spring Security 7 fluent API `HttpSecurity` и `build()` больше не требуют объявлять проверяемое `Exception` в этом методе. Поэтому конфигурация теста выглядит так:

```java
@Bean
SecurityFilterChain testSecurityFilterChain(
        HttpSecurity http,
        RestAuthenticationEntryPoint authenticationEntryPoint,
        RestAccessDeniedHandler accessDeniedHandler
) {
    return http
            // configuration
            .build();
}
```

Это устраняет предупреждение:

```text
Exception 'java.lang.Exception' is never thrown in the method
```

### 35.5. Почему используется `@TestConstructor`

Тестовые классы не являются production-компонентами `@Component` или `@Service`, но их экземпляры создаёт Spring TestContext Framework.

Чтобы явно включить constructor injection и не получать ложное предупреждение IntelliJ:

```java
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class GlobalExceptionHandlerIntegrationTest {

    private final MockMvc mockMvc;

    GlobalExceptionHandlerIntegrationTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }
}
```

В такой конфигурации `@Autowired` на конструкторе не требуется.

### 35.6. Почему `csrf` и `requestCache` отключаются method reference

В тестовой Basic Auth-конфигурации используются:

```java
.csrf(AbstractHttpConfigurer::disable)
.requestCache(AbstractHttpConfigurer::disable)
```

вместо:

```java
.csrf(csrf -> csrf.disable())
.requestCache(cache -> cache.disable())
```

Поведение одинаковое, но method reference убирает предупреждение IntelliJ:

```text
Lambda can be replaced with method reference
```

CSRF отключается только в данном изолированном тесте Basic Auth. Это не означает, что CSRF нужно отключать в production-конфигурации SafeAI Desk, где используются cookie-based access/refresh tokens.

### 35.7. Почему параметр validation endpoint теперь используется

Тестовый endpoint возвращает имя из DTO:

```java
@PostMapping("/validation")
String validation(
        @Valid @RequestBody ValidationRequest request
) {
    return request.name();
}
```

Это устраняет предупреждение:

```text
Parameter 'request' is never used
```

При этом endpoint сохраняет своё основное назначение: проверить реальное прохождение `@Valid` через Spring MVC и преобразование `MethodArgumentNotValidException` в `400 VALIDATION_ERROR`.

### 35.8. Что именно проверяют integration-тесты

`GlobalExceptionHandlerIntegrationTest` поднимает MVC test slice и проверяет реальный выбор методов `@ExceptionHandler`:

```text
ChatBusyException                 → 409 CHAT_BUSY
ChatLockUnavailableException      → 503 CHAT_LOCK_UNAVAILABLE
OptimisticLockingFailureException → 409 CONFLICT
RuntimeException                  → 500 INTERNAL_SERVER_ERROR
```

Дополнительно проверяются:

```text
ConflictException       → 409 CONFLICT
ошибка @Valid           → 400 VALIDATION_ERROR
сломанный JSON          → 400 BAD_REQUEST
return-value validation → 500 INTERNAL_SERVER_ERROR
неподдерживаемый метод  → 405 + Allow
rate limit              → 429 + Retry-After
```

`SecurityErrorResponseIntegrationTest` отдельно поднимает настоящую Spring Security filter chain и проверяет:

```text
неаутентифицированный запрос → 401 UNAUTHORIZED JSON
недостаточно прав            → 403 FORBIDDEN JSON
```

Такое разделение важно: исключения MVC и исключения security filter chain проходят через разные механизмы Spring.