# SafeAI Desk

**SafeAI Desk** — production-oriented full-stack корпоративная AI-платформа / AI Gateway для безопасного использования 
внешних AI-моделей внутри организаций.

> **Актуальность:** август 2026.  
> Документ описывает фактически реализованную архитектуру текущих backend/frontend исходников и Flyway-миграций до `V42`.
> Будущее развитие вынесено в [`ROADMAP.md`](ROADMAP.md).

SafeAI Desk — не просто UI над LLM. Проект строит управляемый слой вокруг корпоративного AI:

- organization-based multi-tenancy;
- RBAC `SUPER_ADMIN / ADMIN / USER`;
- HttpOnly cookie JWT authentication;
- CSRF;
- строгую JWT validation и revocation epochs;
- refresh-token rotation/reuse detection;
- tenant-safe user/organization management;
- durable `ChatTurn` state machine с idempotency, lease и fencing;
- защиту от повторного provider call при неопределённом исходе;
- Redis rate limiting;
- transactional audit outbox;
- immutable actor/target audit snapshots;
- usage/pricing quality model без подмены неизвестных данных нулём;
- OpenAI/Anthropic/mock provider abstraction;
- tenant/ACL-aware Knowledge Bases и immutable document versions;
- durable ingestion с lease/fencing/retry и S3-compatible originals;
- PDF/DOCX/HTML/TXT/Markdown/CSV/XLSX/PPTX/JSON/XML extraction и OCR adapter;
- immutable chunks, pgvector + FTS hybrid retrieval и production embedding adapter;
- RAG context assembly, inline citation validation и knowledge-only fail-closed mode;
- retrieval-to-ChatTurn provenance и immutable Answer Passport;
- Knowledge health/reindex и versioned retrieval evaluation metrics;
- PostgreSQL/Flyway constraints как часть integrity model;
- React frontend с runtime validation API contracts, abortable requests и production error handling.

Текущий статус: **production-oriented pre-1.0 / portfolio-ready baseline**. Это серьёзная база для B2B-продукта, но 
не заявление о compliance certification, billing-grade accounting или формально доказанной HA/SLA.

---

## Содержание

1. [Что решает проект](#что-решает-проект)
2. [Архитектура](#архитектура)
3. [Стек](#стек)
4. [Роли](#роли)
5. [Multi-tenancy и integrity](#multi-tenancy-и-integrity)
6. [Security architecture](#security-architecture)
7. [Модуль common](#модуль-common)
8. [Backend-модули](#backend-модули)
9. [ChatTurn](#chatturn)
10. [AI providers](#ai-providers)
11. [Rate limiting](#rate-limiting)
12. [Audit](#audit)
13. [Usage](#usage)
14. [User management](#user-management)
15. [Organization management](#organization-management)
16. [Frontend](#frontend)
17. [API](#api)
18. [Database/Flyway](#databaseflyway)
19. [Local development](#local-development)
20. [Проверка](#проверка)
21. [Production notes](#production-notes)
22. [Текущие границы](#текущие-границы)
23. [Positioning](#positioning)
24. [Следующий этап](#следующий-этап)

---

# Что решает проект

Корпоративный AI требует отвечать не только на «что ответила модель», но и на:

```text
Кто сделал запрос?
Из какой организации?
Какие у него права?
Активен ли user и tenant?
Не устарела ли access-сессия?
Можно ли безопасно повторить AI operation?
Сколько было токенов и каково качество pricing data?
Кто изменил security state?
Что произошло при crash во время provider I/O?
```

SafeAI Desk объединяет:

```text
Identity
+ Tenant isolation
+ Session security
+ Durable chat operations
+ Rate limits / quotas
+ Provider abstraction
+ Usage / pricing quality
+ Audit trail
```

Пример:

```text
SafeAI Platform
└── superadmin@test.com       SUPER_ADMIN

Demo Company
├── admin@test.com            ADMIN
└── user@test.com             USER
```

---

# Архитектура

Проект — **модульный монолит**. Это сознательно сохраняет сильные transactional/tenant/security invariants без 
преждевременной микросервисной сложности.

```text
┌──────────────────────────────┐
│ React + TypeScript Frontend  │
└──────────────┬───────────────┘
               │ HTTPS / JSON
               │ Cookie auth + CSRF
               ▼
┌──────────────────────────────┐
│ Edge / Nginx                 │
└──────────────┬───────────────┘
               ▼
┌──────────────────────────────┐
│ Spring Boot Backend          │
│ auth / common / user / org   │
│ chat / ai / audit / usage    │
│ knowledge / RAG / retrieval  │
│ ratelimit / admin            │
└───────┬──────────────┬───────┘
        │              │
        ▼              ▼
┌─────────────┐   ┌───────────┐
│ PostgreSQL  │   │ Redis     │
│ + Flyway    │   │ limits /  │
│ source of   │   │ locks /   │
│ truth       │   │ optional  │
│             │   │ cache     │
└──────┬──────┘   └───────────┘
       │
       │ external call outside long DB transaction
       ▼
┌──────────────────────────────┐
│ Mock / OpenAI / Anthropic    │
└──────────────────────────────┘
```

**PostgreSQL — source of truth.** Redis — coordination/rate-limit/cache layer; security correctness не должна зависеть 
только от наличия Redis cache.

---

# Стек

## Backend

- Java 21;
- Spring Boot 4.x;
- Spring Web MVC;
- Spring Security;
- Spring Data JPA / Hibernate;
- PostgreSQL;
- Flyway;
- Redis;
- Maven Wrapper;
- Bean Validation;
- Lombok;
- JUnit 5 / Mockito / Spring Test;
- Testcontainers;
- Docker / Docker Compose.

Точные версии определяет `backend/pom.xml`.

## Frontend

По текущему `package.json`:

- React 19;
- React DOM 19;
- React Router;
- TypeScript 6;
- Vite 8;
- Vitest;
- Testing Library;
- ESLint + TypeScript ESLint;
- `eslint-plugin-jsx-a11y`;
- Recharts;
- Node.js 24;
- npm 11.

Frontend работает в strict TypeScript mode и имеет отдельные `typecheck`, `lint`, `test`, `coverage`, `build`, `ci`.

---

# Роли

У пользователя должна быть **ровно одна системная роль**:

```text
SUPER_ADMIN
ADMIN
USER
```

Это поддерживается backend policy, frontend runtime parser и БД.

## SUPER_ADMIN

Platform/control-plane administrator:

- создаёт/просматривает tenant organizations;
- управляет обычными `ADMIN`/`USER`;
- видит global audit;
- видит global usage;
- фильтрует global reports по tenant.

Через ordinary user-management flow не создаёт и не изменяет `SUPER_ADMIN`.

**В текущем frontend `SUPER_ADMIN` является control-plane ролью и не получает Chat route; Chat UI доступен `ADMIN`/`USER`.**

## ADMIN

Tenant administrator:

- работает с chat;
- управляет `USER` своей организации;
- видит tenant audit;
- видит tenant usage.

Не может управлять другим `ADMIN`, создавать `SUPER_ADMIN` или видеть чужой tenant.

## USER

- работает со своими чатами;
- не имеет administrative routes.

| Возможность | SUPER_ADMIN | ADMIN | USER |
|---|---:|---:|---:|
| Tenant organizations | Да | Нет | Нет |
| Создать ADMIN | Да | Нет | Нет |
| Создать USER | Да | Да, своя org | Нет |
| Управлять USER | Да | Да, своя org | Нет |
| Global audit/usage | Да | Нет | Нет |
| Tenant audit/usage | Да | Да | Нет |
| Текущий Chat UI | Нет | Да | Да |

Frontend guards — UX. Security boundary — backend.

---

# Multi-tenancy и integrity

Tenant key — `organization_id`.

```text
SUPER_ADMIN -> global/platform scope
ADMIN       -> organization scope
USER        -> own-resource scope
```

Tenant isolation применяется к users, organizations, sessions/messages/turns, quota reservations, audit, usage, rate 
limiting и security-state lookup.

Схема БД дополнительно содержит composite tenant-safe relationships, например:

```text
chat_session(user_id, organization_id)
    -> user(id, organization_id)

chat_message(session_id, organization_id)
    -> chat_session(id, organization_id)

chat_turn(session_id, organization_id, user_id)
    -> chat_session(id, organization_id, user_id)
```

Цель — не допустить cross-tenant связи даже при application bug.

---

# Security architecture

## Browser auth

```text
access token  -> HttpOnly cookie
refresh token -> HttpOnly cookie
XSRF-TOKEN    -> readable cookie
```

JWT не хранится в `localStorage`.

Unsafe methods:

```text
POST / PUT / PATCH / DELETE
```

отправляют:

```text
X-XSRF-TOKEN: <XSRF-TOKEN>
```

После login CSRF token ротируется.

## JWT

Access token включает/использует:

```text
sub
email
userId
organizationId
roles
tokenVersion
organizationAuthVersion
jti
iat
exp
iss
aud
```

Валидация включает issuer/audience/type/identity, а не только подпись.

## User security epoch

`users.token_version` меняется при user-specific security mutation:

- password reset;
- role change;
- disable;
- security-significant identity change.

## Organization security epoch

`organizations.auth_version` — отдельный organization-level epoch.

Организацию не нужно инвалидировать массовым increment `tokenVersion` каждого пользователя. Access/refresh state 
проверяет также `organizationAuthVersion`.

`UserStatusFilter` сопоставляет:

```text
user.enabled
organization.enabled
token.organizationId
token.tokenVersion
token.organizationAuthVersion
```

Failure security-state lookup должен fail closed.

## Refresh rotation

Refresh token хранится как hash.

```text
cookie
→ hash
→ locked token
→ validate
→ revoke predecessor
→ create successor in same family
```

DB integrity запрещает self-replacement, cross-user/family replacement, duplicate successor и cycle.

Reuse:

```text
detect reuse
→ terminate family
→ SECURITY_REFRESH_REUSE_DETECTED
→ clear cookies
→ 401
```

## Пароли

BCrypt limit — **72 UTF-8 bytes**, не 72 символа.

Текущая модель:

- `{bcrypt}` через `DelegatingPasswordEncoder`;
- BCrypt strength 12;
- legacy unprefixed BCrypt fallback для migration period;
- login проверяет техническую допустимость старого password;
- create/reset применяют current complexity policy.

New-password policy:

```text
>= 12 Unicode code points
<= 72 UTF-8 bytes
ASCII lowercase
ASCII uppercase
digit
ASCII special
no control characters
```

## Request ID

Старая формулировка README «принять клиентский X-Request-Id как server ID» больше неверна.

**Backend генерирует собственный canonical UUID для каждого request.**

```text
incoming X-Request-Id
    └─ validated optional client metadata

server-generated UUID
    ├─ request attribute
    ├─ MDC
    ├─ response X-Request-Id
    └─ ApiErrorResponse.requestId
```

Client ID не становится server correlation ID.

## Client IP

Forwarded headers доверяются только при direct peer из `trustedProxyCidrs`.

- empty proxy list = trust nobody;
- max XFF hops bounded;
- malformed chain не используется частично;
- справа выбирается nearest untrusted address;
- hostname не резолвится как client IP;
- production запрещает `0.0.0.0/0` / `::/0` в trusted proxies.

## CORS

Origins нормализуются. Запрещены wildcard, unsupported schemes, userinfo, query, fragment, path и invalid port. 
Production validator требует HTTPS origins.

## Errors

MVC:

```text
GlobalExceptionHandler
→ ApiErrorResponseFactory
→ ApiErrorResponse
```

Security:

```text
RestAuthenticationEntryPoint / RestAccessDeniedHandler
→ ApiErrorResponseWriter
→ ApiErrorResponseFactory
```

`error` — stable machine-readable code, `message` — human text.

---

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

backend/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── ru/
│   │   │       └── safeai/
│   │   │           └── gateway/
│   │   │               ├── admin/
│   │   │               │   └── controller/
│   │   │               │       └── AdminUsageController
│   │   │               │
│   │   │               ├── ai/
│   │   │               │   ├── config/
│   │   │               │   │   ├── 
│   │   │               │   │   ├── AiConfiguration
│   │   │               │   │   ├── AiProductionConfigurationValidator
│   │   │               │   │   ├── AnthropicProviderConfiguration
│   │   │               │   │   └── OpenAiProviderConfiguration
│   │   │               │   ├── dto/
│   │   │               │   │   ├── AiChatRequest
│   │   │               │   │   ├── AiChatResponse
│   │   │               │   │   ├── AiMessage
│   │   │               │   │   └── AiMessageRole
│   │   │               │   ├── exception/
│   │   │               │   │   ├── AiContextLimitException
│   │   │               │   │   ├── AiProviderBillingException
│   │   │               │   │   ├── AiProviderErrorType
│   │   │               │   │   ├── AiProviderException
│   │   │               │   │   ├── AiProviderOverloadedException
│   │   │               │   │   ├── AiProviderQuotaExceededException
│   │   │               │   │   ├── AiProviderRateLimitedException
│   │   │               │   │   ├── AiProviderResponseTooLargeException
│   │   │               │   │   ├── AiProviderTimeoutException
│   │   │               │   │   └── AiProviderUnavailableException
│   │   │               │   ├── metadata/
│   │   │               │   │   ├── AiResponseStatus
│   │   │               │   │   ├── PricingStatus
│   │   │               │   │   └── UsageStatus
│   │   │               │   ├── pricing/
│   │   │               │   │   ├── ModelPricingProperties
│   │   │               │   │   ├── ModelPricingService
│   │   │               │   │   └── PricingResult
│   │   │               │   ├── provider/
│   │   │               │   │   ├── anthropic/
│   │   │               │   │   │   ├── AnthropicProperties
│   │   │               │   │   │   └── AnthropicProvider
│   │   │               │   │   ├── mock/
│   │   │               │   │   │   └── MockAiProvider
│   │   │               │   │   ├── openai/
│   │   │               │   │   │   ├── OpenAiProperties
│   │   │               │   │   │   └── OpenAiProvider
│   │   │               │   │   ├── AiContextWindowProperties
│   │   │               │   │   ├── AiContextWindowService
│   │   │               │   │   ├── AiJsonNodeSupport
│   │   │               │   │   ├── AiProvider
│   │   │               │   │   ├── AiProviderAttemptContext
│   │   │               │   │   ├── AiProviderExceptionFactory
│   │   │               │   │   ├── AiProviderProperties
│   │   │               │   │   ├── AiProviderRetryExecutor
│   │   │               │   │   ├── AiProviderSupport
│   │   │               │   │   ├── AiResponseMetadataService
│   │   │               │   │   ├── AiResponseTooLargeIOException
│   │   │               │   │   ├── AiRestClientFactory
│   │   │               │   │   ├── AiRetryProperties
│   │   │               │   │   └── ProviderPropertyValidator
│   │   │               │   └── web/
│   │   │               │       └── AiExceptionHandler
│   │   │               │
│   │   │               ├── audit/
│   │   │               │   ├── config/
│   │   │               │   │   ├── AuditDetailsProperties
│   │   │               │   │   ├── AuditOutboxConfiguration
│   │   │               │   │   ├── AuditOutboxProperties
│   │   │               │   │   ├── AuditRetentionConfiguration
│   │   │               │   │   └── AuditRetentionProperties
│   │   │               │   ├── controller/
│   │   │               │   │   ├── AuditController
│   │   │               │   │   └── AuditDirectoryController
│   │   │               │   ├── details/
│   │   │               │   │   ├── AiResponseAuditDetails
│   │   │               │   │   ├── AuditDetails
│   │   │               │   │   ├── ChatTurnAuditDetails
│   │   │               │   │   ├── RateLimitAuditDetails
│   │   │               │   │   └── SecurityRefreshReuseAuditDetails
│   │   │               │   ├── dto/
│   │   │               │   │   ├── AuditActorDirectoryResponse
│   │   │               │   │   ├── AuditEventCursorResponse
│   │   │               │   │   ├── AuditEventFilter
│   │   │               │   │   ├── AuditEventPageResponse
│   │   │               │   │   ├── AuditEventResponse
│   │   │               │   │   └── AuditTargetOrganizationDirectoryResponse
│   │   │               │   ├── entity/
│   │   │               │   │   ├── AuditEventEntity
│   │   │               │   │   └── AuditOutboxEntity
│   │   │               │   ├── listener/
│   │   │               │   │   └── RateLimitAuditListener
│   │   │               │   ├── model/
│   │   │               │   │   └── AuditActor
│   │   │               │   ├── repository/
│   │   │               │   │   ├── AuditDirectoryQueryRepository
│   │   │               │   │   ├── AuditEventCriteria
│   │   │               │   │   ├── AuditEventCursorRepository
│   │   │               │   │   ├── AuditEventCursorRepositoryImpl
│   │   │               │   │   ├── AuditEventRepository
│   │   │               │   │   └── AuditOutboxRepository
│   │   │               │   ├── service/
│   │   │               │   │   ├── AuditCommand
│   │   │               │   │   ├── AuditCommandFactory
│   │   │               │   │   ├── AuditCursorCodec
│   │   │               │   │   ├── AuditDetailsSanitizer
│   │   │               │   │   ├── AuditDirectoryService
│   │   │               │   │   ├── AuditEventCursorService
│   │   │               │   │   ├── AuditEventQueryPolicy
│   │   │               │   │   ├── AuditEventQueryService
│   │   │               │   │   ├── AuditEventService
│   │   │               │   │   ├── AuditOutboxFailureService
│   │   │               │   │   ├── AuditOutboxProcessor
│   │   │               │   │   ├── AuditOutboxScheduler
│   │   │               │   │   ├── AuditOutboxWriter
│   │   │               │   │   ├── AuditRetentionBatchService
│   │   │               │   │   ├── AuditRetentionLockService
│   │   │               │   │   ├── AuditRetentionService
│   │   │               │   │   └── BestEffortStandaloneAuditService
│   │   │               │   │ 
│   │   │               │   ├── spi/
│   │   │               │   │   └── AuditTargetOrganizationSnapshotProvider
│   │   │               │   │ 
│   │   │               │   └── AuditEventType
│   │   │               │
│   │   │               ├── auth/
│   │   │               │   ├── config/
│   │   │               │   │   └── RefreshTokenCleanupConfiguration
│   │   │               │   ├── controller/
│   │   │               │   │   ├── AuthController
│   │   │               │   │   └── CsrfController
│   │   │               │   ├── dto/
│   │   │               │   │   ├── CsrfTokenResponse
│   │   │               │   │   ├── CurrentUserResponse
│   │   │               │   │   └── LoginRequest
│   │   │               │   ├── entity/
│   │   │               │   │   ├── RefreshTokenEntity
│   │   │               │   │   └── RefreshTokenRevocationReason
│   │   │               │   ├── repository/
│   │   │               │   │   └── RefreshTokenRepository
│   │   │               │   ├── security/
│   │   │               │   │   ├── AccessCookieAuthenticationFilter
│   │   │               │   │   ├── CsrfCookieFilter
│   │   │               │   │   ├── CustomUserDetailsService
│   │   │               │   │   ├── package-info.java
│   │   │               │   │   ├── SecurityConfig
│   │   │               │   │   ├── SpaCsrfTokenRequestHandler
│   │   │               │   │   └── UserStatusFilter
│   │   │               │   ├── service/
│   │   │               │   │   ├── AuthCookieConfigurationValidator
│   │   │               │   │   ├── AuthCookieProperties
│   │   │               │   │   ├── AuthCookieService
│   │   │               │   │   ├── AuthEventService
│   │   │               │   │   ├── AuthService
│   │   │               │   │   ├── LoginSessionResult
│   │   │               │   │   ├── LoginSessionTransactionService
│   │   │               │   │   ├── LogoutAuditSubject
│   │   │               │   │   ├── RefreshTokenCleanupBatchService
│   │   │               │   │   ├── RefreshTokenCleanupJob
│   │   │               │   │   ├── RefreshTokenCleanupProperties
│   │   │               │   │   ├── RefreshTokenService
│   │   │               │   │   └── UserSessionRevocationService
│   │   │               │   └── validation/
│   │   │               │       ├── Utf8ByteLength
│   │   │               │       └── Utf8ByteLengthValidator
│   │   │               │
│   │   │               ├── chat/
│   │   │               │   ├── config/
│   │   │               │   │   ├── ChatConfiguration
│   │   │               │   │   ├── ChatLockProperties
│   │   │               │   │   ├── ChatProperties
│   │   │               │   │   ├── ChatQuotaProperties
│   │   │               │   │   └── ChatRecoveryProperties
│   │   │               │   ├── controller/
│   │   │               │   │   └── ChatController
│   │   │               │   ├── dto/
│   │   │               │   │   ├── ChatCapabilitiesResponse
│   │   │               │   │   ├── ChatDetailsResponse
│   │   │               │   │   ├── ChatErrorResponse
│   │   │               │   │   ├── ChatPageRequest
│   │   │               │   │   ├── ChatPageResponse
│   │   │               │   │   ├── ChatResponse
│   │   │               │   │   ├── ChatTurnStatusResponse
│   │   │               │   │   ├── CreateChatRequest
│   │   │               │   │   ├── MessagePageRequest
│   │   │               │   │   ├── MessageResponse
│   │   │               │   │   ├── SendMessageRequest
│   │   │               │   │   └── SendMessageResponse
│   │   │               │   ├── entity/
│   │   │               │   │   ├── ChatMessageEntity
│   │   │               │   │   ├── ChatMessageRole
│   │   │               │   │   ├── ChatMessageStatus
│   │   │               │   │   ├── ChatQuotaReservationState
│   │   │               │   │   ├── ChatSessionEntity
│   │   │               │   │   ├── ChatTurnEntity
│   │   │               │   │   └── ChatTurnState
│   │   │               │   ├── exception/
│   │   │               │   │   ├── AiOutcomeAmbiguousException
│   │   │               │   │   ├── ChatAccessRevokedException
│   │   │               │   │   ├── ChatApiException
│   │   │               │   │   ├── ChatApiExceptionHandler
│   │   │               │   │   ├── ChatLeaseUnavailableException
│   │   │               │   │   ├── ChatQuotaExceededException
│   │   │               │   │   ├── ChatStaleProcessorException
│   │   │               │   │   ├── ChatTurnFailedException
│   │   │               │   │   ├── ChatTurnInProgressException
│   │   │               │   │   └── IdempotencyKeyReusedException
│   │   │               │   ├── observability/
│   │   │               │   │   └── ChatMetrics
│   │   │               │   ├── quota/
│   │   │               │   │   ├── ChatQuotaConsumption
│   │   │               │   │   └── ChatQuotaPolicy
│   │   │               │   ├── repository/
│   │   │               │   │   ├── ChatHistoryRepository
│   │   │               │   │   ├── ChatHistoryTurn
│   │   │               │   │   ├── ChatMessageRepository
│   │   │               │   │   ├── ChatQuotaRepository
│   │   │               │   │   ├── ChatSecurityStateRepository
│   │   │               │   │   ├── ChatSessionRepository
│   │   │               │   │   ├── ChatTurnMutexRepository
│   │   │               │   │   ├── ChatTurnRecoveryRepository
│   │   │               │   │   ├── ChatTurnRepository
│   │   │               │   │   └── RecoveredChatTurn
│   │   │               │   └── service/
│   │   │               │       ├── AiHistoryBuilder
│   │   │               │       ├── ChatContentNormalizer
│   │   │               │       ├── ChatLockService
│   │   │               │       ├── ChatMapper
│   │   │               │       ├── ChatProcessingContext
│   │   │               │       ├── ChatQuotaService
│   │   │               │       ├── ChatSecurityStateService
│   │   │               │       ├── ChatService
│   │   │               │       ├── ChatTurnFinalizationService
│   │   │               │       ├── ChatTurnLeaseService
│   │   │               │       ├── ChatTurnRecoveryScheduler
│   │   │               │       ├── ChatTurnRecoveryService
│   │   │               │       └── ChatTurnReservationService
│   │   │               │
│   │   │               ├── common/
│   │   │               │   ├── config/
│   │   │               │   │   ├── SchedulingConfiguration
│   │   │               │   │   └── TimeConfiguration
│   │   │               │   ├── exception/
│   │   │               │   │   ├── ApiErrorCode
│   │   │               │   │   ├── ApiErrorResponse
│   │   │               │   │   ├── ApiErrorResponseFactory
│   │   │               │   │   ├── ApiErrorResponseWriter
│   │   │               │   │   ├── ApiException
│   │   │               │   │   ├── AuthServiceUnavailableException
│   │   │               │   │   ├── BadRequestException
│   │   │               │   │   ├── ChatBusyException
│   │   │               │   │   ├── ChatLockUnavailableException
│   │   │               │   │   ├── ConflictException
│   │   │               │   │   ├── ExpiredRefreshTokenException
│   │   │               │   │   ├── ForbiddenOperationException
│   │   │               │   │   ├── GlobalExceptionHandler
│   │   │               │   │   ├── InvalidRefreshTokenException
│   │   │               │   │   ├── OrganizationVersionConflictException
│   │   │               │   │   ├── RateLimitExceededException
│   │   │               │   │   ├── RateLimitUnavailableException
│   │   │               │   │   ├── README.md
│   │   │               │   │   ├── RefreshTokenReuseDetectedException
│   │   │               │   │   ├── ResourceNotFoundException
│   │   │               │   │   └── UserVersionConflictException
│   │   │               │   ├── persistence/
│   │   │               │   │   └── DatabaseConstraintClassifier
│   │   │               │   ├── platform/
│   │   │               │   │   ├── PlatformProperties
│   │   │               │   │   └── PlatformPropertiesConfiguration
│   │   │               │   ├── security/
│   │   │               │   │   ├── AccessTokenSubject
│   │   │               │   │   ├── ClientIpProperties
│   │   │               │   │   ├── ClientIpResolver
│   │   │               │   │   ├── CorsProperties
│   │   │               │   │   ├── JwtCodecConfiguration
│   │   │               │   │   ├── JwtProperties
│   │   │               │   │   ├── JwtService
│   │   │               │   │   ├── package-info.java
│   │   │               │   │   ├── PasswordEncodingConfiguration
│   │   │               │   │   ├── ProductionSecurityInvariantValidator
│   │   │               │   │   ├── RequestIdFilter
│   │   │               │   │   ├── RestAccessDeniedHandler
│   │   │               │   │   ├── RestAuthenticationEntryPoint
│   │   │               │   │   ├── RoleAuthorityMapper
│   │   │               │   │   ├── SafeAiJwtAuthenticationConverter
│   │   │               │   │   ├── SafeAiUserPrincipal
│   │   │               │   │   ├── SecurityIdentityValidator
│   │   │               │   │   ├── SecurityPropertiesConfiguration
│   │   │               │   │   └── SystemRole
│   │   │               │   └── web/
│   │   │               │       └── ApiFallbackController
│   │   │               │
│   │   │               ├── organization/
│   │   │               │   ├── audit/
│   │   │               │   │   └── OrganizationAuditSnapshotProvider
│   │   │               │   ├── controller/
│   │   │               │   │   └── OrganizationController
│   │   │               │   ├── dto/
│   │   │               │   │   ├── CreateOrganizationRequest
│   │   │               │   │   ├── DisableOrganizationRequest
│   │   │               │   │   ├── EnableOrganizationRequest
│   │   │               │   │   ├── OrganizationDirectoryResponse
│   │   │               │   │   ├── OrganizationDisableImpactResponse
│   │   │               │   │   ├── OrganizationPageResponse
│   │   │               │   │   ├── OrganizationResponse
│   │   │               │   │   ├── OrganizationType
│   │   │               │   │   ├── UpdateOrganizationEnabledRequest
│   │   │               │   │   └── UpdateOrganizationRequest
│   │   │               │   ├── entity/
│   │   │               │   │   ├── OrganizationImpactQueryRepository
│   │   │               │   │   └── OrganizationEntity
│   │   │               │   ├── event/
│   │   │               │   │   └── OrganizationSecurityStateChangedEvent
│   │   │               │   ├── repository/
│   │   │               │   │   └── OrganizationImpactQueryRepository
│   │   │               │   │   └── OrganizationRepository
│   │   │               │   └── service/
│   │   │               │       ├── OrganizationNameNormalizer
│   │   │               │       ├── OrganizationService
│   │   │               │       ├── OrganizationStatusCacheInvalidationListener
│   │   │               │       └── PlatformOrganizationInvariantVerifier
│   │   │               │
│   │   │               ├── ratelimit/
│   │   │               │   ├── AiMessageRateLimitProperties
│   │   │               │   ├── DualRateLimitResult
│   │   │               │   ├── LoginRateLimitProperties
│   │   │               │   ├── LoginRateLimitService
│   │   │               │   ├── RateLimitDecision
│   │   │               │   ├── RateLimitExceededEvent
│   │   │               │   ├── RateLimitKeyFactory
│   │   │               │   ├── RateLimitMetrics
│   │   │               │   ├── RateLimitRedisKeyProperties
│   │   │               │   ├── RedisFixedWindowRateLimiter
│   │   │               │   └── RedisRateLimitService
│   │   │               │
│   │   │               ├── usage/
│   │   │               │   ├── config/
│   │   │               │   │   ├── UsageConfiguration
│   │   │               │   │   ├── UsageJdbcClients
│   │   │               │   │   └── UsageProperties
│   │   │               │   ├── controller/
│   │   │               │   │   └── UsageController
│   │   │               │   ├── dto/
│   │   │               │   │   ├── PagedResponse
│   │   │               │   │   ├── UsageCostSummary
│   │   │               │   │   ├── UsageDailySummaryResponse
│   │   │               │   │   ├── UsageDataQualityResponse
│   │   │               │   │   ├── UsageDateFilter
│   │   │               │   │   ├── UsageDateModelFilter
│   │   │               │   │   ├── UsageModelSummaryResponse
│   │   │               │   │   ├── UsagePageRequest
│   │   │               │   │   ├── UsageProblemModelResponse
│   │   │               │   │   ├── UsageResponseSummary
│   │   │               │   │   ├── UsageSummaryInvariants
│   │   │               │   │   ├── UsageSummaryResponse
│   │   │               │   │   ├── UsageTokenSummary
│   │   │               │   │   └── UsageUserSummaryResponse
│   │   │               │   ├── repository/
│   │   │               │   │   ├── JdbcUsageQueryRepository
│   │   │               │   │   ├── UsageDailySummaryProjection
│   │   │               │   │   ├── UsageInstantRange
│   │   │               │   │   ├── UsageQueryCriteria
│   │   │               │   │   ├── UsageQueryPlan
│   │   │               │   │   ├── UsageQueryRepository
│   │   │               │   │   └── UsageRollupStateRepository
│   │   │               │   └── service/
│   │   │               │       ├── UsageQueryService
│   │   │               │       ├── UsageReportExecutor
│   │   │               │       ├── UsageRollupDayProcessor
│   │   │               │       └── UsageRollupScheduler
││   │   │               │
│   │   │               ├── user/
│   │   │               │   ├── controller/
│   │   │               │   │   └── UserController
│   │   │               │   ├── dto/
│   │   │               │   │   ├── CreateUserRequest
│   │   │               │   │   ├── PermanentDeleteUserRequest
│   │   │               │   │   ├── ResetUserPasswordRequest
│   │   │               │   │   ├── UpdateUserEnabledRequest
│   │   │               │   │   ├── UpdateUserRequest
│   │   │               │   │   ├── UpdateUserRolesRequest
│   │   │               │   │   ├── UserDetailsResponse
│   │   │               │   │   ├── UserResponse
│   │   │               │   │   └── UserStatisticsResponse
│   │   │               │   ├── entity/
│   │   │               │   │   ├── RoleEntity
│   │   │               │   │   └── UserEntity
│   │   │               │   ├── event/
│   │   │               │   │   └── UserSecurityStateChangedEvent
│   │   │               │   ├── mapper/
│   │   │               │   │   └── UserRoleMapper
│   │   │               │   ├── repository/
│   │   │               │   │   ├── RoleRepository
│   │   │               │   │   └── UserRepository
│   │   │               │   ├── service/
│   │   │               │   │   ├── UserManagementProperties
│   │   │               │   │   ├── UserSecurityStatus
│   │   │               │   │   ├── UserService
│   │   │               │   │   ├── UserStatusCacheInvalidationListener
│   │   │               │   │   ├── UserStatusCacheProperties
│   │   │               │   │   └── UserStatusCacheService
│   │   │               │   └── validation/
│   │   │               │       ├── BcryptUtf8Length
│   │   │               │       ├── BcryptUtf8LengthValidator
│   │   │               │       ├── PasswordPolicy
│   │   │               │       ├── PasswordValidator
│   │   │               │       └── ValidPassword
│   │   │               │
│   │   │               └── SafeaiBackendApplication
│   │   │
│   │   └── resources/
│   │       ├── db/
│   │       │   ├── local-migration/
│   │       │   │   └── R__seed_local_demo_data.sql
│   │       │   └── migration/
│   │       │       ├── V1__init_schema.sql
│   │       │       ├── V2__seed_reference_data.sql
│   │       │       ├── V3__denormalize_chat_organization.sql
│   │       │       ├── V4__schema_hardening.sql
│   │       │       ├── V5__audit_event_types.sql
│   │       │       ├── V6__updated_at_triggers.sql
│   │       │       ├── V7__usage_quotas_and_rollups.sql
│   │       │       ├── V8__usage_chat_messages_indexes.sql
│   │       │       ├── V9__audit_events_indexes.sql
│   │       │       ├── V10__schema_hardening_timestamps_audit_rollups.sql
│   │       │       ├── V11__add_user_updated_audit_event_type.sql
│   │       │       ├── V12__enforce_tenant_and_refresh_token_integrity.sql
│   │       │       ├── V13__identity_refresh_and_message_integrity.sql
│   │       │       ├── V14__chat_turn_idempotency_and_integrity.sql
│   │       │       ├── V15__audit_query_and_retention_indexes.sql
│   │       │       ├── V16__preserve_usage_history_on_user_delete.sql
│   │       │       ├── V17__ai_usage_and_pricing_metadata.sql
│   │       │       ├── V18__audit_actor_snapshots.sql
│   │       │       ├── V19__user_management_details_and_audit.sql
│   │       │       ├── V20__production_integrity_hardening.sql
│   │       │       ├── V21__refresh_token_cleanup_batch_index.sql
│   │       │       ├── V22__chat_single_reply_index.sql
│   │       │       ├── V23__organization_normalized_name_index.sql
│   │       │       ├── V24__user_security_and_audit_outbox_hardening.sql
│   │       │       ├── V25__organization_auth_version.sql
│   │       │       ├── V26__audit_snapshot_retry_and_retention_hardening.sql
│   │       │       ├── V27__audit_query_and_outbox_indexes.sql
│   │       │       ├── V28__usage_analytics_hardening.sql
│   │       │       ├── V29__usage_analytics_indexes.sql
│   │       │       ├── V30__validate_usage_constraints.sql
│   │       │       ├── V31__usage_user_report_index.sql
│   │       │       ├── V32__chat_turn_state_machine.sql
│   │       │       ├── V33__chat_turn_indexes.sql
│   │       │       ├── V34__chat_ambiguous_usage_quality.sql
│   │       │       ├── V35__chat_turn_composite_integrity.sql
│   │       │       ├── V36__validate_chat_composite_integrity.sql
│   │       │       ├── V37__audit_target_organization_snapshots.sql
│   │       │       └── README_DB.md
│   │       ├── application.yaml
│   │       ├── application-local.yaml
│   │       ├── application-local-nginx.yml
│   │       ├── application-prod.yml
│   │       └── logback-spring.xml
│   │
│   └── test/
│       ├── java/
│       │   └── ru/
│       │       └── safeai/
│       │           └── gateway/
│       │               ├── admin/
│       │               │   └── controller/
│       │               │
│       │               ├── ai/
│       │               │   ├── config/
│       │               │   │   └── AiProductionConfigurationValidatorTest
│       │               │   ├── provider/
│       │               │   │   ├── anthropic/
│       │               │   │   │   └── AnthropicProviderContractTest
│       │               │   │   └── openai/
│       │               │   │       └── OpenAiProviderContractTest
│       │               │   ├── testsupport/
│       │               │   │   ├── AiTestFixtures
│       │               │   │   └── ProviderContractTestServer
│       │               │   ├── AiChatResponseTest
│       │               │   ├── AiContextWindowServiceTest
│       │               │   ├── AiExceptionHandlerTest
│       │               │   ├── AiMessageAndRequestTest
│       │               │   ├── AiProviderPropertiesTest
│       │               │   ├── AiProviderRetryExecutorTest
│       │               │   ├── AiProviderSupportTest
│       │               │   ├── AiRestClientFactoryTest
│       │               │   ├── AiRetryPropertiesTest
│       │               │   ├── AnthropicPropertiesTest
│       │               │   ├── MockAiProviderTest
│       │               │   ├── ModelPricingPropertiesTest
│       │               │   ├── ModelPricingServiceTest
│       │               │   ├── OpenAiPropertiesTest
│       │               │   └── PricingResultTest
│       │               │
│       │               ├── audit/
│       │               │   ├── config/
│       │               │   │   └── AuditDetailsPropertiesTest
│       │               │   ├── controller/
│       │               │   │   └── AuditControllerSecurityTest
│       │               │   ├── details/
│       │               │   │   └── AuditDetailsRecordsTest
│       │               │   ├── dto/
│       │               │   │   └── AuditEventPageResponseTest
│       │               │   ├── listener/
│       │               │   │   └── RateLimitAuditListenerTest
│       │               │   ├── repository/
│       │               │   │   ├── AuditEmailPrefixIndexIntegrationTest
│       │               │   │   ├── AuditMigrationIntegrityIntegrationTest
│       │               │   │   └── AuditRepositoryContractTest
│       │               │   ├── service/
│       │               │   │   ├── AuditActorSnapshotIntegrationTest
│       │               │   │   ├── AuditCursorCodecTest
│       │               │   │   ├── AuditCursorPaginationIntegrationTest
│       │               │   │   ├── AuditDetailsSanitizerTest
│       │               │   │   ├── AuditEventQueryServiceTest
│       │               │   │   ├── AuditEventServiceTest
│       │               │   │   ├── AuditOutboxFailureServiceTest
│       │               │   │   ├── AuditOutboxIntegrationTest
│       │               │   │   ├── AuditOutboxProcessorTest
│       │               │   │   ├── AuditOutboxWriterTest
│       │               │   │   ├── AuditRetentionBatchServiceTest
│       │               │   │   ├── AuditRetentionIntegrationTest
│       │               │   │   ├── AuditRetentionLockIntegrationTest
│       │               │   │   ├── AuditRetentionServiceTest
│       │               │   │   ├── AuditTenantIsolationIntegrationTest
│       │               │   │   ├── BestEffortAuditPolicyIntegrationTest
│       │               │   │   ├── BestEffortStandaloneAuditServiceTest
│       │               │   │   └── RequiredAuditPolicyIntegrationTest
│       │               │   └── testsupport/
│       │               │       └── AbstractAuditPostgresIntegrationTest
       │               │
│       │               ├── auth/
│       │               │   ├── controller/
│       │               │   │   ├── AuthControllerSecurityTest
│       │               │   │   └── CsrfControllerTest
│       │               │   ├── integration/
│       │               │   │   ├── AuthPostgresConcurrencyIT
│       │               │   │   └── UserSecurityMutationTestService
│       │               │   ├── security/
│       │               │   │   ├── CustomUserDetailsServiceTest
│       │               │   │   ├── SecurityConfigIntegrationTest
│       │               │   │   └── UserStatusFilterTest
│       │               │   └── service/
│       │               │       ├── AuthCookiePropertiesTest
│       │               │       ├── AuthCookieServiceTest
│       │               │       ├── AuthEventServiceTest
│       │               │       ├── AuthServiceTest
│       │               │       └── RefreshTokenServiceTest
│       │               │
│       │               ├── chat/
│       │               │   ├── config/
│       │               │   │   └── ChatConfigurationPropertiesTest
│       │               │   ├── controller/
│       │               │   │   ├── ChatControllerContractTest
│       │               │   │   └── ChatControllerSecurityTest
│       │               │   ├── dto/
│       │               │   │   └── ChatDtoContractTest
│       │               │   ├── entity/
│       │               │   │   └── ChatEntityInvariantTest
│       │               │   ├── integration/
│       │               │   │   ├── AbstractChatPostgresIntegrationTest
│       │               │   │   ├── ChatConcurrencyIntegrationTest
│       │               │   │   ├── ChatDatabaseStateConstraintIntegrationTest
│       │               │   │   ├── ChatExplainPlanIntegrationTest
│       │               │   │   ├── ChatHistoryIntegrationTest
│       │               │   │   ├── ChatIntegrationClockConfiguration
│       │               │   │   ├── ChatLegacyUpgradeMigrationIntegrationTest
│       │               │   │   ├── ChatMigrationIntegrityIntegrationTest
│       │               │   │   ├── ChatTenantIsolationIntegrationTest
│       │               │   │   ├── ChatTurnRecoveryIntegrationTest
│       │               │   │   ├── ChatTurnRepositoryTransactionIntegrationTest
│       │               │   │   ├── ChatTurnStateMachineIntegrationTest
│       │               │   │   └── ChatUsageQualityIntegrationTest
│       │               │   ├── observability/
│       │               │   │   └── ChatMetricsTest
│       │               │   ├── service/
│       │               │   │   ├── AiHistoryBuilderTest
│       │               │   │   ├── ChatContentNormalizerTest
│       │               │   │   ├── ChatLockServiceTest
│       │               │   │   ├── ChatQuotaServiceTest
│       │               │   │   ├── ChatServiceTest
│       │               │   │   ├── ChatTurnFinalizationServiceTest
│       │               │   │   ├── ChatTurnLeaseServiceTest
│       │               │   │   ├── ChatTurnRecoverySchedulerTest
│       │               │   │   ├── ChatTurnRecoveryServiceTest
│       │               │   │   └── ChatTurnReservationServiceTest
│       │               │   └── testsupport/
│       │               │       └── ChatTestFixtures
│       │               │
│       │               ├── common/
│       │               │   ├── exception/
│       │               │   │   ├── ApiErrorResponseFactoryTest
│       │               │   │   ├── ApiErrorResponseTest
│       │               │   │   ├── GlobalExceptionHandlerAccessDeniedTest
│       │               │   │   ├── GlobalExceptionHandlerIntegrationTest
│       │               │   │   ├── GlobalExceptionHandlerTest
│       │               │   │   ├── OrganizationVersionConflictExceptionTest
│       │               │   │   └── UserVersionConflictExceptionTest
│       │               │   └── security/
│       │               │       ├── AccessTokenSubjectTest
│       │               │       ├── ClientIpPropertiesTest
│       │               │       ├── ClientIpResolverTest
│       │               │       ├── CorsPropertiesTest
│       │               │       ├── JwtCodecConfigurationTest
│       │               │       ├── JwtConfigurationBindingTest
│       │               │       ├── JwtPropertiesTest
│       │               │       ├── JwtServiceTest
│       │               │       ├── PasswordEncodingConfigurationTest
│       │               │       ├── ProductionSecurityInvariantValidatorTest
│       │               │       ├── ProductionSecurityStartupContractTest
│       │               │       ├── RequestIdFilterTest
│       │               │       ├── RoleAuthorityMapperTest
│       │               │       ├── SafeAiJwtAuthenticationConverterTest
│       │               │       ├── SafeAiUserPrincipalTest
│       │               │       └── SecurityErrorResponseIntegrationTest
│       │               │
│       │               │
                        knowledge
├── controller
│   └── KnowledgeBaseController
├── dto
│   ├── CreateKnowledgeBaseRequest
│   ├── UpdateKnowledgeBaseRequest
│   ├── CreateKnowledgeBaseMemberRequest
│   ├── UpdateKnowledgeBaseMemberRequest
│   ├── KnowledgeBaseResponse
│   ├── KnowledgeBasePageResponse
│   ├── KnowledgeBaseMemberResponse
│   ├── KnowledgeBaseMemberPageResponse
│   └── KnowledgeMemberCandidateResponse
├── entity
│   ├── KnowledgeBaseEntity
│   └── KnowledgeBaseMembershipEntity
├── model
│   ├── KnowledgeBaseVisibility
│   └── KnowledgeBaseAccessLevel
├── repository
│   ├── KnowledgeBaseRepository
│   ├── KnowledgeBaseMembershipRepository
│   └── KnowledgeMemberDirectoryRepository
└── service
    ├── KnowledgeBaseNameNormalizer
    └── KnowledgeBaseService
│       │               │   
│       │               │   
│       │               │    
│       │               │
│       │               ├── organization/
│       │               │   ├── controller/
│       │               │   │   └── OrganizationControllerSecurityTest
│       │               │   ├── repository/
│       │               │   │   └── OrganizationImpactQueryRepositoryTest
│       │               │   └── service/
│       │               │       ├── OrganizationNameNormalizerTest
│       │               │       ├── OrganizationPostgresIntegrationTest
│       │               │       ├── OrganizationSecurityEpochPostgresIntegrationTest
│       │               │       ├── OrganizationServiceTest
│       │               │       ├── OrganizationStatusCacheInvalidationListenerTest
│       │               │       └── PlatformOrganizationInvariantVerifierTest
│       │               │
│       │               ├── ratelimit/
│       │               │   ├── external/
│       │               │   │   ├── RedisClusterRateLimitIT
│       │               │   │   └── RedisSentinelFailoverIT
│       │               │   ├── DualRateLimitResultTest
│       │               │   ├── LoginRateLimitServiceTest
│       │               │   ├── RateLimitExceededExceptionTest
│       │               │   ├── RateLimitKeyFactoryTest
│       │               │   ├── RateLimitPropertiesTest
│       │               │   ├── RedisClusterSlotContractTest
│       │               │   ├── RedisFixedWindowRateLimiterIntegrationTest
│       │               │   └── RedisRateLimitServiceTest
│       │               │
│       │               ├── testsupport/
│       │               │   └── AbstractPostgresIntegrationTest
│       │               │
│       │               ├── usage/
│       │               │   ├── config/
│       │               │   │   ├── UsageJdbcClientsTest
│       │               │   │   └── UsagePropertiesTest
│       │               │   ├── controller/
│       │               │   │   ├── UsageControllerContractTest
│       │               │   │   └── UsageControllerSecurityTest
│       │               │   ├── dto/
│       │               │   │   ├── UsagePagingContractTest
│       │               │   │   └── UsageSummaryValueObjectsTest
│       │               │   ├── repository/
│       │               │   │   ├── UsageCoverageIntegrationTest
│       │               │   │   ├── UsageEntityMappingContractTest
│       │               │   │   ├── UsageExplainAnalyzePerformanceIntegrationTest
│       │               │   │   ├── UsageExplainPlanIntegrationTest
│       │               │   │   ├── UsageMigrationIntegrityIntegrationTest
│       │               │   │   ├── UsagePaginationIntegrationTest
│       │               │   │   ├── UsagePerformanceIntegrationTest
│       │               │   │   ├── UsagePricingCorrectnessIntegrationTest
│       │               │   │   ├── UsageQueryContractTest
│       │               │   │   ├── UsageQueryRepositoryTest
│       │               │   │   ├── UsageRollupAdvisoryLockIntegrationTest
│       │               │   │   ├── UsageRollupReconciliationIntegrationTest
│       │               │   │   ├── UsageTenantIsolationIntegrationTest
│       │               │   │   └── UsageUtcAggregationIntegrationTest
│       │               │   ├── service/
│       │               │   │   ├── UsageQueryServiceTest
│       │               │   │   ├── UsageReportExecutorTest
│       │               │   │   ├── UsageRollupDayProcessorTest
│       │               │   │   └── UsageRollupSchedulerTest
│       │               │   └── testsupport/
│       │               │       └── UsagePostgresIntegrationTestSupport
│       │               │
│       │               ├── user/
│       │               │   ├── controller/
│       │               │   │   └── UserControllerSecurityTest
│       │               │   ├── dto/
│       │               │   │   └── CreateUserRequestValidationTest
│       │               │   ├── service/
│       │               │   │   ├── UserManagementPostgresIntegrationTest
│       │               │   │   ├── UserSecurityTransactionIntegrationTest
│       │               │   │   ├── UserServiceTest
│       │               │   │   ├── UserStatusCacheInvalidationIntegrationTest
│       │               │   │   ├── UserStatusCachePropertiesTest
│       │               │   │   └── UserStatusCacheServiceTest
│       │               │   └── validation/
│       │               │       └── PasswordValidatorTest
│       │               │
│       │               ├── PasswordHashGenerator
│       │               └── SafeaiBackendApplicationTests
│       │
│       └── resources/
│           ├── sql/
│           │   └── post_v23_assertions.sql
│           ├── application-auth-postgres-it.yml
│           └── application-test.yml
│
├── target/
├── .dockerignore
├── .env.example
├── .env.prod
├── .gitattributes
├── .gitignore
├── commit-message.txt
├── Dockerfile
├── mvnw
├── mvnw.cmd
├── pom.xml
└── docs/
    ├── .local-run/
    └── local/
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

## Frontend-модули

```text
├── frontend/
│   ├── node_modules/
│   │
│   ├── src/
│   │   ├── api/
│   │   │   ├── __tests__/
│   │   │   ├── adminApi.test.ts
│   │   │   ├── adminApi.ts
│   │   │   ├── authApi.test.ts
│   │   │   ├── authApi.ts
│   │   │   ├── authCoordinator.ts
│   │   │   ├── chatApi.test.ts
│   │   │   ├── chatApi.ts
│   │   │   ├── http.chat-errors.test.ts
│   │   │   ├── http.test.ts
│   │   │   ├── http.ts
│   │   │   ├── organizationApi.test.ts
│   │   │   ├── organizationApi.ts
│   │   │   ├── query.test.ts
│   │   │   ├── query.ts
│   │   │   ├── runtime.ts
│   │   │   ├── types.ts
│   │   │   ├── usageApi.test.ts
│   │   │   ├── usageApi.ts
│   │   │   ├── userApi.test.ts
│   │   │   └── userApi.ts
│   │   │
│   │   ├── auth/
│   │   │   ├── AuthContext.test.tsx
│   │   │   └── AuthContext.tsx
│   │   │
│   │   ├── components/
│   │   │   ├── admin/
│   │   │   │   ├── __tests__/
│   │   │   │   │   └── UserRoleSelector.test.tsx
│   │   │   │   │
│   │   │   │   ├── audit/
│   │   │   │   │   ├── AuditActor.test.tsx
│   │   │   │   │   ├── AuditActor.tsx
│   │   │   │   │   ├── AuditDetailsModal.tsx
│   │   │   │   │   ├── AuditFilters.tsx
│   │   │   │   │   ├── AuditPagination.tsx
│   │   │   │   │   ├── AuditTable.tsx
│   │   │   │   │   └── types.ts
│   │   │   │   │
│   │   │   │   ├── UserActionsMenu.test.tsx
│   │   │   │   ├── UserActionsMenu.tsx
│   │   │   │   ├── UserIdentityCell.tsx
│   │   │   │   ├── UserRoleBadge.tsx
│   │   │   │   └── UserRoleSelector.tsx
│   │   │   │
│   │   │   ├── ConfirmDialog.tsx
│   │   │   ├── ErrorBoundary.tsx
│   │   │   ├── Modal.tsx
│   │   │   ├── PageErrorBoundary.tsx
│   │   │   └── StateBlock.tsx
│   │   │
│   │   ├── constants/
│   │   │   └── auditEvents.ts
│   │   │
│   │   ├── domain/
│   │   │   ├── __tests__/
│   │   │   │   └── userManagementRolePolicy.test.ts
│   │   │   └── userManagementRolePolicy.ts
│   │   │
│   │   ├── hooks/
│   │   │   ├── admin/
│   │   │   │   ├── useAuditDirectories.ts
│   │   │   │   └── useAuditEvents.ts
│   │   │   └── useAutoClearMessage.ts
│   │   │
│   │   ├── pages/
│   │   │   ├── adminAudit.helpers.test.ts
│   │   │   ├── adminAudit.helpers.ts
│   │   │   ├── AdminAuditPage.css
│   │   │   ├── AdminAuditPage.tsx
│   │   │   ├── AdminOrganizationsPage.css
│   │   │   ├── AdminOrganizationsPage.tsx
│   │   │   ├── adminUsage.helpers.test.ts
│   │   │   ├── adminUsage.helpers.ts
│   │   │   ├── AdminUsagePage.tsx
│   │   │   ├── AdminUsersPage.css
│   │   │   ├── AdminUsersPage.tsx
│   │   │   ├── chatPage.helpers.test.ts
│   │   │   ├── chatPage.helpers.ts
│   │   │   ├── ChatPage.tsx
│   │   │   ├── LoginPage.test.tsx
│   │   │   └── LoginPage.tsx
│   │   │
│   │   ├── styles/
│   │   │   ├── production-hardening.css
│   │   │   └── user-management-additions.css
│   │   │
│   │   ├── test/
│   │   │   └── setup.ts
│   │   │
│   │   ├── utils/
│   │   │   ├── date.test.ts
│   │   │   ├── date.ts
│   │   │   ├── format.test.ts
│   │   │   ├── format.ts
│   │   │   ├── frontendErrorReporting.ts
│   │   │   ├── organizations.test.ts
│   │   │   ├── organizations.ts
│   │   │   ├── page.test.ts
│   │   │   ├── page.ts
│   │   │   ├── password.test.ts
│   │   │   ├── password.ts
│   │   │   └── secureUuid.ts
│   │   │
│   │   ├── App.test.tsx
│   │   ├── App.tsx
│   │   ├── index.css
│   │   ├── main.tsx
│   │   └── vite-env.d.ts
│   │
│   ├── eslint.config.mjs
│   ├── index.html
│   ├── install-and-check.cmd
│   ├── install-and-check.sh
│   ├── package.json
│   ├── package-lock.json
│   ├── tsconfig.app.json
│   ├── tsconfig.json
│   ├── tsconfig.node.json
│   ├── tsconfig.test.json
│   └── vite.config.ts

# Модуль common

`ru.safeai.gateway.common` — cross-cutting infrastructure layer.

```text
common/
├── config/
│   ├── SchedulingConfiguration
│   └── TimeConfiguration
├── exception/
│   ├── ApiErrorCode
│   ├── ApiErrorResponse
│   ├── ApiErrorResponseFactory
│   ├── ApiErrorResponseWriter
│   ├── ApiException
│   ├── AuthServiceUnavailableException
│   ├── BadRequestException
│   ├── ChatBusyException
│   ├── ChatLockUnavailableException
│   ├── ConflictException
│   ├── ExpiredRefreshTokenException
│   ├── ForbiddenOperationException
│   ├── GlobalExceptionHandler
│   ├── InvalidRefreshTokenException
│   ├── OrganizationVersionConflictException
│   ├── RateLimitExceededException
│   ├── RateLimitUnavailableException
│   ├── RefreshTokenReuseDetectedException
│   ├── ResourceNotFoundException
│   └── UserVersionConflictException
├── persistence/
│   └── DatabaseConstraintClassifier
├── platform/
│   ├── PlatformProperties
│   └── PlatformPropertiesConfiguration
├── security/
│   ├── AccessTokenSubject
│   ├── ClientIpProperties
│   ├── ClientIpResolver
│   ├── CorsProperties
│   ├── JwtCodecConfiguration
│   ├── JwtProperties
│   ├── JwtService
│   ├── PasswordEncodingConfiguration
│   ├── ProductionSecurityInvariantValidator
│   ├── RequestIdFilter
│   ├── RestAccessDeniedHandler
│   ├── RestAuthenticationEntryPoint
│   ├── RoleAuthorityMapper
│   ├── SafeAiJwtAuthenticationConverter
│   ├── SafeAiUserPrincipal
│   ├── SecurityIdentityValidator
│   ├── SecurityPropertiesConfiguration
│   └── SystemRole
└── web/
    └── ApiFallbackController
```

### `config`

`TimeConfiguration` даёт UTC `Clock`, что делает timestamps тестируемыми. `SchedulingConfiguration` включает scheduler; 
multi-instance jobs обязаны иметь собственную DB/distributed coordination.

### `exception`

Основные codes:

```text
BAD_REQUEST
VALIDATION_ERROR
UNAUTHORIZED
FORBIDDEN
NOT_FOUND
CONFLICT
USER_VERSION_CONFLICT
ORGANIZATION_VERSION_CONFLICT
CHAT_BUSY
CHAT_LOCK_UNAVAILABLE
RATE_LIMIT_EXCEEDED
RATE_LIMIT_UNAVAILABLE
AUTH_SERVICE_UNAVAILABLE
EXPIRED_REFRESH_TOKEN
INVALID_REFRESH_TOKEN
TOKEN_REVOKED
AI_PROVIDER_TIMEOUT
AI_PROVIDER_RATE_LIMITED
AI_PROVIDER_OVERLOADED
AI_PROVIDER_UNAVAILABLE
AI_PROVIDER_ERROR
METHOD_NOT_ALLOWED
UNSUPPORTED_MEDIA_TYPE
NOT_ACCEPTABLE
INTERNAL_SERVER_ERROR
```

`ApiException` разделяет public/internal message. Internal diagnostics не должны попадать в response.

### `persistence`

`DatabaseConstraintClassifier` классифицирует PostgreSQL unique/FK failures по SQLSTATE/constraint name, чтобы ожидаемый 
concurrency conflict не маскировался как случайный 500.

### `platform`

Platform organization default UUID:

```text
00000000-0000-0000-0000-000000000001
```

### `security`

Общие contracts: JWT, role mapping, principal, identity validation, password encoding, CORS, trusted IP, request correlation, 
production fail-fast invariants.

`SafeAiUserPrincipal` не должен раскрывать password/token в `toString`; access-token principal не несёт password credentials.

---

# Backend-модули

| Модуль | Назначение |
|---|---|
| `auth` | login/logout/refresh, cookies, rotation, revocation |
| `common` | shared security/error/platform/time/persistence |
| `organization` | tenant lifecycle, platform invariants, org security epoch |
| `user` | users, one-role policy, optimistic versions, password/security mutation |
| `chat` | sessions/messages/turns, quotas, lease/fencing/recovery |
| `ai` | provider abstraction, retry/errors/context/pricing metadata |
| `ratelimit` | Redis rate limiting/metrics |
| `audit` | snapshots, outbox, cursor queries, retention |
| `usage` | live/rollup analytics + data quality |
| `admin` | administrative API entry points |

---

# ChatTurn

Текущий chat flow описывается durable state machine:

```text
NEW
PROCESSING
SUCCEEDED
FAILED
AMBIGUOUS
```

Frontend дополнительно имеет UI/transport statuses:

```text
SENDING
PROCESSING
SEND_UNKNOWN
FAILED
AMBIGUOUS
RATE_LIMITED
QUOTA_BLOCKED
ACCESS_REVOKED
IDEMPOTENCY_CONFLICT
```

## Idempotency

Frontend генерирует secure `clientRequestId`.

```text
same id + same normalized request
→ replay

same id + different request
→ IDEMPOTENCY_CONFLICT
```

## Durable provider boundary

До provider I/O фиксируются:

```text
ChatTurn
processingToken
providerOperationId
leaseUntil
quota reservation
```

Provider call выполняется вне долгой DB transaction.

## Fencing

Finalization проверяет `processingToken`; stale processor не может завершить turn после lease takeover.

## Provider ambiguity

`provider_call_started_at` разделяет recovery:

```text
null
→ provider точно не начинался
→ безопасный recovery

non-null
→ provider мог выполнить request
→ blind retry запрещён
→ uncertainty => AMBIGUOUS
```

Frontend намеренно не запускает автоматический новый request из `AMBIGUOUS`.

Reconciliation:

```http
GET /api/chats/{chatId}/turns/by-client-request/{clientRequestId}
```

---

# AI providers

Интерфейс:

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

Есть provider error taxonomy: timeout, rate-limit, overload, quota/billing, context limit, response-too-large, unavailable 
и generic provider error.

## Usage / pricing status

Usage:

```text
NOT_APPLICABLE
AVAILABLE
MISSING
PARTIAL
```

Pricing:

```text
NOT_APPLICABLE
PRICED
FREE
UNPRICED
CALCULATION_FAILED
```

**Unknown pricing не превращается в zero.**

`FREE` — подтверждённый ноль. `UNPRICED` — нет валидной цены. `CALCULATION_FAILED` — сбой расчёта.

---

# Rate limiting

Redis-backed limits:

```text
login: identity/email + IP
AI: user + organization
```

Atomic counter/TTL выполняется Lua script.

При limit:

```text
429
retry metadata
RATE_LIMIT_EXCEEDED
audit event
```

Infrastructure outage имеет отдельный controlled error, а не silent allow-all.

---

# Audit

Текущая архитектура:

```text
business transaction
 ├─ domain mutation
 └─ audit_outbox
       ↓ commit
 processor
       ↓
 audit_events
```

Outbox поддерживает retry/dead-letter:

```text
occurred_at
attempt_count
next_attempt_at
last_error
dead_lettered_at
```

Immutable actor snapshot:

```text
actor_user_id
actor_email
actor_display_name
actor_organization_id
```

Immutable target snapshot:

```text
organization_id
target_organization_name
```

Actor organization и target organization — разные измерения.

Audit details проходят sanitization/size limits и frontend defence-in-depth redaction.

Frontend умеет:

- event type;
- actor directory;
- actor email/prefix;
- target organization для `SUPER_ADMIN`;
- date range;
- URL state;
- pagination;
- details modal.

---

# Usage

Usage subsystem считает не только sums, но и data quality.

```text
available / partial / missing usage
priced / free / unpriced / pricing failed
ambiguous provider operation count
```

Daily aggregation — UTC.

Date range:

```text
2026-08-01 .. 2026-08-04
→ [2026-08-01T00:00:00Z, 2026-08-05T00:00:00Z)
```

Frontend сохраняет большие token counters/decimal values в string-safe форме и использует `BigInt` для форматирования.

Rollups не заменяют source-of-truth live data; есть reconciliation/performance tests.

---

# User management

Основные contracts:

```http
GET   /api/users
GET   /api/users/statistics
GET   /api/users/{id}

POST  /api/users
PATCH /api/users/{id}
PATCH /api/users/{id}/enabled
PATCH /api/users/{id}/roles
POST  /api/users/{id}/reset-password
POST  /api/users/{id}/permanent-deletion
```

Mutations используют `expectedVersion` для optimistic concurrency.

Wire `roles` остаётся массивом для compatibility, но требует ровно одну role.

Permanent deletion — restricted flow с eligibility/retention, confirmation email, version check, dependencies и audit; 
historical data не должно случайно исчезать каскадом.

---

# Organization management

Current frontend contract:

```http
POST  /api/organizations
GET   /api/organizations
GET   /api/organizations/{id}
PATCH /api/organizations/{id}

POST /api/organizations/{id}/disable
POST /api/organizations/{id}/enable
```

Disable использует `expectedVersion` и typed `confirmationName`.

Organization response различает:

```text
PLATFORM + protected=true
TENANT   + protected=false
```

Security invalidation использует organization `authVersion`. Re-enable не восстанавливает старые refresh sessions.

---

# Frontend

Frontend — не только страницы, но и defensive client contracts.

## Runtime parsing

`runtime.ts` валидирует:

```text
UUID
Instant
enum
non-negative integer
decimal string
PageResponse
nullable fields
```

Malformed backend response fail-fast превращается в controlled client contract error.

## HTTP/Auth

Общий HTTP layer отвечает за cookies, CSRF, timeouts, AbortSignal, error envelope и безопасную auth coordination.

`AuthContext` различает состояния вроде:

```text
loading
authenticated
unauthenticated
temporarily-unavailable
logout-unconfirmed
```

Transient auth outage не маскируется как invalid credentials.

## Chat

- secure client UUID;
- optimistic USER message;
- reconciliation по idempotency key;
- `SEND_UNKNOWN`;
- durable turn polling/status;
- no automatic retry after `AMBIGUOUS`;
- отдельный usage/pricing completeness display.

## UI hardening

Есть:

- ErrorBoundary;
- incident ID;
- production-safe frontend error event;
- loading/error/empty state components;
- modal/focus handling;
- live regions;
- focus-visible;
- reduced motion;
- URL-safe audit/usage filters.

Это accessibility hardening, не заявление о формальной WCAG certification.

---

# API

## Auth

```http
GET  /api/auth/csrf
POST /api/auth/login
POST /api/auth/refresh
POST /api/auth/logout
GET  /api/auth/me
```

## Chat

```http
POST /api/chats
GET  /api/chats
GET  /api/chats/{id}
POST /api/chats/{id}/messages
GET  /api/chats/{chatId}/turns/by-client-request/{clientRequestId}
```

## Users

```http
GET   /api/users
GET   /api/users/statistics
GET   /api/users/{id}
POST  /api/users
PATCH /api/users/{id}
PATCH /api/users/{id}/enabled
PATCH /api/users/{id}/roles
POST  /api/users/{id}/reset-password
POST  /api/users/{id}/permanent-deletion
```

## Organizations

```http
POST  /api/organizations
GET   /api/organizations
GET   /api/organizations/{id}
PATCH /api/organizations/{id}
POST  /api/organizations/{id}/disable
POST  /api/organizations/{id}/enable
```

## Audit

```http
GET /api/admin/audit-events
```

Audit module также предоставляет directory API для types/actors/target organizations.

## Usage

```http
GET /api/admin/usage/summary
GET /api/admin/usage/users
GET /api/admin/usage/models
GET /api/admin/usage/daily
GET /api/admin/usage/users/{userId}
GET /api/admin/usage/organizations/{organizationId}
```

---

# Database/Flyway

Production:

```text
backend/src/main/resources/db/migration
```

Local-only:

```text
backend/src/main/resources/db/local-migration/R__seed_local_demo_data.sql
```

Правила:

```text
never edit applied migration
new schema => new migration
Hibernate ddl-auto=validate
critical invariants in PostgreSQL where practical
local seed is not production reference data
```

Текущая история — до `V37`.

| Milestone | Содержание |
|---|---|
| `V1–V10` | base schema, tenant denormalization, hardening, quotas/rollups |
| `V11–V19` | audit/user/chat identity/integrity evolution |
| `V20–V23` | production integrity, cleanup/index/name hardening |
| `V24` | exactly-one-role, disabled retention, audit outbox |
| `V25` | organization `auth_version` |
| `V26–V27` | audit retry/dead-letter/snapshots/indexes |
| `V28–V31` | usage/pricing quality + indexes |
| `V32` | durable ChatTurn state machine |
| `V33–V36` | turn indexes, ambiguity quality, composite integrity |
| `V37` | target organization snapshots |

Future migration numbers в roadmap — tentative: перед merge проверить реально свободный version.

---

# Local development

## Infra

Windows:

```bat
cd /d "D:\Java projects\Safeai-desk\infra"
docker compose -f docker-compose.local.yml up -d postgres redis
```

macOS/Linux:

```bash
cd infra
docker compose -f docker-compose.local.yml up -d postgres redis
```

## Backend

Windows:

```bat
cd /d "D:\Java projects\Safeai-desk\backend"

set SPRING_PROFILES_ACTIVE=local
set SAFEAI_JWT_SECRET=safeai-local-development-secret-key-change-this-value-please-123456789
set REDIS_PASSWORD=safeai_redis_password

mvnw.cmd spring-boot:run
```

macOS/Linux:

```bash
cd backend

export SPRING_PROFILES_ACTIVE=local
export SAFEAI_JWT_SECRET='safeai-local-development-secret-key-change-this-value-please-123456789'
export REDIS_PASSWORD='safeai_redis_password'

./mvnw spring-boot:run
```

## Frontend

```bash
cd frontend
npm ci
npm run dev
```

URLs:

```text
Frontend: http://127.0.0.1:5173
Backend:  http://127.0.0.1:8080
Postgres: localhost:5432
Redis:    localhost:6379
```

`R__seed_local_demo_data.sql` содержит deterministic local identities и проверки перед privileged role assignment. 
Demo credentials не использовать в production.

---

# Проверка

README не утверждает `BUILD SUCCESS` без фактического запуска.

## Backend

```bash
cd backend
./mvnw clean test
```

Windows:

```bat
cd backend
mvnw.cmd clean test
```

## Frontend

```bash
cd frontend
npm ci
npm run check
```

CI-oriented:

```bash
npm run ci
```

Также:

```bash
npm run audit:prod
```

Текущие backend tests включают security, auth, PostgreSQL concurrency, tenant isolation, migrations, audit outbox/retention, 
ChatTurn state machine/recovery, usage reconciliation/performance и provider contracts.

## Flyway

```sql
select installed_rank, version, description, success
from flyway_schema_history
order by installed_rank;
```

Release candidate должен дополнительно проверять:

```text
fresh V1→latest migration
upgrade from representative populated snapshot
container build/start
prod-like startup invariants
```

---

# Production notes

## TLS

```text
Internet
→ TLS terminator / reverse proxy
→ Nginx
→ backend internal port
```

Backend не должен публиковаться напрямую.

## Trusted proxy

Указывать только реальный edge CIDR. Не использовать trust-all networks.

## CORS

Production — explicit HTTPS allowlist.

## Secrets

Не хранить в Git:

```text
JWT secret
DB password
Redis password
OpenAI key
Anthropic key
future BYOK/KMS credentials
```

## Prometheus

`/actuator/prometheus` — internal/network-protected.

## User status cache

До доказанной fail-safe invalidation strategy strict security mode предпочтительно держать status cache disabled.

## Logs

Не логировать по умолчанию:

```text
passwords
raw JWT/refresh token
cookies
Authorization
API keys
full prompts/responses
future document chunks
```

---

# Текущие границы

V42 содержит работающий Knowledge/RAG vertical slice: object storage,
immutable versions, durable ingestion, multi-format extraction, OCR adapter,
pgvector/FTS hybrid retrieval, ACL, context assembly, LLM generation,
validated inline citations, knowledge-only mode, evaluation baseline и Answer
Passport. Production deployment обязан явно выбрать embedding/OCR providers.

До следующих product targets нужны:

- streaming с сохранением durable/fenced semantics;
- полноценный model policy/router и private model adapters;
- OIDC/connectors/ACL sync;
- автоматизированные evaluation gates и knowledge drift alerts;
- production observability/SLO и backup/restore drills;
- controlled tools/MCP, human approvals и durable agents.

Не заявлять текущую версию как:

```text
billing-grade
compliance-certified
fully HA
deterministically reproducible LLM
autonomous agent platform
```

---

# Positioning

Для собеседования:

> **SafeAI Desk — modular-monolith корпоративного AI Gateway с multi-tenancy, one-role RBAC, cookie JWT + CSRF, refresh 
> rotation/reuse detection, user/org security epochs, durable idempotent ChatTurn, provider ambiguity protection, 
> transactional audit outbox и usage/pricing quality analytics.**

Для будущего продукта:

> **Управляемая корпоративная AI-платформа, где известно кто, к каким данным и через какую модель получил доступ, 
> какой контекст был использован, какие действия были разрешены и сколько это стоило.**

---

# Реализованный Knowledge/RAG path

Knowledge/RAG сохраняет текущую короткую `reserveOrReplay()` transaction.

```text
reserveOrReplay()
        │
        ▼
ChatTurn PROCESSING committed
        │
        ▼
RetrievalService.retrieve(...)
        │
        ├─ tenant/ACL
        ├─ hybrid retrieval
        └─ persist RetrievalRun/Hits
        │
        ▼
build final AiChatRequest
        │
        ▼
markProviderCallStarted()
        │
        ▼
AiProvider.sendMessage()
        │
        ▼
fenced finalization
```

Для knowledge-assisted turn:

```text
provider_call_started_at != null
→ retrieval provenance already committed
```

Полная последовательность развития — в [`ROADMAP.md`](ROADMAP.md).


```text
SafeAI-Desk/
├── backend/
│   ├── .env                    ← LOCAL настоящий, Git ❌
│   ├── .env.example            ← LOCAL шаблон, Git ✅
│   ├── .env.prod               ← PROD настоящий, Git ❌
│   ├── .env.prod.example       ← PROD шаблон, Git ✅
│   └── .local-secrets/         ← LOCAL RSA keys, Git ❌
│
├── infra/
│   ├── docker-compose.local.yml
│   ├── docker-compose.yml
│   └── secrets/
│       └── jwt-keys.yml.example ← шаблон RS256, Git ✅
│
└── ...
```



На production-сервере отдельно от проекта:
```text
/etc/safeai/
├── config/
│   └── pricing.yml
└── secrets/
    ├── postgres_bootstrap_password
    ├── db_migrator_password
    ├── db_app_password
    ├── redis_password
    ├── rate_limit_hmac_secret
    ├── openai_api_key
    ├── anthropic_api_key
    ├── knowledge_storage_access_key
    ├── knowledge_storage_secret_key
    └── jwt-keys.yml
```
