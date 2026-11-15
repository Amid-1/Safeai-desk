<!-- module: backend/src/main/java/ru/safeai/gateway/knowledge/README.md -->
# Knowledge — архитектура, контракты и тесты

> **Актуальность:** 14.11.2026. Java 21, Spring Boot, PostgreSQL 16 + pgvector,
> Flyway V1–V90, ObjectStorage LOCAL/S3, optional OCR, embeddings 384 dimensions.
> **Состав:** `ru.safeai.gateway.knowledge` и согласованные контракты Chat/Model/Audit.
> **Статус:** реализованная архитектура, не утверждение о production acceptance.
> **Безопасность:** удаление и recovery по умолчанию выключены; SQL roles не смягчаются.

## Содержание

1. [Назначение и инварианты](#назначение-и-инварианты)
2. [Архитектура и потоки данных](#архитектура-и-потоки-данных)
3. [Структура production-кода](#структура-production-кода)
4. [REST API и права](#rest-api-и-права)
5. [Базы, документы и версии](#базы-документы-и-версии)
6. [Upload и Object Storage](#upload-и-object-storage)
7. [Ingestion, extraction и OCR](#ingestion-extraction-и-ocr)
8. [Embeddings, retrieval и RAG](#embeddings-retrieval-и-rag)
9. [Безопасное удаление V78–V89](#безопасное-удаление-v78v89)
10. [Generation GC V90](#generation-gc-v90)
11. [Миграции Flyway](#миграции-flyway)
12. [Параметры, метрики и эксплуатация](#параметры-метрики-и-эксплуатация)
13. [Каталог классов](#каталог-классов-назначение-и-ответственность)
14. [Структура тестов](#структура-тестов)
15. [Каталог тестов](#каталог-тестов-зачем-каждый-нужен)
16. [Запуск и критерии готовности](#запуск-и-критерии-готовности)
17. [Риски и границы гарантии](#риски-и-границы-гарантии)

## Назначение и инварианты

`Knowledge` — управляемая корпоративная база знаний для Chat/RAG. Модуль
хранит ACL, документы, версионные метаданные, состояние обработки, индексные
поколения, результаты поиска и доказательства использованных источников.
Физические файлы размещаются в `ObjectStorage`, а PostgreSQL/pgvector
является источником истины для metadata, ACL, leases и searchable pointers.

**Четыре обязательные границы:**

1. `organization_id` и membership контролируются сервером, не frontend.
2. Document version и `index_generation` нельзя произвольно перепривязать.
3. Сетевые PUT/DELETE в S3 не являются PostgreSQL транзакцией.
4. При недостатке доказательств действует `fail closed`, не фиктивный успех.

**Не путать состояния:** `enabled=false` исключает данные из обслуживания;
`PENDING/READY` относятся к ingestion; `AVAILABLE/DELETING` описывают
lifecycle документа; `DELETED` завершает **операцию** онлайн-очистки.
`NEEDS_REVIEW` означает неопределённость, `BLOCKED_BY_RETENTION` означает
подтверждённую защиту версии, а не обычную сетевую ошибку.

## Архитектура и потоки данных

```text
React Knowledge UI / Chat
      |
      v
Knowledge REST Controllers --------> KnowledgeAccessService (ACL)
      |                                   |
      +-- Base/Document Services ----------> PostgreSQL metadata + Flyway
      |        |
      |        +-- Upload Intent ----------> ObjectStorage LOCAL / S3
      |        |                               |
      |        +-- LINKED + ingestion job <--- verified storage result
      |        |
      |        +-- Ingestion Scheduler -----> claim/lease/retry
      |                 +-- Extraction / OCR
      |                 +-- Chunking / Embedding
      |                 +-- fenced publication --> searchable index generation
      |
      +-- Retrieval (vector + lexical + RRF)
      |        +-- retrieval runs / hits / context receipt
      |        +-- RAG permission + Answer Passport --> Chat finalization
      |
      +-- Deletion acceptance -------------> durable V78/V82 operation
               +-- separate erasure worker --> version-aware S3 purge
               +-- V84 terminal audit + V89 commit receipt
               +-- publisher -------------> immutable external journal
```

Главная особенность архитектуры: все операции с потерей данных проходят
через проверяемые переходы состояний, отдельные DB-роли и durable evidence.
Удаление SQL-строки или одного S3 key без проверки версий не удовлетворяет
контракту физического удаления.

## Структура production-кода

Путь: `backend/src/main/java/ru/safeai/gateway/knowledge/`.
В список включены компоненты V89 из позднейших патчей, которых ещё нет
в более старой статической инвентаризации Knowledge.

```text
├── chunking/
│   ├── KnowledgeChunkCandidate.java
│   └── KnowledgeChunker.java
├── config/
│   ├── KnowledgeEmbeddingConfigurationInvariantVerifier.java
│   ├── KnowledgeEmbeddingProperties.java
│   ├── KnowledgeErasureReleaseInvariantVerifier.java
│   ├── KnowledgeErasureWorkerDatabaseConfig.java
│   ├── KnowledgeIngestionProperties.java
│   ├── KnowledgeOcrConfigurationInvariantVerifier.java
│   ├── KnowledgeOcrProperties.java
│   ├── KnowledgeProcessingConfiguration.java
│   ├── KnowledgeRagProperties.java
│   └── KnowledgeRetrievalProperties.java
├── controller/
│   ├── AnswerPassportController.java
│   ├── KnowledgeBaseController.java
│   ├── KnowledgeBaseDeletionController.java
│   ├── KnowledgeBaseSearchController.java
│   ├── KnowledgeDeletionOperatorController.java
│   ├── KnowledgeDocumentController.java
│   ├── KnowledgeDocumentDeletionCapabilityController.java
│   ├── KnowledgeDocumentDeletionController.java
│   ├── KnowledgeDocumentDeletionReadController.java
│   ├── KnowledgeEvaluationController.java
│   └── KnowledgeRetrievalController.java
├── dto/
│   ├── AnswerCitationResponse.java
│   ├── AnswerPassportResponse.java
│   ├── CreateKnowledgeBaseMemberRequest.java
│   ├── CreateKnowledgeBaseRequest.java
│   ├── KnowledgeBaseAccessResponse.java
│   ├── KnowledgeBaseDeletionResponse.java
│   ├── KnowledgeBaseMemberPageResponse.java
│   ├── KnowledgeBaseMemberResponse.java
│   ├── KnowledgeBasePageResponse.java
│   ├── KnowledgeBaseResponse.java
│   ├── KnowledgeDocumentDeletionResponse.java
│   ├── KnowledgeDocumentPageResponse.java
│   ├── KnowledgeDocumentResponse.java
│   ├── KnowledgeDocumentVersionPageResponse.java
│   ├── KnowledgeDocumentVersionResponse.java
│   ├── KnowledgeEvaluationCaseRequest.java
│   ├── KnowledgeEvaluationCaseResponse.java
│   ├── KnowledgeEvaluationRequest.java
│   ├── KnowledgeEvaluationResponse.java
│   ├── KnowledgeHealthResponse.java
│   ├── KnowledgeMemberCandidateResponse.java
│   ├── KnowledgeReindexResponse.java
│   ├── KnowledgeRetrievalHitResponse.java
│   ├── KnowledgeRetrievalRequest.java
│   ├── KnowledgeRetrievalResponse.java
│   ├── UpdateKnowledgeBaseMemberRequest.java
│   ├── UpdateKnowledgeBaseRequest.java
│   └── UpdateKnowledgeDocumentRequest.java
├── embedding/
│   ├── HashingKnowledgeEmbeddingProvider.java
│   ├── KnowledgeEmbeddingException.java
│   ├── KnowledgeEmbeddingProductionInvariantVerifier.java
│   ├── KnowledgeEmbeddingProvider.java
│   ├── OpenAiKnowledgeEmbeddingProvider.java
│   └── PgVectorSupport.java
├── entity/
│   ├── KnowledgeBaseEntity.java
│   ├── KnowledgeBaseMembershipEntity.java
│   ├── KnowledgeDocumentEntity.java
│   ├── KnowledgeDocumentVersionEntity.java
│   └── KnowledgeIngestionJobEntity.java
├── evaluation/
│   └── KnowledgeRetrievalMetrics.java
├── exception/
│   └── KnowledgeStorageUnavailableException.java
├── extraction/
│   ├── CsvKnowledgeExtractor.java
│   ├── DocxKnowledgeExtractor.java
│   ├── ExtractedDocument.java
│   ├── ExtractedSection.java
│   ├── ExtractionTextSupport.java
│   ├── HtmlKnowledgeExtractor.java
│   ├── JsonKnowledgeExtractor.java
│   ├── KnowledgeDocumentExtractor.java
│   ├── KnowledgeExtractionService.java
│   ├── MarkdownKnowledgeExtractor.java
│   ├── OoxmlPackageSupport.java
│   ├── PdfKnowledgeExtractor.java
│   ├── PlainTextKnowledgeExtractor.java
│   ├── PptxKnowledgeExtractor.java
│   ├── XlsxKnowledgeExtractor.java
│   └── XmlKnowledgeExtractor.java
├── generation/
│   ├── KnowledgeGenerationGc.java
│   └── KnowledgeGenerationGcScheduler.java
├── ingestion/
│   ├── EmbeddedKnowledgeChunk.java
│   ├── ExpiredKnowledgeIngestionJob.java
│   ├── KnowledgeChunkPersistenceService.java
│   ├── KnowledgeIngestionClaim.java
│   ├── KnowledgeIngestionException.java
│   ├── KnowledgeIngestionFailureService.java
│   ├── KnowledgeIngestionMaintenanceService.java
│   ├── KnowledgeIngestionProcessor.java
│   ├── KnowledgeIngestionQueueRepository.java
│   ├── KnowledgeIngestionScheduler.java
│   └── StaleIngestionOwnershipException.java
├── model/
│   ├── KnowledgeBaseAccessLevel.java
│   ├── KnowledgeBaseVisibility.java
│   ├── KnowledgeDocumentLifecycleState.java
│   ├── KnowledgeHealthState.java
│   └── KnowledgeIngestionStatus.java
├── ocr/
│   ├── DisabledKnowledgeOcrProvider.java
│   ├── HttpKnowledgeOcrProvider.java
│   ├── KnowledgeOcrProvider.java
│   ├── OcrDocument.java
│   └── OcrPage.java
├── rag/
│   ├── AnswerPassportService.java
│   ├── KnowledgeChatContext.java
│   ├── KnowledgeCitationValidator.java
│   ├── KnowledgeContextAssembler.java
│   ├── KnowledgeContextSource.java
│   ├── KnowledgeHashing.java
│   ├── KnowledgeMode.java
│   ├── KnowledgeRagContextReceiptRepository.java
│   ├── KnowledgeRagContextReceiptService.java
│   ├── KnowledgeRagContextRevokedException.java
│   ├── KnowledgeRagErasureGuard.java
│   ├── KnowledgeRagExecutionPermitService.java
│   ├── KnowledgeRagService.java
│   ├── RagCitation.java
│   ├── RagCompletion.java
│   └── RagPreparation.java
├── repository/
│   ├── KnowledgeBaseMembershipRepository.java
│   ├── KnowledgeBaseRepository.java
│   ├── KnowledgeDocumentDeletionRepository.java
│   ├── KnowledgeDocumentQueryRepository.java
│   ├── KnowledgeDocumentRepository.java
│   ├── KnowledgeDocumentVersionRepository.java
│   ├── KnowledgeIngestionJobRepository.java
│   └── KnowledgeMemberDirectoryRepository.java
├── retrieval/
│   ├── KnowledgeRetrievalExecution.java
│   ├── KnowledgeRetrievalHit.java
│   └── KnowledgeRetrievalRepository.java
├── service/
│   ├── KnowledgeAccessService.java
│   ├── KnowledgeBaseDeletionExecutor.java
│   ├── KnowledgeBaseDeletionService.java
│   ├── KnowledgeBaseNameNormalizer.java
│   ├── KnowledgeBaseSearchPattern.java
│   ├── KnowledgeBaseSearchService.java
│   ├── KnowledgeBaseService.java
│   ├── KnowledgeDeletionDiagnostics.java
│   ├── KnowledgeDeletionOperatorService.java
│   ├── KnowledgeDocumentDeletionAcceptanceService.java
│   ├── KnowledgeDocumentDeletionExecutor.java
│   ├── KnowledgeDocumentFileValidator.java
│   ├── KnowledgeDocumentMediaTypeDetector.java
│   ├── KnowledgeDocumentMediaTypes.java
│   ├── KnowledgeDocumentNameNormalizer.java
│   ├── KnowledgeDocumentService.java
│   ├── KnowledgeDocumentWriteService.java
│   ├── KnowledgeErasureCommitPublisher.java
│   ├── KnowledgeErasureObservability.java
│   ├── KnowledgeEvaluationService.java
│   ├── KnowledgeNameNormalizerSupport.java
│   ├── KnowledgeOoxmlDetector.java
│   ├── KnowledgeOperationsService.java
│   ├── KnowledgeRetrievalService.java
│   ├── KnowledgeStructuredTextValidator.java
│   ├── KnowledgeUploadFileSupport.java
│   └── KnowledgeValidationErrors.java
└── storage/
    ├── reconciliation/
    │   ├── KnowledgeStorageReconciliationConfiguration.java
    │   ├── KnowledgeStorageReconciliationProperties.java
    │   ├── KnowledgeStorageReconciliationScheduler.java
    │   ├── KnowledgeStorageReviewMetrics.java
    │   └── KnowledgeStorageUploadJournal.java
    ├── ErasureTombstoneJournal.java
    ├── KnowledgeErasureTombstoneJournal.java
    ├── KnowledgeStorageConfiguration.java
    ├── KnowledgeStorageProductionInvariantVerifier.java
    ├── KnowledgeStorageProperties.java
    ├── KnowledgeStorageType.java
    ├── LocalObjectStorage.java
    ├── ObjectStorage.java
    ├── S3ObjectStorage.java
    ├── StoredObject.java
    └── VerifiedObjectLockException.java
```

## REST API и права

Для обычных операций используются пути под `/api/knowledge-bases`.
Изоляцию выполняют контроллеры и `KnowledgeAccessService`/SQL. Роль
`SUPER_ADMIN` сама по себе не является обходом tenant scope.

### Каталог баз и участники

- `GET /api/knowledge-bases`: доступные базы с серверным ACL-filter.
- `GET /api/knowledge-bases/{baseId}`: детали только разрешённой базы.
- `GET /api/knowledge-bases/{baseId}/access`: effective permission.
- `POST /api/knowledge-bases`: создание с правами ADMIN.
- `PATCH /api/knowledge-bases/{baseId}`: update с optimistic version.
- `GET /api/knowledge-bases/{baseId}/members`: список участников.
- `POST /api/knowledge-bases/{baseId}/members`: добавить участника.
- `PATCH /api/knowledge-bases/{baseId}/members/{userId}`: изменить доступ.
- `DELETE /api/knowledge-bases/{baseId}/members/{userId}`: убрать membership.
- `GET /api/knowledge-bases/member-candidates`: доступные кандидаты.
- `GET /api/knowledge-bases/search`: tenant-scoped поиск по имени.

### Документы, индексы, поиск

- `GET /api/knowledge-bases/{baseId}/documents`: страницы документов.
- `GET /api/knowledge-bases/{baseId}/documents/health`: состояние обработки.
- `POST /api/knowledge-bases/{baseId}/documents`: multipart upload.
- `POST /api/knowledge-bases/{baseId}/documents/{docId}/versions`: новая версия.
- `PATCH /api/knowledge-bases/{baseId}/documents/{docId}`: metadata update.
- `GET /api/knowledge-bases/{baseId}/documents/{docId}/versions`: версии.
- `GET /api/knowledge-bases/{baseId}/documents/{docId}/download`: файл.
- `GET /api/knowledge-bases/{baseId}/documents/{docId}/versions/{verId}/download`:
  заданная версия; всегда перед проверкой storage проверяется ACL.
- `POST /api/knowledge-bases/{baseId}/documents/{docId}/reindex`: новое поколение.
- `POST /api/knowledge-bases/{baseId}/retrieval`: гибридный retrieval.
- `POST /api/knowledge-bases/{baseId}/evaluations`: retrieval evaluation.
- `GET /api/chats/{chatId}/turns/{turnId}/answer-passport`: паспорт AI-ответа.

### Документный и родительский erasure

- `GET /api/knowledge-bases/{baseId}/document-deletions/capability`: доступность.
- `GET /api/knowledge-bases/{baseId}/document-deletions`: история операций.
- `GET /api/knowledge-bases/{baseId}/document-deletions/{opId}`: статус.
- `DELETE /api/knowledge-bases/{baseId}/documents/{docId}`: `202 Accepted`;
  доступен только при документных флагах и полномочиях владельца.
- `GET /api/knowledge-bases/{baseId}/base-deletions/capability`: доступность.
- `GET /api/knowledge-bases/base-deletions`: история удаления баз.
- `GET /api/knowledge-bases/{baseId}/base-deletions/current`: активная операция.
- `GET /api/knowledge-bases/{baseId}/base-deletions/{opId}`: статус.
- `DELETE /api/knowledge-bases/{baseId}`: запрос удаления базы, `202`;
  **не** подтверждение завершённого удаления.
- `GET .../diagnostics`: read-only ADMIN диагностика конкретной операции.
- `POST .../resume`: проверяемый operator resume, не прямое удаление файла.

`If-Match`, `Idempotency-Key`, серверные ACL и границы флагов проверяются
до допуска операций. Изменение membership или API-метаданных не является
логически эквивалентным уничтожению источника.

### Модель доступа

`KnowledgeBaseVisibility`: `ORGANIZATION` или `MEMBERS`.
`KnowledgeBaseAccessLevel`: `VIEWER`, `EDITOR`, `OWNER`.
Административные права применяются внутри tenant. Чужая MEMBERS-база не
должна быть перечислима или определяема по разнице текстов ошибок.

## Базы, документы и версии

`KnowledgeBaseEntity` содержит metadata, visibility и optimistic version.
`KnowledgeBaseMembershipEntity` хранит права участников. Документ представлен
`KnowledgeDocumentEntity` и версиями `KnowledgeDocumentVersionEntity`,
а processing отдельно зафиксирован в `KnowledgeIngestionJobEntity`.

`current_version_id` означает последнюю загруженную версию, а
`searchable_version_id` означает разрешённый для retrieval опубликованный
результат. Между этими моментами документ может быть временно `PENDING`.
Новую версию создают отдельно; не перезаписывают уже использованный source.

Применяются нормализация названий, контроль типов/размеров и безопасное
скачивание с `Content-Disposition`, чтобы пользовательский HTML/XML не
превращался в исполняемый контент того же origin.

## Upload и Object Storage

```text
HTTP upload + ACL + size/signature/structured validation
  -> durable INTENT in DB
  -> external ObjectStorage.put (outside DB metadata transaction)
  -> provider confirms write
  -> STORED
  -> version + job + required audit + LINKED (same DB transaction)
  -> ingestion PENDING
```

Неоднозначный ответ storage приводит к `NEEDS_REVIEW`, а не к догадке,
существует ли файл. `KnowledgeStorageReconciliationScheduler` разбирает
orphan/journal, удаляет только безопасно опознанные объекты с необходимым
fencing. Сам журнал не равен журналу необратимого удаления.

`ObjectStorage.purgeAllVersionsVerified(key)` по умолчанию отказывает, если
провайдер не умеет доказуемую физическую очистку. `S3ObjectStorage` должен
удалять все версии **и delete markers** ровно указанного ключа, не соседей
с похожим префиксом, затем проверять результат. `VerifiedObjectLockException`
означает подтверждённый retention/legal hold, а не любой HTTP 403.

## Ingestion, extraction и OCR

Планировщик берёт ограниченное число задач через PostgreSQL claim/lease.
`KnowledgeIngestionProcessor` контролирует ownership во время длительных
работ, а `KnowledgeChunkPersistenceService` публикует только допустимое
поколение индекса. Stale writer не должен воскресить удаляемый документ.

Pipeline: `VALIDATING → EXTRACTING → CHUNKING → EMBEDDING → READY`,
с retry/backoff и отдельной классификацией отказов. `workerThreads` ограничены;
внешние HTTP-запросы и parser execution не должны делать бесконтрольный fan-out.

Поддерживаемые parser paths: PDF, DOCX, PPTX, XLSX, HTML, CSV, JSON, XML,
Markdown и plain text. `OoxmlPackageSupport` проверяет ZIP entries и общий
объём распаковки. OCR подключается отдельным provider (`disabled` / `http`)
с явными host allowlist, TLS, тайм-аутами и лимитами ответа.

**Важная граница:** отмена `Future` не даёт жёсткой остановки парсера, который
игнорирует interrupt. Для недоверенных файлов необходимы sandboxed worker,
ограничение CPU/memory/time, запрет опасного egress и отдельные испытания.

## Embeddings, retrieval и RAG

### Embeddings и публикация

`KnowledgeEmbeddingProvider` разделяет deterministic hashing fallback
для тестов и внешнего OpenAI provider. Размерность PostgreSQL vector
зафиксирована на **384**; изменение модели/размерности без reindex и
миграции индексов запрещено. Production-invariant verifier должен отвергать
небезопасные локальные/фиктивные настройки.

`KnowledgeChunker` удерживает provenance страницы/секции/заголовка,
порядковый номер и SHA-256. Содержимое индексируется по поколениям;
READY публикуется только после сохранения согласованного набора chunks.

### Поиск

`KnowledgeRetrievalRepository` объединяет pgvector nearest neighbors и
русскоязычный lexical поиск, а затем Reciprocal Rank Fusion. SQL-фильтры
применяют tenant, видимость базы, доступ к документу и опубликованную версию
до выдачи результатов. Retrieval run/hits сохраняют источник и проверяемый
query digest, не разрешая подменять его чужой цитатой.

V76 обеспечивает privacy-правила новых retrieval queries. Исторические
raw query требуют самостоятельной retention/backfill проверки; одно наличие
SHA-256 не означает отсутствие PII в старых строках.

### RAG в Chat

`GENERAL` не требует Knowledge, `KNOWLEDGE_ASSISTED` допускает RAG,
`KNOWLEDGE_ONLY` требует строгих подтверждений. Перед физической попыткой
Knowledge готовит контекст, сохраняет receipt и V85 execution permit.
После ответа валидируются citation IDs и собирается `AnswerPassport`.

Паспорт и citation evidence должны соответствовать фактически выданному
ответу; финализация сохраняется вместе с Chat-turn. Валидная `[C1]` сама
по себе не доказывает, что утверждение модели действительно следует из
текста документа; нужны evaluation/groundedness и защита от prompt injection.

## Безопасное удаление V78–V89

### Почему это не обычный SQL DELETE

Документ может одновременно иметь metadata, несколько версий S3,
`delete markers`, извлечённые chunks, embeddings и исторические citations.
Удаление одной строки не уничтожает все эти копии, а потерянное сетевое
подтверждение не означает успешный purge. Контроль всегда многоэтапный.

```text
Document deletion request / base parent request
   -> role, tenant, If-Match, Idempotency-Key, flags
   -> V78/V82 durable DELETING + audit
   -> dedicated worker claims lease / storage inventory
   -> verified purge of every S3 version outside DB transaction
   -> fenced SQL content cleanup
   -> V84 required audit + V89 commit receipt (same DB commit)
   -> external immutable journal publisher (at-least-once, idempotent)
   -> state DELIVERED or durable retry, never fake success
```

Операция может стать `NEEDS_REVIEW` из-за неоднозначной операции S3 или
`BLOCKED_BY_RETENTION` после подтверждённого Object Lock. Эти статусы не
равны `DELETED`. Родительский worker не удаляет S3 напрямую, а координирует
дочерние операции и ждёт их подтверждённого завершения.

### Durable evidence и привилегии

- V78–V79: durable операция, идентичность, idempotency, guard и audit приёма.
- V80: immutable document-version/chunk identities; историческая provenance
  остаётся корректной после физического удаления online content.
- V81: строгий inventory и lease/fencing, блокировки late write/terminal.
- V82: отдельная parent-operation базы знаний и child orchestration.
- V83: operator diagnostics/resume только в разрешённом состоянии.
- V84: required audit outbox в той же транзакции, что terminal transition.
- V85: RAG permits, physical execution leases, защита гонки с удалением.
- V86: Chat erasure дополнительно проверяет RAG linkage, shared SQL guards.
- V89: изолированная SQL-роль worker, commit receipts и dual-control review.

**V89** требует отдельные `safeai_knowledge_erasure_worker` (LOGIN),
`safeai_knowledge_erasure_operator` (NOLOGIN) и migrator identity. Веб-
`safeai_app` не получает прямой `DELETE` на живые документы, версии, базы
или inventory. Триггеры и `session_user` проверяют реального исполнителя;
подстановка session GUC не заменяет права.

`knowledge_erasure_commit_outbox_v89` фиксирует receipt при переходе операции
в `DELETED` в одном DB commit. `KnowledgeErasureCommitPublisher` доставляет
её во внешний immutable journal после commit. При недоступности journal
строка остаётся для повторной доставки, без отката подтверждённого online
purge и без выдуманной delivery-success. Для прежних завершённых операций
в V89 предусмотрен backfill receipts.

### Retention, Object Lock и два оператора

Снятие legal hold не является функцией обычного HTTP ADMIN. V89 допускает
строго контролируемую **повторную проверку** `BLOCKED_BY_RETENTION` только
через `review_blocked_knowledge_erasure_v89(...)` с двумя независимыми
операторскими подтверждениями, совпадением ticket/evidence digest,
текущего `updated_at`, проверками зависимости и обязательным audit.

Это не обходит Object Lock. Если конкретная версия всё ещё защищена,
исполнитель снова обязан отказаться от удаления. Прямой
`UPDATE state='DELETING'` не является разрешённым восстановлением.

### Feature flags

```yaml
safeai:
  knowledge:
    deletion:
      accept-enabled: false
      executor-enabled: false
      recovery-enabled: false
      poll-delay: PT20S
    base-deletion:
      accept-enabled: false
      executor-enabled: false
      poll-delay: PT20S
```

Две document settings нужны для документного DELETE. Base DELETE требует
также две base settings. Операторский resume дополнительно требует recovery
flag и независимые SQL-права. Read-only status/history/capability должны
оставаться авторизованно доступными, даже когда destructive flags выключены.

**Предел обещания `DELETED`:** очищается предусмотренное online-хранилище;
immutable audit, V80 identities, provider snapshots, backups и сторонние
копии подчиняются отдельным правилам retention и не объявляются удалёнными.

## Generation GC V90

V90 **не** является обыкновенным document erasure. Он запрещает `safeai_app`
прямой `DELETE` из `public.knowledge_document_chunks` и предоставляет
ограниченную функцию для уже ограждённого поколения `COLLECTING`:

```sql
SELECT public.gc_knowledge_generation_chunks_v90(
    ?::uuid,
    ?::uuid
);
```

Функция `SECURITY DEFINER` устанавливает фиксированный `search_path`,
проверяет оба UUID, захватывает generation `FOR UPDATE`, требует
`state='COLLECTING'` и удаляет только относящиеся к ней chunks.
V57 trigger всё ещё действует, `EXECUTE` для `PUBLIC` отозван.

`KnowledgeGenerationGc` сохраняет retention/retrieval pins и запрет удаления
searchable generation. `KnowledgeGenerationGcPrivilegesV90IT` проверяет
реальные SQL privileges и отказ при недопустимом состоянии. Удаление
индексных chunks не равнозначно purge исходных документов в S3.

## Миграции Flyway

Данные кода и схемы должны соответствовать **V1–V90**. В интеграционном
контуре тестовая `V0__test_chat_erasure_roles.sql` создаёт необходимые роли;
она не является бизнес-миграцией. Используется отдельный migrator login.

- **V38** — `V38__knowledge_base_core.sql`: knowledge base core.
- **V39** — `V39__knowledge_documents_and_versions.sql`: knowledge documents and versions.
- **V40** — `V40__knowledge_ingestion_job_version_identity.sql`: knowledge ingestion job version
  identity.
- **V41** — `V41__durable_knowledge_ingestion_and_hybrid_retrieval.sql`: durable knowledge ingestion
  and hybrid retrieval.
- **V42** — `V42__rag_answers_and_answer_passports.sql`: rag answers and answer passports.
- **V44** — `V44__knowledge_production_integrity.sql`: knowledge production integrity.
- **V47** — `V47__knowledge_chunk_generation_uniqueness.sql`: knowledge chunk generation uniqueness.
- **V55** — `V55__knowledge_russian_lexical_profile.sql`: knowledge russian lexical profile.
- **V56** — `V56__knowledge_storage_upload_intents.sql`: knowledge storage upload intents.
- **V57** — `V57__knowledge_index_generations.sql`: knowledge index generations.
- **V59** — `V59__governance_integrity_hardening.sql`: governance integrity hardening.
- **V63** — `V63__knowledge_publication_and_retention_hardening.sql`: knowledge publication and
  retention hardening.
- **V76** — `V76__knowledge_retrieval_query_privacy.sql`: knowledge retrieval query privacy.
- **V78** — `V78__knowledge_document_deletion_foundation.sql`: knowledge document deletion
  foundation.
- **V79** — `V79__knowledge_deletion_request_audit_type.sql`: knowledge deletion request audit type.
- **V80** — `V80__knowledge_erasure_provenance_identities.sql`: knowledge erasure provenance
  identities.
- **V81** — `V81__knowledge_erasure_executor_guards.sql`: knowledge erasure executor guards.
- **V82** — `V82__knowledge_base_erasure_foundation.sql`: knowledge base erasure foundation.
- **V83** — `V83__knowledge_erasure_operator_audit_types.sql`: knowledge erasure operator audit
  types.
- **V84** — `V84__knowledge_erasure_terminal_audit.sql`: knowledge erasure terminal audit.
- **V85** — `V85__knowledge_rag_execution_leases_and_erasure_guards.sql`: knowledge rag execution
  leases and erasure guards.
- **V86** — `V86__chat_archive_restore_erasure.sql`: chat archive restore erasure.
- **V89** — `V89__knowledge_erasure_reconciliation_and_fencing.sql`: knowledge erasure
  reconciliation and fencing.
- **V90** — `V90__knowledge_generation_gc_least_privilege.sql`: knowledge generation gc least
  privilege.

**Правило:** нельзя изменять SQL миграции, уже применённые в окружениях.
Новые изменения требуют следующей append-only версии, проверки `validate`,
последовательного upgrade и испытаний на заполненной БД. `flyway clean`
недопустим на пользовательских или production данных.

## Параметры, метрики и эксплуатация

- `safeai.knowledge.ingestion.*`: batch, workers, lease, retries и extraction.
- `safeai.knowledge.embedding.*`: provider, dimensions, model и HTTPS policy.
- `safeai.knowledge.retrieval.*`: maxTopK, candidates, RRF, query limit.
- `safeai.knowledge.rag.*`: максимальная длина контекста и выдачи.
- `safeai.knowledge.ocr.*`: sandbox/allowlist/тайм-ауты/размер ответа.
- `safeai.knowledge.storage.*`: LOCAL/S3, bucket, credentials и upload limits.
- `safeai.knowledge.storage-reconciliation.*`: guarded cleanup policy.
- `safeai.knowledge.generation.gc.*`: retention, bounded cleaning, scheduler.
- `safeai.knowledge.deletion.*`: документный erasure и recovery.
- `safeai.knowledge.base-deletion.*`: родительские операции удаления.
- `safeai.knowledge.erasure.observability.*`: безопасные read-only gauges.

Метрики включают backlog, NEEDS_REVIEW, age, retries и freshness. Для
failed refresh нельзя подставлять `0` в gauge, притворяясь здоровым.
Метки не содержат содержимого файлов, tenant/user IDs или object keys.
Alertmanager routing, backup policies и external S3 semantics надо проверять
отдельно; наличие кода и dashboard не означает готовность к аварии.

---

## Каталог классов: назначение и ответственность

Список построен по архивам исходников и дополняющим патчам. Для каждого файла указан его
контракт и, где это уместно, реальные имена публичных методов. Изменения реализации сверяют
с исходным `.java`: документация не заменяет контракт компилятора и SQL.

### `chunking/` — Структурная нарезка текста (2)

- **`KnowledgeChunkCandidate`** — Immutable chunk: текст, порядковый номер, источник и хеш
  фрагмента. **Record-поля:** `ordinal`, `content`, `contentSha256`, `estimatedTokens`, `pageFrom`,
  `pageTo`, `heading`. **Методы:** отсутствуют явные объявления; используются значения record/enum.
- **`KnowledgeChunker`** — Разбивал извлечённые секции на перекрывающиеся сегменты с границами
  предложений. Публичные операции: `chunk()`.

### `config/` — Настройки и production invariants (10)

- **`KnowledgeEmbeddingConfigurationInvariantVerifier`** — Проверял допустимость
  embedding-конфигурации при старте.
- **`KnowledgeEmbeddingProperties`** — Задавал провайдера, модель, размерность, batch и транспортные
  лимиты. **Record-поля:** `provider`, `baseUrl`, `apiKey`, `model`, `dimensions`, `batchSize`,
  `maxInputChars`, `connectTimeout`, `readTimeout`.
- **`KnowledgeErasureReleaseInvariantVerifier`** — Конфигурация с проверяемыми параметрами.
- **`KnowledgeErasureWorkerDatabaseConfig`** — Изолированный DataSource для физического удаления с
  проверкой SQL login. Публичные операции: `jdbc()`, `transactionManager()`,
  `knowledgeErasureWorkerConnections()`.
- **`KnowledgeIngestionProperties`** — Задавал очередь, lease, retries, backoff, парсинг и размер
  результата. **Record-поля:** `enabled`, `pollDelay`, `batchSize`, `processingLease`,
  `extractionTimeout`, `extractionThreads`, `maxAttempts`, `initialBackoff`, `maxBackoff`,
  `maxExtractedChars`, `maxDocxUncompressedBytes`, `chunkSizeChars`, `chunkOverlapChars`. Публичные
  операции: `backoffForAttempt()`.
- **`KnowledgeOcrConfigurationInvariantVerifier`** — Сверял сетевые и временные OCR-ограничения.
- **`KnowledgeOcrProperties`** — Проверял OCR endpoint, allowlist, секрет, лимит PDF/ответа и
  тайм-ауты. **Record-поля:** `provider`, `endpoint`, `apiKey`, `allowedHosts`,
  `minNativeCharsPerPage`, `connectTimeout`, `readTimeout`, `maxResponseBytes`, `maxInputBytes`.
- **`KnowledgeProcessingConfiguration`** — Регистрировал инфраструктурные bean-компоненты обработки.
  **Методы:** отсутствуют явные объявления; используются значения record/enum.
- **`KnowledgeRagProperties`** — Проверял лимиты RAG top-k и длины materialized context.
  **Record-поля:** `topK`, `maxContextChars`, `maxChunkChars`.
- **`KnowledgeRetrievalProperties`** — Ограничивал query, top-k, кандидатов и коэффициент RRF.
  **Record-поля:** `maxQueryChars`, `maxTopK`, `candidateLimit`, `rrfK`.

### `controller/` — API, авторизация и read/write разделение (11)

- **`AnswerPassportController`** — Отдавал подтверждённый паспорт ответа по chat/turn. Публичные
  операции: `get()`.
- **`KnowledgeBaseController`** — Предоставлял CRUD БЗ и администрирование участников. Публичные
  операции: `findAll()`, `findById()`, `effectiveAccess()`, `create()`.
- **`KnowledgeBaseDeletionController`** — Capability, история и управление запросом удаления базы;
  service-проверки обязательны. Публичные операции: `capability()`, `delete()`, `list()`,
  `current()`.
- **`KnowledgeBaseSearchController`** — HTTP-поиск баз знаний с организационной фильтрацией и
  серверной пагинацией. Публичные операции: `search()`.
- **`KnowledgeDeletionOperatorController`** — ADMIN diagnostics и строго контролируемое
  возобновление операций NEEDS_REVIEW. Публичные операции: `documentDiagnostics()`,
  `baseDiagnostics()`, `resumeDocument()`, `resumeBase()`.
- **`KnowledgeDocumentController`** — Предоставлял загрузку, версии, скачивание, health и reindex.
  Публичные операции: `health()`, `reindex()`, `list()`, `listVersions()`.
- **`KnowledgeDocumentDeletionCapabilityController`** — Чтение возможности удаления документа без
  включения destructive endpoint. Публичные операции: `capability()`, `CapabilityResponse()`.
- **`KnowledgeDocumentDeletionController`** — Приём DELETE документа с If-Match/idempotency;
  физический purge не выполняется HTTP-потоком. Публичные операции: `delete()`.
- **`KnowledgeDocumentDeletionReadController`** — История и статусы документных операций доступны
  даже при выключенном DELETE. Публичные операции: `status()`, `list()`.
- **`KnowledgeEvaluationController`** — Открывал административную оценку retrieval. Публичные
  операции: `evaluate()`.
- **`KnowledgeRetrievalController`** — Открывал ручную операцию поиска по базе знаний. Публичные
  операции: `retrieve()`.

### `dto/` — Контракты запросов/ответов (28)

- **`AnswerCitationResponse`** — Передавал ссылку цитаты на физический chunk и версию документа.
  **Record-поля:** `label`, `chunkId`, `documentId`, `documentVersionId`, `documentName`,
  `versionNumber`, `chunkOrdinal`, `pageFrom`, `pageTo`, `heading`, `contentSha256`. **Методы:**
  отсутствуют явные объявления; используются значения record/enum.
- **`AnswerPassportResponse`** — Строго типизированный контракт этого API.
- **`CreateKnowledgeBaseMemberRequest`** — Запрашивал выдачу роли участнику Knowledge Base.
  **Record-поля:** `userId`, `accessLevel`. **Методы:** отсутствуют явные объявления; используются
  значения record/enum.
- **`CreateKnowledgeBaseRequest`** — Создавал БЗ с именем, видимостью и включённым состоянием.
  **Record-поля:** `name`, `description`, `visibility`. **Методы:** отсутствуют явные объявления;
  используются значения record/enum.
- **`KnowledgeBaseAccessResponse`** — Возвращал эффективный уровень прав текущего пользователя.
  **Record-поля:** `knowledgeBaseId`, `accessLevel`, `administrator`, `canEditDocuments`,
  `canManageBase`, `canManageMembers`. Публичные операции: `from()`.
- **`KnowledgeBaseDeletionResponse`** — DTO статуса операции удаления базы без раскрытия удалённого
  содержимого.
- **`KnowledgeBaseMemberPageResponse`** — Упаковывал страницу участников с пагинацией.
  **Record-поля:** `content`, `page`, `size`, `totalElements`, `totalPages`. Публичные операции:
  `from()`.
- **`KnowledgeBaseMemberResponse`** — Передавал роль участника и версионные поля. **Record-поля:**
  `knowledgeBaseId`, `userId`, `email`, `fullName`, `accessLevel`, `version`, `createdAt`,
  `updatedAt`. **Методы:** отсутствуют явные объявления; используются значения record/enum.
- **`KnowledgeBasePageResponse`** — Упаковывал страницу БЗ и метаданные пагинации. **Record-поля:**
  `content`, `page`, `size`, `totalElements`, `totalPages`. Публичные операции: `from()`.
- **`KnowledgeBaseResponse`** — Возвращал свойства и версию БЗ. **Record-поля:** `id`,
  `organizationId`, `name`, `description`, `visibility`, `enabled`, `createdByUserId`, `version`,
  `createdAt`, `updatedAt`. Публичные операции: `from()`.
- **`KnowledgeDocumentDeletionResponse`** — DTO operation ID, статуса удаления и связанных
  безопасных метаданных.
- **`KnowledgeDocumentPageResponse`** — Упаковывал страницу документов. **Record-поля:** `content`,
  `page`, `size`, `totalElements`, `totalPages`.
- **`KnowledgeDocumentResponse`** — Описывал документ, его текущую версию и ingest-статус.
  **Record-поля:** `id`, `knowledgeBaseId`, `name`, `enabled`, `version`, `currentVersionId`,
  `searchableVersionId`, `versionNumber`, `originalFilename`, `mediaType`, `sizeBytes`, `status`,
  `createdAt`, `updatedAt`. Публичные операции: `from()`.
- **`KnowledgeDocumentVersionPageResponse`** — Упаковывал историю версий документа. **Record-поля:**
  `content`, `page`, `size`, `totalElements`, `totalPages`. Публичные операции: `from()`.
- **`KnowledgeDocumentVersionResponse`** — Передавал метаданные версии, SHA-256 и состояние.
  **Record-поля:** `id`, `documentId`, `versionNumber`, `originalFilename`, `mediaType`,
  `sizeBytes`, `sha256`, `createdByUserId`, `createdAt`. Публичные операции: `from()`.
- **`KnowledgeEvaluationCaseRequest`** — Передавал запрос поиска и множество ожидаемых версий.
  **Record-поля:** `query`, `expectedDocumentVersionIds`.
- **`KnowledgeEvaluationCaseResponse`** — Возвращал показатели качества отдельного evaluation-кейса.
  **Record-поля:** `ordinal`, `retrievalRunId`, `recall`, `reciprocalRank`, `ndcg`,
  `firstRelevantRank`. **Методы:** отсутствуют явные объявления; используются значения record/enum.
- **`KnowledgeEvaluationRequest`** — Запрашивал batch evaluation с dataset name и top-k.
  **Record-поля:** `datasetName`, `topK`, `cases`.
- **`KnowledgeEvaluationResponse`** — Возвращал итоговые значения recall, MRR и nDCG.
  **Record-поля:** `evaluationRunId`, `knowledgeBaseId`, `datasetName`, `topK`, `meanRecall`,
  `meanReciprocalRank`, `meanNdcg`, `createdAt`, `cases`.
- **`KnowledgeHealthResponse`** — Строго типизированный контракт этого API.
- **`KnowledgeMemberCandidateResponse`** — Представлял кандидата для приглашения в БЗ.
  **Record-поля:** `userId`, `email`, `fullName`. **Методы:** отсутствуют явные объявления;
  используются значения record/enum.
- **`KnowledgeReindexResponse`** — Подтверждал постановку версии документа на переиндексацию.
  **Record-поля:** `knowledgeBaseId`, `documentId`, `documentVersionId`, `ingestionJobId`, `status`,
  `requestedAt`. **Методы:** отсутствуют явные объявления; используются значения record/enum.
- **`KnowledgeRetrievalHitResponse`** — Описывал источник, ранги и оценки одного retrieval hit.
  **Record-поля:** `rank`, `chunkId`, `documentId`, `documentVersionId`, `documentName`,
  `versionNumber`, `chunkOrdinal`, `content`, `pageFrom`, `pageTo`, `heading`, `fusedScore`,
  `lexicalRank`, `semanticRank`, `lexicalScore`, `cosineSimilarity`, `contentSha256`. Публичные
  операции: `from()`.
- **`KnowledgeRetrievalRequest`** — Запрашивал строку поиска и top-k. **Record-поля:** `query`,
  `topK`. **Методы:** отсутствуют явные объявления; используются значения record/enum.
- **`KnowledgeRetrievalResponse`** — Возвращал ID retrieval run, хеш запроса и отсортированные hits.
  **Record-поля:** `retrievalRunId`, `knowledgeBaseId`, `querySha256`, `embeddingModel`,
  `completedAt`, `hits`.
- **`UpdateKnowledgeBaseMemberRequest`** — Менял права участника с контролем ожидаемой версии.
  **Record-поля:** `accessLevel`, `expectedVersion`. **Методы:** отсутствуют явные объявления;
  используются значения record/enum.
- **`UpdateKnowledgeBaseRequest`** — Менял свойства БЗ с ожидаемой версией. **Record-поля:** `name`,
  `description`, `visibility`, `enabled`, `expectedVersion`. **Методы:** отсутствуют явные
  объявления; используются значения record/enum.
- **`UpdateKnowledgeDocumentRequest`** — Менял метаданные документа. **Record-поля:** `name`,
  `enabled`, `expectedVersion`. **Методы:** отсутствуют явные объявления; используются значения
  record/enum.

### `embedding/` — Провайдеры векторов и проверка размерности (6)

- **`HashingKnowledgeEmbeddingProvider`** — Детерминированный локальный hashing embedding для тестов
  и демонстраций. Публичные операции: `dimensions()`, `model()`, `embed()`.
- **`KnowledgeEmbeddingException`** — Хранил код ошибки и retryable-классификацию embedding.
  Публичные операции: `code()`, `retryable()`.
- **`KnowledgeEmbeddingProductionInvariantVerifier`** — Запрещал тестовый hashing embedding в
  production.
- **`KnowledgeEmbeddingProvider`** — Контракт для одиночной и пакетной векторизации.
- **`OpenAiKnowledgeEmbeddingProvider`** — Вызывал внешний embedding API с строгой проверкой ответа
  и ограничениями. Публичные операции: `dimensions()`, `model()`, `preferredBatchSize()`, `embed()`.
- **`PgVectorSupport`** — Сериализовал проверенные float-векторы для PostgreSQL pgvector. Публичные
  операции: `encode()`.

### `entity/` — Персистентные сущности (5)

- **`KnowledgeBaseEntity`** — JPA-сущность БЗ с tenant ID, видимостью, флагом enabled и version.
- **`KnowledgeBaseMembershipEntity`** — Связь пользователя с Knowledge Base и уровнем доступа.
- **`KnowledgeDocumentEntity`** — Сущность документа с current/searchable версиями.
- **`KnowledgeDocumentVersionEntity`** — Immutable metadata версии, storage key, хеш, media type и
  размер.
- **`KnowledgeIngestionJobEntity`** — Состояние ingestion job, attempt, lease, token и технические
  результаты.

### `evaluation/` — Оценка качества retrieval (1)

- **`KnowledgeRetrievalMetrics`** — Считал recall, reciprocal rank и nDCG для retrieval run.
  Публичные операции: `evaluate()`, `Result()`.

### `exception/` — Безопасное представление ошибок (1)

- **`KnowledgeStorageUnavailableException`** — Переводил недоступность object storage в безопасный
  HTTP 503.

### `extraction/` — Разбор документов с лимитами (16)

- **`CsvKnowledgeExtractor`** — Разбирал CSV и превращал таблицы в извлекаемый текст. Публичные
  операции: `supports()`, `extract()`.
- **`DocxKnowledgeExtractor`** — Извлекал абзацы и таблицы DOCX с проверкой OOXML. Публичные
  операции: `supports()`, `extract()`.
- **`ExtractedDocument`** — Группировал секции и версию алгоритма извлечения. **Record-поля:**
  `extractorVersion`, `sections`, `characterCount`.
- **`ExtractedSection`** — Хранил текст секции и её страницу/заголовок. **Record-поля:**
  `pageNumber`, `heading`, `text`. **Методы:** отсутствуют явные объявления; используются значения
  record/enum.
- **`ExtractionTextSupport`** — Нормализовал extracted text, UTF-8 и ограничивал объём.
- **`HtmlKnowledgeExtractor`** — Извлекал полезный текст из HTML через парсер. Публичные операции:
  `supports()`, `extract()`.
- **`JsonKnowledgeExtractor`** — Проверял JSON и извлекал текстовую репрезентацию. Публичные
  операции: `supports()`, `extract()`.
- **`KnowledgeDocumentExtractor`** — Единый интерфейс извлечения текста по media type.
- **`KnowledgeExtractionService`** — Исполнял извлечение в ограниченном thread pool с timeout.
  Публичные операции: `extract()`.
- **`MarkdownKnowledgeExtractor`** — Извлекал Markdown и заголовки документа. Публичные операции:
  `supports()`, `extract()`.
- **`OoxmlPackageSupport`** — Проверял zip/OOXML структуру, имена entry и декомпрессию. Публичные
  операции: `validate()`, `ArchiveIndex()`, `contentTypes()`, `count()`.
- **`PdfKnowledgeExtractor`** — Читал PDF постранично и запрашивал OCR только для нужных страниц.
  Публичные операции: `supports()`, `extract()`.
- **`PlainTextKnowledgeExtractor`** — Декодировал и валидировал plain text. Публичные операции:
  `supports()`, `extract()`.
- **`PptxKnowledgeExtractor`** — Извлекал содержимое слайдов, групп и таблиц PPTX. Публичные
  операции: `supports()`, `extract()`.
- **`XlsxKnowledgeExtractor`** — Извлекал значения ячеек и листов XLSX. Публичные операции:
  `supports()`, `extract()`.
- **`XmlKnowledgeExtractor`** — Использовал безопасный streaming XML reader. Публичные операции:
  `supports()`, `extract()`.

### `generation/` — GC индексных поколений (2)

- **`KnowledgeGenerationGc`** — Сохраняет pinned/searchable поколения, удаляет только ограждённые
  COLLECTING chunks. Публичные операции: `collect()`.
- **`KnowledgeGenerationGcScheduler`** — Запускает ограниченный периодический сбор неиспользуемых
  поколений. Публичные операции: `collect()`.

### `ingestion/` — Очередь, lease и публикация (11)

- **`EmbeddedKnowledgeChunk`** — Связывал chunk с рассчитанным вектором. **Record-поля:** `chunk`,
  `embedding`. Публичные операции: `embedding()`.
- **`ExpiredKnowledgeIngestionJob`** — Идентифицировал задачу с потерянным lease. **Record-поля:**
  `jobId`, `organizationId`, `knowledgeBaseId`, `documentId`, `documentVersionId`, `attempt`.
  **Методы:** отсутствуют явные объявления; используются значения record/enum.
- **`KnowledgeChunkPersistenceService`** — Атомарно публиковал chunks/поколение и READY +
  обязательный audit. Публичные операции: `replaceChunksAndComplete()`.
- **`KnowledgeIngestionClaim`** — Фиксировал владение задачей через jobId, processing token и
  attempt. **Record-поля:** `jobId`, `organizationId`, `knowledgeBaseId`, `documentId`,
  `documentVersionId`, `processingToken`, `attempt`, `leaseUntil`. **Методы:** отсутствуют явные
  объявления; используются значения record/enum.
- **`KnowledgeIngestionException`** — Передавал стабильный код failure и флаг возможности повторной
  попытки. Публичные операции: `code()`, `retryable()`.
- **`KnowledgeIngestionFailureService`** — Регистрировал ошибку/повтор и системное audit-событие.
  Публичные операции: `recordFailure()`.
- **`KnowledgeIngestionMaintenanceService`** — Закрывал исчерпанные lease задачи и инициировал
  maintenance. Публичные операции: `failExhaustedExpired()`.
- **`KnowledgeIngestionProcessor`** — Оркестрировал validate → extract → chunk → embed → publish.
  Публичные операции: `process()`.
- **`KnowledgeIngestionQueueRepository`** — Реализовал SKIP LOCKED claim, lease renewal, retries и
  state transitions. Публичные операции: `claimNext()`, `transition()`, `renewLease()`, `fail()`.
- **`KnowledgeIngestionScheduler`** — Периодически захватывал и обрабатывал bounded batch задач.
  Публичные операции: `poll()`.
- **`StaleIngestionOwnershipException`** — Обозначал устаревший processing token или истёкший lease.

### `model/` — Доменные enum/state (5)

- **`KnowledgeBaseAccessLevel`** — Типизированные роли OWNER, EDITOR и VIEWER. **Методы:**
  отсутствуют явные объявления; используются значения record/enum.
- **`KnowledgeBaseVisibility`** — Разделял доступ уровня организации и приглашённых участников.
  **Методы:** отсутствуют явные объявления; используются значения record/enum.
- **`KnowledgeDocumentLifecycleState`** — Отдельное состояние жизненного цикла документа: AVAILABLE
  / DELETING.
- **`KnowledgeHealthState`** — Перечислял состояния здоровья индекса БЗ. **Методы:** отсутствуют
  явные объявления; используются значения record/enum.
- **`KnowledgeIngestionStatus`** — Перечислял статусы очереди и обработки. **Методы:** отсутствуют
  явные объявления; используются значения record/enum.

### `ocr/` — Внешний OCR и disabled provider (5)

- **`DisabledKnowledgeOcrProvider`** — Отказывал в OCR при отключённой интеграции. Публичные
  операции: `enabled()`, `extractPdf()`.
- **`HttpKnowledgeOcrProvider`** — Отправлял PDF в OCR API и валидировал страницы и версию модели.
  Публичные операции: `enabled()`, `extractPdf()`.
- **`KnowledgeOcrProvider`** — Интерфейс извлечения текста страниц через OCR.
- **`OcrDocument`** — Хранил результат OCR с версией модели. **Record-поля:** `modelVersion`,
  `pages`.
- **`OcrPage`** — Описывал страницу OCR с нумерацией и распознанным текстом. **Record-поля:**
  `pageNumber`, `text`.

### `rag/` — RAG-подготовка и доказательства ответа (16)

- **`AnswerPassportService`** — Транзакционно сохранял и выдавал evidence/persistent citation для
  ответа. Публичные операции: `persist()`, `findByTurn()`, `requireByTurn()`.
- **`KnowledgeChatContext`** — Передавал контекст chat-turn в RAG слой.
- **`KnowledgeCitationValidator`** — Проверял формат и принадлежность цитат конкретному набору
  источников. Публичные операции: `validate()`.
- **`KnowledgeContextAssembler`** — Составлял bounded AI-контекст с политикой untrusted sources.
  Публичные операции: `assemble()`, `AssembledContext()`.
- **`KnowledgeContextSource`** — Связывал метку [C1] и конкретный retrieved hit. **Record-поля:**
  `label`, `hit`. **Методы:** отсутствуют явные объявления; используются значения record/enum.
- **`KnowledgeHashing`** — Формировал стабильные SHA-256-хеши evidence.
- **`KnowledgeMode`** — Определял обычный, assisted и knowledge-only режимы. Публичные операции:
  `usesKnowledge()`.
- **`KnowledgeRagContextReceiptRepository`** — Сохранял hash prepared RAG-запроса для последующей
  проверки.
- **`KnowledgeRagContextReceiptService`** — Фиксировал immutable receipt RAG в отдельной транзакции.
  Публичные операции: `record()`, `assertSourcesAvailable()`.
- **`KnowledgeRagContextRevokedException`** — Компонент подготовки контекста, цитат или evidence.
- **`KnowledgeRagErasureGuard`** — Перепроверяет RAG-права и активность источников во время
  удаления.
- **`KnowledgeRagExecutionPermitService`** — Создаёт/проверяет отдельные разрешения на RAG
  физический вызов. Публичные операции: `createPermit()`, `assertFinalizationAllowed()`,
  `revokePermitAfterRejectedFinalization()`, `closePermit()`.
- **`KnowledgeRagService`** — Координировал retrieval, materialization, receipt и citation
  validation. Публичные операции: `prepare()`, `assertSourcesAvailable()`, `closeExecutionPermit()`,
  `complete()`.
- **`RagCitation`** — Связывал валидную метку цитирования и UUID chunk. **Record-поля:** `label`,
  `ordinal`, `chunkId`. **Методы:** отсутствуют явные объявления; используются значения record/enum.
- **`RagCompletion`** — Хранил проверенный результат LLM и набор цитат. **Record-поля:**
  `preparation`, `response`, `citations`, `citationsValid`, `evidenceSufficient`. Публичные
  операции: `general()`.
- **`RagPreparation`** — Хранил подготовленный RAG-запрос, источники и хеши. **Record-поля:**
  `mode`, `knowledgeBaseId`, `retrievalRunId`, `embeddingModel`, `contextSha256`, `sources`,
  `aiRequest`. Публичные операции: `general()`, `withExecutionPermit()`.

### `repository/` — Tenant-scoped и защищённый доступ к БД (8)

- **`KnowledgeBaseMembershipRepository`** — Читал/блокировал членство пользователя в БЗ.
- **`KnowledgeBaseRepository`** — Выбирал БЗ с tenant-scoped условиями и блокировками.
- **`KnowledgeDocumentDeletionRepository`** — Tenant-scoped поиск операций удаления по idempotency
  key, документу, operation ID и базе. Публичные операции: `findByKey()`, `findByDocument()`,
  `findById()`, `listByBase()`.
- **`KnowledgeDocumentQueryRepository`** — Реализовал эффективные списки и проекции документов.
  Публичные операции: `list()`.
- **`KnowledgeDocumentRepository`** — Читал и блокировал tenant-scoped документы.
- **`KnowledgeDocumentVersionRepository`** — Выбирал неизменяемую версию по составной области
  доступа.
- **`KnowledgeIngestionJobRepository`** — JPA-доступ к ingest-job.
- **`KnowledgeMemberDirectoryRepository`** — Подбирал кандидатов в участники из организации.

### `retrieval/` — Гибридный поиск (3)

- **`KnowledgeRetrievalExecution`** — Связывал результаты одного поискового запуска и его identity.
  **Record-поля:** `retrievalRunId`, `knowledgeBaseId`, `chatTurnId`, `querySha256`,
  `embeddingModel`, `completedAt`, `hits`.
- **`KnowledgeRetrievalHit`** — Вспомогательный компонент модуля.
- **`KnowledgeRetrievalRepository`** — Выполнял tenant-safe hybrid SQL: tsvector + pgvector + RRF.
  Публичные операции: `hybridSearch()`.

### `service/` — ACL, документы и удаление (27)

- **`KnowledgeAccessService`** — Вычислял effective ACL с tenant isolation и скрытием MEMBERS БЗ.
  Публичные операции: `requireAccess()`, `atLeast()`, `isAdmin()`, `Access()`.
- **`KnowledgeBaseDeletionExecutor`** — Родительский координатор V82; физический S3 purge выполняет
  child worker. Публичные операции: `poll()`, `processOperation()`.
- **`KnowledgeBaseDeletionService`** — Авторизованно принимает durable операцию удаления базы и
  child intents. Публичные операции: `enabled()`, `capability()`, `accept()`, `list()`.
- **`KnowledgeBaseNameNormalizer`** — Нормализовал название Knowledge Base. Публичные операции:
  `normalize()`.
- **`KnowledgeBaseSearchPattern`** — Нормализация буквального имени и экранирование
  wildcard-символов SQL LIKE.
- **`KnowledgeBaseSearchService`** — Tenant-scoped поиск по каталогу баз ДО пагинации; проверка прав
  и буквального запроса. Публичные операции: `search()`.
- **`KnowledgeBaseService`** — Управлял БЗ и членством с блокировками и audit. Публичные операции:
  `findAll()`, `findById()`, `create()`, `update()`.
- **`KnowledgeDeletionDiagnostics`** — Прикладная операция с контролем доступа и состояния.
- **`KnowledgeDeletionOperatorService`** — Безопасные diagnostics и контролируемое восстановление
  проблемных операций. Публичные операции: `Diagnostics()`, `documentDiagnostics()`,
  `baseDiagnostics()`, `resumeDocument()`.
- **`KnowledgeDocumentDeletionAcceptanceService`** — Сохраняет документный erasure intent, без
  сетевого удаления в транзакции. Публичные операции: `accept()`, `status()`, `list()`,
  `capability()`.
- **`KnowledgeDocumentDeletionExecutor`** — Доказуемый version-aware purge с lease/fencing и
  terminal audit. Публичные операции: `poll()`, `pollOne()`, `processOperation()`.
- **`KnowledgeDocumentFileValidator`** — Проверял файл, размер, имя, содержимое и media type.
  Публичные операции: `validate()`, `ValidatedUpload()`, `bytes()`, `sizeBytes()`.
- **`KnowledgeDocumentMediaTypeDetector`** — Определял фактический формат по сигнатуре/структуре.
- **`KnowledgeDocumentMediaTypes`** — Задавал разрешённые MIME-константы.
- **`KnowledgeDocumentNameNormalizer`** — Нормализовал имя документа. Публичные операции:
  `normalize()`.
- **`KnowledgeDocumentService`** — Координировал upload/download, права, версии и S3 journal.
  Публичные операции: `list()`, `listVersions()`, `update()`, `uploadNew()`.
- **`KnowledgeDocumentWriteService`** — Атомарно публиковал метаданные документа, job и audit.
  Публичные операции: `createInitialVersion()`, `addVersion()`, `updateDocument()`.
- **`KnowledgeErasureCommitPublisher`** — Публикует V89 commit receipt во внешний неизменяемый
  journal после DB commit. Публичные операции: `poll()`.
- **`KnowledgeErasureObservability`** — Read-only метрики удаления без PII, с freshness/error
  семантикой. Публичные операции: `refresh()`.
- **`KnowledgeEvaluationService`** — Прогонял тестовые retrieval dataset и записывал метрики.
  Публичные операции: `evaluate()`.
- **`KnowledgeNameNormalizerSupport`** — Проверял Unicode, controls, пробелы и длину имён.
- **`KnowledgeOoxmlDetector`** — Определял DOCX/PPTX/XLSX после проверки ZIP-структуры.
- **`KnowledgeOperationsService`** — Оценивал здоровье БЗ и отправлял версии на reindex. Публичные
  операции: `health()`, `reindex()`.
- **`KnowledgeRetrievalService`** — Делал embedding запроса, hybrid search и сохранял
  run/hits/audit. Публичные операции: `retrieve()`, `retrieveForChat()`.
- **`KnowledgeStructuredTextValidator`** — Проверял строгие JSON/XML/UTF-8 и управляющие символы.
- **`KnowledgeUploadFileSupport`** — Безопасно читал MultipartFile, проверял расширение и SHA-256.
- **`KnowledgeValidationErrors`** — Формировал стандартные ошибки валидации входных файлов.

### `storage/` — Хранилище и version-aware purge (11)

- **`ErasureTombstoneJournal`** — Объект и интерфейс инфраструктурного хранения.
- **`KnowledgeErasureTombstoneJournal`** — Записывает минимальное неизменяемое evidence без
  содержания документа. Публичные операции: `publishDocumentIntent()`, `publishObjectPurgeIntent()`,
  `publishObjectPurged()`, `publishDocumentCommitted()`.
- **`KnowledgeStorageConfiguration`** — Регистрировал настройки object storage. **Методы:**
  отсутствуют явные объявления; используются значения record/enum.
- **`KnowledgeStorageProductionInvariantVerifier`** — Требовал S3 и HTTPS в production.
- **`KnowledgeStorageProperties`** — Проверял локальные/S3 настройки и скрывал секреты в toString.
  **Record-поля:** `type`, `localRoot`, `maxUploadBytes`, `endpoint`, `accessKey`, `secretKey`,
  `bucket`.
- **`KnowledgeStorageType`** — Выбирал LOCAL/S3 реализацию. **Методы:** отсутствуют явные
  объявления; используются значения record/enum.
- **`LocalObjectStorage`** — Хранил объекты локально для local/test профиля. Публичные операции:
  `put()`, `get()`, `delete()`, `purgeAllVersionsVerified()`.
- **`ObjectStorage`** — Единый интерфейс операций put/get/delete.
- **`S3ObjectStorage`** — Версионное S3/MinIO хранилище с verified purge всех версий и delete
  markers. Публичные операции: `put()`, `get()`, `delete()`, `purgeAllVersionsVerified()`.
- **`StoredObject`** — Передавал Spring Resource и длину объекта. **Record-поля:** `resource`,
  `contentLength`. **Методы:** отсутствуют явные объявления; используются значения record/enum.
- **`VerifiedObjectLockException`** — Подтверждённая на конкретной версии S3 защита Object Lock /
  legal hold.

### `storage/reconciliation/` — Журнал загрузок и reconciliation (5)

- **`KnowledgeStorageReconciliationConfiguration`** — Включал конфигурацию reconciliation.
  **Методы:** отсутствуют явные объявления; используются значения record/enum.
- **`KnowledgeStorageReconciliationProperties`** — Валидировал настройки reconciliation scheduler.
  **Record-поля:** `enabled`, `pollDelay`.
- **`KnowledgeStorageReconciliationScheduler`** — Карантинировал неоднозначные S3 операции и чистил
  доказанные orphan. Публичные операции: `poll()`.
- **`KnowledgeStorageReviewMetrics`** — Публикует backlog upload-intent NEEDS_REVIEW и отмечает
  ошибки опроса. Публичные операции: `refresh()`.
- **`KnowledgeStorageUploadJournal`** — INTENT/STORED/LINKED и карантин неопределённых исходов
  сетевого PUT. Публичные операции: `countNeedsReview()`, `begin()`, `stored()`, `linked()`.

## Структура тестов

Путь: `backend/src/test/java/ru/safeai/gateway/knowledge/`.

```text
├── chunking/
│   ├── KnowledgeChunkerRegressionTest.java
│   ├── KnowledgeChunkerTest.java
│   └── KnowledgeStructureAwareChunkerTest.java
├── config/
│   ├── KnowledgeConfigurationBoundariesTest.java
│   ├── KnowledgeErasureReleaseInvariantVerifierTest.java
│   ├── KnowledgeIngestionPropertiesConcurrencyTest.java
│   ├── KnowledgeOcrSafetyMarginTest.java
│   └── KnowledgeSecretPropertiesTest.java
├── controller/
│   ├── KnowledgeControllerSecurityTest.java
│   ├── KnowledgeDocumentControllerTest.java
│   ├── KnowledgeDocumentDeletionCapabilityControllerTest.java
│   └── KnowledgeDtoValidationTest.java
├── dto/
│   ├── KnowledgeBaseAccessResponseTest.java
│   └── KnowledgeDocumentResponseJsonTest.java
├── embedding/
│   ├── KnowledgeEmbeddingBoundaryTest.java
│   ├── KnowledgeEmbeddingProductionConditionTest.java
│   ├── KnowledgeEmbeddingProductionInvariantVerifierTest.java
│   ├── OpenAiKnowledgeEmbeddingProvenanceRegressionTest.java
│   ├── OpenAiKnowledgeEmbeddingProviderRegressionTest.java
│   └── OpenAiKnowledgeEmbeddingProviderTest.java
├── evaluation/
│   ├── KnowledgeRetrievalMetricsRegressionTest.java
│   └── KnowledgeRetrievalMetricsTest.java
├── extraction/
│   ├── KnowledgeDocumentExtractorContractTest.java
│   ├── KnowledgeExtractionServiceRegressionTest.java
│   └── PdfKnowledgeExtractorOcrTest.java
├── generation/
│   └── KnowledgeGenerationGcTest.java
├── ingestion/
│   ├── KnowledgeIngestionProcessorRegressionTest.java
│   ├── KnowledgeIngestionQueueIntegrationTest.java
│   └── KnowledgeIngestionSafeLoggingTest.java
├── integration/
│   ├── KnowledgeAccessControlIntegrationTest.java
│   ├── KnowledgeBaseConcurrencyIntegrationTest.java
│   ├── KnowledgeBaseDeletionEndToEndIntegrationTest.java
│   ├── KnowledgeBaseDeletionFoundationIntegrationTest.java
│   ├── KnowledgeBaseDeletionRealJvmCrashIntegrationTest.java
│   ├── KnowledgeBaseDeletionRecoveryConcurrencyIntegrationTest.java
│   ├── KnowledgeBaseObjectLockIntegrationTest.java
│   ├── KnowledgeChunkPublicationIntegrationTest.java
│   ├── KnowledgeDeletionOperatorSafetyIntegrationTest.java
│   ├── KnowledgeDeletionWorkerProcessMain.java
│   ├── KnowledgeDocumentDeletionAcceptanceIntegrationTest.java
│   ├── KnowledgeDocumentDeletionExecutorIntegrationTest.java
│   ├── KnowledgeDocumentDeletionFoundationIntegrationTest.java
│   ├── KnowledgeDocumentVersionConcurrencyIntegrationTest.java
│   ├── KnowledgeErasureEvidenceIntegrationTest.java
│   ├── KnowledgeErasureReceiptV89IT.java
│   ├── KnowledgeErasureTerminalAuditIntegrationTest.java
│   ├── KnowledgeGenerationGcPrivilegesV90IT.java
│   ├── KnowledgeGenerationLifecycleIntegrationTest.java
│   ├── KnowledgeGenerationUpgradeIntegrationTest.java
│   ├── KnowledgeMigrationIntegrityIntegrationTest.java
│   ├── KnowledgePersistenceIntegrationTest.java
│   ├── KnowledgeRagErasureGuardIntegrationTest.java
│   ├── KnowledgeRetentionRecoveryV89IT.java
│   ├── KnowledgeS3EndToEndIntegrationTest.java
│   └── KnowledgeWorkerRowLockPrivilegesV89IntegrationTest.java
├── ocr/
│   └── KnowledgeOcrBoundaryRegressionTest.java
├── rag/
│   ├── AnswerPassportServiceBoundaryTest.java
│   ├── KnowledgeCitationHomoglyphAndMalformedTest.java
│   ├── KnowledgeCitationValidatorRegressionTest.java
│   ├── KnowledgeCitationValidatorTest.java
│   ├── KnowledgeContextAssemblerRegressionTest.java
│   ├── KnowledgeContextAssemblerTest.java
│   ├── KnowledgeRagErasureGuardTest.java
│   ├── KnowledgeRagExpiredLeaseRecoveryPolicyTest.java
│   ├── KnowledgeRagGovernedEnvelopeTest.java
│   ├── KnowledgeRagServiceRegressionTest.java
│   ├── KnowledgeRagV85SchemaIntegrationTest.java
│   └── RagPreparationContractRegressionTest.java
├── retrieval/
│   └── KnowledgeRetrievalRepositoryIntegrationTest.java
├── service/
│   ├── KnowledgeBaseDeletionServiceSafetyTest.java
│   ├── KnowledgeBaseNameNormalizerTest.java
│   ├── KnowledgeBaseSearchPatternTest.java
│   ├── KnowledgeBaseServiceTest.java
│   ├── KnowledgeDocumentDeletionExecutorWiringTest.java
│   ├── KnowledgeDocumentFileValidatorTest.java
│   ├── KnowledgeDocumentNameNormalizerTest.java
│   ├── KnowledgeDocumentServiceTest.java
│   ├── KnowledgeErasureObservabilityTest.java
│   ├── KnowledgeOoxmlUploadLimitRegressionTest.java
│   ├── KnowledgeOperationsServiceRegressionTest.java
│   └── KnowledgeRealFixtureContractTest.java
├── storage/
│   ├── reconciliation/
│   │   ├── KnowledgeStorageReconciliationSchedulerTest.java
│   │   └── KnowledgeStorageReviewMetricsTest.java
│   ├── KnowledgeDocumentErasureRealMinioIntegrationTest.java
│   ├── KnowledgeErasureJournalCompliancePolicyTest.java
│   ├── KnowledgeErasureTombstoneJournalWiringTest.java
│   ├── KnowledgeStorageConfigurationBindingTest.java
│   ├── KnowledgeStorageProductionConditionTest.java
│   ├── KnowledgeStorageProductionInvariantVerifierTest.java
│   ├── KnowledgeStoragePropertiesTest.java
│   ├── LocalObjectStorageFailureRegressionTest.java
│   ├── LocalObjectStorageTest.java
│   ├── S3ObjectStorageIntegrationTest.java
│   ├── S3VerifiedKnowledgeObjectPurgeIntegrationTest.java
│   └── VerifiedKnowledgeObjectPurgeTest.java
└── testsupport/
    ├── KnowledgeErasureTestJournal.java
    └── KnowledgeRegressionFixtures.java
```

## Каталог тестов: зачем каждый нужен

Это дерево соответствует предоставленной структуре тестов, включая V86–V90.
Названия `*IT` и `*IntegrationTest` описывают тестовые файлы, но сами по себе
не доказывают, что запущен Docker или получен зелёный CI.

### `chunking/` (3)

- **`KnowledgeChunkerRegressionTest`** — Проверяет детерминированную нарезку. Примеры проверок:
  `skipsBlankSectionsButNeverEmitsEmptyDocument()`,
  `neverCutsUtf16SurrogatePairAcrossChunkBoundary()`.
- **`KnowledgeChunkerTest`** — Проверяет детерминированную нарезку. Примеры проверок:
  `preservesPageProvenanceAndProducesStableOrdinalsAndHashes()`.
- **`KnowledgeStructureAwareChunkerTest`** — Проверяет детерминированную нарезку. Примеры проверок:
  `markdownHeadingChangesChunkProvenanceWithoutMixingPages()`,
  `paragraphsArePackedWithoutCrossingDistinctMarkdownHeadings()`.

### `config/` (5)

- **`KnowledgeConfigurationBoundariesTest`** — Проверяет границы конфигурации. Примеры проверок:
  `ocrDeadlineCannotExceedExtractionDeadline()`, `embeddingBudgetWithSafetyMarginMustFitLease()`.
- **`KnowledgeErasureReleaseInvariantVerifierTest`** — Проверяет границы конфигурации. Примеры
  проверок: `missingTicketBlocksActivation()`, `disabledDeletionNeedsNoReleaseApproval()`.
- **`KnowledgeIngestionPropertiesConcurrencyTest`** — Проверяет границы конфигурации. Примеры
  проверок: `refusesExcessiveWorkerConcurrency()`, `preservesLegacySingleWorkerDefault()`.
- **`KnowledgeOcrSafetyMarginTest`** — Проверяет границы конфигурации. Примеры проверок:
  `httpOcrMustLeaveTimeForNativePdfExtractionAndResultValidation()`.
- **`KnowledgeSecretPropertiesTest`** — Проверяет границы конфигурации. Примеры проверок:
  `secretBearingPropertiesRedactToString()`.

### `controller/` (4)

- **`KnowledgeControllerSecurityTest`** — Проверяет HTTP контракт и безопасность. Примеры проверок:
  `testSecurityFilterChain()`, `listWithUserRole_returns200()`.
- **`KnowledgeDocumentControllerTest`** — Проверяет HTTP контракт и безопасность. Примеры проверок:
  `list_delegatesPaginationAndPrincipal()`, `uploadVersion_delegatesExistingDocumentAndPrincipal()`.
- **`KnowledgeDocumentDeletionCapabilityControllerTest`** — Проверяет HTTP контракт и безопасность.
  Примеры проверок: `oneFlagIsNotEnough()`, `bothFlagsOnAndOwnerCanDelete()`.
- **`KnowledgeDtoValidationTest`** — Проверяет HTTP контракт и безопасность. Примеры проверок:
  `retrievalRequest_rejectsBlankQuery()`, `retrievalRequest_acceptsInclusiveTopKBoundaries()`.

### `dto/` (2)

- **`KnowledgeBaseAccessResponseTest`** — Проверяет валидацию и JSON-формат. Примеры проверок:
  `administratorReceivesEveryCapability()`,
  `editorCanManageDocumentsButNotAdministrativeSettings()`.
- **`KnowledgeDocumentResponseJsonTest`** — Проверяет валидацию и JSON-формат. Примеры проверок:
  `publishedResponsePreservesUuid()`, `pendingResponseAlwaysContainsNullSearchableVersionId()`.

### `embedding/` (6)

- **`KnowledgeEmbeddingBoundaryTest`** — Проверяет embedding providers и safety. Примеры проверок:
  `pgVectorRejectsNullEmptyAndNonFiniteCoordinates()`,
  `hashingDoesNotReturnNaNForEmptyOrWhitespaceInput()`.
- **`KnowledgeEmbeddingProductionConditionTest`** — Проверяет embedding providers и safety. Примеры
  проверок: `explicitProductionMarkerRejectsHashing()`,
  `canonicalProductionProfilesRejectHashing()`.
- **`KnowledgeEmbeddingProductionInvariantVerifierTest`** — Проверяет embedding providers и safety.
  Примеры проверок: `configuredProductionGradeProviderPasses()`,
  `localHashingCannotStartUnderProductionVerifier()`.
- **`OpenAiKnowledgeEmbeddingProvenanceRegressionTest`** — Проверяет embedding providers и safety.
  Примеры проверок: `exactPhysicalModelIdentityIsAccepted()`,
  `validVectorWithMissingPhysicalModelMustBeRejected()`.
- **`OpenAiKnowledgeEmbeddingProviderRegressionTest`** — Проверяет embedding providers и safety.
  Примеры проверок: `malformedProviderResponseFailsClosed()`,
  `providerHttpFailuresHaveStableRetryability()`.
- **`OpenAiKnowledgeEmbeddingProviderTest`** — Проверяет embedding providers и safety. Примеры
  проверок: `parsesBatchByProviderIndexAndEnforces384Dimensions()`.

### `evaluation/` (2)

- **`KnowledgeRetrievalMetricsRegressionTest`** — Проверяет оценки retrieval. Примеры проверок:
  `emptyRankingIsSafeAndDoesNotInventRecall()`, `emptyGoldSetIsInvalidEvaluationConfiguration()`.
- **`KnowledgeRetrievalMetricsTest`** — Проверяет оценки retrieval. Примеры проверок:
  `computesRecallMrrAndNdcgWithoutDoubleCountingVersion()`.

### `extraction/` (3)

- **`KnowledgeDocumentExtractorContractTest`** — Проверяет парсеры и лимиты. Примеры проверок:
  `rejectsMalformedStructuredDocuments()`, `extractsRealXlsxWithSheetProvenance()`.
- **`KnowledgeExtractionServiceRegressionTest`** — Проверяет парсеры и лимиты. Примеры проверок:
  `boundedTimeoutFailsRetryablyAndCancelsWorker()`,
  `unsupportedMediaTypeFailsBeforeSubmittingWorkerTask()`.
- **`PdfKnowledgeExtractorOcrTest`** — Проверяет парсеры и лимиты. Примеры проверок:
  `usesOcrForScannedPageAndPreservesPageNumber()`.

### `generation/` (1)

- **`KnowledgeGenerationGcTest`** — Проверяет пины retrieval, retention и безопасное удаление старых
  generation.

### `ingestion/` (3)

- **`KnowledgeIngestionProcessorRegressionTest`** — Проверяет ingestion и claim. Примеры проверок:
  `mismatchedActualReadLengthDoesNotReachExtractor()`,
  `invalidImmutableHashMetadataFailsWithoutEmbedding()`.
- **`KnowledgeIngestionQueueIntegrationTest`** — Проверяет claim, lease, retry и безопасную
  обработку ingestion. Примеры проверок: `retryableFailureWaitsUntilNextAttemptAt()`,
  `expiredLeaseCanBeReclaimedAndOldTokenIsFencedOut()`.
- **`KnowledgeIngestionSafeLoggingTest`** — Проверяет ingestion и claim. Примеры проверок:
  `exceptionMessageAndCauseNeverAppearInIngestionLog()`.

### `integration/` (26)

- **`KnowledgeAccessControlIntegrationTest`** — Проверяет ACL, membership и скрытие закрытых баз.
  Примеры проверок: `membersVisibility_viewerCannotUpload_editorCanUpload()`,
  `organizationVisibility_givesImplicitViewerButNotEditor()`.
- **`KnowledgeBaseConcurrencyIntegrationTest`** — Проверяет реальное взаимодействие с БД и потоками.
  Примеры проверок: `concurrentEquivalentNames_produceOneSuccessAndOneConflict()`.
- **`KnowledgeBaseDeletionEndToEndIntegrationTest`** — Проверяет parent/child E2E удаление, карантин
  и сохранность соседних баз. Примеры проверок:
  `retentionBlockedDocumentPreventsParentFinalization()`,
  `failedStoragePurgeDoesNotCompleteAndMayBeSafelyRetried()`.
- **`KnowledgeBaseDeletionFoundationIntegrationTest`** — Проверяет реальное взаимодействие с БД и
  потоками. Примеры проверок: `historicalFksUseImmutableBaseIdentityAndRawDeleteIsRejected()`,
  `parentAcceptanceAtomicallyQueuesAllChildrenAndRejectsNewWrites()`.
- **`KnowledgeBaseDeletionRealJvmCrashIntegrationTest`** — Проверяет восстановление после реального
  падения JVM воркера. Примеры проверок: `InputStreamReader()`,
  `actualJvmKillAfterVerifiedPurgeThenSecondJvmRecovers()`.
- **`KnowledgeBaseDeletionRecoveryConcurrencyIntegrationTest`** — Проверяет параллельный claim и
  восстановление без двойной работы. Примеры проверок:
  `parentCannotFinalizeUsingStolenLeaseToken()`,
  `concurrentWorkersNeverClaimTheSameLiveDocumentLease()`.
- **`KnowledgeBaseObjectLockIntegrationTest`** — Проверяет блокировки retention/legal hold при
  удалении базы. Примеры проверок: `legalHoldMustBlockPhysicalBaseErasure()`,
  `complianceRetentionMustBlockPhysicalBaseErasure()`.
- **`KnowledgeChunkPublicationIntegrationTest`** — Проверяет fenced публикацию chunks и searchable
  generation. Примеры проверок: `staleTokenCannotWriteAnyOrphanChunksOrChangeStatus()`,
  `failingRequiredAuditCannotLeaveVisibleChunksOrReadyJob()`.
- **`KnowledgeDeletionOperatorSafetyIntegrationTest`** — Проверяет реальное взаимодействие с БД и
  потоками. Примеры проверок: `immutableRetentionBlockCannotBeResumed()`,
  `uncertainUploadIntentForbidsManualResume()`.
- **`KnowledgeDeletionWorkerProcessMain`** — Вспомогательный тестовый компонент: общий набор данных,
  setup или отдельный JVM-процесс.
- **`KnowledgeDocumentDeletionAcceptanceIntegrationTest`** — Проверяет реальное взаимодействие с БД
  и потоками. Примеры проверок: `implicitViewerCannotAcceptDeletion()`,
  `refusesCrossTenantAndDoesNotCreateIntent()`.
- **`KnowledgeDocumentDeletionExecutorIntegrationTest`** — Проверяет проверяемое удаление версий и
  fenced SQL transitions. Примеры проверок:
  `ambiguousUploadIntentIsQuarantinedWithoutPurgingAnyObject()`,
  `purgesOriginalAndOnlineMetadataThenKeepsMinimalDeletionEvidence()`.
- **`KnowledgeDocumentDeletionFoundationIntegrationTest`** — Проверяет реальное взаимодействие с БД
  и потоками. Примеры проверок: `deletingDocumentIsFencedAtDatabaseAndHiddenFromLists()`.
- **`KnowledgeDocumentVersionConcurrencyIntegrationTest`** — Проверяет реальное взаимодействие с БД
  и потоками. Примеры проверок: `twoConcurrentVersionUploads_receiveDistinctSequentialNumbers()`.
- **`KnowledgeErasureEvidenceIntegrationTest`** — Проверяет V80 неизменяемое provenance после
  удаления контента. Примеры проверок: `versionEvidenceSurvivesAndIsImmutable()`,
  `historicalForeignKeysPointToImmutableIdentityNotOriginalContent()`.
- **`KnowledgeErasureReceiptV89IT`** — Проверяет V89 DB receipt, аудит и устойчивую доставку после
  commit. Примеры проверок: `journalFailureKeepsReceiptDurableForRetry()`,
  `workerCannotForgeAReceiptForAnUnknownErasureOperation()`.
- **`KnowledgeErasureTerminalAuditIntegrationTest`** — Проверяет атомарность operation и required
  outbox. Примеры проверок: `rolledBackStateChangeRollsBackRequiredAuditIntent()`,
  `verifiedRetentionBlockIsAuditedAndRemainsImmutable()`.
- **`KnowledgeGenerationGcPrivilegesV90IT`** — Проверяет отсутствие прямого DELETE у app и
  ограниченную GC-функцию. Примеры проверок:
  `applicationHasNoDirectDeleteAndCannotPurgeAnUnknownGeneration()`.
- **`KnowledgeGenerationLifecycleIntegrationTest`** — Проверяет атомарную публикацию и очистку
  поколений индекса. Примеры проверок: `incompleteGcRollsBackChunkDeletion()`,
  `retentionAndKeepLatestProtectOldGeneration()`.
- **`KnowledgeGenerationUpgradeIntegrationTest`** — Проверяет совместимость поколений и миграций
  старого состояния. Примеры проверок: `PostgreSQLContainer()`,
  `upgradesPopulatedV56AndPreservesLegacyZeroGenerationPerVersion()`.
- **`KnowledgeMigrationIntegrityIntegrationTest`** — Проверяет реальное взаимодействие с БД и
  потоками. Примеры проверок: `versionIdentityConstraintIsValidated()`,
  `ragProvenanceConstraintsArePresentAndValidated()`.
- **`KnowledgePersistenceIntegrationTest`** — Проверяет реальное взаимодействие с БД и потоками.
  Примеры проверок: `persistedVersionRow_isImmutableAtDatabaseLevel()`,
  `duplicateDocumentName_isRejectedCaseInsensitively()`.
- **`KnowledgeRagErasureGuardIntegrationTest`** — Проверяет RAG permits и гонку с удалением
  источника. Примеры проверок: `emptyRetrievalStillHoldsBaseScopedAttemptLease()`,
  `newPermitIsDeniedAfterDocumentDeletionAccepted()`.
- **`KnowledgeRetentionRecoveryV89IT`** — Проверяет dual-control review, lease и неотменяемый Object
  Lock. Примеры проверок: `oneOperatorCannotResumeBlockedBaseAndSecondIndependentReviewerCan()`.
- **`KnowledgeS3EndToEndIntegrationTest`** — Проверяет настоящий S3/MinIO upload, download и version
  lifecycle. Примеры проверок:
  `transactionRollback_keepsConfirmedS3ObjectUntilFencedReconciliation()`,
  `uploadMetadataAndMinioObject_areConsistentAndDownloadRoundTripsBytes()`.
- **`KnowledgeWorkerRowLockPrivilegesV89IntegrationTest`** — Проверяет реальные SQL row-lock права
  отдельного worker. Примеры проверок:
  `workerCanAcquireV82BaseRowLocksButCannotChangeBusinessFields()`.

### `ocr/` (1)

- **`KnowledgeOcrBoundaryRegressionTest`** — Проверяет строгий outbound OCR endpoint и ограничение
  ответа. Примеры проверок: `providerMustRejectEmptyPdfBeforeNetwork()`,
  `disabledProviderDoesNotClaimOcrCapability()`.

### `rag/` (12)

- **`AnswerPassportServiceBoundaryTest`** — Проверяет RAG contracts. Примеры проверок:
  `deletingBaseRejectsPassportBeforeAnyWrite()`,
  `invalidCitationSetCannotBePersistedEvenWithKnownSources()`.
- **`KnowledgeCitationHomoglyphAndMalformedTest`** — Проверяет отказ от поддельных и Unicode-похожих
  цитат. Примеры проверок: `knowledgeOnlyAbstainsForMalformedOrHomoglyphCitation()`,
  `assistedModeCannotRetainAnyAuthoritativeLookingMarkerAfterInvalidity()`.
- **`KnowledgeCitationValidatorRegressionTest`** — Проверяет RAG contracts. Примеры проверок:
  `mixingValidAndInvalidCitationMustNeverPersistPartialEvidence()`,
  `noCitationsInKnowledgeOnlyMustAbstainWithoutFalselyInvalidatingSyntax()`.
- **`KnowledgeCitationValidatorTest`** — Проверяет RAG contracts. Примеры проверок:
  `knowledgeOnlyFailsClosedForInventedCitation()`,
  `assistedModeRemovesInventedMarkerWithoutClaimingEvidence()`.
- **`KnowledgeContextAssemblerRegressionTest`** — Проверяет RAG contracts. Примеры проверок:
  `emptyFirstHitDoesNotSuppressNextRankedHit()`, `generalModeCannotMaterializeKnowledgeContext()`.
- **`KnowledgeContextAssemblerTest`** — Проверяет RAG contracts. Примеры проверок:
  `buildsBoundedUntrustedContextWithStableLabels()`.
- **`KnowledgeRagErasureGuardTest`** — Проверяет RAG contracts. Примеры проверок:
  `generalModeDoesNotQueryKnowledge()`, `anotherVersionCannotAuthorizeStaleContext()`.
- **`KnowledgeRagExpiredLeaseRecoveryPolicyTest`** — Проверяет RAG contracts. Примеры проверок:
  `unknownOrMalformedEvidenceNeverUnblocksErasure()`,
  `verifiesEveryKnownOutcomeCertaintyAndFinishCombination()`.
- **`KnowledgeRagGovernedEnvelopeTest`** — Проверяет неизменность подготовленного модельного
  запроса. Примеры проверок:
  `sealedGovernedKnowledgeTurnCannotEnterRetrievalWithoutItsReservation()`.
- **`KnowledgeRagServiceRegressionTest`** — Проверяет RAG contracts. Примеры проверок:
  `generalModeNeverCallsRetrievalOrMaterializesKnowledgeContext()`,
  `completionDelegatesToCitationValidationWithoutRewritingItsEvidence()`.
- **`KnowledgeRagV85SchemaIntegrationTest`** — Проверяет RAG contracts. Примеры проверок:
  `allV85RagTablesAndRequiredColumnsExist()`, `immutableEvidenceTriggersAreInstalledAndEnabled()`.
- **`RagPreparationContractRegressionTest`** — Проверяет RAG contracts. Примеры проверок:
  `preparationCopiesSourcesAndRejectsLaterMutation()`,
  `knowledgeEnabledRequiresRunIdentityAndValidContentHash()`.

### `retrieval/` (1)

- **`KnowledgeRetrievalRepositoryIntegrationTest`** — Проверяет tenant-scoped гибридный retrieval и
  историю hits. Примеры проверок:
  `hybridSearchUsesPublishedSearchableVersionAndEnforcesMembershipInSql()`.

### `service/` (12)

- **`KnowledgeBaseDeletionServiceSafetyTest`** — Проверяет бизнес-сценарии. Примеры проверок:
  `disabledFeatureCannotEvenEnqueueDeletion()`.
- **`KnowledgeBaseNameNormalizerTest`** — Проверяет бизнес-сценарии. Примеры проверок:
  `normalize_rejectsNull()`, `normalize_rejectsBlank()`.
- **`KnowledgeBaseSearchPatternTest`** — Проверяет бизнес-сценарии. Примеры проверок:
  `unicodeSearchIsAccepted()`, `rejectsControlAndMalformedUnicode()`.
- **`KnowledgeBaseServiceTest`** — Проверяет бизнес-сценарии. Примеры проверок:
  `addMember_rejectsDisabledUser()`, `update_rejectsStaleExpectedVersion()`.
- **`KnowledgeDocumentDeletionExecutorWiringTest`** — Проверяет бизнес-сценарии. Примеры проверок:
  `explicitJournalIsRequiredByTheDeletionGuard()`,
  `criticalInfrastructureDependenciesCannotBeNull()`.
- **`KnowledgeDocumentFileValidatorTest`** — Проверяет бизнес-сценарии. Примеры проверок:
  `validate_acceptsMarkdownCsvJsonAndXml()`, `validate_rejectsXmlDoctypeAndEntities()`.
- **`KnowledgeDocumentNameNormalizerTest`** — Проверяет бизнес-сценарии. Примеры проверок:
  `normalize_rejectsNull()`, `normalize_rejectsBlank()`.
- **`KnowledgeDocumentServiceTest`** — Проверяет бизнес-сценарии. Примеры проверок:
  `uploadNewStopsWhenStorageFails()`, `organizationViewerCannotUploadDocument()`.
- **`KnowledgeErasureObservabilityTest`** — Проверяет возраст операций, отсутствие ID-меток, stale
  metrics. Примеры проверок: `failedRefreshPreservesLastKnownValuesAndSignalsFailure()`,
  `exportsReadOnlyLowCardinalityMetricsWithoutOperationIdentifiers()`.
- **`KnowledgeOoxmlUploadLimitRegressionTest`** — Проверяет бизнес-сценарии. Примеры проверок:
  `smallValidDocxIsRecognizedByTheSharedBoundedScanner()`,
  `tinyCompressedInputWithExcessiveInflationIsNeverAcceptedAtUpload()`.
- **`KnowledgeOperationsServiceRegressionTest`** — Проверяет бизнес-сценарии. Примеры проверок:
  `lostUpdateCannotClaimSuccessfulReindex()`, `activeIngestionCannotBeResetByConcurrentReindex()`.
- **`KnowledgeRealFixtureContractTest`** — Проверяет бизнес-сценарии. Примеры проверок:
  `productionLikeNegativeFixtures_areRejected()`,
  `productionLikeFixtures_areRecognizedByActualBytes()`.

### `storage/` (12)

- **`KnowledgeDocumentErasureRealMinioIntegrationTest`** — Проверяет удаление всех версий на
  реальном MinIO. Примеры проверок:
  `acceptedDocumentIsPhysicallyErasedAndAuditedWithoutAffectingNeighbor()`.
- **`KnowledgeErasureJournalCompliancePolicyTest`** — Проверяет файловое хранилище и purge. Примеры
  проверок: `rejectsMissingRetentionInformation()`, `rejectsExpiredOrInsufficientRetention()`.
- **`KnowledgeErasureTombstoneJournalWiringTest`** — Проверяет файловое хранилище и purge. Примеры
  проверок: `disabledJournalRejectsEveryPublicationType()`,
  `explicitIso8601RetentionIsConvertedBySpringBoot()`.
- **`KnowledgeStorageConfigurationBindingTest`** — Проверяет файловое хранилище и purge. Примеры
  проверок: `missingS3SecretFailsContextStartup()`, `uploadLimitBelowMinimumFailsContextStartup()`.
- **`KnowledgeStorageProductionConditionTest`** — Проверяет файловое хранилище и purge. Примеры
  проверок: `explicitProductionMarkerRejectsLocalStorage()`,
  `canonicalProductionProfilesRejectLocalStorage()`.
- **`KnowledgeStorageProductionInvariantVerifierTest`** — Проверяет файловое хранилище и purge.
  Примеры проверок: `productionAcceptsHttpsS3()`, `productionRejectsLocalAndInsecureS3()`.
- **`KnowledgeStoragePropertiesTest`** — Проверяет файловое хранилище и purge. Примеры проверок:
  `constructor_rejectsZeroUploadLimit()`, `constructor_rejectsBlankS3Endpoint()`.
- **`LocalObjectStorageFailureRegressionTest`** — Проверяет файловое хранилище и purge. Примеры
  проверок: `malformedKeysCannotEscapeLocalStorageRoot()`,
  `missingKeyCannotBeConfusedWithZeroLengthObject()`.
- **`LocalObjectStorageTest`** — Проверяет файловое хранилище и purge. Примеры проверок:
  `putGetDelete_roundTripsBytes()`, `put_overwritesExistingObject()`.
- **`S3ObjectStorageIntegrationTest`** — Проверяет файловое хранилище и purge. Примеры проверок:
  `missingBucket_failsFast()`, `missingObject_isMappedToNoSuchFileException()`.
- **`S3VerifiedKnowledgeObjectPurgeIntegrationTest`** — Проверяет файловое хранилище и purge.
  Примеры проверок: `removesAllVersionsAndMarkersWithoutTouchingNeighboringPrefix()`.
- **`VerifiedKnowledgeObjectPurgeTest`** — Проверяет файловое хранилище и purge. Примеры проверок:
  `localPurgeCannotFollowSymlinkedDirectory()`,
  `localPhysicalPurgeIsIdempotentAndCannotEscapeRoot()`.

### `storage/reconciliation/` (2)

- **`KnowledgeStorageReconciliationSchedulerTest`** — Проверяет обработку orphan без неверного
  удаления при неизвестном исходе. Примеры проверок: `neverDeletesAnUnconfirmedIntention()`,
  `deletesOnlyClaimedStoredOrphanThenRecordsCompletion()`.
- **`KnowledgeStorageReviewMetricsTest`** — Проверяет reconciliation и карантин. Примеры проверок:
  `reportsPendingReviewAndNeverHidesDatabaseFailureAsZero()`.

### `testsupport/` (2)

- **`KnowledgeErasureTestJournal`** — Вспомогательный тестовый компонент: общий набор данных, setup
  или отдельный JVM-процесс.
- **`KnowledgeRegressionFixtures`** — Вспомогательный тестовый компонент: общий набор данных, setup
  или отдельный JVM-процесс.


## Запуск и критерии готовности

```bash
cd backend
./mvnw -Dtest=KnowledgeBaseServiceTest,KnowledgeRagServiceRegressionTest test
./mvnw -Dtest=KnowledgeGenerationGcPrivilegesV90IT test
./mvnw -Dtest=KnowledgeErasureReceiptV89IT test
./mvnw -Dtest=KnowledgeRetentionRecoveryV89IT test
./mvnw -Dtest=KnowledgeBaseDeletionEndToEndIntegrationTest test
./mvnw -Dtest=FlywaySequentialUpgradeIT test
./mvnw clean verify
```

Для PostgreSQL/MinIO интеграций нужен Docker и **одноразовое** тестовое
хранилище. `-Dtest` запускает выбранные классы и не является заменой
полного CI. Отдельно проверять runbooks и S3 feature compatibility.

### Release gate

1. `Flyway validate`, чистая БД и upgrade **заполненной** копии до V90.
2. Проверка реальных SQL privileges `safeai_app`, worker и operator.
3. Изоляция tenant, MEMBERS/OWNER ACL, revoked role и stale principal.
4. Upload crash/ambiguous PUT, orphan cleanup, S3 403/timeout/version race.
5. Real MinIO версионный bucket: все versions, markers, соседний prefix.
6. Object Lock governance/compliance/legal hold: только безопасные исходы.
7. V84/V89: terminal audit/commit receipt, crash, повтор delivery, backfill.
8. V90: direct DELETE запрещён, GC только `COLLECTING` с V57 trigger.
9. RAG permit/lease, `AnswerPassport`, запрещённый late chunk write.
10. Отдельная оценка sandbox, нагрузки, P95/P99 retrieval, privacy и backups.
11. Destructive flags **OFF** до утверждённого security/release review.

## Риски и границы гарантии

- `READY` означает индексированную допустимую версию, а не истину LLM.
- Корректная citation syntax не доказывает semantic faithfulness.
- OCR/PDF/OOXML ограничители не заменяют OS-level parser isolation.
- Провайдер S3 может иметь иные semantics, чем тестовый MinIO.
- S3 `DELETE` без полного version enumeration не является доказательством.
- Сохранённая identity или ledger не должна удаляться для «красивого» status.
- Невозможно делать атомарный DB commit и HTTP PUT без durable reconciliation.
- Роли runtime `safeai_app` и worker должны быть физически разъединены.
- Отказ publisher не меняет факта предыдущего DB commit, требует retry.
- `BLOCKED_BY_RETENTION` не обходится веб-ролью ADMIN или прямым SQL UPDATE.

**Связанные каталоги:** [Chat](../chat/README.md),
[SQL миграции](../../../../../resources/db/migration),
[Knowledge тесты](../../../../../../test/java/ru/safeai/gateway/knowledge).
