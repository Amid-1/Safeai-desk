# SafeAI Desk

**SafeAI Desk** — production-oriented full-stack MVP корпоративного AI Gateway для организаций.

Проект демонстрирует не просто AI-чат, а полноценную платформенную архитектуру вокруг безопасного корпоративного доступа к AI: multi-tenancy, RBAC, cookie-based JWT authentication, CSRF, refresh-token rotation, audit logging, usage analytics, Redis rate limiting, управление организациями и пользователями, а также подключаемые AI-провайдеры.

Проект можно использовать как портфолио/собеседовательный пример backend/frontend-системы уровня enterprise MVP.

---

## Содержание

1. [Что решает проект](#что-решает-проект)
2. [Архитектура](#архитектура)
3. [Технологический стек](#технологический-стек)
4. [Ролевая модель](#ролевая-модель)
5. [Multi-tenancy](#multi-tenancy)
6. [Security architecture](#security-architecture)
7. [Backend-модули](#backend-модули)
8. [Frontend-модули](#frontend-модули)
9. [User management](#user-management)
10. [Organization management](#organization-management)
11. [Chat flow](#chat-flow)
12. [AI providers](#ai-providers)
13. [Rate limiting](#rate-limiting)
14. [Audit logging](#audit-logging)
15. [Usage analytics](#usage-analytics)
16. [API overview](#api-overview)
17. [Database и Flyway](#database-и-flyway)
18. [Local development](#local-development)
19. [Проверка проекта](#проверка-проекта)
20. [Production notes](#production-notes)
21. [Interview positioning](#interview-positioning)
22. [Roadmap](#roadmap)

---

## Что решает проект

SafeAI Desk моделирует внутреннюю AI-платформу компании, где важно контролировать:

- кто имеет доступ к AI;
- к какой организации относится пользователь;
- кто может создавать организации и пользователей;
- кто может видеть audit и usage;
- сколько токенов и денег потребляет пользователь, организация и модель;
- как отозвать старые сессии после смены роли, отключения пользователя или сброса пароля;
- как защитить login от brute-force;
- как не дать администратору одной организации увидеть данные другой;
- как безопасно подключать mock/OpenAI/Anthropic providers.

Типовой сценарий:

```text
SafeAI Platform organization
└── superadmin@test.com        SUPER_ADMIN

Demo Company
├── admin@test.com             ADMIN
└── user@test.com              USER

ООО "Клевер"
├── admin@klever.ru            ADMIN
├── user1@klever.ru            USER
└── user2@klever.ru            USER
```

---

## Архитектура

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
│ Flyway   │ │RateLimit │                    │OpenAI/Anthropic  │
└──────────┘ └──────────┘                    └─────────────────┘
```

Backend построен как модульный монолит. Домены разделены по package-структуре: `auth`, `user`, `organization`, 
`chat`,`usage`, `audit`, `ai`, `ratelimit`, `common`, `admin`.

---

## Технологический стек

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
- JUnit 5 / Mockito / Spring tests
- Docker / Docker Compose

### Frontend

- React
- TypeScript
- Vite
- React Router
- Fetch API wrapper
- Cookie-based authentication
- CSRF handling
- Typed API clients
- Basic admin UI для MVP

### Infrastructure

- PostgreSQL container
- Redis container
- Docker Compose
- Environment-based configuration
- Local seed через отдельные Flyway local migrations
- Production-oriented Docker/Nginx model

---

## Основные домены

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

## Ролевая модель

В системе используются три роли:

```text
SUPER_ADMIN
ADMIN
USER
```

### SUPER_ADMIN

Платформенный администратор.

Может:

- создавать организации;
- видеть все организации;
- включать и отключать обычные организации;
- создавать `ADMIN` и `USER` в организациях;
- видеть пользователей всех организаций;
- управлять `ADMIN` и `USER`;
- смотреть global audit;
- смотреть global usage;
- фильтровать audit/usage по организации.

Не может через обычный user-management flow:

- создать нового `SUPER_ADMIN`;
- изменить существующего `SUPER_ADMIN`;
- создать пользователя в `SafeAI Platform` organization.

`SUPER_ADMIN` создается через local seed/Flyway или отдельный platform-admin bootstrap flow.

### ADMIN

Администратор внутри одной организации.

Может:

- видеть пользователей только своей организации;
- создавать только `USER` в своей организации;
- редактировать `USER` своей организации;
- сбрасывать пароль `USER` своей организации;
- включать и отключать `USER` своей организации;
- смотреть audit только своей организации;
- смотреть usage только своей организации;
- пользоваться чатом.

Не может:

- создавать `ADMIN`;
- назначать роль `ADMIN`;
- управлять другим `ADMIN`;
- видеть чужие организации;
- видеть пользователей других организаций;
- управлять `SUPER_ADMIN`.

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

### Краткая матрица прав

| Операция | SUPER_ADMIN | ADMIN | USER |
|---|---:|---:|---:|
| Создать организацию | Да | Нет | Нет |
| Переименовать организацию | Да, кроме platform org | Нет | Нет |
| Отключить организацию | Да, кроме platform org | Нет | Нет |
| Создать ADMIN | Да | Нет | Нет |
| Создать USER | Да | Да, только в своей org | Нет |
| Редактировать USER | Да | Да, только в своей org | Нет |
| Управлять ADMIN | Да | Нет | Нет |
| Управлять SUPER_ADMIN | Нет | Нет | Нет |
| Смотреть global audit/usage | Да | Нет | Нет |
| Смотреть org audit/usage | Да | Да, только свою org | Нет |
| Пользоваться чатом | Да | Да | Да |

---

## Multi-tenancy

SafeAI Desk использует organization-based multi-tenancy на уровне приложения.

```text
SUPER_ADMIN -> global/platform scope
ADMIN       -> organization scope
USER        -> own resources only
```

Tenant isolation реализована в service-layer, а не только через URL security.

Изоляция применяется для:

- пользователей;
- организаций;
- чатов;
- сообщений;
- audit events;
- usage analytics;
- rate-limit событий;
- security-state invalidation.

Для usage analytics `organization_id` денормализован в `chat_sessions` и `chat_messages`, чтобы исторические данные не теряли tenant context.

---

## Security architecture

### Browser auth flow

Frontend использует cookie-based authentication:

```text
access_token  -> HttpOnly cookie
refresh_token -> HttpOnly cookie
XSRF-TOKEN    -> readable cookie для frontend
```

JWT не хранится в `localStorage`.

Для authenticated requests frontend использует:

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

`/api/auth/login`, `/api/auth/refresh`, `/api/auth/logout`, `/api/auth/csrf` доступны без access-token на уровне 
Spring Security, но unsafe methods остаются под CSRF-защитой.

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

Backend не полагается только на email. Для tenant isolation и security checks используются `userId`, 
`organizationId`, `roles`, `tokenVersion`.

### Refresh token rotation

Refresh tokens хранятся в БД только в виде hash.

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

При критичных изменениях security-состояния backend увеличивает `tokenVersion` пользователя.

Примеры:

- password reset;
- role change;
- user disabled;
- user email changed;
- organization disabled.

`UserStatusFilter` проверяет на каждом authenticated request:

```text
user.enabled == true
organization.enabled == true
tokenVersion из JWT == актуальный tokenVersion пользователя
organizationId из JWT == актуальный organizationId пользователя
```

Если access token устарел, backend возвращает `401`.

### Refresh session revocation

Backend отзывает активные refresh-сессии:

- после сброса пароля;
- после отключения пользователя;
- после изменения ролей;
- после изменения email;
- после отключения организации.

Это защищает от ситуации, когда старый refresh token может получить новый access token после изменения прав.

### Отключение организации

При отключении организации:

- все активные refresh-токены пользователей организации отзываются;
- `tokenVersion` увеличивается для всех пользователей организации;
- cache статуса пользователей инвалидируется через event;
- существующие access tokens становятся недействительными после проверки `UserStatusFilter`;
- audit фиксирует изменение и `requiresRelogin=true`.

При повторном включении организации refresh tokens не восстанавливаются. Пользователям нужно войти заново.

### Пароли

Пароль ограничен 72 символами из-за BCrypt. Для более строгого production-режима можно добавить проверку UTF-8 
byte length или перейти на Argon2.

---

## Backend-модули

```text
ru.safeai.gateway
├── admin
├── ai
├── audit
├── auth
├── chat
├── common
├── organization
├── ratelimit
├── usage
└── user
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
│   │   │       ├── application-security-example.yml
│   │   │       ├── logback-spring.xml
│   │   │       └── db/
│   │   │           ├── migration/
│   │   │           │   ├── V1__init_schema.sql
│   │   │           │   ├── V2__seed_reference_data.sql
│   │   │           │   ├── V3__denormalize_chat_organization.sql
│   │   │           │   ├── V4__schema_hardening.sql
│   │   │           │   ├── V5__audit_event_types.sql
│   │   │           │   ├── V6__updated_at_triggers.sql
│   │   │           │   ├── V7__usage_quotas_and_rollups.sql
│   │   │           │   ├── V8__usage_chat_messages_indexes.sql
│   │   │           │   ├── V9__audit_events_indexes.sql
│   │   │           │   ├── V10__schema_hardening_timestamps_audit_rollups.sql
│   │   │           │   ├── V11__add_user_updated_audit_event_type.sql
│   │   │           │   ├── V12__enforce_tenant_and_refresh_token_integrity.sql
│   │   │           │   ├── V13__identity_refresh_and_message_integrity.sql
│   │   │           │   ├── V14__chat_turn_idempotency_and_integrity.sql
│   │   │           │   ├── V15__audit_query_and_retention_indexes.sql
│   │   │           │   ├── V16__preserve_usage_history_on_user_delete.sql
│   │   │           │   ├── V17__ai_usage_and_pricing_metadata.sql
│   │   │           │   ├── V18__audit_actor_snapshots.sql
│   │   │           │   └── V19__user_management_details_and_audit.sql
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
│   │       │               └── SafeaiBackendApplicationTests.java
│   │       │
│   │       └── resources/
│   │           └── application-test.yml
│   │
│   ├── .env
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
│   │   │   ├── Modal.tsx
│   │   │   ├── StateBlock.tsx
│   │   │   ├── admin/
│   │   │   │   ├── audit/
│   │   │   │   │    ├── AuditActor.tsx
│   │   │   │   │    ├── AuditDetailsModal.tsx
│   │   │   │   │    ├── AuditFilters.tsx
│   │   │   │   │    ├── AuditPagination.tsx
│   │   │   │   │    ├── AuditTable.tsx
│   │   │   │   │    └── types.ts
│   │   │   │   │    
│   │   │   │   ├── UserActionsMenu.tsx
│   │   │   │   ├── UserIdentityCell.tsx
│   │   │   │   └── UserRoleBadge.tsx
│   │   │   │      
│   │   │   ├── ConfirmDialog.tsx   
│   │   │   ├── ErrorBoundary.tsx    
│   │   │   ├── Modal.tsx    
│   │   │   └── StateBlock.tsx    
│   │   │         
│   │   │         
│   │   ├── hooks/
│   │   │   ├── admin/
│   │   │   │   ├── useAuditDirectories.ts
│   │   │   │   └── useAuditEvents.ts
│   │   │   │
│   │   │   └── useAutoClearMessage.ts
│   │   │
│   │   │
│   │   ├── pages/
│   │   │   ├── AdminAuditPage.tsx
│   │   │   ├── AdminOrganizationsPage.tsx
│   │   │   ├── AdminUsagePage.tsx
│   │   │   ├── AAdminUsersPage.css
│   │   │   ├── AdminUsersPage.tsx
│   │   │   ├── ChatPage.tsx
│   │   │   └── LoginPage.tsx
│   │   │
│   │   ├── styles/
│   │   │   └── user-management-additions.css
│   │   │
│   │   ├── test/
│   │   │   └── setup.ts
│   │   │
│   │   ├── utils/
│   │   │   ├── date.ts
│   │   │   ├── format.ts
│   │   │   ├── organizations.ts
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
│   ├── nginx/
│   │   └── nginx.conf
│   ├── docker-compose.local.yml
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

ru.safeai.gateway/
├── admin/
│   └── controller/
│       └── AdminUsageController
│
├── ai/
│   ├── config/
│   │   └── AiConfiguration
│   │
│   ├── dto/
│   │   ├── AiChatRequest
│   │   ├── AiChatResponse
│   │   ├── AiMessage
│   │   └── AiMessageRole
│   │
│   ├── exception/
│   │   ├── AiProviderErrorType
│   │   ├── AiProviderException
│   │   ├── AiProviderOverloadedException
│   │   ├── AiProviderRateLimitedException
│   │   ├── AiProviderTimeoutException
│   │   └── AiProviderUnavailableException
│   │
│   ├── metadata/
│   │   ├── AiResponseStatus
│   │   ├── PricingStatus
│   │   └── UsageStatus
│   │
│   ├── pricing/
│   │   ├── ModelPricingProperties
│   │   ├── ModelPricingService
│   │   └── PricingResult
│   │
│   ├── provider/
│   │   ├── AiJsonNodeSupport
│   │   ├── AiProvider
│   │   ├── AiProviderExceptionFactory
│   │   ├── AiProviderProperties
│   │   ├── AiProviderRetryExecutor
│   │   ├── AiProviderSupport
│   │   ├── AiResponseMetadataService
│   │   ├── AiRestClientFactory
│   │   ├── AiRetryProperties
│   │   ├──ProviderPropertyValidator 
│   │   │
│   │   ├── anthropic/
│   │   │   ├── AnthropicProperties
│   │   │   └── AnthropicProvider
│   │   │
│   │   ├── mock/
│   │   │   └── MockAiProvider
│   │   │
│   │   └── openai/
│   │       ├── OpenAiProperties
│   │       └── OpenAiProvider
│   │
│   └── web/
│       └── AiExceptionHandler
│
├── audit/
│   ├── config/
│   │   ├── AuditRetentionConfiguration
│   │   └── AuditRetentionProperties
│   │
│   ├── controller/
│   │   └── AuditController
│   │
│   ├── dto/
│   │   ├── AuditEventFilter
│   │   └── AuditEventResponse
│   │
│   ├── entity/
│   │   └── AuditEventEntity
│   │
│   ├── listener/
│   │   └── RateLimitAuditListener
│   │
│   ├── repository/
│   │   └── AuditEventRepository
│   │
│   ├── service/
│   │   ├── AuditEventQueryService
│   │   ├── AuditEventService
│   │   ├── AuditRetentionBatchService
│   │   └── AuditRetentionService
│   │
│   └── AuditEventType
│
├── auth/
│   ├── controller/
│   │   ├── AuthController
│   │   └── CsrfController
│   │
│   ├── dto/
│   │   ├── CurrentUserResponse
│   │   └── LoginRequest
│   │
│   ├── entity/
│   │   └── RefreshTokenEntity
│   │
│   ├── repository/
│   │   └── RefreshTokenRepository
│   │
│   ├── security/
│   │   ├── CsrfCookieFilter
│   │   ├── CustomUserDetailsService
│   │   ├── SecurityConfig
│   │   ├── SpaCsrfTokenRequestHandler
│   │   └── UserStatusFilter
│   │
│   └── service/
│       ├── AuthCookieConfigurationValidator
│       ├── AuthCookieProperties
│       ├── AuthCookieService
│       ├── AuthEventService
│       ├── AuthService
│       ├── RefreshTokenCleanupJob
│       ├── RefreshTokenService
│       └── UserSessionRevocationService
│
├── chat/
│   ├── controller/
│   │   └── ChatController
│   │
│   ├── dto/
│   │   ├── ChatDetailsResponse
│   │   ├── ChatResponse
│   │   ├── CreateChatRequest
│   │   ├── MessageResponse
│   │   └── SendMessageRequest
│   │
│   ├── entity/
│   │   ├── ChatMessageEntity
│   │   ├── ChatMessageRole
│   │   ├── ChatMessageStatus
│   │   └── ChatSessionEntity
│   │
│   ├── repository/
│   │   ├── ChatMessageRepository
│   │   └── ChatSessionRepository
│   │
│   └── service/
│       ├── AiHistoryBuilder
│       ├── ChatLockProperties
│       ├── ChatLockService
│       ├── ChatMapper
│       ├── ChatPersistenceService
│       ├── ChatProcessingContext
│       ├── ChatProperties
│       └── ChatService
│
├── common/
│   ├── config/
│   │   ├── SchedulingConfiguration
│   │   └── TimeConfiguration
│   │
│   ├── exception/
│   │   ├── ApiErrorResponse
│   │   ├── ApiErrorResponseFactory
│   │   ├── BadRequestException
│   │   ├── ChatAvailabilityExceptionHandler
│   │   ├── ChatBusyException
│   │   ├── ChatLockUnavailableException
│   │   ├── ConflictException
│   │   ├── ExpiredRefreshTokenException
│   │   ├── ForbiddenOperationException
│   │   ├── GlobalExceptionHandler
│   │   ├── InvalidRefreshTokenException
│   │   ├── OptimisticLockExceptionHandler
│   │   ├── RateLimitExceededException
│   │   ├── RateLimitUnavailableException
│   │   ├── RefreshTokenReuseDetectedException
│   │   └── ResourceNotFoundException
│   │
│   ├── platform/
│   │   └── PlatformProperties
│   │
│   ├── security/
│   │   ├── AccessTokenSubject
│   │   ├── ClientIpProperties
│   │   ├── ClientIpResolver
│   │   ├── CorsProperties
│   │   ├── JsonAccessDeniedHandler
│   │   ├── JsonAuthenticationEntryPoint
│   │   ├── JsonSecurityErrorWriter
│   │   ├── JwtProperties
│   │   ├── JwtService
│   │   ├── RequestIdFilter
│   │   ├── RoleAuthorityMapper
│   │   ├── SafeAiJwtAuthenticationConverter
│   │   └── SafeAiUserPrincipal
│   │
│   └── web/
│       └── ApiFallbackController
│
├── organization/
│   ├── controller/
│   │   └── OrganizationController
│   │
│   ├── dto/
│   │   ├── CreateOrganizationRequest
│   │   ├── OrganizationResponse
│   │   ├── UpdateOrganizationEnabledRequest
│   │   └── UpdateOrganizationRequest
│   │
│   ├── entity
│   │   └── OrganizationEntity
│   │
│   ├── event/
│   │   └── OrganizationSecurityStateChangedEvent
│   │
│   ├── repository/
│   │   └── OrganizationRepository
│   │
│   └── service/
│       ├── OrganizationService
│       └── OrganizationStatusCacheInvalidationListener
│
├── ratelimit/
│   ├── AiMessageRateLimitProperties
│   ├── DualRateLimitResult
│   ├── LoginRateLimitProperties
│   ├── LoginRateLimitService
│   ├── RateLimitDecision
│   ├── RateLimitExceededEvent
│   ├── RateLimitKeyFactory
│   ├── RateLimitRedisKeyProperties
│   ├── RateLimitResult
│   ├── RedisFixedWindowRateLimiter
│   └── RedisRateLimitService
│
├── usage/
│   ├── dto/
│   │   ├── PagedResponse
│   │   ├── UsageDailySummaryResponse
│   │   ├── UsageDateFilter
│   │   ├── UsageDateModelFilter
│   │   ├── UsageModelSummaryResponse
│   │   ├── UsagePageRequest
│   │   ├── UsageSummaryResponse
│   │   └── UsageUserSummaryResponse
│   │
│   ├── repositor/y
│   │   ├── UsageDailySummaryProjection
│   │   └── UsageQueryRepository
│   │
│   └── service/
│       └── UsageQueryService
│
├── user/
│   ├── controller/
│   │   └── UserController
│   │
│   ├── dto/
│   │   ├── CreateUserRequest
│   │   ├── ResetUserPasswordRequest
│   │   ├── UpdateUserEnabledRequest
│   │   ├── UpdateUserRequest
│   │   ├── UpdateUserRolesRequest
│   │   └── UserResponse
│   │
│   ├── entity/
│   │   ├── RoleEntity
│   │   └── UserEntity
│   │
│   ├── event/
│   │   └── UserSecurityStateChangedEvent
│   │
│   ├── repository/
│   │   ├── RoleRepository
│   │   └── UserRepository
│   │
│   ├── service/
│   │   ├── UserSecurityStatus
│   │   ├── UserService
│   │   ├── UserStatusCacheInvalidationListener
│   │   ├── UserStatusCacheProperties
│   │   └── UserStatusCacheService
│   │
│   └── validation/
│        ├── PasswordPolicy
│        ├── PasswordValidator
│        └── ValidPassword
│
│ 
└── SafeaiBackendApplication
```

| Модуль | Назначение |
|---|---|
| `auth` | login, logout, refresh rotation, текущий пользователь, cookies, auth audit |
| `common.security` | JWT, Spring Security, principal, CORS, requestId, JSON 401/403 |
| `common.exception` | единый формат API ошибок |
| `common.platform` | platform organization settings |
| `ratelimit` | Redis-backed login/AI rate limiting |
| `organization` | организации, platform-level управление, org security events |
| `user` | пользователи, роли, update profile, enable/disable, reset password, password policy |
| `chat` | chat sessions/messages, ownership, message processing |
| `ai` | provider abstraction, mock/OpenAI/Anthropic, pricing |
| `audit` | запись и чтение audit events |
| `usage` | usage analytics и aggregation queries |
| `admin` | admin API entry points, например usage endpoints |

---

---

```text
safeai-desk/
├── backend/
│   ├── .mvn/
│   ├── src/
│   │   ├── main/
│   │   └── test/
│   │       ├── java/
│   │       │   └── ru/
│   │       │       └── safeai/
│   │       │           └── gateway/
│   │       │               └── admin/
│   │       │               │   └── controller/
│   │       │               │      └── AdminUsageControllerSecurityTest
│   │       │               │
│   │       │               ├── ai/
│   │       │               │   ├── AiChatResponseTest
│   │       │               │   ├── AiExceptionHandlerTest
│   │       │               │   ├── AiMessageAndRequestTest
│   │       │               │   ├── AiProviderPropertiesTest
│   │       │               │   ├── AiProviderRetryExecutorTest
│   │       │               │   ├── AiProviderSupportTest
│   │       │               │   ├── AiRetryPropertiesTest
│   │       │               │   ├── AnthropicPropertiesTest
│   │       │               │   ├── MockAiProviderTest
│   │       │               │   ├── ModelPricingPropertiesTest
│   │       │               │   ├── ModelPricingServiceTest
│   │       │               │   └── OpenAiPropertiesTest
│   │       │               │
│   │       │               ├── audit/
│   │       │               │   ├── controller/
│   │       │               │   │  └── AuditControllerSecurityTest 
│   │       │               │   │
│   │       │               │   └── service/
│   │       │               │      ├── AuditEventQueryServiceTest 
│   │       │               │      ├── AuditEventServiceTest
│   │       │               │      └── AuditRetentionBatchServiceTest
│   │       │               │
│   │       │               ├── auth/
│   │       │               │   ├── controller/
│   │       │               │   │  └── AuthControllerSecurityTest
│   │       │               │   │
│   │       │               │   ├── security/
│   │       │               │   │  ├── CustomUserDetailsServiceTest 
│   │       │               │   │  └── UserStatusFilterTest
│   │       │               │   │
│   │       │               │   └── service/
│   │       │               │      ├── AuthCookiePropertiesTest 
│   │       │               │      ├── AuthCookieServiceTest 
│   │       │               │      ├── AuthServiceTest 
│   │       │               │      └── RefreshTokenServiceTest
│   │       │               │
│   │       │               ├── chat/
│   │       │               │   ├── controller/
│   │       │               │   │  └── ChatControllerSecurityTest
│   │       │               │   │
│   │       │               │   └── service/
│   │       │               │      ├── AiHistoryBuilderTest
│   │       │               │      ├── ChatLockServiceTest
│   │       │               │      ├── ChatPersistenceServiceTest  
│   │       │               │      └── ChatServiceTest
│   │       │               │
│   │       │               ├── common/
│   │       │               │   ├── exeption/
│   │       │               │   │  ├── ChatAvailabilityExceptionHandlerTest
│   │       │               │   │  └── GlobalExceptionHandlerTest
│   │       │               │   │
│   │       │               │   └── security/
│   │       │               │      ├── ClientIpPropertiesTest
│   │       │               │      ├── ClientIpResolverTest
│   │       │               │      ├── CorsPropertiesTest
│   │       │               │      ├── JwtPropertiesTest
│   │       │               │      ├── JwtServiceTest
│   │       │               │      ├── RequestIdFilterTest
│   │       │               │      ├── RoleAuthorityMapperTest  
│   │       │               │      ├── SafeAiJwtAuthenticationConverterTest
│   │       │               │      └── SafeAiUserPrincipalTest
│   │       │               │
│   │       │               ├── organization/
│   │       │               │   ├── controller/
│   │       │               │   │  └── OrganizationControllerSecurityTest
│   │       │               │   └── service/
│   │       │               │      └── OrganizationServiceTest
│   │       │               │
│   │       │               ├── ratelimit/
│   │       │               │   ├── LoginRateLimitServiceTest
│   │       │               │   ├── RateLimitPropertiesTest
│   │       │               │   └── RedisRateLimitServiceTest
│   │       │               │
│   │       │               ├── usage/
│   │       │               │   ├── repository/
│   │       │               │   │  └── UsageQueryRepositoryTest
│   │       │               │   └── service/
│   │       │               │      └── UsageQueryServiceTest
│   │       │               │
│   │       │               ├── user/
│   │       │               │   ├── controller/
│   │       │               │   │  └── UserControllerSecurityTest
│   │       │               │   └── service/
│   │       │               │   │   ├── UserServiceSecurityTest
│   │       │               │   │   ├── UserServiceTest  
│   │       │               │   │   ├── UserStatusCachePropertiesTest  
│   │       │               │   │   └── UserStatusCacheServiceTest
│   │       │               │   │
│   │       │               │   └── valigation/
│   │       │               │      └── PasswordValidatorTest
│   │       │               │
│   │       │               ├── PasswordHashGenerator.java
│   │       │               └── SafeaiBackendApplicationTests.java
│   │       │
│   │       └── resources/
│   │           └── application-test.yml


```
---

## Frontend-модули

```text
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
│   │   │   ├── Modal.tsx
│   │   │   ├── StateBlock.tsx
│   │   │   └── admin/
│   │   │          └── audit/
│   │   │             ├── AuditActor.tsx
│   │   │             ├── AuditDetailsModal.tsx
│   │   │             ├── AuditFilters.tsx
│   │   │             ├── AuditPagination.tsx
│   │   │             ├── AuditTable.tsx
│   │   │             └── types.ts
│   │   │
│   │   ├── hooks/
│   │   │   └── admin/
│   │   │          ├── useAuditDirectories.ts
│   │   │          └── useAuditEvents.ts
│   │   │   
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
│   │   │   ├── date.ts
│   │   │   ├── format.ts
│   │   │   ├── organizations.ts
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
```

| Файл | Назначение |
|---|---|
| `api/http.ts` | общий `apiRequest`, cookies, CSRF, refresh retry, `ApiError`, `X-Request-Id` |
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
| `Modal.tsx` | общий modal с Escape/backdrop/focus handling |
| `ConfirmDialog.tsx` | confirmation dialog |
| `ErrorBoundary.tsx` | fallback для frontend runtime errors |
| `StateBlock.tsx` | loading/error/empty states |

---

## User management

Реализованные операции:

```http
POST  /api/users
GET   /api/users
GET   /api/users/{id}
PATCH /api/users/{id}
PATCH /api/users/{id}/enabled
PATCH /api/users/{id}/roles
POST  /api/users/{id}/reset-password
```

### Создание пользователя

```text
SUPER_ADMIN -> может создавать USER/ADMIN в обычных организациях
ADMIN       -> может создавать только USER в своей организации
```

`SUPER_ADMIN` выбирает организацию явно. Пользователи не создаются в `SafeAI Platform` organization через обычный user-management endpoint.

### Редактирование пользователя

`PATCH /api/users/{id}` позволяет изменить:

- `email`;
- `fullName`.

При смене email:

- увеличивается `tokenVersion`;
- отзываются refresh-сессии пользователя;
- публикуется event инвалидации security cache;
- пишется audit event `USER_UPDATED`.

При смене только `fullName` сессии не отзываются.

### Защитные правила

- нельзя отключить самого себя;
- нельзя редактировать самого себя через user-management;
- нельзя управлять `SUPER_ADMIN` через обычный user-management;
- `ADMIN` не может управлять другим `ADMIN`;
- role/password/enabled/email changes отзывают refresh-сессии;
- password policy ограничивает слабые пароли.

---

## Organization management

Реализованные операции:

```http
POST  /api/organizations
GET   /api/organizations
GET   /api/organizations/{id}
GET   /api/organizations/me
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

## Chat flow

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

Внешний AI request не выполняется внутри длинной DB transaction.

Frontend после failed AI send перечитывает чат, чтобы показать сохраненное `FAILED` assistant message.

---

## AI providers

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

Поддерживает request scaffold для OpenAI-compatible flow:

- request payload;
- `store=false` по умолчанию;
- parsing output text;
- usage tokens;
- timeout/rate-limit/unavailable mapping.

### Anthropic provider

Поддерживает Messages API scaffold:

- system prompt отдельно;
- normalized messages;
- content blocks parsing;
- usage tokens;
- error mapping.

### Pricing

Usage cost — это estimate, основанный на локальном pricing config.

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

Этот расчет не является billing-grade источником истины. Для production billing нужна история цен и price versioning.

---

## Rate limiting

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

AI-запросы ограничиваются по пользователю и организации.

При превышении:

- запрос блокируется;
- событие публикуется;
- audit фиксирует rate limit event.

### Redis atomicity

Fixed-window limiter использует Redis Lua script для атомарного `INCR + TTL`.

Известный MVP-компромисс: если AI limit проверяется последовательно по user/org, возможна небольшая неточность счетчиков при отклонении на org-level. Это не позволяет обойти лимиты и не является security issue.

---

## Audit logging

Audit events пишутся в PostgreSQL.

Принципы:

```text
- audit не хранит полный prompt content;
- audit содержит organizationId;
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
USER_UPDATED
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

При добавлении нового `AuditEventType` нужно добавить Flyway migration с insert в `audit_event_types`. Иначе audit event не сохранится из-за FK.

---

## Usage analytics

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

Usage endpoints:

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

Date range rules:

```text
dateFrom -> inclusive
dateTo   -> exclusive
omitted  -> default last 30 days
max      -> 366 days
UTC      -> timestamps interpreted as UTC instants
```

Current implementation may aggregate from `chat_messages` as source of truth. Rollup tables already exist for future optimization.

---

## Frontend functionality

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
- выбор организации при создании пользователя `SUPER_ADMIN`;
- просмотр details;
- редактирование email/fullName;
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
- protection для `SafeAI Platform`;
- auto-dismiss success message.

### Audit

- фильтр по event type;
- фильтр по user email;
- фильтр по date range;
- фильтр по organizationId для `SUPER_ADMIN`;
- pagination;
- отображение JSON details.

### Usage

- tabs: summary/users/models/daily;
- фильтр по date range;
- фильтр по model для summary;
- cost display;
- daily UTC note.

---

## API overview

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
PATCH /api/users/{id}
PATCH /api/users/{id}/enabled
PATCH /api/users/{id}/roles
POST  /api/users/{id}/reset-password
```

### Organizations

```http
POST  /api/organizations
GET   /api/organizations
GET   /api/organizations/{id}
GET   /api/organizations/me
PATCH /api/organizations/{id}
PATCH /api/organizations/{id}/enabled
```

### Admin audit

```http
GET /api/admin/audit-events
GET /api/admin/audit-events/users/{userId}
```

### Admin usage

```http
GET /api/admin/usage/summary
GET /api/admin/usage/users
GET /api/admin/usage/models
GET /api/admin/usage/daily
GET /api/admin/usage/by-user/{userId}
GET /api/admin/usage/by-organization/{organizationId}
```

---

## Error response format

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

## Request ID

Backend поддерживает request correlation через `X-Request-Id`.

Поведение:

```text
если клиент передал валидный X-Request-Id -> использовать его
иначе -> сгенерировать UUID
```

Request ID:

- добавляется в response header;
- добавляется в MDC logging context;
- включается в API error response;
- помогает связывать frontend error и backend logs.

---

## Database и Flyway

Backend использует Flyway.

```text
src/main/resources/db/migration       -> обязательные production migrations
src/main/resources/db/local-migration -> local-only seed/demo data
```

Принципы:

```text
- уже примененные миграции не редактируются;
- изменения схемы добавляются новыми versioned migrations;
- Hibernate ddl-auto должен валидировать схему, а не создавать ее;
- критичные constraints должны жить в PostgreSQL;
- local seed не должен запускаться в production.
```

Текущие migrations:

```text
V1  init schema
V2  seed reference data
V3  denormalize chat organization
V4  schema hardening
V5  audit event types
V6  updated_at triggers
V7  usage quotas and rollups
V8  usage chat message indexes
V9  audit event indexes
V10 schema hardening: timestamps, audit details, rollup constraints
V11 add USER_UPDATED audit event type
```

Local-only seed:

```text
V1000__seed_local_demo_data.sql
```

Важно: в `V11` используется колонка `name`, потому что `audit_event_types` создана как:

```sql
create table audit_event_types (
    name varchar(100) primary key,
    description varchar(255)
);
```

Правильный insert:

```sql
insert into audit_event_types (name, description)
values ('USER_UPDATED', 'User profile data was updated')
on conflict (name) do nothing;
```

---

## Local development

### Требования

```text
Java 21
Maven Wrapper
Node.js
npm
Docker
Docker Compose
```

### Запуск инфраструктуры

Если используется local compose:

```bat
cd infra
docker compose -f docker-compose.local.yml up -d postgres redis
```

Если используется общий compose:

```bat
docker compose -f infra/docker-compose.yml up -d
```

### Запуск backend

```bat
cd backend

set SPRING_PROFILES_ACTIVE=local
set SAFEAI_JWT_SECRET=safeai-local-development-secret-key-change-this-value-please-123456789
set REDIS_PASSWORD=safeai_redis_password

mvnw.cmd spring-boot:run
```

Альтернативно:

```bat
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
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

## Local demo account

Local seed создает платформенного пользователя:

```text
email:    superadmin@test.com
role:     SUPER_ADMIN
org:      SafeAI Platform
```

Пароль зависит от bcrypt hash в `V1000__seed_local_demo_data.sql`.

В текущей local-сборке использовался:

```text
Admin_Dev_2026!Strong#91
```

Demo credentials нельзя использовать в production.

---

## Проверка проекта

### Backend tests

```bat
cd backend
mvnw.cmd test
```

### Backend run

```bat
cd backend
mvnw.cmd spring-boot:run
```

### Frontend build

```bat
cd frontend
npm run build
```

### Docker services

```bat
docker ps
```

### Flyway history

```bat
docker exec -it safeai-postgres psql -U safeai -d safeai -c "select installed_rank, version, description, success from flyway_schema_history order by installed_rank;"
```

### Local SUPER_ADMIN

```sql
select
    u.email,
    r.name as role
from users u
join user_roles ur on ur.user_id = u.id
join roles r on r.id = ur.role_id
where lower(u.email) = lower('superadmin@test.com');
```

### Reset local database

Для local-разработки, если миграции менялись до стабилизации:

```bat
cd infra
docker compose -f docker-compose.local.yml down -v
docker compose -f docker-compose.local.yml up -d postgres redis

cd ..\backend
mvnw.cmd clean
mvnw.cmd spring-boot:run
```

После того как миграции применены, не редактируй старые файлы. Новые изменения добавляются как `V12`, `V13`, `V14` и так далее.

---

## Configuration

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

## Production notes

### HTTPS model

Production compose предполагает TLS termination перед Nginx container.

```text
Client HTTPS
→ external load balancer / reverse proxy / Cloudflare
→ SafeAI Nginx container over internal HTTP port 80
→ backend:8080
```

Так как production cookies используют `Secure=true`, публичный entrypoint должен быть HTTPS.

Nginx/reverse proxy должен прокидывать:

```text
X-Forwarded-For
X-Real-IP
X-Forwarded-Proto=https
```

Если compose используется без внешнего TLS terminator, нужно добавить native Nginx TLS на 443, HTTP-to-HTTPS redirect и HSTS.

### Backend exposure

Backend не должен быть опубликован напрямую в Internet. Внешний трафик должен идти через Nginx/reverse proxy/load balancer.

### Prometheus

`/actuator/prometheus` предназначен только для доверенной сети. Он должен быть защищен на уровне сети или reverse proxy и не должен быть доступен из публичного Интернета.

### JWT и cookie lifetime

Время жизни access JWT и cookie с access token должно совпадать.

```properties
SAFEAI_JWT_EXPIRATION_MINUTES=15
SAFEAI_AUTH_ACCESS_TOKEN_MAX_AGE=15m
```

### Secrets

Не хранить production secrets в репозитории. Использовать environment variables, secret manager или CI/CD secrets.

---

## Security notes

- JWT не хранится в localStorage.
- Browser auth использует HttpOnly cookies.
- Unsafe methods защищены CSRF.
- Refresh tokens хранятся в БД только как hash.
- Refresh rotation защищает от token reuse.
- `tokenVersion` отзывает старые access JWT.
- Role/user/org/email changes отзывают refresh-сессии.
- ADMIN tenant-scoped.
- SUPER_ADMIN platform-scoped.
- Platform organization защищена от изменения.
- Audit details не должны содержать passwords/tokens/prompts/responses/API keys/cookies.
- CORS в production должен быть строго ограничен доверенными origins.

---

## Interview positioning

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
13. AI provider abstraction отделяет business logic от внешнего API.
14. Mock provider позволяет demo без внешних ключей.
15. External AI calls выполняются вне длинных DB transactions.
16. Flyway migrations фиксируют evolution схемы.
17. Request ID связывает frontend errors и backend logs.
```

---

## Roadmap

Возможное развитие проекта:

- session/device management UI;
- organization-level monthly budgets;
- model allowlist per organization;
- usage charts;
- daily/monthly rollup-based reporting;
- RAG knowledge base;
- document upload and indexing;
- policy engine для AI prompts;
- SSO/OIDC;
- Testcontainers integration tests;
- production deployment profile with Nginx/TLS;
- более точный billing-grade cost history;
- token budget/preflight до запроса к AI provider;
- provider request metadata (`providerRequestId`, `durationMs`);
- HMAC для Redis rate-limit keys;
- sliding window/token bucket/GCRA вместо fixed window при необходимости.

---

## Статус проекта

Текущий статус: **production-oriented MVP / portfolio-ready baseline**.

Проект уже демонстрирует ключевые enterprise-практики:

- безопасная browser-auth модель;
- multi-tenant RBAC;
- session revocation;
- auditability;
- usage visibility;
- rate limiting;
- provider abstraction;
- миграционная дисциплина через Flyway;
- separation of concerns между backend modules и frontend API clients.
