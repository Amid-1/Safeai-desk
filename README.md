<!-- SafeAI-Desk/README.md -->

# SafeAI Desk

**SafeAI Desk** — production-oriented корпоративная AI-платформа / AI Gateway для безопасного и
управляемого использования внешних AI-моделей внутри организаций.

> **Обновлено:** 17 октября 2026 года.
> **Репозиторий:** `Amid-1/Safeai-desk`.
> **Подтверждённый GitHub baseline:** `main`, Flyway V1–V72,
> merge PR #8 (`fdcff62`).
> **Подготовленный целевой набор:** V73 (Organization),
> V74 (Usage), V75 (Audit), V76 (Knowledge privacy). Наличие этих SQL в текущем
> локальном checkout и применение к PostgreSQL не подтверждены.
> **Backend:** Java 21, Spring Boot 4.1.1, модульный монолит,
> PostgreSQL, Redis, S3, Flyway, Micrometer/Prometheus.
> **Исторические тесты PR #8:** 2259; 0 failures, 0 errors,
> 3 skipped. Отдельно — 27/27 PostgreSQL/Redis tests.
> **Для патчей 13–17 октября, включая V76:** полный `clean test`, populated
> Flyway upgrade, CI и production smoke ещё не подтверждены.
> **Стадия:** production-oriented pre-1.0; не заявляются
> сертификация compliance, SLA/HA либо invoice-grade billing.

**Источник истины:** файлы текущего checkout, история Flyway,
фактическая конфигурация и результаты тестов. Подготовленный
патч, успешно применённая миграция и production deployment —
три разных состояния; README не подменяет их друг другом.

---

## Содержание

1. [Что решает проект](#что-решает-проект)
2. [Текущее состояние](#текущее-состояние)
3. [Архитектура](#архитектура)
   - [Границы модулей и архитектурный контроль](#границы-модулей-и-архитектурный-контроль)
4. [Технологический стек](#технологический-стек)
5. [Структура backend](#структура-backend)
6. [Роли и границы доступа](#роли-и-границы-доступа)
7. [Multi-tenancy и database integrity](#multi-tenancy-и-database-integrity)
8. [Security architecture](#security-architecture)
9. [Конфигурация](#конфигурация)
10. [Модуль `common`](#модуль-common)
11. [Модуль `identity`](#модуль-identity)
12. [Модуль `auth`](#модуль-auth)
13. [Модуль `user`](#модуль-user)
14. [Модуль `organization`](#модуль-organization)
15. [Модуль `ratelimit`](#модуль-ratelimit)
16. [Модуль `audit`](#модуль-audit)
17. [Модуль `usage`](#модуль-usage)
18. [Модуль `chat`](#модуль-chat)
19. [Модуль `model`](#модуль-model)
20. [Модуль `ai`](#модуль-ai)
21. [Модуль `knowledge`](#модуль-knowledge)
22. [Ключевые backend contracts](#ключевые-backend-contracts)
23. [Database / Flyway V1–V76](#database--flyway-v1v76)
24. [Observability и operational jobs](#observability-и-operational-jobs)
25. [Тестирование](#тестирование)
26. [Local development](#local-development)
27. [Production deployment notes](#production-deployment-notes)
28. [Production-readiness gaps](#текущие-ограничения-и-production-readiness-gaps)
29. [Позиционирование проекта](#позиционирование-проекта)
30. [Frontend](#frontend)
31. [Актуальность документации](#поддержание-документации-в-актуальном-состоянии)
32. [Главное правило дальнейшей эволюции](#главное-правило-дальнейшей-эволюции)
33. [Передача контекста в новый AI-чат](#передача-контекста-в-новый-ai-чат)
34. [План следующего этапа](#план-следующего-этапа)
35. [Релизный контроль V73–V76](#релизный-контроль-v73v76)

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

Основа раздела — подтверждённый baseline V71–V72.
Функционал патчей V73–V76 описан далее отдельно.
Его наличие в архиве не является доказательством внедрения,
успешного Testcontainers-прогона или работы в production.

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
- согласованный порядок блокировок `organization → user → refresh token` для Login/Refresh/Logout;
- синхронизация выдачи сессий с отключением организации;
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
- `provider_call_started_at` выставляется PostgreSQL только при допущенном durable physical
  `STARTED` (V67/V72);
- immutable `chat_turns.authorized_organization_auth_version` для каждого нового ChatTurn (V72);
- `organizations.enabled` + `auth_version` с row-lock при каждом новом физическом AI attempt (V72);
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


## Изменения, подготовленные к 17.10.2026

К базовой архитектуре добавлены **отдельные наборы изменений**.
Статус каждого набора необходимо сверить с `git status`,
`git diff --cached` и `flyway_schema_history`:

- **Organization / V73.** Согласована канонизация Unicode-
  пробелов в Java и PostgreSQL. Усилены проверка коллизий,
  ISO controls, индекса V23 и конкурентной записи.
- **Usage / V74.** Устранена потеря dirty-date при переходе
  через UTC-полночь; созданы индексы физического учёта.
  Подготовлены физические отчёты, reconciliation, строгая
  проверка полноты данных и защита тяжёлых SQL-запросов.
- **Audit / V75.** Добавлена регистрация типа события
  `AUDIT_OUTBOX_DEAD_LETTER_RECOVERED`. Подготовлен
  контролируемый recovery terminal dead-letter без обхода V68.
- **Audit P1/P2.** Подготовлены безопасное логирование
  исключений, typed sanitizer route, ограничение OFFSET,
  оптимизация справочников, алерты и тест пула/retention.
- **Production.** Приведены к реальной структуре проекта
  правила `infra/prometheus/rules/`, настройки внутренних
  Actuator endpoints и проверка deployment-конфигурации.

**Не следует считать реализованным только по описанию:**
автоматическое восстановление каждого dead-letter, доставка
уведомлений дежурному, подтверждённый billing settlement,
проведённые нагрузочные тесты и успешный Flyway upgrade.

---

# Архитектура

Проект построен как **модульный монолит**.

Это сознательный выбор: security, tenant, durability, model-governance и provenance invariants
выгодно держать в одной PostgreSQL transactional boundary, не добавляя преждевременную
распределённую согласованность.

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

`ModelExecutionAttempt` отвечает на вопрос **что физически пытались выполнить и что удалось доказать
о результате**.

---

# Границы модулей и архитектурный контроль

## Действующие правила (подтверждены срезом `main`)

- **`common` независим от остальных прикладных пакетов.** Проверяется
  `CommonDependencyArchitectureTest`.
- **`identity.session` — контракт**, не реализация инфраструктуры аутентификации.
- **`auth` владеет JWT, cookie/filter chain, BCrypt configuration и refresh-session persistence.**
- **`user` и `organization` вызывают отзыв сессий через `SessionRevocationPort`.**
- **Семантика публичных ошибок стабильна:** общий envelope в `common.exception`, модульные mappings
  в своих пакетах.
- **PostgreSQL-инварианты сильнее соглашений о пакетах:** безопасность tenant/epoch/fencing нельзя
  ослабить ради переноса классов.

## ArchUnit — PR #8

```text
backend/pom.xml
  └── com.tngtech.archunit:archunit-junit5:1.5.1 (test)

backend/src/test/java/ru/safeai/gateway/architecture/
  └── CommonDependencyArchitectureTest.java
```

Правило анализирует **производственные классы** `ru.safeai.gateway.common..` и запрещает
bytecode-зависимости на любые классы `ru.safeai.gateway..` **вне `common..`**. Это шире текстового
поиска `import`: срабатывает, например, на тип поля и сигнатуру. Оно не является доказательством
отсутствия runtime reflection, конфигурационных и транзакционных нарушений.

Проверка:

```bash
cd backend
./mvnw -Dtest=CommonDependencyArchitectureTest test
./mvnw test
```

**Negative control:** временный класс `common.archprobe.ArchUnitBoundaryProbe` с полем типа
`auth.security.PasswordEncodingConfiguration` привел к ожидаемой `Architecture Violation`.
Временный файл и скомпилированный `.class` удалены, повторный positive test прошел.

## История разделения границ — восемь merged PR

- **[#1](https://github.com/Amid-1/Safeai-desk/pull/1)**
  - **Изменение:** Common/error boundary
  - **Архитектурный результат:** Общая error infrastructure отделена от module-specific concerns
- **[#2](https://github.com/Amid-1/Safeai-desk/pull/2)**
  - **Изменение:** Identity session boundary
  - **Архитектурный результат:** Контракт `SessionRevocationPort` / `SessionRevocationReason`
    вынесен из auth implementation
- **[#3](https://github.com/Amid-1/Safeai-desk/pull/3)**
  - **Изменение:** Common/JWT boundary
  - **Архитектурный результат:** `JwtService` принадлежит `auth.service`, сохранены
    security/concurrency semantics
- **[#4](https://github.com/Amid-1/Safeai-desk/pull/4)**
  - **Изменение:** Authentication converter
  - **Архитектурный результат:** `SafeAiJwtAuthenticationConverter` принадлежит `auth.security`
- **[#5](https://github.com/Amid-1/Safeai-desk/pull/5)**
  - **Изменение:** Auth HTTP error boundary
  - **Архитектурный результат:** Auth/Spring Security entry points and access-denied handler
    перенесены в `auth.security`
- **[#6](https://github.com/Amid-1/Safeai-desk/pull/6)**
  - **Изменение:** JWT codec/key ring
  - **Архитектурный результат:** `JwtCodecConfiguration`, `JwtRsaKeyRing` и профильный тест
    перенесены в `auth`
- **[#7](https://github.com/Amid-1/Safeai-desk/pull/7)**
  - **Изменение:** Password encoding ownership
  - **Архитектурный результат:** `PasswordEncodingConfiguration` и профильный тест перенесены в
    `auth.security`
- **[#8](https://github.com/Amid-1/Safeai-desk/pull/8)**
  - **Изменение:** ArchUnit common boundary
  - **Архитектурный результат:** Добавлена автоматическая архитектурная проверка `common`


PR #8: исходный коммит `103c587`, merge-коммит `fdcff62`; `main` синхронизирован с `origin/main`,
рабочая директория чистая по последнему предоставленному выводу `git status -sb`.

**Эти PR не означают, что остальные пакеты уже имеют автоматические правила независимости.**
Не создавать новые правила по догадкам: сначала инвентаризировать исходящие зависимости и
Spring bean graph, затем выбрать допустимую границу и проверить тестами.

## Правило безопасного рефакторинга

Малые изолированные PR, сохранение public API/JSON/errors/configuration, тесты до и после
перемещения, отсутствие циклов, package visibility без необоснованного `public`, сохранение
`@Transactional`/event/cache semantics и DB constraints. Не перемещать класс только из-за
его имени; учитываются фактические consumers, ownership, shared contracts и startup wiring.

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
- JUnit Jupiter / Spring Boot Test (версии берутся из актуального BOM);
- ArchUnit 1.5.1 (`archunit-junit5`, test scope);
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
Для среза `fdcff62` проверены также `postgresql.version=42.7.14`,
`snappy-java.version=1.1.10.11` и `testcontainers.version=2.0.5`.

Frontend baseline из `frontend/package.json`: React 19.2.7, React Router 7.18.3,
TypeScript 6.0.3, Vite 8.0.16, Vitest 4.1.10;
**Node.js 24.x и npm 11.x** (`engines`/`packageManager`). Эти номера отражают именно
проверенный manifest, а не отдельную проверку безопасности supply chain.

## Persistence philosophy

- `spring.jpa.hibernate.ddl-auto=validate`;
- application не создаёт production schema через Hibernate;
- schema evolution — только Flyway;
- критические tenant/security/durability invariants, где практично, подкреплены PostgreSQL
  FK/UNIQUE/CHECK/trigger;
- applied migrations не редактируются задним числом;
- local seed отделён от production migrations.

---

# Структура backend

### Краткая карта backend

```text
backend/
├── src/main/java/ru/safeai/gateway/
│   ├── ai/
│   ├── audit/
│   ├── auth/
│   ├── chat/
│   ├── common/
│   ├── identity/
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
│   │   ├── migration/         # V1 ... V76 (цель)
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
    │   └── ru/safeai/gateway/architecture/
    │       └── CommonDependencyArchitectureTest.java
    └── resources/
```

### Подробная структура backend и Flyway

Дерево включает исходный baseline и подготовленные файлы
патчей V73–V76. Оно не является результатом `ls` твоего
локального checkout и не подтверждает их коммит или запуск.

```text
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
│   │   │               ├── identity/
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
│   │       │       ├── V71__usage_dirty_dates_and_recalculation_queue.sql
│   │       │       ├── V72__organization_auth_epoch_physical_attempt_gate.sql
│   │       │       ├── V73__organization_unicode_whitespace_canonicalization.sql
│   │       │       ├── V74__usage_midnight_dirty_date_and_physical_indexes.sql
│   │       │       ├── V75__audit_dead_letter_recovery_event_type.sql
│   │       │       ├── V76__knowledge_retrieval_query_privacy.sql
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

Основные потоки взаимодействия (логические, **не исчерпывающий граф Java imports**):

```text
Потребители / оркестраторы                        Общие контракты
    auth, user, organization, audit, ... ─────────────► common
    user, organization ───────────────────────────────► identity.session (порт)
    auth.service.UserSessionRevocationService ───────► identity.session (реализация порта)

    auth ───► user / organization persisted security state
    chat ───► model governance ───► ai physical execution ───► provider
         └──► knowledge retrieval / RAG
         └──► quota / audit / usage evidence
```

Стрелка означает **использование контракта/ответственности**, а не утверждение о полном наборе
Java-импортов. Фактический межпакетный граф для каждого следующего рефакторинга следует
снимать с текущих исходников и проверять отдельно. Зафиксированное автоматическое
ограничение сегодня только одно: **`common` не зависит от других пакетов `ru.safeai.gateway`**.

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

Цель — не полагаться только на service-layer checks и не допустить cross-tenant/provenance mismatch
даже при application bug или race.

---

# Security architecture

## Browser authentication

```text
access token  -> HttpOnly cookie
refresh token -> HttpOnly cookie
XSRF-TOKEN    -> readable cookie
```

JWT не должен храниться в `localStorage`. Access principal
не должен включать email/PII только ради удобства аудита:
actor snapshot разрешается отдельно из доверенных данных.

Unsafe browser methods передают CSRF header:

```text
X-XSRF-TOKEN
```

После login CSRF state ротируется.

## JWT contract

Access token содержит/валидирует identity и security epoch data:

- subject;
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

JWT validity не ограничивается проверкой криптографической подписи: runtime также проверяет текущий
user/org security state.

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
hash + non-locking identity lookup (only routing IDs)
  ↓
organization FOR SHARE
  ↓
user PESSIMISTIC_WRITE
  ↓
refresh token PESSIMISTIC_WRITE
  ↓
RECHECK token hash/owner/expiry/revocation/family + current epochs
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

## V72 — единый PostgreSQL lock ordering для сессий

Одновременное создание сессии, refresh и отключение tenant — **конкурентные транзакции**. Текущий
патч задаёт общий порядок захвата строк и повторную проверку security state **после** блокировок.

- **Login**
  - **Порядок и защита:** `organizations FOR SHARE` → `users PESSIMISTIC_WRITE` → запись
    login/refresh session
- **Refresh**
  - **Порядок и защита:** неблокирующее чтение только ID владельца токена → `organizations FOR
    SHARE` → `users PESSIMISTIC_WRITE` → `refresh_tokens PESSIMISTIC_WRITE` → повторная валидация
- **Logout**
  - **Порядок и защита:** согласованный `organization → user → token` перед изменением refresh state
- **Organization Disable**
  - **Порядок и защита:** `organizations PESSIMISTIC_WRITE` → `enabled=false`, `auth_version++` →
    отзыв refresh sessions
- **User security mutation**
  - **Порядок и защита:** `organizations PESSIMISTIC_WRITE` → `users PESSIMISTIC_WRITE` → связанный
    отзыв сессий


Правило **organization → user → token** препятствует обратному захвату блокировок по этим путям.
Предварительный lookup ID токена **не является авторизацией**: token hash, владелец, семейство,
состояние, `enabled`, `token_version` и `auth_version` проверяются на актуальных данных под
блокировками. Блокировка `FOR SHARE` удерживается до commit соответствующей транзакции и конфликтует
с disable `FOR UPDATE` той же organization.

После `Disable` любой ранее выпущенный access token должен проходить runtime-проверку
`enabled`/epoch; наличие уже подписанного JWT само по себе не сохраняет доступ. Старый refresh token
не может получить новую сессию через старую версию безопасности. Повторный Enable не делает старый
epoch актуальным.

Это **контракт кода и базы**, а не доказательство отсутствия всех deadlock во всей системе:
необходимы реальные PostgreSQL concurrency-тесты, анализ дополнительных lock paths,
timeout/serialization diagnostics и проверка поведения при параллельных `Refresh` одной семьи.


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

## Production environment detection

`common.security.ProductionEnvironmentCondition` включает production-only invariant
checks не только при `prod`/`production`, но и при профилях вида `prod-k8s`,
`production-eu`; предпочтительный явный сигнал — `safeai.environment=production`.
Проверено по текущему классу. Новые профили и механизм включения этих validators
не должны случайно обходить production hardening. Старый пункт README о распознавании
**только точных** имен профилей устранен и не считается известным дефектом среза.

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

Ниже перечислены ключевые configuration groups, подтверждённые в просмотренном backend. Конкретный
production secret/value задаётся deployment environment, а не README.

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

Security correctness не должна зависеть только от cached value; invalidation/fallback semantics
должны оставаться fail-safe.

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

При работе с `UsageJdbcClients` учитываются изоляция ресурсов аналитических запросов и инварианты
пула подключений; конкретные настройки/бюджеты пула следует сверять с `UsageConfiguration`,
properties и текущими runtime profiles. Не считать схему пулов доказанной только по одному названию
класса.

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

- bounded concurrent `worker-threads` (по умолчанию 1, диапазон 1–8);

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

Baseline embedding dimension фиксирован текущей реализацией; OpenAI production configuration pins
allowed endpoint вместо произвольного outbound host.

### OCR

OCR provider configuration включает:

- provider;
- endpoint;
- API key;
- explicit allowed hosts;
- minimum native chars/page threshold;
- connect/read timeout;
- maximum response size.

Credential-bearing property records редактируют `toString()` так, чтобы API keys не утекали в
logs/diagnostics.

---

# Модуль `common`

Package:

```text
ru.safeai.gateway.common
```

`common` — cross-cutting infrastructure layer. В нем не должна жить business-логика конкретного
tenant domain. Это **проверяемая граница**, а не папка для всего, что повторно используется:
внутренний код `common..` не должен зависеть от других пакетов `ru.safeai.gateway..`. ArchUnit
контролирует это автоматически.

После PR #1–#8 из `common` вынесены auth-specific исключения и handlers, JWT runtime/key ring, auth
filter converter и BCrypt configuration. В `common.security` оставлены действительно совместно
используемые `SafeAiUserPrincipal`, `SystemRole`, `AccessTokenSubject`, `JwtProperties`,
CORS/IP/request-id, management chain и production startup invariants. `JwtProperties` намеренно
остается здесь как shared configuration contract; это **не** повод возвращать сюда `JwtService` или
crypto implementation.

## Структура

```text
common/
├── config/
│   ├── SchedulingConfiguration
│   └── TimeConfiguration
├── exception/
│   ├── ApiErrorCode
│   ├── ApiErrorResponse
│   ├── ApiErrorResponseFactory
│   ├── ApiErrorResponseWriter
│   ├── ApiException
│   ├── ApiExceptionResponseSupport
│   ├── BadRequestException
│   ├── ConflictException
│   ├── ForbiddenOperationException
│   ├── GlobalExceptionHandler
│   ├── README.md
│   ├── ResourceNotFoundException
│   └── ValidationErrorExtractor
├── pagination/
│   └── StablePageableNormalizer
├── persistence/
│   ├── DatabaseConstraintClassifier
│   └── PostgresAdvisoryLockExecutor
├── platform/
│   ├── PlatformProperties
│   └── PlatformPropertiesConfiguration
├── security/
│   ├── AccessTokenSubject
│   ├── ClientIpProperties
│   ├── ClientIpResolver
│   ├── CorsProperties
│   ├── JwtProperties
│   ├── ManagementSecurityConfig
│   ├── package-info.java
│   ├── ProductionEnvironmentCondition
│   ├── ProductionSecurityInvariantValidator
│   ├── RequestIdFilter
│   ├── RoleAuthorityMapper
│   ├── SafeAiUserPrincipal
│   ├── SecurityIdentityValidator
│   ├── SecurityPropertiesConfiguration
│   └── SystemRole
└── validation/
    └── UnicodeValidationSupport
```

## Основные контракты

### `TimeConfiguration`

Предоставляет injectable UTC `Clock`, чтобы business timestamps и lease/retry/retention tests были
воспроизводимыми.

### `StablePageableNormalizer`

Централизует bounded pagination/sort normalization и снижает риск расхождения paging semantics между
controllers.

### `DatabaseConstraintClassifier`

Преобразует ожидаемые PostgreSQL constraint failures в domain-level conflict semantics вместо
случайного generic 500.

### `PostgresAdvisoryLockExecutor`

Централизует session-level PostgreSQL advisory lock:

- acquire;
- action;
- unlock;
- best-effort abort/close небезопасного pooled connection при unlock failure.

Используется jobs, которым нужна кластерная singleton-coordination без отдельного lock service.

### `SafeAiUserPrincipal`

Security principal хранит минимально необходимую identity/security информацию и не должен содержать
password credentials.

### `ProductionSecurityInvariantValidator`

Fail-fast startup boundary для явно небезопасных production settings.

---

# Модуль `identity`

Package: `ru.safeai.gateway.identity`.

```text
identity/
└── session/
    ├── SessionRevocationPort.java
    └── SessionRevocationReason.java
```

Этот пакет — **узкая контрактная граница identity/tenancy**, а не новая реализация JWT или хранилище
refresh-token rows.

`SessionRevocationPort` задает две операции: `revokeAllForUser(UUID, SessionRevocationReason)` и
`revokeAllForOrganization(UUID, SessionRevocationReason)`. Модули `user` и `organization` зависят от
порта,
а реализация `auth.service.UserSessionRevocationService` владеет фактическим отзывом refresh-сессий
и
маппингом причин в auth-specific `RefreshTokenRevocationReason`. Реализация использует
`@Transactional(propagation = MANDATORY)`: бизнес-операция должна открыть транзакцию до вызова
порта.

`SessionRevocationReason` — общие security-mutation reasons (`PASSWORD_RESET`, `ROLE_CHANGED`,
`EMAIL_CHANGED`, `USER_DISABLED`, `ORGANIZATION_DISABLED`, `SECURITY_STATE_CHANGED`,
`ADMIN_REVOKED`). Не добавлять сюда details JWT/cookie/refresh-token persistence: они принадлежат
`auth`.

В `identity` на данном срезе **два Java-класса**; расширять пакет только при наличии нового
реального
межмодульного identity-контракта. Рекомендуемый следующий защитный тест —
`identity..` не зависит от `auth`, `user`, `organization` и прочих прикладных модулей; правило
сначала
нужно проверить на реальном bytecode/импортах.

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
├── controller/
│   ├── AuthController
│   └── CsrfController
├── dto/
│   ├── CsrfTokenResponse
│   ├── CurrentUserResponse
│   └── LoginRequest
├── entity/
│   ├── RefreshTokenEntity
│   └── RefreshTokenRevocationReason
├── exception/
│   ├── AuthExceptionHandler
│   ├── AuthServiceUnavailableException
│   ├── ExpiredRefreshTokenException
│   ├── InvalidRefreshTokenException
│   └── RefreshTokenReuseDetectedException
├── repository/
│   └── RefreshTokenRepository
├── security/
│   ├── AccessCookieAuthenticationFilter
│   ├── BearerAuthenticationEntryPoint
│   ├── CsrfCookieFilter
│   ├── CustomUserDetailsService
│   ├── HstsProperties
│   ├── JwtCodecConfiguration
│   ├── JwtRsaKeyRing
│   ├── package-info.java
│   ├── PasswordEncodingConfiguration
│   ├── RestAccessDeniedHandler
│   ├── RestAuthenticationEntryPoint
│   ├── SafeAiJwtAuthenticationConverter
│   ├── SecurityConfig
│   ├── SpaCsrfTokenRequestHandler
│   └── UserStatusFilter
├── service/
│   ├── AuthAuditRequestSupport
│   ├── AuthCookieConfigurationValidator
│   ├── AuthCookieProperties
│   ├── AuthCookieService
│   ├── AuthEventService
│   ├── AuthService
│   ├── JwtService
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
└── validation/
    ├── Utf8ByteLength
    └── Utf8ByteLengthValidator
```

## Собственность классов после PR #1–#8

`auth.exception` содержит `AuthExceptionHandler` и исключения недействительного, истекшего и
повторно
использованного refresh token, а также недоступности auth infrastructure. Они используют общий
`common.exception.ApiErrorResponse` contract, но не должны находиться в `common`.

`auth.security` владеет `JwtCodecConfiguration`, `JwtRsaKeyRing`,
`SafeAiJwtAuthenticationConverter`, `BearerAuthenticationEntryPoint`,
`RestAuthenticationEntryPoint`, `RestAccessDeniedHandler`, `PasswordEncodingConfiguration`.
`auth.service.JwtService` владеет JWT runtime. BCrypt остается **strength 12**,
новые password hashes используют `{bcrypt}`; поддержка legacy unprefixed BCrypt контролируется
`safeai.security.password.allow-legacy-unprefixed-bcrypt` и не должна выключаться без проверки
существующих учетных записей.

Public JSON envelope остается совместным контрактом в `common.exception`, а все auth-specific
status/error code mappings принадлежат `auth.exception.AuthExceptionHandler` и security handlers.

## Login flow

```text
POST /api/auth/login
  ↓
login rate limit
  ↓
credentials validation
  ↓
initial user + organization security checks
  ↓
organization FOR SHARE → user PESSIMISTIC_WRITE
  ↓
repeat enabled/tokenVersion/authVersion checks under locks
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
refresh cookie → hash
  ↓
non-locking lookup of token routing identity
  ↓
organization FOR SHARE → user PESSIMISTIC_WRITE
  ↓
refresh token PESSIMISTIC_WRITE
  ↓
repeat hash/owner/state/family/expiry + enabled/version checks
  ↓
rotate predecessor; persist successor (same transaction)
  ↓
sign access JWT + set new cookies
```

Reuse detection прекращает token family.

## Logout

Logout отзывает server-side refresh state и очищает browser cookies; audit subject snapshot должен
быть получен до потери необходимого identity context.

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
├── entity/
│   ├── RoleEntity
│   └── UserEntity
├── event/
│   └── UserSecurityStateChangedEvent
├── exception/
│   ├── UserExceptionHandler
│   └── UserVersionConflictException
├── mapper/
│   └── UserRoleMapper
├── repository/
│   ├── RoleRepository
│   └── UserRepository
├── service/
│   ├── UserAccessPolicy
│   ├── UserCommandOperations
│   ├── UserEntityAccess
│   ├── UserManagementProperties
│   ├── UserQueryOperations
│   ├── UserResponseMapper
│   ├── UserRoleAssignmentResolver
│   ├── UserSecurityStatus
│   ├── UserService
│   ├── UserStatusCacheInvalidationListener
│   ├── UserStatusCacheProductionInvariantVerifier
│   ├── UserStatusCacheProperties
│   └── UserStatusCacheService
└── validation/
    ├── BcryptUtf8Length
    ├── BcryptUtf8LengthValidator
    ├── PasswordPolicy
    ├── PasswordValidator
    ├── UserEmailNormalizer
    └── ValidPassword
```

## Внутренние границы и ответственность

`UserService` координирует публичные операции; выделены package-private `UserCommandOperations`
и `UserQueryOperations`, `UserAccessPolicy`, `UserEntityAccess`, `UserRoleAssignmentResolver`,
`UserResponseMapper`. `UserExceptionHandler` и `UserVersionConflictException` локальны для `user`.
Security-sensitive commands зависят от `identity.session.SessionRevocationPort`, не от
`auth.service.UserSessionRevocationService` напрямую. Это важная граница PR #2.

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

Security mutation публикует `UserSecurityStateChangedEvent`, после чего cache инвалидируется
listener-ом.

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
├── controller/
│   └── OrganizationController
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
├── entity/
│   └── OrganizationEntity
├── event/
│   └── OrganizationSecurityStateChangedEvent
├── exception/
│   ├── OrganizationExceptionHandler
│   └── OrganizationVersionConflictException
├── repository/
│   ├── OrganizationImpactQueryRepository
│   └── OrganizationRepository
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

`OrganizationExceptionHandler` и `OrganizationVersionConflictException` принадлежат модулю
`organization`, а не глобальной `common.exception`. Внутренние command/query operations отделены
от публичного `OrganizationService`; отзыв сессий проходит через
`identity.session.SessionRevocationPort`.

## Platform organization

Canonical platform organization имеет отдельную semantic role и не должна обрабатываться как обычный
tenant.

`PlatformOrganizationInvariantVerifier` проверяет startup/runtime assumptions о protected platform
organization.

## Organization naming — V73

`OrganizationNameNormalizer` задаёт правила имени и проверяет
запрещённые управляющие символы. В целевом патче Java и БД
одинаково схлопывают 24 ASCII/Unicode пробельных символа,
включая NBSP, NNBSP, разделители строк и абзацев.

- PostgreSQL `lower()` остаётся источником истины для
  уникального `normalized_name` (с учётом коллации БД).
- Проверяются потенциальные коллизии существующих tenant names.
  Миграция останавливается, но не объединяет организации.
- `SHARE ROW EXCLUSIVE` блокирует конкурентную запись на
  время preflight/rewrite, а последующий DDL берёт свой lock.
- Проверяются действующие ограничения V20 и уникальный,
  `VALID`/`READY` индекс V23.
- Нормализованные имена проверяются на C0/C1 controls,
  пустоту, SQL-длину и Java UTF-16 длину не более 255.
- При реальном изменении записи увеличивается `version`,
  но не `auth_version`: переименование не выдаётся за
  security-significant disable/re-enable.
- `normalizeForConfirmation()` не подменяет уникальность
  `lower(canonical_organization_name(...))`.

Миграция: `V73__organization_unicode_whitespace_canonicalization.sql`.
Применять только после теста на копии populated БД,
предварительной проверки имён и окна блокировок.

## Enable / disable

Disable/enable используют optimistic versioning.

Security-significant organization mutation увеличивает:

```text
organization.auth_version
```

и публикует `OrganizationSecurityStateChangedEvent`.

Это инвалидирует старые auth epochs. Re-enable не должен «оживлять» старые refresh sessions.

### V72: Disable как security boundary, а не только административный флаг

При отключении организация блокируется на запись до изменения `enabled` и увеличения `auth_version`.
Login/Refresh запрашивают совместимую с другими читателями, но конфликтующую с Disable блокировку
`FOR SHARE`; физический AI STARTED использует такой же organization fence в SQL-триггере.

- Commit Disable запрещает **новый** `ModelExecutionAttempt STARTED`, пока organization отключена.
- После Enable устаревший `ChatTurn` с прежним authorized epoch всё равно не проходит V72 gate.
- Физические вызовы, **допущенные до Disable**, не удаляются и не переписываются: нужно
  завершить/сохранить evidence, включая неопределённый outcome.
- Внешний HTTP-запрос, разрешённый до Disable, не гарантированно остановится: DB fence определяет
  момент **авторизации STARTED**, не момент отправки сетевых байтов.

Проверять только `enabled` в Java перед отправкой запрещено считать строгой защитой: между Java
check и DB INSERT возникает TOCTOU. База повторно проверяет identity/epoch под row lock.

---
# Модуль `ratelimit`

Package:

```text
ru.safeai.gateway.ratelimit
```

## Структура

```text
ratelimit/
├── exception/
│   ├── RateLimitExceededException
│   ├── RateLimitExceptionHandler
│   ├── RateLimitUnavailableException
│   └── RateLimitUnavailableLogger
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

Atomic fixed-window operation выполняется Lua script-ом. Для dual-counter операций критична
**предварительная проверка обоих Redis-значений до первой записи**: Redis Lua сериализует вызовы, но
при ошибке **не откатывает** уже выполненные команды.

```text
validate counter #1 + counter #2
        ↓
validate update preconditions / limits
        ↓
INCR and initialize TTL where applicable
        ↓
limit decision / optional marker
```

`tryIncrementBoth()` резервирует оба bucket только при допустимости запроса;
`incrementBothAndCheck()` сохраняет семантику учёта попытки. Интеграционные тесты отдельно проверяют
конкуренцию, TTL и отсутствие частичных записей на повреждённых значениях. В Redis Cluster ключи
одной Lua-операции должны находиться в одном hash slot.

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

Auth- и rate-limit-specific failure classes живут в `ratelimit.exception`, включая
`RateLimitExceededException`, `RateLimitUnavailableException`, `RateLimitUnavailableLogger`
и `RateLimitExceptionHandler`. Совместимый публичный API error envelope обеспечивает
`common.exception`.

## Production fail-fast rate-limit invariants

Login и Refresh limiter обязательны в production. Для AI
отключение требует явного исключения политики
`allow-unlimited-ai-traffic=true`. Это аварийный escape hatch,
а не рекомендуемый штатный режим.

Двойные Redis Lua-операции используют hash tags, чтобы все
ключи одного вызова находились в одном Redis Cluster slot.
HMAC защищает сырой email/IP от попадания в названия ключей.
Счётчики и TTL валидируются, а `Retry-After` округляется вверх.

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
├── controller/
│   ├── AuditController
│   ├── AuditDirectoryController
│   └── AuditOutboxRecoveryController
├── details/
│   ├── AiResponseAuditDetails
│   ├── AuditDeadLetterRecoveryDetails
│   ├── AuditDetails
│   ├── ChatTurnAuditDetails
│   ├── RateLimitAuditDetails
│   └── SecurityRefreshReuseAuditDetails
├── dto/
│   ├── AuditActorDirectoryResponse
│   ├── AuditEventCursorResponse
│   ├── AuditEventFilter
│   ├── AuditEventPageResponse
│   ├── AuditEventResponse
│   └── AuditTargetOrganizationDirectoryResponse
├── entity/
│   ├── AuditEventEntity
│   └── AuditOutboxEntity
├── listener/
│   └── RateLimitAuditListener
├── model/
│   └── AuditActor
├── repository/
│   ├── AuditDirectoryQueryRepository
│   ├── AuditEventCriteria
│   ├── AuditEventCursorRepository
│   ├── AuditEventCursorRepositoryImpl
│   ├── AuditEventRepository
│   └── AuditOutboxRepository
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
│   ├── AuditOutboxRecoveryService
│   ├── AuditOutboxScheduler
│   ├── AuditOutboxWriter
│   ├── AuditRetentionBatchService
│   ├── AuditRetentionLockService
│   ├── AuditRetentionService
│   └── BestEffortStandaloneAuditService
├── spi/
│   └── AuditTargetOrganizationSnapshotProvider
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

## Typed details и обязательный Audit

Для `AuditDetails` должен использоваться
`AuditCommandFactory.createTyped(...)` и `sanitizeTyped()`;
legacy `Map<String, Object>` сохраняет отдельный строгий
unstructured sanitizer. Иначе разрешённые поля типизированного
security evidence могут быть ошибочно вырезаны.

`AuditEventService.recordPrincipalSnapshot(...)` использует
ту же typed boundary. `AuditEventServiceTest` должен мокировать
`createTyped(...)`, не `create(Map)`; `MANDATORY`-аудит
по-прежнему не проглатывает ошибки сохранения.

Best-effort standalone остаётся отдельным `REQUIRES_NEW`
контрактом: ошибка аудита не должна ломать business flow,
но такой вызов недопустим для обязательного evidence.

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

## Контролируемое восстановление dead-letter — V75

Подготовленный endpoint:

```http
POST /api/admin/audit-events/outbox/dead-letters/{eventId}/recover
```

- Доступ только `SUPER_ADMIN` с дополнительной сервисной проверкой.
- Исходная terminal outbox row берётся под `FOR UPDATE`.
- Original event переносится идемпотентно с тем же UUID.
- Отдельный required typed audit фиксирует действие оператора.
- Terminal row удаляется только вместе с успешной фиксацией
  события и evidence восстановления в одной транзакции.
- Не выполняется `UPDATE dead_lettered_at` и не снимаются
  защитные триггеры неизменяемости V68.
- V75 регистрирует **только тип** события в справочнике;
  без Java recovery service/controller это не рабочая функция.

Не выполнять recovery до устранения root cause. Сохранять
старое `occurred_at`: обычные правила retention продолжают
действовать и для восстановленной записи.

## Query

ADMIN scope повторно ограничивается tenant policy на service/query layer.

SUPER_ADMIN получает global/platform scope.

Cursor pagination использует стабильную пару:

```text
(createdAt, id)
```

Подготовленный `AuditEventQueryService` ограничивает глубину
старой OFFSET-пагинации: максимум 10 000 пропущенных строк,
HTTP 400 до тяжёлого запроса. Cursor endpoint остаётся.

Исторические справочники используют выборку ID и latest-row;
`AuditDirectoryService` ограничивает выполнение до 5 секунд.
Это защита ресурса, но не доказательство дешёвого
`DISTINCT`/substring-поиска: нужен `EXPLAIN (ANALYZE,
BUFFERS)` и нагрузочный профиль реальной БД.

## Retention

Retention выполняется bounded batches.

Для межинстансовой координации используется PostgreSQL
advisory lock. Session lock удерживает отдельное соединение,
а `REQUIRES_NEW` batch может потребовать второе. Требуются
настройка Hikari capacity, lock/timeout monitoring и
нагрузочная проверка `AuditRetentionPoolLoadIT` на стенде.
Наличие теста в пакете не означает, что он был выполнен.

## Обязательный и самостоятельный аудит: проверенный контракт

`AuditOutboxWriter.writeRequiredInCurrentTransaction(AuditCommand)` использует
`@Transactional(propagation = Propagation.MANDATORY)`: без активной business transaction
вызов должен завершиться отказом, а не незаметно создать независимую транзакцию.
`AuditEventService.record(...)` вызывает именно этот метод и сохраняет durable audit intent
в рамках транзакции caller. `saveAndFlush()` не равнозначен `COMMIT` caller transaction.

`AuditOutboxWriter.writeStandaloneRequired(AuditCommand)` использует `REQUIRES_NEW`:
это отдельный явно выбранный путь для independent audit intent, когда вызов идет через
Spring transactional proxy. Оба пути нельзя смешивать ради «удобства» или ослаблять
failure/rollback семантику. Этот контракт уже реализован в проверенном `main` и
**не является открытой задачей на изменение `REQUIRED → MANDATORY`**.

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
│   ├── UsageHikariPoolInvariantVerifier
│   ├── UsageJdbcClients
│   └── UsageProperties
├── controller/
│   ├── UsageController
│   ├── UsagePhysicalSpendController
│   └── UsageRebuildController
├── dto/
│   ├── PagedResponse
│   ├── UsageCostSummary
│   ├── UsageDailySummaryResponse
│   ├── UsageDataQualityResponse
│   ├── UsageDateFilter
│   ├── UsageDateModelFilter
│   ├── UsageModelSummaryResponse
│   ├── UsagePageRequest
│   ├── UsagePhysicalReconciliationResponse
│   ├── UsagePhysicalSpendResponse
│   ├── UsageProblemModelResponse
│   ├── UsageResponseSummary
│   ├── UsageSummaryInvariants
│   ├── UsageSummaryResponse
│   ├── UsageTokenSummary
│   └── UsageUserSummaryResponse
├── repository/
│   ├── JdbcUsageQueryRepository
│   ├── UsageDirtyDateRepository
│   ├── UsageFactQueryBuilder
│   ├── UsageFactSql
│   ├── UsageInstantRange
│   ├── UsagePhysicalSpendRepository
│   ├── UsageQueryCriteria
│   ├── UsageQueryPlan
│   ├── UsageQueryRepository
│   ├── UsageRollupStateRepository
│   ├── UsageRowMapperSupport
│   └── UsageSqlFragments
└── service/
    ├── UsageDirtyDateProcessor
    ├── UsageForcedRebuildService
    ├── UsagePhysicalSpendService
    ├── UsageQueryService
    ├── UsageReportExecutor
    ├── UsageRollupDayProcessor
    └── UsageRollupScheduler
```

`UsageRebuildController` входит в текущую структуру `usage.controller`:
`POST /api/admin/usage/rebuild?date=YYYY-MM-DD` возвращает `202 Accepted`, а сам controller
защищен `@PreAuthorize("hasRole('SUPER_ADMIN')")`. Внутренний `UsageForcedRebuildService`
обрабатывает запрос; состояние очереди, ограничения выполнения и audit должны проверяться
по его реализации и тестам при доработках.
`UsageHikariPoolInvariantVerifier` и `UsageJdbcClients` — конфигурационные границы
аналитических JDBC-пулов.

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

## Физические расходы и сверка — пакет Usage V74

Подготовлены отдельные API:

```http
GET /api/admin/usage/physical-attempts
GET /api/admin/usage/physical-attempts/reconciliation
```

Оба используют `dateFrom`/`dateTo` как диапазон `Instant`.
`ADMIN` ограничивается своим tenant, `SUPER_ADMIN` может
читать глобальный scope. Сервис повторно проверяет доступ.

- `UsagePhysicalSpendRepository` агрегирует физические
  `model_execution_attempts` по времени `started_at`,
  а не по времени записи chat message.
- В учёт входит **каждый** физический attempt, включая retry;
  известные затраты считаются только для `PRICED`/`FREE`.
- `STARTED`/`AMBIGUOUS` и недостаточная usage/pricing evidence
  отражаются в unverifiable counters; **unknown != 0**.
- `costComplete` и `usageComplete` согласованы со счётчиками,
  неконсистентные флаги отвергаются DTO.
- Reconciliation выбирает планы по попыткам в интервале,
  но сравнивает **все попытки каждого выбранного плана**
  с сохранённой ценой assistant message.
- `matched`, `mismatched` и `unverifiable` нельзя подменять
  успешностью chat/провайдера или данными для инвойса.

Новые запросы остаются evidence-backed финансовой аналитикой,
а не invoice-grade reconciliation с поставщиком.

## Live + rollup

- source-of-truth факты читаются из persisted chat/AI completion metadata;
- daily aggregation работает в UTC;
- rollup ускоряет длинные отчёты;
- rollup имеет watermark/reconciliation;
- recent/incomplete range может дочитываться live;
- max live range защищает DB от неограниченного тяжёлого запроса.

## V71 — Dirty-date recalculation и восстановление актуальности сводок

Историческая дневная сводка не обязательно остаётся актуальной навсегда: сообщение или физический AI
attempt может получить окончательный статус позже даты первоначального rollup. Поэтому механизм V71
отделяет **последовательный backfill** от **повторного расчёта уже закрытых UTC-дней**.

```text
persisted usage / late-arriving or corrected evidence
       │
       ▼
usage dirty-date tracking (V71)
       │
       ▼
UsageDirtyDateRepository ── pending UTC dates / queue state
       │
       ▼
UsageRollupScheduler ───── PostgreSQL advisory lock
       │
       ├── contiguous backfill → UsageRollupDayProcessor(rebuildDay, true)
       ├── recent reconciliation → rebuildDay(date, false)
       └── dirty-date processing → UsageDirtyDateProcessor
                                   │
                                   ▼
                               daily rollups
```

### Компоненты и ответственность

- **`UsageRollupStateRepository`**
  - **Назначение:** Чтение `last_completed_date`, поиск первого UTC-дня источников, монотонное
    продвижение watermark, запуск под PostgreSQL advisory lock
- **`UsageDirtyDateRepository`**
  - **Назначение:** Доступ к очереди дат, требующих повторной обработки; репозиторий должен
    допускать Spring CGLIB-проксирование, если используется exception translation
- **`UsageDirtyDateProcessor`**
  - **Назначение:** Обработка помеченных дат с учётом состояния очереди; служит отдельной границей
    от обычного backfill
- **`UsageForcedRebuildService`**
  - **Назначение:** Обработка принудительного пересчета; доступен защищенный
    `UsageRebuildController`, `POST /api/admin/usage/rebuild?date=YYYY-MM-DD` (SUPER_ADMIN, HTTP
    202)
- **`UsageRollupDayProcessor`**
  - **Назначение:** Фактическая перестройка дневных агрегатов; `advanceWatermark` определяет,
    продвигать ли последовательный watermark
- **`UsageRollupScheduler`**
  - **Назначение:** Планирование ограниченных порций backfill, lookback reconciliation и dirty-date
    обработки в рамках межинстансовой координации
- **`UsageJdbcClients`**
  - **Назначение:** Конфигурация JDBC-клиентов для usage paths; параметры исполнения запросов и
    подключения следует проверять по коду/configuration


### Watermark: точный контракт

- `findLastCompletedDate()` возвращает последнюю *последовательно* обработанную дату или `null` до
  инициализации.
- `findEarliestUsageDate()` берёт минимум по UTC-дням сообщений `ASSISTANT` и `AMBIGUOUS` chat turns
  с `completed_at`; это шире прежнего assistant-only поиска.
- `markCompleted(date)` реализован как условный PostgreSQL `INSERT ... ON CONFLICT DO UPDATE`:
  повторная запись и запись более ранней даты допустимы, переход вперёд — только на следующий
  UTC-день; попытка пропустить день приводит к `IllegalStateException`, переведённому репозиторием
  через Spring exception translation в соответствующий `DataAccessException`.
- `rebuildDay(date, false)` применяется для reconciliation: **не должен продвигать watermark назад
  или вперёд**, даже если данные за дату изменились.
- Pending dirty dates и watermark — разные понятия: корректировка старой даты не должна изображаться
  как новый contiguous backfill.
- Планировщик ограничивает backfill и lookback настройками `safeai.usage.rollup.*`; не следует
  считать, что один запуск пересчитывает всю историю.

### Безопасность исполнения и эксплуатация

Используется `PostgresAdvisoryLockExecutor`, чтобы два экземпляра приложения не запускали
конкурирующую работу одного scheduled rollup. Session-level advisory lock привязан к JDBC connection
и должен освобождаться при завершении action; корректность изменений всё равно зависит от
транзакционных границ отдельных операций. Принудительная переагрегация — потенциально дорогая
операция, поэтому перед массовым использованием нужно проверять лимиты, аудит вызывающего и
наблюдаемость выполнения по реальному коду.

Для V71 не следует создавать новую SQL-миграцию или редактировать уже применённый
`V71__usage_dirty_dates_and_recalculation_queue.sql` только ради документации.

## V74 — UTC-полночь, dirty dates и индексы

`V74__usage_midnight_dirty_date_and_physical_indexes.sql`
переопределяет функцию `safeai_mark_usage_date_dirty_v71`:
**текущий UTC-день также помечается dirty** внутри исходной
транзакции. Это защищает от гонки start-before-midnight /
commit-after-midnight. Обработчик пересчитывает только
соответствующие закрытые даты в пределах watermark.

V74 добавляет неуникальные индексы:

```sql
idx_model_execution_attempts_started_plan_v74
idx_model_execution_plans_organization_id_v74
```

Увеличение активности dirty queue может создать hot row
на текущем UTC-дне; нагрузка и contention требуют проверки.

## Concurrency / performance

`UsageReportExecutor` ограничивает количество конкурентных
тяжёлых reports **локальным семафором** и, в подготовленном
патче, PostgreSQL `pg_try_advisory_xact_lock`-слотами.

Транзакция — `REQUIRES_NEW`, read-only, `REPEATABLE_READ`;
для нескольких агрегаций отчёта она обеспечивает один DB
snapshot. При исчерпании слотов возвращается HTTP 429.
В production размер Hikari-пула проверяется с запасом от
`maxConcurrentReports + 2`.

Пагинация Usage ограничена 50 000 строками OFFSET; `models`
не должен молча обрезать финансовый отчёт: `LIMIT 1001` и
HTTP 422 при более чем 1000 моделях. `daily` ограничен
366 днями. Эти ограничения относятся к подготовленному
Usage hardening и требуют регрессионного прогона.

SQL paths имеют:

- query timeout;
- fetch size;
- performance/integration tests;
- EXPLAIN/EXPLAIN ANALYZE coverage в тестовой базе.

## Product usage vs physical spend

Usage subsystem остаётся product/chat analytics, но после `V60/V62/V67` физические расходы больше не
обязаны
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
provider-evidenced cost по сохранённой pricing/usage evidence, а не подтверждение расчёта с
поставщиком.

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
├── controller/
│   ├── ChatController
│   └── ChatTitleController
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
├── entity/
│   ├── ChatMessageEntity
│   ├── ChatMessageRole
│   ├── ChatMessageStatus
│   ├── ChatQuotaReservationState
│   ├── ChatSessionEntity
│   ├── ChatTurnEntity
│   └── ChatTurnState
├── exception/
│   ├── AiOutcomeAmbiguousException
│   ├── ChatAccessRevokedException
│   ├── ChatApiException
│   ├── ChatApiExceptionHandler
│   ├── ChatBusyException
│   ├── ChatInfrastructureExceptionHandler
│   ├── ChatLeaseUnavailableException
│   ├── ChatLockUnavailableException
│   ├── ChatQuotaExceededException
│   ├── ChatStaleProcessorException
│   ├── ChatTurnFailedException
│   ├── ChatTurnInProgressException
│   └── IdempotencyKeyReusedException
├── observability/
│   └── ChatMetrics
├── quota/
│   ├── ChatQuotaConsumption
│   ├── ChatQuotaPolicy
│   └── ChatQuotaReservation
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
├── service/
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
└── title/
    ├── ChatTitlePolicy
    ├── ChatTitleService
    └── ChatTitleSource
```

Exception ownership: `ChatBusyException` и `ChatLockUnavailableException` находятся
в `chat.exception` вместе с `ChatInfrastructureExceptionHandler`; не возвращать их в `common`.

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

Identity/provenance поля `ChatTurn` также защищены от изменения после создания. Дополнительно
message row со
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

Replay identity не должен зависеть от mutable policy/catalog state, изменившегося уже после принятия
оригинальной операции.

## Reservation flow

До external provider I/O сохраняются:

```text
ChatTurn
processingToken
providerOperationId
leaseUntil
authorized_organization_auth_version (immutable V72)
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

После V67 `provider_call_started_at` больше не должен быть application-level догадкой о том, начался
ли provider call.

DB boundary для вставки физического attempt:

```text
ExecutionPlan + sealed prepared-request binding v2
  ↓
V72: organization row FOR SHARE, enabled=true,
     authorized_organization_auth_version == auth_version
  ↓
V67: ChatTurn must be PROCESSING
  ↓
processing_token + live lease checked under row lock
  ↓
provider_call_started_at = COALESCE(existing, DB clock_timestamp())
  ↓
ModelExecutionAttempt STARTED committed
  ↓
external provider I/O (outside long DB transaction)
```

Поэтому:

```text
provider_call_started_at = null
    → durable STARTED boundary ещё не пройдена

provider_call_started_at != null
    → физический provider execution был разрешён к старту
    → blind replay после неопределённого исхода запрещён
```

Точная истина об outcome хранится в `ModelExecutionAttempt`; `provider_call_started_at` остаётся
coarse-grained
ChatTurn marker для recovery/fencing.

### V72 — immutable authorization epoch у ChatTurn

`chat_turns.authorized_organization_auth_version` — зафиксированная версия безопасности организации,
предъявленная авторизованным principal при создании logical turn. V72 при `INSERT chat_turns`
блокирует tenant `FOR SHARE`, проверяет `enabled=true` и совпадение epoch; последующее изменение
epoch-колонки запрещает DB-trigger.

Исторические ChatTurn, созданные до V72, сохраняют `NULL` (миграция **не приписывает** прошлым
запросам произвольную авторизацию). Такие строки не получают право на **новые** физические попытки.
`DEFAULT 0` в SQL существует для совместимости новых низкоуровневых тестовых INSERT; production Java
должна явно передавать epoch principal, а не полагаться на default.

При pre-`STARTED` запрете (например, Disable, потерянный lease, несоответствие epoch) корректное
состояние — контролируемый `FAILED` без подмены его `AMBIGUOUS`. Если attempt уже был разрешён,
дальнейший outcome трактуется по durable evidence, а не исходя из последующей смены tenant state.

**Инвариант временной последовательности:** `created_at ≤ provider_call_started_at ≤ completed_at`
там, где terminal timestamp установлен. V67/V72 используют физическое время PostgreSQL на STARTED;
тестовые injectable часы нужно согласовывать с DB clock, не отключая `chk_chat_turns_timestamps` и
не переписывая immutable `created_at`.


## Quota reservation

Quota резервируется до provider call и затем finalization корректирует состояние согласно persisted
usage/cost quality.

V67 добавляет provenance:

- `reservation_version=1` — legacy static reservation;
- `reservation_version=2` — reservation, привязанная к immutable `ModelRouteDecision`;
- `model_route_decision_id` обязателен для v2;
- `cost_verifiable=true` допустим только когда `reserved_cost_usd` является доказанным upper bound
  по поддерживаемой immutable pricing scheme.

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
├── controller/
│   ├── ModelCatalogController
│   ├── ModelPolicyPreviewController
│   ├── ModelRouteDecisionController
│   ├── ModelRuntimeController
│   └── OrganizationModelPolicyController
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
├── exception/
│   ├── ModelRouteDeniedException
│   └── ModelRouteEnvelopeExceededException
├── repository/
│   ├── JdbcTextArrayReader
│   ├── ModelCatalogRepository
│   ├── ModelRouteDecisionRepository
│   ├── ModelRouteRequestBindingRepository
│   ├── ModelRouteRequestMutexRepository
│   └── OrganizationModelPolicyRepository
└── service/
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

На текущем этапе backend runtime всё ещё представляет один активный configured provider/model target
на deployment, а catalog/policy определяют, разрешён ли logical model и совместим ли он с этим
target.

## Versioned Model Catalog

### Явные pricing-схемы и совместимость конструкторов

`ModelCatalogEntry`, `ModelCatalogEntryResponse` и `CreateModelCatalogVersionRequest`
содержат `ModelPricingScheme`. Существующие positional constructors сохранены ради
совместимости вызовов, но не для автоматического повышения доверия к старым тарифам.

- Для старой записи `CONFIGURED` без явной схемы используется
  `LEGACY_FLAT_UNVERIFIED_V1`, а не подтверждённая `FLAT_TOKEN_V1`.
- `FREE` получает `FLAT_TOKEN_V1`; неподдерживаемые/неполные тарифы не становятся
  автоматически пригодными для settlement.
- В ручном создании версии `CONFIGURED` требует явный `pricingScheme=FLAT_TOKEN_V1`.
- API возвращает денежные значения строками из `BigDecimal.toPlainString()`:
  десятичные суммы нельзя неявно конвертировать в JavaScript `Number`.
- `capabilities` и modality sets копируются в неизменяемые коллекции в доменной записи.
- `ModelCatalogRepository` читает точный `pricing_scheme` и сохраняет его вместе с
  immutable catalog entry; нельзя подменять exact version текущей latest-версией.

**Регрессионный риск:** в представленном `ModelCatalogService.importRuntime()`
используется compatibility constructor `ModelCatalogEntry` без явного `pricingScheme`.
Необходимо проверить `FREE` / `CONFIGURED` / `UNPRICED`, равенство повторных
runtime imports и точное ограничение V66. Не объявлять это исправленным только
на основании изменения доменной записи или README.


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

V59/V62 дополнительно защищают canonical text/numeric boundaries, а V66 вводит explicit settlement
algorithm identity.
Исторические legacy prices сохраняются, но не должны автоматически считаться проверенной современной
pricing scheme.

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

Первое отсутствие policy представляется fail-closed/unconfigured state, а не неявным «разрешить
всё».
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

V62 исправляет важный representability case: full monthly liability может оставаться unknown, но
известный lower bound
уже способен доказать budget overrun. V64 сохраняет route-time commitment evidence отдельно и
immutable, не изменяя
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
`knowledge_rag_context_receipts_v64`: exact `RetrievalRun`, KB, user/org, context hash и final
prepared-request hash.

После V65 новые prepared receipts должны иметь `binding_version=2`, а DB trigger на
`model_execution_attempts` не разрешает physical I/O без exact v2 binding.

## Input accounting

Governance accounting использует deterministic input units.

Текущий accounting contract:

```text
UTF8_STRUCTURAL_UNITS_V2
```

Это **не** заявляется как exact provider tokenizer.

Исторические DB column names могут содержать `*_tokens`, но новые semantics в коде трактуются как
versioned deterministic accounting units.

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
├── dto/
│   ├── AiChatRequest
│   ├── AiChatResponse
│   ├── AiMessage
│   └── AiMessageRole
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
├── execution/
│   ├── AiExecutionAttemptObserver
│   ├── AiExecutionAttemptRecorder
│   ├── AiExecutionAttemptScope
│   ├── AiExecutionPlanService
│   ├── AiExecutionRequest
│   ├── AiExecutionResult
│   ├── AiExecutionService
│   ├── AiOrganizationExecutionDeniedException
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
│   ├── ProviderUsageEvidence
│   ├── ResolvedModelMismatchException
│   ├── ResponseValidationStatus
│   └── RetrySafety
├── input/
│   ├── AiInputUnitEstimator
│   └── AiRequestFingerprint
├── metadata/
│   ├── AiResponseStatus
│   ├── AiTokenUsage
│   ├── PricingStatus
│   └── UsageStatus
├── pricing/
│   ├── ModelPricingProperties
│   ├── ModelPricingScheme
│   ├── ModelPricingService
│   ├── PricingResolver
│   ├── PricingResult
│   ├── StaticConfigurationPricingResolver
│   └── VersionedCatalogPricingResolver
├── provider/
│   ├── anthropic/
│   │   ├── AnthropicProperties
│   │   └── AnthropicProvider
│   ├── mock/
│   │   └── MockAiProvider
│   ├── openai/
│   │   ├── OpenAiProperties
│   │   └── OpenAiProvider
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

`AiOrganizationExecutionDeniedException` фиксирует application-level отказ на организационной
execution boundary; authoritative physical STARTED gate при этом остается в PostgreSQL V72.

## Durable physical execution

Начиная с V50 AI execution имеет отдельную durable evidence layer; V62–V67 доводят binding/start
boundary до
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
V72 organization enabled + exact auth_version (FOR SHARE)
      ↓
V67 live PROCESSING ChatTurn + lease/processingToken fence
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
commit Attempt STARTED with V72 organization epoch gate,
V67 live lease/fencing and PostgreSQL wall clock
  ↓
external provider I/O
  ↓
persist terminal physical evidence
  ↓
application/chat finalization
```

Это позволяет переживать crash boundaries без ложного предположения «если Chat не ответил, provider
точно ничего не сделал»
и не позволяет historical binding v1 авторизовать новый physical call.

## V72 — физический AI kill-switch

**V72 ограничивает допуск новых физических вызовов, а не корректность уже состоявшегося внешнего
вызова.** Организационный `enabled` и сохранённый `authVersion` проверяются дважды с разными целями:

1. `ModelExecutionAttemptRepository.organizationEpochCurrent(planId)` — неблокирующий advisory
   preflight для ранней понятной ошибки. Он **не** устраняет TOCTOU.
2. PostgreSQL `BEFORE INSERT` trigger `trg_a_physical_attempt_organization_epoch_v72` — авторитетная
   проверка `organization.enabled`, `auth_version`, immutable ChatTurn epoch и совпадения
   `organization_id`; берёт organization row `FOR SHARE` и удерживает lock до commit INSERT.
3. Остальные триггеры V53/V54/V65/V67 сохраняют свои дополнительные запреты: attempt ordering/retry,
   exact governed binding v2, live `PROCESSING` turn, lease и `processingToken`.
4. Только успешно закоммиченный физический `STARTED` даёт право перейти к provider I/O. С ним
   одновременно фиксируется `provider_call_started_at`; отдельного публичного
   `ChatTurnFinalizationService.markProviderCallStarted()` в актуальном патче нет.

**Линеаризация по БД:** конкурентный Disable (`FOR UPDATE` на той же организации) не может
закоммититься между V72 проверкой организации и commit разрешённого STARTED. После commit Disable
новые STARTED не проходят; после Enable старый ChatTurn также не проходит из-за старого epoch.

**Граница внешнего эффекта:** attempt с разрешением, полученным **до** Disable, может уже выполнять
HTTP либо дойти до сетевой отправки **после** Disable. Такой вызов нельзя считать автоматически
отменённым. Для более строгой гарантии «никаких сетевых send после commit Disable» понадобятся
дополнительные dispatch permits/draining/in-flight coordination; текущая реализация гарантирует
**транзакционный допуск**, а не физическую отмену provider I/O.

**Execution evidence не теряется:** `STARTED`, `SUCCEEDED`/`FAILED`/`AMBIGUOUS`, outcome certainty,
usage/pricing и retry-safety сохраняются по существующим договорам. Отказы до STARTED и
неопределённость после STARTED не объединяются в одно состояние.


## Physical target identity

Attempt фиксирует target:

- provider;
- provider configuration reference/version;
- deployment reference/version;
- requested physical model;
- allowed resolved models.

Текущий runtime использует transitional static target identity; полноценный versioned provider
registry ещё не реализован.

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

Таким образом cost upper-bound reservation не может быть нарушен скрытым retry. Blind retry/fallback
после
`AMBIGUOUS`, `SUCCEEDED` или physically executed protocol rejection не допускается.

## Resolved model validation

Provider response model сверяется с approved target.

Если provider фактически выполнил запрос, но вернул неразрешённый resolved model, физическое
evidence сохраняется до governance rejection.

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

### Физический settlement и проверка целостности

`VersionedCatalogPricingResolver` принимает `operationId` и проверяет непрерывную
цепочку `operation → execution plan → ALLOWED route decision → exact catalog version`.
Без identity либо при несовпадении provider, physical model, tenant, chat turn,
`decisionIntegrityVersion=3` и версии каталога результат `UNPRICED`.

- Только `FLAT_TOKEN_V1`, полный тариф и обязательные rate dimensions допускают расчёт.
- Статусы `FREE` и `PRICED` различаются: неизвестную стоимость нельзя заменить нулём.
- Для OpenAI cached input является частью `input_tokens` и вычитается из ordinary input.
- Для Anthropic cache-read и cache-write учитываются как отдельные input dimensions.
- Отсутствие нужного cache rate, неполная usage или неизвестный provider → `UNPRICED`.
- Арифметическая ошибка и невозможный положительный/нулевой итог → `CALCULATION_FAILED`.
- Денежные суммы округляются до 12 знаков; идентификатор price book строится из
  immutable catalog snapshot с SHA-256, не из изменяемого YAML.

`ModelRoutingCostPolicy` использует worst-case reservation cost и отдельные
`actual`, `reserved`, `unknown` commitment states. Для `HARD` budget неизвестная
стоимость не должна обходить ограничение. Read-only preview использует snapshot
без data-plane budget lock и всегда `nonBinding=true`: он не обещает результата
следующей транзакционной маршрутизации.


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

Legacy `CONFIGURED` pricing после migration не должен автоматически считаться доказуемым современным
расчетом:
governed path обязан fail closed, пока новая audited catalog version не выберет поддерживаемую
scheme.

`StaticConfigurationPricingResolver` остаётся compatibility/dev path, а governed resolution должен
использовать
versioned catalog evidence.

## Usage / response evidence

Provider usage поддерживает quality states и specialized billing dimensions.

V66 отдельно фиксирует факт физически наблюдавшегося provider response:

- `response_observed`;
- `response_validation_status`;
- `provider_http_status`;
- `protocol_failure_code`;
- `PROTOCOL_REJECTED` outcome.

Это позволяет сохранить billable `KNOWN_EXECUTED` evidence даже когда ответ нельзя принять на
application/protocol layer.
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
├── config/
│   ├── KnowledgeEmbeddingConfigurationInvariantVerifier
│   ├── KnowledgeEmbeddingProperties
│   ├── KnowledgeIngestionProperties
│   ├── KnowledgeOcrConfigurationInvariantVerifier
│   ├── KnowledgeOcrProperties
│   ├── KnowledgeProcessingConfiguration
│   ├── KnowledgeRagProperties
│   └── KnowledgeRetrievalProperties
├── controller/
│   ├── AnswerPassportController
│   ├── KnowledgeBaseController
│   ├── KnowledgeDocumentController
│   ├── KnowledgeEvaluationController
│   └── KnowledgeRetrievalController
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
├── embedding/
│   ├── HashingKnowledgeEmbeddingProvider
│   ├── KnowledgeEmbeddingException
│   ├── KnowledgeEmbeddingProductionInvariantVerifier
│   ├── KnowledgeEmbeddingProvider
│   ├── OpenAiKnowledgeEmbeddingProvider
│   └── PgVectorSupport
├── entity/
│   ├── KnowledgeBaseEntity
│   ├── KnowledgeBaseMembershipEntity
│   ├── KnowledgeDocumentEntity
│   ├── KnowledgeDocumentVersionEntity
│   └── KnowledgeIngestionJobEntity
├── evaluation/
│   └── KnowledgeRetrievalMetrics
├── exception/
│   └── KnowledgeStorageUnavailableException
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
├── generation/
│   ├── KnowledgeGenerationGc
│   └── KnowledgeGenerationGcScheduler
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
├── model/
│   ├── KnowledgeBaseAccessLevel
│   ├── KnowledgeBaseVisibility
│   ├── KnowledgeHealthState
│   └── KnowledgeIngestionStatus
├── ocr/
│   ├── DisabledKnowledgeOcrProvider
│   ├── HttpKnowledgeOcrProvider
│   ├── KnowledgeOcrProvider
│   ├── OcrDocument
│   └── OcrPage
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
├── repository/
│   ├── KnowledgeBaseMembershipRepository
│   ├── KnowledgeBaseRepository
│   ├── KnowledgeDocumentQueryRepository
│   ├── KnowledgeDocumentRepository
│   ├── KnowledgeDocumentVersionRepository
│   ├── KnowledgeIngestionJobRepository
│   └── KnowledgeMemberDirectoryRepository
├── retrieval/
│   ├── KnowledgeRetrievalExecution
│   ├── KnowledgeRetrievalHit
│   └── KnowledgeRetrievalRepository
├── service/
│   ├── KnowledgeAccessService
│   ├── KnowledgeBaseNameNormalizer
│   ├── KnowledgeBaseService
│   ├── KnowledgeDocumentFileValidator
│   ├── KnowledgeDocumentMediaTypeDetector
│   ├── KnowledgeDocumentMediaTypes
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
└── storage/
    ├── reconciliation/
    │   ├── KnowledgeStorageReconciliationConfiguration
    │   ├── KnowledgeStorageReconciliationProperties
    │   ├── KnowledgeStorageReconciliationScheduler
    │   └── KnowledgeStorageUploadJournal
    ├── KnowledgeStorageConfiguration
    ├── KnowledgeStorageProductionInvariantVerifier
    ├── KnowledgeStorageProperties
    ├── KnowledgeStorageType
    ├── LocalObjectStorage
    ├── ObjectStorage
    ├── S3ObjectStorage
    └── StoredObject
```

## Конкурентный ingestion, lease fencing и bounded processing

`KnowledgeIngestionProperties` добавляет `workerThreads` с default 1,
допустимыми значениями 1–8 и legacy constructor без нового аргумента.
`KnowledgeIngestionScheduler` создаёт фиксированный pool и claim-ит задачи
только на свободные слоты в пределах `batchSize`. После каждой волны он
ожидает `Future.get()`; shutdown вызывает `shutdownNow()`.

`KnowledgeIngestionProcessor` отдельно поддерживает heartbeat leases с
`processingToken`, регулярное продление во время extraction/embedding и
fenced publication. При потере ownership публикация запрещена. Проверяются
точные document/version/tenant IDs, длина S3-объекта, SHA-256 содержимого,
валидность embedding dimensions (384), finite/non-zero values и batch size.

**Открытые проверки:** failure/cancellation, shutdown, multi-worker race,
lease renewal во время долгих операций, pool starvation и monotonic state
transitions. У heartbeat pool фиксировано два потока: проверить capacity под
фактическую ingest-конкурентность. Наличие кода не доказывает корректность
поведения при многопроцессном сбое или остановке JVM.

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

> Нельзя автоматически считать historical/uncertain S3 object orphan-ом только потому, что SQL row
  не найден.

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

V64 добавляет более раннюю execution-time proof-точку — immutable
`knowledge_rag_context_receipts_v64`.
Она доказывает, что exact retrieval/context assembly был связан с exact final prepared AI request
**до**
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

Ниже — основные публичные API groups. Controllers остаются source of truth для exact query/body
shape.

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
GET  /api/admin/audit-events
POST /api/admin/audit-events/outbox/dead-letters/{eventId}/recover
```

Также есть directory/filter endpoints для UI.
Второй endpoint относится к подготовленному Audit hardening;
он требует Java-сервис и V75, а не только миграцию.

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

Новые отчёты физического учёта (пакет V74):

```http
GET /api/admin/usage/physical-attempts
GET /api/admin/usage/physical-attempts/reconciliation
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

### V76 — privacy для новых retrieval runs

`V76__knowledge_retrieval_query_privacy.sql` вводит `NOT VALID` CHECK:

```sql
CHECK (query_text = '[REDACTED:SHA256_ONLY]') NOT VALID
```

PostgreSQL применяет такой CHECK к новым `INSERT` и `UPDATE`, но не проводит
ретроспективную валидацию всех старых строк. `query_sha256` сохраняет
ограниченный provenance поиска; SHA-256 не гарантирует анонимность
низкоэнтропийного запроса и требует соответствующей privacy policy.

Миграция предварительно требует успешную Flyway V75, таблицы
`knowledge_retrieval_runs` / `knowledge_retrieval_hits` и действующий
`trg_knowledge_retrieval_runs_immutable_v44`. V41/V44/V55/V63 **не редактировать**.
Исторические plaintext queries **не удаляются и не редактируются этой миграцией**:
для них необходим отдельный согласованный retention/evidence процесс.

Добавляется частичный индекс очереди storage review:

```sql
CREATE INDEX idx_knowledge_upload_needs_review_v76
    ON public.knowledge_storage_upload_intents (needs_review_at, id)
    WHERE state = 'NEEDS_REVIEW';
```

**Проверка совместимости:** все writer paths должны до `INSERT` передавать
строго `[REDACTED:SHA256_ONLY]`, отдельно сохранять корректный `query_sha256`
и не писать raw query в trace, audit или metrics. Представленный
`KnowledgeRetrievalRepository.hybridSearch()` передаёт query в SQL для
полнотекстового поиска; это само по себе не доказывает сохранение raw text в БД.
Нужен отдельный тест persist/retrieval-run writer и отрицательный DB test.


```http
POST /api/knowledge-bases/{knowledgeBaseId}/retrieval
POST /api/knowledge-bases/{knowledgeBaseId}/evaluations
```

Answer Passport читается по chat/turn identity через `AnswerPassportController`.

---

# Database / Flyway V1–V76

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

## V76: безопасное применение

Порядок миграций обязателен: V73 → V74 → V75 → V76. Нельзя применять V76
к БД без успешной V75 или переигрывать immutable V41/V44/V55/V63.

`NOT VALID` не означает disabled: PostgreSQL проверяет новые строки, но
разрешает оставаться существующим строкам, которые могли нарушать новый CHECK.
Исторические данные подлежат отдельному privacy review. Проверить `conname`,
`convalidated=false`, `CHECK` enforcement, индекс review queue и absence of
plaintext in newly created retrieval runs на отдельном PostgreSQL стенде.

В preflight V76 проверяется наличие таблиц и одного immutable trigger; это
не заменяет проверки всех write paths и совместимости приложения со схемой.

## Evolution map

- **`V1–V10`**
  - **Назначение:** base schema, tenant denormalization, timestamps, quotas/rollups, indexes
- **`V11–V19`**
  - **Назначение:** audit/user/chat identity and integrity evolution
- **`V20–V23`**
  - **Назначение:** production integrity, canonical identity/name hardening, concurrent indexes
- **`V24`**
  - **Назначение:** exactly-one-role, user security hardening, audit outbox
- **`V25`**
  - **Назначение:** organization `auth_version` + refresh organization epoch
- **`V26–V27`**
  - **Назначение:** audit retry/dead-letter/details bounds/query indexes
- **`V28–V31`**
  - **Назначение:** usage/pricing quality model and query indexes
- **`V32`**
  - **Назначение:** durable `ChatTurn` state machine
- **`V33–V36`**
  - **Назначение:** ChatTurn indexes, ambiguity quality, composite tenant integrity
- **`V37`**
  - **Назначение:** immutable target organization audit snapshots
- **`V38–V42`**
  - **Назначение:** Knowledge Base, versions, ingestion, retrieval, RAG, Answer Passport
- **`V43`**
  - **Назначение:** chat archive lifecycle
- **`V44`**
  - **Назначение:** Knowledge production integrity hardening
- **`V45`**
  - **Назначение:** Model Control Plane baseline
- **`V46`**
  - **Назначение:** Model Control Plane hardening
- **`V47`**
  - **Назначение:** Knowledge chunk-generation uniqueness
- **`V48`**
  - **Назначение:** input-accounting provenance / route integrity v3
- **`V49`**
  - **Назначение:** strict routing + generic pricing semantics
- **`V50`**
  - **Назначение:** AI execution plans/attempts + usage evidence
- **`V51`**
  - **Назначение:** execution attempt integrity hardening
- **`V52`**
  - **Назначение:** strict execution + modality integrity
- **`V53`**
  - **Назначение:** physical attempt transition + pricing integrity
- **`V54`**
  - **Назначение:** provider identity + reconciliation safety
- **`V55`**
  - **Назначение:** Knowledge Russian lexical profile
- **`V56`**
  - **Назначение:** durable Knowledge storage upload intents
- **`V57`**
  - **Назначение:** Knowledge index generations + fenced publication/GC
- **`V58`**
  - **Назначение:** deterministic chat title lifecycle
- **`V59`**
  - **Назначение:** governance integrity: canonical model keys, bounded-cost single-attempt rule,
    reconciliation/title/generation hardening
- **`V60`**
  - **Назначение:** UTC monthly physical cost commitment ledger: actual, reserved, unknown
    liability, carry-forward
- **`V61`**
  - **Назначение:** model-policy allow/deny array uniqueness
- **`V62`**
  - **Назначение:** budget representability fix, final prepared-request receipt, capability
    canonicality, commitment ledger v2
- **`V63`**
  - **Назначение:** Knowledge searchable-version publication, generation lifecycle hardening,
    review-time semantics
- **`V64`**
  - **Назначение:** immutable route-time budget commitments, RAG context receipt, prepared-request
    binding v2
- **`V65`**
  - **Назначение:** post-rollout binding v2 enforcement and DB physical-execution fence
- **`V66`**
  - **Назначение:** physical response/protocol rejection evidence + explicit fail-closed pricing
    scheme provenance
- **`V67`**
  - **Назначение:** ChatTurn state/evidence hardening, live STARTED fencing, quota provenance,
    physical-spend view, DB clock
- **`V68`**
  - **Назначение:** audit event/outbox integrity: canonical snapshots, typed payload, immutable
    evidence/intent, terminal dead-letter state
- **`V69`**
  - **Назначение:** audit actor-link FK detachment: отделение исторического actor snapshot от
    ссылочной зависимости на удаляемую учётную запись
- **`V70`**
  - **Назначение:** audit live-user link separation: разделение живой ссылки на пользователя и
    исторических данных audit actor; сохранение audit evidence при изменении жизненного цикла
    пользователя
- **`V71`**
  - **Назначение:** Usage dirty-date queue и повторный расчёт UTC-дней: учёт поздних изменений
    источников, выборка ожидающих дат и управляемая переагрегация без отката последовательного
    watermark
- **`V72`**
  - **Назначение:** immutable authorization epoch у ChatTurn; organization `FOR SHARE` при
    reservation и каждом физическом `STARTED`; DB-serialized AI kill-switch и запрет оживления
    старых turns
- **`V73`**
  - **Назначение:** Организации: Unicode whitespace + коллизии имён
- **`V74`**
  - **Назначение:** Usage: dirty date на UTC-полуночи + physical indexes
- **`V75`**
  - **Назначение:** Audit: тип события recovery terminal dead-letter
- **`V76`**
  - **Назначение:** Privacy hardening retrieval evidence: новые записи
    `knowledge_retrieval_runs` содержат только маркер `[REDACTED:SHA256_ONLY]`
    вместо raw `query_text`, при сохранении `query_sha256`. Ограничение
    `CHECK ... NOT VALID` защищает новые INSERT/UPDATE без изменения истории.
    Частичный индекс ускоряет обработку `NEEDS_REVIEW` upload intents.
    Исторический plaintext требует отдельного privacy/retention решения.



## V73 — Canonical names организаций

Полное имя файла:

```text
V73__organization_unicode_whitespace_canonicalization.sql
```

Транзакционно заменяет тела существующих функций V20,
не удаляя их CHECK/trigger и уникальный индекс V23.

1. `SHARE ROW EXCLUSIVE` блокирует конкурирующие изменения
   организаций до завершения preflight и UPDATE.
2. Проверяются V20 functions/constraints/trigger и V23
   `UNIQUE`, `VALID`, `READY` индекс `normalized_name`.
3. Канонизация переводит 24 символа пробелов в ASCII SPACE,
   схлопывает подряд идущие пробелы и удаляет крайние.
4. `organization_name_has_iso_controls_v73` исключает
   управляющие C0/C1; контролируется UTF-16 длина имени.
5. Collision preflight проверяет фактический PostgreSQL key:
   `lower(canonical_organization_name_v73(name))`.
6. При наличии коллизий миграция аварийно останавливается.
   Tenant merge/delete не допускается.
7. При реальной корректировке имени сохраняется `auth_version`,
   увеличивается optimistic `version`.

**Риск внедрения:** полный preflight / UPDATE может удерживать
блокировки. Применять сначала на populated backup clone.
Сверить Java `OrganizationNameNormalizer` с финальной V73;
проверить Unicode, ISO controls, emoji и SQL collation.

## V74 — Usage: midnight dirty-date и physical indexes

```text
V74__usage_midnight_dirty_date_and_physical_indexes.sql
```

Миграция переопределяет функцию V71
`safeai_mark_usage_date_dirty_v71(date)`; исходные
триггеры dirty-date tracking сохраняются.

- Теперь в очередь попадает и сегодняшний UTC-день:
  транзакция, завершившаяся после полуночи, не должна
  оставить предыдущий день без перерасчёта.
- Перерасчёт выполняется только при достижении
  соответствующего закрытого дня/watermark.
- Индекс `(started_at, execution_plan_id)` оптимизирует
  диапазонную выборку физических попыток.
- Индекс `(organization_id, id)` на `model_execution_plans`
  помогает tenant-scoped joins.
- Индексы не уникальны и не меняют semantics retry.

**Риск:** ежедневная dirty row может стать точкой
конкурентной записи. Нужны нагрузочные замеры и мониторинг
lock waits на production-like PostgreSQL.

## V76 — Privacy retrieval runs и индекс review queue

```text
V76__knowledge_retrieval_query_privacy.sql
```

- Preflight требует успешно применённую V75, необходимые таблицы
  и действующий immutable trigger V44.
- Новые `knowledge_retrieval_runs.query_text` обязаны содержать
  `[REDACTED:SHA256_ONLY]`; поисковый provenance хранится
  отдельно в `query_sha256`.
- `CHECK ... NOT VALID` проверяет новые INSERT/UPDATE, но не
  подтверждает отсутствие старых открытых запросов в таблице.
- Частичный индекс `(needs_review_at, id)` с фильтром
  `state = 'NEEDS_REVIEW'` оптимизирует очередь на проверку
  в `knowledge_storage_upload_intents`.
- Не менять применённые V41/V44/V55/V63 и не удалять
  immutable retrieval evidence ради успешного запуска.
- Совместимость Java-writer с новым DB CHECK и privacy
  исторических данных должны подтверждаться отдельно.

## V75 — Регистрация типа Audit recovery

```text
V75__audit_dead_letter_recovery_event_type.sql
```

Миграция добавляет `AUDIT_OUTBOX_DEAD_LETTER_RECOVERED`
в справочник `public.audit_event_types` идемпотентно,
не редактируя применённую V68 и не заменяя terminal
`dead_lettered_at` на повторно запланированное событие.

**V75 не создаёт endpoint, восстановление или алерты.**
Для восстановления нужны согласованные Java-классы
`AuditOutboxRecoveryService`,
`AuditOutboxRecoveryController`, `AuditDeadLetterRecoveryDetails`,
`AuditEventType` и тесты.

## Порядок внедрения

```text
V1–V72  →  V73 (Organization)
        →  V74 (Usage)
        →  V75 (Audit event type)
        →  V76 (Knowledge retrieval query privacy)
```

Не копировать ранний конфликтный
`V73__audit_dead_letter_recovery_event_type.sql`:
этот номер уже занят миграцией организаций.

В текущем публичном `main` проверено только наличие
**V1–V72**. Отображение V73–V76 в этом README — описание
подготовленного целевого пакета, не подтверждение применённой
схемы. Истину об установке даёт `flyway_schema_history`.

## V72 — границы транзакционного допуска и обратная совместимость

Новая migration **`V72__organization_auth_epoch_physical_attempt_gate.sql`** добавляет:

- **`chat_turns.authorized_organization_auth_version` (`bigint`, исторически nullable)**
  - **Назначение:** Неподменяемый epoch авторизации из момента reservation
- **`chk_chat_turn_authorized_org_auth_epoch_v72`**
  - **Назначение:** Запрет отрицательной версии
- **`guard_chat_turn_reservation_epoch_v72()` + `trg_a_chat_turn_reservation_epoch_v72`**
  - **Назначение:** На `INSERT chat_turns`: tenant `FOR SHARE`, `enabled`, exact epoch
- **`guard_chat_turn_epoch_immutable_v72()` + `trg_a_chat_turn_epoch_immutable_v72`**
  - **Назначение:** Запрет изменения authorization epoch после INSERT
- **`guard_physical_attempt_organization_epoch_v72()` +
  `trg_a_physical_attempt_organization_epoch_v72`**
  - **Назначение:** На каждый `INSERT model_execution_attempts`: tenant `FOR SHARE` + live security
    epoch


```text
Organization Disable                       Physical STARTED
--------------------                       ----------------
organization FOR UPDATE <──── conflict ── organization FOR SHARE
set enabled=false, auth_version++           check enabled + exact epoch
commit                                     attempt commit (before provider I/O)
```

Операции сериализуются по строке организации: победившая транзакция определяет допустимость нового
STARTED. **Нет гарантий отмены** уже допущенного сетевого вызова; нельзя менять/удалять его
исторический attempt evidence.

Миграция V72 сохраняет `NULL` в исторических строках ChatTurn и даёт `DEFAULT 0` лишь новым
SQL-вставкам, которые не передали значение. Такие исторические turns не могут открыть новую physical
attempt. Загрузка старых версий backend в смешанном rollout может приводить к fail-closed отказам
из-за отсутствующего principal epoch: требуется согласованный cutover. Изменять ранее применённые
V1–V71 вместо нового SQL-файла запрещено.

**Статус проверки:** исторический PostgreSQL Testcontainers log подтверждает применение V1–V72 с
нуля. Позднее получен успешный локальный прогон `./mvnw test` (2259 tests) и трех выбранных
PostgreSQL/Redis concurrency/integration suites (27 tests). Это не равно проверке всех возможных
блокировок и не подтверждает upgrade населенной базы V71→V72 или production rollout.


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
- V71 dirty-date recalculation state и инварианты монотонного rollup watermark;
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
- V72 immutable ChatTurn organization authorization epoch;
- V72 `FOR SHARE` fence при reservation и physical `STARTED`;
- V72 tenant-disable gate, исключающий новый committed STARTED после Disable;
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

Release runbook также должен проверять `pg_index.indisvalid/indisready` для critical unique/business
indexes после аварийного interrupted migration.

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

## Audit Outbox: метрики и оповещения

Файл правил размещается **не в Spring `application.yml`**:

```text
infra/prometheus/rules/safeai-audit-outbox.yml
```

`infra/prometheus/prometheus.yml` загружает
`/etc/prometheus/rules/*.yml`; каталог rules должен быть
смонтирован в контейнер Prometheus. Scrape target использует
внутренний `backend:9091/actuator/prometheus`.

Главные сигналы:

- `safeai_audit_outbox_dead_letter_rows` — dead-letter count;
- `safeai_audit_outbox_oldest_age_seconds` — возраст backlog;
- `safeai_audit_outbox_metrics_refresh_failures_total` —
  сбой обновления метрик;
- `safeai_audit_outbox_processor_failures_total` —
  сбои фоновой обработки;
- `up{job="safeai-backend"}` — доступность scrape.

Конфигурация подготовлена с семью оповещениями, включая
отсутствующую целевую метрику и отсутствие scrape target.
Несколько инстансов публикуют общую DB backlog метрику:
использовать `max`, не `sum` для gauge общих строк БД.

**Важный gap:** одни лишь alert rules не доставляют
уведомления. Необходим реальный Alertmanager, маршруты,
секреты получателей и проверенный test firing → delivery.
Без него «firing в Prometheus» не означает извещения
дежурного. См. `docs/RUNBOOK_AUDIT.md`.

## Безопасное логирование ошибок

Audit best-effort/scheduler/metrics должны логировать
без `Throwable`, `exception.toString()` и сырого
`getMessage()`: сообщения SQL/provider способны содержать
PII и bind values. Допустим ограниченный safe failure type.
Required audit failure при этом **не проглатывается**:
иначе теряется transactional evidence.

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
- usage rollups/backfill и V71 dirty-date recalculation;
- ChatTurn recovery;
- Knowledge ingestion;
- Knowledge storage reconciliation;
- Knowledge generation GC.

Multi-instance job должен иметь DB/distributed coordination там, где duplicate work нарушит
semantics.

---

# Тестирование

README не утверждает `BUILD SUCCESS` без фактического запуска конкретного commit.

## Регрессионные проверки для изменений 17.10.2026

Ниже — **требуемые проверки, а не результаты выполненного прогона**.

- Catalog: legacy positional constructors, `pricingScheme` JSON/request binding,
  runtime import idempotency, exact-version SQL mapping, V66 scheme CHECK.
- Pricing: missing operation identity, wrong physical model, invalid usage/cache
  dimensions, OpenAI/Anthropic semantics, `FREE`, `UNPRICED`, calculation errors.
- Routing: `HARD` budget с unknown liability, concurrency и read-only
  `nonBinding` preview без резервирования средств.
- Ingestion: parallel `workerThreads`, claim leases, heartbeat, cancellation,
  extraction timeout, object hash/size, embedding vector validity и shutdown.
- Retrieval V76: PostgreSQL constraint negative tests, preserved historical
  row, inserted redacted query and valid hash, immutable trigger, review index.
- Upgrade: populated Flyway V72 → V76, application startup, production profile,
  Testcontainers PostgreSQL/Redis и отсутствие регрессии публичных API.

Не заменять падающий тест ослаблением CHECK, удалением assert или
изменением истории Flyway. Сначала установить фактическую причину и
соответствие нового контракта ожидаемому поведению теста.

## Исторические проверки на 11.10.2026

Проверки выполнены пользователем локально перед merge PR #8; это **конкретные команды и их вывод**,
а не заявление о статусе независимого CI, production или `clean` сборки.

- **`./mvnw -Dtest=CommonDependencyArchitectureTest test`**
  - **Фактический результат:** 1 test / 0 failures / 0 errors / `BUILD SUCCESS`
- **Negative control: temporary `common → auth` dependency**
  - **Фактический результат:** Expected `Architecture Violation` / `BUILD FAILURE`; нарушение
    найдено по типу поля
- **Повторный positive test после удаления probe**
  - **Фактический результат:** 1 test / 0 failures / 0 errors / `BUILD SUCCESS`
- **`./mvnw test`**
  - **Фактический результат:** **2259 tests / 0 failures / 0 errors / 3 skipped / `BUILD SUCCESS`**
- **Целевые Auth/Organization/Redis integration tests**
  - Команда: `./mvnw -Dtest=... test`
  - Классы: `AuthPostgresConcurrencyIT`,
    `OrganizationSecurityGateConcurrencyIT`,
    `RedisFixedWindowRateLimiterIT`.
  - **Фактический результат:** **27 tests / 0 failures / 0 errors / `BUILD SUCCESS`** (15 + 5 + 7)
- **`git merge-base --is-ancestor 103c587 main`**
  - **Фактический результат:** Подтверждено: исходный коммит PR #8 в истории `main`
- **`git status -sb`**
  - **Фактический результат:** `## main...origin/main` без изменений


Число `2259` — summary из Maven для **основного прогона**; запуск 27 целевых интеграционных
тестов учитывается отдельно, не нужно автоматически складывать их с общим числом.

### Важно различать уровни доказательств

- **Пройдено:** локальный `./mvnw test` на коде PR #8; targeted PostgreSQL/Redis integration tests;
  positive/negative ArchUnit test; Git ancestry.
- **Подтверждено по репозиторию:** наличие миграций Flyway V1–V72, `pom.xml`, классов и
  архитектурного теста в `main` на `fdcff62`.
- **Историческое свидетельство:** прежний успешный старт Testcontainers PostgreSQL с применением 72
  миграций.
- **Пока не подтверждено предоставленными материалами:** `./mvnw clean test` после merge, CI на
  merge SHA, populated upgrade V71→V72, продакшен-запуск, backup/restore drill, всесторонний
  security/performance/load review.

### Исторические сбои V71/V72 и их уроки (НЕ текущий статус сборки)

Предыдущие логи фиксировали отказ `chk_chat_turns_timestamps`: тестовый `Clock` выставлял
`ChatTurn.created_at` позднее физического `clock_timestamp()` PostgreSQL, после чего
DB-триггер корректно отвергал STARTED. Также существовал fixture в Knowledge integration
suite, который пытался мутировать immutable `created_at`. Эти истории **не следует
представлять как текущий failing build** после подтвержденного green run 2259 tests.

При новом изменении этого кода по-прежнему нельзя отключать `chk_chat_turns_timestamps`,
смягчать state-machine/epoch triggers или делать слепой replay после `AMBIGUOUS`.
Для двух одноименных классов указывайте fully qualified name:

```bash
cd backend
./mvnw -Dtest=ru.safeai.gateway.chat.integration.ChatTurnStateMachineIntegrationTest test
./mvnw -Dtest=ru.safeai.gateway.knowledge.integration.ChatTurnStateMachineIntegrationTest test
```

Не интерпретировать `BUILD SUCCESS` одного targeted test как доказательство прохождения
всей регрессии. Новый PR требует собственного green run, даже если baseline уже зеленый.

## Карта размещения backend-тестов

Дерево baseline `backend/src/test/java/ru/safeai/gateway/` содержит следующие Java-файлы
тестовых пакетов (количество **файлов**, а не JUnit test cases):

| Пакет | Java-файлов | Что проверять при изменениях |
|---|---:|---|
| `architecture` | 1 | ArchUnit: запрет `common → application` |
| `common` | 25 | Shared API errors, security/config, infrastructure |
| `auth` | 25 | Login/refresh/JWT/CSRF/cookie/filter/concurrency |
| `user` | 20 | Tenant access, validation, mutations, versioning |
| `organization` | 12 | Platform org, access, security epochs |
| `ratelimit` | 16 | Redis/Lua, limits, HMAC keys, failure semantics |
| `audit` | 30 | Immutable evidence, outbox, retries, retention |
| `usage` | 25 | SQL reporting, rollup, dirty-date, performance |
| `chat` | 37 | ChatTurn, idempotency, lease, recovery, quotas |
| `model` | 53 | Catalog, policy, route decision, pricing/budget |
| `ai` | 45 | Provider adapters, execution evidence, retry |
| `knowledge` | 59 | ACL, ingestion, retrieval/RAG, storage, GC |

Также в корне test package находятся `SafeaiBackendApplicationTests`, utility
`PasswordHashGenerator`, а в `testsupport` — `AbstractPostgresIntegrationTest`.
Для `identity` на этом срезе отдельной папки с тестами нет; его порт проверяется
в тестах потребителей/реализации. Выборочное добавление dedicated ArchUnit identity rule
закроет пробел в проверке направленности зависимостей.

Maven Surefire настроен для обычных unit/regular integration tests.
В `backend/pom.xml` есть отдельный opt-in Failsafe профиль `rate-limit-topology-it`
(`./mvnw verify -Prate-limit-topology-it`), предназначенный для проверок внешнего
Redis Sentinel/Cluster. Его успешный прогон **не подтвержден** этим README.

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

## Целевые регрессии V72: security epochs, concurrency и физический execution

```bash
cd backend

# Проверка создания ChatTurn, physical STARTED, результата и recovery:
./mvnw -Dtest=ru.safeai.gateway.chat.integration.ChatTurnStateMachineIntegrationTest test

# Независимый одноимённый Knowledge test:
./mvnw -Dtest=ru.safeai.gateway.knowledge.integration.ChatTurnStateMachineIntegrationTest test

# Параллельные Login/Refresh/Disable и token-family races:
./mvnw -Dtest=OrganizationSecurityGateConcurrencyIT test

# Внутренний preflight и epoch у нового ChatTurn:
./mvnw -Dtest=AiExecutionAttemptOrganizationGateTest,ChatTurnOrganizationEpochTest test

# Unit-level state/finalization после удаления старого markProviderCallStarted:
./mvnw -Dtest=ChatServiceTest,ChatTurnFinalizationServiceTest test

# Полный regression suite:
./mvnw clean test
```

> Названия классов относятся к исходным V72-патчам; фактическое существование/расположение тестов в
  текущем checkout необходимо проверить. Некоторые integration suites могут запускаться через
  отдельный Maven Failsafe-профиль; тогда дополнительно выполните `./mvnw verify` в соответствии с
  `pom.xml`.

**Конкурентные сценарии, которые должны завершиться без deadlock и без выдачи действительной сессии
после Disable:** Login ↔ Disable, Refresh ↔ Disable, несколько concurrent Refresh одного token
family, ожидание `FOR SHARE`/`FOR UPDATE`, epoch change + re-enable, authorization при отказах и
повторах. Для AI проверить оба порядка гонки: Disable first → попытка запрещена; STARTED first →
Disable ждёт commit, evidence сохраняется. Добавить проверку deadlock SQLSTATE `40P01`,
statement/lock timeout и соответствующих retry/fail-closed решений.

**Для временных инвариантов** отдельно проверять timestamp ordering с реальными PostgreSQL clock и
тестовым `Clock`. Нельзя добиться зелёной сборки удалением `chk_chat_turns_timestamps`, смягчением
V67/V72-триггеров или возвратом service-level `markProviderCallStarted()`.


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
- dirty-date queue, исторический forced rebuild и монотонный watermark (V71);
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

## Целевые проверки V71 (сохранены при переходе на V72)

```bash
cd backend
./mvnw -Dtest=UsageRollupSchedulerTest,UsageRollupReconciliationIntegrationTest test
./mvnw -Dtest=RedisFixedWindowRateLimiterIT,UserServiceTest test
./mvnw clean test
```

Интеграционные тесты с `@Testcontainers` требуют Docker. Если сборка падает, ищите **первый**
`Caused by`, а не только каскадное `ApplicationContext failure threshold exceeded`. Для проверки V71
полезны: выборка earliest UTC date (включая `AMBIGUOUS`), последовательность backfill, повторный
расчёт dirty dates, монотонность watermark, lock contention, идемпотентность aggregate rebuild. Не
подавляйте проверки SQL-целостности ради зелёного результата.

## Архитектурные регрессии и CI

Текущий пакет `architecture` содержит **один** ArchUnit test. Следующие архитектурные правила
добавляются отдельными PR, только после проверки реальных dependencies/allowed edges.
Без новых тестов в `common` нельзя возвращать `JwtService`, auth exceptions или BCrypt
configuration.

Для запуска всех backend-тестов из корня проекта:

```bash
cd backend
./mvnw clean test
```

Для frontend по актуальному `package.json`:

```bash
cd frontend
npm ci
npm run check
```

`npm run check` выполняет lint, Vitest и build (с проверкой typecheck/production bundle).
Команды приведены как воспроизводимые проверки; успешный frontend run **не подтвержден** данным
срезом.

## Дополнительная регрессия V73–V76

Перед релизом обязательны новые проверки, **не входившие**
в исторические 2259/27 прогонов PR #8:

- Java ↔ PostgreSQL normalizer: все 24 whitespace,
  ISO controls, UTF-16 длина и названия-коллизии.
- V73 на базе с данными: конфликт индекса, конкурирующий
  UPDATE, version semantics, rollback при preflight error.
- V74: транзакция через UTC-полночь, revision/ack,
  повторный расчёт старого UTC-дня, нагрузка на dirty row.
- Physical spend/reconciliation: ADMIN tenant fence,
  complete/incomplete flags, retries и cross-boundary plans.
- Usage отчёты: локальный/распределённый permit,
  `REPEATABLE_READ`, HTTP 429/422 и лимит OFFSET.
- Audit: typed serializer, `MANDATORY` rollback,
  privacy-safe logs, dead-letter recovery atomicity,
  повторный recovery, immutable V68 contract.
- Audit metrics: отсутствие gauge, failure counter,
  реальные Prometheus alerts и получатель Alertmanager.
- Retention: `AuditRetentionPoolLoadIT`, Hikari pool,
  advisory lock release, lock timeout/deadlock и TPS.
- V76: reject plaintext query на новых INSERT/UPDATE,
  accept redacted query с сохранённым SHA-256,
  исторические строки без переписывания, сохранение immutable trigger,
  статус `NOT VALID` и индекс `NEEDS_REVIEW`.
- Privacy review: проверить все insert paths retrieval runs,
  отсутствие raw query в логах и отдельный retention-план для истории.
- Полные Maven/backend и frontend проверки на одном
  релизном commit, CI, backup/restore и smoke tests.

## Release candidate checks

Перед серьёзным deployment:

```text
fresh V1 → V76 (при наличии всех четырёх патчей)
populated upgrade V72 → V76; порядок/lock-timeouts
Flyway history validation
critical index validity check
backend clean test
container build/start
prod-like startup invariants
tenant isolation
auth epoch invalidation
Login/Refresh/Disable concurrency and deadlock regression
V72 fresh/populated migration compatibility
organization kill-switch STARTED-vs-Disable race/re-enable
ChatTurn chronological CHECK constraints under Testcontainers clock
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

## V73–V76: порядок release и rollback

V73–V76 должны находиться в одном согласованном release
наборе, если включаются все четыре набора изменений.
Перед Flyway: backup, preflight существующих организаций,
учёт DDL locks, проверка миграционной истории и совместимости
версий Java-кода с ожидаемой схемой.

Для V75 обязательно деплоить recovery Java-классы
с правильным `AuditEventType`; регистрация event type
без API не заменяет управляемого восстановления.

Для V74 проверить, что rollup worker не обрабатывает
открытый UTC-день и что повторные dirty revisions не теряются.

Для V76 проверить deployment-порядок writer-кода и нового CHECK:
новые retrieval runs должны сохранять redacted `query_text` и
`query_sha256`. Исторические raw queries миграция не исправляет.
Выполнить отдельный privacy review и проверку partial index.

По умолчанию Flyway не откатывает применённый production SQL
автоматически. Rollback приложения допустим лишь при
подтверждённой совместимости со схемой; иначе нужен
подготовленный forward-fix и staged cutover.

## V72 rollout — согласованное внедрение security epoch и AI kill-switch

V72 нельзя считать обычной независимой SQL-миграцией: новая схема требует от reservation path явно
записывать `authorized_organization_auth_version`, а physical executor должен уважать database
rejection. Перед выпуском нужны:

1. Согласованное обновление backend и Flyway V72: не оставлять старые workers, которые создают
   ChatTurn без текущего epoch.
2. Migration rehearsal на пустой БД V1–V72 **и** на копии населённой V71 с upgrade до V72.
   Исторические `NULL` ChatTurn нельзя backfill-ить придуманной версией.
3. PostgreSQL concurrent races: Login/Refresh/Disable; физический `STARTED` против Disable;
   Disable→Enable без reactivation старого turn.
4. Корректный finalization уже начатого provider I/O: сохранять `outcome_certainty`, usage/cost,
   audit и `AMBIGUOUS` при неопределённости; не выполнять blind retry.
5. Проверка latency и блокировок `organizations FOR SHARE/FOR UPDATE`, retry policy при SQLSTATE
   `40P01` и мониторинг времени ожидания транзакций.

Успешный локальный `./mvnw test` (2259 тестов) подтвержден отдельно и не подменяет
`clean test`, CI на релизном коммите, populated V71→V72 upgrade rehearsal, production-like
race tests и runtime/provider smoke. Не считать production release gate выполненным
только по SQL-файлу, Flyway startup либо единичному green запуску.


## PostgreSQL backup / restore

Backup не считается готовым только потому, что существует cron/script.

Нужен периодический restore drill.

## Prometheus

`/actuator/prometheus` / management endpoints — internal-only.
Production management слушает отдельный приватный адрес;
блокировать публичный ingress и проверять reachability из
сети мониторинга. Используются правила в `infra/prometheus`,
**не** `deploy/prometheus` и **не** `application-prod.yml`.

Alertmanager и реальную маршрутизацию необходимо настроить
отдельно: готовый YAML не является доставленным уведомлением.

## Provider egress

Provider endpoints должны быть allowlisted/pinned.

Redirect following с credentials запрещён.

---

# Текущие ограничения и production-readiness gaps

Здесь собраны старые риски и новые release gaps по состоянию
на 17.10.2026. Некоторые тезисы требуют повторного code audit перед изменением production behavior;
их наличие в README не подтверждает незакрытый дефект текущего `main`. Пункты, закрытые `V59–V68`,
из списка удалены: budget-state representability закрыт `V62/V64`, final-request/RAG binding —
`V62/V64/V65`,
searchable Knowledge publication — `V63`, physical response vs protocol rejection — `V66`,
а Chat/Audit evidence получил дополнительные DB backstops в `V67/V68`.

## P0 до production — release validation V72–V75 и мониторинг

- **Текущий локальный baseline зеленый:** `./mvnw test` — 2259/0/0/3; целевые PostgreSQL/Redis IT —
  27/27. Исторический сбой clock fixture не является текущим выводом Maven.
- Нет подтверждения нового полного `./mvnw clean test`,
  CI на релизном SHA и populated V72→V75 upgrade rehearsal.
- Для Audit ещё необходимо доказать Alertmanager delivery,
  atomic recovery и отсутствие секретов в exception-логах.
- Для Usage необходимы midnight-race и high-contention
  тесты dirty-date queue, physical spend и Hikari slots.
- Для Organization V73 требуется rehearsal блокировок и
  проверка коллизий на реальном наборе tenant names. Успешные локальные тесты нельзя объявлять
  достаточным release gate.
- Kill-switch синхронизирует **разрешение STARTED**, но не обещает физическую отмену уже допущенного
  HTTP I/O; если нужен сетевой hard-stop, требуется отдельный dispatch/draining протокол.
- Старые application instances без нового epoch contract при смешанной раскатке могут получить
  fail-closed ошибки. Важно описать rollback/runbook и последовательность остановки workers.

Это **release gates**, а не повод ослаблять блокировки, DB triggers, idempotency, durable evidence
или прежнюю логику recovery.


## P1 — Governed provider entry point

DB уже не разрешает новый physical attempt без v2 final-request binding, а `STARTED` требует live
fenced `PROCESSING`
turn. При этом provider adapter implementation всё равно должен оставаться недоступным для
произвольного application
call-site в обход governed execution scope.

Целевой invariant:

```text
no governed execution identity
    → no credential-bearing provider I/O
```

Это желательно подкреплять package/module visibility, explicit execution capability/token и
integration tests.

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

Нужен явный, хорошо протестированный application recovery path, который возобновляет именно эту
операцию,
а не создаёт новую route/request identity.

## P1 — Provider token/context estimation

Governance input units (`UTF8_STRUCTURAL_UNITS_V2`) и provider-native context tokens — разные
величины.

Deterministic governance accounting полезен для policy/budget envelopes, но char/structural
heuristic нельзя выдавать
за математически строгий upper bound provider tokenizer. Для моделей с критичным context fit нужен
provider-aware
tokenizer либо консервативный fail-closed bound.

## P1 — Pricing scheme breadth

V66 вводит explicit `pricing_scheme` и fail-closed legacy/unsupported states, однако текущая
поддерживаемая схема
не покрывает весь рынок provider billing semantics.

Отдельного versioned model требуют, например:

- tiered/context pricing;
- service-class pricing;
- region/residency surcharges;
- batch/async discounts;
- tool/search/image/audio dimensions;
- cache read/write variants разных providers.

Неподдерживаемая схема должна оставаться `UNPRICED`/unverifiable, а не аппроксимироваться как
flat-token pricing.

## P1 — Billing-grade settlement

`model_cost_commitment_ledger_v62` и `chat_physical_spend_v67` дают provider-evidenced accounting и
unknown-liability
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

Retrieval evidence защищает referenced chunks/generations от уничтожения, но без bounded
retention/legal-hold policy
historical evidence может удерживать storage indefinitely.

Нужен lifecycle:

- retention;
- legal hold;
- privacy deletion/anonymization;
- evidence compaction/hash/excerpt policy;
- explicit policy для Answer Passport / RetrievalRun longevity.

## P1 — Mixed scanned PDF fail-closed

Для mixed PDF OCR-required pages не должны тихо подменяться native-only extraction, если OCR
disabled/unavailable.

Strict `KNOWLEDGE_ONLY` не должен публиковать заведомо partial document как полностью готовый.

## P1 — Parser isolation

Extraction имеет timeout/thread bounds, но untrusted PDF/OOXML всё ещё парсится внутри application
JVM.

Для hardened enterprise deployment parsing стоит вынести в отдельный worker/container sandbox
boundary
с memory/CPU/time/filesystem/network limits.

## P1 — Knowledge provider attempt/cost evidence

OCR/embedding external calls пока не имеют такого же полного durable physical-attempt ledger, как
Chat AI execution.

При partial batch success + process crash возможны повторные billable calls. Нужны durable operation
identity,
attempt evidence и reconciliation semantics для embedding/OCR pipeline.

## P1/P2 — Concurrent index recovery

Interrupted `CREATE INDEX CONCURRENTLY` может оставить invalid index.

Для critical business indexes нужен release assertion по PostgreSQL catalogs (`pg_index.indisvalid`
/
`indisready`), а не только `IF NOT EXISTS`.

## P2 — Human review workflow for Knowledge storage reconciliation

V63 корректно разделяет `needs_review_at` и `reviewed_at`, но сама полноценная operator workflow
вокруг
`NEEDS_REVIEW` должна иметь:

- assignee/claim;
- resolution code;
- comment/evidence;
- resolved timestamp;
- audit event;
- safe replay/cleanup action.

## P2 — V58 legacy title ambiguity

До появления `title_source` невозможно доказать, был ли historical title `Новый чат` placeholder-ом
или осознанным user title.

V58 deterministic backfill принимает это как migration trade-off; V59 защищает дальнейшее движение
provenance назад,
но не может ретроспективно восстановить утраченное историческое намерение пользователя.

## Audit / Usage — остаточные эксплуатационные риски

- **P1:** даже при безопасном Audit logging отдельных
  классов нужно проверить все handlers/logger call sites.
- **P1:** мёртвые события нельзя восстанавливать без
  устранения root cause, операторского журнала и алерта.
- **P2:** исторические справочники с `DISTINCT` и substring
  search могут оставаться тяжёлыми при больших объёмах.
- **P2:** Hikari/advisory-lock нагрузка и масштабирование
  мониторинга должны быть измерены в production-like среде.
- **P2:** Usage dirty-date current UTC day создаёт hot row;
  при высоком TPS может понадобиться иной journal design.

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

> **SafeAI Desk — modular-monolith корпоративного AI Gateway с organization multi-tenancy,
  exactly-one-role RBAC, cookie JWT + CSRF, refresh rotation/reuse detection, user/org security
  epochs, durable idempotent ChatTurn с lease/fencing/ambiguity protection, versioned Model Control
  Plane, durable physical AI attempt evidence, Knowledge/RAG provenance + Answer Passport,
  transactional audit outbox и usage/pricing quality analytics.**

## Архитектурная идея

SafeAI Desk не пытается сделать LLM deterministic.

Вместо этого платформа делает deterministic/auditable **обвязку вокруг недетерминированного
provider**:

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

Переход к микросервисам имеет смысл только после появления реальной независимой scaling/ownership
необходимости и должен сохранять эти invariants.

---

# Frontend

Срез `frontend/package.json` и дерева `frontend/src/` в `main` на `fdcff62`, а не только старых
скриншотов. UI — React 19, TypeScript 6, React Router 7, Vite 8; Vitest / Testing Library.

```text
frontend/
├── package.json
├── src/
│   ├── api/               # HTTP clients, parsers, CSRF, chat, model, Knowledge
│   ├── auth/              # AuthContext, auth context definition, useAuth
│   ├── components/
│   │   ├── admin/         # admin tables/forms/controls
│   │   ├── chat/
│   │   └── knowledge/
│   ├── constants/         # audit event presentation constants
│   ├── domain/            # UI-level role/policy helpers
│   ├── hooks/
│   ├── pages/             # Login, Chat, Knowledge, Users, Audit, Usage, Models
│   ├── styles/
│   ├── test/              # Vitest setup
│   ├── utils/
│   ├── App.tsx
│   └── main.tsx
└── ...
```

Auth и browser transport нужно согласовывать с backend HttpOnly cookies + `X-XSRF-TOKEN`;
frontend route guard — не authorization boundary. API DTO и runtime parsers должны отказывать
на неверных публичных response shapes, не доверять raw provider body, не раскрывать токены
в логах/хранилищах браузера.

### Локальные проверки frontend

```bash
cd frontend
npm ci
npm run lint
npm run test
npm run build
```

Для объединенного прогона `npm run check`; CI-ориентированный script — `npm run ci`
(lint + coverage + production build). `npm run audit:prod` проверяет production dependencies
по npm audit policy. **Эти команды не запускались в предоставленных логах PR #8.**

Frontend routes/API contracts в точности определяются актуальными `App.tsx`, `api/`,
`pages/`, а не этим обзорным деревом; перенос endpoint без проверки обоих слоев запрещен.

---

# Поддержание документации в актуальном состоянии

README предназначен для **реализованных и проверяемых** контрактов. `ROADMAP.md` — для будущих
возможностей.
Связанные документы: `docs/00_PROJECT_OVERVIEW.md`, `docs/01_LOCAL_RUNBOOK.md`,
`docs/02_DOCKER_RUNBOOK.md`, `docs/03_AUTH_JWT_VERIFICATION.md`,
`docs/04_ORGANIZATION_USER_API.md`, `docs/05_CHAT_CORE_VERIFICATION.md`,
`docs/08_DATABASE_CHECKS.md`, `docs/09_TESTING_GUIDE.md`,
`docs/10_TROUBLESHOOTING.md`, `docs/12_KNOWLEDGE_BASE_CORE_VERIFICATION.md`,
`backend/src/main/resources/db/README_DB.md`, `docs/runMacBook/LOCAL_DEVELOPMENT_MACOS.md`.
Эти вспомогательные документы существуют в дереве, но их актуальность по отношению к README
нужно сверять перед выполнением критических команд; они не переопределяют source code. Новая
миграция требует синхронного обновления: migration map, entity/repository contract, API flows,
state-machine diagrams, runbook, known-failure status и тестовой матрицы.

При обновлении проекта сверяйте прежде всего:

- `backend/src/main/resources/db/migration/` и `flyway_schema_history` — реально применённая
  schema/version;
- актуальные Java-классы, repository signatures и controller mappings — точные backend/API
  contracts;
- `backend/pom.xml`, `frontend/package.json` — версии инструментов/библиотек;
- Surefire/Failsafe XML и последние полные логи — **реальный** статус тестов, а не исторические
  counts;
- деплойные профили — фактические security constraints и secret handling;
- concurrency/DB evidence — подтверждение, что ограничения выдерживают не только happy path, но и
  гонки/отказоустойчивость.

Никакие сведения о наличии класса, метода или SQL-файла сами по себе не являются доказательством
прохождения теста. Статусы «реализовано в исходном патче», «применено в тестовой PostgreSQL»,
«проверено интеграционным тестом» и «выпущено в production» следует документировать **раздельно**.

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
organization enabled + exact immutable authVersion checked under FOR SHARE
  ↓
V67 live ChatTurn lease/token fence
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

# Передача контекста в новый AI-чат

Этот раздел предназначен для продолжения работы над SafeAI Desk в **новом диалоге** без повторного
обсуждения восьми уже завершенных PR. Он фиксирует workflow, а не заменяет чтение исходников.

## Исторический baseline PR #8 и целевой пакет 17.10.2026

```text
Repository:  https://github.com/Amid-1/Safeai-desk
Base:        main / origin/main
Main HEAD:   fdcff62fc32131474cbc2639b1fa636edffd45bc (merge PR #8)
Java:        21
Spring Boot: 4.1.1
DB:          PostgreSQL, GitHub main Flyway V1–V72, target V73–V76
Structure:   modular monolith; common, identity, auth, user, organization,
             ratelimit, audit, usage, chat, model, ai, knowledge
PRs merged:  #1–#8; latest adds ArchUnit 1.5.1 architecture rule for common
```

**Уже сделано:** module-specific exception handlers выведены из `common.exception`;
`identity.session` содержит порт отзыва сессий; `auth` владеет JWT runtime/codec,
RSA ring, Spring Security entry points, JWT converter и BCrypt configuration.
`common` оставлен за shared error envelope, principal/roles, IP/CORS, Request ID,
production startup guards, general infrastructure. PR #8 добавил `CommonDependencyArchitectureTest`.

**Проверено до merge PR #8:** `./mvnw test`: 2259 tests, 0 failures, 0 errors, 3 skipped;
дополнительно 27/27 PostgreSQL/Redis IT, ArchUnit positive/negative test.
Чистый `main` относился к историческому PR #8. Это **не** статус текущей ветки или доказательство
того, что другие модули уже архитектурно изолированы.

## Как вести следующий архитектурный PR

1. Начать с чистого `main` и `git status -sb`; не делать коммит в `main`.
2. Снять **read-only** карту межмодульных imports и consumers предполагаемого перемещаемого класса.
3. Проверить bean registration, auto-configuration, package visibility, DTO/error contracts,
   transaction propagation, lock order, events, retention, DB constraints, тесты.
4. Разделить *общий контракт*, *владельца реализации*, *адаптер*, *оркестрацию*;
   не переносить классы механически и не формировать новый общий `common` для устранения import
   errors.
5. При обнаружении real boundary violation выбрать минимальный безопасный PR и понятный rollback.
6. Добавить/скорректировать ArchUnit правило только для **проверенной** архитектурной границы;
   создать negative control, не фиксируя его в Git.
7. Выполнить targeted tests, `./mvnw test`, нужные PostgreSQL/Redis IT и frontend checks,
   если затрагиваются UI/API. Считать общее число тестов и skipped по фактическому лог-файлу.
8. Проверить `git diff --check`, `git status`, staged diff, затем commit и PR.
9. Merge через **Create a merge commit**, затем `git switch main` →
   `git pull --ff-only origin main` → `git status -sb` → `git merge-base --is-ancestor`.
10. Синхронно обновить документацию; не удалять рабочие ветки до подтверждения merge.

## Неприкосновенные инварианты

Сохранять tenant isolation, exactly-one-role RBAC, fail-closed security, cookie JWT/CSRF,
refresh rotation/reuse/family, user/org security epochs, organization→user→token lock order,
durable ChatTurn/idempotency/lease/fencing, `AMBIGUOUS` no-blind-retry, physical
STARTED DB fence V67/V72, sealed request/route/RAG evidence, immutable SQL evidence,
pricing `unknown != zero`, audit outbox и Knowledge ACL/provenance. **Нельзя** добиваться
зеленых тестов ослаблением constraints, SQL migrations или security handlers.

Пользователь предпочитает **небольшие последовательные шаги**: команда → вывод → анализ →
следующий шаг. Объяснения по-русски; commit/PR описания — на русском, Conventional Commits
prefix допустим. Не изменять личные файлы, stash и несвязанные ветки, не создавать PR
без необходимости. При нехватке данных запросить точный файл/лог или проверить repo.

### Команды для начала нового диалога

```bash
git switch main
git status -sb
git log -1 --oneline

git grep -n -E '^import (static )?ru\.safeai\.gateway\.' -- \
  backend/src/main/java/ru/safeai/gateway/auth

cd backend
./mvnw -Dtest=CommonDependencyArchitectureTest test
```

Указывать границы подтверждения: read-only `git grep` проверяет явные Java imports;
ArchUnit — class dependencies, а ни один из них не заменяет анализ runtime flow,
Spring wiring, database triggers и concurrency.

---

# План следующего этапа

Приоритет — **не массовый перенос Java-файлов**, а доказуемое выравнивание ownership
и направление зависимостей в текущем модульном монолите.

- **1. Identity**
  - **Цель:** Аудит `identity.session` и consumers
  - **Что должно получиться:** Узкий стабильный порт без imports из application modules; ArchUnit
    при подтверждении чистоты
- **2. Auth / User / Organization**
  - **Цель:** Инвентаризация cyclic compile/runtime coupling
  - **Что должно получиться:** Security-state contracts и session revocation без обратных ссылок на
    auth implementation
- **3. Rate limit**
  - **Цель:** Выделить наружу только policy/service contracts
  - **Что должно получиться:** Redis/Lua/HMAC внутри реализации; callers не зависят от storage
    details
- **4. Audit**
  - **Цель:** Проверить outbox API + transactional guarantees
  - **Что должно получиться:** Domain modules depend on audit contract, storage/retention stays
    audit-owned
- **5. Usage**
  - **Цель:** Отделить event/fact contracts от reporting implementation
  - **Что должно получиться:** Ограничение heavy queries, корректность watermark/dirty dates,
    независимость от UI
- **6. Model / AI**
  - **Цель:** Разделить governance permission и physical provider execution
  - **Что должно получиться:** Exact sealed request/attempt evidence; запрет bypass provider I/O;
    целевые ArchUnit tests
- **7. Knowledge**
  - **Цель:** Отделить ACL/ingestion/retrieval/RAG adapters
  - **Что должно получиться:** Контракты не раскрывают storage internals; сохранены
    provenance/fenced publication
- **8. Chat**
  - **Цель:** Оркестратор без обратных зависимостей от нижележащих модулей
  - **Что должно получиться:** Durable ChatTurn и recovery остаются центром workflow без
    циклического ownership
- **9. Frontend / API**
  - **Цель:** Сопоставить DTO, ошибки, RBAC и frontend runtime parsers
  - **Что должно получиться:** Проверяемая end-to-end совместимость, без ослабления backend
    authorization


Порядок — гипотеза для **аудита**, а не разрешение сразу вводить все dependency rules.
Сначала строится фактический dependency graph; архитектура и контракты согласуются с
доменными инвариантами. Любое изменение — отдельный маленький PR с целевыми тестами.

---

**Актуализация:** 17 октября 2026 года. Сохранён исторический baseline PR #8;
учтены подготовленные V73–V76, Model pricing, Knowledge ingestion и Audit/Usage hardening.
GitHub `main` всё ещё содержит V1–V72 на момент проверки;
локальный checkout и реальная схема БД не прочитаны.
Полный release gate и deployment не заявляются.
---

# Релизный контроль V73–V76

Выполнить перед внесением README и патчей в основную ветку:

```bash
cd ~/Workspace/Projects/Products/SafeAI-Desk

git status -sb
git diff --check
git diff --stat

find backend/src/main/resources/db/migration \
  -maxdepth 1 -name 'V7[3456]__*.sql' -print

cd backend
./mvnw clean test
```

Сравнить `flyway_schema_history` с именами новых SQL:

```sql
SELECT installed_rank, version, script, success
FROM public.flyway_schema_history
WHERE version IN ('72', '73', '74', '75', '76')
ORDER BY installed_rank;
```

После миграции проверить canonical names, dirty queue,
`audit_event_types`, V76 CHECK, retrieval query redaction,
review queue index и PostgreSQL index validity. Проверить
два экземпляра backend, Redis/DB failures, retention contention
и реальные события `firing` плюс получение уведомления.

Для коммита документации использовать отдельную осмысленную
запись, а не объявлять «проверено в production» без evidence.

**Неизменяемое правило:** миграции V1–V72 не переписывать;
если V73–V76 уже присутствуют в `flyway_schema_history`,
изменять их checksum нельзя. Новые corrections — новой
версией миграции после проверки занятой нумерации.
