# SafeAI Desk Roadmap

Roadmap описывает развитие **SafeAI Desk** от текущего production-oriented MVP к полноценной корпоративной AI Gateway платформе.

SafeAI Desk развивается не как «чат с AI», а как внутренняя корпоративная платформа доступа к AI: с multi-tenancy, RBAC, audit logging, usage analytics, rate limits, cost controls, policy controls, RAG и production deployment.

---

## 1. Принцип развития

Главный порядок развития проекта:

```text
1. Зафиксировать текущий MVP как стабильную базу.
2. Довести tenant/user/admin management до безопасного production-like состояния.
3. Подключить и проверить реальные AI providers.
4. Добавить финансовый контроль: usage, pricing, budgets, quotas.
5. Добавить RAG / Knowledge Base.
6. Добавить AI Safety / Policy Engine.
7. Добавить observability, testing, CI/CD и deployment.
8. Расширить до enterprise-функций.
```

Ключевой принцип: сначала надежная security/multi-tenant основа, потом RAG и enterprise-функции.

---

## 2. Текущий статус проекта

### Уже реализовано

```text
Backend:
- Java 21 + Spring Boot backend.
- PostgreSQL + Flyway migrations.
- Redis-backed rate limiting.
- Cookie-based auth через HttpOnly access_token / refresh_token.
- CSRF protection для unsafe methods.
- Refresh token rotation.
- Refresh token reuse detection.
- tokenVersion-based access JWT invalidation.
- Bulk refresh-session revocation при password reset / role change / user disable / organization disable.
- SUPER_ADMIN / ADMIN / USER roles.
- Organization-based multi-tenancy.
- Platform organization protection.
- User management.
- Organization management.
- Chat sessions/messages.
- AI provider abstraction: mock / OpenAI / Anthropic.
- Audit logging.
- Usage analytics.
- Usage module вынесен отдельно от chat.
- Organization snapshot в chat messages для корректного historical usage.
- Login rate limiting.
- AI message rate limiting.
- RequestId support.
- JSON API error format.

Frontend:
- React + TypeScript + Vite.
- Login flow.
- Cookie-auth через fetch credentials include.
- CSRF integration.
- 401 refresh retry.
- Single-flight refresh protection.
- Protected routes.
- Role-based navigation.
- Chat UI.
- Admin Users UI.
- Admin Organizations UI.
- Admin Audit UI.
- Admin Usage UI.
- Modals / confirmation dialogs.
- Success auto-dismiss.
- SafeAI Platform actions hidden in UI.
```

### Текущая модель ролей

```text
SUPER_ADMIN:
- global/platform scope;
- видит все организации;
- создает организации;
- управляет пользователями разных организаций;
- видит global audit;
- видит global usage;
- не создается через обычный user-management endpoint;
- не должен случайно создавать пользователей в SafeAI Platform.

ADMIN:
- organization scope;
- видит только свою организацию;
- видит пользователей только своей организации;
- видит audit только своей организации;
- видит usage только своей организации;
- может пользоваться chat;
- не должен видеть данные чужих организаций.

USER:
- own-resource scope;
- видит только свои чаты;
- отправляет сообщения в свои чаты;
- не видит admin-разделы.
```

---

## 3. Статусы roadmap

```text
DONE        — уже реализовано.
IN PROGRESS — частично реализовано, требуется доводка.
NEXT        — ближайший приоритет.
LATER       — запланировано на будущие фазы.
OPTIONAL    — полезно, но не критично.
```

---

# Phase 0 — MVP Stabilization

**Статус:** IN PROGRESS  
**Цель:** закрепить текущий MVP как стабильную, воспроизводимую, portfolio-ready систему.

## 0.1 Database / Flyway stabilization

**Статус:** IN PROGRESS

Задачи:

```text
- Проверить flyway_schema_history.
- Убедиться, что V1000 local seed применяется только в local profile.
- Зафиксировать, что production profile не использует local demo seed.
- Проверить ddl-auto validate.
- Проверить indexes под users, organizations, refresh_tokens, audit_events, chat_messages, usage queries.
- Проверить constraints для chat_messages:
  - role;
  - status;
  - non-negative token values;
  - non-negative cost.
- Убедиться, что users.email уникален case-insensitive.
```

Acceptance criteria:

```text
- Backend стартует на чистой БД.
- Backend стартует на уже мигрированной БД.
- Flyway history success=true для всех актуальных миграций.
- superadmin@test.com создается только в local profile.
- Hibernate validate проходит.
```

## 0.2 Test baseline

**Статус:** IN PROGRESS

Задачи:

```text
- Прогнать mvnw.cmd test.
- Исправить все failing tests после рефакторинга usage.
- Проверить security controller tests.
- Проверить service tests:
  - AuthService;
  - RefreshTokenService;
  - UserService;
  - OrganizationService;
  - ChatService;
  - ChatPersistenceService;
  - AuditEventService;
  - AuditEventQueryService;
  - UsageQueryService;
  - RateLimitService.
```

Acceptance criteria:

```text
- Все backend tests проходят.
- Нет obsolete references на AdminUsageService.
- Tests соответствуют текущей архитектуре usage.service.
```

## 0.3 Frontend build baseline

**Статус:** NEXT

Задачи:

```text
- Прогнать npm run build.
- Убрать unused imports.
- Проверить строгую типизацию API DTO.
- Убедиться, что frontend не использует localStorage для JWT.
- Проверить production-safe login form без demo password.
```

Acceptance criteria:

```text
- npm run build проходит.
- Нет TypeScript compile errors.
- Нет broken imports.
```

---

# Phase 1 — Tenant Onboarding and User Management Hardening

**Статус:** NEXT  
**Цель:** сделать безопасный tenant onboarding: организация → первый admin → пользователи.

## 1.1 Organization-aware user creation

**Статус:** NEXT

Проблема текущего состояния:

```text
AdminUsersPage создает пользователя с organizationId = currentUser.organizationId.
Для ADMIN это правильно.
Для SUPER_ADMIN это опасно, потому SUPER_ADMIN находится в SafeAI Platform organization.
```

Нужно сделать:

```text
SUPER_ADMIN:
- видит select организации при создании пользователя;
- обязан выбрать client organization;
- не может случайно создать обычного USER/ADMIN в SafeAI Platform.

ADMIN:
- не видит select организации;
- создает пользователей только в своей organizationId.
```

Frontend:

```text
- Добавить загрузку organizations на AdminUsersPage только для SUPER_ADMIN.
- Добавить organization select.
- Заблокировать submit, если SUPER_ADMIN не выбрал organization.
- Исключить SafeAI Platform из списка организаций для создания обычных пользователей.
```

Backend:

```text
- Проверить, что SUPER_ADMIN может createUser в любой non-platform organization.
- Проверить, что ADMIN может createUser только в своей organization.
- Проверить, что ADMIN не может создать пользователя в чужой organization.
- Проверить, что обычный endpoint не позволяет назначить SUPER_ADMIN.
```

Acceptance criteria:

```text
- SUPER_ADMIN создает ADMIN/USER в выбранной client organization.
- ADMIN создает USER только в своей organization.
- Попытка создать USER/ADMIN в SafeAI Platform отклоняется.
- Попытка назначить SUPER_ADMIN через обычный endpoint отклоняется.
```

## 1.2 Role assignment hardening

**Статус:** NEXT

Желаемая production-модель:

```text
SUPER_ADMIN:
- может назначать USER и ADMIN;
- не может назначать SUPER_ADMIN через обычный user-management endpoint.

ADMIN:
- может назначать только USER;
- не может повышать пользователя до ADMIN;
- не может назначать SUPER_ADMIN.
```

Acceptance criteria:

```text
- ADMIN не может создать ADMIN.
- ADMIN не может сделать Make ADMIN.
- SUPER_ADMIN может назначить ADMIN внутри client organization.
- Platform admin защищен от обычных user actions.
```

## 1.3 First admin onboarding

**Статус:** LATER

Flow:

```text
Create organization
↓
Create first admin
↓
Temporary password / invite
↓
Admin login
↓
Admin creates users
```

Acceptance criteria:

```text
- SUPER_ADMIN может быстро onboard-ить новую организацию.
- У организации есть минимум один ADMIN.
- Нет ручного SQL для базового onboarding.
```

---

# Phase 2 — Admin Visibility and Dashboards

**Статус:** IN PROGRESS  
**Цель:** сделать админские экраны полезными не только для проверки, но и для эксплуатации.

## 2.1 Usage dashboard improvements

**Статус:** IN PROGRESS

Уже есть:

```text
- Summary
- By users
- By models
- Daily
- date filters
- model filter для summary
- tenant scope
```

Добавить:

```text
- By organization tab для SUPER_ADMIN.
- User details drill-down.
- Organization details drill-down.
- Period presets:
  - last 24h;
  - last 7 days;
  - last 30 days;
  - current month;
  - custom range.
```

Acceptance criteria:

```text
- ADMIN видит только usage своей организации.
- SUPER_ADMIN видит global usage и может фильтровать по organization.
- UI явно показывает выбранный период.
```

## 2.2 Usage charts

**Статус:** LATER

Charts:

```text
- daily total tokens;
- daily cost;
- tokens by model;
- cost by model;
- top users by cost;
- top organizations by cost for SUPER_ADMIN.
```

Recommended library:

```text
Recharts
```

Acceptance criteria:

```text
- Usage dashboard показывает trends.
- Tables остаются как detailed view.
```

## 2.3 CSV export

**Статус:** LATER

Endpoints:

```http
GET /api/admin/usage/export.csv
GET /api/admin/audit-events/export.csv
```

Rules:

```text
SUPER_ADMIN -> global export or organization-filtered export.
ADMIN -> own organization only.
```

Acceptance criteria:

```text
- Export respects tenant boundaries.
- Export includes date range and filters.
```

---

# Phase 3 — Real AI Provider Verification and Hardening

**Статус:** NEXT  
**Цель:** перейти от mock-first MVP к проверенной integration с реальными AI providers.

## 3.1 OpenAI live verification

**Статус:** NEXT

Задачи:

```text
- Настроить OPENAI_API_KEY.
- Настроить OPENAI_MODEL.
- Запустить backend с safeai.ai.provider=openai.
- Отправить реальный chat message.
- Проверить response parsing.
- Проверить output_text extraction.
- Проверить usage token extraction.
- Проверить cost calculation.
- Проверить audit event.
- Проверить usage dashboard.
```

Acceptance criteria:

```text
- User получает реальный assistant response.
- Assistant message сохраняется.
- Tokens сохраняются.
- Cost считается по configured pricing.
- Usage dashboard показывает реальные данные.
```

## 3.2 Anthropic live verification

**Статус:** LATER

Задачи:

```text
- Настроить ANTHROPIC_API_KEY.
- Настроить ANTHROPIC_MODEL.
- Проверить messages payload.
- Проверить content block parsing.
- Проверить token usage extraction.
- Проверить provider errors.
```

Acceptance criteria:

```text
- Anthropic provider работает через тот же AiProvider interface.
- Provider выбирается конфигурацией.
```

## 3.3 Provider error classification

**Статус:** IN PROGRESS

Уже есть:

```text
AiProviderException
AiProviderRateLimitedException
AiProviderTimeoutException
AiProviderUnavailableException
```

Довести mapping:

```text
400 -> AI_PROVIDER_BAD_REQUEST
401/403 -> AI_PROVIDER_AUTH_ERROR
429 -> AI_PROVIDER_RATE_LIMITED
5xx -> AI_PROVIDER_ERROR / AI_PROVIDER_UNAVAILABLE
timeout -> AI_PROVIDER_TIMEOUT
```

Acceptance criteria:

```text
- Ошибки provider классифицируются понятно.
- Frontend показывает точные сообщения.
- Audit может хранить provider failure type без prompt content.
```

## 3.4 Retry-After support

**Статус:** LATER

Добавить:

```text
- чтение Retry-After header для 429;
- backoff с jitter;
- max attempts;
- не retry 400/401/403.
```

Acceptance criteria:

```text
- Transient failures ретраятся ограниченно.
- Provider auth/config errors не ретраятся.
```

---

# Phase 4 — Pricing, Budgets and Quotas

**Статус:** IN PROGRESS  
**Цель:** превратить usage analytics в управляемый финансовый контур.

## 4.1 Pricing configuration

**Статус:** DONE / IN PROGRESS

Текущее направление:

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

README note:

```text
Cost is an estimate based on configured model pricing.
```

Acceptance criteria:

```text
- Cost не hardcoded в providers.
- Unknown model не ломает chat flow.
- Missing pricing логируется.
```

## 4.2 Organization budgets

**Статус:** LATER

Новые сущности:

```text
organization_ai_budgets
```

Поля:

```text
id
organization_id
monthly_budget_usd
warning_threshold_percent
enabled
created_at
updated_at
```

Поведение:

```text
- при 80% бюджета — warning;
- при 100% бюджета — block или require approval;
- все события пишутся в audit.
```

Acceptance criteria:

```text
- ADMIN видит бюджет своей организации.
- SUPER_ADMIN видит бюджеты всех организаций.
- Budget exceeded блокирует или ограничивает AI usage.
```

## 4.3 Per-user quotas

**Статус:** LATER

Поддержать:

```text
messages per hour
messages per day
tokens per day
cost per month
```

Уровни настройки:

```text
global default
organization override
user override
```

Acceptance criteria:

```text
- Quotas tenant-scoped.
- Violations пишутся в audit.
- User получает понятную ошибку.
```

---

# Phase 5 — Chat UX and Streaming

**Статус:** LATER  
**Цель:** сделать AI interaction современным и удобным.

## 5.1 Streaming endpoint

Endpoint:

```http
POST /api/chats/{id}/messages/stream
```

Варианты:

```text
SSE
Streaming HTTP response
WebSocket
```

Рекомендуемый MVP:

```text
SSE или streaming fetch response
```

Acceptance criteria:

```text
- Assistant response появляется постепенно.
- Failed streaming completion сохраняет FAILED message.
- Usage сохраняется после финализации.
```

## 5.2 Message lifecycle

Уже есть/используется:

```text
COMPLETED
FAILED
```

Дальше добавить:

```text
PENDING
CANCELLED
```

Acceptance criteria:

```text
- UI показывает pending generation.
- User может отменить generation.
- Backend корректно закрывает pending state.
```

## 5.3 Chat management

Добавить:

```text
rename chat
delete/archive chat
search chats
pagination load more
copy message
regenerate assistant response
```

Acceptance criteria:

```text
- Chat UI становится пригодным для повседневного использования.
```

---

# Phase 6 — RAG / Knowledge Base

**Статус:** LATER  
**Цель:** превратить SafeAI Desk в корпоративного knowledge assistant.

## 6.1 Knowledge module

Новый backend module:

```text
knowledge
```

Сущности:

```text
KnowledgeBase
KnowledgeDocument
DocumentChunk
DocumentEmbedding
```

Таблицы:

```text
knowledge_bases
knowledge_documents
knowledge_document_chunks
knowledge_document_embeddings
```

## 6.2 Document upload

MVP formats:

```text
txt
md
pdf
```

Later:

```text
docx
html
csv
xlsx
```

Flow:

```text
ADMIN uploads document
↓
backend stores metadata
↓
extract text
↓
split into chunks
↓
create embeddings
↓
store chunks and vectors
```

## 6.3 Vector search

Recommended MVP:

```text
PostgreSQL + pgvector
```

Search flow:

```text
user question
↓
embedding
↓
top N vector search
↓
build context
↓
send to AI provider
↓
answer with citations
```

## 6.4 RAG tenant isolation

Rules:

```text
SUPER_ADMIN:
- может управлять global/platform knowledge bases;
- может видеть knowledge всех organizations при необходимости.

ADMIN:
- управляет knowledge bases только своей organization.

USER:
- query только knowledge bases своей organization.
```

Acceptance criteria:

```text
- Document upload работает.
- RAG answers содержат citations.
- Tenant isolation сохраняется.
- Prompt/audit не сохраняют raw sensitive content.
```

---

# Phase 7 — AI Safety and Policy Engine

**Статус:** LATER  
**Цель:** сделать SafeAI действительно безопасным AI gateway.

## 7.1 Sensitive data detection

Detect:

```text
emails
phones
password-like strings
API keys
JWT tokens
credit-card-like strings
personal data patterns
```

Actions:

```text
allow
mask
block
audit only
```

## 7.2 Policy rules

Новая сущность:

```text
ai_policy_rules
```

Поля:

```text
id
organization_id
name
description
pattern
category
action
enabled
created_at
updated_at
```

## 7.3 Policy audit

Event types:

```text
AI_POLICY_VIOLATION
AI_PROMPT_BLOCKED
AI_PROMPT_MASKED
```

Details:

```text
policyId
action
matchedCategory
chatId
messageLength
```

Правило:

```text
Raw prompt content в audit не сохранять.
```

## 7.4 Frontend policy management

Страница:

```text
/admin/policies
```

Features:

```text
list rules
create rule
enable/disable rule
view violations
test prompt against policies
```

Acceptance criteria:

```text
- Risky prompt блокируется или маскируется.
- Violation пишется в audit.
- Policies tenant-scoped.
```

---

# Phase 8 — Observability

**Статус:** LATER  
**Цель:** подготовить систему к эксплуатации.

## 8.1 Structured logs

Добавить JSON logs для prod profile.

Поля:

```text
requestId
userId
organizationId
method
path
status
durationMs
exception
```

## 8.2 Metrics

Через Actuator/Micrometer:

```text
http requests
login success/failure
rate limit exceeded
AI request count
AI request latency
AI provider errors
tokens used
estimated cost
refresh token reuse detected
```

## 8.3 Health checks

Проверять:

```text
PostgreSQL
Redis
AI provider configuration
Flyway status
```

## 8.4 Admin System page

Frontend route:

```text
/admin/system
```

Показывать:

```text
backend health
database status
redis status
active provider
build version
last migration
```

Acceptance criteria:

```text
- Operator понимает состояние системы.
- requestId связывает frontend error и backend logs.
```

---

# Phase 9 — Testing Strategy

**Статус:** IN PROGRESS  
**Цель:** повысить уверенность в security boundaries.

## 9.1 Backend security tests

Покрыть:

```text
ADMIN cannot access another organization users
ADMIN cannot create user in another organization
ADMIN cannot assign ADMIN/SUPER_ADMIN if policy forbids it
USER cannot access admin endpoints
USER cannot access another user's chat
ADMIN cannot access another organization audit
ADMIN cannot access another organization usage
SUPER_ADMIN can access global audit/usage
platform organization cannot be renamed/disabled
old JWT rejected after tokenVersion change
refresh token reuse revokes token family
disabled organization blocks users
```

## 9.2 Integration tests

Использовать:

```text
Testcontainers PostgreSQL
Testcontainers Redis
```

Проверить:

```text
Flyway migrations
login flow
refresh rotation
logout
chat flow
rate limit flow
audit persistence
usage aggregation
```

## 9.3 Frontend tests

Possible stack:

```text
Vitest
React Testing Library
Playwright
```

Проверить:

```text
login flow
protected routes
admin navigation
create organization
create user
chat send flow
usage filters
audit filters
```

Acceptance criteria:

```text
- Критичные security boundaries покрыты тестами.
- CI может запускать tests автоматически.
```

---

# Phase 10 — Frontend Architecture Hardening

**Статус:** LATER  
**Цель:** сделать frontend менее prototype-like и удобным для развития.

## 10.1 Component structure

Refactor:

```text
components/layout
components/forms
components/tables
components/errors
components/admin
components/chat
```

## 10.2 TanStack Query

Добавить:

```text
@tanstack/react-query
```

Плюсы:

```text
cache
deduplication
loading states
refetch
mutation handling
optimistic updates
```

## 10.3 Forms

Добавить:

```text
react-hook-form
zod
```

Использовать для:

```text
login
create user
reset password
create organization
usage filters
audit filters
policy forms
document upload
```

## 10.4 UX improvements

Добавить:

```text
toasts
responsive tables
empty states
skeleton loaders
better date formatting
copy buttons
keyboard shortcuts
dark/light mode toggle
```

Acceptance criteria:

```text
- UI проще поддерживать.
- Forms имеют единый validation style.
- API states управляются единообразно.
```

---

# Phase 11 — CI/CD and Deployment

**Статус:** LATER  
**Цель:** сделать проект deployable.

## 11.1 GitHub Actions

Jobs:

```text
backend-test
frontend-build
docker-build
migration-validation
```

## 11.2 Docker Compose full stack

Services:

```text
frontend
backend
postgres
redis
nginx
```

## 11.3 Nginx reverse proxy

Responsibilities:

```text
serve frontend static files
proxy /api to backend
SPA fallback
TLS termination
gzip/brotli
security headers
```

## 11.4 Environment profiles

Profiles:

```text
local
test
docker
prod
```

Acceptance criteria:

```text
- One command запускает full stack.
- CI проверяет backend и frontend.
- Deployment configuration документирована.
```

---

# Phase 12 — Enterprise Features

**Статус:** LATER

Будущие функции:

```text
SSO / OAuth2 login
SCIM user provisioning
organization invitations
email notifications
temporary password flow
admin approval workflows
model allowlists
provider failover
multi-provider routing
department-level budgets
team workspaces
chat sharing
conversation export
data retention policies
legal hold
audit export
knowledge base permissions
DLP integrations
SIEM export
```

---

# Immediate Implementation Order

Ближайшие практические шаги:

```text
1. Прогнать backend tests и frontend build.
2. Доделать organization-aware user creation для SUPER_ADMIN.
3. Запретить ADMIN создавать/назначать ADMIN, если выбираем strict production модель.
4. Добавить tests для platform organization protection.
5. Добавить tests для SUPER_ADMIN/ADMIN user creation boundaries.
6. Live-verify OpenAI provider.
7. Проверить pricing для real model usage.
8. Добавить By Organization usage tab для SUPER_ADMIN.
9. Добавить базовые usage charts.
10. Подготовить CI pipeline.
```

---

# Feature Priority Matrix

## High impact / low complexity

```text
organization-aware user creation
role assignment hardening
platform organization protection tests
usage by organization tab
OpenAI live verification
frontend build cleanup
README/ROADMAP screenshots
```

## High impact / medium complexity

```text
security integration tests
usage charts
CSV export
organization budgets
provider-specific error mapping
Retry-After support
CI pipeline
```

## High impact / high complexity

```text
RAG Knowledge Base
pgvector integration
document parsing
AI policy engine
streaming responses
production deployment
SSO
SCIM
```

## Nice to have

```text
toasts
skeleton loading
dark mode
admin system status page
chat search
message copy
conversation export
```

---

# Long-Term Vision

SafeAI Desk должен развиться в:

```text
Secure corporate AI Gateway для организаций с RBAC, tenant isolation, audit, usage analytics, budgets, quotas, policy engine, RAG knowledge base, provider abstraction и production deployment.
```

Финальная платформа должна поддерживать:

```text
multiple organizations
multiple AI providers
organization-specific policies
organization-specific budgets
document knowledge bases
RAG chat
admin analytics
security audit
safe prompt processing
streaming chat
CI/CD
observability
enterprise integrations
```

Portfolio positioning:

> SafeAI Desk demonstrates production-oriented backend security architecture for a multi-tenant corporate AI Gateway: RBAC, JWT lifecycle management, refresh rotation, auditability, usage analytics, cost estimation, provider abstraction and a clear path toward RAG and AI safety.
