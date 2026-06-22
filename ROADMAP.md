# SafeAI Desk Roadmap

Этот roadmap описывает развитие SafeAI Desk: от текущего production-oriented MVP к полноценной корпоративной AI Gateway платформе.

Roadmap разбит на фазы. Каждая фаза добавляет business value, technical depth и portfolio/interview value.

---

## Roadmap Philosophy

SafeAI Desk должен развиваться в таком порядке:

```text
1. Stabilize existing MVP
2. Improve admin visibility and tenant management
3. Connect real AI provider
4. Add cost control and budgets
5. Add RAG / Knowledge Base
6. Add AI safety and policy controls
7. Add production deployment and CI/CD
8. Add enterprise-level capabilities
```

Не стоит прыгать сразу в RAG, пока не вычищены текущие security/admin/tenant foundations.

---

## Phase 0 — MVP Stabilization

Цель: сделать текущий проект чистым, воспроизводимым и portfolio-ready.

Приоритет: highest.

### 0.1 Очистка database migrations

Задачи:

```text
- Проверить Flyway migration history.
- Убедиться, что миграция audit organization_id применена.
- Добавить check constraints для chat_messages.
- Исправить BCrypt password для platform superadmin.
- Убрать redundant users.email unique constraint, если используется lower(email) unique index.
- Убрать unique=true из UserEntity.email.
- Проверить, что Hibernate ddl-auto validate проходит.
```

Acceptance criteria:

```text
- Backend стартует без ошибок.
- flyway_schema_history показывает successful migrations.
- superadmin@test.com может залогиниться.
- users.email уникален case-insensitive.
- chat_messages отклоняет invalid roles и negative token/cost values.
```

### 0.2 Frontend package hygiene

Задачи:

```text
- Перенести vite, typescript и @vitejs/plugin-react в devDependencies.
- Перегенерировать package-lock.json.
- Проверить npm run build.
- Проверить реальные имена файлов LoginPage.tsx и AdminAuditPage.tsx.
- Исправить API typings для fieldErrors.
- Добавить organizationId в AuditEvent type.
```

Acceptance criteria:

```text
- npm install создает корректный lock file.
- npm run build проходит.
- Нет broken imports.
- Frontend API types соответствуют backend DTO.
```

### 0.3 Documentation

Задачи:

```text
- Добавить README.md.
- Добавить ROADMAP.md.
- Добавить architecture overview.
- Добавить local development instructions.
- Добавить default demo flow.
- Добавить production gaps section.
```

Acceptance criteria:

```text
- Новый разработчик понимает проект по README.
- Interviewer видит architecture и roadmap.
- Project positioning сформулирован явно.
```

---

## Phase 1 — Organization Management UI

Цель: показать multi-tenant organization model во frontend.

Приоритет: high.

### 1.1 Organization API client

Добавить frontend API module:

```text
frontend/src/api/organizationApi.ts
```

Functions:

```text
getOrganizations()
getOrganizationById(id)
createOrganization(request)
```

Types:

```text
Organization
CreateOrganizationRequest
```

### 1.2 Organizations page

Добавить страницу:

```text
frontend/src/pages/AdminOrganizationsPage.tsx
```

Route:

```text
/admin/organizations
```

Navigation:

```text
Topbar -> Organizations
```

Visibility:

```text
SUPER_ADMIN only для create/list all
ADMIN может видеть только свою organization, если страница переиспользуется
```

UI:

```text
- organization list
- organization name
- createdAt
- create organization form
- organization details link
```

Acceptance criteria:

```text
- SUPER_ADMIN создает organizations из UI.
- SUPER_ADMIN видит все organizations.
- ADMIN не может создавать organizations.
- ADMIN не получает global organization list.
```

### 1.3 Organization details page

Опционально, но полезно:

```text
/admin/organizations/{id}
```

Показывать:

```text
- organization info
- users in organization
- usage by organization
- audit events for organization
```

Эта страница визуально усилит multi-tenant модель.

---

## Phase 2 — Improved Admin Usage Dashboard

Цель: превратить usage из простой таблицы в полноценный admin dashboard.

Приоритет: high.

### 2.1 Frontend usage tabs

Текущая страница показывает только summary.

Добавить вкладки:

```text
Summary
By Users
By Models
Daily
By Organization
```

Backend уже имеет часть endpoints.

Frontend API additions:

```text
getUsageByUsers()
getUsageByModels()
getUsageDaily()
getUsageByOrganizationId(organizationId)
getUsageByUserId(userId)
```

Acceptance criteria:

```text
- ADMIN видит только organization-scoped usage.
- SUPER_ADMIN видит global usage.
- Данные сгруппированы понятно.
```

### 2.2 Date filters

Backend enhancement:

```text
dateFrom
dateTo
```

Применить к:

```text
usage summary
usage by users
usage by models
usage daily
usage by user
usage by organization
```

Frontend:

```text
- date input/date picker
- Apply filters button
- Reset filters button
```

Acceptance criteria:

```text
- Admin видит usage за выбранный date range.
- Queries сохраняют tenant scope.
```

### 2.3 Charts

Возможные charts:

```text
daily total tokens
daily cost
tokens by model
tokens by user
cost by user
```

Library options:

```text
Recharts
Nivo
Chart.js
```

Acceptance criteria:

```text
- Usage dashboard визуально показывает trends.
- Tables остаются для details.
```

### 2.4 CSV export

Endpoint:

```text
GET /api/admin/usage/export.csv
```

Scope:

```text
SUPER_ADMIN -> global export
ADMIN -> own organization only
```

Acceptance criteria:

```text
- Admin может экспортировать usage data.
- Export respects tenant boundaries.
```

---

## Phase 3 — Real AI Provider Hardening

Цель: перейти от mock-first MVP к проверенной real provider integration.

Приоритет: high.

### 3.1 Live verification OpenAI provider

Задачи:

```text
- Настроить OPENAI_API_KEY.
- Настроить OPENAI_MODEL.
- Отправить live request.
- Проверить request payload.
- Проверить response parsing.
- Проверить usage token extraction.
- Проверить error handling.
```

Acceptance criteria:

```text
- User может отправить message через OpenAI provider.
- Assistant response сохраняется.
- Tokens сохраняются.
- Audit event пишется.
- Usage page показывает real token usage.
```

### 3.2 OpenAI max output tokens

Backend config:

```text
safeai.ai.openai.max-output-tokens
```

Provider payload:

```text
max_output_tokens
```

Acceptance criteria:

```text
- Response length контролируется через config.
- Default безопасен для local/demo usage.
```

### 3.3 Live verification Anthropic provider

Задачи:

```text
- Настроить ANTHROPIC_API_KEY.
- Настроить ANTHROPIC_MODEL.
- Проверить messages payload.
- Проверить content block parsing.
- Проверить token usage extraction.
```

Acceptance criteria:

```text
- Anthropic provider работает с real API key.
- Provider выбирается через configuration.
```

### 3.4 Provider-specific error classification

Текущее состояние:

```text
AiProviderException -> 502
AiProviderTimeoutException -> 504
```

Добавить:

```text
AiProviderRateLimitedException
AiProviderUnauthorizedException
AiProviderBadRequestException
```

Mapping:

```text
429 -> AI_PROVIDER_RATE_LIMITED
401/403 -> AI_PROVIDER_AUTH_ERROR
400 -> AI_PROVIDER_BAD_REQUEST
5xx -> AI_PROVIDER_ERROR
timeout -> AI_PROVIDER_TIMEOUT
```

Acceptance criteria:

```text
- Provider rate limit отличается от provider failure.
- Frontend может показать более точное сообщение.
- Audit может записать provider failure type.
```

### 3.5 Retry and backoff

Добавить retry только для transient failures:

```text
429
500
502
503
504
network timeout
```

Не retry:

```text
400
401
403
validation errors
```

Implementation options:

```text
Spring Retry
Resilience4j
manual retry wrapper
```

Acceptance criteria:

```text
- Transient provider failure может восстановиться.
- Request не ретраится бесконечно.
- Retry attempts логируются.
```

---

## Phase 4 — AI Cost Calculation

Цель: сделать usage analytics финансово осмысленной.

Приоритет: high.

### 4.1 Pricing configuration

Добавить config:

```yaml
safeai:
  ai:
    pricing:
      models:
        gpt-4.1:
          input-usd-per-1m-tokens: 0.00
          output-usd-per-1m-tokens: 0.00
        claude-sonnet:
          input-usd-per-1m-tokens: 0.00
          output-usd-per-1m-tokens: 0.00
```

Не hardcode цены прямо в provider classes.

### 4.2 AiCostCalculator

Новый component:

```text
AiCostCalculator
AiPricingProperties
```

Input:

```text
model
inputTokens
outputTokens
```

Output:

```text
BigDecimal costUsd
```

Acceptance criteria:

```text
- Cost считается одинаково для всех providers.
- Usage dashboard показывает non-zero costs, если pricing configured.
```

### 4.3 Unknown model fallback

Если цена модели отсутствует:

```text
cost = 0
details contains pricingMissing=true
log warning
```

Acceptance criteria:

```text
- Unknown models не ломают chat flow.
- Missing pricing видно в logs/audit.
```

---

## Phase 5 — Organization Budgets and Quotas

Цель: добавить financial и operational controls.

Приоритет: medium-high.

### 5.1 Organization-level AI budget

Добавить table:

```text
organization_ai_budgets
```

Possible fields:

```text
id
organization_id
monthly_budget_usd
enabled
created_at
updated_at
```

Behavior:

```text
if estimated monthly cost >= budget -> block or warn
```

### 5.2 Per-user quotas

Добавить поддержку:

```text
messages per hour
messages per day
tokens per day
cost per month
```

Configuration levels:

```text
global default
organization override
user override
```

### 5.3 Budget exceeded audit events

Добавить event types:

```text
ORGANIZATION_BUDGET_WARNING
ORGANIZATION_BUDGET_EXCEEDED
USER_QUOTA_EXCEEDED
```

Acceptance criteria:

```text
- Admin видит budget/quota violations.
- User получает понятную ошибку.
- Audit записывает событие.
```

---

## Phase 6 — Streaming AI Responses

Цель: улучшить chat UX и сделать AI interaction современнее.

Приоритет: medium.

### 6.1 Backend streaming endpoint

Endpoint:

```http
POST /api/chats/{id}/messages/stream
```

Options:

```text
Server-Sent Events
WebSocket
Streaming fetch response
```

Recommended MVP:

```text
SSE или streaming HTTP response
```

### 6.2 Frontend streaming UI

Behavior:

```text
- user sends message
- assistant message appears as pending
- text streams token by token/chunk by chunk
- final token usage saved after completion
```

### 6.3 Message status

Добавить message status:

```text
PENDING
COMPLETED
FAILED
```

Это также решит текущую ситуацию, когда AI failure оставляет user message без структурированного failed assistant response.

Acceptance criteria:

```text
- User видит, как assistant text появляется постепенно.
- Failed generations отображаются как failed messages.
- Usage сохраняется после completion.
```

---

## Phase 7 — RAG Knowledge Base

Цель: превратить SafeAI Desk из chat gateway в company knowledge assistant.

Приоритет: high после core MVP hardening.

### 7.1 Knowledge base domain

Новый module:

```text
knowledge
```

Entities:

```text
KnowledgeBase
Document
DocumentChunk
Embedding
```

Possible tables:

```text
knowledge_bases
documents
document_chunks
document_embeddings
```

### 7.2 Document upload

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
backend stores metadata
backend extracts text
backend splits into chunks
backend creates embeddings
backend stores chunks and vectors
```

### 7.3 Vector search

Recommended database:

```text
PostgreSQL + pgvector
```

Почему:

```text
- стек остается простым
- хорошо для portfolio
- не нужен отдельный vector database на первом этапе
```

Search flow:

```text
user question
-> embedding
-> vector similarity search
-> top N chunks
-> build context
-> send to AI provider
```

### 7.4 RAG chat mode

Добавить chat mode:

```text
general chat
knowledge-based chat
```

Или выбор knowledge base для конкретного чата.

### 7.5 Citations

Assistant responses должны содержать references:

```text
document title
chunk number
source file
```

Acceptance criteria:

```text
- Admin загружает documents.
- User задает вопросы по uploaded knowledge.
- Assistant отвечает с citations.
- Tenant isolation сохраняется.
```

### 7.6 RAG tenant isolation

Critical rules:

```text
ADMIN управляет knowledge bases только внутри своей organization.
USER query только knowledge bases своей organization.
SUPER_ADMIN управляет platform/global knowledge bases.
```

---

## Phase 8 — AI Safety and Policy Engine

Цель: сделать название SafeAI осмысленным.

Приоритет: high после RAG или параллельно.

### 8.1 Sensitive data detection

Detect and optionally mask:

```text
emails
phone numbers
API keys
JWT tokens
password-like strings
credit card-like patterns
personal data
```

Actions:

```text
allow
mask
block
audit only
```

### 8.2 Prompt policy rules

Добавить configurable policies:

```text
block secrets
block harmful content
block source code exfiltration
block private customer data
block uploading confidential docs to external providers
```

Possible entity:

```text
ai_policy_rules
```

Fields:

```text
id
organization_id
name
pattern
action
enabled
created_at
```

### 8.3 Policy violation audit

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

Raw sensitive prompt content в audit не сохранять.

### 8.4 Frontend policy management

Admin page:

```text
/admin/policies
```

Features:

```text
list rules
create rule
enable/disable rule
view violations
```

Acceptance criteria:

```text
- Policy engine блокирует или маскирует risky prompts.
- Violations записываются в audit.
- Rules tenant-scoped.
```

---

## Phase 9 — Frontend Hardening

Цель: сделать frontend менее prototype-like.

Приоритет: medium.

### 9.1 Component structure

Refactor into:

```text
components/layout
components/forms
components/tables
components/errors
components/admin
components/chat
```

### 9.2 Data fetching library

Добавить:

```text
TanStack Query
```

Benefits:

```text
cache
loading states
refetch
error handling
mutation handling
optimistic updates
```

### 9.3 Form validation

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
policy forms
document upload
```

### 9.4 Better UX

Добавить:

```text
toasts
modals
confirmation dialogs
empty states
date formatting
relative time
active nav item
responsive tables
```

### 9.5 Replace prompt-based password reset

Current:

```text
window.prompt
```

Planned:

```text
modal form with password confirmation
validation
success/error toast
```

Acceptance criteria:

```text
- Admin UI выглядит осознанно.
- User actions имеют consistent feedback.
- Forms валидируются до submit.
```

---

## Phase 10 — Authentication Hardening

Цель: приблизиться к production-grade browser security.

Приоритет: medium.

### 10.1 Move away from localStorage token

Current:

```text
JWT stored in localStorage
```

Production risk:

```text
XSS can steal token
```

Planned:

```text
HttpOnly Secure SameSite cookie
```

### 10.2 Refresh token flow

Добавить:

```text
short-lived access token
longer-lived refresh token
refresh token rotation
refresh token reuse detection
```

### 10.3 Logout endpoint

Добавить:

```http
POST /api/auth/logout
```

Behavior:

```text
invalidate refresh token
optionally bump tokenVersion for forced logout
```

### 10.4 Login device/session tracking

Добавить:

```text
user_sessions
```

Fields:

```text
id
user_id
refresh_token_hash
user_agent
ip
created_at
expires_at
revoked_at
```

Acceptance criteria:

```text
- Tokens безопаснее в browser context.
- User sessions можно revokе.
- Security audit получает session visibility.
```

---

## Phase 11 — Organization Lifecycle

Цель: поддержать реальные tenant operations.

Приоритет: medium.

### 11.1 Organization status

Добавить:

```text
enabled
archived
```

Behavior:

```text
disabled organization -> users cannot login/use AI
archived organization -> read-only historical data
```

### 11.2 Organization settings

Possible settings:

```text
default AI provider
allowed models
monthly budget
rate limits
policy rules
knowledge base settings
```

### 11.3 Organization admin assignment

Улучшить onboarding:

```text
Create organization
-> create first admin
-> send temporary password/invite
```

Acceptance criteria:

```text
- SUPER_ADMIN может корректно onboarding a new tenant.
- Disabled organization blocks access.
```

---

## Phase 12 — Observability

Цель: облегчить эксплуатацию системы.

Приоритет: medium.

### 12.1 Structured logs

Добавить JSON logging для production profile.

Include:

```text
requestId
userId
organizationId
path
method
status
durationMs
```

### 12.2 Metrics

Expose через Actuator/Micrometer:

```text
AI request count
AI request latency
AI errors by provider
rate limit exceeded count
login success/failure count
usage tokens
```

### 12.3 Health checks

Добавить health indicators:

```text
PostgreSQL
Redis
AI provider configuration
```

### 12.4 Admin system status page

Frontend page:

```text
/admin/system
```

Show:

```text
backend health
database status
redis status
active provider
build version
```

Acceptance criteria:

```text
- Operator видит system health.
- Logs and metrics помогают diagnose issues.
```

---

## Phase 13 — Testing Strategy

Цель: повысить confidence и показать engineering maturity.

Приоритет: medium-high.

### 13.1 Backend unit tests

Покрыть:

```text
UserService
OrganizationService
AuditEventQueryService
AdminUsageService
RateLimitService
AiCostCalculator
PolicyEngine
```

### 13.2 Security tests

Проверить:

```text
ADMIN cannot access another organization users
ADMIN cannot access another organization audit
ADMIN cannot access another organization usage
USER cannot access admin endpoints
USER cannot access another user's chat
old JWT rejected after tokenVersion change
disabled user rejected
```

### 13.3 Integration tests

Использовать Testcontainers:

```text
PostgreSQL
Redis
```

Проверить:

```text
Flyway migrations
JWT login flow
chat flow
rate limit flow
audit persistence
```

### 13.4 Frontend tests

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
create user form
chat send flow
```

Acceptance criteria:

```text
- Critical security boundaries покрыты tests.
- CI может запускать tests автоматически.
```

---

## Phase 14 — CI/CD and Deployment

Цель: сделать проект deployable.

Приоритет: medium.

### 14.1 GitHub Actions

Pipeline:

```text
backend test
frontend build
docker build
migration validation
```

Example jobs:

```text
backend-ci
frontend-ci
docker-ci
```

### 14.2 Docker Compose production profile

Services:

```text
frontend
backend
postgres
redis
nginx
```

### 14.3 Nginx reverse proxy

Responsibilities:

```text
serve frontend static files
proxy /api to backend
proxy /actuator health if needed
SPA fallback
gzip/brotli
TLS termination
```

### 14.4 Environment profiles

Profiles:

```text
local
test
docker
prod
```

Acceptance criteria:

```text
- One command запускает full stack locally.
- CI проверяет backend и frontend.
- Deployment configuration документирована.
```

---

## Phase 15 — Enterprise Features

Цель: приблизиться к реальной internal AI platform.

Приоритет: later.

Possible features:

```text
SSO / OAuth2 login
SCIM user provisioning
organization invitations
email notifications
admin approval workflows
model allowlists
department-level budgets
chat sharing
team workspaces
conversation export
data retention policies
legal hold
audit export
knowledge base permissions
provider failover
multi-provider routing
```

---

## Suggested Immediate Implementation Order

Следующие 10 практических шагов:

```text
1. Finish migration cleanup.
2. Fix frontend package hygiene and API typings.
3. Add Organizations frontend page.
4. Add Usage dashboard tabs.
5. Add date filters for usage.
6. Live-verify OpenAI provider.
7. Add maxOutputTokens for OpenAI.
8. Add AiCostCalculator.
9. Add backend security tests for tenant isolation.
10. Add README screenshots and demo flow.
```

---

## Feature Priority Matrix

### High impact / low complexity

```text
Organizations UI
Usage dashboard tabs
Frontend type fixes
Migration cleanup
OpenAI maxOutputTokens
AiCostCalculator basic version
README and architecture docs
```

### High impact / medium complexity

```text
date filters for usage
real OpenAI provider verification
security integration tests
organization budgets
policy violation audit
streaming responses
```

### High impact / high complexity

```text
RAG Knowledge Base
pgvector integration
document upload and parsing
HttpOnly cookie + refresh token flow
CI/CD deployment
policy engine
```

### Nice to have

```text
charts
CSV export
toasts
modal forms
dark theme
active nav item
admin system status page
```

---

## Long-Term Vision

SafeAI Desk может развиться в:

```text
Secure internal AI Gateway для организаций с RBAC, tenant isolation, audit, budgets, safety policies, document-based RAG и provider abstraction.
```

В финальном виде система должна поддерживать:

```text
multiple organizations
multiple AI providers
organization-specific policies
organization-specific budgets
document knowledge bases
admin analytics
security audit
safe prompt processing
streaming chat
production deployment
CI/CD
```

Самое сильное portfolio positioning:

> SafeAI Desk is a corporate AI Gateway that demonstrates backend security architecture, multi-tenant access control, operational auditability, usage analytics, and extensible AI provider integration.
