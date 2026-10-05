# SafeAI Desk — Master Production Roadmap 2026–2027

> **Актуальность:** 04 октября 2026  
> **Горизонт:** октябрь 2026 → декабрь 2027  
> **Текущий backend baseline:** Flyway `V1–V61`, modular monolith, `common / auth / user / organization / ratelimit / audit / usage / chat / model / ai / knowledge`  
> **Позиционирование:** production-oriented корпоративная AI-платформа / AI Gateway, которая объединяет безопасный доступ к моделям, корпоративным знаниям, governance, FinOps, DLP, инструменты и в дальнейшем — durable agent runtime.

---

## Как читать этот roadmap

Этот документ описывает **целевое развитие**, а не уже реализованный функционал.

Статусы:

| Статус | Значение |
|---|---|
| `DONE` | уже существует в текущем baseline |
| `HARDEN` | реализовано, но требует production hardening |
| `NEXT` | ближайший обязательный этап |
| `PLANNED` | запланированное развитие |
| `OPTIONAL` | полезно, но не блокирует основную продуктовую линию |
| `FUTURE` | архитектурное направление после базовых safeguards |

Приоритеты:

| Приоритет | Значение |
|---|---|
| `P0` | production blocker / нарушение ключевого invariant |
| `P1` | необходимо закрыть до серьёзного production |
| `P2` | желательно до 1.0 |
| `P3` | улучшение / зрелость / оптимизация |

Будущие номера Flyway-миграций **не резервируются заранее**.  
Перед merge новой migration используется реально свободный version.

> **Source-of-truth rule для этой редакции:** README snapshot местами всё ещё содержит старые подписи `V1–V58`, но предоставленные и уже добавленные migrations `V59`, `V60`, `V61` считаются более новым baseline. Roadmap поэтому ведётся от `V1–V61`. Applied migrations не переименовываются и не переписываются.

---

# 1. Конечная идея SafeAI Desk

SafeAI Desk должен стать не «чатом с несколькими моделями», а корпоративным AI control layer.

```text
                           SafeAI Desk
                                │
          ┌─────────────────────┼─────────────────────┐
          │                     │                     │
     AI Workspace          AI Control Plane       Governance
          │                     │                     │
 Chat / Search / RAG       Models / Providers     Policies / Risk
 Assistants / Agents       Routing / Budgets      DLP / Approvals
 Files / Artifacts         Pricing / Health       Audit / Compliance
          │                     │                     │
          └─────────────────────┼─────────────────────┘
                                │
                         Enterprise Data
                                │
       Drive / SharePoint / Confluence / Jira / Git /
       Slack / DB / CRM / internal APIs / MCP / A2A
```

Платформа должна уметь отвечать на вопросы:

```text
WHO
Кто использовал AI?

WHAT DATA
Какие данные, документы и Knowledge Bases были доступны?

WHICH MODEL
Какая модель, provider и configuration version были выбраны?

WHY
Почему именно эта route/policy decision была разрешена?

HOW MUCH
Сколько было зарезервировано, фактически использовано и потрачено?

WHAT HAPPENED
Какой physical provider attempt реально был начат?

WHAT EVIDENCE
На каких документах и версиях основан ответ?

WHAT ACTION
Какое действие модель хотела выполнить?

WHO APPROVED
Кто одобрил side effect?

WHAT SECURITY STATE
Какие DLP/policy/risk решения применились?

CAN WE REPRODUCE THE CONTROL PATH
Можно ли доказать весь governance/execution path даже при недетерминированном LLM?
```

---

# 2. Архитектурные invariants, которые нельзя ломать

Вне зависимости от количества новых features должны сохраняться:

```text
tenant isolation
security fail-closed
exactly-one-role
security epochs
idempotency
semantic replay identity
lease + fencing
no blind retry after ambiguous external I/O
provider I/O outside long DB transaction
immutable governance evidence
immutable physical execution evidence
unknown != zero
prompt content != authorization
retrieved content != authorization
audit/provenance
database-enforced integrity
versioned configuration
explicit failure semantics
```

Для Model Control Plane:

```text
latest != effective
catalog != runtime
policy != runtime
governance decision != physical execution
provider health != immutable catalog fact
pricing estimate != actual settlement
```

Для Knowledge:

```text
uploaded version != searchable published version
retrieval evidence must remain attributable
ACL filtering happens before ranking
document text is untrusted input
citation != factual truth
```

Для Agents/Tools:

```text
LLM suggestion != permission
approval applies to exact payload
tool call != ordinary text generation
external side effect must be idempotent/fenced
agent memory != authorization source
```

---

# 3. Текущий baseline на 04.10.2026

## 3.1. Identity / Security — `DONE / HARDEN`

Уже есть:

- organization-based multi-tenancy;
- `SUPER_ADMIN / ADMIN / USER`;
- exactly-one-role;
- HttpOnly cookie JWT;
- CSRF;
- RSA JWT key ring;
- strict issuer/audience/type/identity validation;
- `user.tokenVersion`;
- `organization.authVersion`;
- refresh-token rotation;
- refresh reuse detection;
- refresh family termination;
- password UTF-8/BCrypt hardening;
- trusted proxy / client IP handling;
- canonical server request ID;
- fail-closed security-state lookup;
- production invariant validators.

## 3.2. Chat durability — `DONE / HARDEN`

Уже есть:

- durable `ChatTurn`;
- idempotency;
- semantic replay identity;
- lease;
- fencing;
- `processingToken`;
- `providerOperationId`;
- quota reservation;
- recovery;
- `AMBIGUOUS`;
- no blind retry;
- provider I/O вне долгой DB transaction;
- deterministic title lifecycle `DEFAULT / AUTO / USER`.

## 3.3. AI physical execution — `DONE / HARDEN`

Уже есть:

- OpenAI adapter;
- Anthropic adapter;
- Mock adapter;
- durable `ModelExecutionPlan`;
- durable `ModelExecutionAttempt`;
- outcome certainty;
- retry/fallback safety;
- provider target identity;
- usage evidence;
- pricing evidence;
- response size bounds;
- HTTPS/redirect hardening;
- governed resolved-model validation.

## 3.4. Model Control Plane — `DONE / HARDEN`

Уже есть:

- versioned Model Catalog;
- latest/effective semantics;
- immutable organization model policies;
- allow/deny/default model;
- request/output limits;
- pricing requirements;
- retention/training requirements;
- budget preflight;
- immutable route decisions;
- integrity hash;
- input-accounting provenance;
- capability fail-closed;
- runtime probe.

## 3.5. Knowledge / RAG — `DONE / HARDEN`

Уже есть:

- Knowledge Bases;
- memberships;
- document versions;
- S3-compatible storage;
- durable upload intent journal;
- ingestion;
- extraction;
- optional OCR;
- embeddings;
- pgvector;
- FTS;
- Russian lexical profile;
- RRF hybrid retrieval;
- deterministic chunking;
- citations;
- Answer Passport;
- retrieval evaluation;
- generation lifecycle/GC;
- storage reconciliation.

## 3.6. Audit / Usage / Rate Limit — `DONE / HARDEN`

Уже есть:

- transactional audit outbox;
- immutable snapshots;
- dead-letter;
- retention;
- cursor pagination;
- Redis rate limits;
- fail-closed infrastructure semantics;
- usage/pricing quality;
- rollups/reconciliation;
- UTC reporting.

## 3.7. Governance / accounting hardening V59–V61 — `DONE / HARDEN`

После V58 baseline усилен тремя additive migrations. Они **не заменяют** старые migrations и не означают, что весь FinOps/governance roadmap завершён.

### `V59__governance_integrity_hardening.sql`

Реализовано:

- DB-level validation каждого `allow_model_keys` / `deny_model_keys` элемента по canonical model-key syntax;
- numeric upper-bound guard для catalog cost envelope, чтобы экстремальные значения не приводили к небезопасной арифметике;
- запрет control characters в `pricing_version`;
- усиление `enforce_model_execution_attempt_transition_v53()`:
  - physical target обязан соответствовать governed route decision;
  - attempt numbers остаются contiguous;
  - retry разрешён только после доказанно retry-safe предыдущего attempt;
  - same-target retry не может незаметно изменить provider/config/deployment/model;
  - для route с `HARD` monthly budget либо policy `max_request_cost_usd` текущий DB invariant разрешает **ровно один physical attempt**, пока aggregate multi-attempt reservation не моделируется корректно;
- read-only reconciliation view для unresolved/ambiguous/provider-success-but-chat-not-success/cost-unknown physical executions;
- новый Knowledge index generation обязан вставляться только как `ACTIVE` без retirement/collection timestamps;
- retirement chronology защищена DB constraint;
- chat title provenance больше не может двигаться назад (`USER → AUTO/DEFAULT`, `AUTO → DEFAULT`);
- legacy node, меняющий title без `title_source`, автоматически переводит provenance в `USER`, чтобы auto-title не перезаписал ручную правку.

### `V60__monthly_cost_commitment_ledger.sql`

Добавлен versioned accounting read model:

```text
model_cost_commitment_ledger_v60
```

Он разделяет:

```text
actual_cost_usd
reserved_cost_usd
unknown_cost_count
carry_forward
```

и явно различает источники:

```text
PHYSICAL_ATTEMPT
PENDING_RESERVATION
MISSING_PHYSICAL_EVIDENCE
LEGACY_ROUTE_TIMESTAMP
```

Ключевая семантика:

- `actual` означает provider-evidenced cost, **не invoice settlement**;
- STARTED attempt может удерживать pricing-complete upper-bound reservation;
- pending reservation сохраняется до physical I/O;
- missing physical evidence не превращается в `$0`;
- legacy history помечается честно и не получает выдуманный provider timestamp;
- активные commitments могут переноситься через границу UTC-месяца;
- добавлен индекс для monthly cost read path.

### `V61__model_policy_array_uniqueness.sql`

Добавлен DB invariant, запрещающий duplicate normalized model keys внутри каждого policy array:

```text
allow_model_keys
 deny_model_keys
```

Historical append-only rows не переписываются автоматически: `VALIDATE CONSTRAINT` должен выявить старые некорректные данные для явного audit/reconciliation.

В Java model repository также появился `JdbcTextArrayReader`, который читает SQL `text[]` через JDBC API без зависимости от конкретного Java array class PostgreSQL driver и гарантированно освобождает `java.sql.Array`.

### Что V59–V61 **не закрывают полностью**

- P0 budget-state representability между domain и историческими V45 constraints;
- billing-grade provider invoice settlement;
- отдельный physical-vs-application outcome model;
- governed provider entry-point enforcement на каждом adapter call-site;
- safe multi-target fallback для bounded-cost routes;
- Knowledge `latest uploaded` vs `published searchable` pointer;
- audit `MANDATORY` contract;
- OCR/embedding durable physical-attempt ledger;
- dynamic multi-provider registry/router.

---

# 4. Сначала production blockers: этап 0

> **Период:** октябрь–ноябрь 2026  
> **Статус:** `NEXT`  
> **Цель:** не расширять платформу, пока известные P0/P1 не закрыты.

---

## 4.1. P0 — Model budget-state representability — `NEXT`

> **V60 status:** monthly commitment read model уже появился и корректно различает actual/reserved/unknown liability, но он не меняет несовместимую historical route-decision representation. Поэтому P0 остаётся открытым.

Текущая проблема:

```text
historical cost partially unknown
+
known lower bound already exceeds monthly budget
```

Domain способен доказать:

```text
budgetExceeded = true
monthlyCostKnown = false
```

но существующая DB model требует несовместимые значения для `monthly_projected_usd`.

### Что сделать

Ввести явную budget state model, например:

```text
KNOWN_WITHIN
KNOWN_EXCEEDED
UNKNOWN_WITHIN_LOWER_BOUND
UNKNOWN_EXCEEDED_LOWER_BOUND
UNVERIFIABLE
```

или эквивалентную нормализованную модель.

### Требования

- Java/domain и PostgreSQL constraints должны представлять одинаковые состояния;
- historical unknown commitments не теряются;
- `HARD` budget fail closed;
- lower-bound exceed считается exceeded;
- API различает:
  - known actual;
  - reserved;
  - lower bound;
  - unknown amount.

### Exit criteria

- integration tests на все budget states;
- migration с backward-compatible backfill;
- ни одно валидное domain state не нарушает DB constraint.

---

## 4.2. P1 — Physical outcome отдельно от application outcome — `NEXT`

> **V59 status:** reconciliation queue уже лучше обнаруживает расхождение physical/provider и Chat outcome, но persisted attempt schema всё ещё должна отдельно выражать physical truth и application acceptance.

Нужно разделить:

```text
transport/provider execution truth
```

и:

```text
application semantic acceptance
```

Пример:

```text
HTTP 200 получен
provider request выполнен
usage известен
но JSON/schema/protocol validation провалилась
```

Это не должно автоматически превращаться в потерю billable evidence.

### Целевая модель

```text
PhysicalOutcome
  STARTED
  KNOWN_REJECTED
  KNOWN_EXECUTED
  AMBIGUOUS

ApplicationOutcome
  ACCEPTED
  RESPONSE_INVALID
  GOVERNANCE_REJECTED
  PARSING_FAILED
  UNSUPPORTED_CONTENT
```

### Persist evidence

Если доступно:

- HTTP status;
- provider request ID;
- provider message/response ID;
- resolved model;
- usage;
- pricing;
- response validation status;
- bounded protocol failure code.

---

## 4.3. P1 — запрет provider bypass — `NEXT`

> **V59 status:** DB transition guard усиливает корректность attempt insert/retry, но не может запретить Java call-site физически вызвать adapter вне `AiExecutionService`. Этот invariant остаётся application-boundary задачей.

Production provider нельзя вызывать напрямую в обход durable execution boundary.

Целевой invariant:

```text
OpenAI / Anthropic physical I/O
    only inside active governed attempt scope
```

### Реализация

Предпочтительно:

```text
AiExecutionService
   ↓
internal ProviderAdapter
```

а не публичный application-level `AiProvider.sendMessage()` для произвольного call-site.

Тестовый ungoverned path допустим только явно:

```text
test-only / package-private
```

---

## 4.4. P1 — ExecutionPlan recovery

Crash window:

```text
ExecutionPlan committed
↓
process crash
↓
Attempt #1 не создан
```

БД доказывает, что physical call не стартовал.

### Целевой recovery state

```text
CREATED_NO_ATTEMPT
IN_FLIGHT
SUCCEEDED
FAILED_RETRYABLE
FAILED_FINAL
AMBIGUOUS
RECONCILIATION_REQUIRED
```

### Правило

```text
existing matching plan + zero attempts
→ safe resume
```

а не generic conflict.

---

## 4.5. P1 — Final request binding

Целевой pipeline:

```text
history
+
RAG
+
context-window fitting
+
truncation
↓
FINAL AiChatRequest
↓
execution hash/binding
↓
execution guard
↓
provider adapter serialization
```

После binding provider adapter не должен семантически менять request.

---

## 4.6. P1 — Provider token/context estimation

Разделить два понятия.

### Governance input units

```text
UTF8_STRUCTURAL_UNITS_V2
```

Это deterministic internal accounting.

### Provider tokens

Это реальное ограничение context window конкретной модели.

Нужен:

- model-specific tokenizer, где доступен;
- conservative upper-bound strategy;
- versioned token estimator;
- `contextWindowTokens`;
- `maxInputTokens`;
- `maxOutputTokens`;
- effective input formula.

---

## 4.7. P1 — Pricing scheme v2 — `NEXT`

> **V59/V60 status:** `pricing_version` и numeric envelope стали строже, а commitment ledger честно сохраняет unknown liability. Но сложные provider-specific pricing tiers/service modes/context bands ещё не моделируются полноценно.

Текущий flat pricing не должен молча представлять сложные pricing models как точный.

Создать versioned pricing scheme:

```text
PricingSchemeVersion
PricingDimension
PricingTier
ServiceMode
Region
ContextBand
CacheRead
CacheWrite
Input
Output
Reasoning
Image
Audio
Tool
Batch
```

Если pricing schema не умеет выразить provider product:

```text
UNPRICED
```

а не approximation, выдаваемый за actual.

---

## 4.8. P1 — Knowledge searchable publication — `NEXT`

> **V59 status:** generation insert/retirement lifecycle стал строже, но это **не** решает проблему раннего переключения `current_version_id`. Нужен отдельный searchable/published pointer либо atomic publication swap.

Разделить:

```text
latestUploadedVersionId
publishedSearchableVersionId
```

или сделать atomic publication pointer swap.

Цель:

- v1 остаётся searchable, пока v2 не READY;
- failed v2 не скрывает v1;
- publication происходит только после successful ACTIVE generation.

---

## 4.9. P1 — Knowledge generation retention

Нужен explicit policy:

- evidence retention;
- legal hold;
- privacy deletion;
- Answer Passport retention;
- retrieval hit retention;
- bounded evidence excerpt;
- generation GC after expiry.

Нельзя бесконечно держать все chunks только потому, что когда-то был retrieval hit.

---

## 4.10. P1 — Mixed scanned PDF

Если отдельные страницы требуют OCR:

```text
OCR unavailable
→ document must not silently become READY as complete
```

Ввести состояние:

```text
PARTIAL
OCR_REQUIRED
```

или fail ingestion в strict mode.

`KNOWLEDGE_ONLY` не должен использовать partial source как полностью индексированный.

---

## 4.11. P1 — Parser isolation

PDF/OOXML — untrusted input.

Текущий timeout в JVM полезен, но не является sandbox.

### Цель

Отдельный ingestion worker/container:

```text
backend
  ↓ durable job
parser worker
  ↓ bounded result
backend/index pipeline
```

Ограничения worker:

- CPU;
- RAM;
- disk;
- timeout;
- no network by default;
- read-only base image;
- ephemeral filesystem.

---

## 4.12. P1 — OCR/Embedding physical ledger

External OCR/embedding calls могут быть billable.

Нужен общий durable attempt pattern:

```text
KnowledgeProviderOperation
KnowledgeProviderAttempt
```

для:

- embeddings;
- OCR;
- reranker;
- external document intelligence.

### Должно храниться

- operation ID;
- exact provider/config;
- batch identity;
- request hash;
- STARTED;
- outcome certainty;
- usage;
- cost;
- retry safety.

---

## 4.13. P1 — Audit REQUIRED semantics

`Propagation.REQUIRED` не доказывает наличие outer business transaction.

Production target:

```text
recordRequiredInCurrentTransaction
→ Propagation.MANDATORY
```

Отдельный API:

```text
recordStandalone
tryRecordStandalone
```

чтобы transactional semantics были видны из имени метода.

---

## 4.14. P1 — Physical spend ledger — `HARDEN`

> **V60 status:** первый корректный physical cost commitment read model уже реализован. Поэтому задача больше не «создать ledger с нуля», а довести его до FinOps/billing-grade слоя.

Уже есть `model_cost_commitment_ledger_v60`, который различает provider-evidenced actual cost, upper-bound reservations, unknown liability и legacy/missing-evidence cases.

Остаётся добавить:

- durable settlement entries, а не только view;
- invoice/billing export reconciliation;
- adjustment/credit/refund entries без переписывания historical attempt evidence;
- OCR/embedding/reranker/tool/agent spend;
- stable FinOps APIs/rollups;
- budget enforcement, использующий согласованную commitment semantics;
- operator reconciliation workflow.

Usage UI отвечает на:

```text
что получил пользователь
```

FinOps должен также отвечать:

```text
что реально стоило денег у provider
```

Источником actual provider spend должен быть physical attempt ledger, включая:

- application rejected;
- ambiguous;
- partial;
- retries;
- embeddings;
- OCR;
- tools;
- agents.

---

## 4.15. P1 — Production mode

Не завязывать security solely на точные profile names.

Добавить explicit:

```text
safeai.environment.production=true
```

или canonical environment mode.

Все production validators используют одну abstraction.

---

## 4.16. Закрытый hardening — Model policy canonicality V59/V61 — `DONE`

Теперь DB проверяет два разных свойства model-policy arrays:

```text
V59 → каждый key syntactically canonical
V61 → каждый key встречается внутри массива не более одного раза
```

Это важно для deterministic policy evaluation, stable integrity hashing и понятного audit diff.

Остаётся не смешивать это с отдельным вопросом **allow/deny conflict semantics**: precedence между двумя списками определяется domain policy и должна тестироваться отдельно.

---

## 4.17. Закрытый/частичный hardening — Reconciliation visibility V59 — `DONE / HARDEN`

`model_execution_reconciliation_queue_v54` теперь явно выделяет как минимум:

```text
PHYSICAL_STARTED_UNRESOLVED
PHYSICAL_OUTCOME_UNKNOWN
PROVIDER_SUCCEEDED_CHAT_NOT_SUCCEEDED
PROVIDER_SUCCEEDED_COST_UNKNOWN
FAILED_CALL_COST_NOT_PROVEN
```

View остаётся **read-only** и не является автоматическим retry engine.

Следующий этап — Reconciliation Center с operator actions, evidence attachments и разрешённым retry только после доказанной safety.

---

# 5. Release 0.8 — Hardened Core

> **Период:** ноябрь–декабрь 2026  
> **Цель:** production-capable single-runtime baseline без новых рискованных agent features.

## Обязательные результаты

- закрыты P0/P1 этапа 0;
- fresh `V1 → V61+` migration;
- representative populated upgrade;
- full backend test suite;
- migration integrity suite;
- backup + restore drill;
- S3 recovery drill;
- Redis outage behavior;
- provider timeout/reconciliation tests;
- load baseline;
- security log review;
- operational runbooks.

## Новые platform capabilities

### Resumable streaming

Добавить потоковый ответ без потери durable semantics.

```text
provider stream
↓
server event stream
↓
client
```

Но persisted `ChatTurn` остаётся source of truth.

Нужны:

- `SSE` — Server-Sent Events, односторонний поток событий от backend к browser;
- sequence number;
- reconnect cursor;
- terminal persisted result;
- bounded buffering;
- disconnect handling;
- no duplicate finalization.

### Cancel request

Пользователь может отменить logical operation.

Важно различать:

```text
client stopped waiting
```

и:

```text
provider definitely cancelled
```

Если provider cancellation certainty unknown:

```text
do not claim KNOWN_NOT_EXECUTED
```

### Chat search

- full-text search;
- by date;
- by model;
- by Knowledge Base;
- archived chats;
- title/source filters.

### Projects / Workspaces

Над `Chat` добавить managed workspace:

```text
Project
  ├─ members
  ├─ default Knowledge Bases
  ├─ default Assistant later
  ├─ model policy
  ├─ tags
  └─ retention policy
```

Project не должен ломать organization tenant boundary.

---

# 6. Release 0.9 — Dynamic Multi-Provider Data Plane

> **Период:** декабрь 2026 → Q1 2027  
> **Приоритет:** `P1`

Сейчас Control Plane развит сильнее physical runtime.

Цель — route decision должен реально выбирать execution target внутри одного deployment.

---

## 6.1. Provider Configuration Registry

Создать versioned configuration identity:

```text
Provider
ProviderConfiguration
ProviderDeployment
CredentialReference
```

Пример:

```text
OpenAI
 ├─ prod-eu
 └─ prod-us

Anthropic
 └─ prod

Azure OpenAI
 ├─ germany
 └─ sweden

Local
 └─ gpu-cluster-a
```

Secret не сохраняется snapshot-ом в route decision.

Хранится:

```text
credentialReferenceId
credentialVersion
```

---

## 6.2. Adapter Registry

```text
ProviderAdapterRegistry
    ├─ OpenAIAdapter
    ├─ AnthropicAdapter
    ├─ AzureOpenAIAdapter
    ├─ AWSBedrockAdapter
    ├─ GoogleVertexAdapter
    └─ OpenAICompatibleAdapter
```

Не делать giant `if provider == ...` внутри ChatService.

---

## 6.3. Dynamic routing

Input:

- organization policy;
- requested logical model;
- required capabilities;
- region;
- classification;
- budget;
- health;
- latency tier.

Output:

```text
exact ProviderExecutionTarget
```

Target immutable для конкретного attempt.

---

## 6.4. Circuit Breaker

**Circuit breaker** — механизм временного отключения provider/deployment после серии ошибок.

States:

```text
CLOSED
OPEN
HALF_OPEN
```

Но circuit state — runtime signal, а не catalog fact.

---

## 6.5. Health-aware routing

Health signal:

- success rate;
- timeout rate;
- latency;
- rate-limit pressure;
- provider outage;
- deployment availability.

Нельзя использовать transient health как immutable provenance field.

Decision сохраняет:

```text
какой signal snapshot повлиял
```

но не переписывает catalog.

---

## 6.6. Controlled fallback

Fallback разрешён только если:

- policy его разрешила заранее;
- alternate target входит в approved route space;
- capability requirements совпадают;
- data-residency requirements совпадают;
- budget повторно проверен;
- previous outcome retry/fallback-safe;
- создаётся новый physical attempt.

После `AMBIGUOUS` fallback запрещён без reconciliation.

Дополнительный текущий invariant V59:

```text
HARD monthly budget OR max_request_cost_usd configured
→ exactly one physical attempt
```

До снятия этого ограничения multi-provider fallback для bounded-cost routes требует **aggregate multi-attempt reservation model**: budget должен резервировать не один request envelope, а максимально допустимую стоимость всей заранее разрешённой цепочки attempts/fallbacks.

---

## 6.7. Provider-specific idempotency

Использовать provider idempotency/client-request mechanism только если он документирован.

Не считать наличие собственного UUID гарантией provider-side exactly-once.

---

## 6.8. Canary / Champion-Challenger

**Canary** — небольшой процент production traffic идёт новой модели.

**Champion/Challenger** — текущая основная модель сравнивается с кандидатом.

Modes:

```text
LIVE
SHADOW
EVAL_ONLY
```

`SHADOW` не должен отправлять confidential data без отдельной policy.

---

# 7. Model Platform 2027

> **Период:** Q1 2027

---

## 7.1. Расширенный Model Registry

Не создавать второй registry параллельно существующему Model Catalog.

Расширить Catalog:

- reasoning capability;
- reasoning effort modes;
- structured outputs;
- tools;
- image input;
- image generation;
- audio input;
- audio output;
- streaming;
- JSON Schema support;
- max image size;
- file support;
- context window;
- output limit;
- region;
- residency;
- provider lifecycle;
- deprecation date;
- replacement model;
- safety profile;
- latency class;
- pricing scheme;
- cache capability;
- batch capability.

---

## 7.2. Model lifecycle

```text
DRAFT
APPROVED
ACTIVE
DEPRECATED
BLOCKED
RETIRED
```

Scheduled transitions.

Нельзя удалять historical version, которая уже использовалась route decision.

---

## 7.3. Model approval workflow

Перед production activation:

```text
catalog version
↓
technical evaluation
↓
security evaluation
↓
cost evaluation
↓
human approval
↓
effectiveFrom
```

---

## 7.4. Local / private models

Поддержать OpenAI-compatible локальные inference endpoints.

Use cases:

- confidential data;
- offline environment;
- predictable cost;
- data residency;
- customer-managed GPU.

Платформа должна одинаково применять:

- tenant;
- DLP;
- policy;
- audit;
- budget;
- usage;
- route evidence.

---

# 8. Structured Output / Vision / Multimodal

> **Период:** Q1 2027  
> До реализации capability flags остаются fail-closed.

---

## 8.1. Structured Output

**Structured Output** — ответ модели, валидируемый по JSON Schema.

Pipeline:

```text
JSON Schema
↓
provider request
↓
response
↓
strict schema validation
↓
domain mapping
```

Нужны:

- schema version;
- schema hash;
- provider capability;
- strict server-side validation;
- retry policy только для safe generation failure;
- audit provenance.

---

## 8.2. Vision

Image input:

- PNG/JPEG/WebP;
- bounded dimensions;
- byte limit;
- media-type verification;
- malware/image parser boundary;
- EXIF privacy stripping where policy requires;
- DLP before external provider.

---

## 8.3. Audio

Optional 2027:

- speech-to-text;
- meeting transcription;
- audio summarization;
- text-to-speech.

Требования:

- explicit consent/use-case;
- retention;
- storage policy;
- speaker metadata policy;
- cost accounting;
- language support.

---

## 8.4. Files

Model file input не должен обходить Knowledge ingestion safeguards.

Предпочтительно:

```text
file
↓
SafeAI upload/validation
↓
policy/DLP
↓
controlled provider file API
```

---

# 9. Knowledge Platform 1.0

> **Период:** Q1 2027

---

## 9.1. Reranker

**Reranker** — второй ranking stage после initial vector/lexical retrieval.

Pipeline:

```text
100 candidates
↓
hybrid retrieval
↓
20 candidates
↓
reranker
↓
top 5
```

Provider может быть:

- local cross-encoder;
- external rerank API;
- LLM-based scorer.

Каждый external rerank call получает cost/provenance attempt.

---

## 9.2. Semantic chunking

Current chunking deterministic/structure-aware.

Добавить optional semantic strategy:

```text
STRUCTURE_V1
SEMANTIC_V1
HYBRID_V1
```

Version strategy обязательно сохраняется в chunk provenance.

---

## 9.3. Query rewriting

Для сложных вопросов:

```text
user query
↓
query analyzer
├─ lexical form
├─ semantic form
├─ synonyms
└─ optional subqueries
```

Нужен guard против query drift.

---

## 9.4. Multi-query retrieval

Один вопрос может порождать несколько retrieval queries.

Результаты объединяются и дедуплицируются.

Evaluation должна доказать, что это реально повышает recall.

---

## 9.5. Metadata filters

Фильтры:

- document type;
- date;
- department;
- owner;
- tags;
- region;
- confidentiality;
- lifecycle;
- source connector.

ACL применяется отдельно и раньше.

---

## 9.6. Citation entailment verifier

Текущая citation validation доказывает structural source validity.

Добавить отдельный optional verifier:

```text
claim
+
evidence excerpt
↓
SUPPORTED
PARTIAL
UNSUPPORTED
UNKNOWN
```

Это не абсолютная «проверка истины», а groundedness/evidence consistency signal.

---

## 9.7. Knowledge graph — `OPTIONAL`

Для domains с явными сущностями:

```text
Person
Organization
Contract
Product
System
Policy
```

Можно добавить graph extraction и graph-assisted retrieval.

Не делать graph обязательным для обычного RAG.

---

## 9.8. Duplicate / near-duplicate detection

Content SHA ловит exact duplicate.

Добавить near-duplicate:

- MinHash/SimHash;
- embedding similarity;
- source identity.

---

## 9.9. Knowledge freshness

Показывать:

```text
last source update
last sync
indexed version
stale reason
```

Policy может запрещать source старше N дней.

---

# 10. RAG Evaluation Platform

> **Период:** Q1 2027  
> **Приоритет:** высокий

---

## 10.1. Evaluation Dataset

Versioned dataset:

```text
EvaluationSuite
EvaluationSuiteVersion
EvaluationCase
```

Case:

- question;
- tenant/KB scope;
- expected evidence;
- expected concepts;
- expected abstention;
- risk class;
- language;
- tags.

---

## 10.2. Retrieval metrics

- Recall@K;
- Precision@K;
- MRR;
- nDCG;
- source coverage.

Пояснение:

**Recall@K** — попал ли нужный источник в первые K результатов.  
**MRR** — насколько высоко расположен первый правильный результат.  
**nDCG** — качество порядка нескольких релевантных результатов.

---

## 10.3. Answer metrics

- groundedness;
- citation correctness;
- answer relevance;
- abstention correctness;
- completeness;
- unsupported-claim rate.

---

## 10.4. Human labels

LLM-as-judge нельзя считать единственным «истинным» evaluator.

Нужны:

- human review sample;
- reviewer disagreement;
- adjudication;
- versioned rubric.

---

## 10.5. Regression gates

CI/release gate после изменения:

- model;
- prompt;
- chunking;
- embedding;
- reranker;
- query rewriting;
- extraction;
- OCR;
- retrieval weights;
- policy;
- context builder.

---

## 10.6. Offline / online evaluation

Offline:

```text
fixed dataset
```

Online:

```text
user feedback
thumbs up/down
citation opened
answer copied
follow-up rate
abandonment
```

Online signals не должны автоматически заменять explicit quality labels.

---

# 11. Enterprise Knowledge Connectors

> **Период:** Q1–Q2 2027

Manual upload остаётся.

Добавить connectors:

- Microsoft SharePoint;
- OneDrive;
- Google Drive;
- Confluence;
- Notion;
- Jira;
- GitHub/GitLab repositories;
- Slack;
- web crawler;
- S3 bucket;
- internal REST API;
- SQL read-only source;
- CRM/Service Desk — по реальным customer use cases.

---

## 11.1. Connector model

```text
ConnectorDefinition
ConnectorInstance
ConnectorCredentialReference
ConnectorSyncRun
ConnectorCursor
ConnectorSourceItem
```

---

## 11.2. Incremental sync

```text
source cursor
↓
changed items only
↓
immutable SafeAI version
↓
extract
↓
chunk
↓
embed
↓
publish
```

---

## 11.3. ACL mirroring

Если source имеет permissions, SafeAI должен переносить их.

Пример:

```text
SharePoint document ACL
↓
source principal mapping
↓
SafeAI group/user ACL
```

Нельзя индексировать документ globally, а фильтровать permission только в UI.

---

## 11.4. Deletion / tombstone

Source delete:

```text
source item deleted
↓
SafeAI tombstone
↓
stop serving new retrieval
↓
retention/legal-hold policy
↓
eventual physical cleanup
```

---

## 11.5. Sync observability

- last success;
- last partial;
- last failure;
- changed count;
- deleted count;
- skipped count;
- rate limited;
- credential expired;
- next retry.

---

# 12. Identity 2.0 — OIDC / SCIM / Enterprise SSO

> **Период:** Q1–Q2 2027

---

## 12.1. OIDC

**OIDC (OpenID Connect)** — стандарт входа через корпоративный Identity Provider.

Поддержать:

- Microsoft Entra ID;
- Okta;
- Google Workspace;
- generic OIDC.

---

## 12.2. SAML — только по необходимости

SAML поддерживать при customer requirement.

Не делать основным новым integration path, если OIDC закрывает use case.

---

## 12.3. SCIM

**SCIM** — стандарт автоматического управления пользователями и группами.

```text
IdP
↓
create / update / disable
↓
SafeAI
```

При disable:

```text
user disabled
↓
tokenVersion/security epoch
↓
refresh revoke
↓
active access disappears
```

---

## 12.4. Group sync

```text
IdP group
↓
SafeAI Group
↓
KB / Assistant / Policy / Tool access
```

---

## 12.5. Just-in-Time provisioning

Optional:

первый OIDC login может создать SafeAI user по approved mapping.

Нужны:

- domain allowlist;
- role mapping policy;
- organization mapping;
- audit.

---

## 12.6. Step-up authentication

Для рискованных действий требовать повторное подтверждение identity.

Примеры:

- tool writes;
- production DB action;
- policy change;
- export sensitive data;
- destructive operation.

---

## 12.7. Passkeys / WebAuthn — `OPTIONAL`

Для local-admin or high-risk approval flow.

**Passkey** — phishing-resistant credential, использующий public-key authentication.

---

## 12.8. Continuous Access Evaluation

2027 extension:

получать security-state changes от IdP не только при следующем login.

Пример:

```text
user terminated in IdP
↓
security signal
↓
SafeAI session revoked quickly
```

Можно ориентироваться на OpenID Shared Signals / CAEP после повторной проверки актуальной спецификации перед реализацией.

---

# 13. Groups / Departments / Teams

> **Период:** Q1 2027

Current:

```text
Organization
  └─ Users
```

Target:

```text
Organization
  ├─ Department
  │   ├─ Group
  │   └─ Users
  └─ Team
```

На group/team назначаются:

- Knowledge access;
- Assistants;
- models;
- budget;
- DLP policy;
- tool permission;
- connectors;
- data classification;
- retention;
- evaluation scope.

Group membership изменения auditable.

---

# 14. General Policy Engine

> **Период:** Q2 2027

`OrganizationModelPolicy` остаётся model-governance object.

Не превращать его в mega-policy.

---

## 14.1. Policy input

```text
subject
organization
group
role
assistant
use case
data classification
knowledge source
model
provider
region
tool
action
risk
time
```

---

## 14.2. Policy outcomes

```text
ALLOW
DENY
WARN
REDACT
REQUIRE_APPROVAL
ROUTE
LIMIT
LOCAL_ONLY
```

---

## 14.3. Example

```text
WHEN
  department = FINANCE
  AND data.classification >= CONFIDENTIAL
THEN
  external_provider = DENY
  allowed_model_group = LOCAL_APPROVED
  audit_level = HIGH
```

---

## 14.4. Policy decision provenance

Каждый важный execution сохраняет:

- policy set version;
- matched rules;
- decision;
- reason codes;
- input facts hash;
- evaluator version.

---

## 14.5. Policy simulator

Admin UI:

```text
"If user X asks assistant Y with CONFIDENTIAL data,
what happens?"
```

Результат без реального provider call.

---

# 15. Data Classification + DLP

> **Период:** Q2 2027  
> **DLP = Data Loss Prevention, защита от утечки данных.**

---

## 15.1. Classification

Базовые классы:

```text
PUBLIC
INTERNAL
CONFIDENTIAL
RESTRICTED
```

Organization может создать свои labels.

---

## 15.2. Detectors

- email;
- phone;
- passport/ID;
- payment/card data;
- bank account;
- tax identifiers;
- API keys;
- bearer tokens;
- passwords;
- private keys;
- connection strings;
- source code secrets;
- customer-specific dictionaries.

---

## 15.3. Inspection points

DLP применяется к:

```text
user prompt
uploaded files
retrieved chunks
connector content
model output
tool arguments
tool result
agent memory
export
```

---

## 15.4. Actions

```text
ALLOW
WARN
REDACT
BLOCK
REQUIRE_APPROVAL
ROUTE_TO_LOCAL
```

---

## 15.5. Redaction provenance

Нужно сохранять:

- detector version;
- number/type detections;
- redaction ranges/hash;
- action;
- policy version.

Не хранить secret в audit details.

---

## 15.6. Data egress policy

Пример:

```text
RESTRICTED
→ local provider only

CONFIDENTIAL
→ approved EU region only

INTERNAL
→ approved providers

PUBLIC
→ all allowed
```

---

# 16. AI FinOps

> **Период:** Q2 2027  
> **FinOps** — управление затратами: кто потратил, на что, сколько и почему.

---

## 16.1. Physical spend ledger

### Current baseline после V60

Уже существует read model `model_cost_commitment_ledger_v60` с отдельными evidenced actual costs, reservations и unknown liability. Он является **accounting commitment view**, но ещё не billing-grade settlement ledger.

### Target source of truth

```text
PhysicalSpendEntry
```

`PhysicalSpendEntry` должен стать durable immutable ledger entry, а V60 view может использоваться как migration/read-model foundation и reconciliation input.

Создаётся из provider attempts:

- chat generation;
- embeddings;
- OCR;
- rerank;
- image;
- audio;
- tools;
- agent runs.

---

## 16.2. Cost states

```text
KNOWN
FREE
UNPRICED
PARTIAL
ESTIMATED
RECONCILIATION_PENDING
INVOICE_ADJUSTED
```

Unknown никогда не становится zero.

---

## 16.3. Budget hierarchy

```text
Organization
  ↓
Department
  ↓
Project
  ↓
Assistant
  ↓
User
  ↓
Agent
  ↓
Model
```

---

## 16.4. Budget actions

Threshold example:

```text
70%  informational
80%  warning
90%  downgrade/route
100% block for HARD policy
```

---

## 16.5. Reservations

До external call:

```text
reserve worst-case cost
```

После:

```text
settle actual
```

Если outcome ambiguous:

```text
retain conservative commitment
```

---

## 16.6. Provider invoice reconciliation

Импорт provider billing export/invoice:

```text
provider invoice
↓
match request/usage/time
↓
reconcile
↓
adjustment entry
```

Не переписывать historical attempt evidence.

---

## 16.7. Showback / Chargeback

**Showback** — показать подразделению его расходы.  
**Chargeback** — реально распределить стоимость по внутренним cost center.

Dimensions:

- organization;
- department;
- project;
- user;
- assistant;
- model;
- provider;
- Knowledge;
- agent;
- tool.

---

## 16.8. Forecasting

Forecast:

- daily burn rate;
- end-of-month estimate;
- seasonality;
- committed reservations;
- unknown risk.

Forecast не является accounting truth.

---

# 17. Prompt / Configuration Registry

> **Период:** Q2 2027

System prompt не должен существовать только как строка в Java/Git.

---

## 17.1. Entities

```text
PromptTemplate
PromptVersion
ConfigurationBundle
ConfigurationVersion
```

---

## 17.2. Lifecycle

```text
DRAFT
IN_REVIEW
APPROVED
ACTIVE
RETIRED
```

---

## 17.3. Promotion

```text
DEV
↓
STAGE
↓
PROD
```

С exact version, approval и rollback.

---

## 17.4. Provenance

ChatTurn/AgentRun хранит:

```text
promptVersionId
configurationVersionId
```

---

## 17.5. Prompt experiments

Controlled A/B:

```text
variant A
variant B
```

Evaluation first.

Нельзя менять prompt для 50% production пользователей без audit/experiment identity.

---

# 18. AI Use Case Registry / Governance

> **Период:** Q2 2027

Создать register:

```text
UseCase
Owner
BusinessPurpose
AllowedModels
DataClasses
HumanOversight
RiskLevel
AllowedTools
Retention
ApprovalState
```

---

## Risk levels

Например:

```text
LOW
MEDIUM
HIGH
CRITICAL
```

Risk — organization governance construct, не юридическое заключение платформы.

---

## Use-case approval

```text
DRAFT
REVIEW
APPROVED
SUSPENDED
RETIRED
```

Execution может требовать active approved use case.

---

# 19. AI Literacy / Acceptable Use

> **Период:** Q2 2027

Organization AI policy acceptance:

```text
policyVersion
userId
organizationId
acceptedAt
```

При новой version:

```text
forced re-acceptance
```

Admin dashboard:

- accepted;
- pending;
- expired;
- exempted by policy.

---

# 20. Observability 2.0

> **Период:** Q2 2027

---

## 20.1. OpenTelemetry

**OpenTelemetry** — стандарт traces, metrics и logs.

Trace:

```text
HTTP request
↓
ChatTurn
↓
Policy
↓
Model Route
↓
Knowledge Retrieval
↓
Embedding / Rerank
↓
Provider Attempt
↓
Tool Call
```

Использовать актуальные на момент implementation GenAI semantic conventions; не копировать экспериментальные attribute names навечно.

---

## 20.2. Correlation IDs

Связать:

- request ID;
- client request ID;
- chat turn;
- provider operation;
- execution plan;
- attempt;
- retrieval run;
- answer passport;
- agent run;
- tool execution.

---

## 20.3. SLO

**SLO** — измеримая цель качества сервиса.

Примеры:

- auth availability;
- chat API availability;
- p95 route latency;
- provider-start latency;
- ingestion freshness;
- connector lag;
- retrieval latency.

---

## 20.4. Error budget

**Error budget** — допустимый объём нарушения SLO за период.

Использовать для release discipline, а не как marketing metric.

---

## 20.5. Privacy-aware telemetry

По умолчанию не отправлять в telemetry:

- raw prompts;
- full model responses;
- raw chunks;
- secrets;
- tokens;
- credentials.

---

# 21. Enterprise Assistants

> **Период:** Q2–Q3 2027

Assistant — versioned configuration object.

---

## 21.1. Assistant definition

```text
Assistant
AssistantVersion
```

Поля:

- name;
- description;
- owner;
- use case;
- prompt version;
- Knowledge scope;
- allowed model policy;
- tools;
- groups;
- budget;
- retention;
- DLP policy;
- response format;
- starter prompts.

---

## 21.2. Versioning

Изменение assistant config создаёт новую version.

Historical ChatTurn хранит exact assistant version.

---

## 21.3. Assistant directory

User видит только assistants, разрешённые:

- organization;
- group;
- role;
- use case.

---

## 21.4. Assistant analytics

- active users;
- requests;
- success;
- cost;
- latency;
- citation rate;
- user feedback;
- tool calls;
- policy blocks.

---

# 22. Enterprise API + Service Accounts

> **Период:** Q2–Q3 2027

SafeAI становится infrastructure layer не только для собственного UI.

---

## 22.1. Service Account

Отдельная machine identity:

```text
ServiceAccount
organization
scopes
budget
rate limit
allowed models
allowed assistants
allowed tools
credential
expiry
```

Не маскировать machine identity под обычного user.

---

## 22.2. API credential

Хранить hash, а не raw token.

Поддержать:

- rotation;
- expiry;
- last used;
- scopes;
- revocation.

---

## 22.3. API v1

Пример:

```http
POST /api/v1/responses
POST /api/v1/retrieval/search
POST /api/v1/assistants/{id}/runs
GET  /api/v1/operations/{id}
```

---

## 22.4. API contract

Нужны:

- OpenAPI;
- versioning;
- idempotency;
- pagination;
- stable errors;
- request limits;
- async operation model;
- SDK generation;
- deprecation policy.

---

# 23. Secure Tool Gateway

> **Период:** Q3 2027

LLM не получает прямой arbitrary network access.

```text
LLM / Agent
    ↓
SafeAI Tool Gateway
    ├─ authentication
    ├─ authorization
    ├─ tenant
    ├─ policy
    ├─ DLP
    ├─ approval
    ├─ rate limit
    ├─ credential binding
    ├─ audit
    └─ idempotency
    ↓
Internal APIs / MCP / SaaS
```

---

# 24. Tool Registry

> **Период:** Q3 2027

Metadata:

```text
Tool
ToolVersion
ToolOperation
```

Fields:

- name;
- description;
- input schema;
- output schema;
- owner;
- risk;
- read/write;
- side-effect class;
- allowed groups;
- required scopes;
- credential binding;
- timeout;
- rate limit;
- approval policy;
- data classification;
- idempotency support.

---

## Risk examples

```text
Jira.search            LOW
Jira.createIssue       MEDIUM
Slack.postMessage      MEDIUM
CRM.updateCustomer     HIGH
ProdDB.execute         CRITICAL
```

---

# 25. MCP Gateway

> **Период:** Q3 2027

**MCP (Model Context Protocol)** — открытый протокол, через который AI-приложение может обнаруживать и вызывать tools/resources внешних систем.

SafeAI должен работать как controlled MCP gateway, а не как unrestricted proxy.

---

## 25.1. MCP security

- OAuth-based auth where applicable;
- exact token audience/resource validation;
- credential isolation;
- scope enforcement;
- tenant isolation;
- tool allowlist;
- policy;
- DLP;
- egress allowlist.

Перед implementation проверять актуальную released MCP specification и SDK conformance.

---

## 25.2. MCP version pinning

Каждое connection/session сохраняет:

```text
protocolVersion
server identity
capabilities
```

Не использовать «latest» как silent production behavior.

---

## 25.3. MCP tasks

Для long-running tools использовать durable task abstraction, если выбранная актуальная MCP specification поддерживает соответствующий extension.

SafeAI всё равно сохраняет собственный durable ToolExecution state.

External protocol task ID не заменяет internal security/idempotency identity.

---

# 26. Human-in-the-loop approvals

> **Период:** Q3 2027

**Human-in-the-loop** — человек подтверждает рискованное действие до side effect.

Flow:

```text
Agent proposes exact action
↓
payload canonicalization + hash
↓
ApprovalRequest
↓
authorized approver
↓
APPROVE / DENY
↓
execute exactly approved payload
```

---

## 26.1. Approval requirements

- exact tool version;
- exact arguments hash;
- requester;
- approver;
- expiry;
- required role/group;
- reason;
- immutable audit.

---

## 26.2. Payload mutation protection

После approval:

```text
arguments changed
→ approval invalid
```

---

## 26.3. Multi-party approval

Для CRITICAL actions:

```text
2-person rule
```

Например:

- production DB write;
- financial action;
- mass customer communication.

---

# 27. Durable Tool Execution

> **Период:** Q3 2027

Перенести lessons из AI attempts.

State machine:

```text
CREATED
STARTED
SUCCEEDED
FAILED
AMBIGUOUS
CANCELLED
RECONCILIATION_REQUIRED
```

Нужны:

- idempotency key;
- tool request hash;
- credential reference;
- STARTED evidence;
- outcome certainty;
- retry safety;
- response hash;
- side-effect evidence.

---

# 28. Durable Agent Runtime

> **Период:** Q3–Q4 2027

Не делать volatile loop:

```text
while(true) {
  llm();
  tool();
}
```

---

## 28.1. Entities

```text
AgentDefinition
AgentVersion
AgentRun
AgentStep
ToolExecution
ApprovalRequest
AgentCheckpoint
```

---

## 28.2. State machine

```text
CREATED
RUNNING
WAITING_FOR_MODEL
WAITING_FOR_TOOL
WAITING_FOR_APPROVAL
WAITING_FOR_TIMER
PAUSED
SUCCEEDED
FAILED
AMBIGUOUS
CANCELLED
```

---

## 28.3. Durable step

Каждый step:

- ID;
- attempt;
- input hash;
- model route;
- prompt/config version;
- tool proposal;
- usage;
- cost;
- start/end;
- outcome;
- provenance.

---

## 28.4. Limits

Per run:

- max steps;
- max wall-clock duration;
- max model calls;
- max tool calls;
- max cost;
- max tokens/units;
- max retries.

---

## 28.5. Pause / resume

Agent можно:

- pause;
- resume;
- cancel;
- wait for approval;
- wait until scheduled time.

State persisted.

---

## 28.6. Durable timers

```text
WAIT_UNTIL 2027-...
```

не держит JVM thread.

Использовать persisted timer job/scheduler.

---

# 29. Agent Memory

> **Период:** Q4 2027

Разделить:

```text
Conversation memory
Working memory
Long-term user memory
Organization knowledge
Agent state
```

Memory никогда не является authorization source.

---

## 29.1. Memory policy

- opt-in/organization policy;
- TTL;
- data classification;
- user visibility;
- deletion;
- export;
- source/provenance.

---

## 29.2. Memory poisoning protection

Retrieved or tool-provided content не должно автоматически записываться в trusted long-term memory.

Нужны:

- source label;
- sanitization;
- confidence;
- approval rules.

---

# 30. Agent Security

> **Период:** одновременно с Agents

Основной invariant:

```text
prompt content != authorization
```

---

## Добавить

- capability scopes;
- short-lived tool credentials;
- per-run credential isolation;
- network egress control;
- prompt injection detection;
- data exfiltration checks;
- max-step/max-cost;
- dangerous-tool deny;
- approval;
- sandboxing;
- audit.

---

# 31. Sandboxed Code Interpreter

> **Период:** Q3–Q4 2027

Для Python/data analysis нельзя выполнять untrusted code внутри backend JVM.

Architecture:

```text
Agent
↓
CodeExecutionRequest
↓
Sandbox Orchestrator
↓
ephemeral container/microVM
```

Limits:

- CPU;
- RAM;
- disk;
- process count;
- timeout;
- no network by default;
- package policy;
- ephemeral filesystem;
- artifact size;
- malware scan.

---

## Supported use cases

- CSV analysis;
- Excel analysis;
- chart creation;
- PDF tabular analysis;
- safe Python calculations;
- generated artifacts.

---

# 32. Artifact Workspace

> **Период:** Q3–Q4 2027

AI может создавать managed artifacts:

- Markdown;
- PDF;
- DOCX;
- XLSX;
- PPTX;
- charts;
- JSON/CSV;
- code patches.

Каждый artifact:

```text
Artifact
ArtifactVersion
producer Run/Turn
content hash
classification
owner
retention
```

---

# 33. Async / Batch AI Jobs

> **Период:** Q3 2027

Для large workloads:

```text
BatchJob
BatchItem
```

Use cases:

- summarize 10k tickets;
- classify documents;
- regenerate embeddings;
- offline evaluation;
- nightly report.

Нужны:

- durable state;
- checkpoint;
- concurrency;
- budget;
- cancellation;
- partial result;
- retry policy.

---

# 34. Scheduled Automations

> **Период:** Q3–Q4 2027

Examples:

```text
Every morning:
summarize new Jira incidents

When connector sync completes:
run compliance review

Every Monday:
generate department AI usage report
```

Scheduler хранит:

- definition version;
- next run;
- timezone;
- owner;
- budget;
- permissions;
- audit.

---

# 35. Workflow / DAG Engine

> **Период:** Q4 2027  
> **DAG** — направленный ациклический граф шагов.

Пример:

```text
Retrieve
  ↓
Classify
  ↓
Generate draft
  ↓
Human approval
  ↓
Publish
```

Поддержать:

- conditional branches;
- parallel steps;
- retries;
- timers;
- approvals;
- compensation.

Не превращать ChatService в workflow engine.

---

# 36. A2A Gateway

> **Период:** Q4 2027

**A2A (Agent-to-Agent)** — протокол взаимодействия независимых AI agents.

На 2026 существует released A2A 1.0; перед production implementation всё равно необходимо проверить актуальную версию стандарта и conformance.

SafeAI:

```text
Internal Agent
↓
SafeAI A2A Gateway
↓
trusted external agent
```

---

## 36.1. Trust profile

External agent registry:

- identity;
- owner;
- endpoint;
- certificate/key;
- allowed capabilities;
- tenant;
- data classes;
- max budget;
- allowed tasks;
- risk rating.

---

## 36.2. Do not delegate authorization

External agent не получает permissions внутреннего user автоматически.

Каждый cross-agent action заново проходит:

- policy;
- DLP;
- tool authorization;
- budget.

---

# 37. Multi-Agent Collaboration

> **Период:** Q4 2027  
> После single-agent durability.

Roles:

```text
Coordinator
Researcher
Retriever
Reviewer
Executor
```

Но multi-agent orchestration должна иметь:

- bounded participants;
- bounded turns;
- budget;
- run graph;
- provenance;
- loop detection;
- cancellation.

---

# 38. Security Center

> **Период:** Q3–Q4 2027

AuditEvent и SecurityEvent — разные concepts.

---

## SecurityEvent examples

- prompt injection detected;
- DLP blocked;
- secret detected;
- model policy bypass attempt;
- cross-tenant access attempt;
- suspicious connector behavior;
- anomalous tool call;
- repeated approval denials;
- agent loop;
- data exfiltration signal.

---

## Dashboard

- severity;
- tenant;
- user/service account;
- assistant/agent;
- source;
- correlated request/run;
- status;
- assignee;
- resolution.

---

# 39. Continuous AI Security Evaluations

> **Период:** Q4 2027

Regression suite:

- prompt injection;
- indirect prompt injection;
- jailbreak;
- system prompt extraction;
- cross-tenant leakage;
- malicious document;
- malicious MCP server;
- secret exfiltration;
- unsafe tool calls;
- approval bypass;
- privilege escalation;
- memory poisoning;
- policy bypass;
- idempotency abuse;
- agent runaway loop.

Run after changes to:

- model;
- prompt;
- provider;
- RAG;
- connector;
- DLP;
- policy;
- tool;
- agent.

---

# 40. Supply Chain Security

> **Период:** 2027

---

## SBOM

**SBOM** — Software Bill of Materials, список библиотек и компонентов build.

Generate per release.

---

## Dependency scanning

- Maven dependencies;
- npm dependencies;
- container images;
- OS packages.

---

## Image signing

Подписывать production container artifacts.

---

## Build provenance

Хранить:

- source commit;
- build ID;
- dependency lock;
- image digest;
- migration version.

---

## Secret scanning

Git/pre-commit/CI:

- API key patterns;
- private keys;
- credentials.

---

# 41. Secrets / KMS / BYOK

> **Период:** Q2–Q3 2027

**KMS** — Key Management Service, система безопасного хранения/управления ключами.

---

## Secret Manager integration

Не хранить provider secrets в application.yml.

Adapters:

- Vault;
- cloud secret manager;
- KMS-encrypted DB reference where justified.

---

## BYOK

**BYOK — Bring Your Own Key.**

Tenant может использовать собственный provider credential.

Требования:

- encrypted storage;
- tenant isolation;
- KMS envelope encryption;
- rotation;
- last-used metadata;
- no plaintext logging;
- audit.

---

## Customer-managed encryption key

Optional enterprise tier:

customer controls encryption key for selected stored data.

---

# 42. Data Retention / Legal Hold / Privacy

> **Период:** Q2–Q4 2027

Разные retention policies:

- chat;
- audit;
- usage;
- provider execution evidence;
- Knowledge source;
- chunks;
- embeddings;
- Answer Passport;
- DLP events;
- tool executions;
- agent runs;
- artifacts.

---

## Legal Hold

**Legal hold** — запрет удаления данных, попавших под юридическое/внутреннее расследование.

Hold должен иметь:

- scope;
- owner;
- reason;
- created/removed audit.

---

## Privacy deletion

Удаление пользователя не означает naïve cascade delete.

Нужны стратегии:

- delete;
- pseudonymize;
- detach identity;
- preserve legally required audit evidence.

---

# 43. Data Residency / Sovereignty

> **Период:** Q2–Q3 2027

Policy:

```text
EU_ONLY
US_ONLY
LOCAL_ONLY
CUSTOM_REGION_SET
```

Применяется к:

- provider;
- object storage;
- embeddings;
- OCR;
- reranker;
- connector;
- logs.

---

# 44. High Availability / Disaster Recovery

> **Период:** 2027

Нельзя писать `HA ready` без drills.

---

## PostgreSQL

- managed HA or tested replication;
- PITR;
- backups;
- restore;
- migration rollback strategy at application level.

**PITR** — Point-In-Time Recovery, восстановление БД к конкретному моменту.

---

## Redis

- Sentinel/Cluster/managed HA;
- failover tests;
- rate-limit semantics during failure.

---

## Object storage

- versioning where needed;
- replication;
- restore;
- lifecycle;
- integrity checks.

---

## RTO / RPO

**RTO** — сколько времени допустимо восстанавливать сервис.  
**RPO** — сколько данных допустимо потерять по времени.

Установить и проверить drill-ом.

---

# 45. Performance / Capacity Engineering

> **Период:** весь 2027

Load profiles:

- auth burst;
- chat burst;
- long streaming connections;
- high RAG QPS;
- large ingestion batch;
- connector sync;
- usage report;
- audit query;
- agent workloads.

---

## Performance budgets

Определить:

- p50/p95/p99 latency;
- connection pool utilization;
- DB query budget;
- Redis ops;
- provider concurrency;
- JVM heap;
- GC pause;
- thread/virtual-thread count.

---

## Backpressure

Если downstream перегружен:

```text
queue / reject / defer
```

а не бесконечно создавать work.

---

# 46. Semantic Cache — `OPTIONAL`

> **Период:** 2027

**Semantic cache** — повторное использование ответа на семантически похожий запрос.

Рискованно для enterprise data.

Разрешать только при exact policy:

- same tenant;
- same assistant/config;
- same model policy;
- same Knowledge version set;
- compatible security context;
- bounded similarity;
- expiry.

По умолчанию выключено.

---

# 47. Response Cache — `OPTIONAL`

Exact request cache возможен для deterministic utility calls, но не должен обходить:

- auth;
- DLP;
- policy;
- retention;
- current access.

---

# 48. Search / Discovery UX

> **Период:** 2027

Unified search:

- chats;
- projects;
- Knowledge;
- assistants;
- artifacts;
- audit for admins;
- model catalog.

Tenant-safe permission filtering до выдачи результатов.

---

# 49. Collaboration UX

> **Период:** Q2–Q4 2027

- share chat внутри tenant;
- shared project;
- comments;
- mention users;
- pin source;
- save answer to Knowledge draft;
- assistant favorites;
- activity feed.

Sharing не должен повышать permission на source documents автоматически.

---

# 50. Feedback / Quality Loop

User feedback:

```text
helpful
not helpful
wrong citation
outdated source
unsafe
too expensive
```

Feedback связывается с exact:

- ChatTurn;
- model;
- route;
- retrieval;
- assistant version.

Используется в evaluation dataset c human review.

---

# 51. Admin Policy Simulator

Admin вводит hypothetical facts:

```text
role=USER
department=FINANCE
classification=CONFIDENTIAL
assistant=ContractReview
```

Получает:

- allowed model;
- provider;
- DLP action;
- tool permissions;
- budget;
- reasons.

Без provider call.

---

# 52. Explainability / Decision Inspector

Для support/admin:

```text
Why was this request blocked?
```

Ответ:

- matched policy;
- model rule;
- budget state;
- DLP;
- capability;
- provider health;
- tenant restriction.

Не показывать secrets/internal stack.

---

# 53. Reconciliation Center

Unified operational UI:

- ambiguous AI attempt;
- unknown provider outcome;
- failed Knowledge upload intent;
- orphan cleanup review;
- connector partial sync;
- unknown pricing;
- invoice mismatch;
- agent/tool ambiguous side effect.

Operator может:

- inspect;
- mark reconciled;
- attach evidence;
- retry only when safe.

---

# 54. Feature Flags / Progressive Delivery

> **Период:** 2027

Versioned feature flags:

- organization;
- group;
- environment;
- percentage.

Использовать для:

- provider rollout;
- reranker;
- new retrieval;
- streaming;
- assistant beta.

Flag state, влияющий на execution, должен попадать в provenance.

---

# 55. Release Engineering

---

## 55.1. Environments

```text
local
test
stage
production
```

Production mode определяется explicit invariant, а не случайным profile naming.

---

## 55.2. Promotion

```text
commit
↓
tests
↓
image
↓
stage
↓
integration/evals
↓
approval
↓
prod
```

---

## 55.3. Migration gate

Перед production:

- Flyway validate;
- fresh migration CI;
- upgrade migration CI;
- catalog index validity;
- long-running migration review;
- lock impact review.

---

## 55.4. Zero/low-downtime migrations

Patterns:

```text
expand
backfill
switch
contract
```

Не делать destructive schema change одновременно с code switch.

---

# 56. Production Readiness Checklist 1.0

Release 1.0 нельзя считать production-ready только по feature completion.

Нужно доказать:

```text
Security
✓ tenant isolation
✓ auth/session revocation
✓ secrets
✓ DLP baseline
✓ vulnerability management

Data
✓ backup
✓ restore
✓ retention
✓ object storage recovery

Reliability
✓ multi-instance
✓ failure tests
✓ provider outage handling
✓ reconciliation

Database
✓ fresh migrations
✓ upgrade migrations
✓ index validity
✓ constraints

AI
✓ attempt evidence
✓ no blind retry
✓ pricing quality
✓ model registry
✓ eval gates

Knowledge
✓ publication atomicity
✓ ACL
✓ OCR partial handling
✓ parser isolation
✓ generation GC

Operations
✓ dashboards
✓ alerts
✓ runbooks
✓ on-call procedures

Governance
✓ audit
✓ policy provenance
✓ model approval
✓ use-case ownership
```

---

# 57. Product Release Targets

Названия ниже — planning targets, не утверждение о существующих release tags.

---

## SafeAI Desk 0.8 — Core Hardening

**Target:** Q4 2026

- P0/P1 fixes;
- AI execution recovery;
- physical/application outcome split;
- Knowledge publication fix;
- parser/OCR hardening;
- audit transaction semantics;
- production-mode unification;
- RC verification.

---

## SafeAI Desk 0.9 — Dynamic AI Gateway

**Target:** Q1 2027

- multi-provider runtime;
- provider configuration registry;
- pricing v2;
- fallback safety;
- streaming;
- cancel;
- model lifecycle/approval;
- physical spend ledger.

---

## SafeAI Desk 1.0 — Enterprise AI Gateway

**Target:** Q2 2027

- OIDC;
- SCIM;
- groups;
- DLP;
- General Policy Engine;
- enterprise connectors;
- FinOps;
- Prompt Registry;
- RAG evaluation gates;
- observability/SLO;
- retention baseline;
- HA/restore evidence.

---

## SafeAI Desk 1.2 — Enterprise Assistants

**Target:** Q2–Q3 2027

- Assistants;
- assistant versioning;
- Use Case Registry;
- service accounts;
- Enterprise API;
- structured outputs;
- vision;
- collaboration;
- policy simulator.

---

## SafeAI Desk 1.5 — Tools Platform

**Target:** Q3 2027

- Tool Registry;
- Secure Tool Gateway;
- MCP Gateway;
- approvals;
- durable ToolExecution;
- sandboxed code interpreter;
- artifacts;
- scheduled automations.

---

## SafeAI Desk 2.0 — Governed Agent Platform

**Target:** Q4 2027

- durable Agent Runtime;
- agent memory;
- workflow/DAG;
- A2A gateway;
- controlled multi-agent;
- Security Center;
- continuous red-team evals;
- advanced governance.

---

# 58. Quarterly plan

## Q4 2026

```text
P0/P1 core fixes
↓
release-candidate stabilization
↓
Knowledge hardening
↓
AI execution recovery
↓
pricing v2 design
↓
multi-provider registry foundation
```

---

## Q1 2027

```text
dynamic provider dispatch
↓
streaming
↓
structured outputs
↓
Knowledge reranker/evals
↓
enterprise connectors
↓
OIDC/SCIM foundation
```

---

## Q2 2027

```text
DLP
↓
General Policy Engine
↓
FinOps
↓
Prompt Registry
↓
Assistants
↓
OpenTelemetry/SLO
↓
Enterprise API
```

---

## Q3 2027

```text
Tool Registry
↓
MCP Gateway
↓
Human approvals
↓
Durable ToolExecution
↓
Sandbox
↓
Artifacts
↓
Agent Runtime v1
```

---

## Q4 2027

```text
Agent Runtime hardening
↓
Agent memory/workflows
↓
A2A
↓
multi-agent
↓
Security Center
↓
continuous AI security evals
↓
HA/DR maturity
```

---

# 59. Dependency graph

```text
Core P0/P1
    │
    ├────────────► Multi-provider
    │                   │
    │                   ├────► FinOps
    │                   ├────► Structured Output
    │                   └────► Vision/Audio
    │
    ├────────────► Knowledge hardening
    │                   │
    │                   ├────► Connectors
    │                   └────► RAG Evaluations
    │
    └────────────► Identity 2.0
                        │
                        ├────► Groups
                        └────► Policy Engine
                                     │
              ┌──────────────────────┼───────────────────────┐
              │                      │                       │
             DLP                 Assistants             Tool Gateway
                                                           │
                                                           ├─ MCP
                                                           ├─ Approvals
                                                           └─ ToolExecution
                                                                │
                                                                ▼
                                                          Agent Runtime
                                                                │
                                                          ┌─────┴─────┐
                                                          │           │
                                                         A2A       Multi-Agent
```

---

# 60. Что не делать раньше времени

Пока предыдущая dependency не закрыта, не стоит:

- строить agents до durable ToolExecution;
- давать tools без authorization/approval;
- включать vision только потому, что catalog содержит capability;
- делать semantic cache без security identity;
- считать model budget billing-grade;
- делать fallback после ambiguous provider call;
- позволять frontend выбирать physical provider в обход policy;
- хранить secrets в route decision;
- использовать prompt как permission;
- делать KB ACL только после retrieval;
- создавать второй model registry;
- превращать OrganizationModelPolicy в универсальный mega-object;
- добавлять микросервисы только «для enterprise вида»;
- использовать LLM-as-judge как единственный quality truth;
- называть систему HA без restore/failover drills.

---

# 61. Definition of Done для каждого крупного feature

Feature не считается production-ready, пока нет:

```text
1. Domain semantics
2. Authorization
3. Tenant isolation
4. Persistence/integrity
5. Idempotency where needed
6. Failure semantics
7. Audit
8. Metrics
9. Public error contract
10. Migration
11. Tests
12. Operational runbook
13. Security review
14. Data retention behavior
15. Frontend contract
16. Documentation
```

Для external side effect дополнительно:

```text
17. STARTED evidence
18. Outcome certainty
19. Retry safety
20. Reconciliation path
```

---

# 62. Термины

## AI Gateway

Контролируемый слой между корпоративным приложением и AI providers.

Он применяет identity, policy, routing, budget, DLP, audit и observability.

## Control Plane

Слой, который хранит и принимает governance decisions:

- какие модели существуют;
- что разрешено;
- какие policies активны;
- какие budgets действуют.

## Data Plane

Слой, который реально выполняет provider/tool operations.

## RAG

**Retrieval-Augmented Generation** — перед генерацией ответа система находит релевантные корпоративные источники и передаёт их модели как context.

## Embedding

Числовой вектор смысла текста, используемый для semantic search.

## Reranker

Модель второго этапа, которая более точно переупорядочивает найденные документы.

## DLP

**Data Loss Prevention** — обнаружение и блокировка/редактирование чувствительных данных.

## FinOps

Практика управления cloud/AI затратами, бюджетами и распределением расходов.

## OIDC

**OpenID Connect** — стандарт federated login поверх OAuth.

## SCIM

Стандарт provisioning пользователей и групп между Identity Provider и приложением.

## MCP

**Model Context Protocol** — протокол подключения AI application к tools/resources внешних систем.

## A2A

**Agent-to-Agent** — протокол взаимодействия независимых AI agents.

## Tool

Внешняя операция, которую AI может предложить выполнить.

## Side effect

Изменение внешнего мира: создать ticket, отправить письмо, изменить запись DB и т.д.

## Human-in-the-loop

Обязательное участие человека в принятии/подтверждении решения.

## Idempotency

Повтор одного и того же operation ID не создаёт второй логический side effect.

## Fencing

Механизм, запрещающий старому worker завершить работу после того, как ownership перешёл новому worker.

## Reconciliation

Разбор ситуации, когда система не может автоматически доказать финальный outcome external call.

## SLO

**Service Level Objective** — измеримая цель качества/доступности.

## RTO

Максимально допустимое время восстановления после аварии.

## RPO

Максимально допустимая потеря данных по времени.

## SBOM

Список компонентов и библиотек, вошедших в build.

## KMS

Сервис управления криптографическими ключами.

## BYOK

Клиент использует собственный provider/encryption key.

## Legal Hold

Запрет удаления выбранных данных на период расследования/обязательного хранения.

## Canary

Новая версия получает небольшой процент production traffic для контролируемой проверки.

## Shadow Traffic

Запрос копируется в экспериментальный path без влияния его ответа на пользователя.

## Circuit Breaker

Автоматически временно отключает проблемный downstream.

## Backpressure

Ограничение скорости/очереди, когда downstream не успевает обрабатывать поток.

## DAG

Граф workflow, в котором шаги и зависимости задаются явно.

## SLI

**Service Level Indicator** — фактически измеряемая метрика надежности, на основании которой проверяется SLO.

## ABAC

**Attribute-Based Access Control** — доступ по атрибутам subject/resource/context, а не только по фиксированной системной роли.

## Blue/Green deployment

Две параллельные production-среды; трафик переключается между ними после проверки новой версии.

## Chaos testing

Контролируемое внесение отказов, чтобы доказать recovery/failover semantics до реального инцидента.

---

# 63. Standards watch на 2027

Стандарты быстро меняются. Их нельзя вшивать в архитектуру без version pinning.

Перед implementation повторно проверить актуальные released specifications для:

- MCP;
- A2A;
- OpenTelemetry GenAI conventions;
- OpenID Connect;
- SCIM;
- OpenID Shared Signals / CAEP;
- WebAuthn;
- OAuth security best practices.

Принцип:

```text
spec version is configuration/provenance
```

а не скрытое «latest».

---

# 64. Главные продуктовые направления 2027

Если выделить пять наиболее ценных направлений:

## 1. Executable AI Control Plane

Не только хранить policy/catalog, а безопасно исполнять multi-provider routing.

## 2. Enterprise Knowledge

Не просто upload + RAG, а connectors, ACL sync, evaluation, freshness и provenance.

## 3. Governance + DLP + FinOps

Понимать:

```text
кому можно
что можно
куда можно отправлять
сколько можно тратить
```

## 4. Enterprise Assistants + Tools

Создавать управляемые AI applications поверх общей platform security.

## 5. Durable Agents

Только после того, как side effects, approvals, identity, budget и reconciliation уже production-grade.

---

# 65. Конечная целевая архитектура на конец 2027

```text
┌──────────────────────────────────────────────────────────────────────┐
│                           SafeAI Desk                                │
├──────────────────────────────────────────────────────────────────────┤
│ Identity                                                             │
│ OIDC / SCIM / Groups / Service Accounts / Security Epochs            │
├──────────────────────────────────────────────────────────────────────┤
│ Governance                                                           │
│ Policy Engine / DLP / Use Cases / Approvals / Retention              │
├──────────────────────────────────────────────────────────────────────┤
│ AI Control Plane                                                     │
│ Model Catalog / Provider Registry / Routing / Pricing / FinOps       │
├──────────────────────────────────────────────────────────────────────┤
│ Knowledge                                                            │
│ Connectors / ACL / Extraction / OCR / Embeddings / Rerank / RAG     │
├──────────────────────────────────────────────────────────────────────┤
│ AI Workspace                                                         │
│ Projects / Chat / Assistants / Artifacts / Search                    │
├──────────────────────────────────────────────────────────────────────┤
│ Tool Platform                                                        │
│ Tool Registry / MCP Gateway / DLP / Approval / ToolExecution         │
├──────────────────────────────────────────────────────────────────────┤
│ Agent Runtime                                                        │
│ Durable Runs / Steps / Timers / Memory / Workflows / A2A            │
├──────────────────────────────────────────────────────────────────────┤
│ Operations                                                           │
│ Audit / Security Center / OpenTelemetry / SLO / Reconciliation       │
└──────────────────────────────────────────────────────────────────────┘
```

---

# 66. Финальный принцип развития

SafeAI Desk должен расти не за счёт добавления всё большего количества «AI magic», а за счёт управляемости.

Каждая новая возможность должна отвечать на вопросы:

```text
Кто это сделал?
Почему ему было разрешено?
Какая версия policy действовала?
Какая модель/provider использовались?
Какие данные были переданы?
Какие внешние side effects произошли?
Сколько это стоило?
Можно ли доказать outcome?
Можно ли безопасно повторить?
Как это удалить/удержать по retention policy?
Как это расследовать при инциденте?
```

Только после этого capability считается частью production-платформы.

---

# 67. Release Gates — обязательные точки допуска

Release target сам по себе не является разрешением двигаться дальше. Для крупных capabilities вводятся dependency gates.

## Gate A — Production Core

Нельзя считать core hardened, пока не закрыты или формально приняты как controlled risk:

- P0 budget-state representability;
- physical/application outcome separation;
- provider bypass protection;
- ExecutionPlan orphan recovery;
- final request binding;
- searchable Knowledge publication;
- strict audit transaction semantics;
- production-mode unification;
- critical-index release validation;
- FinOps commitment semantics поверх V60.

## Gate B — Enterprise Identity

Перед массовым enterprise rollout:

- OIDC;
- SCIM;
- service accounts;
- group/department model;
- fine-grained permissions;
- session revocation;
- security audit/events;
- identity lifecycle tests.

## Gate C — Dynamic Multi-provider

Перед включением adaptive routing/fallback:

- versioned provider registry;
- credential references/BYOK;
- immutable target identity;
- residency rules;
- health signal model;
- pricing v2;
- aggregate multi-attempt budget reservation для bounded-cost routes либо сохранение V59 single-attempt restriction;
- retry/fallback safety tests.

## Gate D — Agent Runtime

До autonomous side effects обязательны:

- Tool Registry;
- Secure Tool Gateway;
- risk classes;
- exact-payload approvals;
- durable ToolExecution;
- ambiguous side-effect reconciliation;
- cost/step limits;
- pause/stop;
- sandboxing для code execution;
- security evaluations.

---

# 68. KPI / Engineering Health

Не использовать количество моделей, tools или agents как основной показатель зрелости.

## Reliability

- ambiguous attempt rate;
- orphan/recovery count;
- successful recovery percentage;
- audit outbox lag;
- connector/ingestion lag;
- failed generation publication;
- reconciliation queue age;
- SLO/error-budget consumption.

## Security

- rejected cross-tenant attempts;
- stale-session rejection;
- DLP block/warn rate;
- secret detections;
- tool approval bypass attempts;
- connector ACL drift;
- unresolved high-severity SecurityEvents.

## Governance

- route-denial reasons;
- policy simulation drift vs production decision;
- unpriced/unknown-cost share;
- noncompliant residency requests prevented;
- model/use-case approval coverage.

## FinOps

Показывать отдельно:

```text
known actual
reserved commitment
unknown liability
estimated/projected
invoice-adjusted
```

Основные показатели:

- known vs unknown spend share;
- budget overrun prevention;
- forecast accuracy;
- cost per assistant/project/department;
- retry/fallback cost amplification;
- unpriced provider product share.

## Knowledge

- source freshness;
- Recall@K / MRR / nDCG;
- no-hit rate;
- citation coverage;
- unsupported-claim rate;
- ingestion/publication delay;
- stale ACL/source count.

---

# 69. Non-goals 2027

Чтобы roadmap оставался выполнимым, следующие направления **не являются обязательными целями** 2027:

- обучение собственной foundation model с нуля;
- собственная hyperscale vector database;
- собственный universal workflow engine уровня Temporal «с нуля»;
- Kubernetes только ради enterprise-имиджа;
- микросервисный split без доказанной scaling/ownership причины;
- custom cryptography;
- обещание compliance certification без реального внешнего audit/certification process;
- marketing SLA без измеренных SLI/SLO и drills;
- autonomous agent с неограниченными permissions/budget/network access;
- semantic cache по умолчанию для confidential enterprise traffic;
- автоматическое «исправление» ambiguous external side effect повторным вызовом.

---

# 70. Month-by-month execution sequence

Квартальный roadmap остаётся главным. Этот раздел даёт более практичную последовательность и не является жестким контрактом по датам.

```text
OCT 2026
- V59/V60/V61 verification и migration upgrade matrix
- P0 budget-state design
- physical/application outcome design
- audit MANDATORY API design

NOV 2026
- Core P0/P1 fixes
- ExecutionPlan recovery
- final request binding
- searchable Knowledge publication

DEC 2026
- RC hardening / restore drills
- V60 commitment integration
- provider registry foundation
- streaming foundation

JAN 2027
- dynamic provider registry/adapter registry
- pricing v2
- provider-aware token/context estimation

FEB 2027
- routing + health + circuit breaker
- safe fallback/budget envelope
- structured output

MAR 2027
- streaming/cancel production path
- RAG eval gates
- reranker/query improvements

APR 2027
- groups/departments
- service accounts
- OIDC

MAY 2027
- SCIM/JIT/group sync
- DLP classification/detectors
- retention/legal hold

JUN 2027
- General Policy Engine
- FinOps durable ledger/reconciliation
- Prompt/Config Registry

JUL 2027
- enterprise connectors + ACL mirroring
- Assistants
- Enterprise API/webhooks

AUG 2027
- Tool Registry
- Secure Tool Gateway
- MCP gateway

SEP 2027
- approvals
- durable ToolExecution
- reconciliation workflows

OCT 2027
- sandboxed code interpreter
- artifact workspace
- batch/scheduled jobs

NOV 2027
- durable Agent Runtime
- checkpoint/timers/memory policy

DEC 2027
- agent hardening
- A2A/multi-agent optional rollout
- Security Center / DR / 2028 readiness review
```

---

# 71. Testing strategy extensions

Текущие unit/integration/Testcontainers suites остаются базой. Для production 1.0+ добавить следующие классы tests.

## Property-based testing

Особенно для:

- budget state transitions;
- pricing calculations;
- policy evaluation;
- cursor codecs;
- state machines;
- idempotency/replay identity;
- tool/agent limits.

## Fault injection / crash matrix

Автоматически рвать flow в точках:

```text
before reservation commit
after reservation commit
before ExecutionPlan commit
after ExecutionPlan commit / before Attempt
immediately after Attempt STARTED commit
during provider I/O
after provider response / before terminal evidence
after terminal evidence / before Chat finalization
```

Для каждой точки система обязана доказать, что:

- retry разрешён либо запрещён однозначно;
- unknown side effect не становится `FAILED_RETRYABLE` автоматически;
- reservation/cost liability не исчезает;
- stale worker не может finalize operation.

## Migration matrix

```text
fresh V1 → latest
representative V45/V50/V58 → latest
V58 → V59 → V60 → V61
large populated upgrade
historical invalid policy-array validation case
interrupted concurrent-index case
```

## Security regression

Обязательные классы:

- tenant escape / IDOR;
- CSRF/CORS/JWT confusion;
- OIDC/SCIM scope violations;
- SSRF/egress bypass;
- connector ACL leakage;
- indirect prompt injection;
- malicious MCP/tool server;
- approval payload mutation;
- memory poisoning;
- agent runaway loop.

## Load/capacity

Отдельные profiles:

- auth burst;
- chat/stream burst;
- long-lived SSE;
- high-RAG QPS;
- connector sync;
- ingestion workers;
- usage/FinOps reports;
- agents/tools.

---

# 72. Frontend operating consoles

Frontend должен визуализировать governance/evidence, а не скрывать его за «магией AI».

## Model Governance Console

- effective/latest catalog;
- organization policy;
- policy preview;
- provider/deployment eligibility;
- lifecycle/deprecation;
- pricing/residency/retention/training status;
- route simulator.

## Execution Inspector

Для exact `ChatTurn` показывать связанный граф:

```text
ChatTurn
→ ModelRouteDecision
→ ExecutionPlan
→ Attempt(s)
→ physical/application outcomes
→ usage/pricing/commitment
→ RetrievalRun
→ AnswerPassport
→ Audit
```

Raw secrets/prompt bodies по умолчанию не раскрывать.

## Reconciliation Center

Единая очередь:

- V59 physical reconciliation view;
- unknown cost;
- missing physical evidence;
- connector partial sync;
- storage cleanup review;
- tool/agent ambiguous side effect;
- invoice mismatch.

Operator actions должны быть auditable и не давать unsafe retry.

## Knowledge Operations

- source freshness;
- latest uploaded version;
- published searchable version;
- generation state;
- connector cursor;
- ACL sync status;
- OCR/partial state;
- reindex/publication health.

## FinOps Dashboard

Показывать раздельно V60-style:

```text
actual
reserved
unknown
```

и позже:

```text
settled/invoice adjusted
forecast
```

## Security / Agent Operations

- sessions;
- service accounts;
- DLP/security events;
- approvals;
- active AgentRuns;
- step timeline;
- tool calls;
- pause/cancel;
- cost/budget;
- ambiguous state.

---

# 73. API & Integration Platform additions

## Versioned public API

После stabilization внутренних contracts установить explicit compatibility policy:

- `/api/v1/...` или эквивалентная versioning strategy;
- stable error envelope;
- idempotency semantics;
- async operation resource;
- deprecation window;
- OpenAPI contract tests.

## Webhooks

Tenant-scoped signed webhooks для:

- security events;
- budget threshold;
- connector/ingestion completion;
- approval request;
- agent completion/failure;
- reconciliation incident.

Требования:

- signature;
- timestamp/replay protection;
- secret rotation;
- retry/backoff;
- dead letter;
- tenant isolation;
- bounded payload.

## SDKs

После стабилизации API:

- TypeScript;
- Java;
- generated clients там, где OpenAPI достаточно стабилен.

---

# 74. Architecture evolution rules

## Modular monolith остаётся default

Не разносить transactional invariants по сервисам без реальной необходимости.

Особенно тесно связаны:

- security state;
- reservation/idempotency;
- model route decision;
- execution evidence;
- audit intent;
- Knowledge provenance;
- cost commitment.

## Первые кандидаты на isolated workers

Изоляция оправдана security/resource boundaries:

- PDF/OOXML parsing;
- OCR;
- embeddings/reranker batches;
- connector sync;
- code interpreter;
- agent/tool execution с недоверенным кодом.

Это **не обязательно отдельные базы/микросервисы**.

## Если появится service split

Обязательные cross-service invariants:

- global operation identity;
- tenant scope;
- idempotent inbox/outbox;
- fencing;
- durable STARTED evidence;
- immutable provenance;
- retry safety;
- reconciliation.

---

# 75. Roadmap completion criteria

Roadmap нельзя считать выполненным только по количеству feature flags или UI screens.

К концу 2027 целевой SafeAI Desk должен иметь:

- production-safe core execution;
- согласованную budget/commitment semantics после V60 foundation;
- explicit physical vs application outcomes;
- governed provider boundary;
- recoverable ExecutionPlan lifecycle;
- final request binding;
- dynamic multi-provider runtime;
- enterprise identity/provisioning;
- data classification + DLP;
- governed Knowledge connectors + ACL mirroring;
- durable provider spend/settlement/reconciliation layer;
- structured/streaming/multimodal paths;
- Tools/MCP с approvals;
- durable ToolExecution;
- bounded durable Agent Runtime;
- mature observability/SRE;
- tested backup/restore/DR;
- release/security/evaluation gates;
- отсутствие production path, который может выполнить external AI/tool side effect без durable evidence и policy scope.

Финальное архитектурное правило:

> **Недетерминированной может быть модель. Недоказуемой не должна быть инфраструктура вокруг неё.**

---

**SafeAI Desk Master Roadmap — актуализирован относительно backend baseline `V1–V61` по состоянию на 04 октября 2026 года.**
