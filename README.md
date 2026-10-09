# SafeAI Desk

**SafeAI Desk** — production-oriented корпоративная AI-платформа / AI Gateway для безопасного и управляемого использования внешних AI-моделей внутри организаций.

> **Актуальность:** 09 октября 2026  
> **Backend baseline:** ранее просмотренные модули `common`, `auth`, `user`, `organization`, `ratelimit`, `audit`,
> `usage`, `chat`, `model`, `ai`, `knowledge` + актуализированная Flyway-эволюция схемы `V1–V70`.
> В редакции учтены подтверждённые описания `V59–V68`, а также назначения `V69–V70` по именам применённых миграций и результатам тестового запуска. Полный повторный аудит исходников всех модулей не проводился.  
> **Статус:** production-oriented **pre-1.0**. Проект уже содержит серьёзные security/durability/governance/evidence
> механизмы и database backstops, но README не заявляет compliance certification, invoice-grade settlement,
> формально доказанную HA/SLA или полностью динамический multi-provider runtime.

---

## Содержание

1. [Что решает проект](#что-решает-проект)
2. [Текущее состояние](#текущее-состояние)
3. [Архитектура](#архитектура)
4. [Технологический стек](#технологический-стек)
5. [Структура backend](#структура-backend)
6. [Роли и границы доступа](#роли-и-границы-доступа)
7. [Multi-tenancy и database integrity](#multi-tenancy-и-database-integrity)
8. [Security architecture](#security-architecture)
9. [Конфигурация](#конфигурация)
10. [Модуль `common`](#модуль-common)
11. [Модуль `auth`](#модуль-auth)
12. [Модуль `user`](#модуль-user)
13. [Модуль `organization`](#модуль-organization)
14. [Модуль `ratelimit`](#модуль-ratelimit)
15. [Модуль `audit`](#модуль-audit)
16. [Модуль `usage`](#модуль-usage)
17. [Модуль `chat`](#модуль-chat)
18. [Модуль `model`](#модуль-model)
19. [Модуль `ai`](#модуль-ai)
20. [Модуль `knowledge`](#модуль-knowledge)
21. [Ключевые backend contracts](#ключевые-backend-contracts)
22. [Database / Flyway V1–V70](#database--flyway-v1v70)
23. [Observability и operational jobs](#observability-и-operational-jobs)
24. [Тестирование](#тестирование)
25. [Local development](#local-development)
26. [Production deployment notes](#production-deployment-notes)
27. [Текущие ограничения и production-readiness gaps](#текущие-ограничения-и-production-readiness-gaps)
28. [Позиционирование проекта](#позиционирование-проекта)
29. [Frontend](#frontend)

---

# Что решает проект

Корпоративная работа с AI требует отвечать не только на вопрос «что ответила модель».

SafeAI Desk проектируется так, чтобы после выполнения запроса можно было установить:

- кто инициировал запрос;
- из какой организации пришёл запрос;
- какая системная роль и security state были у пользователя;
- был ли user и tenant активен;
- не устарела ли access-сессия после security mutation;
- какой `clientRequestId` использовался для idempotency;
- какой `ChatTurn` представлял логическую операцию;
- какая версия Model Catalog была effective;
- какая organization model policy была применена;
- почему route был `ALLOWED` или `DENIED`;
- какой input-accounting contract использовался;
- какой объём input/output был зарезервирован до внешнего I/O;
- какие Knowledge Bases и document versions участвовали в retrieval;
- какие chunks попали в RAG context;
- какой immutable Answer Passport остался после ответа;
- был ли физический provider attempt фактически начат;
- какой provider/config/deployment/model target был разрешён;
- можно ли безопасно повторить физический вызов;
- известен ли provider outcome или он `AMBIGUOUS`;
- какие usage/pricing данные известны и какого они качества;
- кто изменил governance/security state;
- что произошло при crash между commit, provider I/O и finalization.

Высокоуровнево проект объединяет:

```text
Identity
+ Tenant isolation
+ Session security
+ Durable chat operations
+ Model governance
+ Physical AI execution evidence
+ Knowledge / Retrieval provenance
+ Rate limits / quotas
+ Usage / pricing quality
+ Audit trail
+ PostgreSQL-enforced integrity
```

---

# Текущее состояние

Ниже описан подтверждённый ранее backend baseline, дополненный состоянием миграций и тестов на 09.10.2026.

## Identity / Security

- organization-based multi-tenancy;
- `SUPER_ADMIN / ADMIN / USER`;
- exactly-one-role policy;
- HttpOnly access/refresh cookies;
- CSRF для browser flow;
- строгая JWT validation;
- RSA key ring / active key id;
- `users.token_version`;
- `organizations.auth_version`;
- refresh-token rotation;
- refresh reuse detection;
- refresh family termination;
- tenant-safe user/organization management;
- trusted-proxy-aware client IP resolution;
- server-generated request correlation ID;
- fail-closed security-state lookup;
- BCrypt UTF-8 boundary hardening;
- production startup invariant validators.

## Durable chat / AI execution

- durable `ChatTurn`;
- idempotency + semantic replay identity;
- short reservation transaction;
- lease + fencing;
- `processingToken`;
- `providerOperationId`;
- DB-enforced `NEW → PROCESSING → terminal` state machine;
- terminal `ChatTurn` immutability;
- route-bound quota reservation provenance;
- cost-verifiable reservation flag;
- route-decision binding;
- `provider_call_started_at` is set at the durable physical `STARTED` boundary;
- crash recovery;
- `AMBIGUOUS` protection;
- no blind retry после неопределённого external I/O;
- V50+ durable `ModelExecutionPlan`;
- durable physical `ModelExecutionAttempt`;
- final prepared-request receipt before physical I/O;
- v2-only DB execution binding fence;
- outcome certainty;
- `PROTOCOL_REJECTED` as physically executed but application-rejected response evidence;
- retry/fallback safety;
- immutable physical target identity;
- provider usage/pricing evidence;
- completed Chat message evidence immutability;
- physical spend read model per `ChatTurn`.

## Model governance

- append-only versioned Model Catalog;
- latest/effective distinction;
- scheduled `effectiveFrom`;
- organization model policies;
- allow/deny/default model rules;
- canonical/unique model-key arrays;
- unique required-capability arrays;
- max input/output envelope;
- max request cost preflight;
- monthly budget preflight;
- route-time `actual / reserved / unknown` commitment evidence;
- explicit monthly cost state;
- pricing/data requirements;
- explicit `pricing_scheme` provenance;
- retention/training requirements;
- immutable route decisions;
- integrity digest;
- exact route decision ↔ `ChatTurn` binding;
- sealed pre-RAG request hash;
- sealed final prepared-request hash;
- exact RAG retrieval/context provenance for Knowledge requests;
- input-accounting provenance;
- fail-closed unconfigured-policy semantics;
- fail-closed capability gate для ещё не реализованного specialized data plane.

## Knowledge / RAG

- tenant/ACL-aware Knowledge Bases;
- organization/member visibility;
- immutable document versions;
- separate latest-uploaded and searchable/published version pointers;
- atomic searchable-pointer advancement only after fenced `READY` publication;
- durable ingestion jobs;
- S3-compatible object storage;
- upload intent journal;
- separate `needs_review_at` and actual `reviewed_at` evidence;
- fenced orphan cleanup/reconciliation;
- bounded extraction;
- PDF/DOCX/PPTX/XLSX/HTML/Markdown/JSON/XML/CSV/plain text extractors;
- optional OCR boundary;
- embeddings;
- pgvector + lexical retrieval;
- Russian lexical profile;
- Reciprocal Rank Fusion;
- deterministic chunking;
- RAG context assembly;
- `GENERAL / KNOWLEDGE_ASSISTED / KNOWLEDGE_ONLY`;
- immutable RAG context receipt bound to exact retrieval run and prepared AI request;
- inline citations;
- citation validation;
- retrieval provenance;
- Answer Passport;
- retrieval evaluation;
- explicit index-generation lifecycle;
- hardened generation chronology;
- fenced generation publication/GC.

## Audit / Usage / Rate limits

- transactional audit outbox;
- canonical immutable actor snapshots;
- immutable target organization snapshots;
- DB-validated audit event types;
- object-only JSON audit details;
- `audit_events` immutable after INSERT while retention DELETE remains explicit;
- immutable audit-outbox intent with mutable delivery bookkeeping only;
- monotonic retry attempt count;
- terminal dead-letter semantics;
- retry/dead-letter;
- cursor pagination;
- retention jobs;
- Redis fixed-window rate limits;
- HMAC-derived Redis keys;
- fail-closed infrastructure semantics;
- live + rollup usage analytics;
- pricing/usage quality states;
- UTC aggregation;
- provider-evidenced monthly commitment ledger;
- per-ChatTurn known physical spend view;
- rollup reconciliation.

---

# Архитектура

Проект построен как **модульный монолит**.

Это сознательный выбор: security, tenant, durability, model-governance и provenance invariants выгодно держать в одной PostgreSQL transactional boundary, не добавляя преждевременную распределённую согласованность.

```text
┌──────────────────────────────┐
│ React + TypeScript frontend  │
└──────────────┬───────────────┘
               │ HTTPS / JSON
               │ HttpOnly cookies + CSRF
               ▼
┌──────────────────────────────┐
│ Edge / Nginx                 │
└──────────────┬───────────────┘
               ▼
┌───────────────────────────────────────────────────────────────┐
│ Spring Boot backend                                           │
│                                                               │
│ common / auth / user / organization / ratelimit / audit       │
│ usage / chat / model / ai / knowledge                         │
└───────────┬──────────────────┬──────────────────┬──────────────┘
            │                  │                  │
            ▼                  ▼                  ▼
     ┌─────────────┐     ┌─────────────┐   ┌──────────────┐
     │ PostgreSQL  │     │ Redis       │   │ S3-compatible│
     │ + Flyway    │     │ rate limit  │   │ object store │
     │ source of   │     │ coordination│   │ Knowledge    │
     │ truth       │     │ cache       │   │ documents    │
     └──────┬──────┘     └─────────────┘   └──────────────┘
            │
            │ provider I/O outside long DB transaction
            ▼
     ┌──────────────────────────────┐
     │ Mock / OpenAI / Anthropic    │
     └──────────────────────────────┘
```

## Control plane vs execution plane

SafeAI Desk разделяет два принципиально разных слоя.

```text
Model Control Plane
    │
    ├─ Model Catalog
    ├─ Organization Policy
    ├─ budget / pricing / retention checks
    └─ immutable ModelRouteDecision
            │
            ▼
AI Physical Execution Plane
    │
    ├─ ModelExecutionPlan
    ├─ ModelExecutionAttempt #1..N
    ├─ exact provider/config/deployment/model target
    ├─ outcome certainty
    ├─ retry/fallback safety
    └─ usage/pricing evidence
```

`ModelRouteDecision` отвечает на вопрос **что governance разрешил**.

`ModelExecutionAttempt` отвечает на вопрос **что физически пытались выполнить и что удалось доказать о результате**.

---

# Технологический стек

## Backend

- Java 21 baseline;
- Spring Boot;
- Spring Web MVC;
- Spring Security;
- Spring Data JPA / Hibernate;
- Spring JDBC;
- Bean Validation;
- PostgreSQL 16 family;
- Flyway;
- Redis;
- Maven Wrapper;
- Lombok;
- JUnit Jupiter 6 (текущие тестовые логи);
- Mockito / Spring Test;
- Testcontainers;
- AWS SDK v2 / S3-compatible storage;
- pgvector;
- Apache PDFBox;
- Apache POI;
- jsoup;
- document-format-specific extractors;
- Micrometer / Prometheus;
- structured logging;
- Docker / Docker Compose.

Точные dependency versions определяет `backend/pom.xml`; README не дублирует их как источник истины.

## Persistence philosophy

- `spring.jpa.hibernate.ddl-auto=validate`;
- application не создаёт production schema через Hibernate;
- schema evolution — только Flyway;
- критические tenant/security/durability invariants, где практично, подкреплены PostgreSQL FK/UNIQUE/CHECK/trigger;
- applied migrations не редактируются задним числом;
- local seed отделён от production migrations.

---

# Структура backend

```text
backend/
├── src/main/java/ru/safeai/gateway/
│   ├── ai/
│   ├── audit/
│   ├── auth/
│   ├── chat/
│   ├── common/
│   ├── knowledge/
│   ├── model/
│   ├── organization/
│   ├── ratelimit/
│   ├── usage/
│   ├── user/
│   └── SafeaiBackendApplication.java
│
├── src/main/resources/
│   ├── db/
│   │   ├── migration/         # V1 ... V70
│   │   └── local-migration/   # local-only seed
│   │
│   ├── application.yml
│   ├── application-local.yml
│   ├── application-local-nginx.yml
│   ├── application-prod.yml
│   ├── application-prod-knowledge-reconciliation.example.yml
│   └── logback-spring.xml
│
└── src/test/
    ├── java/
    └── resources/
    
    
backend/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── ru/
│   │   │       └── safeai/
│   │   │           └── gateway/
│   │   │               ├── ai/
│   │   │               ├── audit/
│   │   │               ├── auth/
│   │   │               ├── chat/
│   │   │               ├── common/
│   │   │               ├── knowledge/
│   │   │               ├── model/
│   │   │               ├── organization/
│   │   │               ├── ratelimit/
│   │   │               ├── usage/
│   │   │               ├── user/
│   │   │               └── SafeaiBackendApplication
│   │   │
│   │   └── resources/
│   │       ├── db/
│   │       │   ├── local-migration/
│   │       │   │   └── R__seed_local_demo_data.sql
│   │       │   │
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
│   │       │       ├── V38__knowledge_base_core.sql
│   │       │       ├── V39__knowledge_documents_and_versions.sql
│   │       │       ├── V40__knowledge_ingestion_job_version_identity.sql
│   │       │       ├── V41__durable_knowledge_ingestion_and_hybrid_retrieval.sql
│   │       │       ├── V42__rag_answers_and_answer_passports.sql
│   │       │       ├── V43__archive_chat_sessions.sql
│   │       │       ├── V44__knowledge_production_integrity.sql
│   │       │       ├── V45__model_control_plane.sql
│   │       │       ├── V46__model_control_plane_hardening.sql
│   │       │       ├── V47__knowledge_chunk_generation_uniqueness.sql
│   │       │       ├── V48__model_input_accounting_provenance.sql
│   │       │       ├── V49__model_control_plane_strict_routing_and_generic_pricing.sql
│   │       │       ├── V50__ai_execution_attempt_usage_evidence.sql
│   │       │       ├── V51__ai_execution_attempt_integrity_hardening.sql
│   │       │       ├── V52__model_strict_execution_and_modality_integrity.sql
│   │       │       ├── V53__physical_attempt_transition_and_pricing_integrity.sql
│   │       │       ├── V54__provider_identity_and_reconciliation_safety.sql
│   │       │       ├── V55__knowledge_russian_lexical_profile.sql
│   │       │       ├── V56__knowledge_storage_upload_intents.sql
│   │       │       ├── V57__knowledge_index_generations.sql
│   │       │       ├── V58__chat_title_lifecycle.sql
│   │       │       ├── V59__governance_integrity_hardening.sql
│   │       │       ├── V60__monthly_cost_commitment_ledger.sql
│   │       │       ├── V61__model_policy_array_uniqueness.sql
│   │       │       ├── V62__model_control_plane_hardening.sql
│   │       │       ├── V63__knowledge_publication_and_retention_hardening.sql
│   │       │       ├── V64__model_control_plane_completion.sql
│   │       │       ├── V65__model_execution_binding_enforcement.sql
│   │       │       ├── V66__physical_response_and_pricing_scheme.sql
│   │       │       ├── V67__chat_execution_quota_and_evidence_hardening.sql
│   │       │       ├── V68__audit_integrity_hardening.sql
│   │       │       ├── V69__audit_actor_link_fk_detachment.sql
│   │       │       ├── V70__audit_live_user_link_separation.sql
│   │       │       └── README_DB.md
│   │       │
│   │       ├── META-INF/
│   │       ├── application.yml
│   │       ├── application-local.yml
│   │       ├── application-local-nginx.yml
│   │       ├── application-prod.yml
│   │       ├── application-prod-knowledge-reconciliation.example.yml
│   │       └── logback-spring.xml
│   │
│   └── test/
│       └── java/
```

Основные зависимости модулей:

```text
common
 ├─ auth
 ├─ user
 ├─ organization
 ├─ ratelimit
 ├─ audit
 ├─ usage
 ├─ chat
 ├─ model
 ├─ ai
 └─ knowledge

auth ──────────────► user / organization security state
chat ──────────────► model route decision
chat ──────────────► knowledge RAG preparation
model ─────────────► ai execution binding
ai ────────────────► provider
chat finalization ─► usage metadata / audit
knowledge ─────────► Answer Passport + route evidence
```

---

# Роли и границы доступа

У пользователя должна быть ровно одна системная роль:

```text
SUPER_ADMIN
ADMIN
USER
```

## SUPER_ADMIN

Platform/control-plane administrator:

- управляет tenant organizations;
- создаёт/управляет обычными `ADMIN` и `USER`;
- читает global audit;
- читает global usage;
- управляет глобальным Model Catalog;
- импортирует runtime model в catalog;
- создаёт immutable catalog versions;
- читает и изменяет organization model policies;
- читает route evidence разных tenants;
- выполняет administrative runtime probe.

Ordinary user-management flow не должен использоваться для создания нового `SUPER_ADMIN`.

## ADMIN

Tenant administrator:

- работает с Chat;
- управляет `USER` своей организации;
- работает с Knowledge Bases;
- управляет KB memberships;
- читает tenant audit;
- читает tenant usage;
- читает runtime model status;
- читает global/effective Model Catalog;
- создаёт новые версии model policy своей организации;
- читает route evidence своей организации.

ADMIN не должен:

- управлять чужим tenant;
- мутировать глобальный catalog;
- управлять чужой policy;
- читать чужой route evidence;
- превращать пользователя в `SUPER_ADMIN`.

## USER

- работает со своими chats;
- использует доступные Knowledge Bases;
- получает AI responses в пределах policy организации;
- не имеет administrative routes.

## Model Control Plane RBAC

| Возможность | USER | ADMIN | SUPER_ADMIN |
|---|---:|---:|---:|
| Runtime status | — | read | read |
| Global catalog | — | read | read/write |
| Import runtime → catalog | — | — | write |
| Own organization policy | — | read/write | read/write |
| Other organization policy | — | — | read/write |
| Own route evidence | — | read | read |
| Other tenant route evidence | — | — | read |

Frontend guards являются UX-слоем. Security boundary находится на backend.

---

# Multi-tenancy и database integrity

Tenant key:

```text
organization_id
```

Scope model:

```text
SUPER_ADMIN -> platform/global scope
ADMIN       -> organization scope
USER        -> own-resource / ACL scope
```

Tenant isolation применяется к:

- users;
- organizations;
- chat sessions;
- chat messages;
- chat turns;
- quota reservations;
- model policies;
- model-route decisions;
- execution plans/attempts через route provenance;
- Knowledge Bases;
- Knowledge memberships;
- documents/versions/chunks;
- retrieval runs/hits;
- Answer Passports;
- audit;
- usage;
- rate-limit keys;
- security-state lookup.

Типовые composite integrity relations:

```text
chat_session(user_id, organization_id)
    -> user(id, organization_id)

chat_message(session_id, organization_id)
    -> chat_session(id, organization_id)

chat_turn(session_id, organization_id, user_id)
    -> chat_session(id, organization_id, user_id)

model_route_decision
    -> exact catalog snapshot
    -> exact organization policy snapshot
    -> exact chat/user/organization context

chat_turn.model_route_decision_id
    -> exact ALLOWED route decision

answer_passport.model_route_decision_id
    -> exact route evidence
```

Цель — не полагаться только на service-layer checks и не допустить cross-tenant/provenance mismatch даже при application bug или race.

---

# Security architecture

## Browser authentication

```text
access token  -> HttpOnly cookie
refresh token -> HttpOnly cookie
XSRF-TOKEN    -> readable cookie
```

JWT не должен храниться в `localStorage`.

Unsafe browser methods передают CSRF header:

```text
X-XSRF-TOKEN
```

После login CSRF state ротируется.

## JWT contract

Access token содержит/валидирует identity и security epoch data:

- subject;
- email/identity;
- user ID;
- organization ID;
- role;
- user `tokenVersion`;
- organization `authVersion`;
- token type;
- `jti`;
- `iat`;
- `exp`;
- issuer;
- audience;
- signing key identity.

JWT validity не ограничивается проверкой криптографической подписи: runtime также проверяет текущий user/org security state.

## Security epochs

```text
users.token_version
```

изменяется при user-specific security mutation.

```text
organizations.auth_version
```

— organization-level epoch.

Это позволяет инвалидировать tenant access state без обязательного массового переписывания всех JWT.

## Refresh token rotation / reuse detection

Refresh token хранится как hash.

```text
cookie
  ↓
hash
  ↓
locked token row
  ↓
validate token + family + epochs
  ↓
revoke predecessor
  ↓
create successor
```

Reuse path:

```text
reused predecessor
  ↓
terminate refresh family
  ↓
security audit
  ↓
clear cookies
  ↓
401
```

DB-инварианты защищают replacement chain/family consistency.

## Passwords

BCrypt ограничение учитывается как **72 UTF-8 bytes**, а не «72 символа».

Baseline включает:

- `DelegatingPasswordEncoder`;
- `{bcrypt}`;
- BCrypt strength 12;
- migration compatibility для legacy BCrypt;
- current create/reset password policy;
- UTF-8 byte-length validation;
- запрет control characters.

## Request ID

Backend генерирует собственный canonical UUID request ID.

Incoming `X-Request-Id` не считается автоматически доверенным server correlation ID.

Server request ID используется в:

- request attributes;
- MDC;
- response header;
- API error envelope.

## Trusted proxy / client IP

Forwarded headers учитываются только если direct peer принадлежит configured trusted proxy CIDR.

Security model:

```text
empty trustedProxyCidrs = trust nobody
```

Production не должен использовать trust-all CIDR.

## CORS

Production policy — explicit HTTPS allowlist.

Не допускаются wildcard origins и неоднозначные URL forms с userinfo/query/fragment/path.

## Public error contract

MVC:

```text
Exception
  ↓
GlobalExceptionHandler
  ↓
ApiErrorResponseFactory
  ↓
ApiErrorResponse
```

Spring Security:

```text
authentication / authorization failure
  ↓
RestAuthenticationEntryPoint / RestAccessDeniedHandler
  ↓
ApiErrorResponseWriter
  ↓
ApiErrorResponse
```

Public response не должен содержать:

- stack traces;
- raw provider body;
- credentials;
- cookies;
- raw JWT;
- secret configuration.

---
# Конфигурация

Ниже перечислены ключевые configuration groups, подтверждённые в просмотренном backend. Конкретный production secret/value задаётся deployment environment, а не README.

## Core

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false

  flyway:
    enabled: true
    locations:
      - classpath:db/migration
    validate-migration-naming: true
    clean-disabled: true
    postgresql:
      transactional-lock: false

server:
  port: 8080
  forward-headers-strategy: none
```

`transactional-lock=false` важен для migrations с PostgreSQL concurrent index operations.

## JWT / platform

```text
app.security.jwt.*
safeai.platform.organization-id
```

Canonical platform organization baseline:

```text
00000000-0000-0000-0000-000000000001
```

## Auth cookies

Prefix:

```text
safeai.auth.cookies.*
```

Ключевые параметры:

- `secure`;
- `same-site`;
- `access-token-max-age`;
- `refresh-token-max-age`;
- `refresh-token-absolute-max-age`;
- `reuse-detection-retention`;
- `domain`;
- access cookie name;
- refresh cookie name.

Refresh cleanup:

```text
safeai.auth.refresh-cleanup.*
```

- cron;
- batch size;
- max batches per run.

## User status cache

```text
safeai.security.user-status-cache.*
```

- enabled;
- TTL;
- key prefix.

Security correctness не должна зависеть только от cached value; invalidation/fallback semantics должны оставаться fail-safe.

## Rate limiting

```text
safeai.rate-limit.redis.*
safeai.rate-limit.login.*
safeai.rate-limit.refresh.*
safeai.rate-limit.ai-messages.*
```

Ключевые настройки:

- HMAC secret;
- key version;
- login email/IP limits;
- refresh IP limit;
- user/admin/org AI-message limits;
- fixed windows.

## Audit

```text
safeai.audit.outbox.*
safeai.audit.retention.*
```

Baseline включает:

- outbox batch size;
- poll delay;
- retention enable;
- retention period;
- batch size;
- max batches/run;
- cron;
- timezone.

## Usage

```text
safeai.usage.*
safeai.usage.rollup.*
```

Baseline properties:

- default range;
- max range;
- max live range;
- max page size;
- query timeout;
- slow-query threshold;
- JDBC fetch size;
- max concurrent reports;
- rollup cron;
- lookback;
- backfill per run;
- advisory lock key;
- statement timeout.

## Chat

```text
safeai.chat.*
safeai.chat.lock.*
safeai.chat.quota.*
safeai.chat.recovery.*
```

Ключевые настройки:

- list/details limits;
- message length;
- processing lease;
- lease-renewal pool;
- distributed chat-lock TTL;
- max active locks/watchdogs;
- quota reservation input/output/cost;
- quota period zone;
- recovery scheduler batch limits/cron.

## AI

```text
safeai.ai.provider
safeai.ai.context.*
safeai.ai.retry.*
safeai.ai.openai.*
safeai.ai.anthropic.*
```

Context configuration контролирует:

- max user message chars;
- instruction/history limits;
- total input chars;
- model context safety margin.

Retry configuration контролирует:

- enabled;
- max attempts;
- initial/max backoff;
- max `Retry-After`;
- total timeout.

OpenAI/Anthropic configuration включает:

- API key;
- exact base URL;
- model;
- input/output limits;
- response chars/body bytes;
- connect/read timeout;
- provider-specific flags/version.

Production provider URLs проходят strict HTTPS/host validation.

## Knowledge

Main prefixes:

```text
safeai.knowledge.ingestion.*
safeai.knowledge.retrieval.*
safeai.knowledge.rag.*
safeai.knowledge.embedding.*
safeai.knowledge.ocr.*
```

### Ingestion baseline

- enabled;
- poll delay;
- batch size;
- processing lease;
- extraction timeout;
- extraction worker count;
- max attempts;
- retry backoff;
- max extracted chars;
- max OOXML uncompressed size;
- chunk size;
- chunk overlap.

### Retrieval baseline

- max query chars;
- max `topK`;
- candidate limit;
- RRF `k`.

### RAG baseline

- `topK`;
- max context chars;
- max chunk chars.

### Embeddings

Providers:

```text
hashing
openai
```

Baseline embedding dimension фиксирован текущей реализацией; OpenAI production configuration pins allowed endpoint вместо произвольного outbound host.

### OCR

OCR provider configuration включает:

- provider;
- endpoint;
- API key;
- explicit allowed hosts;
- minimum native chars/page threshold;
- connect/read timeout;
- maximum response size.

Credential-bearing property records редактируют `toString()` так, чтобы API keys не утекали в logs/diagnostics.

---

# Модуль `common`

Package:

```text
ru.safeai.gateway.common
```

`common` — cross-cutting infrastructure layer. В нём не должна жить business-логика конкретного tenant domain.

## Структура

```text
common/
├── config/
│   ├── SchedulingConfiguration
│   └── TimeConfiguration
│
├── exception/
│   ├── ApiErrorCode
│   ├── ApiErrorResponse
│   ├── ApiErrorResponseFactory
│   ├── ApiErrorResponseWriter
│   ├── ApiException
│   ├── ApiExceptionResponseSupport
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
│   ├── RateLimitUnavailableLogger
│   ├── README.md
│   ├── RefreshTokenReuseDetectedException
│   ├── ResourceNotFoundException
│   ├── UserVersionConflictException
│   └── ValidationErrorExtractor
│
├── pagination/
│   └── StablePageableNormalizer
│
├── persistence/
│   ├── DatabaseConstraintClassifier
│   └── PostgresAdvisoryLockExecutor
│
├── platform/
│   ├── PlatformProperties
│   └── PlatformPropertiesConfiguration
│
├── security/
│   ├── AccessTokenSubject
│   ├── BearerAuthenticationEntryPoint
│   ├── ClientIpProperties
│   ├── ClientIpResolver
│   ├── CorsProperties
│   ├── JwtCodecConfiguration
│   ├── JwtProperties
│   ├── JwtRsaKeyRing
│   ├── JwtService
│   ├── ManagementSecurityConfig
│   ├── package-info.java
│   ├── PasswordEncodingConfiguration
│   ├── ProductionEnvironmentCondition
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
│
└── validation/
     └── UnicodeValidationSupport

```

## Основные контракты

### `TimeConfiguration`

Предоставляет injectable UTC `Clock`, чтобы business timestamps и lease/retry/retention tests были воспроизводимыми.

### `StablePageableNormalizer`

Централизует bounded pagination/sort normalization и снижает риск расхождения paging semantics между controllers.

### `DatabaseConstraintClassifier`

Преобразует ожидаемые PostgreSQL constraint failures в domain-level conflict semantics вместо случайного generic 500.

### `PostgresAdvisoryLockExecutor`

Централизует session-level PostgreSQL advisory lock:

- acquire;
- action;
- unlock;
- best-effort abort/close небезопасного pooled connection при unlock failure.

Используется jobs, которым нужна кластерная singleton-coordination без отдельного lock service.

### `SafeAiUserPrincipal`

Security principal хранит минимально необходимую identity/security информацию и не должен содержать password credentials.

### `ProductionSecurityInvariantValidator`

Fail-fast startup boundary для явно небезопасных production settings.

---

# Модуль `auth`

Package:

```text
ru.safeai.gateway.auth
```

## Структура

```text
auth/
├── config/
│   ├── ProductionAuthCookieInvariantValidator
│   └── RefreshTokenCleanupConfiguration
│
├── controller/
│   ├── AuthController
│   └── CsrfController
│
├── dto/
│   ├── CsrfTokenResponse
│   ├── CurrentUserResponse
│   └── LoginRequest
│
├── entity/
│   ├── RefreshTokenEntity
│   └── RefreshTokenRevocationReason
│
├── repository/
│   └── RefreshTokenRepository
│
├── security/
│   ├── AccessCookieAuthenticationFilter
│   ├── CsrfCookieFilter
│   ├── CustomUserDetailsService
│   ├── HstsProperties
│   ├── package-info.java
│   ├── SecurityConfig
│   ├── SpaCsrfTokenRequestHandler
│   └── UserStatusFilter
│
├── service/
│   ├── AuthAuditRequestSupport
│   ├── AuthCookieConfigurationValidator
│   ├── AuthCookieProperties
│   ├── AuthCookieService
│   ├── AuthEventService
│   ├── AuthService
│   ├── LoginSessionResult
│   ├── LoginSessionTransactionService
│   ├── LogoutAuditSubject
│   ├── RefreshReuseAuditService
│   ├── RefreshSessionResult
│   ├── RefreshSessionTransactionService
│   ├── RefreshTokenCleanupBatchService
│   ├── RefreshTokenCleanupJob
│   ├── RefreshTokenCleanupProperties
│   ├── RefreshTokenService
│   └── UserSessionRevocationService
│
└── validation/
    ├── Utf8ByteLength
    └── Utf8ByteLengthValidator
```

## Login flow

```text
POST /api/auth/login
  ↓
login rate limit
  ↓
credentials validation
  ↓
user + organization security checks
  ↓
transactional refresh-session creation
  ↓
access JWT
  ↓
HttpOnly access + refresh cookies
  ↓
CSRF state
  ↓
audit
```

## Access-token validation

`AccessCookieAuthenticationFilter` извлекает token из cookie.

JWT converter/identity validator формирует principal только после проверки contract claims.

`UserStatusFilter` дополнительно проверяет текущие persisted/cache-backed:

- user enabled;
- organization enabled;
- token organization ID;
- user token version;
- organization auth version.

Security-state infrastructure failure должен fail closed.

## Refresh flow

```text
refresh cookie
  ↓
hash
  ↓
SELECT/lock refresh row
  ↓
validate expiry / revocation / family / security epochs
  ↓
rotate token
  ↓
write successor
  ↓
set new cookies
```

Reuse detection прекращает token family.

## Logout

Logout отзывает server-side refresh state и очищает browser cookies; audit subject snapshot должен быть получен до потери необходимого identity context.

## Cleanup

Expired/revoked refresh records удаляются bounded batch job-ом, а не одним неограниченным delete.

---

# Модуль `user`

Package:

```text
ru.safeai.gateway.user
```

## Структура

```text
user/
├── controller/
│   └── UserController
│
├── dto/
│   ├── CreateUserRequest
│   ├── PermanentDeleteUserRequest
│   ├── ResetUserPasswordRequest
│   ├── UpdateUserEnabledRequest
│   ├── UpdateUserRequest
│   ├── UpdateUserRolesRequest
│   ├── UserDetailsResponse
│   ├── UserResponse
│   └── UserStatisticsResponse
│
├── entity/
│   ├── RoleEntity
│   └── UserEntity
│
├── event/
│   └── UserSecurityStateChangedEvent
│
├── mapper/
│   └── UserRoleMapper
│
├── repository/
│   ├── RoleRepository
│   └── UserRepository
│
├── service/
│   ├── UserManagementProperties
│   ├── UserSecurityStatus
│   ├── UserService
│   ├── UserStatusCacheInvalidationListener
│   ├── UserStatusCacheProductionInvariantVerifier
│   ├── UserStatusCacheProperties
│   └── UserStatusCacheService
│
└── validation/
    ├── BcryptUtf8Length
    ├── BcryptUtf8LengthValidator
    ├── PasswordPolicy
    ├── PasswordValidator
    ├── UserEmailNormalizer
    └── ValidPassword
```

## Основные invariants

- user принадлежит одной organization;
- ровно одна системная role;
- role mutation контролируется application policy и БД;
- canonical email сохраняется как storage invariant;
- security-significant mutation увеличивает `token_version`;
- optimistic concurrency использует entity `version`;
- stale update не должен молча перетирать новое состояние.

## User status cache

Cache является optimization layer, а не единственным security source of truth.

Security mutation публикует `UserSecurityStateChangedEvent`, после чего cache инвалидируется listener-ом.

## Permanent deletion

Permanent deletion — restricted flow:

- eligibility;
- retention period;
- confirmation;
- expected version;
- dependency checks;
- audit;
- защита historical records от случайного cascade disappearance.

---

# Модуль `organization`

Package:

```text
ru.safeai.gateway.organization
```

## Структура

```text
organization/
├── audit/
│   └── OrganizationAuditSnapshotProvider
│
├── controller/
│   └── OrganizationController
│
├── dto/
│   ├── CreateOrganizationRequest
│   ├── DisableOrganizationRequest
│   ├── EnableOrganizationRequest
│   ├── OrganizationDirectoryResponse
│   ├── OrganizationDisableImpactResponse
│   ├── OrganizationPageResponse
│   ├── OrganizationResponse
│   ├── OrganizationType
│   ├── UpdateOrganizationEnabledRequest
│   └── UpdateOrganizationRequest
│
├── entity/
│   └── OrganizationEntity
│
├── event/
│   └── OrganizationSecurityStateChangedEvent
│
├── repository/
│   ├── OrganizationImpactQueryRepository
│   └── OrganizationRepository
│
└── service/
    ├── OrganizationAccessPolicy
    ├── OrganizationCommandOperations
    ├── OrganizationNameNormalizer
    ├── OrganizationQueryOperations
    ├── OrganizationResponseMapper
    ├── OrganizationService
    ├── OrganizationStatusCacheInvalidationListener
    └── PlatformOrganizationInvariantVerifier
```

## Platform organization

Canonical platform organization имеет отдельную semantic role и не должна обрабатываться как обычный tenant.

`PlatformOrganizationInvariantVerifier` проверяет startup/runtime assumptions о protected platform organization.

## Organization naming

`OrganizationNameNormalizer` отвечает за canonical/normalized name policy.

DB хранит уникальный normalized organization-name invariant.

## Enable / disable

Disable/enable используют optimistic versioning.

Security-significant organization mutation увеличивает:

```text
organization.auth_version
```

и публикует `OrganizationSecurityStateChangedEvent`.

Это инвалидирует старые auth epochs. Re-enable не должен «оживлять» старые refresh sessions.

---
# Модуль `ratelimit`

Package:

```text
ru.safeai.gateway.ratelimit
```

## Структура

```text
ratelimit/
├── AiMessageRateLimitProperties
├── DualRateLimitResult
├── LoginRateLimitProperties
├── LoginRateLimitService
├── RateLimitDecision
├── RateLimitExceededEvent
├── RateLimitKeyFactory
├── RateLimitMetrics
├── RateLimitProductionConfigurationValidator
├── RateLimitProductionProperties
├── RateLimitRedisKeyProperties
├── RateLimitResult
├── RedisFixedWindowRateLimiter
├── RedisRateLimitService
├── RefreshRateLimitProperties
└── RefreshRateLimitService
```

## Модель

Redis используется как distributed rate-limit state.

Atomic fixed-window operation выполняется Lua script-ом:

```text
INCR
+
TTL initialization
+
limit decision
```

## Keys

Raw email/IP/user identity не обязана попадать в Redis key.

`RateLimitKeyFactory` использует versioned/HMAC-based key derivation.

## Policies

### Login

Комбинирует identity/email и IP boundary.

### Refresh

Имеет отдельный IP-oriented policy.

### AI messages

Контролирует:

- user bucket;
- organization bucket;
- role-dependent limits.

## Failure semantics

Redis outage не должен превращаться в silent allow-all.

Infrastructure unavailable имеет отдельный controlled error path и observability.

`RateLimitExceededEvent` используется для audit/metrics integration.

---

# Модуль `audit`

Package:

```text
ru.safeai.gateway.audit
```

## Структура

```text
audit/
├── config/
│   ├── AuditDetailsProperties
│   ├── AuditOutboxConfiguration
│   ├── AuditOutboxProperties
│   ├── AuditRetentionConfiguration
│   └── AuditRetentionProperties
│
├── controller/
│   ├── AuditController
│   └── AuditDirectoryController
│
├── details/
│   ├── AiResponseAuditDetails
│   ├── AuditDetails
│   ├── ChatTurnAuditDetails
│   ├── RateLimitAuditDetails
│   └── SecurityRefreshReuseAuditDetails
│
├── dto/
│   ├── AuditActorDirectoryResponse
│   ├── AuditEventCursorResponse
│   ├── AuditEventFilter
│   ├── AuditEventPageResponse
│   ├── AuditEventResponse
│   └── AuditTargetOrganizationDirectoryResponse
│
├── entity/
│   ├── AuditEventEntity
│   └── AuditOutboxEntity
│
├── listener/
│   └── RateLimitAuditListener
│
├── model/
│   └── AuditActor
│
├── repository/
│   ├── AuditDirectoryQueryRepository
│   ├── AuditEventCriteria
│   ├── AuditEventCursorRepository
│   ├── AuditEventCursorRepositoryImpl
│   ├── AuditEventRepository
│   └── AuditOutboxRepository
│
├── service/
│   ├── AuditCommand
│   ├── AuditCommandFactory
│   ├── AuditCursorCodec
│   ├── AuditDatabaseClock
│   ├── AuditDetailsSanitizer
│   ├── AuditDirectoryService
│   ├── AuditEventCursorService
│   ├── AuditEventQueryPolicy
│   ├── AuditEventQueryService
│   ├── AuditEventService
│   ├── AuditOutboxFailureService
│   ├── AuditOutboxMetrics
│   ├── AuditOutboxProcessor
│   ├── AuditOutboxScheduler
│   ├── AuditOutboxWriter
│   ├── AuditRetentionBatchService
│   ├── AuditRetentionLockService
│   ├── AuditRetentionService
│   └── BestEffortStandaloneAuditService
│
├── spi/
│   └── AuditTargetOrganizationSnapshotProvider
│
└── AuditEventType
```

## Transactional outbox

Основной flow:

```text
business transaction
  ├─ domain mutation
  └─ audit_outbox
         ↓ commit
background processor
         ↓
audit_events
```

Преимущества:

- audit intent фиксируется рядом с business mutation;
- provider/network side effects не нужны для записи event;
- delivery retry безопасно повторяется;
- `audit_events.id = audit_outbox.id` поддерживает idempotent delivery;
- uncertain DB commit не приводит автоматически к duplicate audit event.

### V68–V70 database integrity

V68 делает разделение intent/evidence явным на уровне PostgreSQL:

- `audit_outbox.event_type` обязан существовать в `audit_event_types`;
- `details` и в outbox, и в final events должен быть JSON object;
- actor email canonicalized как lowercase + trim;
- actor display name canonicalized через trim;
- control characters запрещены в actor/organization snapshot text;
- `audit_events` нельзя `UPDATE` после вставки;
- retention `DELETE` намеренно не блокируется;
- immutable поля `audit_outbox` нельзя менять после enqueue;
- `attempt_count` не может уменьшаться;
- после `dead_lettered_at` delivery row становится terminal;
- dead-letter row не может одновременно оставаться scheduled через `next_attempt_at`.

### V69–V70: actor snapshot и live user link

Последовательные миграции `V69__audit_actor_link_fk_detachment.sql` и
`V70__audit_live_user_link_separation.sql` уточняют границы между **исторической
идентичностью инициатора audit event** и **актуальной ссылкой на пользователя**.
Ключевой принцип: удаление или изменение существования user row не должно
уничтожать уже сохранённую историю аудита. При этом live linkage и правила
tenant isolation должны оставаться проверяемыми. Точные правила удаления,
`ON DELETE` и состав колонок определяются SQL этих миграций, а не данным README.

## Immutable snapshots

Actor snapshot:

```text
actor_user_id
actor_organization_id
actor_email
actor_display_name
```

Target snapshot:

```text
target_organization_id
target_organization_name
```

Snapshot нужен потому, что пользователь/организация могут быть переименованы или удалены позже.

## Delivery

Outbox processor использует:

```text
FOR UPDATE SKIP LOCKED
```

что позволяет нескольким backend instances безопасно обрабатывать очередь.

Failure state включает:

- attempt count;
- next attempt time;
- bounded/sanitized last error;
- dead-letter timestamp.

## Query

ADMIN scope повторно ограничивается tenant policy на service/query layer.

SUPER_ADMIN получает global/platform scope.

Cursor pagination использует стабильную пару:

```text
(createdAt, id)
```

## Retention

Retention выполняется bounded batches.

Для межинстансовой координации используется PostgreSQL advisory lock.

## Важная текущая семантика

`AuditOutboxWriter.writeRequired()` в просмотренном baseline использует `Propagation.REQUIRED`.

Это означает:

- при существующей business transaction audit join-ится в неё;
- без outer transaction Spring создаст отдельную transaction.

Следовательно гарантированная атомарность required audit path сегодня зависит от корректного transactional call-site. Для абсолютного API-инварианта production hardening логично перейти к `MANDATORY` или явно разделить API `recordRequiredInCurrentTransaction` / `recordStandalone`.

---

# Модуль `usage`

Package:

```text
ru.safeai.gateway.usage
```

## Структура

```text
usage/
├── config/
│   ├── UsageConfiguration
│   ├── UsageJdbcClients
│   └── UsageProperties
│
├── controller/
│   └── UsageController
│
├── dto/
│   ├── PagedResponse
│   ├── UsageCostSummary
│   ├── UsageDailySummaryResponse
│   ├── UsageDataQualityResponse
│   ├── UsageDateFilter
│   ├── UsageDateModelFilter
│   ├── UsageModelSummaryResponse
│   ├── UsagePageRequest
│   ├── UsageProblemModelResponse
│   ├── UsageResponseSummary
│   ├── UsageSummaryInvariants
│   ├── UsageSummaryResponse
│   ├── UsageTokenSummary
│   └── UsageUserSummaryResponse
│
├── repository/
│   ├── JdbcUsageQueryRepository
│   ├── UsageFactQueryBuilder
│   ├── UsageFactSql
│   ├── UsageInstantRange
│   ├── UsageQueryCriteria
│   ├── UsageQueryPlan
│   ├── UsageQueryRepository
│   ├── UsageRollupStateRepository
│   ├── UsageRowMapperSupport
│   └── UsageSqlFragments
│
└── service/
    ├── UsageQueryService
    ├── UsageReportExecutor
    ├── UsageRollupDayProcessor
    └── UsageRollupScheduler
```

## Usage quality model

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

Ключевой принцип:

> Unknown pricing не превращается в `$0`.

`FREE` означает подтверждённый нулевой cost.

`UNPRICED` означает, что корректная price identity отсутствует.

`CALCULATION_FAILED` означает, что pricing должен был быть рассчитан, но расчёт завершился ошибкой.

## Live + rollup

- source-of-truth факты читаются из persisted chat/AI completion metadata;
- daily aggregation работает в UTC;
- rollup ускоряет длинные отчёты;
- rollup имеет watermark/reconciliation;
- recent/incomplete range может дочитываться live;
- max live range защищает DB от неограниченного тяжёлого запроса.

## Concurrency / performance

`UsageReportExecutor` ограничивает количество конкурентных тяжёлых reports.

SQL paths имеют:

- query timeout;
- fetch size;
- performance/integration tests;
- EXPLAIN/EXPLAIN ANALYZE coverage в тестовой базе.

## Product usage vs physical spend

Usage subsystem остаётся product/chat analytics, но после `V60/V62/V67` физические расходы больше не обязаны
исчезать из accounting только потому, что `ChatTurn` не завершился пользовательским success.

Отдельные DB read models дают разные представления:

```text
model_cost_commitment_ledger_v62
    → provider-evidenced actual cost
    → active upper-bound reservations
    → unknown liability count
    → retry-safe carry-forward semantics

chat_physical_spend_v67
    → known executed input/output
    → known provider-evidenced cost
    → usage_unverifiable / cost_unverifiable flags
```

Это существенно ближе к FinOps, но всё ещё **не invoice/settlement ledger**: `actual` здесь означает
provider-evidenced cost по сохранённой pricing/usage evidence, а не подтверждение расчёта с поставщиком.

---

# Модуль `chat`

Package:

```text
ru.safeai.gateway.chat
```

## Структура

```text
chat/
├── config/
│   ├── ChatConfiguration
│   ├── ChatLockProperties
│   ├── ChatProperties
│   ├── ChatQuotaProperties
│   └── ChatRecoveryProperties
│
├── controller/
│   ├── ChatController
│   └── ChatTitleController
│
├── dto/
│   ├── ChatCapabilitiesResponse
│   ├── ChatDetailsResponse
│   ├── ChatErrorResponse
│   ├── ChatPageRequest
│   ├── ChatPageResponse
│   ├── ChatResponse
│   ├── ChatTurnStatusResponse
│   ├── CreateChatRequest
│   ├── MessagePageRequest
│   ├── MessageResponse
│   ├── SendMessageRequest
│   ├── SendMessageResponse
│   └── UpdateChatTitleRequest
│
├── entity/
│   ├── ChatMessageEntity
│   ├── ChatMessageRole
│   ├── ChatMessageStatus
│   ├── ChatQuotaReservationState
│   ├── ChatSessionEntity
│   ├── ChatTurnEntity
│   └── ChatTurnState
│
├── exception/
│   ├── AiOutcomeAmbiguousException
│   ├── ChatAccessRevokedException
│   ├── ChatApiException
│   ├── ChatApiExceptionHandler
│   ├── ChatLeaseUnavailableException
│   ├── ChatQuotaExceededException
│   ├── ChatStaleProcessorException
│   ├── ChatTurnFailedException
│   ├── ChatTurnInProgressException
│   └── IdempotencyKeyReusedException
│
├── observability/
│   └── ChatMetrics
│
├── quota/
│   ├── ChatQuotaConsumption
│   ├── ChatQuotaPolicy
│   └── ChatQuotaReservation
│
├── repository/
│   ├── ChatHistoryRepository
│   ├── ChatHistoryTurn
│   ├── ChatMessageRepository
│   ├── ChatQuotaRepository
│   ├── ChatSecurityStateRepository
│   ├── ChatSessionRepository
│   ├── ChatTurnMutexRepository
│   ├── ChatTurnRecoveryRepository
│   ├── ChatTurnRepository
│   └── RecoveredChatTurn
│
└── service/
│   ├── AiHistoryBuilder
│   ├── ChatContentNormalizer
│   ├── ChatDatabaseClockRepository
│   ├── ChatLockService
│   ├── ChatMapper
│   ├── ChatProcessingContext
│   ├── ChatQuotaService
│   ├── ChatSecurityStateService
│   ├── ChatService
│   ├── ChatTurnAuditDetailsFactory
│   ├── ChatTurnExecutionCoordinator
│   ├── ChatTurnFinalizationService
│   ├── ChatTurnLeaseService
│   ├── ChatTurnReadSupport
│   ├── ChatTurnRecoveryCoordinator
│   ├── ChatTurnRecoveryScheduler
│   ├── ChatTurnRecoveryService
│   └── ChatTurnReservationService
│
└── title/
    ├── ChatTitlePolicy
    ├── ChatTitleService
    └── ChatTitleSource
```

## `ChatTurn` state machine

```text
NEW
  ↓
PROCESSING
  ├─→ SUCCEEDED
  ├─→ FAILED
  └─→ AMBIGUOUS
```

`AMBIGUOUS` — отдельное operationally-sensitive состояние, а не alias для `FAILED`.

V67 закрепляет переходы в PostgreSQL trigger-ом:

```text
NEW -> PROCESSING
PROCESSING -> SUCCEEDED | FAILED | AMBIGUOUS
terminal -> no further UPDATE
```

Identity/provenance поля `ChatTurn` также защищены от изменения после создания. Дополнительно message row со
`status=COMPLETED` становится immutable evidence и не может быть переписан ORM/direct SQL.

## Idempotency

Ключ:

```text
clientRequestId
```

Semantics:

```text
same clientRequestId + same semantic request
    → replay

same clientRequestId + different semantic request
    → IDEMPOTENCY_CONFLICT
```

Replay identity не должен зависеть от mutable policy/catalog state, изменившегося уже после принятия оригинальной операции.

## Reservation flow

До external provider I/O сохраняются:

```text
ChatTurn
processingToken
providerOperationId
leaseUntil
quota reservation
ModelRouteDecision
reserved execution envelope
```

Provider call не выполняется внутри долгой business transaction.

## Lease / fencing

`processingToken` является fencing token.

Finalization должна доказать, что worker всё ещё владеет текущим processing generation.

Stale worker не может завершить turn после lease takeover.

## Provider-call ambiguity

После V67 `provider_call_started_at` больше не должен быть application-level догадкой о том, начался ли provider call.

DB boundary для вставки физического attempt:

```text
ExecutionPlan
  ↓
ChatTurn must be PROCESSING
  ↓
processing_token + live lease checked under row lock
  ↓
provider_call_started_at = COALESCE(existing, DB clock_timestamp())
  ↓
Attempt STARTED may be inserted
  ↓
external provider I/O
```

Поэтому:

```text
provider_call_started_at = null
    → durable STARTED boundary ещё не пройдена

provider_call_started_at != null
    → физический provider execution был разрешён к старту
    → blind replay после неопределённого исхода запрещён
```

Точная истина об outcome хранится в `ModelExecutionAttempt`; `provider_call_started_at` остаётся coarse-grained
ChatTurn marker для recovery/fencing.

## Quota reservation

Quota резервируется до provider call и затем finalization корректирует состояние согласно persisted usage/cost quality.

V67 добавляет provenance:

- `reservation_version=1` — legacy static reservation;
- `reservation_version=2` — reservation, привязанная к immutable `ModelRouteDecision`;
- `model_route_decision_id` обязателен для v2;
- `cost_verifiable=true` допустим только когда `reserved_cost_usd` является доказанным upper bound по поддерживаемой immutable pricing scheme.

## Recovery

Recovery scheduler:

- выбирает expired PROCESSING turns;
- различает safe recoverability;
- использует fencing/lease state;
- не повторяет blindly unknown provider side effect.

## Chat title lifecycle — V58

Title provenance:

```text
DEFAULT
AUTO
USER
```

Semantics:

- `DEFAULT` — новый пустой chat с canonical title `Новый чат`;
- `AUTO` — deterministic title из первого accepted USER message;
- `USER` — explicit title при create/rename.

Automatic naming:

- не вызывает LLM;
- выполняется детерминированно;
- не должен перезаписывать user-owned title;
- manual rename фиксируется audit event `CHAT_RENAMED`;
- audit не должен копировать сам текст title без необходимости.

---
# Модуль `model`

Package:

```text
ru.safeai.gateway.model
```

## Логическая структура

```text
model/
├── config/
│   └── ModelRoutingEnvelopeProperties
│
├── controller/
│   ├── ModelCatalogController
│   ├── ModelPolicyPreviewController
│   ├── ModelRouteDecisionController
│   ├── ModelRuntimeController
│   └── OrganizationModelPolicyController
│
├── domain/
│   ├── BudgetEnforcement
│   ├── ModelBudgetCommitmentSnapshot
│   ├── ModelCapability
│   ├── ModelCatalogEntry
│   ├── ModelCatalogSource
│   ├── ModelLifecycle
│   ├── ModelModality
│   ├── ModelPricingStatus
│   ├── ModelRetentionStatus
│   ├── ModelRouteDecision
│   ├── ModelRouteOutcome
│   ├── ModelRouteReason
│   ├── ModelRouteRequest
│   ├── ModelRouteResult
│   ├── ModelTrainingUseStatus
│   ├── MonthlyCommitmentState
│   ├── MonthlyCostState
│   ├── OrganizationModelPolicy
│   ├── RuntimeModelProbeResult
│   └── RuntimeModelProbeStatus
│
├── dto/
│   ├── CreateModelCatalogVersionRequest
│   ├── CreateOrganizationModelPolicyVersionRequest
│   ├── ModelCatalogEntryResponse
│   ├── ModelPolicyPreviewItemResponse
│   ├── ModelPolicyPreviewResponse
│   ├── ModelRouteDecisionResponse
│   ├── OrganizationModelPolicyResponse
│   ├── RuntimeModelProbeResponse
│   ├── RuntimeModelStatusResponse
│   └── RuntimeModelStatusWireResponse
│
├── exception/
│   ├── ModelRouteDeniedException
│   └── ModelRouteEnvelopeExceededException
│
├── repository/
│   ├── JdbcTextArrayReader
│   ├── ModelCatalogRepository
│   ├── ModelRouteDecisionRepository
│   ├── ModelRouteRequestBindingRepository
│   ├── ModelRouteRequestMutexRepository
│   └── OrganizationModelPolicyRepository
│
├── service/
    ├── AnthropicRuntimeModelHealthProbe
    ├── DatabaseTimestampNormalizer
    ├── MockRuntimeModelHealthProbe
    ├── ModelCatalogAuditDetails
    ├── ModelCatalogAuditDetailsFactory
    ├── ModelCatalogRules
    ├── ModelCatalogService
    ├── ModelControlPlaneAccess
    ├── ModelControlPlaneNumericValidation
    ├── ModelControlPlaneSchemaVerifier
    ├── ModelInputTokenEstimator
    ├── ModelPolicyPreviewService
    ├── ModelRouteDecisionAuditDetails
    ├── ModelRouteDecisionFactory
    ├── ModelRouteDecisionIntegrity
    ├── ModelRouteExecutionBindingService
    ├── ModelRouteExecutionGuard
    ├── ModelRouteExecutionIdentity
    ├── ModelRouteReservedRequestService
    ├── ModelRoutingCostPolicy
    ├── ModelRoutingEnvelopeService
    ├── ModelRoutingExecutionCapabilityGate
    ├── ModelRoutingSelectionPolicy
    ├── ModelRoutingService
    ├── OpenAiRuntimeModelHealthProbe
    ├── OrganizationModelPolicyAuditDetailsFactory
    ├── OrganizationModelPolicyRules
    ├── OrganizationModelPolicyService
    ├── RuntimeModelHealthProbe
    ├── RuntimeModelProbeHttpSupport
    ├── RuntimeModelProbeService
    └── RuntimeModelStatusService
```

## Runtime model

Runtime — это то, что физически настроено в deployment.

Control Plane не равен dynamic provider router.

На текущем этапе backend runtime всё ещё представляет один активный configured provider/model target на deployment, а catalog/policy определяют, разрешён ли logical model и совместим ли он с этим target.

## Versioned Model Catalog

Catalog entry — immutable/append-only snapshot.

Основные поля/семантика:

- `modelKey`;
- version;
- provider;
- provider model ID;
- display name;
- lifecycle;
- limits;
- capabilities;
- modalities;
- retention status;
- training-use status;
- pricing status;
- pricing dimensions/version;
- explicit `pricingScheme`;
- `effectiveFrom`;
- source;
- creator/time.

V59/V62 дополнительно защищают canonical text/numeric boundaries, а V66 вводит explicit settlement algorithm identity.
Исторические legacy prices сохраняются, но не должны автоматически считаться проверенной современной pricing scheme.

### Latest vs effective

```text
latest
    = последняя созданная version
    = может быть scheduled

effective
    = версия, вступившая в силу к decision time
    = участвует в routing
```

Старая version не редактируется.

## Organization model policy

Policy append-only/versioned.

Поддерживает:

- enabled;
- allow model keys;
- deny model keys;
- default model key;
- max input envelope;
- max output envelope;
- max request cost;
- monthly budget;
- `SOFT / HARD`;
- require complete pricing;
- require no training;
- require zero data retention.

DB canonicality hardening:

- каждый allow/deny key соответствует normalized lowercase key syntax;
- duplicate key внутри allow/deny array запрещён;
- required capability array route decision также должен быть unique.

Первое отсутствие policy представляется fail-closed/unconfigured state, а не неявным «разрешить всё».
После V65 новый strict integrity-v3 decision без policy может существовать только как явное
`DENIED / MODEL_NOT_ALLOWED` unconfigured evidence; physical execution такой row не авторизует.

## Route decision

До provider I/O сохраняется immutable evidence:

- requested model;
- selected model;
- exact catalog version;
- exact policy version;
- provider/model identity;
- capabilities/modalities;
- input accounting version;
- additional input upper bound;
- output cap;
- pricing/budget state;
- route outcome;
- reason;
- integrity version;
- SHA-256 digest.

DB deferred trigger связывает `ALLOWED` decision с exact `ChatTurn`.

### Budget commitments — V60/V62/V64

Budget accounting разделяет:

```text
actual_cost_usd
reserved_cost_usd
unknown_cost_count
cost_state
```

V62 исправляет важный representability case: full monthly liability может оставаться unknown, но известный lower bound
уже способен доказать budget overrun. V64 сохраняет route-time commitment evidence отдельно и immutable, не изменяя
исторические V1/V2/V3 decision hashes.

### Final request binding — V62/V64/V65

Governance evidence проходит две durable seal-точки:

```text
base/pre-RAG request
  ↓ SHA-256
model_route_reserved_requests
  ↓
retrieval + RAG + context fitting
  ↓ SHA-256
model_route_prepared_requests_v62
  ↓
binding_version=2
  ↓
ExecutionPlan / physical attempt
```

Для Knowledge mode prepared receipt дополнительно обязан совпасть с immutable
`knowledge_rag_context_receipts_v64`: exact `RetrievalRun`, KB, user/org, context hash и final prepared-request hash.

После V65 новые prepared receipts должны иметь `binding_version=2`, а DB trigger на
`model_execution_attempts` не разрешает physical I/O без exact v2 binding.

## Input accounting

Governance accounting использует deterministic input units.

Текущий accounting contract:

```text
UTF8_STRUCTURAL_UNITS_V2
```

Это **не** заявляется как exact provider tokenizer.

Исторические DB column names могут содержать `*_tokens`, но новые semantics в коде трактуются как versioned deterministic accounting units.

## Capability gate

Catalog metadata может описывать:

```text
TEXT
TOOLS
VISION
STRUCTURED_OUTPUT
```

Но metadata не означает автоматически реализованный data plane.

Нереализованные specialized capabilities должны fail closed.

## Runtime probe

Runtime probe:

- отделён от user Chat;
- не отправляет user prompt/history/RAG content;
- использует strict HTTPS/host policy;
- redirects disabled;
- bounded connect/read/body limits;
- возвращает controlled status.

Типовые states:

```text
NOT_PROBED
AVAILABLE
AUTH_ERROR
MODEL_NOT_FOUND
RATE_LIMITED
UNAVAILABLE
ERROR
```

Probe — point-in-time check, а не SLA monitoring.

---

# Модуль `ai`

Package:

```text
ru.safeai.gateway.ai
```

## Структура

```text
ai/
├── config/
│   ├── AiConfiguration
│   ├── AiProductionConfigurationValidator
│   ├── AiRetryTimeoutBudgetValidator
│   ├── AnthropicProviderConfiguration
│   └── OpenAiProviderConfiguration
│
├── dto/
│   ├── AiChatRequest
│   ├── AiChatResponse
│   ├── AiMessage
│   └── AiMessageRole
│
├── exception/
│   ├── AiContextLimitException
│   ├── AiProviderBillingException
│   ├── AiProviderErrorType
│   ├── AiProviderException
│   ├── AiProviderOverloadedException
│   ├── AiProviderQuotaExceededException
│   ├── AiProviderRateLimitedException
│   ├── AiProviderResponseTooLargeException
│   ├── AiProviderTimeoutException
│   └── AiProviderUnavailableException
│
├── execution/
│   ├── AiExecutionAttemptObserver
│   ├── AiExecutionAttemptRecorder
│   ├── AiExecutionAttemptScope
│   ├── AiExecutionPlanService
│   ├── AiExecutionRequest
│   ├── AiExecutionResult
│   ├── AiExecutionService
│   ├── ExecutionAttemptOutcome
│   ├── FallbackSafety
│   ├── ModelExecutionAttemptEntity
│   ├── ModelExecutionAttemptRepository
│   ├── ModelExecutionPlanEntity
│   ├── ModelExecutionPlanRepository
│   ├── OutcomeCertainty
│   ├── PhysicalAttemptReconciliationRequiredException
│   ├── ProviderExecutionTarget
│   ├── ProviderFailureCertainty
│   ├── ProviderPricingEvidence
│   ├── ProviderResponseEvidence
│   ├── ProviderUsageEvidence
│   ├── ResolvedModelMismatchException
│   ├── ResponseValidationStatus
│   └── RetrySafety
│
│
├── input/
│   ├── AiInputUnitEstimator
│   └── AiRequestFingerprint
│
├── metadata/
│   ├── AiResponseStatus
│   ├── AiTokenUsage
│   ├── PricingStatus
│   └── UsageStatus
│
├── pricing/
│   ├── ModelPricingProperties
│   ├── ModelPricingScheme
│   ├── ModelPricingService
│   ├── PricingResolver
│   ├── PricingResult
│   ├── StaticConfigurationPricingResolver
│   └── VersionedCatalogPricingResolver
│
├── provider/
│   ├── anthropic/
│   │   ├── AnthropicProperties
│   │   └── AnthropicProvider
│   │
│   ├── mock/
│   │   └── MockAiProvider
│   │
│   ├── openai/
│   │   ├── OpenAiProperties
│   │   └── OpenAiProvider
│   │
│   ├── AiContextWindowProperties
│   ├── AiContextWindowService
│   ├── AiJsonNodeSupport
│   ├── AiProvider
│   ├── AiProviderAttemptContext
│   ├── AiProviderExceptionFactory
│   ├── AiProviderProperties
│   ├── AiProviderRetryExecutor
│   ├── AiProviderSupport
│   ├── AiResponseMetadataService
│   ├── AiResponseTooLargeIOException
│   ├── AiRestClientFactory
│   ├── AiRetryProperties
│   ├── ProviderEvidenceText
│   ├── ProviderPropertyValidator
│   └── ProviderRegistry
│
└── web/
    └── AiExceptionHandler
```

## Provider abstraction

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

## Durable physical execution

Начиная с V50 AI execution имеет отдельную durable evidence layer; V62–V67 доводят binding/start boundary до
жёсткого database-enforced контракта.

```text
ModelRouteDecision
      ↓
sealed base request
      ↓
final prepared request + optional exact RAG receipt
      ↓
binding_version=2
      ↓
ModelExecutionPlan
      ↓
live fenced ChatTurn check
      ↓
ModelExecutionAttempt STARTED + provider_call_started_at
      ↓
provider I/O
      ↓
terminal physical evidence
      ↓
Chat finalization
```

Ключевой порядок:

```text
validate governed route/envelope
  ↓
commit final prepared-request binding
  ↓
commit ExecutionPlan
  ↓
commit Attempt STARTED using live lease/fencing + DB wall clock
  ↓
external provider I/O
  ↓
persist terminal physical evidence
  ↓
application/chat finalization
```

Это позволяет переживать crash boundaries без ложного предположения «если Chat не ответил, provider точно ничего не сделал»
и не позволяет historical binding v1 авторизовать новый physical call.

## Physical target identity

Attempt фиксирует target:

- provider;
- provider configuration reference/version;
- deployment reference/version;
- requested physical model;
- allowed resolved models.

Текущий runtime использует transitional static target identity; полноценный versioned provider registry ещё не реализован.

## Outcome certainty

Система различает:

```text
KNOWN_NOT_EXECUTED
KNOWN_REJECTED
KNOWN_EXECUTED
AMBIGUOUS
```

Outcome certainty не эквивалентен HTTP status и не эквивалентен business success.

## Retry / fallback safety

Следующий physical attempt допускается только если предыдущий terminal state доказан как retry-safe.

DB boundary сериализует attempt insert и требует:

```text
previous outcome = FAILED
AND certainty ∈ {KNOWN_NOT_EXECUTED, KNOWN_REJECTED}
AND retrySafety = SAME_TARGET_RETRY_ALLOWED
AND exact same physical target
```

V59 дополнительно запрещает второй physical attempt для bounded-cost routes, если действует:

```text
HARD monthly budget
OR policy.max_request_cost_usd
```

Таким образом cost upper-bound reservation не может быть нарушен скрытым retry. Blind retry/fallback после
`AMBIGUOUS`, `SUCCEEDED` или physically executed protocol rejection не допускается.

## Resolved model validation

Provider response model сверяется с approved target.

Если provider фактически выполнил запрос, но вернул неразрешённый resolved model, физическое evidence сохраняется до governance rejection.

## HTTP hardening

`AiRestClientFactory` обеспечивает:

- connect timeout;
- read timeout;
- redirects disabled;
- response-body byte limits;
- bounded error-body read;
- controlled exception conversion.

Provider base URL:

- HTTPS;
- allowlisted exact host/base URL;
- без userinfo/query/fragment;
- без произвольного custom endpoint в production baseline.

## Pricing

`VersionedCatalogPricingResolver` привязывает calculation evidence к:

```text
provider operation
  ↓
execution plan
  ↓
route decision
  ↓
exact catalog entry/version
  ↓
pricing version
  ↓
pricing scheme
```

Unknown pricing не становится zero.

V66 делает pricing algorithm identity явной:

```text
FLAT_TOKEN_V1
LEGACY_FLAT_UNVERIFIED_V1
UNSUPPORTED_COMPLEX_V1
```

Legacy `CONFIGURED` pricing после migration не должен автоматически считаться доказуемым современным расчетом:
governed path обязан fail closed, пока новая audited catalog version не выберет поддерживаемую scheme.

`StaticConfigurationPricingResolver` остаётся compatibility/dev path, а governed resolution должен использовать
versioned catalog evidence.

## Usage / response evidence

Provider usage поддерживает quality states и specialized billing dimensions.

V66 отдельно фиксирует факт физически наблюдавшегося provider response:

- `response_observed`;
- `response_validation_status`;
- `provider_http_status`;
- `protocol_failure_code`;
- `PROTOCOL_REJECTED` outcome.

Это позволяет сохранить billable `KNOWN_EXECUTED` evidence даже когда ответ нельзя принять на application/protocol layer.
Pricing/usage evidence остаётся на physical attempt для reconciliation.

---

# Модуль `knowledge`

Package:

```text
ru.safeai.gateway.knowledge
```

## Структура

```text
knowledge/
├── chunking/
│   ├── KnowledgeChunkCandidate
│   └── KnowledgeChunker
│
├── config/
│   ├── KnowledgeEmbeddingConfigurationInvariantVerifier
│   ├── KnowledgeEmbeddingProperties
│   ├── KnowledgeIngestionProperties
│   ├── KnowledgeOcrConfigurationInvariantVerifier
│   ├── KnowledgeOcrProperties
│   ├── KnowledgeProcessingConfiguration
│   ├── KnowledgeRagProperties
│   └── KnowledgeRetrievalProperties
│
├── controller/
│   ├── AnswerPassportController
│   ├── KnowledgeBaseController
│   ├── KnowledgeDocumentController
│   ├── KnowledgeEvaluationController
│   └── KnowledgeRetrievalController
│
├── dto/
│   ├── AnswerCitationResponse
│   ├── AnswerPassportResponse
│   ├── CreateKnowledgeBaseMemberRequest
│   ├── CreateKnowledgeBaseRequest
│   ├── KnowledgeBaseAccessResponse
│   ├── KnowledgeBaseMemberPageResponse
│   ├── KnowledgeBaseMemberResponse
│   ├── KnowledgeBasePageResponse
│   ├── KnowledgeBaseResponse
│   ├── KnowledgeDocumentPageResponse
│   ├── KnowledgeDocumentResponse
│   ├── KnowledgeDocumentVersionPageResponse
│   ├── KnowledgeDocumentVersionResponse
│   ├── KnowledgeEvaluationCaseRequest
│   ├── KnowledgeEvaluationCaseResponse
│   ├── KnowledgeEvaluationRequest
│   ├── KnowledgeEvaluationResponse
│   ├── KnowledgeHealthResponse
│   ├── KnowledgeMemberCandidateResponse
│   ├── KnowledgeReindexResponse
│   ├── KnowledgeRetrievalHitResponse
│   ├── KnowledgeRetrievalRequest
│   ├── KnowledgeRetrievalResponse
│   ├── UpdateKnowledgeBaseMemberRequest
│   ├── UpdateKnowledgeBaseRequest
│   └── UpdateKnowledgeDocumentRequest
│
├── embedding/
│   ├── HashingKnowledgeEmbeddingProvider
│   ├── KnowledgeEmbeddingException
│   ├── KnowledgeEmbeddingProductionInvariantVerifier
│   ├── KnowledgeEmbeddingProvider
│   ├── OpenAiKnowledgeEmbeddingProvider
│   └── PgVectorSupport
│
├── entity/
│   ├── KnowledgeBaseEntity
│   ├── KnowledgeBaseMembershipEntity
│   ├── KnowledgeDocumentEntity
│   ├── KnowledgeDocumentVersionEntity
│   └── KnowledgeIngestionJobEntity
│
├── evaluation/
│   └── KnowledgeRetrievalMetrics
│
├── exception/
│   └── KnowledgeStorageUnavailableException
│
├── extraction/
│   ├── CsvKnowledgeExtractor
│   ├── DocxKnowledgeExtractor
│   ├── ExtractedDocument
│   ├── ExtractedSection
│   ├── ExtractionTextSupport
│   ├── HtmlKnowledgeExtractor
│   ├── JsonKnowledgeExtractor
│   ├── KnowledgeDocumentExtractor
│   ├── KnowledgeExtractionService
│   ├── MarkdownKnowledgeExtractor
│   ├── OoxmlPackageSupport
│   ├── PdfKnowledgeExtractor
│   ├── PlainTextKnowledgeExtractor
│   ├── PptxKnowledgeExtractor
│   ├── XlsxKnowledgeExtractor
│   └── XmlKnowledgeExtractor
│
├── generation/
│   ├── KnowledgeGenerationGc
│   └── KnowledgeGenerationGcScheduler
│
│
│
│
├── ingestion/
│   ├── EmbeddedKnowledgeChunk
│   ├── ExpiredKnowledgeIngestionJob
│   ├── KnowledgeChunkPersistenceService
│   ├── KnowledgeIngestionClaim
│   ├── KnowledgeIngestionException
│   ├── KnowledgeIngestionFailureService
│   ├── KnowledgeIngestionMaintenanceService
│   ├── KnowledgeIngestionProcessor
│   ├── KnowledgeIngestionQueueRepository
│   ├── KnowledgeIngestionScheduler
│   └── StaleIngestionOwnershipException
│
├── model/
│   ├── KnowledgeBaseAccessLevel
│   ├── KnowledgeBaseVisibility
│   ├── KnowledgeHealthState
│   └── KnowledgeIngestionStatus
│
├── ocr/
│   ├── DisabledKnowledgeOcrProvider
│   ├── HttpKnowledgeOcrProvider
│   ├── KnowledgeOcrProvider
│   ├── OcrDocument
│   └── OcrPage
│
├── rag/
│   ├── AnswerPassportService
│   ├── KnowledgeCitationValidator
│   ├── KnowledgeContextAssembler
│   ├── KnowledgeContextSource
│   ├── KnowledgeHashing
│   ├── KnowledgeMode
│   ├── KnowledgeRagContextReceiptRepository
│   ├── KnowledgeRagContextReceiptService
│   ├── KnowledgeRagService
│   ├── RagCitation
│   ├── RagCompletion
│   └── RagPreparation
│
├── repository/
│   ├── KnowledgeBaseMembershipRepository
│   ├── KnowledgeBaseRepository
│   ├── KnowledgeDocumentQueryRepository
│   ├── KnowledgeDocumentRepository
│   ├── KnowledgeDocumentVersionRepository
│   ├── KnowledgeIngestionJobRepository
│   └── KnowledgeMemberDirectoryRepository
│
├── retrieval/
│   ├── KnowledgeRetrievalExecution
│   ├── KnowledgeRetrievalHit
│   └── KnowledgeRetrievalRepository
│
├── service/
│   ├── KnowledgeAccessService
│   ├── KnowledgeBaseNameNormalizer
│   ├── KnowledgeBaseService
│   ├── KnowledgeDocumentFileValidator
│   ├── KnowledgeDocumentMediaTypeDetector
│   ├── KnowledgeDocumentMediaType
│   ├── KnowledgeDocumentNameNormalizer
│   ├── KnowledgeDocumentService
│   ├── KnowledgeDocumentWriteService
│   ├── KnowledgeEvaluationService
│   ├── KnowledgeNameNormalizerSupport
│   ├── KnowledgeOoxmlDetector
│   ├── KnowledgeOperationsService
│   ├── KnowledgeRetrievalService
│   ├── KnowledgeStructuredTextValidator
│   ├── KnowledgeUploadFileSupport
│   └── KnowledgeValidationErrors
│
└── storage/
    ├── reconciliation/
    │   ├── KnowledgeStorageReconciliationConfiguration
    │   ├── KnowledgeStorageReconciliationProperties
    │   ├── KnowledgeStorageReconciliationScheduler
    │   └── KnowledgeStorageUploadJournal
    │   
    ├── KnowledgeStorageConfiguration
    ├── KnowledgeStorageProductionInvariantVerifier
    ├── KnowledgeStorageProperties
    ├── KnowledgeStorageType
    ├── LocalObjectStorage
    ├── ObjectStorage
    ├── S3ObjectStorage
    └── StoredObject  
```

## Knowledge Base access

Visibility model включает organization/member semantics.

`KnowledgeAccessService` является backend security boundary.

ACL/filtering применяется **до ranking**, а не после retrieval.

## Document/version model

Document metadata отделено от immutable uploaded versions.

Версия хранит provenance:

- storage key;
- content SHA;
- size;
- media type;
- version number;
- uploader/time;
- ingestion identity.

После V63 у документа два разных указателя:

```text
current_version_id
    = latest uploaded version
    = может быть PENDING / processing / FAILED

searchable_version_id
    = latest successfully published searchable version
    = меняется только после fenced READY publication
```

Поэтому новый upload больше не должен временно скрывать предыдущую рабочую searchable version.

## Upload journal — V56

Новый upload создаёт durable intent.

Lifecycle:

```text
INTENT
  ├─→ STORED
  │     ├─→ LINKED
  │     └─→ CLEANING → CLEANED
  └─→ NEEDS_REVIEW

CLEANING
  └─→ NEEDS_REVIEW   # ambiguous cleanup
```

Принцип:

> Нельзя автоматически считать historical/uncertain S3 object orphan-ом только потому, что SQL row не найден.

Cleanup разрешён только для positively known `STORED` unlinked object с fencing.

V63 также разделяет timestamps:

- `needs_review_at` — когда операция попала в очередь `NEEDS_REVIEW`;
- `reviewed_at` — резервируется для фактически завершённого human review.

Попадание в review queue больше не маскируется как уже состоявшийся review.

## Ingestion

Job state хранится durable.

Queue path использует:

- bounded claims;
- `FOR UPDATE SKIP LOCKED`;
- processing lease;
- processing token/generation fencing;
- retry/backoff;
- stale ownership detection;
- failure service.

## Extraction

Поддержанные baseline formats:

```text
PDF
DOCX
PPTX
XLSX
HTML
Markdown
JSON
XML
CSV
plain text
```

Extractors имеют format-specific safety/validation.

OOXML path защищается от unbounded decompression.

Extraction выполняется с timeout и bounded worker pool.

## OCR

OCR — optional provider boundary.

HTTP OCR configuration:

- HTTPS only;
- explicit allowed hosts;
- credential redaction;
- connect/read timeout;
- response byte limit.

PDF extraction определяет страницы, которым требуется OCR.

## Embeddings

Provider abstraction:

```text
KnowledgeEmbeddingProvider
  ├─ HashingKnowledgeEmbeddingProvider
  └─ OpenAiKnowledgeEmbeddingProvider
```

Hashing provider — deterministic/local baseline.

Production OpenAI embedding path использует explicit endpoint restrictions.

## Chunking

`KnowledgeChunker` выполняет deterministic structure-aware chunking.

Это не semantic chunking и README не заявляет обратное.

Chunk provenance включает:

- document/version;
- generation;
- ordinal;
- heading/page metadata;
- extractor/chunker/embedding identity;
- content hash;
- vector;
- lexical content.

## Retrieval

Pipeline:

```text
query
  ↓
tenant + KB + membership + enabled filters
  ↓
vector candidates
  +
lexical candidates
  ↓
RRF
  ↓
topK
  ↓
persist retrieval run/hits
```

V55 добавляет Russian lexical profile:

```text
simple+russian-v2
```

Historical retrieval runs сохраняют legacy provenance.

## RAG modes

```text
GENERAL
KNOWLEDGE_ASSISTED
KNOWLEDGE_ONLY
```

`KNOWLEDGE_ONLY` должен abstain/fail closed при недостаточном evidence.

## Prompt-injection boundary

Retrieved document content считается **untrusted source data**.

RAG context формируется так, чтобы документ не получал authority system instruction.

## Citations

`KnowledgeCitationValidator` проверяет structural/source validity citation markers.

Citation evidence содержит exact chunk/document/version identity.

## Answer Passport

Answer Passport связывает:

```text
ChatTurn
RetrievalRun
KnowledgeBase
ModelRouteDecision
knowledge mode
provider
requested/resolved model
embedding model
context hash
answer hash
citations
evidence flags
```

Это provenance document, а не обещание семантической истинности ответа.

V64 добавляет более раннюю execution-time proof-точку — immutable `knowledge_rag_context_receipts_v64`.
Она доказывает, что exact retrieval/context assembly был связан с exact final prepared AI request **до**
физического provider execution.

## Index generations — V57/V59/V63

Generation-level lifecycle:

```text
ACTIVE
  ↓
RETIRED
  ↓
COLLECTING
  ↓
COLLECTED
```

Инварианты:

- новая generation может быть вставлена только как `ACTIVE`;
- retirement/collection chronology проверяется БД;
- максимум одна `ACTIVE` generation для document version;
- publication проверяет exact chunk count/provenance;
- stale publication блокируется;
- searchable pointer меняется атомарно только после успешной publication;
- защищается именно generation, реально доступная retrieval через `searchable_version_id`;
- chunk deletion разрешён только fenced GC;
- retrieval evidence защищает referenced chunks;
- retired/collected generation нельзя «воскресить»;
- incomplete GC transaction блокируется deferred guard.

---
# Ключевые backend contracts

Ниже — основные публичные API groups. Controllers остаются source of truth для exact query/body shape.

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

Дополнительно существует отдельный title-management controller для manual rename lifecycle.

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

Mutation contracts используют optimistic `expectedVersion`.

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

Также есть directory/filter endpoints для UI.

## Usage

```http
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
```

## Model Control Plane

```http
GET  /api/admin/models/runtime

GET  /api/admin/models/catalog
GET  /api/admin/models/catalog/effective
POST /api/admin/models/catalog
POST /api/admin/models/catalog/import-runtime

GET  /api/admin/models/policies/{organizationId}
POST /api/admin/models/policies/{organizationId}

GET  /api/admin/models/route-decisions/{decisionId}
```

Runtime availability probe — отдельная administrative operation в `ModelRuntimeController`.

## Knowledge Bases

```http
GET   /api/knowledge-bases
GET   /api/knowledge-bases/{knowledgeBaseId}
GET   /api/knowledge-bases/{knowledgeBaseId}/access
POST  /api/knowledge-bases
PATCH /api/knowledge-bases/{knowledgeBaseId}
```

Membership administration существует под `/api/knowledge-bases/{knowledgeBaseId}/members`.

## Knowledge documents

Base path:

```http
/api/knowledge-bases/{knowledgeBaseId}/documents
```

Поддерживаются:

- list;
- upload/version creation;
- metadata update;
- list versions;
- current version download;
- historical version download;
- health;
- reindex.

Подтверждённые routes:

```http
GET  /api/knowledge-bases/{knowledgeBaseId}/documents/health
POST /api/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/reindex
GET  /api/knowledge-bases/{knowledgeBaseId}/documents
GET  /api/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/versions
GET  /api/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/download
GET  /api/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/versions/{versionId}/download
```

## Knowledge retrieval / evaluation

```http
POST /api/knowledge-bases/{knowledgeBaseId}/retrieval
POST /api/knowledge-bases/{knowledgeBaseId}/evaluations
```

Answer Passport читается по chat/turn identity через `AnswerPassportController`.

---

# Database / Flyway V1–V70

Production migrations:

```text
backend/src/main/resources/db/migration
```

Local-only:

```text
backend/src/main/resources/db/local-migration
```

## Правила

```text
never edit an applied migration
new schema change => new migration
ddl-auto=validate
critical invariants in PostgreSQL where practical
local seed != production reference data
```

## Evolution map

| Version   | Назначение |
|-----------|--|
| `V1–V10`  | base schema, tenant denormalization, timestamps, quotas/rollups, indexes |
| `V11–V19` | audit/user/chat identity and integrity evolution |
| `V20–V23` | production integrity, canonical identity/name hardening, concurrent indexes |
| `V24`     | exactly-one-role, user security hardening, audit outbox |
| `V25`     | organization `auth_version` + refresh organization epoch |
| `V26–V27` | audit retry/dead-letter/details bounds/query indexes |
| `V28–V31` | usage/pricing quality model and query indexes |
| `V32`     | durable `ChatTurn` state machine |
| `V33–V36` | ChatTurn indexes, ambiguity quality, composite tenant integrity |
| `V37`     | immutable target organization audit snapshots |
| `V38–V42` | Knowledge Base, versions, ingestion, retrieval, RAG, Answer Passport |
| `V43`     | chat archive lifecycle |
| `V44`     | Knowledge production integrity hardening |
| `V45`     | Model Control Plane baseline |
| `V46`     | Model Control Plane hardening |
| `V47`     | Knowledge chunk-generation uniqueness |
| `V48`     | input-accounting provenance / route integrity v3 |
| `V49`     | strict routing + generic pricing semantics |
| `V50`     | AI execution plans/attempts + usage evidence |
| `V51`     | execution attempt integrity hardening |
| `V52`     | strict execution + modality integrity |
| `V53`     | physical attempt transition + pricing integrity |
| `V54`     | provider identity + reconciliation safety |
| `V55`     | Knowledge Russian lexical profile |
| `V56`     | durable Knowledge storage upload intents |
| `V57`     | Knowledge index generations + fenced publication/GC |
| `V58`     | deterministic chat title lifecycle |
| `V59`     | governance integrity: canonical model keys, bounded-cost single-attempt rule, reconciliation/title/generation hardening |
| `V60`     | UTC monthly physical cost commitment ledger: actual, reserved, unknown liability, carry-forward |
| `V61`     | model-policy allow/deny array uniqueness |
| `V62`     | budget representability fix, final prepared-request receipt, capability canonicality, commitment ledger v2 |
| `V63`     | Knowledge searchable-version publication, generation lifecycle hardening, review-time semantics |
| `V64`     | immutable route-time budget commitments, RAG context receipt, prepared-request binding v2 |
| `V65`     | post-rollout binding v2 enforcement and DB physical-execution fence |
| `V66`     | physical response/protocol rejection evidence + explicit fail-closed pricing scheme provenance |
| `V67`     | ChatTurn state/evidence hardening, live STARTED fencing, quota provenance, physical-spend view, DB clock |
| `V68`     | audit event/outbox integrity: canonical snapshots, typed payload, immutable evidence/intent, terminal dead-letter state |
| `V69`     | audit actor-link FK detachment: отделение исторического actor snapshot от ссылочной зависимости на удаляемую учётную запись |
| `V70`     | audit live-user link separation: разделение живой ссылки на пользователя и исторических данных audit actor; сохранение audit evidence при изменении жизненного цикла пользователя |

## Важные DB boundaries

Финальная схема включает:

- canonical user email uniqueness;
- normalized organization-name uniqueness;
- exactly-one-role;
- composite tenant FK;
- refresh family integrity;
- audit event type/payload/snapshot validation;
- immutable `audit_events` UPDATE boundary;
- immutable `audit_outbox` intent + monotonic delivery metadata;
- usage quality checks;
- one reply per user message;
- DB-enforced ChatTurn transition graph;
- terminal ChatTurn immutability;
- completed Chat message immutability;
- route-decision immutability;
- deferred exact route-decision ↔ ChatTurn validation;
- canonical unique model-policy key arrays;
- route-time budget commitment evidence;
- sealed base request;
- sealed final prepared request;
- exact Knowledge RAG context receipt;
- binding-version=2 physical execution fence;
- execution-plan immutability;
- live lease/fencing requirement at physical `STARTED`;
- physical attempt sequence;
- bounded-cost single-attempt rule;
- physical retry safety;
- provider target identity;
- physical response/protocol-rejection evidence;
- explicit pricing-scheme identity;
- reconciliation safety;
- monthly physical cost commitment read model;
- per-ChatTurn known physical spend read model;
- Knowledge latest-uploaded vs searchable-version separation;
- Knowledge chunk/generation provenance;
- one active Knowledge generation;
- fenced generation publication/GC;
- generation chronology;
- deterministic chat title provenance and no-backwards provenance transition.

## Concurrent indexes

Часть migrations использует `CREATE INDEX CONCURRENTLY`.

Production Flyway должен сохранять совместимую PostgreSQL transactional-lock configuration.

Release runbook также должен проверять `pg_index.indisvalid/indisready` для critical unique/business indexes после аварийного interrupted migration.

---

# Observability и operational jobs

## Logging

Structured log context включает server request ID и, где применимо, client request ID.

Не логировать по умолчанию:

- passwords;
- raw JWT;
- raw refresh token;
- cookies;
- Authorization;
- provider API keys;
- full user prompts;
- full provider responses;
- raw document chunks;
- OCR/embedding credentials.

## Metrics

Проект содержит module-level metrics, включая:

- chat processing;
- rate limiting;
- audit outbox.

Prometheus endpoint должен быть internal/network-protected.

## Scheduled jobs

В текущем baseline существуют scheduled/maintenance paths для:

- refresh-token cleanup;
- audit outbox processing;
- audit retention;
- usage rollups/backfill;
- ChatTurn recovery;
- Knowledge ingestion;
- Knowledge storage reconciliation;
- Knowledge generation GC.

Multi-instance job должен иметь DB/distributed coordination там, где duplicate work нарушит semantics.

---

# Тестирование

README не утверждает `BUILD SUCCESS` без фактического запуска конкретного commit.

## Подтверждённое состояние на 09.10.2026

По предоставленному Maven Surefire summary последнего полного запуска:

```text
Tests run: 2238, Failures: 0, Errors: 2, Skipped: 3
BUILD FAILURE
```

Обе ошибки локализованы в `ChatTurnStateMachineIntegrationTest`, который находится
**в `ru.safeai.gateway.knowledge.integration`**. Его исторический helper
`expire()` обновляет `chat_turns.created_at`; PostgreSQL V67 корректно отвергает
изменение неизменяемой identity. При этом одноимённый класс в
`ru.safeai.gateway.chat.integration` уже использует тестовый `Clock` для
моделирования истечения lease и отдельно проходил в IntelliJ IDEA.
Наличие двух классов с одинаковым простым именем не означает, что второй
можно безопасно удалить: сперва необходимо сопоставить их сценарии и сохранить
уникальное покрытие. Исправление должно выполняться в устаревшем тестовом
helper/fixture без отключения DB triggers, изменения `created_at` и снижения
строгости assertions.

Статус сборки **не является** подтверждением полного green baseline.
Необходимо заново прогнать `./mvnw clean test` после исправления оставшегося
класса и приложить Surefire summary нового запуска.

## Backend

```bash
cd backend
./mvnw clean test
```

Для двух одноимённых интеграционных классов в разных пакетах используйте
**полное имя** при выборочном запуске:

```bash
./mvnw -Dtest=ru.safeai.gateway.chat.integration.ChatTurnStateMachineIntegrationTest test
./mvnw -Dtest=ru.safeai.gateway.knowledge.integration.ChatTurnStateMachineIntegrationTest test
```

Команда с коротким `-Dtest=ChatTurnStateMachineIntegrationTest` потенциально
выберет оба класса и не подходит для изоляции диагностики.


Windows:

```bat
cd backend
mvnw.cmd clean test
```

## Фактически присутствующие test categories

### Security / Auth

- JWT contracts;
- auth cookie configuration;
- refresh rotation/reuse;
- CSRF;
- request ID;
- trusted proxy/client IP;
- production startup invariants;
- security error responses;
- PostgreSQL auth concurrency.

### User / Organization

- authorization boundaries;
- request validation;
- role mapping;
- optimistic concurrency;
- security epoch integration;
- platform organization invariant;
- cache invalidation.

### Chat

- controller contract/security;
- entity invariants;
- tenant isolation;
- state machine;
- recovery;
- concurrency;
- repository transaction behavior;
- history;
- usage-quality integration;
- EXPLAIN plan coverage;
- title behavior.

### Audit

- authorization boundary;
- tenant isolation;
- cursor pagination;
- actor/target snapshots;
- outbox;
- uncertain commit behavior;
- retry/dead-letter;
- retention;
- advisory lock;
- required/best-effort policies.

### Usage

- controller contract/security;
- query contracts;
- pagination;
- UTC aggregation;
- pricing correctness;
- rollup reconciliation;
- performance/EXPLAIN;
- tenant isolation.

### AI / Model

- provider contract tests;
- retry behavior;
- provider properties;
- response size bounds;
- context fitting;
- pricing;
- production configuration;
- route/policy/catalog behavior;
- runtime probe;
- model frontend/API contract tests exist separately.

### Knowledge

- ACL;
- KB concurrency;
- document-version concurrency;
- migration integrity;
- ingestion queue;
- retrieval repository;
- extraction;
- OCR behavior;
- S3 end-to-end;
- storage configuration;
- citations;
- context assembly;
- retrieval metrics;
- real fixture contracts.

## Release candidate checks

Перед серьёзным deployment:

```text
fresh V1 → V70
representative populated upgrade → V70
Flyway history validation
critical index validity check
backend clean test
container build/start
prod-like startup invariants
tenant isolation
auth epoch invalidation
ChatTurn recovery
AI physical attempt recovery/reconciliation
Model route binding
Knowledge generation publication/GC
backup/restore drill
```

---

# Local development

## Infrastructure

macOS/Linux:

```bash
cd infra
docker compose -f docker-compose.local.yml up -d postgres redis
```

Windows:

```bat
cd infra
docker compose -f docker-compose.local.yml up -d postgres redis
```

Knowledge S3/MinIO requirements зависят от выбранного local profile/configuration.

## Backend

macOS/Linux:

```bash
cd backend
export SPRING_PROFILES_ACTIVE=local
export REDIS_PASSWORD='local-redis-password'

# Текущий JWT contract использует RSA key ring.
# Перед запуском задайте через environment/configtree:
# SAFEAI_JWT_ISSUER
# SAFEAI_JWT_ACTIVE_KEY_ID
# SAFEAI_JWT_PUBLIC_KEY
# SAFEAI_JWT_PRIVATE_KEY

./mvnw spring-boot:run
```

Windows CMD:

```bat
cd backend
set SPRING_PROFILES_ACTIVE=local
set REDIS_PASSWORD=local-redis-password

REM Текущий JWT contract использует RSA key ring.
REM Перед запуском задайте SAFEAI_JWT_ISSUER, SAFEAI_JWT_ACTIVE_KEY_ID,
REM SAFEAI_JWT_PUBLIC_KEY и SAFEAI_JWT_PRIVATE_KEY через environment/configtree.

mvnw.cmd spring-boot:run
```

Typical local services:

```text
Backend:  http://127.0.0.1:8080
Postgres: localhost:5432
Redis:    localhost:6379
```

Local/demo credentials никогда не должны использоваться в production.

---

# Production deployment notes

## TLS

```text
Internet
  ↓
TLS terminator / reverse proxy
  ↓
Nginx
  ↓
backend internal port
```

Backend не должен публиковаться напрямую без edge controls.

## Trusted proxy

Указывать только реальные trusted edge CIDRs.

Не использовать:

```text
0.0.0.0/0
::/0
```

как доверенные proxy ranges.

## Secrets

Не хранить в Git:

- JWT private material;
- DB credentials;
- Redis credentials;
- OpenAI key;
- Anthropic key;
- Knowledge embedding key;
- OCR key;
- S3 credentials;
- future BYOK/KMS/service-account secrets.

## S3-compatible object storage

Production Knowledge требует:

- durable object store;
- access policy;
- lifecycle policy;
- backup/restore strategy;
- monitoring;
- reconciliation job;
- explicit storage endpoint hardening.

## Flyway

- applied migrations immutable;
- `clean` disabled;
- concurrent-index migration behavior протестирован;
- production role имеет необходимые extension/index privileges;
- `pgvector` доступен до применения Knowledge vector migrations.

## PostgreSQL backup / restore

Backup не считается готовым только потому, что существует cron/script.

Нужен периодический restore drill.

## Prometheus

`/actuator/prometheus` / management endpoints — internal-only.

## Provider egress

Provider endpoints должны быть allowlisted/pinned.

Redirect following с credentials запрещён.

---

# Текущие ограничения и production-readiness gaps

Этот раздел фиксирует **зафиксированные в ранее проанализированном коде ограничения (переоценены по доступным материалам на 09.10.2026)**. Пункты, закрытые `V59–V68`,
из списка удалены: budget-state representability закрыт `V62/V64`, final-request/RAG binding — `V62/V64/V65`,
searchable Knowledge publication — `V63`, physical response vs protocol rejection — `V66`,
а Chat/Audit evidence получил дополнительные DB backstops в `V67/V68`.

## P1 — Governed provider entry point

DB уже не разрешает новый physical attempt без v2 final-request binding, а `STARTED` требует live fenced `PROCESSING`
turn. При этом provider adapter implementation всё равно должен оставаться недоступным для произвольного application
call-site в обход governed execution scope.

Целевой invariant:

```text
no governed execution identity
    → no credential-bearing provider I/O
```

Это желательно подкреплять package/module visibility, explicit execution capability/token и integration tests.

## P1 — ExecutionPlan recovery

Crash boundary:

```text
final request binding committed
+
ExecutionPlan committed
+
Attempt #1 ещё не создан
```

остаётся безопасно recoverable, потому что durable physical `STARTED` evidence отсутствует.

Нужен явный, хорошо протестированный application recovery path, который возобновляет именно эту операцию,
а не создаёт новую route/request identity.

## P1 — Provider token/context estimation

Governance input units (`UTF8_STRUCTURAL_UNITS_V2`) и provider-native context tokens — разные величины.

Deterministic governance accounting полезен для policy/budget envelopes, но char/structural heuristic нельзя выдавать
за математически строгий upper bound provider tokenizer. Для моделей с критичным context fit нужен provider-aware
tokenizer либо консервативный fail-closed bound.

## P1 — Pricing scheme breadth

V66 вводит explicit `pricing_scheme` и fail-closed legacy/unsupported states, однако текущая поддерживаемая схема
не покрывает весь рынок provider billing semantics.

Отдельного versioned model требуют, например:

- tiered/context pricing;
- service-class pricing;
- region/residency surcharges;
- batch/async discounts;
- tool/search/image/audio dimensions;
- cache read/write variants разных providers.

Неподдерживаемая схема должна оставаться `UNPRICED`/unverifiable, а не аппроксимироваться как flat-token pricing.

## P1 — Billing-grade settlement

`model_cost_commitment_ledger_v62` и `chat_physical_spend_v67` дают provider-evidenced accounting и unknown-liability
semantics, но это ещё не бухгалтерский/invoice settlement subsystem.

Для billing-grade слоя потребуются:

- provider invoice import/reconciliation;
- immutable settlement periods;
- currency/FX policy;
- credits/discounts/taxes;
- adjustments;
- dispute workflow;
- invoice-to-attempt matching;
- close/reopen governance;
- financial audit trail.

## P1 — Knowledge evidence retention / GC

Retrieval evidence защищает referenced chunks/generations от уничтожения, но без bounded retention/legal-hold policy
historical evidence может удерживать storage indefinitely.

Нужен lifecycle:

- retention;
- legal hold;
- privacy deletion/anonymization;
- evidence compaction/hash/excerpt policy;
- explicit policy для Answer Passport / RetrievalRun longevity.

## P1 — Mixed scanned PDF fail-closed

Для mixed PDF OCR-required pages не должны тихо подменяться native-only extraction, если OCR disabled/unavailable.

Strict `KNOWLEDGE_ONLY` не должен публиковать заведомо partial document как полностью готовый.

## P1 — Parser isolation

Extraction имеет timeout/thread bounds, но untrusted PDF/OOXML всё ещё парсится внутри application JVM.

Для hardened enterprise deployment parsing стоит вынести в отдельный worker/container sandbox boundary
с memory/CPU/time/filesystem/network limits.

## P1 — Knowledge provider attempt/cost evidence

OCR/embedding external calls пока не имеют такого же полного durable physical-attempt ledger, как Chat AI execution.

При partial batch success + process crash возможны повторные billable calls. Нужны durable operation identity,
attempt evidence и reconciliation semantics для embedding/OCR pipeline.

## P1 — Audit required transaction semantics

V68 защищает уже записанный audit intent/evidence от переписывания, но `AuditOutboxWriter.writeRequired()` в
просмотренном Java baseline использует `Propagation.REQUIRED`.

Без outer business transaction это может открыть отдельную transaction. Для строгой атомарности required audit path
желательно `MANDATORY` либо явно разделённые API:

```text
recordRequiredInCurrentTransaction(...)
recordStandaloneBestEffort(...)
```

## P1 — Production mode detection

Часть startup validators historically ориентирована на точные profile names `prod` / `production`.

Deployment profile вроде `production-eu` не должен случайно обходить production safeguards.
Предпочтительно иметь единый explicit production-mode property/profile group.

## P1/P2 — Concurrent index recovery

Interrupted `CREATE INDEX CONCURRENTLY` может оставить invalid index.

Для critical business indexes нужен release assertion по PostgreSQL catalogs (`pg_index.indisvalid` /
`indisready`), а не только `IF NOT EXISTS`.

## P2 — Human review workflow for Knowledge storage reconciliation

V63 корректно разделяет `needs_review_at` и `reviewed_at`, но сама полноценная operator workflow вокруг
`NEEDS_REVIEW` должна иметь:

- assignee/claim;
- resolution code;
- comment/evidence;
- resolved timestamp;
- audit event;
- safe replay/cleanup action.

## P2 — V58 legacy title ambiguity

До появления `title_source` невозможно доказать, был ли historical title `Новый чат` placeholder-ом или осознанным user title.

V58 deterministic backfill принимает это как migration trade-off; V59 защищает дальнейшее движение provenance назад,
но не может ретроспективно восстановить утраченное историческое намерение пользователя.

## Текущие product boundaries

Ещё не реализованы как полноценный end-to-end production layer:

- dynamic multi-provider runtime dispatch;
- versioned physical provider registry/credential reference;
- provider fallback/residency router;
- invoice-grade billing settlement;
- streaming end-to-end governance path;
- TOOLS execution plane;
- VISION execution plane;
- STRUCTURED_OUTPUT execution plane;
- OIDC/SCIM;
- generalized Policy Engine;
- DLP/data classification;
- Groups/Departments;
- enterprise connector sync + ACL mirroring;
- MCP approval gateway;
- durable autonomous Agent Runtime;
- formal compliance certification;
- formally proven HA/SLO.

---

# Позиционирование проекта

## Короткое техническое описание

> **SafeAI Desk — modular-monolith корпоративного AI Gateway с organization multi-tenancy, exactly-one-role RBAC, cookie JWT + CSRF, refresh rotation/reuse detection, user/org security epochs, durable idempotent ChatTurn с lease/fencing/ambiguity protection, versioned Model Control Plane, durable physical AI attempt evidence, Knowledge/RAG provenance + Answer Passport, transactional audit outbox и usage/pricing quality analytics.**

## Архитектурная идея

SafeAI Desk не пытается сделать LLM deterministic.

Вместо этого платформа делает deterministic/auditable **обвязку вокруг недетерминированного provider**:

```text
кто запросил
какие права были
какие данные разрешены
какая policy действовала
какая catalog version выбрана
какой execution target разрешён
какие знания использованы
какой external attempt начат
что известно о его исходе
что было сохранено как evidence
```

## Почему modular monolith

Текущий modular-monolith подходит проекту, потому что:

- tenant security state;
- ChatTurn reservation;
- quota;
- model route decision;
- audit intent;
- Knowledge provenance;
- execution evidence

требуют сильных transactional boundaries.

Переход к микросервисам имеет смысл только после появления реальной независимой scaling/ownership необходимости и должен сохранять эти invariants.

---

# Frontend

Эта редакция README фиксирует ранее изученный backend baseline и подтверждённое применение Flyway до `V70` на 09.10.2026.

По предоставленным скриншотам видно, что frontend уже содержит отдельные API clients, runtime parsers, auth context, admin audit/usage/users/organizations/model control plane UI, Chat title UI и Knowledge UI, а также значительный test layer.

Полное описание frontend contracts, компонентов и актуального file tree лучше синхронизировать после отдельной передачи текущего frontend archive/source, чтобы не переносить в README устаревшие детали из более ранних версий.

---

# Главное правило дальнейшей эволюции

Новые возможности не должны ослаблять уже существующие invariants:

```text
tenant isolation
auth revocation
idempotency
ChatTurn durability
provider ambiguity protection
route-decision provenance
physical-attempt evidence
Knowledge provenance
audit integrity
unknown != zero
database-enforced correctness
```

Целевой execution flow:

```text
reserveOrReplay()
  │
  ├─ auth / tenant / quota / rate-limit
  ├─ semantic idempotency
  ├─ immutable model route decision
  ├─ accounting/output reservation
  └─ ChatTurn PROCESSING
  ↓
commit short transaction
  ↓
Knowledge retrieval / final context preparation
  ↓
RAG context receipt (Knowledge modes)
  ↓
FINAL prepared-request hash + binding_version=2
  ↓
ExecutionPlan
  ↓
live ChatTurn lease/token fence
  ↓
Attempt STARTED + provider_call_started_at committed
  ↓
provider I/O outside long DB transaction
  ↓
terminal physical/response/pricing evidence
  ↓
fenced ChatTurn finalization
  ↓
usage / physical spend / Answer Passport / audit
```

---

**Документ актуализирован 09 октября 2026 года. Подтверждено применение Flyway `V1–V70` в интеграционной среде; текущий полный тестовый прогон пока не завершился успешно.**
