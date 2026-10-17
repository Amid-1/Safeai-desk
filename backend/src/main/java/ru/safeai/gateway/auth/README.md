# SafeAI Desk — модуль `auth`

> **Техническая документация и статический security review**  
> **Срез:** 15 октября 2026 года · GitHub `Amid-1/Safeai-desk`, `main`, commit `ec2596ed079423d7103fa3218b51a0ce6cd46df0`  
> **Исходники:** `backend/src/main/java/ru/safeai/gateway/auth/`  
> **Стек проекта:** Java 21, Spring Boot 4.1.1, Spring Security / OAuth2 Resource Server, Spring MVC, Spring Data JPA /
> Hibernate, PostgreSQL, Redis, Jakarta Bean Validation, Nimbus JOSE + JWT, Flyway, Maven.  
> **Статус:** архитектура в целом проработана, **но обнаружен подтверждённый блокер production startup (P0)**. До его 
> устранения нельзя считать deployment готовым.

**Область достоверности.** Документ составлен по предоставленному исходному коду с проверкой файлов, структуры, тестов и 
конфигурации на указанном коммите GitHub. Это **статический аудит**, не отчёт об успешно выполненной сборке, penetration 
test или нагрузочном тесте. В частности, интеграционные тесты обнаружены и изучены, но в рамках подготовки README **не 
запускались**. Утверждения «код реализует» и «тест проверяет» не означают, что соответствующий тест прошёл на текущей машине.
Выводы о runtime специально маркируются по степени уверенности.

---

## 1. Назначение и место в архитектуре

`auth` отвечает за **аутентификацию**, выпуск и проверку access JWT, работу с refresh-token-сессиями, browser-cookie и CSRF, 
завершение сессий, security-state validation и события безопасности.

Это **логический модуль (package-based modular monolith)** внутри одного backend Maven-проекта, **не отдельный микросервис** 
и **не отдельный Maven-модуль**. Наличие нескольких пакетов не требует сетевого взаимодействия между ними.

### Зависимости и направление связей

```text
HTTP / Browser / API client
           │
           ▼
┌──────────────────────────────────────────────┐
│ auth.controller                              │
│ AuthController, CsrfController               │
└──────────────────────┬───────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────┐
│ auth.security + auth.service                 │
│ SecurityFilterChain, JWT, login, refresh,    │
│ cookie/CSRF, status filter, session flow     │
└───────┬─────────┬─────────┬─────────┬────────┘
        │         │         │         │
        ▼         ▼         ▼         ▼
    common      user     ratelimit    audit
  contracts   identity   IP/account   outbox
        │         ▲
        │         │
        │    identity.session ───► auth.service
        │    (port/interface)      (adapter)
        ▼
 PostgreSQL (refresh_tokens), Redis (status/rate-limit)
```

- `common` предоставляет универсальные `ApiException`, `ApiErrorCode`, `ApiErrorResponseWriter`, `AccessTokenSubject`, 
- `SafeAiUserPrincipal`, `JwtProperties`, `SystemRole`, `ClientIpResolver`, detection production и `Clock`.
- `user` владеет пользователями, организациями, ролями и состоянием безопасности; `auth` читает их и синхронизируется с 
- security mutations через блокировки.
- `ratelimit` ограничивает попытки login/refresh, в том числе при недоступности Redis.
- `audit` предоставляет best-effort аудит обычных событий и **обязательную транзакционную запись** о повторном использовании 
- refresh token.
- `identity.session.SessionRevocationPort` позволяет другим прикладным модулям инициировать отзыв сессий через абстракцию, 
- не завися от `auth`.

**Архитектурное решение:** переносить весь `auth` в `common` нельзя: JWT-схема как общий контракт может быть общей, но 
проверка пароля, refresh-token repository, HTTP filter chain, rate limiting и auditing — прикладные обязанности `auth`. 
Точно так же не нужно возвращать прямую зависимость `user → auth` вместо `identity.session`.

## 2. Быстрая карта модуля

Фактически в `auth` на указанном коммите **50 Java-файлов**, включая `security/package-info.java` (**49 типов и файл 
package-level аннотации**). Девять пакетов:

| Пакет | Назначение | Количество `.java` |
|---|---|---:|
| `config` | Production-проверки и регистрация свойств | 2 |
| `controller` | HTTP endpoints | 2 |
| `dto` | Контракты HTTP API | 3 |
| `entity` | Модель refresh-token persistence | 2 |
| `exception` | Доменные ошибки авторизации | 5 |
| `repository` | JPA и SQL | 1 |
| `security` | Фильтры, JWT codec, CSRF, RBAC | 15 (включая `package-info.java`) |
| `service` | Login/refresh/logout, транзакции, cookie, audit, cleanup | 18 |
| `validation` | Пользовательское ограничение UTF-8 длины | 2 |
| **Итого** | | **50** |

**Точка входа:** `POST /api/auth/login`, `POST /api/auth/refresh`, `POST /api/auth/logout`, `GET /api/auth/me` и 
`GET /api/auth/csrf`.

---

## 3. API: HTTP-маршруты и контракт

| Метод и путь | Аутентификация | CSRF | Результат |
|---|---|---|---|
| `POST /api/auth/login` | Доступен без access JWT; проверяется email/password | Нужен согласно общей CSRF-политике | `200` с `CurrentUserResponse` + access/refresh cookies |
| `POST /api/auth/refresh` | Выполняется по refresh-cookie, access JWT не обязателен | Нужен согласно общей CSRF-политике | `204 No Content` + обновлённые cookies |
| `POST /api/auth/logout` | Может исполняться при отсутствующем/негодном refresh-cookie | Нужен согласно общей CSRF-политике | `204 No Content`; завершение семьи и удаление cookies |
| `GET /api/auth/me` | Действующий Bearer JWT или access cookie | Для GET не нужен | `200 CurrentUserResponse` |
| `GET /api/auth/csrf` | Публичный | Для GET не нужен | `200 CsrfTokenResponse`; `Cache-Control: no-store` |

Пути `/login`, `/refresh`, `/logout` входят в `PUBLIC_AUTH_ENDPOINTS`: access-cookie filter и Bearer resolver намеренно 
не обрабатывают access credentials на этих маршрутах. Это **не** отменяет проверки CSRF или rate limit.

Типовой ответ `CurrentUserResponse` содержит `id`, `organizationId`, `email`, `fullName`, `enabled`, `roles`. 
Он предназначен для авторизованного frontend и **не должен** складываться в публичные кеши.

### Обработка ошибок

`auth.exception.AuthExceptionHandler` использует общий `common.exception.ApiErrorResponseSupport`:

| Условие | HTTP | Машинный `error` | Публичный ответ |
|---|---:|---|---|
| Недействительный refresh | 401 | `INVALID_REFRESH_TOKEN` | Нейтральное сообщение |
| Истёкший refresh | 401 | `EXPIRED_REFRESH_TOKEN` | «Refresh token истёк» |
| Повторно использован rotated refresh | 401 | `INVALID_REFRESH_TOKEN` | Не раскрывает сведения об инциденте |
| Сбой инфраструктуры auth | 503 | `AUTH_SERVICE_UNAVAILABLE` | Без SQL/Redis внутренних деталей |
| JWT отозван после изменения security state | 401 | `TOKEN_REVOKED` | «Токен больше не действителен» |
| Доступ запрещён | 403 | `FORBIDDEN` | «Доступ запрещён» |

`WWW-Authenticate: Bearer` выставляется только на Bearer-paths; для cookie-auth обычный `401` не притворяется Bearer challenge. 
`AuthExceptionHandler` имеет высокий приоритет `Ordered.HIGHEST_PRECEDENCE + 2`; глобальный обработчик из `common`
работает как поздний fallback.

---

## 4. Потоки выполнения: как устроено

### 4.1. Login: проверка пароля и создание новой сессии

```text
POST /api/auth/login
  → Bean Validation(LoginRequest)
  → AuthService.login
      → нормализация email; пароль без новой complexity policy
      → ClientIpResolver
      → LoginRateLimitService.checkAllowed (fail-closed)
      → AuthenticationManager / DaoAuthenticationProvider
          → CustomUserDetailsService → UserRepository
          → PasswordEncoder BCrypt / DelegatingPasswordEncoder
      → LoginSessionTransactionService.createSession [TX]
          → organization FOR SHARE
          → user PESSIMISTIC_WRITE
          → сверка security snapshot
          → update lastLoginAt без optimistic version bump
          → RefreshTokenService.createForLogin [MANDATORY TX]
          → создание AccessTokenSubject, CurrentUserResponse
        [COMMIT]
      → JwtService.generateToken (RS256)
      → best-effort login audit, limiter success
      → сброс прежнего CSRF-token
      → Set-Cookie access + refresh
      → 200 CurrentUserResponse
```

**Почему есть вторая проверка пользователя под блокировкой:** между успешной проверкой пароля и созданием сессии 
администратор мог отключить пользователя, организацию или изменить пароль/роль. Код сверяет уже прочитанный authenticated 
snapshot с актуальным состоянием под PostgreSQL lock и в конфликте **не выпускает сессию**.

**Важная транзакционная граница:** создание refresh-token подтверждается в БД до записи HTTP cookies. При сетевом обрыве 
после commit возможна orphaned session row — это неизбежная граница между DB и HTTP response, а не гарантированная 
доставка cookie. При login JWT подписывается **после** commit; при refresh — **внутри** транзакции до commit (см. ниже).

### 4.2. Refresh: строгая одноразовая ротация

```text
POST /api/auth/refresh + refresh cookie + valid CSRF
  → RefreshRateLimitService.checkAllowed(IP)
  → AuthCookieService.extractRefreshToken
  → RefreshSessionTransactionService.rotateAndSign [TX]
      → RefreshTokenService.rotate
          → формат токена → SHA-256 hash
          → lookup user/organization IDs без security decision
          → organization FOR SHARE → user FOR UPDATE → token FOR UPDATE
          → повторная проверка ownership и security versions
          → анализ revoked/expiry/family expiry
          → predecessor.revoked = ROTATED
          → создать единственный successor в той же family
      → JwtService.generateToken в той же TX
    [COMMIT]
  → Set-Cookie new access + new refresh
  → 204 No Content
```

- **Refresh token — opaque random secret**, а не JWT. Генерируется `SecureRandom` на 64 байта (**512 бит энтропии**) и 
- передаётся как Base64URL без padding.
- PostgreSQL хранит только `SHA-256` hex digest, не исходный токен. Для такой высокоэнтропийной случайной строки SHA-256 
- уместен; BCrypt нужен для низкоэнтропийных пользовательских паролей, а не для random 512-bit token.
- `token_family_id` связывает поколения токенов; у нового токена сохраняется абсолютное окончание жизни семьи, чтобы 
- постоянная ротация не продлевала жизнь сессии бесконечно.
- Когда старый токен с `ROTATED` приходит повторно, семья завершается целиком и создаётся обязательный security audit 
- outbox event в **одной транзакции**.
- Конкурентные попытки refresh одного токена могут привести к **одному успеху и одному reuse incident** с отзывом всей 
- семьи. Это намеренно строгая политика, а не случайно появившаяся ошибка. Frontend должен координировать refresh между 
- вкладками, а backend — не добавлять бесконтрольное replay grace window.

`RefreshSessionTransactionService` использует `noRollbackFor` для контролируемых ошибок (`InvalidRefreshTokenException`, 
`ExpiredRefreshTokenException`, `RefreshTokenReuseDetectedException`), поскольку завершение семьи и запись инцидента 
обязаны пережить возвращаемый клиенту `401`. **Убирать `noRollbackFor` без анализа нельзя**: это откатит требуемую security 
mutation.

### 4.3. Logout и завершение сессий

```text
POST /api/auth/logout
  → RefreshTokenService.revokeFamilyAndReturnSubject [TX]
      → lock organization → user → refresh token
      → terminateFamily(LOGOUT)
    [COMMIT]
  → AuthEventService.logout (best-effort)
  → finally: AuthCookieService.clearAuthCookies
             CsrfTokenRepository.saveToken(null)
  → 204
```

Logout идемпотентен для отсутствующих, некорректных и уже завершённых refresh tokens. При инфраструктурной ошибке отзыв 
в БД может быть **не подтверждён**: метод возвращает контролируемый 503, хотя browser cookies всё равно очищаются через 
`finally`. Это различие между *удалением токена в браузере* и *подтверждённым серверным отзывом* нельзя скрывать в 
документации или мониторинге.

### 4.4. Аутентификация запросов: Bearer и cookie

```text
HTTP request
  → RequestIdFilter (common)
  → Spring Security / CSRF
  → BearerTokenAuthenticationFilter: Authorization: Bearer ...
      → NimbusJwtDecoder + validators → SafeAiJwtAuthenticationConverter
  → AccessCookieAuthenticationFilter (если Bearer уже не аутентифицировал)
      → access cookie → тот же JwtAuthenticationProvider
  → UserStatusFilter
      → UserStatusCacheService (Redis / security state)
      → enabled, organizationId, tokenVersion, organizationAuthVersion
      → fail closed: 401 TOKEN_REVOKED или 503 AUTH_SERVICE_UNAVAILABLE
  → RBAC / @PreAuthorize / Controller
```

**Bearer имеет приоритет над access cookie.** Оба пути используют идентичную JWT-validation и преобразование ролей, 
чтобы не возникло расхождения полномочий. При инфраструктурном сбое получения security state доступ не разрешается «на удачу».

### 4.5. CSRF для browser cookies

`CookieCsrfTokenRepository.withHttpOnlyFalse()` выдаёт **доступную frontend** CSRF-cookie; это намеренно и **не относится 
к HttpOnly access/refresh cookies**. SPA передаёт токен заголовком `X-XSRF-TOKEN`.

`SpaCsrfTokenRequestHandler`: для HTTP header читает исходный, немаскированный token (`plain`), для прочих случаев использует 
XOR masking. `CsrfCookieFilter` принудительно разрешает deferred token, чтобы браузеру можно было установить cookie. 
`GET /api/auth/csrf` также возвращает CSRF token в JSON и запрещает кеширование.

Важно: наличие `SameSite=Lax/Strict` **не заменяет** серверную CSRF-защиту для cookie authentication. CSRF-токен после 
login/logout специально сбрасывается и должен быть заново получен frontend.

---

## 5. Подробный каталог: каждый файл и методы

### 5.1. `auth.config` — 2 файла

#### `ProductionAuthCookieInvariantValidator`

`@Component`, `@Conditional(ProductionEnvironmentCondition.class)`, `SmartInitializingSingleton`. Проверяет конфигурацию 
браузерной аутентификации **при старте production** (fail-fast).

- Конструктор — требует `AuthCookieProperties` и `HstsProperties`.
- `afterSingletonsInstantiated()` — запускает весь набор invariant checks.
- `validateSecureCookies()` — требует `Secure=true`.
- `validateHostOnlyCookieBoundary()` — запрещает `Domain`; требует `__Host-` у access, refresh и CSRF cookie. **Требование 
- к refresh ошибочно конфликтует с ограниченным Path** (см. P0).
- `validateHstsGuarantee()` — требует HSTS из приложения или trusted edge, но не проверяет реальный внешний HTTPS-response.

#### `RefreshTokenCleanupConfiguration`

`@Configuration(proxyBeanMethods=false)` + `@EnableConfigurationProperties(RefreshTokenCleanupProperties.class)`. Включает 
type-safe configuration binding для параметров batch cleanup. Своих методов нет.

### 5.2. `auth.controller` — 2 файла

#### `AuthController`

REST endpoint `/api/auth` и тонкий adapter к `AuthService`:

- `login(LoginRequest, HttpServletRequest, HttpServletResponse)` — `POST /login`, `@Valid`, возвращает пользователя и 
- выставляет cookies через service.
- `refresh(request, response)` — `POST /refresh`, `204`.
- `logout(request, response)` — `POST /logout`, `204`.
- `me(@AuthenticationPrincipal SafeAiUserPrincipal)` — `GET /me`, возвращает текущего пользователя.

Контроллер не реализует самостоятельно парольную проверку, транзакции или ротацию — это правильное разделение слоёв.

#### `CsrfController`

- `csrf(CsrfToken)` — `GET /api/auth/csrf`, отдаёт `headerName`, `parameterName`, `token` через `CsrfTokenResponse`; 
- ставит `Cache-Control: no-store`.

### 5.3. `auth.dto` — 3 файла

#### `CsrfTokenResponse`

`record(headerName, parameterName, token)`: публичный DTO для `/csrf`.

- Compact canonical constructor — проверяет обязательные значения.
- `requireText(value, fieldName)` — private helper для non-null/non-blank.
- Автоматические методы record: component accessors, `equals`, `hashCode`, `toString`.

**Не журналировать DTO целиком:** стандартный record `toString()` включает `token`, поэтому в логах CSRF secret может 
оказаться открытым. См. рекомендацию P2.

#### `CurrentUserResponse`

`record(id, organizationId, email, fullName, enabled, roles)`:

- Compact constructor — делает `roles` immutable через `Set.copyOf`.
- Автоматические accessors, `equals/hashCode/toString` record.

Используется login и `/me`; в отличие от JWT, JSON вправе содержать профильные PII для самого вошедшего пользователя. 
DTO не предназначен для анонимного доступа.

#### `LoginRequest`

`record(email, password)`:

- Bean Validation: `@Email`, `@NotBlank`, `@Size(max=255)` для email; `@NotBlank`, `@Size(max=72)`, `@BcryptUtf8Length` 
- для password.
- `toString()` — **redacted** для email/password, чтобы не логировать credentials.
- Не проверяет новую complexity policy при login: ранее разрешённые пароли должны продолжать работать. Проверка 
- технического предела BCrypt выполняется через custom user-domain constraint.

### 5.4. `auth.entity` — 2 файла

#### `RefreshTokenEntity`

JPA `@Entity`, таблица `refresh_tokens`. Поля отражают модель одноразовой refresh-family:

| Группа | Поля | Смысл |
|---|---|---|
| Identity | `id`, `user`, `tokenHash` | UUID токена, владелец, уникальный SHA-256 digest |
| Security snapshot | `issuedTokenVersion`, `issuedOrganizationAuthVersion` | Версии при выдаче: отзыв при изменении состояния |
| Время | `createdAt`, `expiresAt`, `familyCreatedAt`, `familyExpiresAt`, `lastUsedAt` | Idle и absolute expiry, usage evidence |
| Ротация | `tokenFamilyId`, `replacedByTokenId` | Семья и ссылка на successor |
| Отзыв | `revokedAt`, `revocationReason` | Причина и время завершения |
| Forensics | `createdByIp`, `userAgent` | Ограниченный контекст событий |

- `prePersist()` — назначает UUID при отсутствии, запрещает сохранение без `createdAt` или с отрицательными security versions.
- Lombok `@Getter/@Setter` — доступors/mutators всех полей; setter-API не является самостоятельной проверкой бизнес-инвариантов. 
- Источники истины — `RefreshTokenService` и DB schema.

#### `RefreshTokenRevocationReason`

Enum: `ROTATED`, `LOGOUT`, `PASSWORD_RESET`, `ROLE_CHANGED`, `EMAIL_CHANGED`, `USER_DISABLED`, `ORGANIZATION_DISABLED`, 
`SECURITY_STATE_CHANGED`, `EXPIRED`, `REUSE_DETECTED`, `ADMIN_REVOKED`, `LEGACY_REVOKED`.

Используется как строковое JPA-поле. Семантику значения менять без анализа исторических данных и миграции нельзя.

### 5.5. `auth.exception` — 5 файлов

#### `AuthExceptionHandler`

`@RestControllerAdvice` с приоритетом `HIGHEST_PRECEDENCE + 2`:

- `handleAuthServiceUnavailable()` — `503 AUTH_SERVICE_UNAVAILABLE`, логирует инфраструктурную ошибку.
- `handleExpiredRefreshToken()` — `401 EXPIRED_REFRESH_TOKEN`.
- `handleInvalidRefreshToken()` — `401 INVALID_REFRESH_TOKEN`.
- `handleRefreshTokenReuseDetected()` — тот же безопасный публичный `401 INVALID_REFRESH_TOKEN`, но дополнительно логирует 
- `userId`, `organizationId`, `tokenFamilyId`. Обязательный аудит записывает транзакционный сервис, **не** этот handler.

#### `AuthServiceUnavailableException`

`ApiException` с публичным безопасным сообщением и внутренней причиной; конструктор `(internalMessage, cause)` задаёт `503`.

#### `ExpiredRefreshTokenException`

`ApiException`; конструктор `(internalMessage)` задаёт `401`, `EXPIRED_REFRESH_TOKEN`. Техническое описание переносится 
в cause, не в HTTP.

#### `InvalidRefreshTokenException`

`ApiException`; конструкторы `(internalMessage)` и `(internalMessage, cause)` задают `401 INVALID_REFRESH_TOKEN`.

#### `RefreshTokenReuseDetectedException`

`ApiException` с полями `userId`, `organizationId`, `tokenFamilyId`; конструктор фиксирует контекст security incident. 
Lombok `@Getter` генерирует getters. В HTTP инцидент не раскрывается отдельно от недействительного токена.

### 5.6. `auth.repository` — 1 файл

#### `RefreshTokenRepository`

`JpaRepository<RefreshTokenEntity, UUID>` + custom запросы:

- `findLockIdentityByTokenHash(tokenHash)` — **preflight scalar lookup** `userId, organizationId` без блокировки; 
- значения используются только для определения порядка locks. Нельзя авторизовать по результату этого чтения без последующего 
- locked re-read.
- `findByTokenHashForUpdate(tokenHash)` — `PESSIMISTIC_WRITE` на строке refresh-токена.
- `terminateFamily(tokenFamilyId, revokedAt, reason)` — завершает семью; `COALESCE` сохраняет ранее записанную причину отзыва.
- `terminateAllByUserId(userId, revokedAt, reason)` — закрывает все refresh sessions пользователя.
- `terminateAllByOrganizationId(organizationId, revokedAt, reason)` — массовый отзыв сессий tenant/organization.
- `deleteExpiredBatch(threshold, batchSize)` — native PostgreSQL `DELETE` через CTE с `FOR UPDATE SKIP LOCKED`; выбирает 
- только элементы цепочки, не имеющие predecessor, который на них ссылается. Сохраняет FK-порядок и допускает несколько 
- workers.
- Унаследованные `JpaRepository` методы (`save`, `findById` и др.) используются согласно назначению; ручные бизнес-операции 
- не должны обходить lock protocol.

**SQL-модель критична:** `SKIP LOCKED` обеспечивает конкурентную очистку, но не распределённый singleton. На одном батче 
работают независимые транзакции; медленные или недоступные строки пропускаются и будут обработаны позднее.

### 5.7. `auth.security` — 15 файлов, включая `package-info.java`

#### `AccessCookieAuthenticationFilter`

`OncePerRequestFilter`, интерпретирует HttpOnly access-cookie как JWT:

- Конструктор — получает cookie service, JWT `AuthenticationManager`, entry point и matcher исключаемых маршрутов.
- `shouldNotFilter(request)` — пропускает публичные auth endpoints.
- `doFilterInternal(...)` — если request уже успешно Bearer-authenticated, ничего не перезаписывает; иначе извлекает cookie, 
- аутентифицирует через `BearerTokenAuthenticationToken`, устанавливает новый `SecurityContext`; при `AuthenticationException`
- очищает контекст и выдаёт cookie-specific 401.

Располагается **после** стандартного Bearer filter, а значит поддерживает оба способа без двойной аутентификации.

#### `BearerAuthenticationEntryPoint`

- `commence(request, response, exception)` — формирует `401` с `WWW-Authenticate: Bearer` через общий `ApiErrorResponseWriter`.

#### `CsrfCookieFilter`

- `doFilterInternal(...)` — обращается к deferred `CsrfToken#getToken()` для материализации токена и установки CSRF-cookie, 
- затем передаёт запрос дальше.

#### `CustomUserDetailsService`

`UserDetailsService` для password authentication:

- `loadUserByUsername(email)` — read-only TX; нормализация email, загрузка пользователя, организации и ролей; построение 
- `SafeAiUserPrincipal.passwordPrincipal(...)` с password hash и version snapshot.
- `normalizeAuthenticationEmail(email)` — преобразует нарушение входных требований в нейтральный `UsernameNotFoundException`, 
- не раскрывая существование аккаунта.

#### `HstsProperties`

`@ConfigurationProperties("safeai.security.hsts")`: `enabled` и `edgeManaged`; определяет, кто публикует 
`Strict-Transport-Security`: приложение или HTTPS termination edge. Сам факт `edgeManaged=true` — **декларация**, 
её нужно подтвердить реальным HTTPS smoke test.

#### `JwtCodecConfiguration`

- `jwtEncoder(JwtRsaKeyRing)` — `NimbusJwtEncoder` с активным private JWK.
- `jwtDecoder(JwtRsaKeyRing, JwtProperties, Clock)` — `NimbusJwtDecoder` с public JWKs.
- `createJwtValidator(...)` — объединяет срок годности, issuer, audience и `typ`.
- `createTimestampValidator(Clock)` — `JwtTimestampValidator`, ±30 секунд clock skew; `exp` обязателен, `nbf` может 
- отсутствовать.
- `createTypeValidator()` — требует header `typ=JWT`.

#### `JwtRsaKeyRing`

Иммутабельный in-process RSA key ring:

- Конструктор — парсит PEM, проверяет минимум 2048 бит, публичный/приватный modulus, формирует signing JWK только для 
- active `kid`, а verification JWKs — для всех допустимых ключей.
- `signingJwkSource()` — источник активного приватного ключа; package-private.
- `verificationJwkSource()` — источник публичных ключей; package-private.
- `parsePublicKey(pem, keyId)` — X.509 SubjectPublicKeyInfo.
- `parsePrivateKey(pem, keyId)` — PKCS#8.
- `decodePem(...)` — удаление PEM delimiters, Base64 decode.
- `rsaKeyFactory()` — стандартный JCA RSA factory.
- `validateRsaStrength(key, keyId)` — минимальная длина RSA modulus.

`kid` позволяет проверять ещё действительные access JWT старым **public** key во время ротации. Проверка лишь совпадения 
modulus не заменяет функциональный sign/verify smoke test всей пары.

#### `PasswordEncodingConfiguration`

- `passwordEncoder()` — `DelegatingPasswordEncoder` с `{bcrypt}` и BCrypt cost **12**.
- Свойство `safeai.security.password.allow-legacy-unprefixed-bcrypt` временно допускает старые хэши без `{bcrypt}`. 
- Production validator из `common` требует явного выбора значения.

Стоимость BCrypt нужно измерять на production hardware и учитывать rate limiting. Переход на Argon2id — **возможная 
дорожная карта**, а не автоматическое требование срочно менять уже корректную совместимую схему.

#### `RestAccessDeniedHandler`

- `handle(...)` — JSON `403 FORBIDDEN` для отказа в доступе.

#### `RestAuthenticationEntryPoint`

- `commence(...)` — JSON `401 UNAUTHORIZED` без Bearer challenge для browser/cookie flow.

#### `SafeAiJwtAuthenticationConverter`

Проверяет строгую access-token schema и создаёт `UsernamePasswordAuthenticationToken` с `SafeAiUserPrincipal` 
**без пароля/email/raw JWT**:

- `convert(Jwt)` — проверяет обязательный `sub`, `iat`, UUID `jti/userId/organizationId`, совпадение `sub=userId`, 
- version claims, ровно одну системную роль.
- `requiredStringClaim(jwt, name)` — строго строковый non-blank claim.
- `requiredNonNegativeLongClaim(jwt, name)` — numeric claim только как точное неотрицательное целое `long`, включая 
- проверки `BigInteger`, `BigDecimal`, `Float/Double`.
- `parseUuid(value, claimName)` — валидирует UUID с `BadJwtException`.

Это важная защита от неявной потери точности в JSON-числах и несанкционированных ролей.

#### `SecurityConfig`

Главная declarative Spring Security конфигурация:

- `securityFilterChain(...)` — конфигурирует CSRF, CORS, `STATELESS`, JSON handlers, доступы RBAC и цепочку фильтров.
- `authenticationManager(AuthenticationConfiguration)` — основной password authentication manager.
- `authenticationProvider(UserDetailsService, PasswordEncoder)` — `DaoAuthenticationProvider`.
- `corsConfigurationSource()` — exact-origin allowlist, разрешённые методы/headers, `allowCredentials=true`, 
- expose headers, preflight max age.
- `csrfTokenRepository()` — `CookieCsrfTokenRepository`, настраивает name/`X-XSRF-TOKEN`, `SameSite`, `Secure`, 
- `HttpOnly=false`, `Path=/`, опциональный Domain для non-prod.
- `bearerTokenResolver()` — стандартный `Authorization: Bearer` с игнорированием публичных auth endpoints.
- `accessCookieAuthenticationManager(JwtDecoder)` — выделенный `ProviderManager` с JWT provider для cookie.
- `resourceServerJwtAuthenticationConverter()` — адаптирует JWT schema exception к `InvalidBearerTokenException`.
- `configureHeaders(...)` — CSP, X-Frame-Options, X-Content-Type-Options и HSTS.
- `isPublicAuthEndpoint(request)` — сравнивает путь со строгим allowlist.
- `pathWithinApplication(request)` — учитывает `contextPath` при распознавании маршрута.

Важные детали RBAC: `/api/organizations` (GET) допускает `ADMIN/SUPER_ADMIN`, остальные organization mutations — 
`SUPER_ADMIN`; `/api/users`, `/api/admin` — `ADMIN/SUPER_ADMIN`; `/api/knowledge-bases` и `/api/chats` — `ADMIN/USER`, 
но **не** `SUPER_ADMIN`. Неизвестные `/api/**` запрещены по умолчанию; `@EnableMethodSecurity` позволяет service/method-level 
checks. Health/actuator закрываются отдельной логикой management chain из `common` и инфраструктурой (см. README `common`).

**Проверить при интеграции:** настройка `.csrf(...)` в текущем файле не содержит явного исключения Bearer POST; тест 
ожидает отсутствие CSRF для Bearer. См. P1.

#### `SpaCsrfTokenRequestHandler`

- `handle(request, response, Supplier<CsrfToken>)` — XOR/masked request attribute.
- `resolveCsrfTokenValue(request, csrfToken)` — plain token из header, XOR token из другого канала.

#### `UserStatusFilter`

`OncePerRequestFilter` для **актуального** security state после проверки криптографической подписи:

- `doFilterInternal(...)` — читает identity из `SafeAiUserPrincipal`; получает `UserSecurityStatus` по user ID; при 
- несовпадении 401, при недоступности 503; иначе продолжает цепочку.
- `isValid(status, principal)` — сравнивает `userEnabled`, `organizationEnabled`, organization ID, tokenVersion, 
- organizationAuthVersion.
- `rejectToken(request, response)` — очищает SecurityContext, пишет `TOKEN_REVOKED`.
- `isBearerRequest(request)` — распознаёт Bearer flow для корректного `WWW-Authenticate`.

**Это не чисто stateless JWT:** подпись и claims проверяются локально, но актуальность выдачи определяется security-state 
store. Это осознанный компромисс ради немедленного отзыва прав; его надёжность зависит от правил кеширования и инвалидации 
в `user`.

#### `security/package-info.java`

`@NullMarked`: пакетная декларация строгих nullability contracts JSpecify. Не Java class и не Spring component.

### 5.8. `auth.service` — 18 файлов

#### `AuthAuditRequestSupport`

Package-private нормализация metadata для аудита, без собственной policy хранения:

- `ipOrUnknown(ClientIpResolver, request)` — bounded client IP.
- `userAgentOrUnknown(request)` — bounded user agent.
- `requestIdOrUnknown(request)` — только server-issued `requestId`.
- `clientRequestIdOrNull(request)` — отдельно помеченный клиентский correlation ID.
- `truncateOrUnknown(value, maxCodePoints)` — нормализация либо `unknown`.
- `truncateNullable(value, maxCodePoints)` — trim и ограничение Unicode code points, без разрезания surrogate pair.

#### `AuthCookieConfigurationValidator`

- `validate()` (`@PostConstruct`) — fail-fast совпадение `access-token-max-age` и JWT `expiration-minutes`.

#### `AuthCookieProperties`

Type-safe `@ConfigurationProperties("safeai.auth.cookies")`:

- Compact constructor — нормализует `SameSite`, domain, cookie names и проверяет все lifetime bounds.
- `hasDomain()` — задано ли cookie Domain.
- `csrfTokenName()` — `__Host-XSRF-TOKEN` для secure host-only, иначе `XSRF-TOKEN`.
- `requirePositive`, `normalizeSameSite`, `normalizeDomain`, `normalizeCookieName`, `validateCookiePrefix`, 
- `invalidDomain` — private helpers для строгого binding.

Основная текущая политика: access cookie `__Host-safeai-access` при `Path=/`; refresh cookie `__Secure-safeai-refresh` 
при `Path=/api/auth`. **Конструктор запрещает `__Host-` у refresh**, что соответствует cookie standard; это конфликтует 
с production валидатором — P0.

Ограничения: access max 60 минут; refresh idle max 90 дней; absolute family max 90 дней; reuse retention max 30 дней; 
refresh idle > access; absolute ≥ refresh idle; `SameSite=None` требует `Secure`.

#### `AuthCookieService`

Единоличный владелец **auth cookies** (не CSRF cookies):

- `addAccessTokenCookie(response, token)` — HttpOnly cookie с `Path=/`.
- `addRefreshTokenCookie(response, token, maxAge)` — HttpOnly cookie с `Path=/api/auth`.
- `extractAccessToken(request)`, `extractRefreshToken(request)` — достают непустой токен только по точному имени cookie.
- `clearAuthCookies(response)` — удаляет текущие и **подтверждённые исторические** имена и scopes (name/path/domain), 
- стараясь не оставить legacy cookies.
- `addScope(...)` — регистрирует идентичность cookie для удаления.
- `extract(...)` — private exact-name parser.
- `issue(...)` — проверяет имя, токен, lifetime, scope до заголовков.
- `write(...)` — создаёт `ResponseCookie` и добавляет `Set-Cookie` (не `setHeader`).
- `validateSameSite`, `validatePolicy`, `prefixed`, `requireName` — helpers; `CookieIdentity` — record для уникальности 
- target scope.

**Почему отдельный CSRF owner:** удаление CSRF cookie должно происходить через `CsrfTokenRepository.saveToken(null, ...)`, 
а не сторонними ad-hoc заголовками.

#### `AuthEventService`

Best-effort события обычной аутентификации:

- `loginSuccess(CurrentUserResponse, request)` — audit `USER_LOGIN_SUCCESS`.
- `loginFailed(email, request)` — audit `USER_LOGIN_FAILED` с нейтральной причиной.
- `logout(LogoutAuditSubject, request)` — audit `USER_LOGOUT`.
- `requestDetails(request, eventDetails)` — добавляет IP, user agent и correlation IDs.

**Не использовать для reuse:** этот инцидент обслуживает транзакционный `RefreshReuseAuditService`.

#### `AuthService`

Application-level coordinator:

- `login(LoginRequest, request, response)` — validation, IP/rate limit, password manager, session transaction, JWT, audit, 
- cookie response.
- `refresh(request, response)` — refresh rate limit, получение cookie, transactional rotate+sign, запись новых cookies; 
- controlled failure вызывает очистку.
- `logout(request, response)` — идемпотентный отзыв, аудит, очистка cookies + CSRF в `finally`.
- `getCurrentUser(principal)` — `@Transactional(readOnly=true)`, получает актуальный профиль и роли.
- `clearAuthCookies(response)` — private delegation.
- `authServiceUnavailable(cause)` — private mapping infrastructure fault → публичный 503.

12 dependency-конструктора отражают координационную ответственность, но сами по себе **не доказывают** нарушение SRP: 
настоящие транзакции, JWT и cookie реализованы делегатами. Разделять только ради сокращения списка зависимостей не нужно.

#### `JwtService`

- `generateToken(AccessTokenSubject)` — строит RS256 JWT со строгим `kid`, `typ=JWT`, `iss`, `aud`, `iat`, `exp`, `sub`, 
- `jti`, `userId`, `organizationId`, `tokenVersion`, `organizationAuthVersion`, `roles`.
- Срок жизни берётся из injected `Clock`, произвольные PII в JWT не добавляются.

#### `LoginSessionResult`

`record(currentUser, accessTokenSubject, rawRefreshToken, refreshCookieMaxAge)` — результат успешного committed login, 
`toString()` маскирует чувствительные поля. Record автоматически генерирует accessors и equality.

#### `LoginSessionTransactionService`

- `createSession(principal, request)` — `@Transactional`: organization FOR SHARE, user FOR UPDATE, повторная сверка 
- credential/security snapshot, обновление времени последнего входа и создание refresh session.
- `validateAuthenticatedSnapshot(...)` — проверяет, что username/email, tenant, security versions и системная роль не 
- изменились после проверки пароля.
- `authenticatedRoleNames(principal)` — получает canonical role names.
- `securityStateChanged()` — создаёт нейтральную `BadCredentialsException` при конкурентной security mutation.

#### `LogoutAuditSubject`

`record(userId, organizationId, email)` — bounded validated audit subject, который возвращается из transaction layer для 
best-effort лога выхода. Конструктор требует IDs и non-blank email.

#### `RefreshReuseAuditService`

- `recordRequired(userId, organizationId, tokenFamilyId, request)` — `@Transactional(MANDATORY)` и 
- `AuditOutboxWriter.writeRequiredInCurrentTransaction`; фиксирует `SECURITY_REFRESH_REUSE_DETECTED` **атомарно с отзывом 
- семьи**. Если audit intent не удалось сохранить, security transaction также не должна считаться успешно подтверждённой.

#### `RefreshSessionResult`

`record(accessToken, rawRefreshToken, refreshCookieMaxAge)` — результат ротации с подписанным JWT; canonical constructor 
требует tokens и positive duration; `toString()` маскирует оба токена.

#### `RefreshSessionTransactionService`

- `rotateAndSign(rawRefreshToken, request)` — `@Transactional` с контролируемым `noRollbackFor`; включает в одну транзакцию 
- refresh rotation и RS256 signing. Непредвиденный сбой signing вызывает rollback, сохраняя одноразовый refresh token 
- неиспользованным.

#### `RefreshTokenCleanupBatchService`

- `deleteNextBatch(threshold, batchSize)` — `@Transactional(REQUIRES_NEW)`, `batchSize` 1..10000, выполняет SQL cleanup 
- и проверяет число удалённых строк.

#### `RefreshTokenCleanupJob`

- `scheduledCleanup()` — `@Scheduled`, cron по умолчанию ежедневно в **03:00 UTC**, пишет bounded operational logs.
- `runCleanup()` — рассчитывает conservative threshold: `now - reuseDetectionRetention`, запускает bounded batches.
- `runCleanup(threshold, batchSize, maxBatches)` — цикл **без внешней транзакции**, каждый batch commit отдельный; не 
- считает short batch автоматическим завершением.
- Вложенный `CleanupResult` record — `deletedRows`, `committedBatches`, `limitReached`, invariant constructor.
- `CleanupResult.completed(...)`, `limitReached(...)` — именованные фабрики результата.

#### `RefreshTokenCleanupProperties`

`@ConfigurationProperties("safeai.auth.refresh-cleanup")`, `@Validated`:

- `batchSize` по умолчанию **500**, границы 1..10000.
- `maxBatchesPerRun` по умолчанию **100**, границы 1..10000.

Фактическая `application.yml` может переопределять эти defaults (в проверенной конфигурации базовый `batch-size` задан 
через environment placeholder с fallback 200). Не путать default record и применённое окружение.

#### `RefreshTokenService`

Главный service lifecycle opaque refresh tokens:

- `createForLogin(user, request, now)` — `@Transactional(MANDATORY)`, новая family, expiry, hash-only persistence.
- `rotate(rawToken, request)` — one-time rotation, locks, security-state checks, detect reuse, expiry, create successor.
- `revokeFamilyAndReturnSubject(rawToken)` — `@Transactional`, logout/revoke family и возврат audit identity.
- `lockTokenInSecurityOrder(hash)` — strict required lookup.
- `findLockedTokenInSecurityOrder(hash)` — preflight → lock organization → user → token → cross-check ownership/scope.
- `terminateForSecurityState(familyId, timestamp, reason)` — закрывает семью и возвращает controlled exception.
- `terminateFamily(...)` — batch revoke family + structured logging.
- `createToken(...)` — `SecureRandom` 64 bytes, hash, JPA entity, expiry and cookie duration.
- `toAccessTokenSubject(user)` — stable JWT security claims.
- `validateRawToken(token)` — nonblank, max 256, Base64URL alphabet.
- `generateRawRefreshToken()` — случайный opaque secret; никогда не следует логировать.
- `hash(rawToken)` — SHA-256 hex.
- `cookieMaxAge(now, expiresAt)` — conservative seconds for cookie expiry.
- `canonicalEmail(email)` — проверка email для logout audit snapshot.
- `truncateUserAgent(value)` — bounded persistence field.
- `validateTokenResult`, `requireText`, `requirePositiveDuration` — validation helpers.
- Nested `RefreshTokenRotationResult`, `CreatedRefreshToken`, `LockedRefreshToken` — типизированные результаты и 
- внутренний lock state; два публичных record защищают `toString()`.

**Ключевой invariant:** `ROTATED` predecessor должен остаться в БД достаточно долго, чтобы повторное использование 
обнаруживалось; cleanup не удаляет записи до истечения family expiry + configured reuse-detection retention.

#### `UserSessionRevocationService`

`implements identity.session.SessionRevocationPort`, без прямой зависимости других модулей от `auth`:

- `revokeAllForUser(userId, reason)` — `@Transactional(MANDATORY)`, завершает все refresh sessions пользователя.
- `revokeAllForOrganization(organizationId, reason)` — аналогично для организации.
- `toRefreshTokenReason(reason)` — строгое преобразование enum порта в enum persistence.
- `logResult(type, id, affectedRows, reason)` — structured logging.

При вызове из изменения ролей/пароля/organization security state необходимо **одной транзакцией** обновить сам state и 
отозвать refresh sessions, а также обеспечить инвалидацию security-state cache для access JWT.

### 5.9. `auth.validation` — 2 файла

#### `Utf8ByteLength`

Собственная Jakarta Bean Validation annotation с параметром `max`, `message`, `groups`, `payload`; 
`@Constraint(validatedBy=Utf8ByteLengthValidator.class)`.

#### `Utf8ByteLengthValidator`

- `initialize(constraint)` — считывает положительный или нулевой предел; отрицательный — invalid annotation declaration.
- `isValid(CharSequence, context)` — `null` валиден сам по себе, остальные значения измеряются через `UTF_8` bytes.

**Важно:** `String#getBytes(UTF_8)` по умолчанию заменяет malformed surrogate последовательности, а не отвергает их. 
Для security-sensitive inputs при строгом требовании корректного Unicode нужно отдельно применить 
`common.validation.UnicodeValidationSupport.requireWellFormedUtf16` или encoder с `CodingErrorAction.REPORT`. 
Из этого класса нельзя делать вывод о строгой проверке UTF-16.

---

## 6. Что сделано хорошо и почему это современно

| Решение | Практическая оценка |
|---|---|
| Java 21 LTS, Spring Boot 4.1.1 | Современный framework/runtime baseline на октябрь 2026; переход на более новую LTS Java — отдельный миграционный проект |
| Spring Security declarative `SecurityFilterChain`, `@EnableMethodSecurity` | Актуальная альтернатива устаревшему `WebSecurityConfigurerAdapter` |
| OAuth2 Resource Server / Nimbus JWT | Стандартная проверка JWT; не используется самописный RSA/JWS parser |
| RS256 + `kid` + public-only previous keys | Управляемая ротация без хранения прошлых signing secrets |
| Opaque random 512-bit refresh + SHA-256 storage | Сильная криптографическая энтропия, хранение hash-only |
| One-time refresh rotation + family reuse detection | Серьёзное предотвращение replay; требует координации frontend |
| Lock order `organization → user → token` | Осмысленная защита от race conditions и ряда deadlocks |
| Transaction-required audit outbox | Security incident и отзыв не расходятся по commit |
| `noRollbackFor` только контролируемым типам | Сохраняет committed security transitions при 401 |
| CSRF, Secure/HttpOnly/SameSite, exact-origin CORS | Адекватный browser-auth boundary; см. найденный конфигурационный дефект |
| `UserStatusFilter` + version checks | Отзыв даже ещё не истёкшего JWT после security mutation |
| PostgreSQL `SKIP LOCKED` + `REQUIRES_NEW` cleanup | Multi-instance безопасная обработка очереди cleanup |
| `Clock` injection, records, JSpecify, Jakarta Validation | Тестируемость, ограничение mutability, явные nullability/validation contracts |
| Error contract с masking и `requestId` | Снижение риска разглашения credentials и удобство расследований |
| `SessionRevocationPort` | Снижение связности `user`/`organization` с `auth` |

Это **современный инженерный подход**, а не «просто JWT фильтр и таблица refresh_tokens». В то же время ни выбранный стек, 
ни количество тестов сами по себе не доказывают защищённость production: важны корректная конфигурация, фактические тесты, 
секреты, edge, observability, backups, развёртывание.

---

## 7. Результаты код-ревью: ошибки, риски, улучшения

Шкала: **P0** — блокирует запуск/релиз; **P1** — серьёзное функциональное или security-расхождение, нуждается в проверке; 
**P2** — локальное улучшение/оборонительная мера. Статус отделяет *подтверждённый кодом дефект* от *проверить интеграцией*.

### P0 — подтверждено: несовместимые требования к refresh-cookie

**Файлы:**

1. `config/ProductionAuthCookieInvariantValidator.java` — в `validateHostOnlyCookieBoundary()` требует:

   ```java
   authCookieProperties.refreshTokenName().startsWith("__Host-")
   ```

2. `service/AuthCookieProperties.java` — явно запрещает:

   ```java
   if (refreshTokenName.startsWith("__Host-")) {
       throw new IllegalStateException(...);
   }
   ```

3. `service/AuthCookieService.java` — выдаёт refresh с `Path=/api/auth`.

В browser cookie semantics `__Host-` допустим **только** с `Secure`, `Path=/`, без `Domain`. Поэтому текущие требования 
**невыполнимы одновременно**. `AuthCookieProperties` по умолчанию выбирает `__Secure-safeai-refresh`, корректный для 
`Path=/api/auth`, но production validator отвергает его. Установить `__Host-` не получится: конструктор properties бросит исключение ещё до проверки валидатора.

**Следствие:** при включённом `safeai.environment=production` или production profile Spring bean 
`ProductionAuthCookieInvariantValidator` не проходит инициализацию; production application context не может стартовать 
с корректными defaults. Проверка `application-prod.yml` подтверждает `safeai.environment: production` и пустой 
`refresh-token-name`, то есть дефект касается штатной конфигурации, а не только редкой кастомизации.

**Безопасное рекомендуемое исправление, концептуально:**

```java
// В ProductionAuthCookieInvariantValidator.validateHostOnlyCookieBoundary():
// access: __Host- + Path=/ + Secure + отсутствие Domain
// csrf:   __Host- + Path=/ + Secure + отсутствие Domain
// refresh: __Secure- + Path=/api/auth + Secure + отсутствие Domain

if (!authCookieProperties.refreshTokenName().startsWith("__Secure-")) {
    throw new IllegalStateException(
            "В production refresh-cookie должна использовать __Secure- "
                    + "при Path=/api/auth и без Domain"
    );
}
```

**Не менять** refresh path на `/` только ради удовлетворения `__Host-`: это расширит область передачи refresh-cookie без 
явной необходимости. `__Secure-` + `Secure` + отсутствие `Domain` обеспечивает host-only scope; ограниченный `Path` 
дополнительно снижает отправку refresh-токена на unrelated маршруты (хотя `Path` не абсолютная граница безопасности).

**Обязательный regression test:** поднять *полный* production context с реалистичными bean properties, явно проверить 
оба условия: с допустимым `__Secure-` + host-only production **стартует**, с domain-scoped/insecure или неподходящим 
префиксом **не стартует**. В существующем `common/security/ProductionSecurityStartupContractTest` и 
`auth/security/SecurityConfigIntegrationTest` проверяются другие сценарии; первый не является доказательством полного 
auth production startup, второй идёт под `@ActiveProfiles("test")`.

### P1 — конфигурация CSRF и контракт Bearer POST: требуется runtime-проверка

**Файл:** `security/SecurityConfig.java`.

В текущей настройке `.csrf(...)` указан cookie repository и SPA handler, но **нет явного `requireCsrfProtectionMatcher` 
либо `ignoringRequestMatchers` для Bearer-only requests**. Стандартная servlet CSRF-политика Spring Security требует 
token для state-changing HTTP methods независимо от того, кто впоследствии аутентифицирует запрос.

При этом тест `SecurityConfigIntegrationTest.organizationCreationWithBearerHeaderDoesNotRequireCsrf()` ожидает `200 OK` 
для `POST /api/organizations` с Bearer JWT без CSRF. Между видимой конфигурацией и ожидаемым поведением есть расхождение.
**Это нельзя объявлять прошедшим тестом, пока он действительно не выполнен.**

**Проверить:**

- Bearer `POST` без cookie и без CSRF — должен соответствовать явному API-контракту (в тесте — разрешён при наличии RBAC).
- Access-cookie `POST` без CSRF — обязательно 403.
- Смешанный `Authorization: Bearer` + cookie — определить приоритет, не допустить обхода CSRF, если фактически использована 
- cookie identity.
- Не применять широкое `csrf.disable()` или blanket ignore `/api/**`: это нарушит browser-auth модель.

Предполагаемое исправление зависит от подтверждённого продуктового контракта: можно использовать узкий, хорошо 
протестированный request matcher, который исключает **только действительно Bearer-authenticated API requests**, а не 
любой запрос с подставленным текстом `Authorization`. Для сложного dual-auth сценария отдельные security chains иногда 
проще в сопровождении, но миграцию делать только по тестам, а не по предположению.

### P1 — HSTS preload / includeSubDomains: operational review

**Файлы:** `security/SecurityConfig.java`, `security/HstsProperties.java`, deployment edge.

При `hsts.enabled=true` приложение выставляет `includeSubDomains(true)` и `preload(true)` с `maxAge=31536000`. Это 
осмысленная security policy, но нужно подтвердить, что **все соответствующие поддомены** всегда обслуживаются HTTPS. 
HSTS preload нельзя безболезненно включать как локальную настройку ради галочки: возможны долгоживущие последствия для 
legacy subdomains.

При `edgeManaged=true` startup проверяет только декларацию, а не реальный внешний HSTS header. Нужен smoke test публичного 
endpoint за TLS terminator.

### P1 — проверить гарантию немедленного отзыва access JWT

**Файлы:** `security/UserStatusFilter.java`, `user.service.UserStatusCacheService` и invalidation paths.

Код правильно проверяет status/версии и fail-closes на исключении. Но обещание «токен сразу перестал работать после 
изменения роли/выключения» зависит от **актуальности внешнего кеша**. Проверить invalidation после commit, Redis outage, 
stale reads, редкие races и несколько backend instances.

Это **не установленный дефект `auth`**, а production-предпосылка, которую нельзя вывести из одного фильтра.

### P2 — `CsrfTokenResponse` может раскрыться через `toString()`

`record CsrfTokenResponse` содержит `token` и не переопределяет `toString()`. Если DTO случайно передать в structured logger,
Java record включит секрет в строковое представление. По аналогии с `LoginRequest`, `LoginSessionResult` и 
`RefreshSessionResult` рекомендуется **redacted `toString()` и соответствующий unit test**. Токен должен оставаться 
доступным в JSON для `/csrf` — менять JSON response нельзя.

### P2 — Unicode validator не отвергает malformed UTF-16

`Utf8ByteLengthValidator` использует `String.getBytes(UTF_8)` и считает байты после возможной замены некорректных surrogate.
Это корректно для простой метрики byte length, но **не** является строгой проверкой well-formed Unicode. Если аннотация 
должна выполнять security-grade validation, дополнить явной проверкой или переименовать контракт. Не менять поведение без 
проверки всех call sites.

### P2 — RSA pair startup self-test

`JwtRsaKeyRing` сверяет modulus, размер, формат PEM. Желательно отдельным тестом подписывать JWT активным `kid` и проверять 
decoder, включая previous public keys и отвергание неизвестного `kid`. Это повышает уверенность, что ошибочно 
сконфигурированные keys не создают массовые 401 после запуска. Тесты `JwtCodecConfigurationTest` и security integration 
уже существуют: **сначала изучить, что из этого ими покрыто**.

### P2 — наблюдаемость ошибок выпуска cookie после commit

Login создаёт refresh-row до генерации access JWT и HTTP-отправки; refresh подписывает JWT до commit, но передача cookies 
всё равно позже. Нет глобальной транзакции между БД и браузером. Не пытаться сделать retry старого refresh token без анализа
строгой one-time semantics. Нужно измерять количество 5xx после commit, stale/abandoned refresh sessions, incidents из-за 
cross-tab concurrency.

### Не считать дефектами без дополнительных доказательств

- `SHA-256` вместо BCrypt для **512-bit random refresh** — корректное решение.
- `@Transactional(noRollbackFor=RefreshTokenReuseDetectedException.class)` — необходимо для durable termination + audit.
- `HttpOnly=false` у **CSRF** cookie — ожидаемое решение для SPA, в отличие от access/refresh.
- `@Transactional(MANDATORY)` на `RefreshReuseAuditService` — правильная граница обязательного аудита.
- `__Secure-` у refresh cookie с `Path=/api/auth` — **правильный** префикс; проблема именно в production invariant.
- `SUPER_ADMIN` не имеет автоматического доступа к tenant chat/knowledge — заданная RBAC-политика; изменять только по 
- продуктовым требованиям.
- `AuthService` зависит от `user`/`audit`/`ratelimit`: это ожидаемое orchestration направление, а не циклическая зависимость
- сама по себе.

---

## 8. Тесты в репозитории и что ещё добавить

В проверенной структуре есть unit/slice tests для `controller`, `exception`, `security`, `service`, а также 
PostgreSQL/Testcontainers integration tests (`integration/`). **Наличие файлов подтверждено; их прохождение в этой сессии 
не утверждается.**

### Существующее покрытие по именам и назначению

| Тест | Что проверяет / на что ориентирован |
|---|---|
| `AuthControllerSecurityTest`, `CsrfControllerTest` | HTTP controller contracts, CSRF response |
| `AuthExceptionHandlerTest` | Безопасная сериализация доменных auth ошибок |
| `CustomUserDetailsServiceTest` | UserDetails и password principal |
| `JwtCodecConfigurationTest` | JWT codec + issuer/audience/time/type validators |
| `PasswordEncodingConfigurationTest` | BCrypt и legacy-prefix compatibility |
| `SafeAiJwtAuthenticationConverterTest`, `SafeAiJwtAuthenticationConverterHardeningTest` | Strict JWT claims и invalid schema |
| `SecurityConfigIntegrationTest` | MVC+Security chain, cookie/Bearer, RBAC, CSRF, headers, JSON errors |
| `UserStatusFilterTest` | Актуальность security state, fail-closed |
| `AuthAuditRequestSupportTest`, `AuthEventServiceTest` | Audit metadata и события |
| `AuthCookiePropertiesTest`, `AuthCookieServiceTest` | Prefix/path/domain/lifetimes и cookie issuance/removal |
| `AuthServiceTest`, `AuthServiceExpiredRefreshTokenTest` | Login/refresh/logout orchestration |
| `AuthServiceRefreshReuseAuditRegressionTest`, `RefreshReuseAuditServiceTest` | Без повторного best-effort audit; required audit semantics |
| `JwtServiceTest` | Состав и выпуск JWT |
| `RefreshTokenCleanupBatchServiceTest`, `RefreshTokenCleanupJobTest` | Batch bounds, scheduling, результаты очистки |
| `RefreshTokenServiceTest` | Rotation, versions, reuse, locking, logout |
| `AuthPostgresConcurrencyIT` | Реальные конкурентные refresh/logout/security mutation, chain cleanup, commit failure |
| `OrganizationSecurityGateConcurrencyIT` | Organization gate `FOR SHARE` vs security mutation, concurrent refresh |
| `UserSecurityMutationTestService` | Вспомогательный объект для интеграционных сценариев |

Особенно ценны сценарии `twoConcurrentRefreshes_oneSuccess_oneReuse_familyTerminated`, 
`realCommitFailure_doesNotWriteSetCookie`, `twoCleanupWorkers_deleteExpiredReplacementChainsWithoutFkViolations`, 
`loginWaitingForDisableCannotIssueSessionAfterDisableCommit` и `concurrentRefreshOfOneTokenDoesNotCreateTwoSuccessors`. 
Они тестируют настоящую concurrency-модель, а не только Mockito interactions.

### Приоритетные новые регрессии

1. **P0 production startup:** контекст реально запускается с валидным host-only `__Host-` access, `__Secure-` refresh, `__Host-` CSRF, `Secure=true`, HSTS; некорректные комбинации отвергаются. Тестировать оба production detection signals.
2. **P1 Bearer POST / cookie POST:** контракт по CSRF совпадает с фактической SecurityFilterChain.
3. **JWT issuance/rotation:** signing active key и verifying active+previous; неверный `kid`, неподходящий key material, 
4. консервативный clock skew.
4. **Security state revocation:** stale cache, нескольких инстансов, concurrent mutation + revoked JWT.
5. **Никаких секретов в `toString()`/logs:** DTO с CSRF, login/refresh results, exceptions.
6. **Deployment smoke:** `Set-Cookie` attributes, `Cache-Control`, HSTS, CORS, management isolation, trusted proxy, HTTPS.

### Команды локальной проверки

Запуск из корня репозитория при наличии JDK 21 и Maven:

```bash
# Unit + Spring MVC slice / обычный Surefire набор
mvn -f backend/pom.xml test

# Full verification, включая integration tests через Failsafe
# Требуется рабочий Docker/Testcontainers и PostgreSQL test image.
mvn -f backend/pom.xml verify
```

Проверить реальную конфигурацию Surefire/Failsafe в `backend/pom.xml`: стандартные Surefire `*Test`, Failsafe `*IT` и 
их Maven phases должны соответствовать проекту. При воспроизведении P0 пользоваться **полным production context** или 
отдельным `ApplicationContextRunner`, который явно включает `ProductionAuthCookieInvariantValidator`; non-prod tests этот 
дефект не выявляют.

---

## 9. Эксплуатация и production checklist

Перед развёртыванием должно выполняться **всё** нижеперечисленное, а не только успешная компиляция:

- [ ] Исправлен P0 конфликт `__Host-`/`__Secure-` refresh-cookie; подтверждён полноценный production startup.
- [ ] Production JWT keyring содержит active RSA private/public и previous **public-only** ключи; приватные ключи доступны только backend runtime, не репозиторию/frontend.
- [ ] `app.security.jwt.issuer` — валидный HTTPS URI; audience и key IDs согласованы между всеми backend instances.
- [ ] `safeai.auth.cookies.secure=true`; Domain не задан; access `__Host-` + `Path=/`, refresh `__Secure-` + `Path=/api/auth`, CSRF `__Host-` + `Path=/`.
- [ ] `SameSite` соответствует реальной схеме origins, CORS разрешает **только** проверенные exact HTTPS origins с credentials.
- [ ] HSTS действительно приходит на публичном HTTPS-response; решение `includeSubDomains`/`preload` согласовано для домена.
- [ ] Spring CSRF policy проверена по Bearer/cookie матрице; unsafe cookie requests без token отклоняются.
- [ ] LoginRateLimit/RefreshRateLimit fail-closed; определена стратегия Redis outage и сигналов алертинга.
- [ ] Security-state invalidation подтверждена при изменении пароля, email, роли, enabled и organization state.
- [ ] Migration `refresh_tokens` и FK/indexes согласованы с cleanup SQL; PostgreSQL privileges достаточны и минимальны.
- [ ] Успешны concurrency Testcontainers tests: rotate vs rotate, rotate vs logout, security mutation, multi-worker cleanup.
- [ ] Audit outbox и revocation commit атомарны на реальном PostgreSQL; error/failure paths не потеряли mandatory incident.
- [ ] Метрики: login failure, 401 по кодам, 503 auth/Redis, reuse incidents, latency password verification, number of active/family refresh tokens, cleanup lag/batches.
- [ ] Логи и responses не содержат raw access/refresh/CSRF токенов, паролей, signing keys, cookies или Authorization headers.
- [ ] Access JWT TTL и access cookie TTL совпадают; key rotation не разрушает ещё действительные access tokens.
- [ ] Проверены секреты, Nginx/ingress, backup/restore, PostgreSQL readiness, TLS, management port isolation и rollback plan.

### Варианты корректной cookie-политики

| Cookie | Имя (production default/recommended) | Path | Secure | HttpOnly | Domain |
|---|---|---|---|---|---|
| Access JWT | `__Host-safeai-access` | `/` | true | true | отсутствует |
| Refresh opaque token | `__Secure-safeai-refresh` | `/api/auth` | true | true | отсутствует |
| CSRF | `__Host-XSRF-TOKEN` | `/` | true | false | отсутствует |

**Не устанавливать произвольные значения max age без согласования** `JwtProperties`, `AuthCookieProperties`, refresh 
DB TTL и reuse retention. Время исчисляется серверным `Clock`; edge и база должны иметь синхронизированное время.

### Minimal configuration example (концептуально)

```yaml
safeai:
  environment: production
  auth:
    cookies:
      secure: true
      same-site: Lax
      domain: ""
      access-token-name: __Host-safeai-access
      refresh-token-name: __Secure-safeai-refresh
      # Lifetimes должны быть согласованы с JWT и политикой продукта.
  security:
    hsts:
      enabled: false
      edge-managed: true  # Только если edge действительно выставляет HSTS
```

Это **фрагмент, не полный production `application.yml`**. Он **не заработает на исходном коде до устранения P0**. 
Остальные обязательные свойства (`jwt.keys`, `issuer`, `audience`, trusted CIDRs, management address/port, scheduler pool, 
BCrypt migration flag, CORS) описаны в `common` и production files; placeholder с secrets сюда намеренно не добавлен.

---

## 10. Архитектурные решения: что сохранять при рефакторинге

1. **`common` не должен импортировать `auth`.** Общие типы безопасности остаются в `common`; реальные фильтры, JWT issuance, credential verification и refresh persistence — в `auth`.
2. **`user` и `organization` обращаются через `identity.session.SessionRevocationPort`.** Реализация `UserSessionRevocationService` остаётся в `auth`; не возвращать направленную зависимость `user → auth`.
3. **DB security state — источник истины.** Cache ускоряет проверку access JWT, но его недоступность не делает отзыв необязательным.
4. **Не нарушать lock order** `organization FOR SHARE → user FOR UPDATE → refresh token FOR UPDATE`. Менять порядок после одобрения транзакционной модели и concurrency tests.
5. **Security reuse outbox обязателен в той же транзакции**, а не best-effort после commit.
6. **One-time refresh — строго одноразовый.** Не вводить скрыто повторяемый refresh без отдельной модели device session и replay policy.
7. **Отдельные HTTP и database границы** неизбежны. Нельзя утверждать exactly-once delivery cookies только потому, что JPA транзакция committed.
8. **Не расширять RBAC по «иерархии ролей»,** если политика явно исключает SUPER_ADMIN из tenant user functions.
9. **Защищать public contracts** (`ApiErrorCode`, JSON shape, cookie identities, JWT schema, status codes) от незаметной несовместимой миграции.
10. **Следовать тестам и инвариантам**, а не рефакторить ради длины класса, вкуса к паттернам или «полностью реактивной» архитектуры. Servlet + JPA здесь допустимы и соответствуют проекту.

---

## 11. Оценка на осень 2026 года и заключение

| Вопрос | Ответ |
|---|---|
| Является ли `auth` самостоятельным микросервисом? | **Нет.** Это выделенный функциональный модуль modular monolith. |
| Нужно ли перемещать его в `common`? | **Нет.** Границы уже концептуально корректны. |
| Требуется ли весь `user` для понимания `auth`? | **Для логики токенов — не весь; для доказательства немедленного отзыва и tenant isolation — да, соответствующие security mutations и cache contracts.** |
| Современен ли Java/Spring стек? | **Да**, поддерживается в проекте на Java 21 LTS и Spring Boot 4.1.1. |
| Применена ли промышленная модель refresh? | **Да, по исходному дизайну**: rotation, hashes, families, locks, required audit, cleanup. |
| Есть ли критическая ошибка? | **Да: P0 несовместимость production refresh-cookie invariant.** |
| Можно ли объявить готовность к production? | **Нет**, до устранения P0, проверки Bearer/CSRF сценария и реального staging/smoke/интеграционного прогона. |
| Нужно ли переписывать весь модуль? | **Нет.** Исправлять локально, с регрессиями, сохраняя подтверждённые гарантии. |

**Итог:** `auth` — достаточно зрелый с архитектурной точки зрения модуль с хорошим разделением credential authentication, JWT, refresh session persistence, transaction boundaries и security audit. Приоритет — **не масштабный рефакторинг**, а **снятие P0 startup блокера**, подтверждение CSRF-политики для Bearer и cookie, затем полноценный runtime/security regression и staging.

---

## 12. Ссылки и источники

**Проект (проверенный коммит):**

- [Модуль `auth` в GitHub](https://github.com/Amid-1/Safeai-desk/tree/ec2596ed079423d7103fa3218b51a0ce6cd46df0/backend/src/main/java/ru/safeai/gateway/auth)
- [Тесты `auth`](https://github.com/Amid-1/Safeai-desk/tree/ec2596ed079423d7103fa3218b51a0ce6cd46df0/backend/src/test/java/ru/safeai/gateway/auth)
- [`application-prod.yml`](https://github.com/Amid-1/Safeai-desk/blob/ec2596ed079423d7103fa3218b51a0ce6cd46df0/backend/src/main/resources/application-prod.yml)
- [`ProductionAuthCookieInvariantValidator`](https://github.com/Amid-1/Safeai-desk/blob/ec2596ed079423d7103fa3218b51a0ce6cd46df0/backend/src/main/java/ru/safeai/gateway/auth/config/ProductionAuthCookieInvariantValidator.java)
- [`AuthCookieProperties`](https://github.com/Amid-1/Safeai-desk/blob/ec2596ed079423d7103fa3218b51a0ce6cd46df0/backend/src/main/java/ru/safeai/gateway/auth/service/AuthCookieProperties.java)
- [`SecurityConfigIntegrationTest`](https://github.com/Amid-1/Safeai-desk/blob/ec2596ed079423d7103fa3218b51a0ce6cd46df0/backend/src/test/java/ru/safeai/gateway/auth/security/SecurityConfigIntegrationTest.java)

**Нормативные/справочные материалы:**

- [MDN: Set-Cookie, `__Host-` и `__Secure-`](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie)
- [MDN: Secure cookie configuration](https://developer.mozilla.org/en-US/docs/Web/Security/Practical_implementation_guides/Cookies)
- [Spring Security: CSRF reference](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
- [Spring Security: OAuth2 Resource Server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [Spring Framework: transaction rollback rules](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html)
- [PostgreSQL: explicit locking](https://www.postgresql.org/docs/current/explicit-locking.html)

*README описывает состояние конкретного commit. При изменении JWT claims, cookie policy, Flyway schema, refresh transaction 
semantics, RBAC, error codes или Spring Security chain обновлять документ вместе с кодом и регрессионными тестами.*
