# SafeAI Desk

**SafeAI Desk** — production-oriented full-stack MVP корпоративного AI Gateway для организаций.  
Проект показывает не просто чат с AI, а полноценную платформенную архитектуру вокруг безопасного корпоративного доступа к AI: аутентификация, роли, multi-tenancy, аудит, usage analytics, rate limiting, управление организациями и пользователями, а также подключаемые AI-провайдеры.

Проект можно использовать как портфолио/собеседовательный пример backend/frontend-системы уровня enterprise MVP.

---

## 1. Что решает проект

SafeAI Desk моделирует внутреннюю AI-платформу компании, где важно контролировать:

- кто имеет доступ к AI;
- к какой организации относится пользователь;
- кто может создавать пользователей и организации;
- кто может видеть аудит и usage;
- сколько токенов потребляет пользователь, организация и модель;
- как отозвать старые сессии после смены роли, отключения пользователя или сброса пароля;
- как защитить login от brute-force;
- как не дать администратору одной организации увидеть данные другой;
- как безопасно подключать mock/OpenAI/Anthropic providers.

---

## 2. Высокоуровневая архитектура

```text
┌──────────────────────────────┐
│        React Frontend         │
│ TypeScript + Vite + Router    │
└───────────────┬──────────────┘
                │
                │ HTTP / JSON
                │ Cookie Auth + CSRF
                ▼
┌──────────────────────────────┐
│      Spring Boot Backend      │
│ Java 21 + Security + JPA      │
└───────────────┬──────────────┘
                │
        ┌───────┼──────────────────────────────────────┐
        │       │                                      │
        ▼       ▼                                      ▼
┌──────────┐ ┌──────────┐                    ┌─────────────────┐
│PostgreSQL│ │  Redis   │                    │ External AI API  │
│Flyway DB │ │RateLimit │                    │OpenAI/Anthropic  │
└──────────┘ └──────────┘                    └─────────────────┘
```

Backend построен как модульный монолит.  
Каждый домен выделен в отдельный package: `auth`, `user`, `organization`, `chat`, `usage`, `audit`, `ai`, `ratelimit`, `common`.

---

## 3. Технологический стек

### Backend

- Java 21
- Spring Boot
- Spring Web MVC
- Spring Security
- Spring Data JPA / Hibernate
- PostgreSQL
- Flyway
- Redis
- Maven
- Lombok
- Bean Validation
- Docker / Docker Compose
- JUnit 5 / Mockito / Spring MVC tests

### Frontend

- React
- TypeScript
- Vite
- React Router
- Fetch API wrapper
- Cookie-based auth
- CSRF handling
- Простая MVP-стилизация

### Infrastructure

- PostgreSQL container
- Redis container
- Docker Compose
- Environment-based configuration
- Local seed через отдельные Flyway local migrations

---

## 4. Основные домены

```text
Organization
User
Role
ChatSession
ChatMessage
AuditEvent
Usage
RateLimit
AIProvider
RefreshToken
```

---

## 5. Ролевая модель

В системе используются три роли:

```text
SUPER_ADMIN
ADMIN
USER
```

### SUPER_ADMIN

Платформенный администратор.

Может:

- видеть все организации;
- создавать организации;
- включать/выключать организации;
- видеть всех пользователей всех организаций;
- создавать `ADMIN` и `USER` в организациях;
- смотреть весь audit;
- смотреть весь usage;
- фильтровать audit/usage по organizationId;
- управлять администраторами внутри организаций.

Не должен:

- создавать еще одного `SUPER_ADMIN` через обычную форму пользователей;
- случайно создавать пользователей в `SafeAI Platform`;
- работать как обычный пользователь клиентской организации.

`SUPER_ADMIN` создается через local seed/Flyway или отдельный platform-admin flow.

### ADMIN

Администратор внутри одной организации.

Может:

- видеть пользователей только своей организации;
- создавать `USER` внутри своей организации;
- сбрасывать пароль `USER` своей организации;
- включать/выключать `USER` своей организации;
- смотреть audit только своей организации;
- смотреть usage только своей организации;
- пользоваться чатом.

Безопасное production-правило:

```text
ADMIN не должен создавать других ADMIN.
Назначение ADMIN лучше оставить только SUPER_ADMIN.
```

### USER

Обычный пользователь.

Может:

- видеть только свои чаты;
- создавать свои чаты;
- отправлять сообщения;
- видеть только свои сообщения.

Не может:

- видеть Users;
- видеть Organizations;
- видеть Audit;
- видеть Usage;
- создавать пользователей;
- менять роли;
- видеть чужие чаты.

---

## 6. Multi-tenancy модель

SafeAI Desk использует organization-based multi-tenancy на уровне приложения.

```text
SUPER_ADMIN -> global/platform scope
ADMIN       -> organization scope
USER        -> own resources only
```

Tenant isolation реализована на уровне service-логики, а не только через URL security.

Изоляция применяется для:

- пользователей;
- организаций;
- чатов;
- сообщений;
- audit events;
- usage analytics;
- rate-limit событий;
- security-state invalidation.

---

## 7. Security Architecture

### Browser auth flow

Frontend использует cookie-based authentication:

```text
access_token  -> HttpOnly cookie
refresh_token -> HttpOnly cookie
XSRF-TOKEN    -> readable cookie для frontend
```

Frontend не хранит JWT в `localStorage`.

Для authenticated requests frontend делает:

```ts
fetch('/api/...', {
  credentials: 'include'
})
```

Для unsafe methods (`POST`, `PUT`, `PATCH`, `DELETE`) frontend отправляет CSRF header:

```text
X-XSRF-TOKEN: <value from XSRF-TOKEN cookie>
```

### Auth endpoints

```http
GET  /api/auth/csrf
POST /api/auth/login
POST /api/auth/refresh
POST /api/auth/logout
GET  /api/auth/me
```

`/api/auth/login`, `/api/auth/refresh`, `/api/auth/logout`, `/api/auth/csrf` должны быть доступны без access-token на уровне Spring Security, но unsafe методы остаются под CSRF-защитой.

### JWT claims

Access JWT содержит:

```text
sub
email
userId
organizationId
roles
tokenVersion
jti
iat
exp
iss
```

Backend не полагается только на email.  
Для tenant isolation и security checks используются `userId`, `organizationId`, `roles`, `tokenVersion`.

### Refresh token rotation

Refresh tokens хранятся в БД в виде hash.

Refresh flow:

```text
POST /api/auth/refresh
↓
read refresh_token cookie
↓
hash token
↓
find token by hash with PESSIMISTIC_WRITE lock
↓
validate revoked/expired/reuse
↓
revoke old refresh token
↓
create new refresh token in same token_family_id
↓
issue new access token
↓
set new cookies
```

При reuse revoked refresh token:

```text
revoke all active tokens in same token family
audit SECURITY_REFRESH_REUSE_DETECTED
clear cookies
return 401
```

### Token invalidation через tokenVersion

При критичных изменениях security-состояния пользователя backend увеличивает `tokenVersion`.

Примеры:

- password reset;
- role change;
- user disabled;
- force logout scenario.

`UserStatusFilter` проверяет на каждом authenticated request:

```text
user.enabled == true
organization.enabled == true
tokenVersion из JWT == актуальный tokenVersion пользователя
```

Если токен устарел, backend возвращает `401`.

### Refresh session revocation

Кроме access-token invalidation через `tokenVersion`, backend отзывает активные refresh-сессии:

- после сброса пароля;
- после отключения пользователя;
- после изменения ролей;
- после отключения организации.

Это защищает от ситуации, когда старый refresh token может получить новый access token после изменения прав.

---

## 8. Backend-модули

```text
ru.safeai.gateway
├── admin
│   └── controller
│       └── AdminUsageController
│
├── ai
│   ├── config
│   ├── dto
│   ├── exception
│   ├── pricing
│   ├── provider
│   │   ├── anthropic
│   │   ├── mock
│   │   └── openai
│   └── web
│
├── audit
│   ├── controller
│   ├── dto
│   ├── entity
│   ├── listener
│   ├── repository
│   ├── service
│   └── AuditEventType
│
├── auth
│   ├── controller
│   ├── dto
│   ├── entity
│   ├── repository
│   ├── security
│   └── service
│
├── chat
│   ├── controller
│   ├── dto
│   ├── entity
│   ├── repository
│   └── service
│
├── common
│   ├── exception
│   ├── platform
│   └── security
│
├── organization
│   ├── controller
│   ├── dto
│   ├── entity
│   ├── event
│   ├── repository
│   └── service
│
├── ratelimit
│
├── usage
│   ├── dto
│   ├── repository
│   └── service
│
└── user
    ├── controller
    ├── dto
    ├── entity
    ├── event
    ├── repository
    ├── service
    └── validation
```

### Назначение backend-модулей

| Модуль | Назначение |
|---|---|
| `auth` | login, logout, refresh rotation, текущий пользователь, cookies, auth audit |
| `common.security` | JWT, Spring Security, principal, CORS, requestId, JSON 401/403 |
| `common.exception` | единый формат API ошибок |
| `common.platform` | platform organization settings |
| `ratelimit` | Redis-backed login/AI rate limiting |
| `organization` | организации, platform-level управление, org security events |
| `user` | пользователи, роли, enable/disable, reset password, password policy |
| `chat` | chat sessions/messages, ownership, message processing |
| `ai` | provider abstraction, mock/OpenAI/Anthropic, pricing |
| `audit` | запись и чтение audit events |
| `usage` | usage analytics и aggregation queries |
| `admin` | admin API entry points, например usage endpoints |

---

```text

safeai-desk/
├── backend/
│   ├── .mvn/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   │   └── ru/
│   │   │   │       └── safeai/
│   │   │   │           └── gateway/
│   │   │   │               ├── admin/
│   │   │   │               ├── ai/
│   │   │   │               ├── audit/
│   │   │   │               ├── auth/
│   │   │   │               ├── chat/
│   │   │   │               ├── common/
│   │   │   │               ├── organization/
│   │   │   │               ├── ratelimit/
│   │   │   │               ├── usage/
│   │   │   │               ├── user/
│   │   │   │               └── SafeaiBackendApplication.java
│   │   │   │
│   │   │   └── resources/
│   │   │       ├── application.yml
│   │   │       ├── application-local.yml
│   │   │       ├── application-local-nginx.yml
│   │   │       ├── application-prod.yml
│   │   │       ├── logback-spring.xml
│   │   │       └── db/
│   │   │           ├── migration/
│   │   │           │   ├── V1__init_schema.sql
│   │   │           │   ├── V2__seed_reference_data.sql
│   │   │           │   ├── V3__denormalize_chat_organization.sql
│   │   │           │   ├── V4__schema_hardening.sql
│   │   │           │   ├── V5__audit_event_types.sql
│   │   │           │   ├── V6__updated_at_triggers.sql
│   │   │           │   └── V7__usage_quotas_and_rollups.sql
│   │   │           │
│   │   │           └── local-migration/
│   │   │               └── V1000__seed_local_demo_data.sql
│   │   │
│   │   └── test/
│   │       ├── java/
│   │       │   └── ru/
│   │       │       └── safeai/
│   │       │           └── gateway/
│   │       │               ├── admin/
│   │       │               ├── ai/
│   │       │               ├── audit/
│   │       │               ├── auth/
│   │       │               ├── chat/
│   │       │               ├── common/
│   │       │               ├── organization/
│   │       │               ├── ratelimit/
│   │       │               ├── usage/
│   │       │               ├── user/
│   │       │               ├── PasswordHashGenerator.java
│   │       │               └── SafeaiBackendApplicationTests.java
│   │       │
│   │       └── resources/
│   │           └── application-test.yml
│   │
│   ├── .env.example
│   ├── .env.prod
│   ├── Dockerfile
│   ├── mvnw
│   ├── mvnw.cmd
│   └── pom.xml
│
├── frontend/
│   ├── src/
│   │   ├── api/
│   │   │   ├── adminApi.ts
│   │   │   ├── authApi.ts
│   │   │   ├── chatApi.ts
│   │   │   ├── http.ts
│   │   │   ├── organizationApi.ts
│   │   │   └── userApi.ts
│   │   │
│   │   ├── auth/
│   │   │   └── AuthContext.tsx
│   │   │
│   │   ├── components/
│   │   │   ├── ConfirmDialog.tsx
│   │   │   ├── ErrorBoundary.tsx
│   │   │   └── Modal.tsx
│   │   │
│   │   ├── pages/
│   │   │   ├── AdminAuditPage.tsx
│   │   │   ├── AdminOrganizationsPage.tsx
│   │   │   ├── AdminUsagePage.tsx
│   │   │   ├── AdminUsersPage.tsx
│   │   │   ├── ChatPage.tsx
│   │   │   └── LoginPage.tsx
│   │   │
│   │   ├── utils/
│   │   │   ├── format.ts
│   │   │   └── page.ts
│   │   │
│   │   ├── App.tsx
│   │   ├── global.d.ts
│   │   ├── index.css
│   │   ├── main.tsx
│   │   └── vite-env.d.ts
│   │
│   ├── index.html
│   ├── package.json
│   ├── package-lock.json
│   ├── tsconfig.app.json
│   ├── tsconfig.json
│   ├── tsconfig.node.json
│   └── vite.config.ts
│
├── infra/
│   └── docker-compose.yml
│
├── scripts/
│   ├── check-health.bat
│   ├── full-docker-up.bat
│   ├── psql-audit-events.bat
│   ├── run-backend-local.bat
│   ├── run-infra.bat
│   └── stop-infra.bat
│
├── docs/
├── README.md
├── ROADMAP.md
└── .gitignore

```


```text

ru.safeai.gateway
├── admin
│   └── controller
│       └── AdminUsageController
│
├── ai
│   ├── config
│   │   └── AiConfiguration
│   │
│   ├── dto
│   │   ├── AiChatRequest
│   │   ├── AiChatResponse
│   │   └── AiMessage
│   │
│   ├── exception
│   │   ├── AiProviderException
│   │   ├── AiProviderRateLimitedException
│   │   ├── AiProviderTimeoutException
│   │   └── AiProviderUnavailableException
│   │
│   ├── pricing
│   │   ├── ModelPricingProperties
│   │   └── ModelPricingService
│   │
│   ├── provider
│   │   ├── AiProvider
│   │   ├── AiProviderProperties
│   │   ├── AiProviderRetryExecutor
│   │   ├── AiProviderSupport
│   │   ├── AiRestClientFactory
│   │   ├── AiRetryProperties
│   │   │
│   │   ├── anthropic
│   │   │   ├── AnthropicProperties
│   │   │   └── AnthropicProvider
│   │   │
│   │   ├── mock
│   │   │   └── MockAiProvider
│   │   │
│   │   └── openai
│   │       ├── OpenAiProperties
│   │       └── OpenAiProvider
│   │
│   └── web
│       └── AiExceptionHandler
│
├── audit
│   ├── controller
│   │   └── AuditController
│   │
│   ├── dto
│   │   ├── AuditEventFilter
│   │   └── AuditEventResponse
│   │
│   ├── entity
│   │   └── AuditEventEntity
│   │
│   ├── listener
│   │   └── RateLimitAuditListener
│   │
│   ├── repository
│   │   └── AuditEventRepository
│   │
│   ├── service
│   │   ├── AuditEventQueryService
│   │   └── AuditEventService
│   │
│   └── AuditEventType
│
├── auth
│   ├── controller
│   │   ├── AuthController
│   │   └── CsrfController
│   │
│   ├── dto
│   │   ├── CurrentUserResponse
│   │   └── LoginRequest
│   │
│   ├── entity
│   │   └── RefreshTokenEntity
│   │
│   ├── repository
│   │   └── RefreshTokenRepository
│   │
│   ├── security
│   │   ├── CsrfCookieFilter
│   │   ├── CustomUserDetailsService
│   │   ├── SecurityConfig
│   │   ├── SpaCsrfTokenRequestHandler
│   │   └── UserStatusFilter
│   │
│   └── service
│       ├── AuthCookieProperties
│       ├── AuthCookieService
│       ├── AuthEventService
│       ├── AuthService
│       ├── RefreshTokenCleanupJob
│       ├── RefreshTokenService
│       └── UserSessionRevocationService
│
├── chat
│   ├── controller
│   │   └── ChatController
│   │
│   ├── dto
│   │   ├── ChatDetailsResponse
│   │   ├── ChatResponse
│   │   ├── CreateChatRequest
│   │   ├── MessageResponse
│   │   └── SendMessageRequest
│   │
│   ├── entity
│   │   ├── ChatMessageEntity
│   │   ├── ChatMessageRole
│   │   ├── ChatMessageStatus
│   │   └── ChatSessionEntity
│   │
│   ├── repository
│   │   ├── ChatMessageRepository
│   │   └── ChatSessionRepository
│   │
│   └── service
│       ├── ChatLockProperties
│       ├── ChatLockService
│       ├── ChatMapper
│       ├── ChatPersistenceService
│       ├── ChatProcessingContext
│       ├── ChatProperties
│       └── ChatService
│
├── common
│   ├── exception
│   │   ├── ApiErrorResponse
│   │   ├── ApiErrorResponseFactory
│   │   ├── BadRequestException
│   │   ├── ConflictException
│   │   ├── ExpiredRefreshTokenException
│   │   ├── ForbiddenOperationException
│   │   ├── GlobalExceptionHandler
│   │   ├── InvalidRefreshTokenException
│   │   ├── RateLimitExceededException
│   │   ├── RateLimitUnavailableException
│   │   ├── RefreshTokenReuseDetectedException
│   │   └── ResourceNotFoundException
│   │
│   ├── platform
│   │   └── PlatformProperties
│   │
│   └── security
│       ├── ClientIpProperties
│       ├── ClientIpResolver
│       ├── CorsProperties
│       ├── JsonAccessDeniedHandler
│       ├── JsonAuthenticationEntryPoint
│       ├── JsonSecurityErrorWriter
│       ├── JwtProperties
│       ├── JwtService
│       ├── RequestIdFilter
│       ├── RoleAuthorityMapper
│       ├── SafeAiJwtAuthenticationConverter
│       └── SafeAiUserPrincipal
│
├── organization
│   ├── controller
│   │   └── OrganizationController
│   │
│   ├── dto
│   │   ├── CreateOrganizationRequest
│   │   ├── OrganizationResponse
│   │   ├── UpdateOrganizationEnabledRequest
│   │   └── UpdateOrganizationRequest
│   │
│   ├── entity
│   │   └── OrganizationEntity
│   │
│   ├── event
│   │   └── OrganizationSecurityStateChangedEvent
│   │
│   ├── repository
│   │   └── OrganizationRepository
│   │
│   └── service
│       ├── OrganizationService
│       └── OrganizationStatusCacheInvalidationListener
│
├── ratelimit
│   ├── AiMessageRateLimitProperties
│   ├── LoginRateLimitProperties
│   ├── LoginRateLimitService
│   ├── RateLimitExceededEvent
│   ├── RateLimitKeyFactory
│   ├── RateLimitRedisKeyProperties
│   ├── RateLimitResult
│   ├── RedisFixedWindowRateLimiter
│   └── RedisRateLimitService
│
├── usage
│   ├── dto
│   │   ├── UsageDailySummaryResponse
│   │   ├── UsageModelSummaryResponse
│   │   ├── UsageSummaryResponse
│   │   └── UsageUserSummaryResponse
│   │
│   ├── repository
│   │   ├── UsageDailySummaryProjection
│   │   └── UsageQueryRepository
│   │
│   └── service
│       └── UsageQueryService
│
└── user
    ├── controller
    │   └── UserController
    │
    ├── dto
    │   ├── CreateUserRequest
    │   ├── ResetUserPasswordRequest
    │   ├── UpdateUserEnabledRequest
    │   ├── UpdateUserRolesRequest
    │   └── UserResponse
    │
    ├── entity
    │   ├── RoleEntity
    │   └── UserEntity
    │
    ├── event
    │   └── UserSecurityStateChangedEvent
    │
    ├── repository
    │   ├── RoleRepository
    │   └── UserRepository
    │
    ├── service
    │   ├── UserSecurityStatus
    │   ├── UserService
    │   ├── UserStatusCacheInvalidationListener
    │   ├── UserStatusCacheProperties
    │   └── UserStatusCacheService
    │
    └── validation
        └── PasswordPolicy

```

---

## 9. Frontend-модули

```text
frontend/
├── src/
│   ├── api/
│   │   ├── adminApi.ts
│   │   ├── authApi.ts
│   │   ├── chatApi.ts
│   │   ├── http.ts
│   │   ├── organizationApi.ts
│   │   └── userApi.ts
│   │
│   ├── auth/
│   │   └── AuthContext.tsx
│   │
│   ├── components/
│   │   ├── ConfirmDialog.tsx
│   │   ├── ErrorBoundary.tsx
│   │   └── Modal.tsx
│   │
│   ├── pages/
│   │   ├── AdminAuditPage.tsx
│   │   ├── AdminOrganizationsPage.tsx
│   │   ├── AdminUsagePage.tsx
│   │   ├── AdminUsersPage.tsx
│   │   ├── ChatPage.tsx
│   │   └── LoginPage.tsx
│   │
│   ├── utils/
│   │   ├── format.ts
│   │   └── page.ts
│   │
│   ├── App.tsx
│   ├── index.css
│   └── main.tsx
```

### Назначение frontend-файлов

| Файл | Назначение |
|---|---|
| `api/http.ts` | общий `apiRequest`, cookies, CSRF, refresh retry, `ApiError` |
| `api/authApi.ts` | login/logout/me |
| `api/chatApi.ts` | chats API |
| `api/userApi.ts` | user-management API |
| `api/organizationApi.ts` | organization-management API |
| `api/adminApi.ts` | audit и usage API |
| `auth/AuthContext.tsx` | состояние текущего пользователя, login/logout, unauthorized events |
| `App.tsx` | routes, protected routes, topbar |
| `LoginPage.tsx` | форма входа |
| `ChatPage.tsx` | чат, сообщения, pending/failed состояния |
| `AdminUsersPage.tsx` | управление пользователями |
| `AdminOrganizationsPage.tsx` | управление организациями |
| `AdminAuditPage.tsx` | audit events |
| `AdminUsagePage.tsx` | usage analytics |
| `components/Modal.tsx` | общий modal |
| `components/ConfirmDialog.tsx` | confirmation dialog |
| `components/ErrorBoundary.tsx` | fallback для frontend runtime errors |

---

## 10. User Management

Реализованные операции:

```http
POST  /api/users
GET   /api/users
GET   /api/users/{id}
PATCH /api/users/{id}/enabled
PATCH /api/users/{id}/roles
POST  /api/users/{id}/reset-password
```

Security rules:

```text
SUPER_ADMIN:
- может создавать USER/ADMIN в любой организации;
- не может создавать SUPER_ADMIN через обычный endpoint.

ADMIN:
- может видеть пользователей только своей организации;
- может создавать USER только в своей организации;
- не должен создавать ADMIN/SUPER_ADMIN;
- не может управлять SUPER_ADMIN.
```

Дополнительные protections:

- нельзя отключить самого себя;
- нельзя отключить platform admin через обычный user-management flow;
- role/password/enabled changes отзывают refresh-сессии;
- password policy ограничивает слабые пароли.

---

## 11. Organization Management

Реализованные операции:

```http
POST  /api/organizations
GET   /api/organizations
GET   /api/organizations/{id}
PATCH /api/organizations/{id}
PATCH /api/organizations/{id}/enabled
```

Security rules:

```text
SUPER_ADMIN:
- может создавать организации;
- может видеть все организации;
- может переименовывать/включать/отключать обычные организации.

ADMIN:
- может видеть только свою организацию.

USER:
- не имеет доступа к organization-management.
```

`SafeAI Platform` organization защищена:

```text
- нельзя отключить;
- нельзя переименовать;
- frontend скрывает Rename/Disable;
- backend запрещает mutation даже при прямом API-запросе.
```

---

## 12. Chat Flow

Chat module отвечает за:

- создание чатов;
- список чатов пользователя;
- загрузку деталей чата;
- отправку сообщений;
- сохранение user message;
- вызов AI provider;
- сохранение assistant response;
- сохранение failed assistant message при ошибке provider;
- audit и usage side effects.

Send message flow:

```text
POST /api/chats/{chatId}/messages
↓
validate request
↓
check chat ownership
↓
acquire Redis chat lock
↓
check AI message rate limit
↓
save USER message and prepare AI request
↓
call AI provider outside long DB transaction
↓
save ASSISTANT message or FAILED assistant message
↓
return updated chat details
```

Важно: внешний AI request не выполняется внутри длинной DB-транзакции.

Frontend после failed AI send перечитывает чат, чтобы показать сохраненное `FAILED` assistant message.

---

## 13. AI Provider Abstraction

Backend использует общий интерфейс:

```java
public interface AiProvider {
    AiChatResponse sendMessage(AiChatRequest request);
}
```

Реализации:

```text
MockAiProvider
OpenAiProvider
AnthropicProvider
```

### Mock provider

Используется для local development и demo:

- не требует API key;
- не тратит деньги;
- позволяет тестировать chat/audit/usage.

### OpenAI provider

Поддерживает request через Responses API scaffold:

- request payload;
- `store=false` по умолчанию;
- parsing output text;
- usage tokens;
- provider request id;
- timeout/rate-limit/unavailable mapping.

### Anthropic provider

Поддерживает Messages API scaffold:

- system prompt отдельно;
- normalized messages;
- content blocks parsing;
- usage tokens;
- error mapping.

### Pricing

Usage cost — это estimate, основанный на конфиге pricing:

```yaml
safeai:
  ai:
    pricing:
      models:
        - model: mock-safeai
          input-usd-per-1m-tokens: 0
          output-usd-per-1m-tokens: 0
        - model: gpt-4.1
          input-usd-per-1m-tokens: 2.00
          output-usd-per-1m-tokens: 8.00
        - model: claude-opus-4-8
          input-usd-per-1m-tokens: 15.00
          output-usd-per-1m-tokens: 75.00
```

Если модель не найдена в pricing config, cost считается `0`.

---

## 14. Rate Limiting

Rate limiting работает через Redis.

### Login rate limit

Используются два лимита:

```text
email-based limit
IP-based limit
```

Назначение:

```text
email limit -> защита конкретного аккаунта
IP limit    -> защита от массового перебора
```

При превышении:

- возвращается `429`;
- событие пишется в audit как `RATE_LIMIT_EXCEEDED`;
- frontend показывает ошибку с requestId.

### AI message rate limit

AI-запросы ограничиваются по пользователю/организации в зависимости от текущей конфигурации.

При превышении:

- запрос блокируется;
- событие публикуется;
- audit фиксирует rate limit event.

### Redis atomicity

Limiter использует Lua script для атомарного `INCR + TTL`.

---

## 15. Audit Logging

Audit events пишутся в PostgreSQL.

Audit principles:

```text
- audit не должен хранить полный prompt content;
- audit должен содержать organizationId;
- audit write не должен ломать основной business flow;
- ADMIN видит audit только своей организации;
- SUPER_ADMIN видит global audit;
- details проходят sanitization перед JSONB storage.
```

Примеры event types:

```text
USER_LOGIN_SUCCESS
USER_LOGIN_FAILED
USER_LOGOUT
SECURITY_REFRESH_REUSE_DETECTED
CHAT_CREATED
CHAT_MESSAGE_SENT
AI_RESPONSE_RECEIVED
AI_RESPONSE_FAILED
USER_CREATED
USER_ENABLED_CHANGED
USER_ROLES_CHANGED
USER_PASSWORD_RESET
ORGANIZATION_CREATED
ORGANIZATION_NAME_CHANGED
ORGANIZATION_ENABLED_CHANGED
RATE_LIMIT_EXCEEDED
```

Admin audit endpoints:

```http
GET /api/admin/audit-events
GET /api/admin/audit-events/users/{userId}
```

---

## 16. Usage Analytics

Usage вынесен в отдельный backend-модуль `usage`.

Usage считается по assistant messages:

```text
role = ASSISTANT
status = COMPLETED
model is not null
```

Отслеживается:

```text
userId
userEmail
organizationId
model
inputTokens
outputTokens
totalTokens
costUsd
date
```

Usage views:

```http
GET /api/admin/usage/summary
GET /api/admin/usage/users
GET /api/admin/usage/models
GET /api/admin/usage/daily
GET /api/admin/usage/by-user/{userId}
GET /api/admin/usage/by-organization/{organizationId}
```

Scope rules:

```text
SUPER_ADMIN -> global usage
ADMIN       -> organization-scoped usage
```

Для organization-scoped usage используются denormalized organization snapshots на `chat_messages`, чтобы исторический usage не "переехал" при переносе пользователя в другую организацию.

Default range:

```text
last 30 days
```

Max range:

```text
366 days
```

Daily usage группируется по UTC date.

---

## 17. Frontend функциональность

### Login

- login через email/password;
- JWT не хранится в localStorage;
- auth state загружается через `/api/auth/me`;
- при 401 frontend пытается refresh;
- при refresh failure сбрасывает пользователя и отправляет на login.

### Chat

- создание чатов;
- список чатов;
- открытие чата;
- textarea input;
- Ctrl+Enter для отправки;
- assistant pending indicator;
- отображение failed assistant messages;
- отображение model/tokens/cost для assistant response.

### Users

- список пользователей;
- фильтр по ролям;
- создание пользователя;
- enable/disable;
- смена роли;
- reset password через modal;
- confirmation dialogs;
- auto-dismiss success message.

### Organizations

- создание организаций;
- список организаций;
- rename через modal;
- enable/disable через confirmation dialog;
- protection для SafeAI Platform;
- auto-dismiss success message.

### Audit

- фильтр по event type;
- фильтр по user email;
- фильтр по date range;
- фильтр по organizationId для SUPER_ADMIN;
- pagination;
- отображение JSON details.

### Usage

- tabs: summary/users/models/daily;
- фильтр по date range;
- фильтр по model для summary;
- cost display;
- daily UTC note.

---

## 18. API Overview

### Auth

```http
GET  /api/auth/csrf
POST /api/auth/login
POST /api/auth/refresh
POST /api/auth/logout
GET  /api/auth/me
```

### Chats

```http
POST /api/chats
GET  /api/chats
GET  /api/chats/{id}
POST /api/chats/{id}/messages
```

### Users

```http
POST  /api/users
GET   /api/users
GET   /api/users/{id}
PATCH /api/users/{id}/enabled
PATCH /api/users/{id}/roles
POST  /api/users/{id}/reset-password
```

### Organizations

```http
POST  /api/organizations
GET   /api/organizations
GET   /api/organizations/{id}
PATCH /api/organizations/{id}
PATCH /api/organizations/{id}/enabled
```

### Admin Audit

```http
GET /api/admin/audit-events
GET /api/admin/audit-events/users/{userId}
```

### Admin Usage

```http
GET /api/admin/usage/summary
GET /api/admin/usage/users
GET /api/admin/usage/models
GET /api/admin/usage/daily
GET /api/admin/usage/by-user/{userId}
GET /api/admin/usage/by-organization/{organizationId}
```

---

## 19. Error Response Format

Backend возвращает единый формат ошибок:

```json
{
  "timestamp": "2026-01-01T12:00:00Z",
  "status": 400,
  "error": "VALIDATION_ERROR",
  "message": "Ошибка валидации запроса",
  "path": "/api/users",
  "requestId": "abc-123",
  "fieldErrors": {
    "email": [
      "must be a well-formed email address"
    ]
  }
}
```

Типовые error codes:

```text
BAD_REQUEST
VALIDATION_ERROR
UNAUTHORIZED
FORBIDDEN
NOT_FOUND
CONFLICT
RATE_LIMIT_EXCEEDED
RATE_LIMIT_UNAVAILABLE
AI_PROVIDER_TIMEOUT
AI_PROVIDER_RATE_LIMITED
AI_PROVIDER_UNAVAILABLE
AI_PROVIDER_ERROR
INTERNAL_SERVER_ERROR
```

---

## 20. Request ID

Backend поддерживает request correlation через `X-Request-Id`.

Поведение:

```text
если клиент передал валидный X-Request-Id -> использовать его
иначе -> сгенерировать UUID
```

RequestId:

- добавляется в response header;
- добавляется в MDC logging context;
- включается в API error response;
- помогает связывать frontend error и backend logs.

---

## 21. Database / Flyway

Backend использует Flyway.

```text
db/migration          -> обязательные production migrations
db/local-migration    -> local-only seed/demo data
```

Принципы:

```text
- уже примененные миграции не редактируются;
- изменения схемы добавляются новыми versioned migrations;
- Hibernate ddl-auto должен валидировать схему, а не создавать ее;
- критичные constraints должны жить в PostgreSQL;
- local seed не должен запускаться в production.
```

Текущие основные направления миграций:

```text
V1  init schema
V2  seed reference data
V3  denormalize chat organization
V4  schema hardening
V6  updated_at triggers
V7  usage quotas and rollups
V1000 local demo data
```

`V1000__seed_local_demo_data.sql` применяется только при local profile.

---

## 22. Local Development

### Требования

```text
Java 21
Maven
Node.js
npm
Docker
Docker Compose
```

### Запуск инфраструктуры

Из корня проекта:

```bash
docker compose -f infra/docker-compose.yml up -d
```

Или через scripts:

```bat
scripts\run-infra.bat
```

### Запуск backend

```bat
cd backend
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
```

Или:

```bat
scripts\run-backend-local.bat
```

### Запуск frontend

```bat
cd frontend
npm install
npm run dev
```

### URL

```text
Frontend: http://localhost:5173
Backend:  http://localhost:8080
Postgres: localhost:5432
Redis:    localhost:6379
```

---

## 23. Local demo account

Local seed создает платформенного пользователя:

```text
email:    superadmin@test.com
role:     SUPER_ADMIN
org:      SafeAI Platform
password: зависит от bcrypt hash в V1000__seed_local_demo_data.sql
```

В текущей local-сборке пароль использовался:

```text
Admin_Dev_2026!Strong#91
```

Demo credentials нельзя использовать в production.

---

## 24. Проверка проекта

### Backend tests

```bat
cd backend
mvnw.cmd test
```

### Frontend build

```bat
cd frontend
npm run build
```

### Проверка Docker services

```bat
docker ps
```

### Проверка Flyway local seed

```sql
select version, description, success
from flyway_schema_history
order by installed_rank;
```

### Проверка local SUPER_ADMIN

```sql
select
    u.email,
    r.name as role
from users u
join user_roles ur on ur.user_id = u.id
join roles r on r.id = ur.role_id
where lower(u.email) = lower('superadmin@test.com');
```

---

## 25. Configuration

Основные группы конфигурации:

```text
spring.datasource.*
spring.flyway.*
safeai.jwt.*
safeai.auth.cookies.*
safeai.platform.*
safeai.cors.*
safeai.ratelimit.*
safeai.ai.*
safeai.ai.openai.*
safeai.ai.anthropic.*
safeai.ai.pricing.*
```

Пример выбора AI provider:

```yaml
safeai:
  ai:
    provider: mock
```

Варианты:

```text
mock
openai
anthropic
```

Production secrets должны задаваться через environment variables или secret manager:

```text
JWT secret
DB password
Redis password
OpenAI API key
Anthropic API key
```

---

## 26. Security notes

- JWT не хранится в localStorage.
- Browser auth использует HttpOnly cookies.
- Unsafe methods защищены CSRF.
- Refresh tokens хранятся в БД только как hash.
- Refresh rotation защищает от token reuse.
- tokenVersion отзывает старые access JWT.
- Role/user/org changes отзывают refresh-сессии.
- ADMIN tenant-scoped.
- SUPER_ADMIN platform-scoped.
- Platform organization защищена от изменения.
- Audit details не должны содержать passwords/tokens/prompts/responses/API keys/cookies.

---

## 27. Interview positioning

SafeAI Desk можно описывать так:

> Production-oriented full-stack MVP корпоративного AI Gateway с multi-tenancy, RBAC, cookie-based JWT auth, refresh-token rotation, CSRF, audit logging, usage analytics, Redis rate limits, session revocation и подключаемыми AI providers.

Ключевые engineering decisions:

```text
1. Cookie-based auth вместо localStorage JWT.
2. CSRF для unsafe browser requests.
3. Refresh-token rotation с reuse detection.
4. tokenVersion для отзыва старых access JWT.
5. Refresh session revocation при security changes.
6. Organization-based tenant isolation.
7. ADMIN ограничен своей организацией.
8. SUPER_ADMIN имеет platform/global scope.
9. Audit events tenant-aware через organizationId.
10. Usage считается через organization snapshot сообщений.
11. Chat ownership проверяется до обработки сообщения.
12. Chat lock защищает от параллельной отправки в один чат.
13. AI provider abstraction отделяет бизнес-логику от внешнего API.
14. Mock provider позволяет demo без внешних ключей.
15. External AI calls выполняются вне длинных DB transactions.
```

---

## 28. Возможное дальнейшее развитие

```text
1. Organization selector при создании пользователя SUPER_ADMIN.
2. Запрет ADMIN создавать ADMIN на backend и frontend.
3. Session/device management UI.
4. Monthly organization budgets.
5. Model allowlist per organization.
6. Usage charts.
7. RAG knowledge base.
8. Document upload and indexing.
9. Policy engine для AI prompts.
10. SSO/OIDC.
11. Testcontainers integration tests.
12. Production deployment profile with Nginx/TLS.
```
