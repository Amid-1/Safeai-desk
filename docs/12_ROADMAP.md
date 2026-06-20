# SafeAI Desk — 12_ROADMAP

Актуальная дорожная карта проекта SafeAI Desk на текущий момент.

SafeAI Desk — это full-stack MVP корпоративного AI Gateway: внутренняя платформа между сотрудниками организации и внешними AI-провайдерами. Проект уже имеет рабочий backend, frontend, PostgreSQL, Redis, JWT-auth, роли, админку, чаты, audit, usage tracking и provider abstraction. Дальше задача — довести MVP до безопасного, аккуратного production-like portfolio проекта с multi-tenant security, live AI provider verification, RAG и production deployment.

---

## 1. Цель проекта

Главная цель SafeAI Desk — дать организации контролируемый доступ к AI.

```text
Employees / Admins / Platform Owner
  ↓
React Frontend
  ↓
Spring Boot AI Gateway
  ↓
Auth / RBAC / Organizations / Users / Chat / AI Providers / Audit / Usage / Limits / RAG
  ↓
PostgreSQL + Redis
  ↓
OpenAI / Anthropic / Mock / Future providers
```

Проект решает задачи:

```text
- контролировать, кто имеет доступ к AI;
- разделять пользователей по организациям;
- управлять пользователями и ролями;
- отключать пользователей без удаления истории;
- сбрасывать забытые пароли;
- хранить историю чатов;
- считать usage по токенам и стоимости;
- вести audit событий;
- ограничивать потребление AI через Redis rate limits;
- переключать AI-провайдера через конфигурацию;
- позже подключить документы и RAG;
- подготовить production-like архитектуру для демонстрации на собеседовании.
```

---

## 2. Текущая ролевая модель

Актуальная модель ролей теперь трёхуровневая:

```text
SUPER_ADMIN
  Platform-level администратор.
  Управляет организациями, видит global usage/audit, может создавать первых админов организаций.

ADMIN
  Администратор конкретной организации.
  Управляет пользователями только внутри своей organization.
  Видит usage/audit только своей organization.

USER
  Обычный пользователь.
  Может работать только с chat.
```

Это важное изменение относительно ранней версии проекта, где были только `ADMIN / USER`.

Текущий код уже содержит `SUPER_ADMIN`:

```text
- V9__add_super_admin_role.sql добавляет роль SUPER_ADMIN;
- OrganizationController использует SUPER_ADMIN для создания организаций;
- AdminUsageService частично учитывает SUPER_ADMIN;
- UserService разрешает SUPER_ADMIN как роль.
```

Поэтому дальнейшая цель — не убирать `SUPER_ADMIN`, а официально довести трёхролевую модель до конца во всех слоях.

---

## 3. Текущий статус проекта

Статус:

```text
Core full-stack MVP:              ✅ работает
Backend architecture:             ✅ сильная база
Frontend admin prototype:         ✅ работает как MVP
Redis rate limit foundation:      ✅ уже реализован
AI provider abstraction:          ✅ реализована
Mock provider:                    ✅ работает локально
OpenAI/Anthropic providers:       ⚠️ реализованы, но требуют live verification
Multi-tenant hardening:           ⚠️ частично, есть P0-доработки
Production readiness:             ⚠️ ещё не production
RAG:                              ⏳ pending
Deployment/CI/observability:      ⏳ pending
```

Главный вывод:

```text
Проект уже не на стадии идеи.
Это рабочий full-stack MVP, который теперь нужно стабилизировать по security/multi-tenancy и довести до production-like portfolio уровня.
```

---

## 4. Что уже сделано

### 4.1 Infrastructure

```text
✅ Docker Compose
✅ PostgreSQL 16
✅ Redis 7
✅ PostgreSQL volume
✅ PostgreSQL healthcheck
✅ backend Dockerfile multi-stage build
✅ infra/docker-compose.yml
✅ scripts для локального запуска
```

Текущий инфраструктурный риск:

```text
⚠️ В docker-compose backend должен получать REDIS_HOST=redis.
⚠️ Redis желательно добавить healthcheck.
```

---

### 4.2 Backend foundation

```text
✅ Java 21
✅ Spring Boot 4
✅ Spring Web MVC
✅ Spring Security
✅ OAuth2 Resource Server JWT
✅ Spring Data JPA
✅ Flyway
✅ PostgreSQL
✅ Redis
✅ Maven
✅ Lombok
✅ Actuator health endpoint
✅ Centralized JSON error responses
✅ RequestIdFilter
✅ JSON 401/403 responses
✅ open-in-view=false
✅ ddl-auto=validate
```

---

### 4.3 Database / Flyway

Текущие миграции:

```text
V1__init_schema.sql
V2__seed_roles.sql
V3__seed_demo_admin.sql
V4__use_timestamptz_for_created_at.sql
V5__add_indexes.sql
V6__add_unique_organization_name.sql
V7__add_user_token_version_and_version.sql
V8__add_audit_event_type_index.sql
V9__add_super_admin_role.sql
```

Сделано:

```text
✅ organizations
✅ users
✅ roles
✅ user_roles
✅ chat_sessions
✅ chat_messages
✅ audit_events
✅ usage fields in chat_messages
✅ audit details jsonb
✅ timestamptz for created_at
✅ indexes for users/chats/audit
✅ unique lower(name) for organizations
✅ token_version for JWT invalidation
✅ JPA @Version field for users
✅ SUPER_ADMIN role migration
```

Нужно добавить:

```text
⏳ audit_events.organization_id
⏳ unique lower(email) for users
⏳ indexes for usage aggregation
⏳ check constraints for chat_messages role/tokens/cost
⏳ created_at default now()
```

---

### 4.4 Auth / Security

Сделано:

```text
✅ POST /api/auth/login
✅ GET /api/auth/me
✅ BCrypt password hashing
✅ JWT generation
✅ JWT validation
✅ issuer validation
✅ secret length validation
✅ SafeAiUserPrincipal
✅ SafeAiJwtAuthenticationConverter
✅ CustomUserDetailsService
✅ UserStatusFilter
✅ tokenVersion check on every authenticated request
✅ enabled check on every authenticated request
✅ token cleanup on frontend after 401
✅ method security
```

Важный факт:

```text
Disabled user или пользователь с изменённым tokenVersion уже должен терять доступ по старому JWT.
Это значит, что ранее указанное ограничение "disabled user old JWT works until expiry" устарело.
```

Нужно проверить/доработать:

```text
⏳ SecurityConfig должен официально учитывать SUPER_ADMIN.
⏳ /api/organizations/** должен различать POST для SUPER_ADMIN и GET для ADMIN/SUPER_ADMIN.
⏳ /api/admin/** должен пускать ADMIN и SUPER_ADMIN, а scope решать в сервисах.
⏳ RateLimitUnavailableException должен иметь отдельный handler.
⏳ Login endpoint не должен получать Authorization header от frontend.
```

---

### 4.5 Organization module

Сделано:

```text
✅ OrganizationEntity
✅ CreateOrganizationRequest
✅ OrganizationResponse
✅ OrganizationRepository
✅ OrganizationService
✅ OrganizationController
✅ normalizeName
✅ case-insensitive duplicate check
✅ unique lower(name) in DB
✅ SUPER_ADMIN-only organization creation
✅ ADMIN can read own organization
✅ SUPER_ADMIN can read all organizations
```

Нужно:

```text
⏳ окончательно закрепить SUPER_ADMIN модель в README/security/frontend;
⏳ проверить SecurityConfig для /api/organizations/**;
⏳ добавить pagination/search для организаций позже;
⏳ добавить @Version, когда появятся изменяемые настройки organization.
```

---

### 4.6 User module

Сделано:

```text
✅ UserEntity
✅ RoleEntity
✅ UserRepository
✅ RoleRepository
✅ UserService
✅ UserController
✅ Create user
✅ List users
✅ Get user by id
✅ Enable / disable user
✅ Reset password
✅ Update roles
✅ BCrypt password hashing
✅ tokenVersion increment on enable/disable
✅ tokenVersion increment on role change
✅ tokenVersion increment on password reset
✅ protection from self-disable
✅ protection from removing own ADMIN role
✅ protection from disabling last active ADMIN
✅ protection from removing ADMIN from last active ADMIN
✅ audit events for user-management actions
```

P0/P1 доработки:

```text
⏳ countEnabledAdmins() должен считать админов по organizationId, а не глобально.
⏳ ADMIN должен управлять только пользователями своей organization.
⏳ SUPER_ADMIN может управлять пользователями разных организаций по отдельным правилам.
⏳ findByIdAndOrganizationId лучше заменить на scoped query.
⏳ DataIntegrityViolationException for duplicate email должен возвращать 409.
⏳ users.email должен иметь unique lower(email) index.
⏳ защита последнего ADMIN должна быть race-condition safe для production.
```

---

### 4.7 Chat module

Сделано:

```text
✅ ChatSessionEntity
✅ ChatMessageEntity
✅ ChatMessageRole
✅ Create chat
✅ List own chats
✅ Get own chat details
✅ Send message
✅ Save USER message
✅ Prepare history for AI
✅ Call AiProvider outside long DB transaction
✅ Save ASSISTANT message
✅ Save usage fields
✅ Audit chat events
✅ Ownership check through findByIdAndUser_Id
```

Сильное место:

```text
AI provider call не выполняется внутри длинной DB transaction.
USER message сохраняется до вызова AI provider.
```

Нужно:

```text
⏳ проверить ownership до списания rate limit;
⏳ защититься от параллельной отправки сообщений в один чат;
⏳ сделать stable ordering: createdAt + id или message_index;
⏳ добавить message status: SENT / FAILED / STREAMING позже;
⏳ добавить pagination/windowing для длинных чатов;
⏳ добавить SSE/streaming responses позже.
```

---

### 4.8 AI module

Сделано:

```text
✅ AiProvider interface
✅ AiChatRequest
✅ AiChatResponse
✅ AiMessage
✅ AiProviderProperties
✅ AiProviderSupport
✅ MockAiProvider
✅ OpenAiProvider
✅ AnthropicProvider
✅ AiProviderException
✅ AiProviderTimeoutException
✅ AiRestClientFactory
✅ configurable provider through safeai.ai.provider
✅ timeouts for providers
```

Статус provider-ов:

```text
MockAiProvider:        ✅ рабочий локально
OpenAiProvider:        ⚠️ scaffold, нужен live test
AnthropicProvider:     ⚠️ scaffold, нужен live test
```

Нужно:

```text
⏳ live OpenAI verification;
⏳ live Anthropic verification;
⏳ startup validation when provider=openai/anthropic;
⏳ collect all output_text/text blocks, not only first;
⏳ maxOutputTokens for OpenAI;
⏳ system prompt handling for Anthropic;
⏳ provider retry/backoff policy;
⏳ provider latency logging;
⏳ provider request id/status logging;
⏳ real cost calculation by model pricing.
```

---

### 4.9 Audit module

Сделано:

```text
✅ AuditEventEntity
✅ AuditEventResponse
✅ AuditEventRepository
✅ AuditEventService
✅ AuditEventQueryService
✅ AuditController
✅ AuditEventType enum
✅ jsonb details
✅ pagination through Page/Pageable
✅ EntityGraph for user
✅ REQUIRES_NEW audit writes
✅ audit write failures are logged
```

Event types:

```text
USER_LOGIN_SUCCESS
USER_LOGIN_FAILED
CHAT_CREATED
CHAT_MESSAGE_SENT
AI_RESPONSE_RECEIVED
AI_RESPONSE_FAILED
USER_CREATED
ORGANIZATION_CREATED
USER_ENABLED_CHANGED
USER_ROLES_CHANGED
USER_PASSWORD_RESET
RATE_LIMIT_EXCEEDED
```

P0 проблема:

```text
⚠️ ADMIN сейчас может получить global audit, если endpoint использует findAllByOrderByCreatedAtDesc.
```

Нужно:

```text
⏳ AuditController: hasAnyRole('ADMIN', 'SUPER_ADMIN')
⏳ AuditEventQueryService должен принимать currentUser
⏳ SUPER_ADMIN видит global audit
⏳ ADMIN видит только audit своей organization
⏳ запретить ADMIN смотреть audit userId из другой organization
⏳ добавить organization_id в audit_events
⏳ добавить index audit_events(organization_id, created_at desc)
⏳ ограничить pageable size
⏳ добавить фильтры eventType/from/to
```

---

### 4.10 Admin usage module

Сделано:

```text
✅ AdminUsageController
✅ AdminUsageService
✅ usage summary
✅ usage by users
✅ usage by models
✅ daily usage
✅ usage by userId
✅ usage by organizationId
✅ getUsageSummary(currentUser) уже учитывает SUPER_ADMIN/ADMIN scope
```

P0 проблема:

```text
⚠️ Часть usage endpoints пока global для обычного ADMIN:
- /api/admin/usage/users
- /api/admin/usage/models
- /api/admin/usage/daily
- /api/admin/usage/by-user/{userId}
```

Нужно:

```text
⏳ AdminUsageController: hasAnyRole('ADMIN', 'SUPER_ADMIN')
⏳ во все usage endpoints передавать currentUser
⏳ SUPER_ADMIN видит global usage
⏳ ADMIN видит только usage своей organization
⏳ by-user должен проверять organization текущего ADMIN
⏳ добавить organization-scoped repository queries
⏳ добавить daily usage by organization
⏳ убрать/закрыть no-args global getUsageSummary()
⏳ добавить date range фильтры
⏳ добавить indexes for usage aggregation
```

---

### 4.11 Rate limits

Сделано:

```text
✅ RateLimitExceededException
✅ RateLimitUnavailableException
✅ AiMessageRateLimitProperties
✅ LoginRateLimitService
✅ RedisRateLimitService
✅ AI message limit USER/ADMIN
✅ login email/IP rate limit
✅ RATE_LIMIT_EXCEEDED audit event for AI messages
✅ application.yml contains safeai.rate-limit.ai-messages
```

Текущие лимиты:

```text
USER: 20 AI messages/hour
ADMIN: 100 AI messages/hour
```

Нужно hardening:

```text
⏳ Redis INCR + EXPIRE сделать атомарно через Lua script.
⏳ RateLimitUnavailableException handler → 503 или выбранная policy.
⏳ login rate limit вынести в application.yml.
⏳ hash email/IP в Redis keys.
⏳ добавить Retry-After для 429.
⏳ audit LOGIN_RATE_LIMIT_EXCEEDED или details.type=LOGIN.
⏳ не списывать AI quota до проверки chat ownership.
⏳ добавить organization-level AI limit позже.
```

---

### 4.12 Frontend

Сделано:

```text
✅ React
✅ TypeScript
✅ Vite
✅ React Router
✅ Fetch API wrapper
✅ LoginPage
✅ ChatPage
✅ AdminUsersPage
✅ AdminAuditPage
✅ AdminUsagePage
✅ Protected routes
✅ Admin routes
✅ Token storage in localStorage
✅ Token cleanup on 401
✅ Current user topbar
✅ Role badges
✅ User management actions
✅ User filters
✅ Usage table
✅ Audit table
✅ Vite proxy to backend
```

P0/P1 доработки:

```text
⏳ Audit API mismatch: backend returns Page, frontend expects AuditEvent[].
⏳ Remove hardcoded DEMO_ORGANIZATION_ID.
⏳ Use currentUser.organizationId for ADMIN user creation.
⏳ Add SUPER_ADMIN awareness.
⏳ Do not send Authorization header on /api/auth/login.
⏳ Add authLoading in App/RequireAdmin.
⏳ Protect SUPER_ADMIN users from accidental Make USER.
⏳ Replace reset password prompt with modal/form.
⏳ Add loading guard in ChatPage sendMessage.
⏳ Fix package-lock/package.json if out of sync.
⏳ Clean duplicated CSS.
```

---

## 5. Актуальный список P0-задач

Эти задачи нужно закрыть до дальнейшего расширения функциональности.

### P0.1. Официально закрепить SUPER_ADMIN model

```text
Цель:
Сделать роли SUPER_ADMIN / ADMIN / USER одинаково понятными во всём проекте.

Задачи:
- обновить README и ROADMAP;
- обновить SecurityConfig;
- обновить frontend role helpers;
- обновить admin routes;
- обновить organization/user/admin/audit rules;
- обновить тесты.
```

Acceptance criteria:

```text
✅ SUPER_ADMIN может создавать organizations.
✅ SUPER_ADMIN может видеть global audit/usage.
✅ ADMIN не может видеть чужие organizations/users/audit/usage.
✅ USER не видит admin UI и не проходит backend admin endpoints.
```

---

### P0.2. Исправить tenant isolation в audit

```text
Проблема:
ADMIN может получить global audit.

Что сделать:
- AuditController принимает currentUser.
- AuditEventQueryService фильтрует по роли.
- SUPER_ADMIN → global audit.
- ADMIN → audit только своей organization.
- /users/{userId} должен проверять organization пользователя.
- добавить organization_id в audit_events.
```

Acceptance criteria:

```text
✅ ADMIN Organization A не видит audit Organization B.
✅ SUPER_ADMIN видит audit всех организаций.
✅ audit events без user_id могут быть привязаны к organization_id.
```

---

### P0.3. Исправить tenant isolation в admin usage

```text
Проблема:
Некоторые usage endpoints возвращают global usage обычному ADMIN.

Что сделать:
- во все usage endpoints передавать currentUser;
- SUPER_ADMIN получает global данные;
- ADMIN получает только данные своей organization;
- by-user проверяет organization;
- daily/users/models должны иметь organization-scoped queries.
```

Acceptance criteria:

```text
✅ ADMIN Organization A не видит usage Organization B.
✅ SUPER_ADMIN видит global usage.
✅ by-user чужого пользователя для ADMIN возвращает 404/403.
```

---

### P0.4. Исправить last active ADMIN per organization

```text
Проблема:
countEnabledAdmins() считает админов глобально по всей базе.

Что сделать:
- заменить на countEnabledAdminsByOrganizationId(UUID organizationId);
- использовать эту проверку в disable и role change;
- позже добавить lock для race-condition safety.
```

Acceptance criteria:

```text
✅ Нельзя оставить конкретную organization без активного ADMIN.
✅ Админы других организаций не влияют на проверку.
```

---

### P0.5. Исправить frontend API contracts

```text
Проблемы:
- Audit frontend ожидает массив, backend отдаёт Page.
- organizationId захардкожен.
- frontend не знает SUPER_ADMIN.
- login может отправлять старый Authorization header.

Что сделать:
- PageResponse<T> type;
- getAuditEvents().content;
- currentUser.organizationId вместо DEMO_ORGANIZATION_ID;
- role helpers: hasRole/hasAnyRole/isSuperAdmin/isAdmin;
- apiRequest auth:false для login.
```

Acceptance criteria:

```text
✅ /admin/audit не падает на events.map.
✅ создание user использует organization текущего ADMIN.
✅ SUPER_ADMIN видит корректное меню.
✅ login работает даже при старом/битом token в localStorage.
```

---

### P0.6. Исправить Docker Redis host

```text
Проблема:
В Docker backend по умолчанию ищет Redis на localhost, а должен на redis.

Что сделать:
- backend.environment.REDIS_HOST=redis;
- backend.environment.REDIS_PORT=6379;
- redis healthcheck;
- depends_on.redis.condition=service_healthy.
```

Acceptance criteria:

```text
✅ docker compose --profile full up --build поднимает backend/postgres/redis.
✅ backend подключается к Redis внутри compose.
✅ rate limits работают в Docker.
```

---

## 6. Следующая миграция V10

Рекомендуемая следующая миграция:

```text
V10__harden_multi_tenant_audit_and_constraints.sql
```

Содержимое по смыслу:

```sql
alter table audit_events
    add column organization_id uuid references organizations(id);

update audit_events ae
set organization_id = u.organization_id
from users u
where ae.user_id = u.id
  and ae.organization_id is null;

create index idx_audit_events_organization_id_created_at
    on audit_events (organization_id, created_at desc);

create unique index ux_users_email_lower
    on users (lower(email));

create index idx_chat_messages_role_model_created_at
    on chat_messages (role, model, created_at desc);

create index idx_chat_messages_role_created_at
    on chat_messages (role, created_at desc);

alter table chat_messages
    add constraint chk_chat_messages_role
    check (role in ('USER', 'ASSISTANT', 'SYSTEM'));

alter table chat_messages
    add constraint chk_chat_messages_input_tokens_non_negative
    check (input_tokens is null or input_tokens >= 0);

alter table chat_messages
    add constraint chk_chat_messages_output_tokens_non_negative
    check (output_tokens is null or output_tokens >= 0);

alter table chat_messages
    add constraint chk_chat_messages_cost_usd_non_negative
    check (cost_usd is null or cost_usd >= 0);

update users
set token_version = token_version + 1
where email = 'admin@test.com';
```

После миграции обновить:

```text
- AuditEventEntity;
- AuditEventResponse;
- AuditEventService.record(...);
- AuditEventRepository organization-scoped queries;
- AuditEventQueryService.
```

---

## 7. Roadmap до конца проекта

### Phase 0 — Current MVP stabilization

Статус: **идёт сейчас**

Цель:

```text
Закрыть P0 security/multi-tenant/contract проблемы без расширения функциональности.
```

Задачи:

```text
1. SUPER_ADMIN model finalized.
2. SecurityConfig updated for SUPER_ADMIN.
3. Audit tenant isolation fixed.
4. Admin usage tenant isolation fixed.
5. Last active ADMIN per organization fixed.
6. Frontend API contracts fixed.
7. Frontend SUPER_ADMIN awareness added.
8. Docker Redis host fixed.
9. V10 migration added.
10. Tests updated.
```

Готово, когда:

```text
✅ backend tests pass;
✅ frontend build pass;
✅ manual smoke test pass;
✅ ADMIN cannot see other organization audit/usage;
✅ SUPER_ADMIN can see global audit/usage;
✅ Docker full profile works.
```

---

### Phase 1 — Rate limit hardening

Статус: **следующий после P0**

Цель:

```text
Сделать Redis rate limits безопаснее и production-like.
```

Задачи:

```text
1. Lua script for atomic INCR + EXPIRE.
2. RateLimitUnavailableException handler.
3. Configurable login limits in application.yml.
4. Redis keys without raw email/IP.
5. Retry-After header for 429.
6. Audit for login rate limit exceeded.
7. Check chat ownership before AI quota increment.
8. Add tests for Redis unavailable behavior.
```

Готово, когда:

```text
✅ rate limit не оставляет keys без TTL;
✅ Redis outage возвращает контролируемый ответ;
✅ login brute-force ограничен;
✅ AI message quota работает корректно;
✅ 429 возвращается стабильно и понятно.
```

---

### Phase 2 — AI provider live verification

Статус: **pending**

Цель:

```text
Доказать, что OpenAIProvider и AnthropicProvider реально работают с live API.
```

Задачи:

```text
1. Add provider startup validation.
2. Add OpenAI maxOutputTokens config.
3. Collect all output_text blocks.
4. Collect all Anthropic text blocks.
5. Add Anthropic system prompt handling.
6. Add provider latency logging.
7. Add provider HTTP status logging.
8. Add safe error logging without API keys.
9. Add retry/backoff policy for 429/5xx/timeouts.
10. Add model pricing config and cost calculation.
11. Add provider integration smoke tests behind profile/manual flag.
```

Готово, когда:

```text
✅ SAFEAI_AI_PROVIDER=openai работает с реальным коротким запросом;
✅ SAFEAI_AI_PROVIDER=anthropic работает с реальным коротким запросом;
✅ provider errors превращаются в 502/504;
✅ usage содержит реальные token counts;
✅ costUsd считается не всегда 0.
```

---

### Phase 3 — Frontend hardening

Статус: **pending**

Цель:

```text
Довести frontend от prototype UI до аккуратного admin MVP.
```

Задачи:

```text
1. Add authLoading.
2. Add role helper utilities.
3. Add PageResponse<T>.
4. Add audit pagination.
5. Add users search/filter.
6. Replace reset password prompt with modal.
7. Add organization selector for SUPER_ADMIN.
8. Add empty states.
9. Add table overflow wrappers.
10. Clean duplicated CSS.
11. Add better ApiError fieldErrors rendering.
12. Add 429-specific UI message.
```

Готово, когда:

```text
✅ frontend не ломается на Page responses;
✅ SUPER_ADMIN/ADMIN/USER отображаются корректно;
✅ admin actions имеют нормальный UX;
✅ tables не ломают layout;
✅ ошибки backend показываются понятно.
```

---

### Phase 4 — Admin dashboards and usage analytics

Статус: **pending**

Цель:

```text
Сделать usage/audit полезными для админа, а не просто таблицами.
```

Задачи:

```text
1. Add date range filters.
2. Add usage summary cards.
3. Add usage by day chart.
4. Add usage by model chart.
5. Add usage by user chart.
6. Add cost breakdown.
7. Add provider/model filters.
8. Add CSV export.
9. Add monthly budget fields later.
```

Готово, когда:

```text
✅ ADMIN видит понятную статистику своей organization;
✅ SUPER_ADMIN видит global platform statistics;
✅ usage можно фильтровать по дате/модели/пользователю.
```

---

### Phase 5 — Token revocation / force logout

Статус: **pending**

Важно:

```text
Сейчас tokenVersion уже даёт DB-based invalidation.
Redis revocation нужен как следующий production-like слой.
```

Цель:

```text
Добавить управляемый force logout и session/token invalidation.
```

Варианты:

```text
1. DB tokenVersion only — уже есть.
2. Redis revoked jti list — для точечного logout token.
3. Redis user tokenVersion cache — чтобы не ходить в БД каждый request.
```

Задачи:

```text
1. Add jti claim to JWT.
2. Add tokenVersion cache in Redis or token blacklist.
3. Add admin force logout endpoint.
4. Add logout endpoint.
5. Add frontend logout API call.
6. Add tests.
```

Готово, когда:

```text
✅ admin can force logout user;
✅ old token stops working immediately;
✅ UserStatusFilter no longer needs DB lookup on every request, or lookup is cached.
```

---

### Phase 6 — Document upload

Статус: **pending**

Цель:

```text
Подготовить основу для RAG.
```

Задачи:

```text
1. Add documents table.
2. Add document upload endpoint.
3. Add file validation.
4. Add file size limit.
5. Add document ownership by organization.
6. Add document status: UPLOADED / PROCESSING / READY / FAILED.
7. Add document list UI.
8. Add admin-only document management.
```

Готово, когда:

```text
✅ ADMIN может загрузить документ для своей organization;
✅ документ сохраняется;
✅ USER не может видеть чужие документы;
✅ audit records document upload events.
```

---

### Phase 7 — RAG indexing

Статус: **pending**

Цель:

```text
Индексировать документы для последующего retrieval.
```

Задачи:

```text
1. Add document_chunks table.
2. Add chunking service.
3. Add embedding provider abstraction.
4. Add pgvector or external vector store.
5. Add indexing job.
6. Add reindex endpoint.
7. Add indexing audit events.
8. Add failed indexing retry.
```

Готово, когда:

```text
✅ документы разбиваются на chunks;
✅ chunks получают embeddings;
✅ embeddings сохраняются;
✅ можно искать релевантные chunks по запросу.
```

---

### Phase 8 — RAG retrieval in chat

Статус: **pending**

Цель:

```text
Подмешивать релевантный контекст документов в AI request.
```

Задачи:

```text
1. Retrieve top-K chunks by user message.
2. Check document access by organization.
3. Build context prompt.
4. Add citations/source metadata.
5. Add RAG toggle per chat/organization.
6. Add audit event RAG_CONTEXT_USED.
7. Add frontend display of sources.
```

Готово, когда:

```text
✅ пользователь задаёт вопрос по документам;
✅ backend находит релевантные chunks;
✅ AI отвечает с учётом контекста;
✅ frontend показывает sources.
```

---

### Phase 9 — Production infrastructure

Статус: **pending**

Цель:

```text
Подготовить проект к production-like deployment.
```

Задачи:

```text
1. Add application-prod.yml.
2. Add production Docker Compose.
3. Run backend as non-root Docker user.
4. Add backend healthcheck.
5. Do not expose PostgreSQL/Redis ports in production compose.
6. Add frontend Dockerfile.
7. Add nginx/reverse proxy.
8. Add HTTPS/TLS docs.
9. Add env validation docs.
10. Add backup/restore docs for PostgreSQL.
```

Готово, когда:

```text
✅ проект можно поднять одной командой в production-like режиме;
✅ secrets не попадают в Git;
✅ PostgreSQL/Redis не торчат наружу;
✅ healthchecks работают.
```

---

### Phase 10 — CI/CD and quality gates

Статус: **pending**

Цель:

```text
Сделать проект проверяемым автоматически.
```

Задачи:

```text
1. GitHub Actions backend test.
2. GitHub Actions frontend build.
3. Docker image build.
4. Check formatting/linting.
5. Testcontainers integration tests.
6. Security dependency scan.
7. README validation.
```

Готово, когда:

```text
✅ push/PR запускает backend tests;
✅ push/PR запускает frontend build;
✅ broken build нельзя случайно слить.
```

---

### Phase 11 — Observability and operations

Статус: **pending**

Цель:

```text
Сделать систему наблюдаемой.
```

Задачи:

```text
1. Structured JSON logs.
2. RequestId everywhere.
3. Provider latency metrics.
4. AI errors metrics.
5. Rate limit metrics.
6. Audit write failure metrics.
7. Actuator prometheus endpoint.
8. Grafana dashboard later.
```

Готово, когда:

```text
✅ можно понять, что сломалось;
✅ можно увидеть provider latency/errors;
✅ можно отследить usage/rate limits/audit failures.
```

---

### Phase 12 — Final portfolio polish

Статус: **pending**

Цель:

```text
Сделать проект презентабельным для GitHub, резюме и собеседований.
```

Задачи:

```text
1. Final README.
2. Architecture diagrams.
3. Screenshots.
4. API examples.
5. Demo script.
6. Interview story.
7. Known limitations.
8. Future roadmap.
9. Clean commits.
10. Optional demo video.
```

Готово, когда:

```text
✅ по README понятно, что проект делает;
✅ проект можно быстро запустить;
✅ есть понятная архитектурная история;
✅ можно уверенно объяснить решения на собеседовании.
```

---

## 8. Приоритетный порядок работ на сейчас

Текущий рекомендуемый порядок:

```text
1. Fix SecurityConfig for SUPER_ADMIN.
2. Fix UserService countEnabledAdminsByOrganizationId.
3. Fix AdminUsageController/AdminUsageService tenant scope.
4. Fix AuditController/AuditEventQueryService tenant scope.
5. Add V10 audit_events.organization_id + indexes + constraints.
6. Fix frontend Audit Page PageResponse.
7. Remove DEMO_ORGANIZATION_ID from frontend.
8. Add SUPER_ADMIN role helpers in frontend.
9. Fix docker-compose REDIS_HOST=redis.
10. Run backend tests.
11. Run frontend build.
12. Manual smoke test.
```

После этого:

```text
13. Rate limit hardening.
14. Live OpenAI/Anthropic verification.
15. Frontend UX hardening.
16. RAG foundation.
```

---

## 9. Что не делать сейчас

Чтобы не застрять в бесконечной полировке, сейчас не нужно:

```text
❌ делать красивый UI до закрытия tenant isolation;
❌ добавлять RAG до исправления audit/usage scope;
❌ добавлять charts до date range и scoped usage;
❌ делать deployment до Docker/Redis/security фиксов;
❌ подключать live providers до стабилизации core security;
❌ переписывать всё на микросервисы;
❌ добавлять refresh tokens до ясного session strategy;
❌ делать сложный billing до корректного cost calculation.
```

---

## 10. Manual smoke test после P0 fixes

Проверка SUPER_ADMIN:

```text
1. Login admin@test.com / admin123.
2. Убедиться, что роль SUPER_ADMIN отображается.
3. Создать organization или проверить доступ к /api/organizations.
4. Проверить global usage.
5. Проверить global audit.
```

Проверка ADMIN:

```text
1. Создать отдельную organization.
2. Создать ADMIN внутри этой organization.
3. Login под этим ADMIN.
4. Проверить, что он видит только своих users.
5. Проверить, что он видит только usage своей organization.
6. Проверить, что он видит только audit своей organization.
7. Попробовать открыть чужой userId/orgId вручную.
8. Должен быть 403 или 404.
```

Проверка USER:

```text
1. Login под USER.
2. Видно только Chat.
3. /admin/users недоступен.
4. /api/admin/usage/summary возвращает 403.
5. /api/users возвращает 403.
6. Chat работает.
```

Проверка rate limit:

```text
1. Установить маленький лимит для USER.
2. Отправить сообщения до превышения.
3. Получить 429.
4. Проверить RATE_LIMIT_EXCEEDED в audit.
5. Проверить Retry-After после hardening.
```

Проверка tokenVersion:

```text
1. Login под USER.
2. Admin отключает USER.
3. Старый token USER должен получить 401 TOKEN_REVOKED.
4. Admin меняет роль USER.
5. Старый token должен получить 401 TOKEN_REVOKED.
6. Admin reset password.
7. Старый token должен получить 401 TOKEN_REVOKED.
```

---

## 11. Команды проверки

Backend tests:

```bat
cd /d "D:\Java projects\Safeai-desk\backend"
mvnw.cmd clean test
```

Frontend build:

```bat
cd /d "D:\Java projects\Safeai-desk\frontend"
npm run build
```

Infrastructure:

```bat
cd /d "D:\Java projects\Safeai-desk\infra"
docker compose up -d postgres redis
```

Full Docker profile:

```bat
cd /d "D:\Java projects\Safeai-desk\infra"
docker compose --profile full up --build
```

Health:

```bat
curl -i http://localhost:8080/actuator/health
```

Frontend:

```bat
cd /d "D:\Java projects\Safeai-desk\frontend"
npm run dev
```

---

## 12. Definition of Done для всего проекта

Проект можно считать завершённым как strong portfolio production-like MVP, когда выполнено:

```text
✅ SUPER_ADMIN / ADMIN / USER модель завершена.
✅ Multi-tenant isolation закрыта для users/audit/usage/chat.
✅ Backend tests проходят.
✅ Frontend build проходит.
✅ Docker full profile работает.
✅ Redis rate limits работают и hardened.
✅ OpenAI live verification выполнен.
✅ Anthropic live verification выполнен.
✅ Provider errors обрабатываются корректно.
✅ Usage считает tokens и cost.
✅ Audit привязан к organization.
✅ Frontend корректно показывает роли и ошибки.
✅ README и ROADMAP актуальны.
✅ Есть production-like Docker docs.
✅ Есть CI pipeline.
✅ Есть базовый RAG flow или ясно описанный pending этап.
```

Если RAG будет реализован полностью, финальный статус:

```text
SafeAI Desk becomes an enterprise-style AI Gateway with RBAC, audit, usage analytics, rate limiting, provider abstraction and RAG over organization documents.
```

---

## 13. Формулировка для собеседования

```text
SafeAI Desk — это full-stack MVP корпоративного AI Gateway. Я реализовал backend на Java 21 и Spring Boot 4 с JWT-авторизацией, RBAC, организациями, пользователями, админским управлением пользователей, чатами, audit events, usage tracking и абстракцией AI-провайдера. AI-модуль работает через интерфейс AiProvider, поэтому chat core не зависит от конкретного provider-а. Сейчас есть Mock provider для локальной разработки и configurable scaffolds для OpenAI и Anthropic.

В проекте есть PostgreSQL/Flyway схема, Redis-ready инфраструктура, rate limiting для login и AI messages, tokenVersion-based invalidation, JSON error handling, requestId и React/TypeScript frontend с login, chat, admin users, audit и usage pages.

Текущая архитектурная модель развивается в сторону enterprise SaaS: SUPER_ADMIN управляет платформой и организациями, ADMIN управляет пользователями и usage/audit своей organization, USER работает только с chat. Сейчас фокус — закрыть multi-tenant isolation для audit/usage, hardened Redis rate limits, live-проверку OpenAI/Anthropic и затем добавить RAG по документам организации.
```

---

## 14. Короткий статус для README / GitHub

```text
SafeAI Desk is a full-stack corporate AI Gateway MVP built with Spring Boot, React, PostgreSQL and Redis. It provides JWT authentication, RBAC, organizations, admin user management, chat sessions, AI provider abstraction, audit events, usage tracking and Redis-based rate limit foundation. The project currently supports a local Mock AI provider and configurable OpenAI/Anthropic provider integrations. Next steps are multi-tenant hardening, provider live verification, production deployment and RAG over organization documents.
```
