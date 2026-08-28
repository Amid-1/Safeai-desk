SafeAI Desk — Roadmap развития с августа 2026

Актуальность: 28 августа 2026
Горизонт: осень 2026 → конец 2027
Проект: SafeAI Desk
Позиционирование: production-oriented full-stack корпоративная AI-платформа / AI Gateway для безопасного использования внешних и локальных AI-моделей внутри организаций.

1. Текущее состояние проекта

SafeAI Desk уже выходит за рамки обычного интерфейса над LLM API.

На текущем этапе фундамент проекта включает:

organization-based multi-tenancy;

RBAC SUPER_ADMIN / ADMIN / USER;

HttpOnly cookie JWT authentication;

CSRF-защиту;

строгую JWT validation;

user tokenVersion и organization authVersion;

refresh-token rotation;

refresh-token reuse detection;

tenant-safe управление пользователями и организациями;

Redis rate limiting;

transactional audit outbox;

usage analytics;

pricing/data-quality semantics без подмены неизвестных значений нулём;

OpenAI / Anthropic / mock provider abstraction;

durable ChatTurn state machine;

idempotency;

lease и fencing;

защиту от повторного provider call при неопределённом исходе;

tenant/ACL-aware Knowledge Bases;

immutable document versions;

durable ingestion;

S3-compatible object storage;

document extraction;

embeddings;

pgvector + FTS hybrid retrieval;

RAG context assembly;

inline citations;

knowledge-only fail-closed mode;

retrieval provenance;

Answer Passport;

PostgreSQL/Flyway integrity constraints;

React frontend с runtime validation API contracts и production error handling.

Следующий этап проекта должен быть не «добавить ещё один чат» или «подключить ещё одну модель», а развить SafeAI Desk в полноценный корпоративный AI Control Plane.

2. Целевое направление

Целевая архитектура:

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

Эволюция продукта:

2026
Безопасно пользоваться AI

        ↓

2027
Безопасно давать AI доступ к корпоративным данным

        ↓

2027+
Безопасно позволять AI выполнять действия внутри компании

3. Этап 1 — завершить Knowledge/RAG vertical slice

Приоритет: максимальный
Период: сентябрь–октябрь 2026

Нужно закончить полный пользовательский сценарий Knowledge.

ADMIN

создаёт Knowledge Base
→ задаёт visibility
→ назначает memberships
→ загружает документы
→ видит ingestion status
→ видит READY / FAILED
→ понимает причину ошибки
→ запускает reindex
→ управляет версиями и доступом

USER

открывает AI Chat
→ выбирает Knowledge Base
→ задаёт вопрос
→ получает grounded answer
→ видит citations
→ открывает источник
→ видит документ / версию / страницу
→ открывает Answer Passport

Реализовать

Knowledge UI

список Knowledge Bases;

создание;

редактирование;

ORGANIZATION / MEMBERS;

memberships;

enabled / disabled;

документы;

immutable versions;

upload;

ingestion state;

ingestion errors;

retry/reindex;

Knowledge health.

Source Drawer

Пример:

Источники

[1] Политика информационной безопасности.pdf
    Version 4
    Pages 12–13
    Knowledge Base: Security Policies

    "Передача конфиденциальных..."

Answer Passport UI

Показывать:

provider;

requested model;

resolved model;

embedding model;

Knowledge Bases;

retrieval run;

retrieved chunks;

source document;

document version;

page;

citations;

evidence status;

token usage;

cost;

request ID;

timestamps;

prompt/config version в будущем.

Knowledge modes

GENERAL
KNOWLEDGE_ASSISTED
KNOWLEDGE_ONLY

Для KNOWLEDGE_ONLY:

нет достаточного evidence
→ модель не должна придумывать ответ
→ возвращается controlled abstention

4. Этап 2 — Retrieval / RAG Evaluations

Период: октябрь–ноябрь 2026

До agents и сложной автоматизации необходимо создать Evaluation Framework.

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

cost;

provider/model comparison.

Regression evaluations

При изменении:

system prompt;

embedding model;

chunking;

retrieval parameters;

RRF;

model;

provider;

Knowledge ingestion;

SafeAI Desk должен запускать regression suite.

Пример:

Old configuration:
87.2%

New configuration:
82.4%

Result:
BLOCK RELEASE

5. Этап 3 — Model Registry

Период: ноябрь 2026

Создать централизованный Model Registry.

Model

Хранить:

provider;

provider model ID;

display name;

capabilities;

context window;

input price;

cached input price;

cache-write price;

output price;

multimodal support;

vision;

tool calling;

structured output;

reasoning;

data residency;

retention policy;

enabled;

deprecated;

production approved.

Frontend не должен хардкодить список моделей.

6. Этап 4 — Organization Model Policies

Период: ноябрь–декабрь 2026

Организация должна управлять тем, какие модели доступны её пользователям.

Пример:

Organization A

Allowed:
✓ premium OpenAI model
✓ Anthropic model
✓ Local Qwen

Blocked:
✕ experimental model
✕ provider without approved retention policy

Поддержать политики по:

organization;

role;

group/department;

use case;

data classification;

budget;

provider;

model capability.

7. Этап 5 — AI FinOps / Budgets

Период: декабрь 2026

Usage необходимо превратить из отчётности в control plane.

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

hard/soft budget.

Реакции

80% → warning
100% → downgrade / restrict premium models
110% → block premium traffic

Dashboard

cost by organization;

cost by user;

cost by department;

cost by model;

cost by use case;

cost by assistant;

cost by agent;

cached-token savings;

RAG cost;

forecast;

budget utilization.

8. Этап 6 — Data Classification + DLP

Период: конец 2026 → начало 2027

Перед отправкой prompt внешнему provider:

User input
    ↓
Data Classification
    ↓
DLP / PII / secrets
    ↓
Policy Engine
    ↓
Provider

Классы данных

PUBLIC
INTERNAL
CONFIDENTIAL
RESTRICTED

Detection

email;

phone;

passport / ID;

credit card;

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

9. Этап 7 — Policy Engine

Период: начало 2027

Не размазывать enterprise policy по if внутри сервисов.

Создать централизованный Policy Engine.

Пример:

WHEN
    organization = ACME
    AND department = FINANCE
    AND classification >= CONFIDENTIAL

THEN
    deny external providers
    allow local models
    audit = HIGH

Другой пример:

WHEN
    model = PREMIUM
    AND monthlyBudget > 90%

THEN
    route = STANDARD_MODEL

PolicyDecision

ALLOW
DENY
WARN
REDACT
REQUIRE_APPROVAL
ROUTE

10. Этап 8 — Groups / Departments

Период: начало 2027

Текущая модель:

Organization
→ Users

Целевая:

Organization
→ Groups / Departments
→ Users

Примеры:

Finance;

Legal;

HR;

Engineering;

Sales;

Support.

На группы назначаются:

Knowledge access;

Assistant access;

Model access;

budget;

DLP policy;

tool access;

data classification rules.

11. Этап 9 — Enterprise Knowledge Connectors

Период: Q1 2027

Ручной upload оставить, но добавить источники:

Knowledge Source
├── File upload
├── Confluence
├── SharePoint
├── OneDrive
├── Google Drive
├── Notion
├── Jira
├── Git
├── Web site
└── Internal API

Connector model

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

health;

retry;

sync status;

last sync;

next sync;

partial failure;

credential rotation.

12. Этап 10 — SSO / SCIM

Период: Q1 2027

Для enterprise deployment добавить:

Authentication

OIDC;

Microsoft Entra ID;

Okta;

Google Workspace;

SAML при необходимости.

Provisioning

SCIM;

automatic user create;

automatic disable;

group sync;

department sync.

Сценарий:

Employee leaves company
→ IdP disables user
→ SafeAI revokes active sessions
→ AI access disappears

13. Этап 11 — AI Governance

Период: Q1–Q2 2027

Добавить AI Use Case Registry.

Пример:

Use case:
HR CV Assistant

Owner:
HR

Purpose:
Candidate screening support

Models:
...

Data:
Personal data

Human oversight:
Required

Risk:
...

Approved:
YES

Risk profile

personal data;

confidential data;

autonomous actions;

external provider;

employment decisions;

financial impact;

customer-facing usage;

legal impact;

human oversight.

SafeAI Desk должен обеспечивать технический governance workflow, а не выступать юридическим консультантом.

14. Этап 12 — AI Literacy / Acceptable Use

Период: Q1–Q2 2027

При первом входе пользователь принимает корпоративную AI policy.

Пример:

Do not:
- send passwords;
- upload prohibited confidential documents;
- treat generated output as final legal advice;
- bypass company security policies.

Хранить:

policyVersion
userId
organizationId
acceptedAt

При обновлении policy:

requiresReacceptance = true

15. Этап 13 — Prompt / Configuration Registry

Период: Q2 2027

System prompts и AI configuration не должны существовать только в исходном коде.

Создать:

PromptTemplate
PromptVersion
ConfigurationVersion

Пример:

Corporate Assistant
v17

Created by: ADMIN
Approved by: SECURITY
Deployed: 2027-04-04

ChatTurn сохраняет:

promptVersionId
configurationVersionId

Answer Passport показывает точную конфигурацию.

16. Этап 14 — AI Observability

Период: Q2 2027

Единый trace:

HTTP request
  ↓
ChatTurn
  ↓
Retrieval
  ↓
Embedding
  ↓
Provider call
  ↓
Tool calls

Пример:

traceId abc

ChatTurn                8.1s
├─ Retrieval            120ms
│  ├─ embedding          73ms
│  └─ pgvector           29ms
│
└─ Provider              7.7s

Использовать OpenTelemetry и GenAI semantic conventions.

17. Этап 15 — Enterprise Assistants

Период: Q2 2027

Добавить сущность Assistant.

ADMIN создаёт:

Name:
Legal Assistant

Prompt:
...

Knowledge:
Legal KB

Models:
approved legal models

Tools:
none / selected

Available to:
Legal Department

Другие примеры:

HR Assistant;

IT Support Assistant;

DevOps Assistant;

Sales Assistant;

Finance Assistant.

Пользователь выбирает assistant, а не вручную настраивает model/KB/prompt.

18. Этап 16 — MCP Gateway

Период: Q2–Q3 2027

SafeAI Desk не должен отдавать модели прямой доступ к произвольным MCP servers.

Архитектура:

AI Agent
   │
   ↓
SafeAI Tool Gateway
   │
   ├── authentication
   ├── authorization
   ├── tenant isolation
   ├── policy
   ├── approval
   ├── audit
   ├── secrets
   ├── DLP
   └── rate limits
   │
   ↓
MCP Servers

19. Этап 17 — Tool Registry

Период: Q2–Q3 2027

Подключаемые tools:

Jira;

GitHub;

CRM;

SQL read-only;

Confluence;

ServiceNow;

Slack;

internal APIs.

Для каждого tool:

name
capabilities
riskLevel
organization
groups
read/write
credential
requiredRole
approvalPolicy

Пример:

Jira.search
risk = LOW
approval = false

Jira.createTicket
risk = MEDIUM
approval = optional

ProductionDB.execute
risk = CRITICAL
approval = required

20. Этап 18 — Human-in-the-loop approvals

Период: Q3 2027

Перед опасным действием:

AI wants to:

Create Jira issue
Project: PROD
Title: Database latency incident

[Approve]
[Deny]

Для более опасных действий:

AI wants to send email to 427 customers.

Requires:
✓ user approval
✓ manager approval

Audit chain:

agent proposed
→ user approved
→ tool invoked
→ result stored

21. Этап 19 — Durable Agent Runtime

Период: Q3 2027

Перенести принципы ChatTurn на agents.

Создать:

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

Каждый external side effect должен поддерживать:

idempotency;

lease;

fencing;

timeout;

retry classification;

audit;

provenance.

Не использовать простой бесконечный:

LLM → tool → LLM → tool

без durable execution semantics.

22. Этап 20 — Agent Security

Период: одновременно с Agent Runtime

Agent не получает «все инструменты».

Использовать capability-based access:

Agent
→ exact tools
→ exact scopes
→ exact organization
→ exact credentials

Главный invariant:

prompt content != authorization

Документ из RAG или внешний сайт не может получить permission просто потому, что содержит инструкцию вызвать tool.

23. Этап 21 — Sandboxed Code Interpreter

Период: Q3–Q4 2027

Для анализа файлов:

CSV
Excel
PDF
data analysis
charts
Python

использовать ephemeral sandbox:

CPU limit
RAM limit
disk limit
timeout
network disabled by default
ephemeral filesystem

Не исполнять пользовательский Python внутри backend JVM.

24. Этап 22 — Service Accounts

Период: Q3–Q4 2027

Добавить machine identity:

ServiceAccount

Use cases:

CI/CD reviewer;

CRM summarizer;

ticket classifier;

nightly document analyzer;

internal automation.

ServiceAccount имеет:

organization;

scopes;

allowed models;

budget;

rate limit;

credentials;

expiry;

audit actor;

allowed tools.

25. Этап 23 — Enterprise API

Период: Q4 2027

SafeAI Desk становится инфраструктурой не только для собственного UI.

Пример API:

POST /api/v1/ai/responses
POST /api/v1/retrieval/search
POST /api/v1/assistants/{id}/runs
POST /api/v1/agents/{id}/runs

К нему могут подключаться:

CRM;

internal portal;

support system;

mobile app;

automation;

CI/CD.

При этом SafeAI продолжает применять:

routing;

budget;

DLP;

policy;

audit;

usage;

governance.

26. Этап 24 — A2A / Agent interoperability

Период: конец 2027

После MCP/tool layer можно добавлять Agent-to-Agent взаимодействие.

SafeAI Agent
    ↓
A2A
    ↓
External enterprise agent

Но только через gateway:

identity
trust
policy
tenant isolation
audit
budget

27. Этап 25 — Data Retention / Legal Hold / Privacy

Период: 2027

Пример policy:

Chat history        30 days
Prompt content       7 days
Audit              365 days
Usage              3 years
Answer Passports   365 days

Добавить:

retention policies;

deletion jobs;

legal hold;

export;

pseudonymization;

personal-data deletion workflow.

28. Этап 26 — AI Security Center

Период: 2027

Отдельный dashboard:

Prompt injection attempts     34
DLP blocks                    12
Secret detections              7
Blocked tool calls             3
Policy violations             19
Suspicious agents              1

Создать отдельную сущность:

SecurityEvent

а не смешивать всё только с AuditEvent.

29. Этап 27 — Continuous Red Team / Security Evals

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

malicious MCP server behavior;

malicious retrieved documents.

После изменения:

model
prompt
knowledge
tool
agent
policy

автоматически запускать security evaluation suite.

30. Версии продукта

SafeAI Desk 0.8 — сентябрь–октябрь 2026

Knowledge frontend
Ingestion UI
Citations
Source Drawer
Answer Passport UI
Knowledge-only mode
Knowledge health
Reindex
Integration tests

SafeAI Desk 0.9 — ноябрь–декабрь 2026

Evaluation Framework
Model Registry
Organization Model Policies
Correct pricing
AI budgets
Provider routing
DLP v1
Groups / Departments
Knowledge connectors v1

SafeAI Desk 1.0 — начало 2027

SSO / OIDC
SCIM
Model Control Plane
Knowledge connectors
DLP
Policy Engine
AI Use Case Registry
Prompt Registry
Evaluations
OpenTelemetry AI traces
Production deployment
Backup / restore
HA

SafeAI Desk 1.5 — середина 2027

Enterprise Assistants
MCP Gateway
Tool Registry
Human approvals
Service Accounts
Enterprise API
Sandboxed Code Interpreter

SafeAI Desk 2.0 — конец 2027

Durable Agents
Agent workflows
A2A
Multi-agent collaboration
Agent Security Center
Continuous AI red teaming
Advanced policy engine
Governance automation

31. Практический порядок разработки с текущего момента

Не начинать сразу с agents.

Рекомендуемый путь:

1. Knowledge frontend
        ↓
2. Citations + Source Drawer
        ↓
3. Answer Passport UI
        ↓
4. Retrieval/RAG evaluations
        ↓
5. Model Registry
        ↓
6. Organization Model Policies
        ↓
7. Correct pricing + budgets
        ↓
8. Data Classification / DLP
        ↓
9. Groups / Departments
        ↓
10. Enterprise Knowledge Connectors
        ↓
11. SSO / SCIM
        ↓
12. Policy Engine / Governance
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

32. Главные продуктовые приоритеты

Если выбирать только три крупных эпика после завершения текущего Knowledge вертикального среза:

1. Knowledge UX + Evaluations

Даёт контролируемые ответы по корпоративным данным и позволяет измерять качество.

2. Model Control Plane + AI FinOps + DLP

Превращает SafeAI Desk из чата в настоящий корпоративный AI Gateway.

3. Enterprise Assistants + MCP Gateway

Даёт управляемую автоматизацию и подготавливает платформу к agents.

33. Конечная идея SafeAI Desk

SafeAI Desk должен стать системой, через которую организация контролирует:

WHO
кто использует AI

WHAT
какие данные и знания доступны

WHICH MODEL
какие модели можно использовать

HOW MUCH
сколько можно потратить

WHY
для какого use case используется AI

WHAT HAPPENED
что реально произошло

WHAT EVIDENCE
на каких данных основан ответ

WHAT ACTION
какое действие хочет выполнить AI

WHO APPROVED
кто разрешил это действие

Конечная архитектура SafeAI Desk — это не просто чат и не просто RAG.

Это:

AI Gateway + Knowledge Platform + Model Control Plane + Governance + FinOps + Secure Agent Runtime.