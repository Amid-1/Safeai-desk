SafeAI Desk — Roadmap развития

Актуальность: 6 сентября 2026
Горизонт: сентябрь 2026 → конец 2027
Проект: SafeAI Desk
Позиционирование: production-oriented full-stack корпоративная AI-платформа / AI Gateway для безопасного и управляемого 
использования внешних и локальных AI-моделей внутри организаций.

Этот roadmap синхронизирован с текущим baseline проекта до Flyway V48 включительно.

Knowledge/RAG, Answer Passport и первый Model Control Plane уже реализованы и больше не считаются полностью будущими эпиками. 
V45–V48 перевели проект от «просто AI Gateway с RAG» к платформе с versioned model governance, immutable route evidence 
и input-accounting provenance.

Будущие номера Flyway-миграций намеренно не резервируются заранее. Перед merge всегда использовать реально свободный version.

1. Текущее состояние проекта

SafeAI Desk уже существенно выходит за рамки интерфейса над LLM API.

На 6 сентября 2026 реализован следующий фундамент.

Identity / Security

organization-based multi-tenancy;

RBAC SUPER_ADMIN / ADMIN / USER;

ровно одна системная роль на пользователя;

HttpOnly cookie JWT authentication;

CSRF-защита;

строгая JWT validation;

user.tokenVersion;

organization.authVersion;

refresh-token rotation;

refresh-token reuse detection;

tenant-safe user/organization management;

production security invariant validators;

trusted-proxy/client-IP handling;

canonical server request IDs;

fail-closed security state lookup;

BCrypt/DelegatingPasswordEncoder hardening.

Durable AI execution

OpenAI / Anthropic / Mock provider abstraction;

durable ChatTurn state machine;

idempotency;

semantic replay identity;

lease + fencing;

providerOperationId;

provider-call boundary;

provider_call_started_at;

recovery;

AMBIGUOUS outcome protection;

Redis rate limiting;

quota reservation semantics;

provider I/O вне долгой DB transaction;

no blind retry после неопределённого external I/O.

Audit / Usage

transactional audit outbox;

immutable actor snapshots;

immutable target organization snapshots;

audit retention/retry/dead-letter;

usage analytics;

pricing/data-quality semantics без подмены unknown нулём;

UTC rollups/reconciliation;

controlled unknown/unpriced/calculation-failed states.

Knowledge / RAG — реализован baseline

tenant/ACL-aware Knowledge Bases;

KB memberships/visibility;

immutable document versions;

durable ingestion;

S3-compatible object storage;

bounded document extraction;

optional OCR boundary;

embeddings;

pgvector + FTS hybrid retrieval;

RAG context assembly;

inline citations;

GENERAL / KNOWLEDGE_ASSISTED / KNOWLEDGE_ONLY;

controlled abstention;

retrieval provenance;

Answer Passport;

Knowledge health/reindex/evaluation backend;

frontend для KB/documents/memberships/ingestion/citations/passport baseline.

Model Control Plane — реализован V45→V48 baseline

append-only versioned Model Catalog;

lifecycle/capabilities/modalities;

retention/training metadata;

pricing semantics;

scheduled effectiveFrom;

separate latest-created и effective-at-route snapshots;

organization model policies;

allow/deny/default model rules;

max input/output limits;

max-request-cost preflight;

monthly budget preflight SOFT/HARD;

requireCompletePricing;

requireNoTraining;

requireZeroDataRetention;

immutable model-route decisions;

decision integrity digest;

exact binding route decision → ChatTurn;

Answer Passport → exact model-route evidence;

frontend Model Control Plane;

runtime status/probe separation;

V48 input-accounting provenance;

V48 decision integrity v3;

prepared-request execution envelope;

fail-closed specialized capability gate;

deterministic envelope failure до provider I/O.

Infrastructure

PostgreSQL/Flyway integrity constraints;

Redis;

S3-compatible storage;

Docker/Docker Compose;

Nginx;

Prometheus;

production validation/deploy scripts;

PostgreSQL backup/restore scripts;

systemd backup timer/service;

production secret bootstrap examples.

Текущий главный фокус

Следующий этап — не «добавить ещё один чат» и не начинать agents.

Приоритет:

V48 release-candidate stabilization
        ↓
Knowledge UX + RAG quality measurement
        ↓
Executable multi-provider/model data plane
        ↓
FinOps + DLP + enterprise identity/data controls
        ↓
Assistants / Tools / Agents

2. Целевое направление

                        SafeAI Desk
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
   AI Workspace        AI Control Plane      Governance
        │                    │                    │
 Chat / RAG / Agents     Models / Routing     Policies / Risk
 Knowledge / Search      Budgets / Limits     Audit / Approvals
 Tools / MCP             Providers            Compliance
        │                    │                    │
        └────────────────────┼────────────────────┘
                             │
                      Enterprise Data
                             │
       Confluence / Drive / SharePoint / Jira /
       DB / CRM / Git / Slack / internal APIs

Эволюция проекта:

2026

Безопасно пользоваться AI
        ↓
Безопасно использовать корпоративные знания
        ↓
Контролировать разрешённые модели
        ↓
Доказывать, какое route decision реально применялось
        ↓
Контролировать input accounting / budget preflight

2027

Безопасно подключать enterprise data
        ↓
Управлять DLP / identity / budgets / policy
        ↓
Создавать управляемых Assistants
        ↓
Безопасно давать AI инструменты
        ↓
Разрешать durable agent actions

3. Этап 0 — V48 production stabilization

Приоритет: максимальный
Период: сентябрь 2026
Статус: текущий активный этап

V45–V48 уже реализованы. Ближайшая задача — не расширять control plane, а доказать корректность текущего baseline.

3.1. Обязательная release-candidate проверка

Flyway

fresh migration V1 → V48;

representative populated upgrade pre-V48 → V48;

Flyway checksum consistency;

отсутствие редактирования applied migrations;

проверка rolling-sensitive compatibility для V46/V47/V48;

validation всех NOT VALID constraints после data inspection, где это предусмотрено rollout-планом.

Backend

./mvnw clean test;

targeted Model Control Plane integration tests;

Knowledge/RAG integration tests;

Testcontainers PostgreSQL;

Testcontainers MinIO/S3 where applicable;

authorization regression;

tenant isolation;

idempotency;

lease/fencing;

ambiguity/recovery;

audit outbox;

usage quality;

startup production invariants.

Frontend

npm ci;

npm run check;

npm run audit:prod;

production build;

production preview;

auth route guards;

runtime response parsers;

ErrorBoundary/PageErrorBoundary;

Model Control Plane ADMIN/SUPER_ADMIN paths;

responsive administrative layouts;

modal focus/scroll/resize behavior.

Infrastructure

container build/start;

prod-like startup;

Nginx/proxy headers;

trusted proxy allowlist;

CORS;

secret validation;

Prometheus exposure;

PostgreSQL backup;

PostgreSQL restore;

object-storage backup/recovery expectations;

log secret review.

3.2. V48 semantic regression matrix

Обязательно проверить:

Catalog / effective time

future-scheduled catalog version не executable до effectiveFrom;

latest view показывает будущую snapshot отдельно от effective;

route time берётся с server-controlled Clock;

старая effective version не проходит runtime matching после физической смены provider/model, если уже не соответствует 
runtime.

Pricing

UNPRICED / FREE / CONFIGURED / INCOMPLETE соответствуют DB/domain constraints;

exact money fields не теряют precision;

unknown cost не становится zero;

HARD budget fail-closed при unverifiable cost.

Policy

unconfigured policy:

configured=false;

version=0;

enabled=false;

первая сохранённая policy не включается случайно;

optimistic expectedPreviousVersion;

два concurrent writes к одной policy version:

один success;

один controlled conflict;

ADMIN не управляет policy чужой организации;

SUPER_ADMIN может работать в platform scope.

Route decision

ALLOWED route создаётся до provider I/O;

exact ChatTurn binding;

denied route evidence сохраняется корректно;

route-decision tenant isolation;

historical integrity v1/v2 остаётся валидным;

новые decisions получают integrity v3.

V48 input accounting

Проверить для новых route decisions:

decision_integrity_version = 3
input_accounting_version = UTF8_STRUCTURAL_UNITS_V2
additional_input_unit_upper_bound >= 0
decision_sha256 = 64 lowercase hex

Execution envelope

base request reservation;

RAG context добавляется только внутри envelope;

KnowledgeContextAssembler сохраняет reservation/output cap;

ModelRouteExecutionGuard проверяет exact identity;

prepared request > reservation:

deterministic fail;

provider call не начинается;

mismatch user/org/chat/providerOperationId/history:

integrity failure;

provider call не начинается.

Replay

same clientRequestId + same semantic request = replay;

same id + different semantic request = conflict;

изменение текущего envelope/config после первоначального request не ломает replay исходной durable operation.

Capability gate

Пока data plane не реализован полностью:

TEXT               -> разрешён по существующим правилам
TOOLS              -> fail closed
VISION             -> fail closed
STRUCTURED_OUTPUT  -> fail closed

Никакой capability metadata не должна автоматически включать unsupported execution path.

3.3. Frontend Model Control Plane hardening

До выхода из stabilization:

runtime wording не должен путать enabled и реальную network availability;

probe state показывается отдельно;

policy modal предупреждает о runtime/effective mismatch;

ADMIN не видит global catalog mutations;

SUPER_ADMIN видит import/create actions;

latest/effective/scheduled visual state различим;

route-decision diagnostics корректно parses integrity v1/v2/v3;

exact decimal money остаётся string-safe;

narrow viewport остаётся рабочим.

3.4. Exit criteria этапа 0

Этап считается закрытым только если:

fresh migration проходит;

representative upgrade проходит;

backend test suite зелёный;

frontend check зелёный;

critical integration suites зелёные;

backup/restore проверен;

V48 integrity/provenance matrix доказана тестами;

README/ROADMAP соответствуют реальному baseline;

нет P0/P1 известных дефектов в auth/tenant/idempotency/routing/provenance.

4. Этап 1 — Knowledge UX + RAG Evaluations

Период: сентябрь–октябрь 2026
Приоритет: высокий

Knowledge backend/frontend baseline уже существует. Следующая задача — закрыть качественный vertical slice и сделать 
качество измеримым.

4.1. Knowledge UX hardening

ADMIN flow

Create Knowledge Base
→ visibility / memberships
→ upload document
→ immutable version
→ ingestion status
→ READY / FAILED + reason
→ retry / reindex
→ health

USER flow

Chat
→ Knowledge mode / KB scope
→ question
→ grounded answer
→ citations
→ source drawer
→ exact document version/page
→ Answer Passport

4.2. Source Drawer

Показывать:

citation number;

Knowledge Base;

document;

immutable document version;

page/section;

bounded evidence excerpt;

retrieval score/provenance where appropriate;

retrieval run identity;

source status.

Не показывать неограниченные raw chunks.

4.3. Answer Passport UI

Показывать:

provider;

requested model;

resolved model;

exact Model Catalog snapshot;

ModelRouteDecision;

organization policy snapshot;

input-accounting version;

reservation/envelope metadata;

embedding model;

Knowledge Bases;

retrieval run;

retrieved chunks;

source document/version/page;

citations/evidence status;

usage;

pricing/cost quality;

request ID;

timestamps.

4.4. Evaluation Dataset

Пример:

Question:
"Какой срок хранения договора?"

Expected evidence:
documentVersion = 7
page = 14

Expected concepts:
"5 лет"

Dataset должен поддерживать:

expected source;

expected document version;

expected page/section;

expected concepts;

answerable/unanswerable;

expected abstention;

tenant/KB scope.

4.5. Метрики

retrieval recall;

retrieval precision;

groundedness;

citation correctness;

answer relevance;

abstention correctness;

latency;

input units;

provider usage;

cost/data-quality;

model/provider comparison.

4.6. Regression gates

При изменении:

system prompt/config;

embedding model;

chunking;

extraction;

retrieval parameters;

RRF;

model/provider;

Knowledge ingestion;

model-routing policy;

accounting/envelope logic;

запускать regression suite.

Release должен блокироваться при существенной деградации заранее определённых quality thresholds.

4.7. Exit criteria

есть versioned evaluation dataset;

baseline metrics зафиксированы;

regression можно запускать автоматически;

source/citation/passport UX закрывает end-to-end flow;

KNOWLEDGE_ONLY abstention тестируется;

retrieval quality перестаёт оцениваться только вручную.

5. Этап 2 — Multi-provider / multi-model data plane

Период: октябрь–ноябрь 2026
Приоритет: высокий

Текущий Model Control Plane умеет принимать governance decision, но deployment пока имеет один физически активный 
provider/model adapter.

Следующий архитектурный шаг — исполняемый provider/model multiplexer.

5.1. Целевая схема

ModelRouteDecision
        ↓
exact provider/model selection
        ↓
Provider Configuration Registry
        ↓
Provider Adapter Registry
        ↓
Execution

5.2. Требования

immutable selected provider/model;

exact provider configuration identity/version;

provider credential reference, но не secret snapshot;

capability-aware execution;

timeout budget per provider;

retry budget per provider;

provider-specific retry classification;

no blind retry after ambiguous I/O;

provider-specific idempotency where available;

deterministic failure taxonomy;

controlled fallback policy;

audit/provenance;

exact usage/pricing mapping;

runtime health as signal, не immutable catalog field.

5.3. Routing invariant

Control Plane принимает governance decision.
Data Plane исполняет уже разрешённый exact route.

Не делать:

if provider == "openai" ...
else if provider == "anthropic" ...

внутри ChatService.

Provider selection должен быть самостоятельной boundary.

5.4. Fallback

Fallback допустим только если:

policy явно разрешает;

alternative model/provider заранее входит в разрешённый decision space;

новая попытка имеет отдельное immutable evidence;

ambiguous first call не маскируется retry;

budget/data-residency/capability ограничения выполняются повторно.

5.5. Exit criteria

минимум два реальных provider adapters исполнимы внутри одного deployment;

route decision однозначно приводит к выбранному adapter/config;

provider-specific failure mapping покрыт тестами;

fallback не нарушает ambiguity invariant;

usage привязан к exact resolved model/provider.

6. Этап 3 — Model Registry / Runtime expansion

Период: ноябрь 2026

Базовый Model Catalog уже существует. Расширять его, не создавать параллельный registry.

Добавить

provider configuration identity/version;

richer capabilities;

reasoning modes;

structured output capability metadata;

multimodal limits;

data residency;

provider region;

cache-read/cache-write pricing dimensions;

batch pricing;

deprecation dates;

production approval workflow;

provider health/circuit state как runtime signal;

provider/model compatibility metadata.

Важный принцип

Immutable catalog fact:

что заявлено/разрешено/версионировано

Runtime signal:

что сейчас доступно/здорово/деградирует

Не смешивать эти сущности.

Frontend не должен хардкодить доступные модели.

7. Этап 4 — Organization Model Policies expansion

Период: ноябрь–декабрь 2026

Текущий policy уже покрывает organization-level allow/deny/default, limits, cost/budget и некоторые provider-data требования.

Расширить scope до:

role;

group/department;

assistant/use case;

data classification;

provider region/residency;

model capabilities;

fallback permissions;

approved reasoning modes;

provider-specific restrictions;

local-model-only policy;

tool/structured-output permission boundaries.

Не превращать текущий record в универсальный mega-policy.

Этот этап должен подготовить переход к General Policy Engine.

8. Этап 5 — AI FinOps / Budgets

Период: декабрь 2026

Текущий monthly budget — conservative governance preflight.

Следующий этап — полноценный FinOps control plane.

8.1. Budget hierarchy

Organization
→ Department
→ User
→ Assistant
→ Agent
→ Model

8.2. Возможности

monthly organization budget;

department budget;

user quota;

model budget;

input/output unit quota;

request quota;

daily limit;

hard/soft budget;

reservation;

reconciliation;

provider invoice reconciliation;

versioned pricing;

currency semantics;

unpriced/unknown quality;

forecast.

8.3. Реакции

Пример:

80%   -> warning
100%  -> restrict/downgrade according to policy
>100% -> controlled block where HARD requires it

8.4. Dashboard

Cost/usage:

organization;

department;

user;

model;

provider;

use case;

assistant;

agent;

Knowledge/RAG;

embeddings;

cached-token savings;

forecast;

budget utilization;

unknown/unpriced/pricing-failed quality.

Unknown cost никогда не считать нулём.

8.5. Exit criteria

preflight и actual reconciled usage разделены;

reservations корректно release/reconcile;

ambiguous operations учитываются консервативно;

pricing version фиксируется;

budget decisions auditable.

9. Этап 6 — Data Classification + DLP

Период: конец 2026 → начало 2027

Целевая цепочка

User input / retrieved context
        ↓
Data Classification
        ↓
DLP / PII / Secrets
        ↓
Policy Engine
        ↓
Model Routing
        ↓
Provider

Классы

PUBLIC
INTERNAL
CONFIDENTIAL
RESTRICTED

Detection

email/phone;

passport/ID;

card/payment data;

API keys;

tokens;

passwords;

private keys;

access credentials;

internal identifiers;

company-specific secret dictionaries.

Actions

ALLOW
WARN
REDACT
BLOCK
REQUIRE_APPROVAL
ROUTE_TO_LOCAL_MODEL

DLP decision должен иметь immutable provenance/audit.

Prompt instructions не являются DLP authorization.

10. Этап 7 — Groups / Departments

Период: начало 2027

Текущая модель:

Organization
→ Users

Целевая:

Organization
→ Groups / Departments
→ Users

На group назначаются:

Knowledge access;

Assistant access;

Model access;

budget;

DLP policy;

tool access;

data classification rules;

connector ACL mapping.

Group membership должен учитываться tenant-safe и versioned/auditable там, где влияет на execution.

11. Этап 8 — General Policy Engine

Период: начало 2027

OrganizationModelPolicy остаётся model-governance policy.

Более широкий enterprise policy не должен превращать его в mega-object.

Создать централизованный policy evaluation layer.

Пример:

WHEN
  organization = ACME
  AND department = FINANCE
  AND classification >= CONFIDENTIAL
THEN
  deny external providers
  allow approved local models
  audit = HIGH

Decision space:

ALLOW
DENY
WARN
REDACT
REQUIRE_APPROVAL
ROUTE

Policy decision должен быть:

versioned;

auditable;

immutable per execution;

связан с model route / DLP / tool execution provenance.

12. Этап 9 — Enterprise Knowledge Connectors

Период: Q1 2027

Manual upload сохранить.

Добавить:

Confluence;

SharePoint;

OneDrive;

Google Drive;

Notion;

Jira;

Git;

Web site;

internal API.

Connector pipeline

Source
→ sync cursor
→ incremental sync
→ immutable version
→ extraction
→ chunks
→ embeddings
→ index

Обязательно

ACL mirroring;

source deletion handling;

sync health;

retry;

last/next sync;

partial failure;

credential rotation;

connector-specific rate limits;

source provenance;

tenant isolation;

tombstone/deletion semantics.

13. Этап 10 — SSO / SCIM

Период: Q1 2027

Authentication

OIDC;

Microsoft Entra ID;

Okta;

Google Workspace;

SAML только при реальной customer requirement.

Provisioning

SCIM;

automatic user create/disable;

group sync;

department sync.

Ключевой invariant:

Employee disabled in IdP
→ SafeAI user disabled
→ security epoch/session invalidation
→ AI access disappears

14. Этап 11 — AI Governance / Use Case Registry

Период: Q1–Q2 2027

Добавить AI Use Case Registry:

Use case
Owner
Purpose
Models
Data classes
Human oversight
Risk
Approval state

Risk profile может учитывать:

personal/confidential data;

autonomous actions;

external provider;

employment/financial/legal/customer impact;

human oversight.

SafeAI Desk обеспечивает technical governance workflow, а не юридическое заключение.

15. Этап 12 — AI Literacy / Acceptable Use

Период: Q1–Q2 2027

При первом входе пользователь принимает organization AI policy.

Хранить:

policyVersion
userId
organizationId
acceptedAt

При новой policy version можно требовать re-acceptance.

Добавить:

version history;

forced reacceptance;

audit;

admin compliance view.

16. Этап 13 — Prompt / Configuration Registry

Период: Q2 2027

System prompts/configuration не должны существовать только в Git/source code.

Создать:

PromptTemplate
PromptVersion
ConfigurationVersion

ChatTurn / Answer Passport должны сохранять exact configuration provenance.

Нужны:

immutable versions;

draft/approved lifecycle;

environment promotion;

rollback;

owner;

changelog;

evaluation binding;

route/use-case binding.

17. Этап 14 — AI Observability

Период: Q2 2027

Целевой trace:

HTTP request
  ↓
ChatTurn
  ↓
Policy / Model Route
  ↓
Retrieval
  ↓
Embedding
  ↓
Provider
  ↓
Tool calls

Использовать OpenTelemetry и актуальные GenAI semantic conventions после проверки действующего стандарта на момент реализации.

Не логировать raw secrets/prompts по умолчанию.

Observability должна дополнять, а не заменять durable audit/provenance.

18. Этап 15 — Enterprise Assistants

Период: Q2 2027

ADMIN создаёт Assistant:

Name
Prompt/config version
Knowledge scope
Allowed models/policy
Tools
Available groups
Budget
Use case

Пользователь выбирает управляемый Assistant, а не вручную собирает model/KB/prompt policy.

Assistant должен быть versioned configuration object, а не только frontend preset.

19. Этап 16 — MCP Gateway

Период: Q2–Q3 2027

Модель не получает прямой доступ к произвольным MCP servers.

AI runtime
   ↓
SafeAI Tool Gateway
   ├─ authentication
   ├─ authorization
   ├─ tenant isolation
   ├─ policy
   ├─ approval
   ├─ audit
   ├─ secrets
   ├─ DLP
   └─ rate limits
   ↓
MCP Servers / internal tools

20. Этап 17 — Tool Registry

Период: Q2–Q3 2027

Tool metadata:

name
capabilities
riskLevel
organization/groups
read/write
credential binding
requiredRole
approvalPolicy
rateLimit
dataClassification

Примеры:

Jira.search        LOW       approval=false
Jira.createTicket  MEDIUM    approval=policy
ProdDB.execute     CRITICAL  approval=required

21. Этап 18 — Human-in-the-loop approvals

Период: Q3 2027

Перед side effect:

agent proposed
→ approval requested
→ authorized approver approves/denies
→ tool invoked exactly once
→ result/provenance stored

Для high-risk actions:

multi-party approval;

expiry;

exact payload hash;

approver role requirements;

post-approval mutation protection.

Approval должен относиться к exact action payload, а не к абстрактному «разрешить агенту».

22. Этап 19 — Durable Agent Runtime

Период: Q3 2027

Перенести принципы ChatTurn на agents.

Сущности:

AgentRun
AgentStep
ToolExecution
Approval

State machine:

CREATED
RUNNING
WAITING_FOR_APPROVAL
WAITING_FOR_TOOL
SUCCEEDED
FAILED
AMBIGUOUS
CANCELLED

Каждый external side effect:

idempotency;

lease;

fencing;

timeout;

retry classification;

audit;

provenance.

Не использовать бесконечный volatile loop:

LLM → tool → LLM → tool

без durable semantics.

23. Этап 20 — Agent Security

Период: одновременно с Agent Runtime

Capability-based access:

Agent
→ exact tools
→ exact scopes
→ exact organization
→ exact credentials

Главный invariant:

prompt content != authorization

Retrieved document, website или tool output не может расширить permissions инструкцией в тексте.

Добавить:

prompt-injection resistant tool boundary;

capability tokens/scopes;

credential isolation;

data egress control;

high-risk tool policy;

agent-specific rate limits;

max-step/max-cost limits.

24. Этап 21 — Sandboxed Code Interpreter

Период: Q3–Q4 2027

Для CSV/Excel/PDF/data analysis/charts/Python использовать ephemeral sandbox:

CPU limit;

RAM limit;

disk limit;

timeout;

network disabled by default;

ephemeral filesystem;

per-run isolation;

artifact export controls;

package allowlist/policy.

Не исполнять пользовательский Python внутри backend JVM.

25. Этап 22 — Service Accounts

Период: Q3–Q4 2027

Machine identity:

ServiceAccount
organization
scopes
allowed models
budget
rate limit
credential/expiry
audit actor
allowed tools

Use cases:

CI/CD reviewer;

CRM summarizer;

ticket classifier;

scheduled analysis;

internal automation.

Service Account не должен маскироваться под обычного пользователя.

26. Этап 23 — Enterprise API

Период: Q4 2027

SafeAI становится infrastructure layer не только для собственного UI.

Пример будущего API:

POST /api/v1/ai/responses
POST /api/v1/retrieval/search
POST /api/v1/assistants/{id}/runs
POST /api/v1/agents/{id}/runs

На вызовы продолжают применяться:

identity;

tenant isolation;

routing;

budget;

DLP;

policy;

audit;

usage;

governance;

rate limits.

Нужны:

service-account auth;

API versioning;

idempotency;

quota;

observability;

stable public error contract.

27. Этап 24 — A2A / Agent interoperability

Период: конец 2027

Только после identity/tool/policy layer:

SafeAI Agent
    ↓
controlled A2A gateway
    ↓
External enterprise agent

Обязательны:

identity/trust;

tenant isolation;

policy;

audit;

budget;

tool/capability boundary;

provenance;

replay/idempotency;

external-agent trust profile.

28. Этап 25 — Data Retention / Legal Hold / Privacy

Период: 2027

Добавить policy-driven:

retention;

deletion jobs;

legal hold;

export;

pseudonymization;

personal-data deletion workflow.

Не смешивать retention periods для:

chat;

audit;

usage;

Knowledge source;

embeddings/indexes;

Answer Passport;

route decisions;

tool execution;

agent runs.

29. Этап 26 — AI Security Center

Период: 2027

Отдельная сущность SecurityEvent, а не только AuditEvent.

Dashboard:

prompt injection attempts;

DLP blocks;

secret detections;

blocked tool calls;

policy violations;

suspicious agent activity;

cross-tenant anomaly attempts;

unusual provider/tool behavior.

Security events должны быть tenant-safe, severity-aware и correlation-friendly.

30. Этап 27 — Continuous Red Team / Security Evals

Период: конец 2027

Автоматически тестировать:

prompt injection;

jailbreak;

cross-tenant leakage;

source poisoning;

secret exfiltration;

system prompt extraction;

unsafe tool usage;

privilege escalation;

malicious MCP server;

malicious retrieved documents;

policy bypass;

approval bypass;

replay/idempotency abuse.

После изменения:

model
prompt/config
knowledge
retrieval
tool
agent
policy
provider

запускать security evaluation suite.

31. Product versions — обновлённый ориентир

Версии ниже — planning targets, а не утверждение о существующих release tags.

Pre-1.0 baseline — текущее состояние, сентябрь 2026

Уже есть:

Security / multi-tenancy;

Durable ChatTurn;

Audit / usage quality;

Knowledge/RAG backend + frontend baseline;

Answer Passport;

V45–V48 Model Control Plane;

V48 input-accounting provenance;

production-oriented infra baseline.

SafeAI Desk 0.8 candidate — осень 2026

Цель:

V48 release-candidate verification;

Knowledge UX polish;

Source Drawer / Passport UX completion;

RAG evaluation framework;

regression gates;

runtime/control-plane UX hardening;

multi-provider data-plane implementation/prototype.

SafeAI Desk 0.9 candidate — конец 2026

Цель:

multi-provider/model execution;

provider configuration registry;

Model Registry expansion;

Organization Model Policy expansion;

FinOps/budget reconciliation;

DLP v1;

Groups/Departments baseline;

Connector v1.

SafeAI Desk 1.0 candidate — 2027

Цель:

OIDC / SCIM;

production multi-provider control/data plane;

Knowledge connectors;

DLP;

Policy Engine;

Use Case Registry;

Prompt Registry;

Evaluations;

OpenTelemetry traces;

production deployment drills;

backup/restore;

HA/SLO evidence.

SafeAI Desk 1.5 candidate — середина 2027

Цель:

Enterprise Assistants;

MCP Gateway;

Tool Registry;

Human approvals;

Service Accounts;

Enterprise API;

Sandboxed Code Interpreter.

SafeAI Desk 2.0 candidate — конец 2027

Цель:

Durable Agents;

Agent workflows;

A2A;

multi-agent collaboration;

Agent Security Center;

continuous AI red teaming;

advanced policy/governance automation.

32. Практический порядок разработки с текущего момента

1. V48 production stabilization
        ↓
2. Fresh/upgrade migrations + RC verification
        ↓
3. Knowledge UX + citations/passport polish
        ↓
4. Retrieval/RAG evaluations + regression gates
        ↓
5. Multi-provider/model data plane
        ↓
6. Model Registry / policy expansion
        ↓
7. FinOps / budget reconciliation
        ↓
8. Data Classification / DLP
        ↓
9. Groups / Departments
        ↓
10. Enterprise Knowledge Connectors
        ↓
11. OIDC / SCIM
        ↓
12. General Policy Engine / Governance
        ↓
13. Prompt / Configuration Registry
        ↓
14. Enterprise Assistants
        ↓
15. MCP Gateway / Tool Registry
        ↓
16. Human approvals
        ↓
17. Durable Agents
        ↓
18. Enterprise API / Service Accounts
        ↓
19. A2A

Не начинать production agents, пока отдельно не доказаны:

tool authorization;

approval;

idempotency;

durable execution;

DLP;

budget;

credential isolation;

audit/provenance.

33. Главные продуктовые приоритеты

Если выбирать три крупных направления после V48 stabilization:

1. Knowledge quality + Evaluations

Не просто:

RAG работает

а:

качество измеряется
источники доказуемы
регрессия обнаруживается автоматически

2. Executable Model Control Plane + FinOps + DLP

V45–V48 governance должен получить полноценный multi-provider data plane и финансово/security-контролируемое исполнение.

3. Enterprise Assistants + MCP Gateway

Только после identity/policy/data controls давать управляемую автоматизацию и готовить платформу к agents.

34. Что не нужно делать сейчас

Чтобы не размыть архитектуру, до завершения текущих этапов не стоит:

строить второй Model Registry параллельно существующему Catalog;

добавлять agent loop непосредственно в ChatService;

включать TOOLS/VISION только потому, что появились capability flags;

считать budget billing ledger;

хранить provider secrets в route decisions;

разрешать frontend выбирать физический provider в обход policy;

использовать prompt text как authorization;

делать fallback после ambiguous provider call;

превращать OrganizationModelPolicy в универсальный policy mega-object;

добавлять новый infrastructure layer без чёткой failure semantics;

усложнять систему микросервисами до появления реальной operational необходимости.

35. Архитектурные invariants, которые нельзя ломать

Вне зависимости от новых features должны сохраняться:

tenant isolation
security fail-closed
exactly-one-role
append-only governance snapshots
idempotency
semantic replay identity
lease/fencing
provider-call ambiguity safety
no blind retry
audit/provenance
database-enforced integrity
unknown != zero
prompt content != authorization
provider I/O outside long DB transaction
exact route evidence before execution
exact request identity across preparation/execution

Для Model Control Plane дополнительно:

latest != effective
policy != runtime
catalog != runtime health
governance decision != provider execution
historical integrity versions are immutable
new accounting provenance must be explicit
unsupported capabilities fail closed

36. Конечная идея SafeAI Desk

SafeAI Desk должен контролировать:

WHO

Кто использует AI.

WHAT

Какие данные и знания доступны.

WHICH MODEL

Какая model/policy/catalog version реально применена.

HOW

Какая execution configuration и accounting model применялась.

HOW MUCH

Сколько разрешено и сколько реально потрачено.

WHY

Для какого use case используется AI.

WHAT HAPPENED

Что реально произошло.

WHAT EVIDENCE

На каких данных основан ответ.

WHAT ACTION

Какое действие хочет выполнить AI.

WHO APPROVED

Кто разрешил side effect.

Конечная архитектура:

AI Gateway
+ Knowledge Platform
+ Model Control Plane
+ Governance
+ FinOps
+ Secure Tool Gateway
+ Durable Agent Runtime

Главный принцип развития:

новые routing, RAG, governance, FinOps, connector, tool и agent capabilities не должны ослаблять уже реализованные 
durability, tenant isolation, idempotency, provider-ambiguity protection, provenance и database-enforced integrity.