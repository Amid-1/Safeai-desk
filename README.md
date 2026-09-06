SafeAI Desk

SafeAI Desk — production-oriented full-stack корпоративная AI-платформа / AI Gateway для безопасного и управляемого 
использования внешних AI-моделей внутри организаций.

Актуальность: 6 сентября 2026.

README описывает фактически реализованный baseline текущего проекта: backend, frontend и Flyway-эволюцию схемы до V48 
включительно. Будущее развитие и крупные продуктовые этапы должны оставаться в ROADMAP.md.

SafeAI Desk — не UI-обёртка над LLM и не «чат с API-ключом». Проект строит управляемый корпоративный слой вокруг AI:

organization-based multi-tenancy;

RBAC SUPER_ADMIN / ADMIN / USER;

HttpOnly-cookie JWT authentication + CSRF;

строгую JWT validation и security revocation epochs;

refresh-token rotation/reuse detection;

tenant-safe user/organization management;

durable ChatTurn state machine с idempotency, lease и fencing;

защиту от повторного provider call при неопределённом исходе;

Redis rate limiting;

transactional audit outbox;

immutable actor/target audit snapshots;

usage/pricing quality model без подмены неизвестных данных нулём;

Mock / OpenAI / Anthropic provider abstraction;

Knowledge/RAG: Knowledge Bases, immutable document versions, ingestion, object storage, extraction, embeddings, hybrid 
retrieval, citations и Answer Passport;

Model Control Plane: versioned Model Catalog, organization model policies и immutable model-route evidence;

V48 input-accounting provenance и integrity v3 для новых route decisions;

fail-closed capability enforcement для ещё не реализованного specialized data plane;

runtime configuration/probe UI, отдельно от organization policy;

PostgreSQL/Flyway constraints как часть integrity model;

React frontend с runtime validation API contracts, abortable requests, production error handling и адаптивными 
административными workspace.

Текущий статус: production-oriented pre-1.0 / portfolio-ready baseline.

Это серьёзная база B2B AI-platform / AI Gateway, но не заявление о compliance certification, billing-grade accounting, 
формально доказанной HA/SLA, полноценном dynamic multi-provider data plane или автономной agent platform.

Содержание

Что решает проект

Архитектура

Стек

Роли и границы доступа

Multi-tenancy и database integrity

Security architecture

Backend-модули

Durable ChatTurn

AI providers и runtime

Knowledge / RAG

Model Control Plane — V45→V48

Input accounting и V48 route evidence

Rate limiting

Audit

Usage / pricing quality

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

Корпоративный AI требует отвечать не только на вопрос «что ответила модель».

Нужно уметь установить:

кто сделал запрос;

из какой организации;

какие права были у пользователя;

был ли user и tenant активен;

не устарела ли access-сессия;

какие Knowledge Bases разрешено использовать;

какая версия документа участвовала в retrieval;

какой model policy был действующим;

какая версия model catalog была effective в момент routing;

какая физическая runtime model реально могла выполнить запрос;

какой input accounting использовался;

какой объём был зарезервирован до provider I/O;

можно ли безопасно повторить AI operation;

был ли provider call фактически начат;

сколько usage/cost данных известно и какого они качества;

кто изменил governance/security state;

что произошло при crash во время provider I/O;

какое immutable evidence осталось после решения маршрутизации.

SafeAI Desk объединяет:

Identity
+ Tenant isolation
+ Session security
+ Durable chat operations
+ Knowledge / Retrieval provenance
+ Model governance
+ Input accounting provenance
+ Rate limits / quotas
+ Provider abstraction
+ Usage / pricing quality
+ Audit trail

Архитектура

Проект построен как модульный монолит.

Это сознательный выбор: текущие tenant/security/durability/governance invariants выгодно держать внутри одной 
transactional boundary, не добавляя преждевременную микросервисную сложность.

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
┌─────────────────────────────────────────────────────────┐
│ Spring Boot Backend                                     │
│ auth / common / organization / user / model / chat / ai │
│ knowledge / audit / usage / ratelimit                   │
└─────────────┬────────────────┬──────────────────────────┘
              │                │
              ▼                ▼
      ┌─────────────┐    ┌──────────────┐
      │ PostgreSQL  │    │ Redis        │
      │ + Flyway    │    │ rate limits  │
      │ source of   │    │ coordination │
      │ truth       │    │ optional     │
      └──────┬──────┘    └──────────────┘
             │
             ├──────────────► S3-compatible object storage
             │                 Knowledge documents
             │
             │ provider call outside long DB transaction
             ▼
      ┌──────────────────────────────┐
      │ Mock / OpenAI / Anthropic    │
      └──────────────────────────────┘

PostgreSQL — source of truth для durable/security/governance state.
Redis — coordination/rate-limit/cache layer. Security correctness не должна зависеть только от Redis cache.
S3-compatible storage — содержимое Knowledge documents.
Provider I/O выполняется вне долгой database transaction.

Control plane и data plane

Model Control Plane уже умеет хранить versioned Model Catalog, определять latest/effective версии, применять organization 
policy, проверять pricing/data requirements, делать budget preflight и фиксировать immutable route decision.

Но текущий runtime всё ещё представляет один физически активный provider/model adapter на deployment. Governance plane 
может разрешить logical model только если её effective snapshot исполним текущим runtime.

Стек

Backend

Java 21;

Spring Boot 4.1.0-M1;

Spring Web MVC;

Spring Security;

Spring Data JPA / Hibernate;

Spring JDBC;

PostgreSQL;

Flyway;

Redis;

Maven Wrapper;

Bean Validation;

Lombok;

MapStruct;

JUnit 5 / Mockito / Spring Test;

Testcontainers;

MinIO / S3-compatible storage;

AWS SDK v2;

Apache Tika;

PDFBox;

Apache POI;

jsoup;

Micrometer / Prometheus;

structured logging;

Docker / Docker Compose.

Точные версии определяет backend/pom.xml.

Frontend

По текущему frontend/package.json:

React 19.1.1;

React DOM 19.1.1;

React Router DOM 7.8.2;

TypeScript 6.0.0-dev.20250826;

Vite 8.0.0-beta.13;

Vitest 3.2.4;

Testing Library;

ESLint 9 + TypeScript ESLint;

eslint-plugin-jsx-a11y;

Recharts 3.1.2;

Node.js >=24.6.0;

npm >=11.5.1.

Frontend работает в strict TypeScript mode и имеет scripts для typecheck, lint, tests, coverage, production build, combined check, CI и production dependency audit.

Роли и границы доступа

У пользователя ровно одна системная роль:

SUPER_ADMIN
ADMIN
USER

SUPER_ADMIN

Platform/control-plane administrator:

создаёт и просматривает tenant organizations;

управляет обычными ADMIN/USER;

видит global audit и usage;

читает runtime model status;

управляет глобальным Model Catalog;

импортирует текущую runtime model в catalog;

создаёт новые immutable catalog versions;

читает/изменяет organization model policies;

выбирает tenant для model-governance administration;

читает immutable route decisions разных организаций;

может запускать административную проверку runtime model availability.

Через ordinary user-management flow не должен создавать/превращать пользователя в SUPER_ADMIN.

Текущий frontend рассматривает SUPER_ADMIN как platform/control-plane роль и не выдаёт ей обычный Chat route.

ADMIN

Tenant administrator:

работает с Chat;

управляет USER своей организации;

работает с Knowledge Bases в tenant scope;

видит tenant audit/usage;

читает runtime status;

читает глобальный Model Catalog/effective catalog;

создаёт новую version model policy своей организации;

читает route-decision evidence своей организации.

Не может мутировать глобальный catalog, импортировать runtime, управлять policy чужого tenant или читать чужой route evidence.

USER

работает со своими чатами;

использует разрешённые Knowledge Bases;

получает AI responses в рамках organization policy;

не имеет administrative routes.

Model Control Plane RBAC

Возможность

USER

ADMIN

SUPER_ADMIN

Страница «Модели»

нет

да

да

Runtime status

нет

read

read

Global catalog

нет

read-only

read/write

Import runtime → catalog

нет

нет

да

New catalog version

нет

нет

да

Policy своей организации

нет

read/write

read/write

Policy другой организации

нет

нет

read/write

Route decision своей организации

нет

read

read

Route decision другой организации

нет

нет

read

Frontend guards — UX. Security boundary — backend.

Multi-tenancy и database integrity

Tenant key — organization_id.

SUPER_ADMIN -> platform/global scope
ADMIN       -> organization scope
USER        -> own-resource scope

Tenant isolation применяется к users, organizations, sessions/messages/turns, Knowledge Bases/memberships/documents, 
quota reservations, audit, usage, model policies, model-route decisions, rate limiting и security-state lookup.

Composite tenant-safe relationships включают:

chat_session(user_id, organization_id)
    -> user(id, organization_id)

chat_message(session_id, organization_id)
    -> chat_session(id, organization_id)

chat_turn(session_id, organization_id, user_id)
    -> chat_session(id, organization_id, user_id)

Model governance продолжает ту же модель:

model_route_decision
    -> exact model_catalog snapshot
    -> exact organization_model_policy snapshot
    -> exact chat/user/organization context

chat_turn.model_route_decision_id
    -> exact ALLOWED route decision

answer_passport.model_route_decision_id
    -> exact governance evidence

Цель — не допустить cross-tenant/provenance mismatch даже при application bug.

Security architecture

Browser auth

access token  -> HttpOnly cookie
refresh token -> HttpOnly cookie
XSRF-TOKEN    -> readable cookie

JWT не хранится в localStorage. Unsafe methods передают X-XSRF-TOKEN. После login CSRF token ротируется.

JWT validation

Access token валидируется по signature и contract claims: identity, userId, organizationId, role, token/org security 
versions, jti, iat, exp, iss, aud, token type.

Security epochs

users.token_version меняется при user-specific security mutation.
organizations.auth_version — tenant-level epoch.

Failure security-state lookup должен fail closed.

Refresh rotation/reuse

Refresh token хранится как hash. Rotation/reuse flow использует locked token state, predecessor/successor family 
integrity, reuse detection и family termination.

Passwords

BCrypt limit — 72 UTF-8 bytes, не 72 символа. Baseline использует DelegatingPasswordEncoder, {bcrypt}, BCrypt strength 
12 и legacy unprefixed BCrypt fallback только на migration period.

Request ID

Backend генерирует canonical UUID request ID. Incoming X-Request-Id не становится автоматически server correlation ID.

Client IP / trusted proxy

Forwarded headers доверяются только когда direct peer принадлежит configured trustedProxyCidrs.

empty proxy allowlist = trust nobody

Production не должен использовать trust-all CIDR.

CORS

Production — explicit HTTPS allowlist.

Public errors

MVC и Spring Security используют безопасный public error contract. Internal stack traces/secrets/provider diagnostics 
не должны попадать клиенту.

Runtime health probe safety

Runtime probe отделён от Chat flow и не должен передавать user prompt, history, RAG context или document content. HTTP 
hardening включает HTTPS-only URL, disabled redirects, bounded timeouts, discarded response body и controlled status mapping.

Backend-модули

Модуль

Назначение

auth

login/logout/refresh, cookies, token rotation/revocation

common

shared security/error/platform/time/persistence

organization

tenant lifecycle, platform invariants, org security epoch

user

users, exactly-one-role policy, optimistic versions, password/security mutations

model

runtime status/probe, catalog, organization policy, immutable route decisions, V48 accounting provenance

chat

sessions/messages/turns, quotas, idempotency, lease/fencing/recovery, route binding

ai

provider abstraction, retry/errors, input accounting, provider metadata

knowledge

KB/ACL, documents/versions, storage, extraction, ingestion, embeddings, retrieval, RAG, Answer Passport

ratelimit

Redis rate limiting/metrics

audit

snapshots, outbox, cursor queries, retention

usage

live/rollup analytics + pricing/data-quality state

Durable ChatTurn

State machine:

NEW
PROCESSING
SUCCEEDED
FAILED
AMBIGUOUS

Frontend дополнительно различает transport/UI states вроде SENDING, SEND_UNKNOWN, RATE_LIMITED, QUOTA_BLOCKED, 
ACCESS_REVOKED, IDEMPOTENCY_CONFLICT.

Idempotency

same clientRequestId + same semantic request
→ replay

same clientRequestId + different semantic request
→ IDEMPOTENCY_CONFLICT

V48 replay identity учитывает semantic request, включая knowledge selection, а не текущую mutable routing envelope 
configuration.

External-I/O boundary

До provider I/O фиксируются:

ChatTurn
processingToken
providerOperationId
leaseUntil
quota reservation
ModelRouteDecision
route/input reservation

Provider call выполняется вне долгой DB transaction.

Fencing

Finalization проверяет processingToken. Stale worker не может завершить turn после lease takeover.

Provider ambiguity

provider_call_started_at = null
→ provider точно не начинался
→ safe recovery possible

provider_call_started_at != null
→ provider мог выполнить operation
→ blind retry запрещён
→ uncertainty => AMBIGUOUS

Frontend не запускает автоматический новый provider request из AMBIGUOUS.

AI providers и runtime

Provider abstraction:

public interface AiProvider {
    AiChatResponse sendMessage(AiChatRequest request);
}

Реализации:

MockAiProvider;

OpenAiProvider;

AnthropicProvider.

Provider error taxonomy различает timeout, rate limit, overload, quota/billing, context limit, too-large, unavailable и 
generic provider error.

Runtime ≠ organization policy

Физическая configured model определяется backend runtime.

safeai.ai.provider=mock
        ↓
MockAiProvider
        ↓
mock-safeai

Organization policy не переключает provider adapter.

Runtime health

UI разделяет:

Конфигурация: включена

и:

Проверка доступности: NOT_PROBED / ...

enabled=true не означает, что provider уже проверен по сети.

Типовые probe states:

AVAILABLE
AUTH_ERROR
MODEL_NOT_FOUND
RATE_LIMITED
UNAVAILABLE
ERROR
NOT_PROBED

Probe — point-in-time metadata-only check, не SLA/HA monitoring.

Knowledge / RAG

Knowledge/RAG уже реализован.

Возможности:

tenant/ACL-aware Knowledge Bases;

ORGANIZATION / MEMBERS access model;

memberships;

immutable document versions;

durable ingestion jobs;

S3-compatible object storage;

bounded extraction;

optional OCR boundary;

embeddings;

PostgreSQL pgvector + FTS hybrid retrieval;

context assembly;

GENERAL / KNOWLEDGE_ASSISTED / KNOWLEDGE_ONLY modes;

controlled abstention/fail-closed behavior;

inline citations;

retrieval provenance;

Answer Passport;

health/reindex/evaluation backend;

frontend для KB/documents/memberships/status/reindex/sources/passport.

Extraction baseline включает PDF, DOCX, PPTX, XLSX, HTML, Markdown, JSON, XML, CSV и plain text.

V48 envelope

RAG context не может обходить route reservation. KnowledgeContextAssembler сохраняет reserved input units/output cap 
и route identity. Prepared request проверяется execution guard. Если actual prepared input превышает reservation, 
operation завершается детерминированно до provider I/O.

Model Control Plane — V45→V48

Архитектура разделяет:

Runtime
= что физически настроено deployment

Catalog
= какие immutable model snapshots знает governance

Organization Policy
= что разрешено tenant

Route Decision
= какое immutable решение принято для конкретного request

Versioned Model Catalog

model_catalog_entries — append-only snapshots:

modelKey
version
provider
providerModelId
displayName
lifecycle
limits
capabilities/modalities
retention/training metadata
pricing status/dimensions/version
effectiveFrom
source
createdBy
createdAt

Latest vs effective

latest
→ последняя созданная version
→ может быть scheduled

effective
→ уже вступила в силу на server decision time
→ участвует в routing

Старая version не редактируется.

Pricing integrity

Catalog различает UNPRICED, FREE, CONFIGURED, INCOMPLETE. Unknown pricing не превращается в zero. Money wire contract 
использует exact decimal strings.

Organization Model Policies

Policy поддерживает:

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

Policies append-only и используют expectedPreviousVersion + locking/version allocation.

Safe unconfigured state

Для отсутствующей policy:

configured = false
version = 0
enabled = false

Это предотвращает случайное включение fail-closed policy при первом сохранении.

UI предупреждает, если enabled policy не имеет исполнимой effective catalog entry, совпадающей с runtime. Save не 
блокируется полностью, потому что administrator может сознательно подготовить fail-closed/staged policy.

Runtime compatibility

policy allows
+
effective catalog exists
+
governance checks pass
+
catalog provider/model matches active runtime
→ executable

Иначе controlled denial/mismatch.

Immutable ModelRouteDecision

Перед provider I/O фиксируются requested/selected model, exact catalog/policy snapshots, provider/model, capabilities, 
accounting/output reservation, budget/pricing state, outcome/reason, integrity version и SHA-256.

Historical integrity versions v1/v2 сохраняются. Новые V48 decisions используют integrity v3.

Frontend умеет читать immutable route evidence по decisionId.

Input accounting и V48 route evidence

Старый отдельный ModelInputTokenEstimator удалён. Используется единый:

ru.safeai.gateway.ai.input.AiInputUnitEstimator

Accounting version:

UTF8_STRUCTURAL_UNITS_V2

Новые governance API используют термин input units: internal deterministic estimator не выдаётся за точный tokenizer 
provider. Исторические DB fields *_tokens сохраняются.

V48 provenance

Новые route decisions сохраняют:

input_accounting_version
additional_input_unit_upper_bound

Для integrity v3:

input_accounting_version != null
additional_input_unit_upper_bound >= 0
decision_sha256 = 64 lowercase hex

Для historical v1/v2 provenance fields остаются NULL.

Execution guard

До проверки размера guard подтверждает exact request identity:

user;

organization;

chat/session;

providerOperationId;

user message;

history;

reservation;

output cap.

Затем:

prepared estimated input units
<=
reserved input units

Иначе deterministic failure до provider I/O.

Route reservation path сериализуется по (chatId, clientRequestId).

Capability enforcement

Catalog может описывать capabilities/modalities, но metadata ещё не означает реализованный data plane.

На текущем этапе специализированные возможности:

TOOLS
VISION
STRUCTURED_OUTPUT

должны fail closed, пока соответствующий end-to-end provider flow не реализован. TEXT flow остаётся рабочим.

V48 также нормализует integrity между vision/image-input metadata.

Budget semantics

Budget — governance preflight, не billing ledger.

estimate conservative;

processing/ambiguous operations не должны оптимистично игнорироваться;

unknown cost делает HARD budget unverifiable;

HARD может fail closed;

final usage/cost остаётся отдельной usage quality model.

Rate limiting

Redis-backed limits:

login   -> identity/email + IP
AI      -> user + organization
refresh -> dedicated policy

Atomic counter/TTL выполняется Lua. Infrastructure outage не должен превращаться в silent allow-all.

Audit

business transaction
 ├─ domain mutation
 └─ audit_outbox
       ↓ commit
 processor
       ↓
 audit_events

Outbox поддерживает retry/dead-letter. Actor/target snapshots immutable. Audit покрывает security/governance mutations, 
включая catalog, organization policy и route-decision related events.

Usage / pricing quality

Usage states:

NOT_APPLICABLE
AVAILABLE
MISSING
PARTIAL

Pricing states:

NOT_APPLICABLE
PRICED
FREE
UNPRICED
CALCULATION_FAILED

Unknown pricing не превращается в 0. Daily aggregation — UTC. Большие counters/decimal money сохраняются string-safe и 
не должны терять точность через JS number.

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

Mutations используют expectedVersion. Exactly-one-role invariant сохраняется.

Frontend Users использует адаптивный resizable workspace: control area отдельно, table viewport отдельно, 
pagination/footer не перекрываются таблицей.

Organization management

POST  /api/organizations
GET   /api/organizations
GET   /api/organizations/{id}
PATCH /api/organizations/{id}
POST  /api/organizations/{id}/disable
POST  /api/organizations/{id}/enable

Disable/enable используют version checks и organization.authVersion.

Frontend отделяет tenant creation controls от resizable organization table viewport.

Frontend

Frontend — defensive client.

Runtime parsers

Валидируются UUID, Instant/date, enums, non-negative values, nullable/page contracts, exact decimal strings, 
ModelRouteDecision integrity 1/2/3 и V48 provenance invariants.

Malformed backend response превращается в controlled client contract error.

Chat

secure client UUID;

optimistic USER message;

idempotency reconciliation;

durable turn polling;

no blind retry after AMBIGUOUS;

knowledge mode;

citations/sources;

Answer Passport;

route/model metadata.

Models

AdminModelsPage разделяет три слоя:

Подключённая модель / Runtime — физическая backend configuration + health/probe state.

Catalog — latest/effective/scheduled immutable snapshots.

Organization Policy — allow/deny/default/limits/budget/data requirements.

SUPER_ADMIN видит catalog mutation actions; ADMIN получает read-only catalog и own-tenant policy.

Policy modal:

первая policy открывается disabled;

предупреждает о runtime/effective mismatch;

создаёт immutable policy versions;

resizable;

адаптируется к ширине/высоте;

не закрывается случайным backdrop click;

не закрывается по Escape;

закрывается крестиком или после successful Save;

использует fixed header + scrollable body + fixed action footer.

Administrative pages используют resizable/fixed workspace там, где это оправдано, без overlay таблиц поверх control area.

Error/accessibility hardening

Есть ErrorBoundary, PageErrorBoundary, loading/error/empty states, modal focus management, inert background, focus trap, 
live regions, focus-visible и reduced motion support.

Это не заявление о формальной WCAG certification.

API

Ниже — основные contracts; дополнительные routes определяются текущими controllers/frontend clients.

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

Runtime availability probe существует как отдельная administrative operation в runtime model control path; exact route 
определяется текущим ModelRuntimeController.

Authorization:

catalog read   -> ADMIN / SUPER_ADMIN
catalog mutate -> SUPER_ADMIN

policy         -> ADMIN own tenant / SUPER_ADMIN platform
route evidence -> ADMIN own tenant / SUPER_ADMIN platform

Knowledge

Knowledge module предоставляет API для Knowledge Bases, memberships, documents/versions, upload/ingestion, reindex, 
health, retrieval/evaluation и Answer Passport.

Audit

GET /api/admin/audit-events

Плюс directory/filter API, используемые текущим UI.

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
new schema change => new migration
Hibernate ddl-auto=validate
critical invariants in PostgreSQL where practical
local seed != production reference data

Текущая история — до V48.

Версии

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

Knowledge Base, immutable document versions, ingestion, hybrid retrieval, RAG Answer Passport

V43

archive chat sessions

V44

Knowledge production integrity hardening

V45

Model Control Plane baseline

V46–V47

follow-up Model Control Plane / integrity / rolling-compatibility hardening

V48

input-accounting provenance, decision integrity v3 semantics, accounting envelope provenance, vision/image capability 
integrity normalization

V48 schema hardening

Добавлены:

model_route_decisions.input_accounting_version
model_route_decisions.additional_input_unit_upper_bound

Integrity versions:

1 / 2 / 3

V1/V2 сохраняют provenance fields NULL. V3 требует non-blank accounting version, non-negative upper bound и 64-char 
lowercase hex SHA.

Applied migrations не редактируются задним числом. Новое schema изменение получает новый Flyway version.

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

cd /d "D:\Java projects\Safeai-deskackend"
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

Integration tests с Testcontainers требуют работающий Docker и доступность нужных images. Container startup failure 
следует отличать от unit/domain assertion failure.

Frontend

cd frontend
npm ci
npm run check
npm run audit:prod

Текущий check объединяет lint, Vitest run и production TypeScript/Vite build.

CI:

npm run ci

Flyway / release candidate

select
    installed_rank,
    version,
    description,
    success
from flyway_schema_history
order by installed_rank;

Release candidate должен дополнительно проходить:

fresh V1→V48;

representative pre-V48 → V48 upgrade;

rolling/bridge compatibility verification where required;

backend clean test;

frontend check/CI;

container build/start;

prod-like startup invariants;

latest/effective catalog scheduling;

stale runtime/catalog mismatch;

policy optimistic concurrency;

tenant isolation;

exact ChatTurn/route binding;

Answer Passport/route evidence integrity;

V48 integrity v3/provenance;

prepared-request envelope enforcement;

idempotent replay after mutable governance changes;

capability fail-closed tests;

backup/restore drill.

Production notes

TLS

Internet
→ TLS terminator / reverse proxy
→ Nginx
→ backend internal port

Backend не должен публиковаться напрямую без edge controls.

Trusted proxy

Только реальные trusted edge CIDR. Не использовать trust-all networks.

CORS

Production — explicit HTTPS allowlist.

Secrets

Не хранить в Git:

JWT secret/private material;

DB/Redis passwords;

OpenAI/Anthropic keys;

S3 credentials;

future BYOK/KMS credentials.

Object storage

Production Knowledge storage требует durable S3-compatible backend с lifecycle/backup/restore/access policy/monitoring.

Prometheus

/actuator/prometheus — internal/network-protected.

Logs

Не логировать passwords, raw JWT/refresh token, cookies, Authorization, API keys, full prompts/responses, raw document 
chunks и secret connector credentials.

Runtime probe

Probe не отправляет пользовательские данные, не должен follow redirects с credentials и не является заменой monitoring/SLA.

Текущие границы

На 6 сентября 2026 SafeAI Desk уже содержит tenant/security baseline, durable ChatTurn, audit outbox, usage/pricing 
quality, Knowledge/RAG, Answer Passport, Model Control Plane, V48 route-accounting provenance и administrative model 
runtime/catalog/policy UI.

Но это ещё не enterprise 1.0.

Ограничения:

один физический provider/model adapter на deployment;

нет runtime provider/model multiplexer;

нет полноценного provider fallback/data-residency router;

runtime probe — point-in-time check, не HA monitoring;

TOOLS/VISION/STRUCTURED_OUTPUT fail closed до реализации data plane;

budget — conservative preflight, не billing-grade ledger;

нет DLP/data-classification plane;

нет general-purpose Policy Engine;

нет Groups/Departments;

нет enterprise connector sync/ACL mirroring baseline 1.0;

нет OIDC/SCIM;

нет Prompt/Configuration Registry уровня enterprise governance;

нет завершённых regression/security evaluation gates;

нет MCP Tool Gateway/approvals;

нет durable Agent Runtime;

HA/SLO/backup-restore должны подтверждаться deployment drills;

compliance certification не заявляется.

Не описывать текущую версию как:

billing-grade
compliance-certified
fully HA
formally verified
fully dynamic multi-provider router
deterministically reproducible LLM
autonomous agent platform

Positioning

Для собеседования

SafeAI Desk — modular-monolith корпоративного AI Gateway: multi-tenancy, exactly-one-role RBAC, cookie JWT + CSRF, 
refresh rotation/reuse detection, user/org security epochs, durable idempotent ChatTurn с lease/fencing/ambiguity 
protection, Knowledge/RAG provenance + Answer Passport, transactional audit outbox, usage/pricing quality analytics, 
versioned Model Catalog, organization policies, immutable route decisions и V48 input-accounting provenance/integrity v3.

Для продукта

Управляемая корпоративная AI-платформа, где можно установить кто сделал запрос, из какой организации, какие права и 
knowledge evidence использовались, какая policy/catalog version применялась, какая runtime model была исполнима, какой 
accounting contract использовался, почему routing был ALLOWED/DENIED и сколько usage/cost данных известно с какой 
степенью качества.

Следующий этап

После V48 hardening логичный порядок:

Release-candidate verification V1→V48 и representative upgrade path.

Knowledge UX + RAG evaluation/regression gates.

Dynamic multi-provider/model data-plane routing.

Provider fallback / residency-aware routing.

AI FinOps / budget reconciliation / ledger-quality accounting.

Data Classification + DLP.

Groups / Departments.

Enterprise Knowledge Connectors.

OIDC / SCIM.

General Policy Engine / Governance.

Prompt / Configuration Registry.

Enterprise Assistants.

MCP Tool Gateway + approvals.

Durable Agent Runtime.

Routing evolution должна сохранять invariant:

reserveOrReplay()
        │
        ├─ idempotency lookup
        ├─ immutable model governance decision
        ├─ input-accounting provenance
        ├─ ChatTurn reservation
        ├─ quota/rate-limit state
        ▼
commit short transaction

Retrieval / context preparation
        │
        ├─ preserve route identity
        ├─ preserve output cap
        ├─ enforce reserved input envelope
        ▼
markProviderCallStarted()
        ▼
provider I/O outside long DB transaction
        ▼
fenced finalization

Главное правило дальнейшей эволюции:

новые governance, routing, RAG, FinOps и agent capabilities не должны ослаблять уже реализованные ChatTurn durability, 
tenant isolation, idempotency, provider-ambiguity protection, provenance и database-enforced integrity invariants.