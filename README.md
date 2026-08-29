SafeAI Desk

SafeAI Desk — production-oriented full-stack корпоративная AI-платформа / AI Gateway для безопасного использования 
внешних AI-моделей внутри организаций.

Актуальность: 28 августа 2026.
Документ описывает фактически реализованный baseline текущих backend/frontend исходников и Flyway-миграций до V45 
включительно.
Будущее развитие вынесено в ROADMAP.md.

SafeAI Desk — не UI-обёртка над LLM. Проект строит управляемый слой вокруг корпоративного AI:

organization-based multi-tenancy;

RBAC SUPER_ADMIN / ADMIN / USER;

HttpOnly cookie JWT authentication + CSRF;

строгую JWT validation и security revocation epochs;

refresh-token rotation/reuse detection;

tenant-safe user/organization management;

durable ChatTurn state machine с idempotency, lease и fencing;

защиту от повторного provider call при неопределённом исходе;

Redis rate limiting;

transactional audit outbox;

immutable actor/target audit snapshots;

usage/pricing quality model без подмены неизвестных данных нулём;

OpenAI / Anthropic / mock provider abstraction;

Knowledge/RAG platform: Knowledge Bases, immutable document versions, ingestion, object storage, extraction, embeddings, 
hybrid retrieval, citations и Answer Passport;

V45 Model Control Plane: versioned Model Catalog, organization model policies и immutable model-route evidence;

PostgreSQL/Flyway constraints как часть integrity model;

React frontend с runtime validation API contracts, abortable requests и production error handling.

Текущий статус: production-oriented pre-1.0 / portfolio-ready baseline. Это серьёзная база B2B-продукта, но не заявление 
compliance certification, billing-grade accounting, формально доказанной HA/SLA или полном multi-provider data plane.

Содержание

Что решает проект

Архитектура

Стек

Роли

Multi-tenancy и integrity

Security architecture

Backend-модули

ChatTurn

AI providers

Knowledge / RAG

Model Control Plane — V45

Rate limiting

Audit

Usage

User management

Organization management

Frontend

API

Database / Flyway

Local development

Проверка

Production notes

Текущие границы

Positioning

Следующий этап

Что решает проект

Корпоративный AI требует отвечать не только на «что ответила модель», но и на:

Кто сделал запрос?
Из какой организации?
Какие у него права?
Активен ли user и tenant?
Не устарела ли access-сессия?
Какие корпоративные знания разрешено использовать?
Какая версия документа попала в evidence?
Какая модель была разрешена policy в момент запроса?
Можно ли безопасно повторить AI operation?
Сколько было токенов и каково качество pricing data?
Кто изменил security/governance state?
Что произошло при crash во время provider I/O?

SafeAI Desk объединяет:

Identity
+ Tenant isolation
+ Session security
+ Durable chat operations
+ Knowledge / Retrieval provenance
+ Model governance
+ Rate limits / quotas
+ Provider abstraction
+ Usage / pricing quality
+ Audit trail

Архитектура

Проект — модульный монолит. Это сознательно сохраняет сильные transactional/tenant/security invariants без преждевременной микросервисной сложности.

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
┌──────────────────────────────────────────────────┐
│ Spring Boot Backend                              │
│ auth / common / user / org / model / chat / ai  │
│ knowledge / audit / usage / ratelimit            │
└───────┬──────────────────────┬───────────────────┘
        │                      │
        ▼                      ▼
┌─────────────┐         ┌──────────────┐
│ PostgreSQL  │         │ Redis        │
│ + Flyway    │         │ rate limits │
│ source of   │         │ coordination│
│ truth       │         │ optional    │
└──────┬──────┘         └──────────────┘
       │
       ├──────────────► S3-compatible object storage
       │                 Knowledge documents
       │
       │ provider call outside long DB transaction
       ▼
┌──────────────────────────────┐
│ Mock / OpenAI / Anthropic    │
└──────────────────────────────┘

PostgreSQL — source of truth. Redis — coordination/rate-limit/cache layer. Security correctness не должна зависеть 
только от Redis cache.

V45 не превращает текущий runtime в полноценный dynamic multi-provider executor: catalog/policy принимают governance 
decision, но ALLOWED route пока обязан совпадать с физически активным provider/model adapter текущего deployment.

Стек

Backend

Java 21;

Spring Boot 4.1.0;

Spring Web MVC;

Spring Security;

Spring Data JPA / Hibernate;

PostgreSQL;

Flyway;

Redis;

Maven Wrapper;

Bean Validation;

Lombok;

JUnit 5 / Mockito / Spring Test;

Testcontainers;

MinIO / S3-compatible storage;

PDFBox / Apache POI / jsoup;

Micrometer / Prometheus;

structured JSON logging;

Docker / Docker Compose.

Точные версии определяет backend/pom.xml.

Frontend

По текущему package.json:

React 19;

React DOM 19;

React Router;

TypeScript 6;

Vite 8;

Vitest;

Testing Library;

ESLint + TypeScript ESLint;

eslint-plugin-jsx-a11y;

Recharts;

Node.js 24;

npm 11.

Frontend работает в strict TypeScript mode и имеет отдельные typecheck, lint, test, coverage, build, ci.

Роли

У пользователя должна быть ровно одна системная роль:

SUPER_ADMIN
ADMIN
USER

Это поддерживается backend policy, frontend runtime parser и БД.

SUPER_ADMIN

Platform/control-plane administrator:

создаёт/просматривает tenant organizations;

управляет обычными ADMIN/USER;

видит global audit и usage;

управляет глобальным Model Catalog;

импортирует физический runtime model в catalog;

задаёт/просматривает organization model policies;

читает immutable route decisions.

Через ordinary user-management flow не создаёт и не изменяет SUPER_ADMIN.

Текущий frontend рассматривает SUPER_ADMIN как control-plane роль и не выдаёт ей Chat route.

ADMIN

Tenant administrator:

работает с chat;

управляет USER своей организации;

управляет Knowledge Bases в разрешённом tenant scope;

видит tenant audit/usage;

видит Model Catalog/runtime;

читает и изменяет model policy своей организации;

читает route evidence только своей организации.

USER

работает со своими чатами;

использует разрешённые Knowledge Bases;

не имеет administrative routes.

Frontend guards — UX. Security boundary — backend.

Multi-tenancy и integrity

Tenant key — organization_id.

SUPER_ADMIN -> global/platform scope
ADMIN       -> organization scope
USER        -> own-resource scope

Tenant isolation применяется к users, organizations, sessions/messages/turns, Knowledge Bases/memberships/documents, 
quota reservations, audit, usage, model policies, model-route decisions, rate limiting и security-state lookup.

Схема БД дополнительно содержит composite tenant-safe relationships, например:

chat_session(user_id, organization_id)
    -> user(id, organization_id)

chat_message(session_id, organization_id)
    -> chat_session(id, organization_id)

chat_turn(session_id, organization_id, user_id)
    -> chat_session(id, organization_id, user_id)

V45 продолжает ту же модель integrity:

model_route_decision
    -> exact model_catalog snapshot
    -> exact organization_model_policy snapshot
    -> exact chat_session tenant/user scope

chat_turn.model_route_decision_id
    -> exact ALLOWED model_route_decision

answer_passport.model_route_decision_id
    -> exact model-route evidence

Цель — не допустить cross-tenant или provenance mismatch даже при application bug.

Security architecture

Browser auth

access token  -> HttpOnly cookie
refresh token -> HttpOnly cookie
XSRF-TOKEN    -> readable cookie

JWT не хранится в localStorage.

Unsafe methods:

POST / PUT / PATCH / DELETE

отправляют X-XSRF-TOKEN.

После login CSRF token ротируется.

JWT

Access token использует claims:

sub
email/user identity fields according to token contract
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

Валидация включает issuer/audience/type/identity, а не только подпись.

Security epochs

users.token_version меняется при user-specific security mutation.
organizations.auth_version — отдельный tenant-level epoch.

UserStatusFilter сопоставляет user/org enabled state и token epochs. Failure security-state lookup должен fail closed.

Refresh rotation

Refresh token хранится как hash. Rotation/reuse flow использует locked token, predecessor/successor family integrity, 
reuse detection и family termination.

Пароли

BCrypt limit — 72 UTF-8 bytes, не 72 символа.

Текущий baseline:

{bcrypt} через DelegatingPasswordEncoder;

BCrypt strength 12;

legacy unprefixed BCrypt fallback на migration period;

create/reset используют current password policy.

Request ID

Backend генерирует собственный canonical UUID на каждый request. Incoming X-Request-Id может быть клиентской metadata, 
но не становится server correlation ID.

Client IP / proxy trust

Forwarded headers доверяются только при direct peer из trustedProxyCidrs. Empty proxy list = trust nobody. Production 
запрещает trust-all CIDR.

CORS

Production — explicit HTTPS allowlist. Wildcard/userinfo/query/fragment/path и malformed origins запрещены validator-ом.

Errors

MVC и Spring Security используют единый public error contract через ApiErrorResponse. Internal diagnostics не должны 
попадать клиенту.

Backend-модули

Модуль

Назначение

auth

login/logout/refresh, cookies, rotation, revocation

common

shared security/error/platform/time/persistence

organization

tenant lifecycle, platform invariants, org security epoch

user

users, one-role policy, optimistic versions, password/security mutation

model

V45 Model Catalog, organization model policy, runtime status, immutable route decisions

chat

sessions/messages/turns, quotas, lease/fencing/recovery, model-route binding

ai

provider abstraction, retry/errors/context/pricing metadata

knowledge

KB/ACL, documents/versions, storage, extraction, ingestion, embeddings, hybrid retrieval, RAG, Answer Passport

ratelimit

Redis rate limiting/metrics

audit

snapshots, outbox, cursor queries, retention

usage

live/rollup analytics + data quality

ru.safeai.gateway.common остаётся cross-cutting infrastructure layer, а domain-specific governance не переносится в 
common без необходимости.

ChatTurn

Durable state machine:

NEW
PROCESSING
SUCCEEDED
FAILED
AMBIGUOUS

Frontend transport/UI statuses дополнительно включают SENDING, SEND_UNKNOWN, RATE_LIMITED, QUOTA_BLOCKED, ACCESS_REVOKED, 
IDEMPOTENCY_CONFLICT и др.

Idempotency

Frontend генерирует secure clientRequestId.

same id + same normalized request
→ replay

same id + different request
→ IDEMPOTENCY_CONFLICT

Durable external-I/O boundary

До provider I/O фиксируются:

ChatTurn
processingToken
providerOperationId
leaseUntil
quota reservation
modelRouteDecisionId

Provider call выполняется вне долгой DB transaction.

Fencing

Finalization проверяет processingToken; stale processor не может завершить turn после lease takeover.

Provider ambiguity

provider_call_started_at разделяет recovery:

null
→ provider точно не начинался
→ безопасный recovery

non-null
→ provider мог выполнить request
→ blind retry запрещён
→ uncertainty => AMBIGUOUS

Frontend намеренно не запускает автоматический новый provider request из AMBIGUOUS.

AI providers

Интерфейс:

public interface AiProvider {
    AiChatResponse sendMessage(AiChatRequest request);
}

Реализации:

MockAiProvider
OpenAiProvider
AnthropicProvider

Есть provider error taxonomy: timeout, rate-limit, overload, quota/billing, context limit, response-too-large, 
unavailable и generic provider error.

Usage / pricing status

Usage:

NOT_APPLICABLE
AVAILABLE
MISSING
PARTIAL

Pricing:

NOT_APPLICABLE
PRICED
FREE
UNPRICED
CALCULATION_FAILED

Unknown pricing не превращается в zero.

Knowledge / RAG

Knowledge/RAG baseline уже реализован и не является «будущим модулем».

Основные возможности:

tenant/ACL-aware Knowledge Bases;

ORGANIZATION / MEMBERS visibility/access model;

Knowledge Base memberships;

immutable document versions;

durable ingestion jobs;

S3-compatible object storage;

bounded extraction для PDF/DOCX/PPTX/XLSX/HTML/Markdown/JSON/XML/CSV/plain text;

optional OCR provider boundary;

embeddings;

PostgreSQL pgvector + FTS hybrid retrieval;

context assembly;

GENERAL, KNOWLEDGE_ASSISTED, KNOWLEDGE_ONLY modes;

knowledge-only controlled abstention/fail-closed behavior;

inline citations;

retrieval provenance;

Answer Passport;

Knowledge health/reindex/evaluation backend;

Knowledge frontend для KB/documents/memberships/status/reindex и source/passport UX baseline.

Для knowledge-assisted turn retrieval provenance фиксируется до provider boundary и связывается с ChatTurn/Answer 
Passport.

Model Control Plane — V45

V45 добавляет governance plane поверх существующего single-provider data plane.

1. Versioned Model Catalog

model_catalog_entries — append-only snapshots:

modelKey
version
provider
providerModelId
displayName
lifecycle
context/token limits
capabilities/modalities
retention/training metadata
pricing status + dimensions
pricingVersion
effectiveFrom
source
createdBy
createdAt

Latest vs effective

Это два разных понятия:

latest created version
→ административный/version-allocation view
→ может быть scheduled в будущем

effective version at route time
→ max(version) where effective_from <= server decision time
→ только она участвует в routing

Frontend имеет отдельные latest/effective views. Backend endpoint /api/admin/models/catalog/effective вычисляет snapshot 
по server-side Clock, а не по времени браузера.

Важно: runtime identity filter применяется после выбора effective snapshot каждого modelKey. Старая версия того же logical 
model не может снова стать executable только потому, что её provider/model всё ещё совпадает с физическим adapter.

2. Pricing integrity

V45 различает:

UNPRICED
FREE
CONFIGURED
INCOMPLETE

API/service validation согласована с DB constraints, включая PostgreSQL numeric(30,12).

Ключевые semantics:

UNPRICED: никаких price dimensions, pricingComplete=false, empty extra pricing;

FREE: complete + ordinary price = 0, optional cached/cache-write только 0, empty extra pricing;

CONFIGURED: complete + input/output + pricingVersion, cached/cache-write не выше ordinary input, empty extra pricing;

INCOMPLETE: pricingComplete=false, допускает частичные/extra dimensions.

3. Organization Model Policies

Versioned tenant policy поддерживает:

enabled
allowModelKeys
denyModelKeys
defaultModelKey
maxInputTokens
maxOutputTokens
maxRequestCostUsd
monthlyBudgetUsd
budgetEnforcement = SOFT | HARD
requireCompletePricing
requireNoTraining
requireZeroDataRetention

Policies append-only и используют optimistic expectedPreviousVersion + DB locking для version allocation.

4. Immutable route decision

Перед quota/rate-limit/provider I/O создаётся deterministic governance decision:

requested model key
selected catalog snapshot
selected provider/model
policy snapshot
required capabilities
estimated input/output tokens
estimated max cost
monthly budget snapshot
budget enforcement/outcome
pricing completeness
reason
decision SHA-256

Decision immutable и audit-visible.

ALLOWED decision должен быть связан с exact planned ChatTurn; deferred DB constraint не позволяет commit governance 
decision без соответствующего turn binding.

Answer Passport хранит ссылку на exact route decision, поэтому knowledge answer может показать не только evidence 
документов, но и governance evidence модели.

5. Текущая data-plane граница

V45 не заявляет dynamic multi-provider execution.

Сейчас deployment устанавливает один физический AiProvider adapter. Поэтому:

catalog/policy may allow logical model
        ↓
route must still match active runtime provider/model
        ↓
otherwise controlled RUNTIME_MISMATCH / MODEL_NOT_FOUND

Настоящий provider/model multiplexer — следующий отдельный data-plane milestone, а не скрытая возможность V45.

6. Budget semantics

V45 budget — governance preflight, а не billing-grade ledger.

estimate deliberately conservative;

committed/processing/ambiguous operations учитываются безопасно;

unknown cost делает HARD budget unverifiable и может fail closed;

окончательная usage/cost остаётся отдельной usage quality model;

RAG context и будущие specialized pricing dimensions требуют дальнейшего FinOps развития.

Rate limiting

Redis-backed limits:

login: identity/email + IP
AI: user + organization
refresh: dedicated policy

Atomic counter/TTL выполняется Lua script. Infrastructure outage имеет controlled error вместо silent allow-all.

Audit

Architecture:

business transaction
 ├─ domain mutation
 └─ audit_outbox
       ↓ commit
 processor
       ↓
 audit_events

Outbox поддерживает retry/dead-letter. Actor и target organization snapshots immutable.

V45 добавляет audit events для catalog/policy/route decisions. Audit details проходят sanitization/size limits и 
frontend defence-in-depth redaction.

Usage

Usage subsystem считает не только sums, но и data quality:

available / partial / missing usage
priced / free / unpriced / pricing failed
ambiguous provider operation count

Daily aggregation — UTC. Frontend сохраняет большие counters/decimal values в string-safe форме и использует BigInt 
для форматирования там, где нельзя безопасно проходить через JS number.

V45 budget preflight не заменяет usage source of truth.

User management

Основные contracts:

GET   /api/users
GET   /api/users/statistics
GET   /api/users/{id}
POST  /api/users
PATCH /api/users/{id}
PATCH /api/users/{id}/enabled
PATCH /api/users/{id}/roles
POST  /api/users/{id}/reset-password
POST  /api/users/{id}/permanent-deletion

Mutations используют expectedVersion для optimistic concurrency. Wire roles остаётся массивом для compatibility, но 
требует ровно одну role.

Organization management

POST  /api/organizations
GET   /api/organizations
GET   /api/organizations/{id}
PATCH /api/organizations/{id}
POST  /api/organizations/{id}/disable
POST  /api/organizations/{id}/enable

Disable/enable используют version checks и organization authVersion для security invalidation.

Frontend

Frontend — defensive client, а не только набор страниц.

Runtime contracts

API parsers валидируют UUID/Instant/enums/non-negative values/decimal strings/page contracts/nullability. Malformed 
backend response превращается в controlled client contract error.

HTTP/Auth

Общий HTTP layer отвечает за cookies, CSRF, timeouts, AbortSignal, error envelope и auth coordination.

Chat

secure client UUID;

optimistic USER message;

reconciliation по idempotency key;

durable turn polling/status;

no blind retry after AMBIGUOUS;

knowledge mode / source/passport integration;

model-route metadata в durable response/passport chain.

Knowledge

Текущий frontend содержит KB list/details, memberships, documents/versions, ingestion status/errors, reindex, health, 
sources/citations и Answer Passport UX baseline.

Models

AdminModelsPage содержит:

physical runtime status;

latest catalog snapshots;

effective catalog snapshots;

scheduled future version visibility;

catalog version creation/import runtime;

organization policy editor;

route decision evidence lookup;

exact decimal validation без JS floating-point comparison для V45 money fields.

UI hardening

Есть ErrorBoundary/PageErrorBoundary, incident ID, production-safe frontend error reporting, loading/error/empty states, 
modal/focus handling, live regions, focus-visible и reduced motion.

Это accessibility hardening, не заявление о формальной WCAG certification.

API

Auth

GET  /api/auth/csrf
POST /api/auth/login
POST /api/auth/refresh
POST /api/auth/logout
GET  /api/auth/me

Chat

POST /api/chats
GET  /api/chats
GET  /api/chats/{id}
POST /api/chats/{id}/messages
GET  /api/chats/{chatId}/turns/by-client-request/{clientRequestId}

Users

GET   /api/users
GET   /api/users/statistics
GET   /api/users/{id}
POST  /api/users
PATCH /api/users/{id}
PATCH /api/users/{id}/enabled
PATCH /api/users/{id}/roles
POST  /api/users/{id}/reset-password
POST  /api/users/{id}/permanent-deletion

Organizations

POST  /api/organizations
GET   /api/organizations
GET   /api/organizations/{id}
PATCH /api/organizations/{id}
POST  /api/organizations/{id}/disable
POST  /api/organizations/{id}/enable

Model Control Plane

GET  /api/admin/models/runtime

GET  /api/admin/models/catalog
GET  /api/admin/models/catalog/effective
POST /api/admin/models/catalog
POST /api/admin/models/catalog/import-runtime

GET  /api/admin/models/policies/{organizationId}
POST /api/admin/models/policies/{organizationId}

GET  /api/admin/models/route-decisions/{decisionId}

Catalog writes — SUPER_ADMIN. Policy scope дополнительно проверяется service-level tenant authorization.

Knowledge

Knowledge module предоставляет API для Knowledge Bases, memberships, documents/versions, reindex/health, 
retrieval/evaluation и Answer Passport. Конкретный route contract определяется текущими controllers/frontend API clients.

Audit

GET /api/admin/audit-events

Плюс directory API для event types/actors/target organizations.

Usage

GET /api/admin/usage/summary
GET /api/admin/usage/users
GET /api/admin/usage/models
GET /api/admin/usage/daily
GET /api/admin/usage/by-user/{userId}
GET /api/admin/usage/by-organization/{organizationId}
GET /api/admin/usage/by-organization/{organizationId}/users
GET /api/admin/usage/by-organization/{organizationId}/models
GET /api/admin/usage/by-organization/{organizationId}/daily
GET /api/admin/usage/data-quality

Database / Flyway

Production migrations:

backend/src/main/resources/db/migration

Local-only seed:

backend/src/main/resources/db/local-migration/R__seed_local_demo_data.sql

Правила:

never edit applied migration
new schema => new migration
Hibernate ddl-auto=validate
critical invariants in PostgreSQL where practical
local seed is not production reference data

Текущая история — до V45 включительно.

Milestone

Содержание

V1–V10

base schema, tenant denormalization, hardening, quotas/rollups

V11–V19

audit/user/chat identity/integrity evolution

V20–V23

production integrity, cleanup/index/name hardening

V24

exactly-one-role, disabled retention, audit outbox

V25

organization auth_version

V26–V27

audit retry/dead-letter/snapshots/indexes

V28–V31

usage/pricing quality + indexes

V32

durable ChatTurn state machine

V33–V36

turn indexes, ambiguity quality, composite integrity

V37

target organization snapshots

V38–V42

Knowledge Base, versioned documents, durable ingestion, hybrid retrieval, RAG Answer Passport

V43

archive chat sessions

V44

Knowledge production integrity hardening

V45

Model Control Plane: catalog, org policies, immutable route decisions, ChatTurn/Answer Passport binding

V45 не изменяется после применения. Production hardening найденных runtime semantics выполняется в Java/frontend поверх 
существующей migration. Будущие schema changes должны получать новый свободный Flyway version.

Local development

Infra

Windows:

cd /d "D:\Java projects\Safeai-desk\infra"
docker compose -f docker-compose.local.yml up -d postgres redis

macOS/Linux:

cd infra
docker compose -f docker-compose.local.yml up -d postgres redis

Backend

Windows:

cd /d "D:\Java projects\Safeai-desk\backend"
set SPRING_PROFILES_ACTIVE=local
set SAFEAI_JWT_SECRET=safeai-local-development-secret-key-change-this-value-please-123456789
set REDIS_PASSWORD=safeai_redis_password
mvnw.cmd spring-boot:run

macOS/Linux:

cd backend
export SPRING_PROFILES_ACTIVE=local
export SAFEAI_JWT_SECRET='safeai-local-development-secret-key-change-this-value-please-123456789'
export REDIS_PASSWORD='safeai_redis_password'
./mvnw spring-boot:run

Frontend

cd frontend
npm ci
npm run dev

Typical local URLs:

Frontend: http://127.0.0.1:5173
Backend:  http://127.0.0.1:8080
Postgres: localhost:5432
Redis:    localhost:6379

Demo credentials/local seed не использовать в production.

Проверка

README не утверждает BUILD SUCCESS без фактического запуска.

Backend

cd backend
./mvnw clean test

Windows:

cd backend
mvnw.cmd clean test

Для отдельного topology profile:

./mvnw verify -Prate-limit-topology-it

Frontend

cd frontend
npm ci
npm run check
npm run audit:prod

CI-oriented:

npm run ci

Flyway / release candidate

Проверить:

select installed_rank, version, description, success
from flyway_schema_history
order by installed_rank;

Release candidate обязан дополнительно проходить:

fresh V1→V45 migration
upgrade from representative populated V44 snapshot to V45
backend clean test
frontend ci/check
container build/start
prod-like startup invariants
Model Catalog latest/effective scheduling tests
stale-runtime-version routing test
organization model-policy tenant isolation tests
ALLOWED route → exact ChatTurn deferred-integrity test
Answer Passport → route-decision integrity test
backup/restore drill

Production notes

TLS

Internet
→ TLS terminator / reverse proxy
→ Nginx
→ backend internal port

Backend не должен публиковаться напрямую.

Trusted proxy

Указывать только реальный edge CIDR. Не использовать trust-all networks.

CORS

Production — explicit HTTPS allowlist.

Secrets

Не хранить в Git:

JWT private/secret material
DB password
Redis password
OpenAI key
Anthropic key
S3 credentials
future BYOK/KMS credentials

Object storage

Production Knowledge storage должен использовать durable S3-compatible backend с lifecycle/backup policy. Local 
filesystem storage — только там, где это осознанно допустимо.

Prometheus

/actuator/prometheus — internal/network-protected.

User status cache

До доказанной fail-safe invalidation strategy strict security mode предпочтительно держать status cache disabled.

Logs

Не логировать по умолчанию:

passwords
raw JWT/refresh token
cookies
Authorization
API keys
full prompts/responses
raw document chunks
secret connector credentials

Текущие границы

На 28 августа 2026 проект уже содержит Knowledge/RAG и V45 Model Control Plane, но ещё не является законченной 
enterprise 1.0 платформой.

Текущие ограничения:

один физический provider/model adapter на deployment; нет настоящего runtime multiplexer;

V45 budget — conservative preflight, не billing-grade ledger;

нет полноценного provider routing/fallback/data-residency router;

нет DLP/data-classification policy plane;

нет централизованного general-purpose Policy Engine;

нет Groups/Departments;

нет enterprise connector sync/ACL mirroring baseline уровня 1.0;

нет OIDC/SCIM enterprise provisioning;

нет Prompt/Configuration Registry;

нет формально завершённых regression/security evaluation gates;

нет MCP Tool Gateway / approvals / durable Agent Runtime;

HA/SLO/backup-restore должны подтверждаться deployment drills, а не только кодом;

compliance certification не заявляется.

Не заявлять текущую версию как:

billing-grade
compliance-certified
fully HA
formally verified
fully dynamic multi-provider router
deterministically reproducible LLM
autonomous agent platform

Positioning

Для собеседования:

SafeAI Desk — modular-monolith корпоративного AI Gateway: multi-tenancy, one-role RBAC, cookie JWT + CSRF, refresh 
rotation/reuse detection, user/org security epochs, durable idempotent ChatTurn с lease/fencing/ambiguity protection, 
Knowledge/RAG provenance + Answer Passport, transactional audit outbox, usage/pricing quality analytics и V45 Model 
Control Plane с immutable route evidence.

Для продукта:

Управляемая корпоративная AI-платформа, где известно кто, к каким данным и знаниям получил доступ, какая policy и версия 
модели были применены, на каком evidence основан ответ, что произошло и сколько это стоило с известной степенью качества 
данных.

Следующий этап

Ближайший приоритет — не ещё один чат и не agents.

После V45 production hardening:

1. Release-candidate verification V1→V45 / V44→V45
2. Knowledge UX + RAG evaluation/regression gates
3. Multi-provider/model data-plane routing
4. AI FinOps / budgets reconciliation
5. Data Classification + DLP
6. Groups / Departments
7. Enterprise Knowledge Connectors
8. OIDC / SCIM
9. General Policy Engine / Governance
10. Enterprise Assistants
11. MCP Tool Gateway + approvals
12. Durable Agent Runtime

При развитии routing сохраняется текущий invariant:

reserveOrReplay()
        │
        ├─ idempotency lookup
        ├─ immutable model governance decision
        ├─ ChatTurn reservation + quota/rate-limit state
        ▼ commit short transaction

Retrieval / context preparation
        ▼
markProviderCallStarted()
        ▼
provider I/O outside long DB transaction
        ▼
fenced finalization

Главное правило дальнейшей эволюции: governance/provenance не должны ослаблять уже реализованные ChatTurn durability, 
tenant isolation и database-enforced integrity invariants.