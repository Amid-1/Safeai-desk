# SafeAI Desk — Retention & Erasure Operational Readiness

**Дата:** 24.11.2026. **Статус:** архитектурный контракт и безопасный UI-этап, **НЕ production GO**.
**Текущая граница схемы:** Flyway V102. Предлагаемая адресная схема V103+ НЕ применена.
**Область:** чаты, документы, базы знаний, учётные записи, организации; политики приёма, заявки, согласование, выполнение, доказательства и операторский контроль.

## 1. Принцип: кто, почему, что и на основании чего удаляет

**Никогда не смешивать:** (1) сообщение/обращение, (2) право инициировать, (3) согласование,
(4) принятую необратимую операцию, (5) подтверждённое физическое стирание.

- **Инициатор** (`requesterUserId`) — аутентифицированный пользователь; ID получается только из серверного principal.
- **Заявитель** (`subjectUserId`) — человек, чьи данные затронуты; может не совпадать с инициатором.
- **Объект** (`targetType`, `targetId`, `targetOrgId`, `targetOwnerId`) — строго один ресурс с неизменяемой областью организации.
- **Основание** (`basisCode`, `evidenceRef`) — собственный запрос, проверенное обращение, действие администратора в пределах полномочий, обязанность по закону и т.п.
- **Согласующий** (`reviewerUserId`) — отдельное лицо при требуемом подтверждении; его полномочия проверяются при каждом действии.
- **Исполнитель** (`workerIdentity`, `operationId`) — системная роль с минимальными SQL/S3 правами, не веб-пользователь.
- **Результат** (`receipt`, `completedAt`) — структурированное доказательство выполнения, отдельно от HTTP 202 и от сообщения пользователя.

Сообщение, письмо, тикет, жалоба или чат **сами по себе не являются полномочиями**. Они могут стать `evidenceRef`
после подтверждения источника. Импорт обращения не должен автоматически вызывать destructive API.

## 2. Текущее, подтверждённое по исходникам SafeAI Desk

| Объект | Нынешний способ | Нынешний субъект доступа | Условия | Реальное состояние до deploy |
|---|---|---|---|---|
| Архив чата | POST `/{chatId}/archive` | Владелец своего чата | Действующая сессия и tenant | Отдельно от удаления |
| Стирание содержимого чата | DELETE `/{chatId}/erasure` | ADMIN **собственного архивного чата** | `If-Match`, idempotency, точное подтверждение, удержания, отсутствие активной AI операции, worker flags | Только при отдельно включённой и проверенной конфигурации |
| Удаление документа Knowledge | DELETE `/api/knowledge-bases/{baseId}/documents/{documentId}` | OWNER базы или ADMIN того же tenant | Доступ, версия, idempotency, guarded worker, журнал | Контроллер скрыт, пока flags/journal OFF |
| Удаление базы знаний | существующий `base-deletions` API | ADMIN собственной организации | Safe base deletion, все дочерние документы, worker, retention | Не утверждать готовность без staging-приёмки |
| Окончательное удаление пользователя | POST `/api/users/{id}/permanent-deletion` | SUPER_ADMIN | Отключение, retention (по умолчанию 7 суток), токены, зависимости, подтверждённый email | Не является удалением связанных документов |
| Удаление организации | **нет destructive API** | Никто | Допускается только отключение с сохранением данных | Не реализовано |

Backend `RetentionManagementService` сегодня контролирует **две глобальные** группы: `chatAllowed` и `knowledgeAllowed`.
`Gate(configured, workerInitialized)` говорит лишь об обнаруженных настройках/bean, **не** о здоровье сервера,
доступности S3, отсутствии Object Lock, завершении удаления или о наличии согласия.

## 3. Как должны выглядеть индивидуальные запросы

| Сценарий | Нужный процесс | Кто может согласовать | Исполнение |
|---|---|---|---|
| Пользователь хочет убрать **свой** документ в базе | Аутентифицированная заявка; если нет права OWNER, отправляется владельцу базы/ADMIN | OWNER/ADMIN своей организации, после проверки владельца и классификации данных | Через существующий protected document erasure executor |
| Владелец базы хочет удалить документ | Прямой подтверждённый запрос при наличии разрешения, по утверждённому профилю риска | Может не требовать второй подписи для своего разрешённого документа | Отдельный operationId и подтверждение purge всех версий |
| ADMIN просит удалить документ другого пользователя | Заявка **с раскрытым инициатором и основанием**; прямое удаление без основания запрещено новой политикой | Уполномоченный reviewer tenant, при спорном основании — compliance/privacy reviewer | Только после подтверждения прав/удержаний |
| ADMIN просит удалить базу знаний целиком | Заявка с перечнем документов, риском, зависимостями и оценкой последствий | Второй администратор или privacy reviewer, не инициатор | Транзакционный intent + существующий base executor; dual control |
| Пользователь просит удалить **свой чат** | Отдельная self-service заявка; сейчас само стирание пользователю не выдано | Уполномоченный ADMIN по правилам организации; **не** подмена личности пользователя | Только существующий erasure worker после новой связки права/approval; не открывать прямой destructive endpoint пользователю |
| ADMIN хочет удалить чат другого пользователя | Высокорисковый review, дополнительное подтверждение полномочий и причины | Независимый reviewer + право законного доступа; по умолчанию DENY | До отдельного утверждённого сценария нельзя обходить owner fence |
| USER просит удалить свою учётную запись | Формальная privacy-заявка; не является прямым удалением аккаунта | SUPER_ADMIN/privacy reviewer после идентификации заявителя, retention и проверки связанных данных | Отдельный существующий user deletion endpoint; никаких каскадных обходов |
| ADMIN просит удалить пользователя организации | Запрос на отключение/удаление с причиной; разграничение request и execute | SUPER_ADMIN с подтверждёнными полномочиями; инициатор и reviewer по возможности разные | Retention, active refresh, FK dependencies остаются обязательны |
| ADMIN хочет удалить организацию | Запрос на offboarding/disable и экспорт/инвентаризацию | Два независимых уполномоченных администратора + владельцы данных/privacy | **Только disable сейчас.** Не пытаться удалить tenant SQL cascade |

**Принцип «админ видит всё» запрещён.** `SUPER_ADMIN` вправе видеть глобальные статусы и заявки по одобренной политике,
но это не основание просматривать содержимое чужой переписки или стирать его по UUID. Для cross-tenant действий нужен
отдельный контролируемый break-glass сценарий, срок действия, reason code и два независимых утверждения.

## 4. Две независимые оси политики

1. **Глобальная политика допуска** (V102) — может запрещать новые destructive intents по Chat/Knowledge для всей платформы.
2. **Адресная политика инициирования и согласования** (V103+, **не реализована**) — утверждённые роли и объекты:
   `targetOrganizationId`, `targetOwnerId`, `targetType`, `requesterId`, `requesterRole`, `requestedAction`,
   `approvalPolicyVersion`, `expiresAt`, `basisCode`.

Ни одна адресная политика не может разрешить то, что запретили global gate, retention hold, S3 Object Lock,
тенантная изоляция, подтверждение владения, отдельный worker gate или действующее право.

Нельзя реализовать это как бессрочный `canDelete=true` на карточке пользователя. Права должны быть
привязаны к **конкретному действию, конкретному объекту, актуальной версии и сроку**; отзыв и смена роли инвалидируют approval.

## 5. Цикл заявки, который нужен в V103+

```text
DRAFT -> SUBMITTED -> IDENTITY_VERIFIED -> POLICY_CHECKED -> PENDING_APPROVAL
  -> APPROVED -> ADMITTED -> EXECUTING -> VERIFYING -> COMPLETED
                |            |              |               |
                |            +-> BLOCKED_BY_RETENTION / BLOCKED_BY_POLICY
                +-> REJECTED             +-> NEEDS_REVIEW
CANCELLED доступен только ДО ADMITTED; EXPIRED до ADMITTED.
```

- `SUBMITTED` — лишь получение сообщения или формы. **Не** принятая destructive операция.
- `APPROVED` — пройдено согласование, но ещё не создан защищённый удаляющий intent.
- `ADMITTED` — создаётся атомарная запись в существующей таблице удаления, связывается `operationId`;
  после этого отмена заявки **не** считается отменой физического исполнения.
- `COMPLETED` — только по trusted result исполнителя + долговечному audit receipt. Не считать успехом HTTP 202.
- Неуспешное S3 удаление, Object Lock, неизвестный результат, crash, lease loss -> `NEEDS_REVIEW`, а не `COMPLETED`.
- Для массовых заявок нужен immutable snapshot целей, лимит batch и независимое approval на batch fingerprint.

### 5.1 Идемпотентность и гонки

`tenant + requester + operationType + targetId + expectedVersion + requestDigest + idempotencyKey` связаны однозначно.
Повторная отправка возвращает тот же requestId/operationId; та же key для иной цели -> 409.
Смена owner/role/tenant/ACL/объекта после утверждения -> отказ и новая проверка. В каждой транзакции использовать
последовательные locks, shared version/ETag, monotonic state transitions, уникальные индексы.

### 5.2 Аудит

Обязательно писать transactionally-required audit/outbox при: REQUESTED, SOURCE_VERIFIED, APPROVED,
REJECTED, ADMITTED, WORKER_STARTED, BLOCKED, RETRIED, COMPLETED, FAILED, EXPIRED.
Фиксировать `requestId`, `operationId`, `requesterUserId`, `subjectUserId`, `reviewerUserId`, `targetOrgId`,
`targetType`, `targetId`, policyVersion, reasonCode, timestamp, correlationId, role snapshot, result evidence.
**Не писать** содержимое документов, сообщения, raw PII или секреты в аудит. Целевые имена/адреса отображать
в интерфейсе по текущим правам, а после удаления сохранять безопасные идентификаторы.

## 6. Концепция REST API V103+ (контракт, НЕ внедрён)

```text
POST /api/erasure-requests                   create authenticated request, 201
GET  /api/erasure-requests?scope=mine         own requests only
GET  /api/admin/erasure-requests?...          server-scoped tenant/admin reviewer queue
GET  /api/erasure-requests/{requestId}        requester/reviewer scoped
POST /api/erasure-requests/{id}/verify-source privacy evidence verification
POST /api/erasure-requests/{id}/decision      approve/reject with If-Match
POST /api/erasure-requests/{id}/admit         internal service only, not generic user HTTP
POST /api/erasure-requests/{id}/cancel        only while pre-admission
GET  /api/admin/erasure-operations?...        redacted operational registry
```

**Нельзя принимать** `requesterUserId`, `reviewerUserId`, `organizationId` как доверенные body fields.
Даже если API принимает `targetOrgId`, обязателен server-side tenant scope.
PATCH generic state / `DELETE all` / arbitrary SQL / emergency `force=true` **не создаются**.
Для privacy-обращений хранить ссылку на защищённое доказательство (ticketId+digest), не произвольный текст письма.

Ответы 403/404 без раскрытия чужих объектов; 409 для conflicts, 412 для stale ETag, 423 для retention hold;
202 только для **принятой** durable операции. Отказ S3/SQL не прятать под успешный `200/202 COMPLETED`.

## 7. V103+ (изолированное проектирование, НЕ запускать миграцию автоматически)

Требуются как минимум:
- `erasure_requests`: UUID, target_type, tenant_id, target_id, owner_id (nullable), requested_by,
  subject_id (nullable), source_kind, evidence_digest, basis_code, status, expected_version,
  approval_policy_version, created_at, expires_at, updated_at, admitted_operation_id, request_digest;
- `erasure_request_decisions`: immutable UUID, request_id, reviewer_id, decision, reason_code,
  reviewer_tenant_id, current_role_snapshot, created_at, request_version;
- `erasure_request_state_events`: append-only audit projection, retention-controlled, no raw PII;
- уникальные индексы idempotency per requester/tenant, неразрывная FK-цепочка целей без каскадного content delete;
- штатная migration config, reviewed-migrations SHA-256, `RuntimeAclPolicy.REVIEWED_THROUGH`,
  runtime-v90.sql с минимальными GRANT на чтение и insert/update только через узкие права/проверки.

Проверять clean V1→V103 и populated V85→V103, current Flyway history, rollback на failure, совместимость
с V102, тест на отказ при пропущенной V103. Не передавать access к worker DB role веб-приложению.

## 8. Реальная подготовка исполнителей (вне UI и без автопереключателей)

### Чаты
- выделенная роль `safeai_chat_erasure_worker`, отдельный ограниченный pool, exact JDBC URL match;
- flags `safeai.chat.erasure.{accept-enabled,executor-enabled,retention-approved}` только после утверждения;
- archive-first, owner/admin fence, отсутствие AI turn in flight, retention hold, лимит атомарного стирания,
  размер batch, lease fencing, rolled-back transaction при failure;
- подтверждённый `ERASED` и сохранение безопасного audit evidence.

### Документы и базы знаний
- `safeai_knowledge_erasure_worker` — отдельный DB login, секреты вне repo;
- bucket версионируется, Object Lock проверен с настоящим провайдером, retention/legal hold не обходятся;
- независимый `ErasureTombstoneJournal` immutable/write-once и независимые учётные данные;
- проверенный `purgeAllVersionsVerified` с пагинацией всех версий, не обычный `delete(key)`;
- audit/outbox; запрет RAG leases до удаления; нет незавершённой ingestion, нет конкурентного upload;
- recovery после crash между S3 purge и SQL commit, retries, NEEDS_REVIEW, честный unknown result;
- отдельные проверенные `accept-enabled`, `executor-enabled`, `erasure-journal.enabled`,
  `erasure-journal.object-lock-required` и feature flags для удаления базы;
- резервные копии и журналы под собственными сроками: физическое удаление активного контента не означает
  мгновенного уничтожения backup, immutable audit и разрешённых сохранений.

### Пользователь и организация
- пользователь: токены, связанные объекты, retention и последняя ADMIN-учётная запись проверяются в SQL,
  email подтверждения и авторизованный SUPER_ADMIN;
- организация: **нет реализации purge**. Требуется отдельная inventory, legal/privacy basis, согласование,
  экспорт и возврат полномочий, а не каскадное `DELETE FROM organizations`.

## 9. Операционная безопасность и мониторинг

Сделать в admin UI **после V103+** раздельные зоны: «Мои запросы», «Ожидают согласования»,
«Операции в исполнении», «Заблокированы/требуют внимания», «Завершены с доказательством».
Каждая строка показывает `requestId`, объект (с учётом доступа), инициатора, заявителя (если другой),
основание-код, согласующего, tenant, время, status, идентификатор удаления, ссылку на журнал.

Metrics без пользовательских ID в labels: total submitted/admitted/failed/held, queue oldest age,
worker lease, retries, unresolved unknown, last verified receipt, WORM health.
Alerts: очередь не двигается, lease starved, повышенные NEEDS_REVIEW, пропажа journal, S3 credentials denied,
Object Lock unexpectedly disabled, mismatch DB role. Alarm не равен «удаление произошло».

**Вместо** неограниченного текста объяснения хранить reasonCode из allow-list; PII evidence отдельно.
Bulk delete, delete other tenant, direct admin bypass, disable compliance holds — DENY по умолчанию.

## 10. Обязательный acceptance gate

Не давать Production GO по процентам JaCoCo, статическому флагу или `BUILD SUCCESS`.
Отдельные результаты:
1. Версионированная интеграционная среда PostgreSQL 16 + S3/MinIO с настоящим Object Lock.
2. DB roles `safeai_app` не могут DELETE содержимое в обход worker, worker не читает посторонние tenant.
3. Удаление конкретного документа, базы и чата по корректному request; отсутствие полномочия, чужой tenant,
   чужой owner, stale version, replay, MFA/reauth where required, revoked admin, expired approval.
4. Юридическое удержание, активные lease, S3 неизвестный ответ и сбой SQL после S3.
5. Kill -9 посреди удаления, restart на другом pod, без повторного физического стирания, durable NEEDS_REVIEW.
6. Результаты actual storage list-versions и tombstone receipts, tenant isolation и audit API.
7. Негативный тест: email/мессенджер без верификации -> запрос не превращается в операцию.
8. Двухлицевое подтверждение high-impact и невозможность утвердить свой запрос.
9. Нагрузочный bounded batch, no pool starvation, alerts, restore drill, политики backup.
10. Успешные Maven, Playwright, архитектурные, security, coverage, migration+ACL и operator drills на одном SHA.

**Результат текущей поставки:** исправлены тексты и доступность существующей страницы;
предоставлен контракт для полноценного адресного workflow и acceptance evidence gate.
**Не выполнено:** V103+, индивидуальные разрешения и согласования, интеграция реального journal/worker в целевой среде,
проверка Object Lock, сетевых прав, live S3 purge, полноценный production deployment.
Никаких runtime flags скрипт установки не меняет.
