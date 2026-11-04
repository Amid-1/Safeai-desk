<!-- SafeAI-Desk/README.md -->

# SafeAI Desk

**SafeAI Desk** — production-oriented корпоративная AI-платформа / AI Gateway для безопасного и
управляемого использования внешних AI-моделей внутри организаций.

> **Актуализировано:** 31 октября 2026 года. Документ описывает проект и локально подготовленные изменения вплоть до **Flyway V84**.
> **Репозиторий:** `Amid-1/Safeai-desk`; основной файл документации — `README.md` в корне.
> **Рабочая ветка, указанная пользователем 31.10.2026:** `fix/knowledge-upload-confirmation-20261025`;
> в ней имеются staged/unstaged изменения. Исторически проверенный baseline PR #8 (`fdcff62`) относится к более раннему `main` (V1–V72), **не** к текущему checkout.
> **Backend:** Java 21, Spring Boot (точную версию определяет `backend/pom.xml`), PostgreSQL/pgvector, Redis,
> S3-compatible Object Storage, Flyway, Micrometer/Prometheus; frontend — React/TypeScript.
> **Состояние V78–V84:** представлены миграции, backend-компоненты удаления документов и баз знаний,
> операторская диагностика/контролируемый resume, аудит переходов и метрики. Присутствие файлов и применение patch
> не равнозначны выполнению всех тестов, применению Flyway или production deployment.
> **Проверки на 31.10.2026:** Testcontainers PostgreSQL 16.15 успешно прошёл Flyway validation и применение **84 миграций на пустой тестовой БД**. Отдельный полный backend-прогон завершился с **2455 tests, 0 failures, 1 error, 3 skipped**: некорректная подготовка `NEEDS_REVIEW → BLOCKED_BY_RETENTION` в `KnowledgeDeletionOperatorSafetyIntegrationTest`, которую V84 правильно заблокировал. Исправление тестового сценария подготовлено отдельно, но новый успешный полный `./mvnw clean verify` ещё не представлен. Frontend CI, миграция заполненной БД, real S3/MinIO end-to-end и production deployment не подтверждены.
> **Подтверждено 31.10.2026:** `git diff --check`, проверки синтаксиса shell, `check-config-topology.sh`, `check-prometheus-config.sh` и `promtool` для конфигурации и трёх наборов правил (7 + 6 + 3); тесты правил Knowledge erasure также прошли. Это **не** проверка production deployment, Alertmanager или разрешение на физическое удаление.
> **Режим безопасности:** удаление и operator recovery должны оставаться выключенными по умолчанию.
> **Стадия:** production-oriented pre-1.0; не заявляются compliance certification, SLA/HA,
> invoice-grade billing или гарантированное удаление из backup/external providers.

**Источник истины:** файлы текущего checkout, история Flyway,
фактическая конфигурация и результаты тестов. Подготовленный
патч, успешно применённая миграция и production deployment —
три разных состояния; README не подменяет их друг другом.

---

## Содержание

1. [Что решает проект](#что-решает-проект)
2. [Текущее состояние](#текущее-состояние)
3. [Архитектура](#архитектура)
4. [Технологический стек](#технологический-стек)
   - [Карта репозитория на 31.10.2026](#карта-репозитория-на-31102026)
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
22. [Безопасное удаление Knowledge — V78–V84](#безопасное-удаление-knowledge--v78v84)
23. [Ключевые backend contracts](#ключевые-backend-contracts)
24. [Database / Flyway V1–V84](#database--flyway-v1v84)
25. [Observability и operational jobs](#observability-и-operational-jobs)
26. [Тестирование](#тестирование)
27. [Local development](#local-development)
28. [Production deployment notes](#production-deployment-notes)
29. [Текущие ограничения и production-readiness gaps](#текущие-ограничения-и-production-readiness-gaps)
30. [Позиционирование проекта](#позиционирование-проекта)
31. [Frontend](#frontend)
32. [Поддержание документации](#поддержание-документации-в-актуальном-состоянии)
33. [Главное правило дальнейшей эволюции](#главное-правило-дальнейшей-эволюции)
34. [Передача контекста в новый AI-чат](#передача-контекста-в-новый-ai-чат)
35. [План следующего этапа](#план-следующего-этапа)
36. [Релизный контроль V73–V84](#релизный-контроль-v73v84)

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

Историческая контрольная точка — подтверждённый baseline V71–V72 (PR #8).
К 31.10.2026 представлены миграции V73–V84, новые документные и базовые
этапы Knowledge erasure, read-only диагностика, V83 operator recovery и V84
transactional audit/metrics. Отдельно подтверждены проверки Prometheus-конфигурации.
В тестовой изолированной PostgreSQL схема V1–V84 применена успешно, однако
последний полный backend-прогон имел один ошибочный test fixture. Это не подтверждает
успешный Testcontainers/CI suite, запуск destructive worker, применение миграций к
пользовательской БД или production readiness.

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

## Knowledge: управление физическим удалением — срез 31.10.2026

- **V78–V81:** `AVAILABLE → DELETING`, durable/idempotent document request, независимые
  immutable version/chunk identities, guarded content erasure, проверяемая очистка всех версий
  оригинальных S3-объектов. `enabled=false` само по себе **не** является удалением.
- **V82:** добавлены `knowledge_base_identities_v82`, `knowledge_base_deletions_v82`,
  base lifecycle, защита новых записей, защищённое удаление живой строки базы после завершения дочерних операций.
- **V83:** отдельный read-only status API; ADMIN diagnostics с актуальной DB-проверкой роли;
  ограниченное продолжение только `NEEDS_REVIEW` с оптимистичной сверкой времени и required audit.
- **V84:** SQL-trigger записывает обязательный transactional `audit_outbox` intent при реальных переходах
  `DELETING → DELETED / NEEDS_REVIEW / BLOCKED_BY_RETENTION`; Micrometer публикует низкокардинальные
  count/age/retry/freshness/failure; подготовлены канонические правила Prometheus и их тесты.
- **Блокировка:** пять флагов приёма/исполнения/операторского продолжения по умолчанию `false`.
  `BLOCKED_BY_RETENTION` не возобновляется административной командой и остаётся неизменяемым.
- **Ограничение обещания удаления:** после `DELETED` возможно законное сохранение audit,
  immutable provenance, исторических RAG/AI-артефактов и резервных копий по отдельной политике.
- **Статус релиза:** код и схема подготовлены; enablement требует отдельного production-like
  end-to-end release gate, а не только компиляции, применённого SQL или проверенного YAML.
- **V85:** 


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


## Хронология функциональности V73–V84 на 31.10.2026

Ниже приведены **состояния исходного кода и назначение миграций**, а не утверждение
об их применении к production PostgreSQL. Историческая граница PR #8 — V72;
все новые стадии требуют подтверждения по фактическому `git status`, CI и
`flyway_schema_history`.

| Миграция / этап | Актуальная функциональность | Эксплуатационная оговорка |
|-----------------|---|---|
| V73             | Канонизация Unicode whitespace в именах организаций; защита от коллизий при backfill | Применяется после анализа имен на populated БД |
| V74             | Учёт dirty-date при UTC-полуночи, индексы физического usage, reconciliation | Не является invoice-grade settlement |
| V75             | Тип аудита controlled dead-letter recovery | Работает только совместно с Java recovery и V68 guards |
| V76             | Privacy новых retrieval runs: redacted query + SHA-256; индекс review | Исторические строки не очищаются автоматически |
| V77             | Backfill legacy названий чатов, сохранение title provenance | Не вызывает внешние AI-модели |
| V78             | Durable документная операция удаления, lifecycle, lock/ingestion fences | SQL не запускает физический purge |
| V79             | Тип обязательного аудита принятия удаления документа | `202` означает принятие, не успех |
| V80             | Immutable identities для версий/chunks, перенос и валидация исторических FK | Audit/provenance остаются после контентного purge |
| V81             | DB fences для erasure, проверка lease, inventory S3 keys и запрет преждевременного `DELETED` | Object Lock и legal hold не обходятся |
| V82             | Parent-op удаления Knowledge Base, lifecycle базы, tombstone identity, дочерние операции | Нельзя подтверждать удаление базы до детей |
| V83             | Раздельные read-only GET и destructive routes, диагностика и controlled `NEEDS_REVIEW` resume | Только current ADMIN, все 5 flags; `BLOCKED_BY_RETENTION` immutable |
| V84             | Атомарный audit outbox на terminal/attention transitions, low-cardinality Micrometer, Prometheus alerts | Без backfill старых событий; alerts требуют рабочего scrape/Alertmanager |
| V85             |  |  |

**Состояние frontend к 31.10:** существуют UI подтверждения удаления документов/баз,
история операций и controller для capability/status. Изменение V84 отделило сбой
GET истории от пустого результата и не скрывает историю при `FEATURE_DISABLED`.
Права и возможности определяет backend; элементы интерфейса не служат security boundary.

**Состояние поставки:** созданные SQL и Java-классы, успешный `promtool` и
пройденные исторические тесты не означают, что пять destructive flags можно
включать. До отдельной production-like приёмки они остаются выключенными.

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

## Knowledge erasure: границы V78–V84

```text
Browser / React
  |  GET capability, status, history                 (read-only, tenant-scoped)
  |  DELETE + If-Match + Idempotency-Key              (feature-gated)
  |  ADMIN diagnostics / reviewed resume             (V83, strictly gated)
  v
Spring Boot 4.x / Knowledge modules
  +-- AcceptanceService (document) / BaseDeletionService (base)
  |     +-- PostgreSQL transaction: request + lifecycle + required audit
  +-- DocumentDeletionExecutor (V81) / BaseDeletionExecutor (V82)
  |     +-- claim lease_token / expiration / fencing in PostgreSQL
  |     +-- S3/MinIO: enumerate and verify ALL object versions/markers
  |     +-- PostgreSQL: fenced content cleanup, state transition
  |            +-- V84 AFTER UPDATE: terminal audit_outbox intent (same transaction)
  +-- OperatorController + OperatorService (V83)
  |     +-- current ADMIN, organization, expectedUpdatedAt, mandatory audit
  +-- KnowledgeErasureObservability (V84, SELECT only)
        +-- Micrometer -> internal /actuator/prometheus
                           -> Prometheus rules -> Alertmanager (if configured)

PostgreSQL retains immutable V78/V82 request identities and V80 provenance.
Audit-outbox processor asynchronously delivers audit events; S3/network deletion
NEVER belongs to the V84 audit trigger transaction.
```

Разделение принципиально: принятие запроса, исполнение, юридически заблокированное
хранение, подтверждённый `DELETED`, запись аудита и доставка алерта — **шесть разных
фактов**. В частности, `202 Accepted`, `NEEDS_REVIEW → DELETING` после resume и
успешный HTTP-вызов S3 не подтверждают полную физическую очистку.

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


PR #8: исходный коммит `103c587`, merge-коммит `fdcff62`. Это **историческое** состояние `main`; на 31.10.2026 показана другая рабочая ветка `fix/knowledge-upload-confirmation-20261025` с изменёнными и новыми файлами. Статус чистого дерева к ней не относится.

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
- MinIO Java SDK / S3-compatible storage (`io.minio.MinioClient` в текущем коде);
- pgvector;
- Apache PDFBox;
- Apache POI;
- jsoup;
- document-format-specific extractors;
- Micrometer / Prometheus;
- structured logging;
- Docker / Docker Compose.

Точные dependency versions определяет `backend/pom.xml`; README не дублирует их как источник истины.
Для исторического среза `fdcff62` проверены также `postgresql.version=42.7.14`,
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

Карта структуры каталогов приведена далее. Для точного перечня Java-файлов
используйте текущий checkout, а не историческое дерево из документации.


## Карта репозитория на 31.10.2026

Это **структурная схема**, а не копия `find` из непереданного локального checkout.
Пути проверены по предоставленным снимкам IDE, конфигурационным файлам и архивам
V82–V84. Стандартный `target/`, IDE metadata, `.env`, секреты и локальные
object-storage данные не являются частью релиза.

```text
SafeAI-Desk/
├── .github/                          # CI workflows
├── backend/
│   ├── .mvn/ / mvnw / pom.xml         # Java 21 / Maven Wrapper
│   ├── Dockerfile
│   ├── .env.example                    # local template; commit allowed
│   ├── .env.prod.example               # production template; commit allowed
│   ├── .env / .env.prod                # local runtime, NEVER commit
│   ├── .local-secrets/                 # local-only secret storage
│   ├── docs/config/                    # documentation examples, NOT auto-loaded
│   └── src/
│       ├── main/java/ru/safeai/gateway/  # modular monolith
│       ├── main/resources/db/migration/  # immutable Flyway V1..V84
│       ├── main/resources/db/local-migration/
│       ├── main/resources/application*.yml
│       ├── main/resources/logback-spring.xml
│       ├── test/java/ru/safeai/gateway/
│       └── test/resources/             # JUnit and Spring test profiles
├── frontend/
│   ├── package.json / package-lock.json
│   ├── scripts/                        # architecture/lint/dependency checks
│   └── src/                            # app / entities / features / pages / shared
├── infra/
│   ├── docker-compose.yml              # production topology
│   ├── docker-compose.local.yml        # local topology
│   ├── prometheus/
│   │   ├── prometheus.yml              # ACTIVE rule_files binding
│   │   ├── rules/
│   │   │   ├── safeai-audit-outbox.yml
│   │   │   ├── safeai-knowledge-review.yml
│   │   │   └── safeai-knowledge-erasure.yml
│   │   └── tests/knowledge-erasure.test.yml
│   ├── scripts/
│   │   ├── check-config-topology.sh
│   │   ├── check-prometheus-config.sh
│   │   ├── validate-prod.sh
│   │   ├── deploy-prod.sh
│   │   └── ...                          # build/backup/restore/secret scripts
│   ├── postgres/ / nginx/ / host-nginx/
│   ├── config/pricing.yml.example
│   ├── secrets/jwt-keys.yml.example
│   └── systemd/                        # backup timer/service
├── docs/
│   ├── config/
│   │   ├── CONFIGURATION_LAYOUT.md
│   │   └── PRODUCTION_CONFIG_AUDIT_2026-10-31.md
│   └── knowledge-erasure/              # V84 operator/readiness notes, if installed
├── scripts/                             # local convenience tooling
├── .gitignore / .dockerignore
└── README.md                            # this entry point
```

**Важно:** `backend/deploy/prometheus/` — отдельный legacy/example-каталог в
некоторых снимках. Он не становится активным из-за названия: актуальные
`infra/docker-compose*.yml` монтируют только `infra/prometheus`.
`ops/prometheus/knowledge-erasure-alerts.yml`, поставленный в V84 ZIP как
пример, не следует одновременно подключать с каноническими rules: это создаёт
дубликат аудиторского alert. Источником истины служит Compose mount + `rule_files`.

# Структура frontend

Начиная с refactoring frontend использует feature-oriented каталоги. В архиве
приведён проверяемый шаблон схемы; при установке `sync_readme_inventory.py`
автоматически заменяет схему полным деревом реальных `frontend/` файлов из текущего
checkout (без `node_modules`, `.env` и результатов сборки). Это исключает устаревшую
плоскую структуру `src/api`, `src/components` и пропуски новых React/TypeScript файлов.

```text
frontend/
├── package.json / package-lock.json
├── scripts/
│   ├── audit-dependencies.cjs
│   ├── check-architecture.cjs
│   ├── verify-source-layout.cjs
│   └── ...                            # tests for module dependencies
├── src/
│   ├── app/                           # bootstrap, routing
│   ├── entities/
│   │   └── knowledge/api/
│   │       ├── knowledgeDocumentDeletionApi.ts
│   │       ├── knowledgeBaseDeletionApi.ts
│   │       └── knowledge/             # parsers, DTO types
│   ├── features/
│   │   └── knowledge/
│   │       ├── controller/
│   │       │   ├── useKnowledgeDeletionController.ts
│   │       │   └── useKnowledgeDeletionController.test.tsx
│   │       ├── ui/
│   │       │   ├── BaseDeletionHistory.tsx
│   │       │   ├── KnowledgeBaseDeleteAction.tsx
│   │       │   ├── KnowledgeDetailsWorkspace.tsx
│   │       │   └── details/
│   │       │       ├── KnowledgeDeleteDialog.tsx
│   │       │       └── KnowledgeDetailsContent.tsx
│   │       └── ...                     # display and editing components
│   ├── pages/
│   │   ├── KnowledgeDetailsPage.tsx
│   │   ├── KnowledgeDetailsPage.test.tsx
│   │   ├── KnowledgePage.test.tsx
│   │   └── ...
│   ├── shared/                        # HTTP client, UI, helpers
│   ├── test/                          # fixtures, setup
│   └── main.tsx
├── tsconfig*.json
└── vite.config.ts
```


Список конкретных файлов и ограничений направленности imports проверяется
`frontend/scripts/check-architecture.cjs`, `verify-source-layout.cjs` и
`audit-dependencies.cjs`. При `FEATURE_DISABLED` история операций **не должна исчезать**: V84 отделяет
`GET` истории и статуса от destructive DELETE. Ошибка запроса истории должна
отображаться как ошибка, а не как «операций нет». Тесты в V84 архиве охватывают
этот контракт; полное прохождение текущего `npm run ci` необходимо проверить
в локальной ветке. Команда «Удалить…» является UI capability, а не разрешением
backend на физическую очистку.

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
│   │   ├── migration/         # V1 ... V84 (все 84 файла, подробно ниже)
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

Дерево объединяет исторический baseline и файлы, обнаруженные в предоставленных
архивах и подготовленных патчах V73–V84. Это не результат `ls` актуальной рабочей
ветки и не подтверждает коммит, успешную миграцию либо запуск worker.

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
│   │   │               │   ├── controller/   # document/base DELETE, read/status, operator
│   │   │               │   ├── dto/          # deletion responses / capability
│   │   │               │   ├── repository/   # durable document operation queries
│   │   │               │   ├── service/      # см. полный inventory всех классов ниже; V78–V84 включены
│   │   │               │   ├── storage/      # S3/MinIO verified version purge
│   │   │               │   ├── ingestion/ / generation/ / retrieval/ / rag/
│   │   │               │   └── ...          # extraction, embedding, configuration
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
│   │       │   ├── migration/
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
│   │       │       ├── V77__backfill_legacy_default_chat_titles.sql
│   │       │       ├── V78__knowledge_document_deletion_foundation.sql
│   │       │       ├── V79__knowledge_deletion_request_audit_type.sql
│   │       │       ├── V80__knowledge_erasure_provenance_identities.sql
│   │       │       ├── V81__knowledge_erasure_executor_guards.sql
│   │       │       ├── V82__knowledge_base_erasure_foundation.sql
│   │       │       ├── V83__knowledge_erasure_operator_audit_types.sql
│   │       │       ├── V84__knowledge_erasure_terminal_audit.sql
│   │       │       └── V85__knowledge_rag_execution_leases_and_erasure_guards.sql
│   │       │
│   │       │   └── README_DB.md              # документация DB; структура db/
│   │       │
│   │       ├── META-INF/
│   │       ├── application.yml
│   │       ├── application-local.yml
│   │       ├── application-local-nginx.yml
│   │       ├── application-prod.yml
│   │       ├── application-prod-knowledge.example.yml
│   │       ├── application-prod-knowledge-reconciliation.example.yml
│   │       ├── application-security-example.yml
│   │       └── logback-spring.xml
│   │
│   └── test/
│       └── java/
```

### V78–V84: классы и интеграционные тесты по путям

Имена классов сверены с приложенными исходными файлами/архивами; наличие каждого файла в актуальной локальной ветке следует проверить отдельно.
Обозначение версии говорит о **появлении в пакете**, а не о подтверждённом
production rollout; доступность в конкретном checkout нужно сравнить с `git ls-files`.

```text
backend/src/main/java/ru/safeai/gateway/knowledge/
├── controller/
│   ├── KnowledgeDocumentDeletionCapabilityController.java  # independent GET
│   ├── KnowledgeDocumentDeletionController.java            # destructive DELETE
│   ├── KnowledgeDocumentDeletionReadController.java        # V83 GET history/status
│   ├── KnowledgeBaseDeletionController.java                # V82 base API/status
│   └── KnowledgeDeletionOperatorController.java            # V83 ADMIN diagnostics/resume
├── dto/
│   ├── KnowledgeDocumentDeletionResponse.java
│   └── KnowledgeBaseDeletionResponse.java
├── repository/
│   └── KnowledgeDocumentDeletionRepository.java
├── service/
│   ├── KnowledgeDocumentDeletionAcceptanceService.java     # V78/V79
│   ├── KnowledgeDocumentDeletionExecutor.java              # V81
│   ├── KnowledgeBaseDeletionService.java                   # V82
│   ├── KnowledgeBaseDeletionExecutor.java                  # V82
│   ├── KnowledgeDeletionOperatorService.java               # V83
│   └── KnowledgeErasureObservability.java                  # V84 read-only
└── storage/
    ├── ObjectStorage.java                                 # purge interface
    └── S3ObjectStorage.java                               # verified version purge

backend/src/main/java/ru/safeai/gateway/audit/
└── AuditEventType.java                                    # V79/V82/V83/V84 types

backend/src/test/java/ru/safeai/gateway/knowledge/
├── integration/
│   ├── KnowledgeBaseDeletionFoundationIntegrationTest.java
│   ├── KnowledgeBaseDeletionRecoveryConcurrencyIntegrationTest.java
│   ├── KnowledgeBaseDeletionRealJvmCrashIntegrationTest.java
│   ├── KnowledgeBaseObjectLockIntegrationTest.java
│   ├── KnowledgeDeletionOperatorSafetyIntegrationTest.java
│   └── KnowledgeErasureTerminalAuditIntegrationTest.java
├── service/
│   ├── KnowledgeBaseDeletionServiceSafetyTest.java
│   └── KnowledgeErasureObservabilityTest.java
└── storage/
    └── KnowledgeDocumentErasureRealMinioIntegrationTest.java
```

Дополнительные тесты документного executor, version purge и отдельных S3
провайдеров перечислены в разделе «Тестирование». Тестовый worker для
kill/restart запускается в отдельной JVM, а не в production process.

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

## Каноническое расположение конфигурации — V84 (31.10.2026)

**Нельзя «привести всё к одному `application.yml`».** Runtime Spring, Compose,
настоящие файлы секретов, example-шаблоны и тестовые профили выполняют
разные роли. Правка примера в `backend/docs/config` сама по себе ничего не
активирует; production остаётся управляемым через `/etc/safeai/config`,
`/etc/safeai/secrets`, `backend/.env.prod` и проверенные изображения контейнеров.

Разделять **Spring runtime**, **Compose deployment**, **Prometheus monitoring** и
**reference templates**. Одинаковое имя в другом каталоге не делает файл активным.

| Роль | Канонический источник |
|---|---|
| Общие свойства Spring | `backend/src/main/resources/application.yml` |
| Local / prod | `backend/src/main/resources/application-local.yml`, `application-prod.yml` |
| JUnit integration | `backend/src/test/resources/application-test.yml`, `application-auth-postgres-it.yml` |
| Production/local Compose | `infra/docker-compose.yml`, `infra/docker-compose.local.yml` |
| Prometheus + alerts | `infra/prometheus/prometheus.yml`, `infra/prometheus/rules/*.yml` |
| Erasure monitoring regression | `infra/prometheus/tests/knowledge-erasure.test.yml` |
| Production env template | `backend/.env.prod.example` (commit разрешён) |
| Production host env | `backend/.env.prod` (**не** коммитить) |
| Pricing / secrets на host | `/etc/safeai/config/pricing.yml`, `/etc/safeai/secrets/` |
| Документационные примеры | `backend/docs/config/`, `infra/config/*.example`, `infra/secrets/*.example` |

Файлы `backend/deploy/prometheus/*` — **legacy** с точки зрения актуального
`infra/docker-compose*.yml`: они не монтируются и не активируют алерты. То же
относится к `ops/prometheus/*`, если его явно не включает иной deployment.
Не удалять legacy файлы, пока не выполнены `git grep` по ссылкам и review скриптов.

31.10.2026 конфигурационным merge добавлены `check-config-topology.sh`,
`check-prometheus-config.sh`, erasure rules/tests, уточнены env template,
production Compose/validator/deploy. Применение merge выполнено пользователем
без изменения runtime secrets и базы. Сообщённые `promtool` проверки успешны;
для release всё равно нужны Compose render with actual **non-secret**
production settings, pinned image digest, проверка секретов на host и
Alertmanager routing. Подробности: `docs/config/CONFIGURATION_LAYOUT.md`,
`docs/config/PRODUCTION_CONFIG_AUDIT_2026-10-31.md`.

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
### Безвозвратное удаление документов (опционально, по умолчанию выключено)

```yaml
safeai:
  knowledge:
    deletion:
      accept-enabled: false
      executor-enabled: false
      poll-delay: PT20S
```

- `accept-enabled`: регистрация destructive HTTP DELETE/controller.
- `executor-enabled`: регистрация фонового `KnowledgeDocumentDeletionExecutor`.
- **Оба** document-флага должны быть `true`, чтобы backend разрешил приём
  destructive DELETE; status/list GET с V83 вынесены в независимый read-only controller.
- Сам read-only capability endpoint регистрируется **независимо** от двух флагов;
  проверяет tenant и `OWNER` на backend и при выключенной функции возвращает
  `200 {"allowed":false,"reason":"FEATURE_DISABLED"}` для уполномоченного пользователя.
- Переменные среды для Spring Boot:
  `SAFEAI_KNOWLEDGE_DELETION_ACCEPT_ENABLED`,
  `SAFEAI_KNOWLEDGE_DELETION_EXECUTOR_ENABLED`.
- **Нельзя включать** оба флага только ради активации UI. Сначала выполнить
  migration rehearsal и полную destructive integration matrix на изолированном стенде.
- V82 добавляет отдельные `safeai.knowledge.base-deletion.accept-enabled` и
  `safeai.knowledge.base-deletion.executor-enabled` (default `false`). V83 добавляет
  `safeai.knowledge.deletion.recovery-enabled` (default `false`). Подробная матрица ниже.


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

## V84: транзакционный аудит завершения удаления Knowledge

В `V84__knowledge_erasure_terminal_audit.sql` зарегистрированы **шесть** типов:
`KNOWLEDGE_DOCUMENT_DELETION_COMPLETED`,
`KNOWLEDGE_DOCUMENT_DELETION_REVIEW_REQUIRED`,
`KNOWLEDGE_DOCUMENT_DELETION_RETENTION_BLOCKED` и аналогичные
`KNOWLEDGE_BASE_DELETION_*`.

`trg_document_erasure_terminal_audit_v84` / `trg_base_erasure_terminal_audit_v84`
срабатывают **после реального UPDATE state**, когда операция перешла из
`DELETING` в `DELETED`, `NEEDS_REVIEW` либо `BLOCKED_BY_RETENTION`.
`enqueue_knowledge_erasure_terminal_audit_v84()` вставляет outbox intent в **той же
PostgreSQL транзакции**; rollback отменяет и state, и audit. Для одного перехода
создаётся один event; повторный вход в `NEEDS_REVIEW` после санкционированного
V83 resume может породить новое событие, что корректно отражает новую попытку.

Детали содержат только UUID операции/базы/документа, state, предыдущий state,
число попыток и безопасный код ошибки; `actor_user_id = NULL` обозначает системный
worker. Запрещено помещать в этот payload storage keys, file names, query text,
access tokens и provider exception strings. Асинхронный `AuditOutboxProcessor`
проводит доставку отдельно; V84 **не** backfill-ит старые завершённые операции.

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

Watchdog `ChatTurnLeaseService` использует
`ChatTurnRepository.renewLeaseUsingDatabaseClock(turnId, processingToken, leaseMillis)` — PostgreSQL `clock_timestamp()`
определяет и проверку действующего срока, и новую границу lease в одном SQL-запросе. Сохраняются условия `PROCESSING`,
точного `processing_token` и непросроченной аренды; результат, отличный от одной обновлённой строки, означает потерю
ownership. Ошибка БД также инвалидирует watchdog; нельзя переключаться на разрешающий fallback по локальным часам JVM.
Сигнатура конструктора service сохранена для совместимости существующих consumers.


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

Новая reservation-путь вызывает перегрузку `ChatQuotaService.reserve(..., ChatQuotaReservation)` вместо устаревшего
пятиаргументного `reserve(...)`. `ChatQuotaReservation` привязывается к immutable `ModelRouteResult.decisionId`,
использует `estimatedInputTokens`, `estimatedOutputTokens`, верхнюю оценку `estimatedMaxCostUsd` и `pricingComplete`.
Сервис отдельно подтверждает поддерживаемую схему через `hasVerifiedRoutePricingScheme`; неизвестная стоимость не
подменяется доказанным нулём и не обходит денежный hard quota. Старую перегрузку не следует возвращать в production-flow
лишь ради предупреждений IDE.


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

## Chat title lifecycle — V58/V77

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

### Автоматическое и ручное переименование — интеграция 17.10.2026

- `ChatTitlePolicy.automaticTitle()` формирует заголовок детерминированно из первого принятого сообщения пользователя,
  не вызывая LLM; длина ограничена 72 Unicode code points.
- Frontend при создании пустого чата должен отправлять `title: null` или неявное значение по контракту
  `CreateChatRequest`, **не** строку `«Новый чат»` как пользовательский заголовок: иначе `ChatSessionEntity.create()`
  установит `title_source=USER` и автоматическое назначение не сработает. Совместимость JSON уточняется по актуальному
  DTO.
- `ChatTurnReservationService.reserveOrReplay()` выполняет проверку идемпотентности до любых новых изменений заголовка;
  при первом новом принятом USER-сообщении под `ChatTurnMutexRepository.lockChat()` вызывает `assignAutomaticTitle(...)`
  только если `title_source=DEFAULT`. Все изменения заголовка принадлежат той же транзакции reservation и откатываются
  при её отказе.
- Если заголовок имеет источник `AUTO` или `USER`, последующие сообщения не меняют его. Явное изменение пользователем
  имеет приоритет и переводит источник в `USER`.
- `ChatTitleService.rename()` проверяет владельца/организацию, использует ту же mutex boundary и регистрирует
  `CHAT_RENAMED` без помещения текста заголовка в audit details; повторное одинаковое пользовательское переименование
  идемпотентно.
- Заголовок в истории должен перечитываться из API после отправки сообщения, чтобы новое название появилось без
  обновления страницы. Успешный ответ провайдера **не является** необходимым условием для названия уже принятого
  сообщения: наименование привязано к успешной reservation transaction.

### V77 — однократный backfill исторических заголовков

`V77__backfill_legacy_default_chat_titles.sql` обрабатывает только строки `chat_sessions`, где `title = 'Новый чат'`.
Для них по первому непустому `USER`-сообщению (сортировка `created_at, id`) вычисляется заголовок с ограничением до 72
символов, устанавливается `title_source='AUTO'` и увеличивается optimistic `version`. Остальные пользовательские
названия сохраняются.

Пустые старые чаты с заглушкой, без USER-сообщений, получают `title_source='DEFAULT'`, чтобы первое новое сообщение
могло назвать их автоматически. Миграция не передаёт сообщения провайдеру и не изменяет tenant ownership. При повторном
исполнении обновлённые строки не подходят под условие заглушки; однако **Flyway не следует запускать повторно с
изменённой checksum**.

**Неустранимая неоднозначность legacy:** старый frontend мог записать буквальную строку `Новый чат` как `USER`; по
данным БД нельзя надёжно отличить это от сознательного ручного выбора такого же названия. V77 реализует явную политику
восстановления всех заглушек, заказанную владельцем проекта. Перед применением к populated БД необходимо сделать backup
и оценить этот компромисс. V58 задним числом не менять.

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
│   ├── AiExecutionAttemptAuthorization
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
│   ├── KnowledgeErasureReleaseInvariantVerifier
│   ├── KnowledgeIngestionProperties
│   ├── KnowledgeOcrConfigurationInvariantVerifier
│   ├── KnowledgeOcrProperties
│   ├── KnowledgeProcessingConfiguration
│   ├── KnowledgeRagProperties
│   └── KnowledgeRetrievalProperties
├── controller/
│   ├── AnswerPassportController
│   ├── KnowledgeBaseController
│   ├── KnowledgeBaseSearchController
│   ├── KnowledgeDocumentController
│   ├── KnowledgeDocumentDeletionCapabilityController
│   ├── KnowledgeDocumentDeletionController
│   ├── KnowledgeDocumentDeletionReadController
│   ├── KnowledgeBaseDeletionController
│   ├── KnowledgeDeletionOperatorController
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
│   ├── KnowledgeBaseDeletionResponse
│   ├── KnowledgeDocumentDeletionResponse
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
│   ├── KnowledgeDocumentLifecycleState
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
│   ├── KnowledgeChatContext
│   ├── KnowledgeCitationValidator
│   ├── KnowledgeContextAssembler
│   ├── KnowledgeContextSource
│   ├── KnowledgeHashing
│   ├── KnowledgeMode
│   ├── KnowledgeRagContextReceiptRepository
│   ├── KnowledgeRagContextReceiptService
│   ├── KnowledgeRagContextRevokedException
│   ├── KnowledgeRagErasureGuard
│   ├── KnowledgeRagExecutionPermitService
│   ├── KnowledgeRagService
│   ├── RagCitation
│   ├── RagCompletion
│   └── RagPreparation
├── repository/
│   ├── KnowledgeBaseMembershipRepository
│   ├── KnowledgeBaseRepository
│   ├── KnowledgeDocumentDeletionRepository
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
│   ├── KnowledgeBaseDeletionExecutor
│   ├── KnowledgeBaseDeletionService
│   ├── KnowledgeBaseNameNormalizer
│   ├── KnowledgeBaseSearchPattern
│   ├── KnowledgeBaseSearchService
│   ├── KnowledgeBaseService
│   ├── KnowledgeDeletionOperatorService
│   ├── KnowledgeDocumentDeletionAcceptanceService
│   ├── KnowledgeDocumentDeletionExecutor
│   ├── KnowledgeDocumentFileValidator
│   ├── KnowledgeDocumentMediaTypeDetector
│   ├── KnowledgeDocumentMediaTypes
│   ├── KnowledgeDocumentNameNormalizer
│   ├── KnowledgeDocumentService
│   ├── KnowledgeDocumentWriteService
│   ├── KnowledgeErasureObservability
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
    │   ├── KnowledgeStorageReviewMetrics
    │   └── KnowledgeStorageUploadJournal
    ├── ErasureTombstoneJournal
│   ├── KnowledgeErasureTombstoneJournal
    ├── KnowledgeStorageConfiguration
    ├── KnowledgeStorageProductionInvariantVerifier
    ├── KnowledgeStorageProperties
    ├── KnowledgeStorageType
    ├── LocalObjectStorage
    ├── ObjectStorage
    ├── S3ObjectStorage
    └── StoredObject
```

**Контроль полноты структурных схем (31.10.2026).** В обоих присланных файлах в
`knowledge/service` перечислены **25** имён `.java`; указанные тобой 26 относятся
к более свежему локальному checkout, к которому эти два документа сами по себе
доступа не дают. Добавлять 26-е имя по догадке запрещено. В поставку включён
`sync_readme_inventory.py`, который считывает фактические `.java` непосредственно
из `backend/src/main/java/ru/safeai/gateway` **на твоём Mac** и синхронизирует
12 полных деревьев модулей. Он выводит отдельно число файлов
`knowledge/service`, проверяет V1–V84 и не редактирует миграции или исходники.
После синхронизации эта схема должна показывать **все** локальные классы, а не
частичный список из старого README. Аналогичная проверка проводится для
`common`, `auth`, `user`, `organization`, `ratelimit`, `audit`, `usage`, `chat`,
`model`, `ai` и `identity`. См. инструкцию в архиве.

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
## Безвозвратное удаление документа — V78–V81 (подготовлено, opt-in)

### Разные операции: отключение и удаление

| Операция | Поиск AI | Файлы и версии | Можно восстановить |
|---|---|---|---|
| `enabled=false`, `lifecycle_state=AVAILABLE` | выключен | сохранены | да, повторным включением |
| `lifecycle_state=DELETING` | запрещён | очистка ожидает/выполняется | нет через обычный API |
| `knowledge_document_deletions_v78.state=DELETED` | запрещён | онлайн-очистка должна быть доказана | нет |

`DELETED` — **состояние независимой операции**, не строка в `knowledge_documents`:
после завершения документ и его версии физически удаляются из online DB.
Статусы операции: `DELETING`, `NEEDS_REVIEW`, `BLOCKED_BY_RETENTION`, `DELETED`.
Состояние `BLOCKED_BY_RETENTION` означает, что Object Lock/legal hold/retention
препятствует обещанию очистки. Обозначение не подменяется успехом.

### Принятие запроса и закрытие доступа

`KnowledgeDocumentDeletionController` зарегистрирован только при обоих feature flags.
`KnowledgeDocumentDeletionAcceptanceService` выполняет принятие в короткой транзакции:

1. Проверяет `OWNER` своей базы/организацию на сервере, а не по кнопке UI.
2. Проверяет `Idempotency-Key` (12–128 символов) и `If-Match` — кавычки вокруг
   целой версии документа, например `"7"`.
3. Берёт документ под `FOR UPDATE`, исключая гонку с новой версией/редактированием.
4. В том же коммите сохраняет deletion intent, `enabled=false`, `DELETING`
   и обязательное событие аудита `KNOWLEDGE_DOCUMENT_DELETION_REQUESTED`.
5. Отвечает `202 Accepted`; **это принятие**, а не факт физического удаления.

Повтор с тем же ключом и той же семантикой возвращает исходную операцию;
повтор с другим документом/версией — конфликт. Права и организация повторно
проверяются при чтении операции. V78 дополнительно ограничивает запись новых
версий и публикацию после перехода в `DELETING`.

### Фоновая очистка: lease, fencing, S3 и PostgreSQL

`KnowledgeDocumentDeletionExecutor` создан как opt-in scheduled job:

```text
claim durable DELETING job (SKIP LOCKED) + lease_token
    ↓
transactional inventory of storage keys (V81)
    ↓ COMMIT
purgeAllVersionsVerified(key) для каждого ключа — OUTSIDE DB TRANSACTION
    ↓
transactional fenced erase of chunks/versions/jobs/document
    ↓
DB completion guard + DELETED acknowledgement
```

- `knowledge_document_erasure_objects_v81` содержит **временный** перечень
  storage keys документных версий и связанных upload-intents, но не текст
  или bytes документа. Прочие derived artifacts требуют отдельной инвентаризации.
- Inventory учитывает версии и связанные `LINKED`/`CLEANED` upload intents.
  Неоднозначные `INTENT/STORED/CLEANING/NEEDS_REVIEW` требуют разбора:
  нельзя объявлять отсутствие физического объекта без проверки.
- Lease продлевается до S3-операций; stale worker не имеет права подтверждать завершение.
  Ошибки приводят к bounded retry либо `NEEDS_REVIEW`.
- `ObjectStorage.purgeAllVersionsVerified` обязан проверить все S3 `versionId` и
  delete markers: обычный `delete(key)` этого **не гарантирует**.
- `S3ObjectStorage` использует MinIO Java SDK; `LocalObjectStorage` — отдельный
  provider-specific контракт. Версионирование, Object Lock и provider errors требуют
  интеграционных тестов по реальной конфигурации.
- PostgreSQL-транзакция очищает chunks/embeddings, документы, версии и ingestion jobs,
  а V81 запрещает зафиксировать `DELETED`, если остались online-content rows
  или временный inventory. Проверка БД не доказывает физическую очистку S3.
- Очистка учитывает V57/V63 generation state machine, не должна обходить
  защищённые поколения без отдельного fenced разрешения.

### Сохранение независимых аудиторских свидетельств

V80 создаёт `knowledge_document_version_identities_v80` и
`knowledge_chunk_identities_v80` и переносит к ним внешние ключи после backfill
и `VALIDATE CONSTRAINT`. В этих таблицах нет самого извлечённого текста,
векторов или storage keys. Исторические retrieval hits и generation records
сохраняют корректную ссылочную целостность. V81 допускает ограниченную
санитизацию `document_name_snapshot` до `Удалённый документ` под fenced lease.

**Граница обещания удаления:** исторические чаты, ранее отправленные ответы,
RAG context receipts, prompts, аудит, backup snapshots, внешние OCR/embedding/AI
провайдеры и реплики имеют самостоятельный retention/erasure контракт.
Без инвентаризации этих мест нельзя обещать тотальное удаление каждого следа
содержимого. Нельзя каскадно уничтожать immutable RAG receipts или историю аудита.

### UI: capability и подтверждение

`KnowledgeDocumentDeletionCapabilityController` зарегистрирован независимо от
feature flags. Его ответ защищён ACL/tenant/OWNER и не является разрешением на
обход backend checks. В интерфейсе владельцу показывается «Удалить…»;
при `allowed:false` команда неактивна с понятной причиной. При `allowed:true`
требуется точное название документа; после `202` показывается ход операции.
Повтор при timeout использует тот же `Idempotency-Key`; успешное сообщение
«Файл удалён» допустимо только после терминального `DELETED` от сервера.

### Удаление Knowledge Base — V82 (подготовлено, opt-in)

V78–V81 относятся к документам; V82 отдельно вводит durable
`knowledge_base_deletions_v82`, `knowledge_bases.lifecycle_state`, неизменяемую
base identity и транзакционные ограничения на новые загрузки/retrieval/ссылки.
`KnowledgeBaseDeletionService` принимает и координирует удаление дочерних
документов, а `KnowledgeBaseDeletionExecutor` удаляет live base row только
при подтверждённой очистке. `DELETE knowledge_bases` вручную запрещён
DB fencing. Frontend подтверждения базы подготовлен, но V82–V84 UI/CI release
приёмка текущей ветки отдельно не подтверждена.


# Безопасное удаление Knowledge — V78–V84

Этот раздел описывает фактическую последовательность предоставленных SQL V78–V84,
классы и эксплуатационный контракт. Миграции **добавляют ограничения и аудит**;
сами по себе они не удаляют документов, объектов S3 или базы. Runtime-операции разрушительны,
поэтому отделены feature flags и проверками прав, lease, fencing и состояния провайдера.

## Состояния и граница подтверждения

```text
Живой документ / Knowledge Base: AVAILABLE → DELETING

Долговечная операция удаления:
DELETING ──► DELETED                  # verified, terminal, immutable
         ├─► NEEDS_REVIEW            # операторский разбор
         └─► BLOCKED_BY_RETENTION    # доказанный Object Lock / legal hold

NEEDS_REVIEW ──► DELETING             # только V83 controlled resume при строгих условиях
BLOCKED_BY_RETENTION ──► [нет обычного resume]
DELETED ──► [нет переходов]
```

`DELETING` — защитное состояние, не факт окончания физической очистки. `NEEDS_REVIEW`
не подразумевает, что S3 уже очищен. `BLOCKED_BY_RETENTION` фиксирует подтверждённое
ограничение хранения; нельзя заменять его generic ошибкой, автоматически снимать hold
или переписывать terminal evidence. `DELETED` устанавливается только после проверок
executor и внутренних БД-ограничений; оно **не** обещает удаления из backup,
исторического аудита и внешних копий.

## Миграции V78–V84: точные обязанности

| Версия | SQL | Назначение |
|---|---|--|
| **V78** | `V78__knowledge_document_deletion_foundation.sql` | `knowledge_documents.lifecycle_state`, durable `knowledge_document_deletions_v78`, unique tenant/document + tenant/idempotency, lease/completion CHECK, immutable operation identity, запреты поздних version/ingestion/chunk writes |
| **V79** | `V79__knowledge_deletion_request_audit_type.sql` | Регистрация `KNOWLEDGE_DOCUMENT_DELETION_REQUESTED` в `audit_event_types` |
| **V80** | `V80__knowledge_erasure_provenance_identities.sql` | Immutable version/chunk identity, backfill истории, validated FK для retrieval hits/index generations без необходимости удерживать оригинальный текст/embeddings |
| **V81** | `V81__knowledge_erasure_executor_guards.sql` | Object inventory, transaction-local erasure lease/token validation, ограниченное право на chunk GC/hit redaction, запрет `DELETED`, пока живы online-rows/inventory; `BLOCKED_BY_RETENTION` immutable |
| **V82** | `V82__knowledge_base_erasure_foundation.sql` | Immutable base identity и backfill, validated FK исторических chat/retrieval/evaluation, `knowledge_base_deletions_v82`, lifecycle/child-write fences, verified owner fence перед физическим удалением базы, audit request type |
| **V83** | `V83__knowledge_erasure_operator_audit_types.sql` | Регистрация `KNOWLEDGE_DOCUMENT_DELETION_RESUMED` и `KNOWLEDGE_BASE_DELETION_RESUMED` для авторизованного операторского requeue |
| **V84** | `V84__knowledge_erasure_terminal_audit.sql` | Шесть terminal/attention audit types и два `AFTER UPDATE OF state` trigger, запись в `audit_outbox` в исходной транзакции |
| **V84** | `V85__knowledge_rag_execution_leases_and_erasure_guards.sql` |  |

V78: после принятия durable document operation состояние документа монотонно;
новые версии, ingestion и chunk publication после `DELETING` отвергаются. Серверная
проверка наличия operation при переходе в `DELETING` не заменяет tenant/RBAC checks.

V80: сначала копируются immutable identities из уже существующих версий/chunks,
затем новая составная FK добавляется как `NOT VALID`, **проверяется** через
`VALIDATE CONSTRAINT` и лишь после этого удаляется старый RESTRICT FK.
Это смена provenance reference, а не каскадное удаление исторических цитирований.

V81: разрешение на удаление chunks устанавливается по **действующему DB lease**
(`operation_id`, `lease_token`, `lease_until`), lifecycle и состоянию operation.
Простое `SET safeai.knowledge.erasure_*` не даёт достаточного права без живой
строки с совпадающим токеном. Нарушение inventory/completion guard должно
останавливать финализацию, а не игнорироваться в Java.

V82: базовый identity сохраняется после удаления live-row. Три исторические ссылки
(`chat_turns`, `knowledge_retrieval_runs`, `knowledge_evaluation_runs`) переходят
на `knowledge_base_identities_v82` после валидации. Дополнительные запреты
сериализуют новые документы/версии/upload intents/retrieval/evaluation/generation/hits,
изменение memberships и повторную публикацию относительно перехода базы в `DELETING`.
Физический `DELETE knowledge_bases` разрешён только актуальному base-erasure owner
после завершения дочерних операций и очистки активных зависимостей.

V83: только регистрация audited operator-resume types; реальное разрешение, requeue
и валидация находятся в `KnowledgeDeletionOperatorController` и
`KnowledgeDeletionOperatorService`. **Не** объявлять ручным recovery SQL-обновление
статуса или удаление `knowledge_document_erasure_objects_v81`.

V84: триггеры фиксируют только **реальное изменение** состояния из `DELETING`
в `DELETED`, `NEEDS_REVIEW` или `BLOCKED_BY_RETENTION`. Для документа и базы
записываются соответствующие шесть event types. Поля деталей: `operationId`,
`knowledgeBaseId`, `documentId` (для базы `null`), текущий/предыдущий state,
`attempts`, безопасный `last_error_code`. Отсутствуют имя файла, S3 keys,
текст документа и provider exception text. `actor_user_id=NULL` означает системный
worker; инициатор запроса остаётся в immutable V78/V82 operation. Уже завершённые
**до** установки V84 операции не получают синтетического audit-backfill.
V85:

## Backend-классы и ответственность

| Компонент | Роль в workflow |
|---|---|
| `KnowledgeDocumentDeletionAcceptanceService` | Tenant/OWNER authorization, optimistic version/idempotency, принятие документа и атомарный durable audit/request |
| `KnowledgeDocumentDeletionRepository` | Хранение/чтение документных операций удаления |
| `KnowledgeDocumentDeletionExecutor` | Lease claim/renewal, inventory, S3 version-aware verified purge, DB-fenced физическая очистка и финализация |
| `KnowledgeBaseDeletionService` | Административное принятие базы, создание parent operation, связь с дочерними документами и защитное отключение базы |
| `KnowledgeBaseDeletionExecutor` | Координация завершения дочерних операций, физическое удаление live base row только после DB fences |
| `KnowledgeDocumentDeletionCapabilityController` | Read-only capability для интерфейса; доступен и при выключенном DELETE |
| `KnowledgeDocumentDeletionController` | Destructive document DELETE; включается только с требуемыми флагами |
| `KnowledgeDocumentDeletionReadController` | Отдельные GET status/list, сохраняющие чтение при выключенных destructive flags и tenant/RBAC checks |
| `KnowledgeBaseDeletionController` | Base DELETE/capability/status, проверка авторизации и подтверждения запроса |
| `KnowledgeDeletionOperatorController` | ADMIN diagnostics + узкий POST resume, без физического удаления внутри HTTP handler |
| `KnowledgeDeletionOperatorService` | Проверка актуальных ADMIN/organization DB state, `expectedUpdatedAt`, unresolved uploads/children, mandatory audit для `NEEDS_REVIEW → DELETING` |
| `KnowledgeErasureObservability` | Read-only scheduled collector Micrometer, не захватывает leases и не выполняет DELETE |
| `S3ObjectStorage` / `ObjectStorage` | Provider-specific version-aware deletion и проверка результатов; обычный S3 `DELETE` не доказывает отсутствия предыдущих версий |

Точные Java package names и HTTP signatures сверяются с исходниками в текущей ветке,
особенно если локальные файлы после ZIP/patch были вручную изменены.

## Feature flags: default-deny

| Свойство | По умолчанию | За что отвечает |
|---|---|---|
| `safeai.knowledge.deletion.accept-enabled` | `false` | Приём document DELETE |
| `safeai.knowledge.deletion.executor-enabled` | `false` | Document physical executor |
| `safeai.knowledge.base-deletion.accept-enabled` | `false` | Приём base DELETE |
| `safeai.knowledge.base-deletion.executor-enabled` | `false` | Base physical executor |
| `safeai.knowledge.deletion.recovery-enabled` | `false` | V83 operator resume; одного этого флага недостаточно |

Для base DELETE V82 требует четыре document/base флага одновременно. V83
operator resume требует **все пять**. Чтение диагностики и статусов не должно
зависеть от включения физического удаления, но авторизация для него обязательна.
**Ни один из флагов нельзя включать ради проверки UI на существующей БД.**

## HTTP, idempotency и операторское продолжение

Подтверждённые по контроллерам V82/V83 маршруты (authorization проверяется на backend):

```http
DELETE /api/knowledge-bases/{baseId}/documents/{documentId}
GET    /api/knowledge-bases/{baseId}/document-deletions
GET    /api/knowledge-bases/{baseId}/document-deletions/{operationId}
GET    /api/knowledge-bases/{baseId}/document-deletions/capability

DELETE /api/knowledge-bases/{baseId}
GET    /api/knowledge-bases/{baseId}/base-deletions/capability
GET    /api/knowledge-bases/{baseId}/base-deletions/current
GET    /api/knowledge-bases/{baseId}/base-deletions/{operationId}
GET    /api/knowledge-bases/base-deletions
```

Фактические HTTP mapping annotations и сигнатуры ответа проверять в исходниках.
Особенно важно, что document read-only GET в V83 находится в отдельном
`KnowledgeDocumentDeletionReadController`, а не за флагом destructive DELETE.
`If-Match` и `Idempotency-Key` обязательны для запросов, принимающих удаление;
ответ `202 Accepted` означает принятие durable request, **не** завершение purge.

Подтверждённые по V83 пути защищённой диагностики:

```http
GET /api/knowledge-bases/{baseId}/document-deletions/{operationId}/diagnostics
GET /api/knowledge-bases/{baseId}/base-deletions/{operationId}/diagnostics
```

Продолжение только с текущей ролью ADMIN, tenant isolation и всеми пятью flags:

```http
POST /api/knowledge-bases/{baseId}/document-deletions/{operationId}/resume
POST /api/knowledge-bases/{baseId}/base-deletions/{operationId}/resume
```

```json
{
  "expectedUpdatedAt": "2026-10-29T18:00:00Z",
  "reasonCode": "OPERATOR_REVIEWED"
}
```

`expectedUpdatedAt` берётся из свежего GET diagnostics; `reasonCode` — короткий
нормализованный код (не свободное описание инцидента). Service проверяет
актуальную включённость пользователя/организации и **сохранённую** роль ADMIN,
точную временную версию, истёкший/отсутствующий lease и неразрешённые upload intents.
Для базы дополнительно запрещён resume при `NEEDS_REVIEW`/
`BLOCKED_BY_RETENTION` у дочерних документов. V83 requeue не выполняет S3 I/O:
следующий штатный executor заново проверяет inventory/ownership.

Отмена ранее принятого destructive request и восстановление `DELETING → AVAILABLE`
по умолчанию **не** поддерживаются. Конкурентный duplicate request не должен
приводить к двум несогласованным операциям или повторной физической очистке.

## Физическое удаление, retention и crash safety

- Versioned S3/MinIO bucket: удалять и проверять **каждую** версию и delete marker;
  не считать обычный `DELETE object` достаточным доказательством удаления.
- `COMPLIANCE` Object Lock / legal hold: при **подтверждённом** ограничении
  переходить в `BLOCKED_BY_RETENTION`, сохранять immutable evidence; не пытаться
  обойти lock или снять hold через административный retry.
- Неоднозначный provider failure / непроверенный `InvalidRequest` — не то же самое,
  что достоверный retention block; требуется безопасный retry/review согласно
  ограниченному retry policy и фактическому доказательству.
- После crash между S3 purge и PostgreSQL commit возобновление должно опираться
  на durable inventory, текущий lease/fencing и повторную верификацию; нельзя
  подставлять «успех» из факта, что прежний worker запустился.
- Исторические citation/Answer Passport/audit identifiers сохраняются отдельно
  от исходных bytes; metadata/provenance persistence надо учитывать в privacy policy.
- Если источники данных уже были отправлены внешнему AI provider, локальный purge
  не удаляет их копии у провайдера; действует отдельный provider retention contract.

## Monitoring V84 и поведение при сбоях

`KnowledgeErasureObservability` по расписанию (default `PT1M`) читает агрегаты
PostgreSQL для `scope=document|base` и четырёх states: `DELETING`,
`NEEDS_REVIEW`, `BLOCKED_BY_RETENTION`, `DELETED`. В каждом bucket публикуются
count, возраст самой старой операции **от `created_at`** и сумма `max(attempts-1,0)`.

Prometheus names после экспорта Micrometer:

```text
safeai_knowledge_erasure_operations{scope="document|base",state="..."}
safeai_knowledge_erasure_oldest_age_seconds{scope="document|base",state="..."}
safeai_knowledge_erasure_retries{scope="document|base",state="..."}
safeai_knowledge_erasure_metrics_last_success_epoch_seconds
safeai_knowledge_erasure_metrics_refresh_failures_total
```

24 gauges по scope/state + 1 freshness gauge + 1 failure counter. Метки
`operationId`, `documentId`, `tenant`, `storageKey`, exception text **не** используются.
Если SQL refresh завершается ошибкой, сохраняется последний корректный снимок,
увеличивается failure counter; `last_success=0` до первого успешного сбора,
после чего устаревание определяется по его возрасту. Повторяющиеся PostgreSQL-backed
counts от разных replicas следует агрегировать `max`, **не** `sum`.

Канонические файлы мониторинга:

```text
infra/prometheus/prometheus.yml
infra/prometheus/rules/safeai-audit-outbox.yml
infra/prometheus/rules/safeai-knowledge-review.yml
infra/prometheus/rules/safeai-knowledge-erasure.yml
infra/prometheus/tests/knowledge-erasure.test.yml
infra/scripts/check-prometheus-config.sh
```

`infra/prometheus/prometheus.yml` подключает `/etc/prometheus/rules/*.yml`.
`infra/docker-compose.yml` монтирует канонический каталог rules read-only.
**Не** копировать второй dead-letter alert из `ops/prometheus` или
`backend/deploy/prometheus`: dead-letter уже отслеживает canonical audit rule.
Prometheus firing без Alertmanager routing не означает уведомления дежурного.

31.10.2026 пользователь подтвердил успешную проверку `promtool`: валидна общая
конфигурация и **3 файла** правил (audit: 7, erasure: 6, storage review: 3);
проверка erasure unit rules также прошла. Это не проверка реально активного
Alertmanager и не доказательство подключённого production Prometheus.

## Операторский runbook и release safety

1. Считать защищённую диагностику и текущую роль; проверить state, `lastErrorCode`,
   `attempts`, age, upload intents, дочерние операции. Зафиксировать incident вне свободного audit payload.
2. `DELETING`: диагностировать работу PostgreSQL/S3/lease; не запускать второй worker в обход fencing.
3. `NEEDS_REVIEW`: устранить первопричину; продолжить только с одобренным `reasonCode`,
   актуальным `expectedUpdatedAt` и включёнными всеми пятью flags на отдельном разрешённом стенде.
4. `BLOCKED_BY_RETENTION`: оставить evidence неизменным; выполнить compliance/legal hold review,
   не править SQL и не возобновлять V83 endpoint.
5. После любого перехода проверить V78/V82 operation, audit outbox/delivery, метрики и пользовательский status;
   `202 Accepted` и запись `*_RESUMED` не означают `DELETED`.
6. При проблеме — управляемое выключение flags и redeploy с сохранением диагностики;
   не удалять immutable rows и не редактировать применённые миграции.

До любого production enablement нужны: пустая и populated PostgreSQL Flyway
rehearsal; V78–V84 negative/concurrency/lease/retention/real versioned-S3 tests;
Frontend CI и staging UX; восстановление из backup; проверка RBAC после revoke;
реальный Prometheus scrape + Alertmanager notification test; согласованная
policy для архивов, audit, Answer Passport и provider retention. До прохождения
этой матрицы feature flags остаются `false`.

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

Ручное переименование обслуживает отдельный `ChatTitleController` / `ChatTitleService`; точный HTTP verb и путь сверять
с текущим кодом контроллера. Доступ только к собственным неархивированным чатам своей организации; повторное одинаковое
пользовательское название — no-op. Заголовки при создании без пользовательского ввода не должны передаваться как явное
`"Новый чат"`.

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

### Knowledge: API безвозвратного удаления документа (opt-in)

```http
GET    /api/knowledge-bases/{knowledgeBaseId}/document-deletions/capability
DELETE /api/knowledge-bases/{knowledgeBaseId}/documents/{documentId}
GET    /api/knowledge-bases/{knowledgeBaseId}/document-deletions
GET    /api/knowledge-bases/{knowledgeBaseId}/document-deletions/{operationId}
```

- Read-only `capability` зарегистрирован и при выключенных двух флагах;
  требует backend tenant/OWNER, возвращает `allowed:false, reason:FEATURE_DISABLED`.
- Только destructive DELETE зависит от двух document flags. Начиная с V83
  GET status/list должны работать независимо от этих флагов и сохранять ACL.
  Для DELETE обязательны `If-Match: "<version>"` и `Idempotency-Key`.
- `202 Accepted` = операция принята, документ находится в `DELETING`;
  сведения об окончании берутся из status/list, а не из статуса HTTP 202.
- `GET` защищён той же серверной авторизацией, что и DELETE.
- V82 добавляет отдельный base deletion controller, durable parent operation и
  read-only историю; интерфейс и безопасность выключены по умолчанию до release gate.

ADMIN V83 diagnostics / opt-in continuation:

```http
GET  /api/knowledge-bases/{baseId}/document-deletions/{operationId}/diagnostics
GET  /api/knowledge-bases/{baseId}/base-deletions/{operationId}/diagnostics
POST /api/knowledge-bases/{baseId}/document-deletions/{operationId}/resume
POST /api/knowledge-bases/{baseId}/base-deletions/{operationId}/resume
```

GET сохраняет tenant/RBAC проверки при выключенных destructive flags; POST
допускается только для `NEEDS_REVIEW` после верификации текущей роли/причины.

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

# Database / Flyway V1–V84

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

Исторический privacy-gate V76 остаётся обязательной составной частью
последовательного V73–V84 upgrade. На реальной БД нельзя перескакивать
через обязательные версии. V76 нельзя применять без успешного прохождения
V75; нельзя переигрывать immutable V41/V44/V55/V63. Порядок V73 → V74 → V75
→ V76 — префикс общей последовательности V73–V84.

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
- **`V77`**
  - **Назначение:** восстановление исторических названий чатов `Новый чат` из первого пользовательского сообщения;
    `AUTO`-provenance для заполненных чатов, `DEFAULT` для пустых legacy-чата, увеличение optimistic `version` без
    изменения сообщений/tenant. Дополнительных AI-вызовов нет.
- **`V78`**
  - **Назначение:** создан фундамент удаления отдельных документов без автоматического физического purge.
    В `knowledge_documents` добавлено монотонное состояние `AVAILABLE / DELETING`, а в
    `knowledge_document_deletions_v78` — durable operation с tenant/document identity,
    `Idempotency-Key`, `expected_version`, числом попыток, lease-токеном, сроком аренды и
    проверяемым terminal state. `UNIQUE` предотвращает два конкурирующих запроса на один
    документ и повторное использование ключа идемпотентности в организации. Триггеры
    запрещают восстановление удаляемого документа, повторное добавление версий и позднюю
    запись ingestion/chunks после принятия удаления. Доказательство запроса нельзя
    обновлять в части identity или удалять обычным SQL.
- **`V79`**
  - **Назначение:** зарегистрирован `KNOWLEDGE_DOCUMENT_DELETION_REQUESTED` в
    `audit_event_types` — тип обязательного события принятия запроса на удаление;
    миграция не открывала HTTP DELETE, не вызывала S3 и не меняла статус операции.
- **`V80`**
  - **Назначение:** вынесено независимое immutable provenance версий и фрагментов в
    `knowledge_document_version_identities_v80` и `knowledge_chunk_identities_v80`.
    Существующие UUID и tenant lineage перенесены backfill-запросами; исторические
    `knowledge_retrieval_hits` и `knowledge_index_generations` переключены на новые
    составные FK только после `NOT VALID` и `VALIDATE CONSTRAINT`. Так сохранена
    ссылочная история цитирований без удержания исходных байтов, текстовых chunks,
    embeddings и storage keys. Обновление или удаление identity запрещено триггерами.
- **`V81`**
  - **Назначение:** усилены PostgreSQL fences фактического document-erasure executor.
    Добавлен временный durable inventory `knowledge_document_erasure_objects_v81`,
    предотвращён новый upload-intent для уже удаляемого документа. Разрешение на
    контентный DELETE требует совпадающих `operation_id`, `lease_token`, действующего
    `lease_until`, состояния `DELETING` и отключённого поиска. Узко разрешены
    fenced GC chunks и санитизация исторического `document_name_snapshot`; обычные
    guards V57/V44 остаются. Переход в `DELETED` запрещён при наличии online records
    или незачищенного inventory; `BLOCKED_BY_RETENTION` сохраняет immutable evidence.
    Сам SQL не проверяет отсутствие S3-версий и не заменяет проверку провайдера.
- **`V82`**
  - **Назначение:** добавлен отдельный жизненный цикл удаления всей Knowledge Base.
    Созданы `knowledge_base_identities_v82` и `knowledge_base_deletions_v82`, сохранены
    независимые immutable UUID/tenant lineage и перенесены исторические FK для
    chat/retrieval/evaluation с проверкой целостности. Base lifecycle, parent operation,
    child-document operations и DB fencing предотвращают новую загрузку, публикацию,
    retrieval/evaluation и необоснованное удаление live base row после принятия удаления.
    Завершение parent требует завершения зависимостей; retention/hold не обходятся.
- **`V83`**
  - **Назначение:** зарегистрированы `KNOWLEDGE_DOCUMENT_DELETION_RESUMED` и
    `KNOWLEDGE_BASE_DELETION_RESUMED` для обязательного аудита контролируемого
    operator recovery. Java-контроллер и сервис могут возвращать только
    `NEEDS_REVIEW → DELETING` при действующей DB-роли ADMIN, совпадающем
    `expectedUpdatedAt`, tenant scope, отсутствии неразрешённых upload intents,
    включённых защитных флагах и обязательном аудите. `DELETED` и
    `BLOCKED_BY_RETENTION` не восстанавливаются; GET status/diagnostics отделены от
    destructive DELETE. В SQL V83 нет самостоятельного физического удаления.
- **`V84`**
  - **Назначение:** добавлены шесть типов аудита фактических переходов
    `DELETING → DELETED / NEEDS_REVIEW / BLOCKED_BY_RETENTION` отдельно для документов
    и баз знаний, а также два `AFTER UPDATE OF state` PostgreSQL-триггера.
    В `audit_outbox` в **той же транзакции**, что и изменение состояния, записываются
    только безопасные идентификаторы операции/base/document, старое/новое состояние,
    attempts и код ошибки, без исходных файлов, storage keys, текста и provider errors.
    Откат записи outbox откатывает терминальный переход. Прямой
    `NEEDS_REVIEW → BLOCKED_BY_RETENTION` запрещён. Micrometer/Prometheus и проверки
    frontend/Java относятся к программному выпуску V84, а не к содержимому SQL-миграции.
- **`V85`**
  - **Назначение:**


## V78–V84 — эволюция жизненного цикла удаления

| Этап | Durable schema / DB enforcement | Java и внешний эффект |
|------|---|---|
| V78  | `knowledge_document_deletions_v78`, документ `AVAILABLE/DELETING`, immutable intent, запрет поздней публикации | Приём операции, без физического удаления при самой миграции |
| V79  | `audit_event_types` содержит `KNOWLEDGE_DOCUMENT_DELETION_REQUESTED` | Required audit при принятии |
| V80  | `knowledge_document_version_identities_v80`, `knowledge_chunk_identities_v80`, перенесённые validated FK | История цитирования не блокирует удаление исходных bytes |
| V81  | `knowledge_document_erasure_objects_v81`, fenced checks `is_knowledge_erasure_lease_owner_v81`, terminal guards | Executor вызывает verified S3 purge, затем защищённый DB cleanup |
| V82  | `knowledge_base_identities_v82`, `knowledge_base_deletions_v82`, parent lifecycle/fences | Base executor ждёт состояния всех детей; не стирает audit/provenance |
| V83  | Два новых `*_RESUMED` audit event types | Контролируемый operator resume только из `NEEDS_REVIEW`; независимая GET диагностика |
| V84  | Два AFTER UPDATE trigger и шесть terminal/attention event types | Outbox сохраняется атомарно, low-cardinality мониторинг собирается отдельно |
| V85  |  | |


V78–V81 образуют document-erasure фундамент, V82 добавляет удаление всей базы,
V83 — контролируемое операторское продолжение и отдельный read API, V84 —
обязательный атомарный аудит переходов. Полные таблицы, бизнес-инварианты,
feature flags и операторский runbook приведены в главе
[«Безопасное удаление Knowledge — V78–V84»](#безопасное-удаление-knowledge--v78v84).

**Порядок миграций строго последовательный:** V78 → V79 → V80 → V81 → V82 → V83 → V84.
V80/V82 выполняют identity backfill и validated FK переходы; V84 регистрирует
триггеры и audit types без физического удаления. На populated PostgreSQL нужны
backup, preflight существующих данных, проверка ограничений и остановка старых
workers, несовместимых с новым SQL contract. **Не исправлять checksum
применённых Flyway SQL**, включая V1–V83; schema fix — следующей миграцией.


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
V1–V72 → V73 Organization → V74 Usage → V75 Audit → V76 Retrieval privacy
       → V77 Chat titles → V78 Document lifecycle → V79 Request audit
       → V80 Provenance identities → V81 Document erasure guards
       → V82 Base deletion → V83 Operator recovery → V84 Terminal audit
```

Это последовательность **номеров Flyway**, а не инструкция вручную запускать
каждый SQL на действующей базе. Миграции выполняет Flyway на разрешённом
стенде после проверки checksum и предварительного резервного копирования.

Не копировать ранний конфликтный
`V73__audit_dead_letter_recovery_event_type.sql`:
этот номер уже занят миграцией организаций.

В исторически проверенном `main` (PR #8) было подтверждено наличие V1–V72.
В предоставленных архивных материалах от 28–31.10.2026 доступны V73–V84,
но это **не** подтверждает их применение к текущей PostgreSQL.
Истину об установке даёт `flyway_schema_history`.

## V77 — безопасный backfill исторических чатов

Перед применением `V77__backfill_legacy_default_chat_titles.sql` проверить, что V58 и все V73–V76 уже успешно применены
в правильном порядке, сделать snapshot populated БД и провести dry-run подсчёта строк. Данные первого сообщения
используются **только внутри того же чата**; миграция не меняет `organization_id`, `user_id`, `created_at`,
`chat_messages` и audit trail. Legacy-заголовок `Новый чат` по своей природе неоднозначен, поэтому возможное намеренное
название, совпадающее с заглушкой, также будет заменено: это явно принятая политика backfill, а не универсальное
восстановление provenance.

После применения проверить число оставшихся заглушек с/без сообщений, распределение `title_source`, `version` и
неизменность названий, отличных от заглушки. Новые транзакционные проверки должны охватывать идемпотентный replay,
ошибку Redis/quota, ручное rename одновременно с первым сообщением и frontend refresh списка. Если V77 уже есть в
`flyway_schema_history`, изменять её нельзя; correction требует следующей свободной версии.

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

## Knowledge erasure (V84): техническая наблюдаемость и правила

`KnowledgeErasureObservability` регистрирует на старте 26 meters:
`2 scopes × 4 states × 3 gauges = 24`, плюс freshness gauge и refresh failure counter.
Период обновления `${safeai.knowledge.erasure.observability.poll-delay:PT1M}`.
Параметры запроса только агрегированные, без tenant/user/operation labels.

```promql
safeai_knowledge_erasure_operations{scope="document",state="NEEDS_REVIEW"}
safeai_knowledge_erasure_oldest_age_seconds{scope="base",state="DELETING"}
safeai_knowledge_erasure_retries{scope="document",state="DELETING"}
safeai_knowledge_erasure_metrics_last_success_epoch_seconds
safeai_knowledge_erasure_metrics_refresh_failures_total
```

Статусы без операций представлены нулями после успешного refresh. При ошибке
SELECT данные **не обнуляются**, counter сбоя увеличивается, timestamp
успешного refresh не сдвигается. До первого успешного refresh `last_success=0`.
У всех этих рядов проверять `absent(...)` и freshness отдельно, а реплики
агрегировать `max` для PostgreSQL-backed gauges, не `sum`.

`infra/prometheus/rules/safeai-knowledge-erasure.yml` содержит 6 правил:
`NeedsReview`, `RetentionBlocked`, `Stuck`, `MetricsStale`, `MetricsAbsent`,
и `SafeAIKnowledgeErasureMetricsRefreshFailed` (полные identifiers приведены в YAML).
Дублирующее правило audit dead letters не добавляется — оно уже находится в
`safeai-audit-outbox.yml`. Инструментальный endpoint — **внутренний**
`/actuator/prometheus` на management port 9091, не public Nginx.

## Metrics

Проект содержит module-level metrics, включая:

- chat processing;
- rate limiting;
- audit outbox;
- Knowledge storage reconciliation;
- V84 Knowledge erasure (`KnowledgeErasureObservability`).

Для V84 точные названия метрик, aggregation, freshness и правила приведены
в отдельной главе «Безопасное удаление Knowledge — V78–V84».

Prometheus endpoint должен быть internal/network-protected.

## Scheduled jobs

В историческом baseline существуют scheduled/maintenance paths для:

- refresh-token cleanup;
- audit outbox processing;
- audit retention;
- usage rollups/backfill и V71 dirty-date recalculation;
- ChatTurn recovery;
- Knowledge ingestion;
- Knowledge storage reconciliation;
- Knowledge generation GC;
- opt-in `KnowledgeDocumentDeletionExecutor` и `KnowledgeBaseDeletionExecutor`
  (исполняются только при соответствующих feature flags; по умолчанию выключены);
- `KnowledgeErasureObservability` (V84, read-only, не зависит от destructive flags).

Multi-instance job должен иметь DB/distributed coordination там, где duplicate work нарушит
semantics.

---

# Тестирование

README не утверждает `BUILD SUCCESS` без фактического запуска конкретного commit.

## Регрессии V73–V77 (историческая матрица, не замена V84 CI)

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
- Chat title V58/V77: first accepted message, manually named chats, default placeholder submitted as `null`, concurrent
  rename/reservation, idempotent replay, rollback, legacy backfill on populated PostgreSQL and UI list refresh.
- Lease: PostgreSQL-clock renewal, fencing token mismatch, expired lease, DB outage and watchdog ownership-loss metrics.
- Quota: route-bound reservation, verified/unverified pricing scheme, hard monthly cost limit, unknown-cost fail-closed
  and rollback consistency.
- Upgrade: populated Flyway V72 → V77, application startup, production profile,
  Testcontainers PostgreSQL/Redis и отсутствие регрессии публичных API.

Не заменять падающий тест ослаблением CHECK, удалением assert или
изменением истории Flyway. Сначала установить фактическую причину и
соответствие нового контракта ожидаемому поведению теста.

## Целевой release gate удаления Knowledge (V78–V84)

**Слои проверки должны оставаться раздельными:** unit/API-contract tests,
PostgreSQL/Testcontainers constraints/concurrency, настоящие versioned MinIO
purge/Object Lock/legal hold, отдельный JVM SIGKILL/restart, frontend UI,
Prometheus rule tests и deployment preflight. Положительный результат одного
слоя **не** подменяет другой. Тесты удаления работают только с одноразовой БД;
не запускать destructive проверку с текущими рабочими 7 KB / 18 документами.


Наличие Java-теста не означает, что он запускался на текущем checkout. Исторический
V82 `./mvnw clean verify` не подтверждает новое дерево; V83/V84 требуют
нового прогона уже после объединения всех изменений, а не переиспользования
старого результата.

Ключевые test categories/classes из ранее предоставленных архивов и ZIP:

- `KnowledgeDocumentDeletionFoundationIntegrationTest`,
  `KnowledgeDocumentDeletionAcceptanceIntegrationTest`,
  `KnowledgeDocumentDeletionExecutorIntegrationTest` — request, DB fences, cleanup;
- `KnowledgeBaseDeletionFoundationIntegrationTest`,
  `KnowledgeBaseDeletionRecoveryConcurrencyIntegrationTest`,
  `KnowledgeBaseDeletionRealJvmCrashIntegrationTest` — parent/child lifecycle,
  конкурентность, recovery после реального SIGKILL;
- `S3VerifiedKnowledgeObjectPurgeIntegrationTest`,
  `KnowledgeBaseObjectLockIntegrationTest` — versioned MinIO,
  COMPLIANCE Object Lock, legal hold, непроверенный InvalidRequest;
- `KnowledgeDeletionOperatorSafetyIntegrationTest` — V83 role/revoke/flags,
  current-state diagnostics и ограниченный operator resume;
- `KnowledgeErasureTerminalAuditIntegrationTest` — V84 атомарный outbox,
  exactly-one transition intent и rollback;
- `KnowledgeErasureObservabilityTest` — V84 fixed-cardinality metrics,
  last-success freshness, failure-preserved last known values;
- `KnowledgeDocumentDeletionCapabilityControllerTest` и frontend
  `useKnowledgeDeletionController.test.tsx`, `KnowledgeDetailsPage.test.tsx` —
  capability/history/UX даже при выключенных destructive flags.

Фактический набор файлов сверить через `find` — часть тестов поставлялась
в отдельных предыдущих ZIP и может отличаться по именам/ревизиям.

```bash
cd ~/Workspace/Projects/Products/SafeAI-Desk

git status -sb
git diff --check
for f in infra/scripts/*.sh; do bash -n "$f" || exit 1; done
bash infra/scripts/check-config-topology.sh
bash infra/scripts/check-prometheus-config.sh

cd backend
./mvnw -Dtest=KnowledgeErasureTerminalAuditIntegrationTest,KnowledgeErasureObservabilityTest test
./mvnw -Dtest=KnowledgeDeletionOperatorSafetyIntegrationTest test
./mvnw clean verify

cd ../frontend
npm ci
NODE_OPTIONS=--no-experimental-webstorage npm run ci
```

Для Testcontainers/S3 tests — только одноразовые PostgreSQL и versioned MinIO.
Тестирование **не** должно затрагивать пользовательские 7 Knowledge Bases / 18
документов либо production database, включая Flyway, S3 purge и UI deletion.
Проверить loss-of-response, retention/legal hold, unknown provider outcomes,
stale worker, failing JDBC commit, prior provider purge, archive references,
retry idempotency, tenant isolation, permission revoke и отсутствие утечки
operation IDs в метрики. Не снижать assertions/guards ради зелёной сборки.


## Исторические проверки до V78 (11.10.2026)

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
fresh V1 → V84 (только на disposable PostgreSQL/pgvector)
populated upgrade V72 → V84; порядок/lock-timeouts; Flyway checksum
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

## V78–V84: controlled destructive erasure rollout — NO-GO без отдельной приёмки

Разработаны документы и база: `V78–V81` (document), `V82` (base),
`V83` (операторское `NEEDS_REVIEW` requeue), `V84` (terminal audit и метрики).
Наличие SQL, сервисов, готового frontend confirmation и настроенного Prometheus
**не** означает разрешение включить удаление на реальных данных.

Пять flags `safeai.knowledge.deletion.accept-enabled`,
`safeai.knowledge.deletion.executor-enabled`,
`safeai.knowledge.base-deletion.accept-enabled`,
`safeai.knowledge.base-deletion.executor-enabled`,
`safeai.knowledge.deletion.recovery-enabled` по умолчанию должны оставаться `false`.
Для операторского resume **все пять** должны быть явно включены только на
утверждённом окружении. `BLOCKED_BY_RETENTION` нельзя переводить в `DELETING`
обычной командой. Read-only diagnosis и аудит продолжают работать без включения
физического удаления.

После backup/restore rehearsal и populated V77→V84 Flyway upgrade на отдельной
БД проверить реальные Object Lock/retention, tenant/RBAC/revoke,
known vs ambiguous S3 failures, отказ SQL после S3 purge, consumer concurrency,
frontend UI, audit outbox и Prometheus/Alertmanager end-to-end.


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

Здесь собраны исторические риски и новые release gaps по состоянию
на 31.10.2026. Некоторые тезисы требуют повторного code audit перед изменением production behavior;
их наличие в README не подтверждает незакрытый дефект текущего `main`. Пункты, закрытые `V59–V68`,
из списка удалены: budget-state representability закрыт `V62/V64`, final-request/RAG binding —
`V62/V64/V65`,
searchable Knowledge publication — `V63`, physical response vs protocol rejection — `V66`,
а Chat/Audit evidence получил дополнительные DB backstops в `V67/V68`.

## P0 до production — release validation V72–V84, deletion safety и мониторинг

- Исторический PR #8: 2259 tests и 27/27 PostgreSQL/Redis; отдельный
  V82 `clean verify` также сообщался как успешный. Его точное количество тестов
  следует брать из вывода того прогона, не переносить на V83/V84.
- После объединения V83/V84 нужен новый `./mvnw clean verify`, frontend CI,
  upgrade rehearsal populated V77→V84 и S3/MinIO/Object Lock + live-SIGKILL matrix.
- `BLOCKED_BY_RETENTION` остаётся immutable. Для отмены legal hold/истечения retention
  нужен отдельный согласованный процесс и новая migration, если потребуется
  разрешённый переход; нельзя делать plain `UPDATE state`.
- V83 operator resume допускается только для проверенного `NEEDS_REVIEW`,
  при актуальном ADMIN в БД, `expectedUpdatedAt`, устранении upload/child blockers
  и пяти flags. Required audit обязателен.
- V84 outbox trigger даёт атомарный event intent, но доставка в `audit_events`
  асинхронна; dead letters, stale/failure metrics и реальный Alertmanager
  должны иметь эксплуатационный runbook и tested routing.
- 31.10.2026 локальные `promtool`/topology/shell проверки прошли; недоказанными
  остаются production Compose execution, image digest verification на релизном
  commit, реальный scrape/alert delivery и DB migration.
- Для Audit/Usage/Organization остаются исторические и release gates по V72–V76:
  миграция populated tenants, contention, dirty dates, heavy reports и security epochs.
- API `202 Accepted` означает создание задачи, **не** окончательное удаление;
  `DELETED` не включает backup, RAG/audit provenance и внешних AI providers.

**Запрещено** отключать safety triggers, менять applied SQL checksum,
ослаблять RBAC/tenant scope и подменять unknown provider result успехом.


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

На 31.10.2026 сценарии Knowledge deletion вынесены в feature controllers и
отдельные API clients. В V84 исправлено поведение чтения истории: выключенный
capability не должен очищать ранее загруженные операции; network/parse failure
не маскируется под пустую историю. Acceptance `202` показывает состояние запроса,
не завершение erasure. Все отрицательные состояния и stale errors должны
оставаться видимыми после перезагрузки страницы.

Актуальный предоставленный frontend использует React 19 / TypeScript 6 и
feature-oriented layout (`app`, `entities`, `features`, `pages`, `shared`, `test`),
не старые корневые `src/api`, `src/auth`, `src/components`.

```text
frontend/src/
├── app/
├── entities/knowledge/api/knowledgeDocumentDeletionApi.ts
├── features/knowledge/
│   ├── controller/useKnowledgeDeletionController.ts
│   └── ui/details/KnowledgeDeleteDialog.tsx
├── pages/KnowledgeDetailsPage.tsx
├── shared/
├── test/
└── main.tsx
```

Команда «Удалить…» появилась в меню документов только для серверно разрешённого
владельца; при выключенной функции видна **неактивной** с текстом причины.
React-модалка требует точного совпадения имени, отправляет `If-Match` и
стабильный `Idempotency-Key`, не объявляет успех по одному HTTP 202, отображает
`DELETING` / `NEEDS_REVIEW` / `BLOCKED_BY_RETENTION` / `DELETED`.
Модальный UI и проверки ролей не заменяют серверную tenant/OWNER авторизацию.
В предоставленном V82 frontend-снимке есть `knowledgeBaseDeletionApi.ts`,
`KnowledgeBaseDeleteAction.tsx`, `BaseDeletionHistory.tsx` и тесты подтверждения;
V84 затрагивает `useKnowledgeDeletionController.ts` и историю/ошибки статуса.
Фактическая работа текущей локальной UI-ветки и `npm run ci` подлежит отдельной проверке.

**Ключевые изменения по V84 ZIP:**
`useKnowledgeDeletionController.ts` и его `.test.tsx`,
`KnowledgeDetailsWorkspace.tsx`, `KnowledgeDetailsContent.tsx` и
`KnowledgeDetailsPage.test.tsx`. Они относятся к реакции UI на ошибки/историю
и не расширяют доступ к destructive DELETE. Источником фактических связей и
контрактов остаётся `frontend/src` текущей рабочей копии.

### Локальные проверки frontend

```bash
cd frontend
npm ci
npm run lint
npm run test
npm run build
```

Для объединенного прогона `npm run check`; CI-ориентированный script — `npm run ci`
(проверка архитектуры, lint, coverage, production build). Для текущего проекта
используется `NODE_OPTIONS=--no-experimental-webstorage npm run ci`. `npm run audit:prod` проверяет production dependencies
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

## Исторический baseline PR #8 и доступные исходники 28.10.2026

```text
Repository:  https://github.com/Amid-1/Safeai-desk
Base:        main / origin/main
Main HEAD:   fdcff62fc32131474cbc2639b1fa636edffd45bc (merge PR #8)
Java:        21
Spring Boot: 4.1.1
DB:          PostgreSQL; historical main V1–V72; supplied sources V1–V84
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

**Ближайший функциональный приоритет — завершить общую V78–V84
production-like приёмку удаления документов и Knowledge Base, V83 operator recovery,
V84 аудита/наблюдаемости и frontend.** Основа для удаления базы уже создана в V82;
не проектировать её повторно. До отдельного GO серверные destructive flags
остаются выключенными.


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

**Актуализация документа:** 31 октября 2026 года. Сохранён исторический baseline PR #8,
обновлены описания V73–V84, общие и доменные структурные деревья,
frontend feature architecture, транзакционный аудит V84, metрики,
Prometheus, operator recovery и opt-in удаление документов и целых баз знаний. Фактический Git HEAD и текущая пользовательская PostgreSQL/Flyway
не были прочитаны; полный release gate и production deployment не заявляются.
---

# Релизный контроль V73–V84

Ниже **обязательные проверки перед release**, не заявление об их прохождении.
Работать с фактической веткой и незакоммиченными изменениями. Не пытаться
переустановить V84 поверх populated production database ради проверки.

```bash
cd ~/Workspace/Projects/Products/SafeAI-Desk

git status -sb
git diff --check
git diff --stat
git diff --cached --stat

# На macOS/BSD find, без GNU -regextype:
find backend/src/main/resources/db/migration -maxdepth 1 \
  -name 'V*.sql' -print | LC_ALL=C sort

for f in infra/scripts/*.sh; do bash -n "$f" || exit 1; done
bash infra/scripts/check-config-topology.sh
bash infra/scripts/check-prometheus-config.sh

# Только с корректно подготовленным окружением и без запуска деплоя:
# docker compose --env-file backend/.env.prod -f infra/docker-compose.yml config --quiet

cd backend
./mvnw clean verify
cd ../frontend
npm ci
NODE_OPTIONS=--no-experimental-webstorage npm run ci
```

На **отдельной disposable или восстановленной staging БД** проверить миграционную историю:

```sql
SELECT installed_rank, version, description, script, success
FROM public.flyway_schema_history
WHERE version IN ('73','74','75','76','77','78','79','80','81','82','83','84')
ORDER BY installed_rank;

SELECT to_regclass('public.knowledge_document_deletions_v78') AS document_operations,
       to_regclass('public.knowledge_document_version_identities_v80') AS version_identity,
       to_regclass('public.knowledge_chunk_identities_v80') AS chunk_identity,
       to_regclass('public.knowledge_document_erasure_objects_v81') AS object_inventory,
       to_regclass('public.knowledge_base_identities_v82') AS base_identity,
       to_regclass('public.knowledge_base_deletions_v82') AS base_operations;

SELECT conname, convalidated
FROM pg_constraint
WHERE conname IN (
  'fk_knowledge_retrieval_hits_provenance_v80',
  'fk_knowledge_generation_provenance_v80',
  'fk_chat_turns_knowledge_base_identity_v82',
  'fk_knowledge_retrieval_runs_base_identity_v82',
  'fk_knowledge_evaluation_runs_base_identity_v82'
);
```

Для V84 дополнительно проверить наличие `trg_document_erasure_terminal_audit_v84`
и `trg_base_erasure_terminal_audit_v84`, а также шести типов audit events;
для V83 — обоих `*_RESUMED` и fail-closed operator permissions.
Не проверять destructive SQL на живой базе.

**Release gate остаётся открытым**, пока нет свежего green backend/frontend CI,
законченного populated upgrade rehearsal, восстановления из backup,
проверенных S3 Object Lock/legal hold, независимого permission-revoke теста,
работающего Prometheus scrape/Alertmanager delivery и формального решения
о включении всех конкретных destructive flags. В противном случае оставлять
flags off и выпускать только read-only observability, если она отдельно принята.

**Неизменяемое правило:** не редактировать миграции, уже существующие в
`flyway_schema_history`. Последующие исправления оформлять следующей свободной
версией после проверки текущей ветки и применённой схемы.
