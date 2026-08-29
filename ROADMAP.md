SafeAI Desk — Roadmap развития с августа 2026

Актуальность: 28 августа 2026
Горизонт: осень 2026 → конец 2027
Проект: SafeAI Desk
Позиционирование: production-oriented full-stack корпоративная AI-платформа / AI Gateway для безопасного использования 
внешних и локальных AI-моделей внутри организаций.

Этот roadmap синхронизирован с фактическим baseline до Flyway V45 включительно.
Knowledge/RAG и первый Model Control Plane уже реализованы; они больше не указаны как полностью будущие эпики.
Будущие номера Flyway-миграций намеренно не резервируются заранее: перед merge всегда использовать реально свободный 
version.

1. Текущее состояние проекта

SafeAI Desk уже выходит за рамки интерфейса над LLM API.

На текущем этапе реализован фундамент:

Identity / Security

organization-based multi-tenancy;

RBAC SUPER_ADMIN / ADMIN / USER;

HttpOnly cookie JWT authentication;

CSRF-защита;

строгая JWT validation;

user tokenVersion и organization authVersion;

refresh-token rotation;

refresh-token reuse detection;

tenant-safe user/organization management;

production security invariant validators;

trusted-proxy/client-IP handling;

canonical server request IDs.

Durable AI execution

OpenAI / Anthropic / mock provider abstraction;

durable ChatTurn state machine;

idempotency;

lease + fencing;

provider-call boundary;

recovery;

AMBIGUOUS outcome protection;

Redis rate limiting;

quota reservation semantics.

Audit / Usage

transactional audit outbox;

immutable actor snapshots;

immutable target organization snapshots;

audit retention/retry/dead-letter;

usage analytics;

pricing/data-quality semantics без подмены unknown нулём;

UTC rollups/reconciliation.

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

KNOWLEDGE_ONLY controlled abstention;

retrieval provenance;

Answer Passport;

Knowledge health/reindex/evaluation backend;

frontend для KB/documents/memberships/ingestion/citations/passport baseline.

Model Control Plane — реализован V45 baseline

append-only versioned Model Catalog;

lifecycle/capabilities/modalities;

retention/training metadata;

pricing semantics;

scheduled effectiveFrom;

separate latest-created и effective-at-route snapshots;

organization model policies;

allow/deny/default model rules;

token limits;

max-request-cost preflight;

monthly budget preflight SOFT/HARD;

requireCompletePricing / no-training / ZDR requirements;

immutable model-route decisions;

decision digest;

exact binding route decision → ChatTurn;

Answer Passport → exact model-route evidence;

frontend Model Control Plane page.

Infrastructure

PostgreSQL/Flyway integrity constraints;

Redis;

S3-compatible storage;

Docker/Docker Compose;

Nginx;

Prometheus rules;

production validation/deploy scripts;

PostgreSQL backup/restore scripts;

systemd backup timer/service;

production secret bootstrap examples.

Текущий фокус должен быть не «добавить ещё один чат», а стабилизировать текущий control plane, измерить качество RAG и 
затем отделить governance plane от полноценного multi-provider data plane.

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

Эволюция:

2026
Безопасно пользоваться AI
        ↓
Безопасно использовать корпоративные знания
        ↓
Контролировать разрешённые модели и evidence

2027
Безопасно давать AI доступ к enterprise data sources
        ↓
Управлять DLP / policy / budgets / identity
        ↓
Безопасно позволять AI выполнять действия

3. Этап 0 — V45 production stabilization

Приоритет: максимальный
Период: сейчас / перед следующим крупным epic

V45 уже реализован, поэтому ближайшая задача — не проектировать Model Registry заново, а доказать корректность текущего 
baseline.

Обязательные проверки

fresh migrations V1 → V45;

representative populated upgrade V44 → V45;

Flyway checksum consistency;

backend clean test;

frontend ci/check;

container build/start;

prod-like startup invariants;

tenant isolation;

model catalog authorization;

policy authorization;

route decision authorization;

deferred ChatTurn/route-decision integrity;

Answer Passport/route-decision integrity;

backup/restore drill.

V45 semantic regression cases

Future-scheduled catalog version не становится executable до effectiveFrom.

Latest-created view показывает future snapshot отдельно от effective snapshot.

Route time берётся с server-controlled Clock.

После смены provider/model в новой effective версии старая версия того же modelKey не может пройти runtime matching.

UNPRICED/FREE/CONFIGURED/INCOMPLETE соответствуют DB constraints.

Money fields соответствуют numeric(30,12).

Invalid policy/cost input возвращает controlled 4xx, а не случайный database 500.

HARD budget fail-closed при unverifiable monthly cost.

ALLOWED route commit невозможен без exact planned ChatTurn.

Denied route evidence сохраняется без создания provider operation.

Документация

README/ROADMAP должны описывать V45 как current baseline, а не future work.

4. Этап 1 — Knowledge UX + RAG Evaluations

Период: сентябрь–октябрь 2026

Knowledge backend/frontend baseline уже существует. Следующая задача — закрыть продуктовый vertical slice и сделать 
качество измеримым.

Knowledge UX hardening

ADMIN:

создаёт Knowledge Base
→ visibility / memberships
→ upload document
→ immutable version
→ ingestion status
→ READY / FAILED + reason
→ retry/reindex
→ health

USER:

Chat
→ Knowledge mode / KB scope
→ question
→ grounded answer
→ citations
→ source drawer
→ exact document version/page
→ Answer Passport

Source Drawer

Показывать:

citation number
Knowledge Base
document
document version
page/section
bounded evidence excerpt
retrieval score/provenance where appropriate

Answer Passport UI

Показывать:

provider
requested/resolved model
model catalog snapshot
model route decision
policy snapshot
embedding model
Knowledge Bases
retrieval run
retrieved chunks
source documents/versions/pages
citations/evidence status
token usage
pricing/cost quality
request ID
timestamps

Evaluation Dataset

Пример:

Question:
"Какой срок хранения договора?"

Expected evidence:
documentVersion = 7
page = 14

Expected concepts:
"5 лет"

Метрики

retrieval recall;

retrieval precision;

groundedness;

citation correctness;

answer relevance;

abstention correctness;

latency;

token usage;

cost/data-quality;

model/provider comparison.

Regression gates

При изменении:

system prompt
embedding model
chunking
retrieval parameters
RRF
model/provider
Knowledge ingestion
model routing policy

запускать regression suite и уметь блокировать release при значимой деградации.

5. Этап 2 — Multi-provider / multi-model data plane

Период: октябрь–ноябрь 2026

V45 Model Control Plane уже принимает governance decision, но deployment пока устанавливает один физический 
provider/model adapter.

Следующий архитектурный шаг — не новый catalog, а исполняемый provider/model multiplexer.

Требования

ModelRouteDecision
        ↓
exact provider/model selection
        ↓
provider registry / adapter registry
        ↓
execution

Нужно обеспечить:

immutable selected provider/model;

exact provider configuration snapshot/version;

capability-aware execution;

timeout/retry budget per provider;

no blind retry after ambiguous external I/O;

provider-specific idempotency support where available;

deterministic failure taxonomy;

controlled fallback policy;

audit/provenance;

usage/pricing mapping to exact resolved model.

Не делать

Не превращать routing в набор if provider == ... внутри ChatService.

Control Plane принимает policy decision; Data Plane исполняет уже разрешённый route.

6. Этап 3 — Model Registry expansion

Статус: базовый Model Catalog уже реализован в V45.
Период расширения: ноябрь 2026

Развивать текущую сущность, а не создавать параллельный registry.

Дополнительно понадобятся:

provider configuration identity/version;

richer capabilities;

reasoning modes;

structured output schemas/capabilities;

multimodal limits;

data residency;

provider region;

cache-read/cache-write billing dimensions;

batch pricing;

deprecation dates;

production approval workflow;

health/circuit state как runtime signal, не immutable catalog fact.

Frontend не должен хардкодить доступные модели.

7. Этап 4 — Organization Model Policies expansion

Статус: baseline уже реализован в V45.
Период расширения: ноябрь–декабрь 2026

Текущий policy покрывает organization-level allow/deny/default, token/cost/budget и некоторые provider-data requirements.

Расширить scope до:

role;

group/department;

assistant/use case;

data classification;

provider region/residency;

model capabilities;

fallback permissions;

approved reasoning modes;

provider-specific restrictions.

Не размазывать новые правила по ChatService/frontend conditions.

8. Этап 5 — AI FinOps / Budgets

Период: декабрь 2026

V45 monthly budget — conservative governance preflight. Следующий этап должен превратить usage + route estimates в 
полноценный FinOps control plane.

Budget hierarchy

Organization
→ Department
→ User
→ Assistant
→ Agent
→ Model

Возможности

monthly organization budget;

user quota;

department budget;

model budget;

token quota;

request quota;

daily limit;

hard/soft budget;

reservation/reconciliation;

provider invoice reconciliation;

currency/versioned pricing semantics.

Реакции

80%  → warning
100% → restrict/downgrade according to policy
>100% → controlled block where HARD policy requires it

Dashboard

cost by organization/user/department/model/use case/assistant/agent;

cached-token savings;

RAG/embedding cost;

forecast;

budget utilization;

unknown/unpriced/pricing-failed quality.

Unknown cost нельзя считать нулём.

9. Этап 6 — Data Classification + DLP

Период: конец 2026 → начало 2027

До внешнего provider:

User input / retrieved context
    ↓
Data Classification
    ↓
DLP / PII / secrets
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

API keys/tokens/passwords;

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

DLP decision должен иметь provenance/audit и не должен зависеть от prompt instructions.

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

data classification rules.

11. Этап 8 — General Policy Engine

Период: начало 2027

OrganizationModelPolicy остаётся model-governance policy. Более широкий enterprise policy не должен превращать этот 
record в универсальный mega-object.

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

Policy decision должен быть versioned/auditable и привязан к execution provenance.

12. Этап 9 — Enterprise Knowledge Connectors

Период: Q1 2027

Manual upload оставить, добавить источники:

File upload
Confluence
SharePoint
OneDrive
Google Drive
Notion
Jira
Git
Web site
Internal API

Connector pipeline:

Source
→ sync cursor
→ incremental sync
→ immutable version
→ extraction
→ chunks
→ embeddings
→ index

Обязательно:

ACL mirroring;

source deletion handling;

sync health;

retry;

last/next sync;

partial failure;

credential rotation;

connector-specific rate limits;

source provenance.

13. Этап 10 — SSO / SCIM

Период: Q1 2027

Authentication:

OIDC;

Microsoft Entra ID;

Okta;

Google Workspace;

SAML при реальной customer requirement.

Provisioning:

SCIM;

automatic user create/disable;

group sync;

department sync.

Employee disabled in IdP
→ SafeAI user disabled
→ security epoch/session revocation
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

Risk profile:

personal/confidential data;

autonomous actions;

external provider;

employment/financial/legal/customer impact;

human oversight.

SafeAI Desk обеспечивает технический governance workflow, а не юридическое заключение.

15. Этап 12 — AI Literacy / Acceptable Use

Период: Q1–Q2 2027

При первом входе пользователь принимает organization AI policy.

Хранить:

policyVersion
userId
organizationId
acceptedAt

При новой policy version можно требовать re-acceptance.

16. Этап 13 — Prompt / Configuration Registry

Период: Q2 2027

System prompts и execution configuration не должны существовать только в Git/source code.

Создать:

PromptTemplate
PromptVersion
ConfigurationVersion

ChatTurn/Answer Passport должны уметь сохранять exact configuration provenance.

17. Этап 14 — AI Observability

Период: Q2 2027

Единый trace:

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

Использовать OpenTelemetry и актуальные GenAI semantic conventions после проверки их текущего стандарта на момент 
реализации.

Не логировать raw secrets/prompts по умолчанию ради observability.

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

Пользователь выбирает управляемый Assistant, а не вручную собирает model/KB/prompt policy.

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

Для high-risk actions поддержать multi-party approval.

22. Этап 19 — Durable Agent Runtime

Период: Q3 2027

Перенести принципы ChatTurn на agents.

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

Не использовать бесконечный volatile цикл LLM → tool → LLM → tool без durable semantics.

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

Retrieved document или внешний сайт не может расширить permissions агента инструкцией в тексте.

24. Этап 21 — Sandboxed Code Interpreter

Период: Q3–Q4 2027

Для CSV/Excel/PDF/data analysis/charts/Python использовать ephemeral sandbox:

CPU limit
RAM limit
disk limit
timeout
network disabled by default
ephemeral filesystem

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

Use cases: CI/CD reviewer, CRM summarizer, ticket classifier, scheduled analysis.

26. Этап 23 — Enterprise API

Период: Q4 2027

SafeAI становится infrastructure layer не только для собственного UI.

Пример будущего API:

POST /api/v1/ai/responses
POST /api/v1/retrieval/search
POST /api/v1/assistants/{id}/runs
POST /api/v1/agents/{id}/runs

На все вызовы продолжают применяться routing/budget/DLP/policy/audit/usage/governance.

27. Этап 24 — A2A / Agent interoperability

Период: конец 2027

Только после identity/tool/policy layer:

SafeAI Agent
    ↓
controlled A2A gateway
    ↓
External enterprise agent

Identity/trust/policy/tenant isolation/audit/budget обязательны.

28. Этап 25 — Data Retention / Legal Hold / Privacy

Период: 2027

Добавить policy-driven:

retention;

deletion jobs;

legal hold;

export;

pseudonymization;

personal-data deletion workflow.

Не смешивать retention periods для chat, audit, usage, Knowledge source и Answer Passport без явной policy.

29. Этап 26 — AI Security Center

Период: 2027

Отдельная сущность SecurityEvent, а не только AuditEvent.

Dashboard:

Prompt injection attempts
DLP blocks
Secret detections
Blocked tool calls
Policy violations
Suspicious agent activity

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

malicious retrieved documents.

После изменения model/prompt/knowledge/tool/agent/policy запускать security evaluation suite.

31. Версии продукта — обновлённый ориентир

Версии ниже — product planning, а не утверждение о release tag.

Pre-1.0 baseline — текущее состояние, август 2026

Уже есть:

Security / multi-tenancy
Durable ChatTurn
Audit / usage quality
Knowledge/RAG backend + frontend baseline
Answer Passport
V45 Model Control Plane
Production-oriented infra baseline

SafeAI Desk 0.8 candidate — осень 2026

Цель:

V45 release-candidate verification
Knowledge UX polish
Source Drawer / Passport UX completion
RAG evaluation framework
Regression gates
Multi-provider data-plane design/prototype

SafeAI Desk 0.9 candidate — конец 2026

Цель:

Multi-provider/model execution
Model Registry expansion
Organization Model Policies expansion
FinOps/budget reconciliation
DLP v1
Groups/Departments baseline
Connector v1

SafeAI Desk 1.0 candidate — 2027

Цель:

OIDC / SCIM
Production model control plane
Knowledge connectors
DLP
Policy Engine
Use Case Registry
Prompt Registry
Evaluations
OpenTelemetry traces
Production deployment drills
Backup / restore
HA/SLO evidence

SafeAI Desk 1.5 candidate — середина 2027

Enterprise Assistants
MCP Gateway
Tool Registry
Human approvals
Service Accounts
Enterprise API
Sandboxed Code Interpreter

SafeAI Desk 2.0 candidate — конец 2027

Durable Agents
Agent workflows
A2A
Multi-agent collaboration
Agent Security Center
Continuous AI red teaming
Advanced policy/governance automation

32. Практический порядок разработки с текущего момента

1. V45 production stabilization
        ↓
2. Fresh/upgrade migrations + full RC verification
        ↓
3. Knowledge UX + citations/passport polish
        ↓
4. Retrieval/RAG evaluations + release gates
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
13. Enterprise Assistants
        ↓
14. MCP Gateway / Tools
        ↓
15. Human approvals
        ↓
16. Durable Agents
        ↓
17. A2A

Не начинать agents, пока tool authorization/approval/idempotency/durable execution не сформированы как отдельные 
invariants.

33. Главные продуктовые приоритеты

Если выбирать три крупных направления после V45 stabilization:

1. Knowledge quality + Evaluations

Не просто «RAG работает», а качество измеряется и regression можно доказать.

2. Executable Model Control Plane + FinOps + DLP

V45 governance должен получить полноценный multi-provider data plane и финансово/security-контролируемое исполнение.

3. Enterprise Assistants + MCP Gateway

Только после policy/data controls давать управляемую автоматизацию и готовить платформу к agents.

34. Конечная идея SafeAI Desk

SafeAI Desk должен контролировать:

WHO
кто использует AI

WHAT
какие данные и знания доступны

WHICH MODEL
какая model/policy/version реально применена

HOW MUCH
сколько разрешено и сколько реально потрачено

WHY
для какого use case используется AI

WHAT HAPPENED
что реально произошло

WHAT EVIDENCE
на каких данных основан ответ

WHAT ACTION
какое действие хочет выполнить AI

WHO APPROVED
кто разрешил side effect

Конечная архитектура:

AI Gateway + Knowledge Platform + Model Control Plane + Governance + FinOps + Secure Agent Runtime.

При этом фундаментальные invariants текущего проекта должны сохраняться:

tenant isolation
security fail-closed
append-only governance snapshots
idempotency
lease/fencing
ambiguous external-I/O safety
audit/provenance
database-enforced integrity
unknown != zero
prompt content != authorization