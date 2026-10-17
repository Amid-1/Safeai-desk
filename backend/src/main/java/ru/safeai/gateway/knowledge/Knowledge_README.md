<!-- SafeAI-Desk/backend/src/main/java/ru/safeai/gateway/knowledge/README.md -->
# Knowledge — устройство модуля, контракт и технический аудит

**Срез:** 17 октября 2026 года. **Основа:** 16 архивов и 2 Java-файла,
предоставленные вместе с запросом. **Исходный код не изменялся.**

> Документ описывает переданный исходный код, а не подтверждает успешный rollout.
> PostgreSQL, S3/MinIO, внешний OCR и OpenAI не запускались в рамках этого аудита.
> Полный Maven и интеграционные тесты отсутствуют в предоставленном наборе.

**Навигация:** назначение → сценарии → инварианты → достоинства →
риски P1/P2/P3 → тест-план → подробный справочник каждого файла и метода.

## 1. Область проверки и границы выводов

- Разобраны все 144 уникальных Java-файла из 18 переданных файлов.
- Синтаксически разобраны 165 типов (включая nested record) и 578 методов
  и конструкторов, явно объявленных в исходном Java-коде.
- Дубли служебных `__MACOSX/._*` игнорировались: это не Java-исходники.
- Номера строк ниже относятся к приложенным файлам, а не обязательно к `main`.
- Сгенерированные record-accessor методы и методы Lombok не перечислены:
  их нет как явных объявлений в переданных исходниках.
- Зависимости из других модулей (`common`, `chat`, `ai`, `audit`) рассмотрены
  по видимым вызовам, но их полный код в эти архивы не входит.

## 2. Назначение, функциональность и границы

Knowledge — multi-tenant подсистема хранения и поиска корпоративных знаний.
Она принимает версии документов, проверяет физические файлы, извлекает текст,
разбивает его на chunks, создаёт 384-мерные векторы, публикует поколения индекса,
проводит гибридный поиск и подготавливает подтверждаемые RAG-ответы.

Области ответственности:

- **Control/API:** управление базами знаний, владельцами и участниками;
  загрузка документов, редактирование, версии, скачивание и health.
- **Data plane:** byte validation, local/S3 storage, ingestion jobs, pgvector,
  lexical search, RRF ranking и журнал retrieval evidence.
- **RAG:** собственная policy untrusted sources, ограничения input envelope,
  ссылки `[C1]`–`[C999]`, контекстный SHA-256 и Answer Passport.
- **Эксплуатация:** retry/lease-fencing, публикация READY, reconciliation
  orphan-объектов, garbage collection старых поколений и evaluation.

Не является частью этого кода: полноценное LLM-провайдерное исполнение,
авторизация Chat-приложения, миграции Flyway и настройка Alertmanager.

## 3. Основные сценарии

### 3.1. Загрузка и индексация

1. REST получает principal и требует роль как минимум `EDITOR` в Knowledge Base.
2. `KnowledgeDocumentFileValidator` проверяет имя, extension, байты и MIME.
3. `KnowledgeDocumentService` резервирует ID и storage key, начинает S3 intent.
4. Физический `ObjectStorage.put()` происходит вне DB metadata transaction.
5. `KnowledgeDocumentWriteService` в одной транзакции создаёт версию, job,
   binding upload journal и обязательное событие Audit.
6. Scheduler захватывает PENDING/expired job через `FOR UPDATE SKIP LOCKED`.
7. Processor сверяет размер и SHA-256, затем выполняет extract/chunk/embed.
8. `KnowledgeChunkPersistenceService` сохраняет chunks и READY, с аудитом.
9. Новая поисковая версия/поколение участвует в запросах при сохранённом ACL.

### 3.2. Поиск

1. `KnowledgeAccessService.requireAccess()` проверяет organization/visibility.
2. Запрос нормализуется, получается query embedding.
3. В PostgreSQL закрепляются active generations, фильтруются tenant и права.
4. Выполняются lexical candidates и cosine/vector candidates.
5. Reciprocal Rank Fusion (RRF) сливает два рейтинга и выбирает top-k.
6. В транзакции сохраняются retrieval run, hits и обязательный audit.

### 3.3. RAG и Answer Passport

1. `KnowledgeRagService.prepare()` проверяет identity и route input envelope.
2. Выполняется retrieval; `KnowledgeContextAssembler` ограничивает источники.
3. Сохраняется hash prepared request в `KnowledgeRagContextReceiptService`.
4. После ответа модели `KnowledgeCitationValidator` проверяет маркеры.
5. В KNOWLEDGE_ONLY при отсутствии валидных цитат применяется abstention.
6. `AnswerPassportService.persist()` записывает citations вместе с Chat-turn.

### 3.4. Сбои и восстановление

- Lease, processing token, expected status и попытки ограничивают stale worker.
- Retryable ошибки возвращают job в PENDING; исчерпание переводит в FAILED.
- Неподтверждённый S3 PUT не считается безопасным для автоматического DELETE.
- Upload intents переходят INTENT → STORED → LINKED либо NEEDS_REVIEW/CLEANED.
- GC сохраняет поколения, на которые есть ссылки в retrieval evidence.

## 4. Защитные и бизнес-инварианты

- Tenant isolation применяется в ACL и основных SQL-предикатах organization ID.
- Для MEMBERS БЗ пользователь без членства получает not-found semantics.
- Исторические версии документа и связанные chunks сохраняют provenance.
- Смена поискового поколения не переписывает ранее созданные retrieval hits.
- Чтение с S3 и ingestion различают not-found, timeout и hash mismatch.
- Для production проверяется S3 over HTTPS и реальный embedding provider.
- Запросы embeddings валидируются по модели, размерности, index, finite norm.
- Подозрительные OCR/PDF входные данные не публикуются как полный индекс.
- Audit write является обязательным при публикации metadata/READY/retrieval.
- Наличие SHA-256 подтверждает побайтовую целостность, но не истинность фактов.

## 5. Что уже реализовано хорошо

- Разделены контроллеры, сервисы, репозитории, провайдеры и DTO.
- PostgreSQL и аудит закрепляют изменения, требующие транзакционной целостности.
- У ingestion имеется владение задачей, конкуренция через SKIP LOCKED и retry.
- Extractor pool ограничен по числу активных и ожидающих задач.
- Набор поддерживаемых форматов не строится исключительно по имени файла.
- PDF OCR требует покрыть все страницы, действительно нуждающиеся в OCR.
- OpenAI embedding API не принимается без полной проверки ответа.
- В retrieval исходно проверяются и tenant, и права пользователя.
- RAG ограничивает materialized input и отсекает неподтверждённые цитаты.
- S3 reconciliation не удаляет результат неоднозначного PUT вслепую.
- Для каждого retrieval сохраняется идентичность результата и score-evidence.

## 6. Замечания и технические риски

Приоритеты: **P1** — устранить до работы с чувствительными документами;
**P2** — включить в ближайший цикл hardening и нагрузочного тестирования;
**P3** — удобство сопровождения и дополнительных гарантий.

### P1-K1. Полный stack trace в ingestion-логах

**Подтверждено исходником.** `ingestion/KnowledgeIngestionProcessor.java:176`
вызывает `log.error(..., exception)` для неожиданного RuntimeException.
Причина может содержать текст документа, URL с параметрами, ответы OCR или
данные провайдера из вложенных exceptions. Само наличие утечки не доказано,
но поверхность чувствительного логирования есть.

**Исправление:** логировать jobId и безопасную категорию сбоя, отдельно
собирать конфиденциальную диагностику в защищённом канале без raw payload.
Тестом проверить отсутствие document text, S3 key, Authorization и API token.

### P2-K2. Длинные provider calls могут пережить lease

**Подтверждено расположением renewals.** `KnowledgeIngestionProcessor`
продлевает lease перед/после embedding batch, но не в течение самого вызова.
Также OCR и тяжёлое извлечение не имеют отдельного heartbeat в processor.
Если latency превысит lease, задача может быть re-claimed другой репликой.
Fencing защищает публикацию, но возможны повторные платные API-вызовы.

**Исправление:** ограничить время каждой стадии относительно lease или добавить
контролируемый heartbeat с токеном; тестировать pause/timeout/failover.

### P2-K3. Ingestion scheduler обрабатывает batch последовательно

**Подтверждено.** `ingestion/KnowledgeIngestionScheduler.java:68–91`
вызывает `processor.process()` внутри цикла. `batchSize=4` не означает
четыре одновременно работающих задачи. Один медленный PDF/OCR/embedding
задержит последующие задачи в этой реплике.

**Исправление:** измерить backlog и tail latency, затем вводить управляемую
конкурентность с ограничением внешних provider requests и JDBC connections.

### P2-K4. В retrieval сохраняется исходный текст запроса

**Подтверждено.** `service/KnowledgeRetrievalService.java:309–332`
пишет `query_text` вместе с `query_sha256`. Поле может содержать PII,
коммерческую тайну и фрагменты запроса пользователя. Сам хеш этого не
устраняет. Политика retention/шифрования этих строк не видна в данном наборе.

**Исправление:** документировать lawful purpose, TTL/retention, доступ и
маскирование; оценить хранение только хеша там, где raw query не требуется.

### P2-K5. Дорогой lexical path в PostgreSQL

**Потенциальный риск производительности.** `KnowledgeRetrievalRepository`
вычисляет `to_tsvector('russian', allowed.content)` во время поиска.
На большом числе chunks это может дорого обходиться без подходящего
выражения/предрасчитанного индекса. Это не доказательство плохого plan.

**Исправление:** выполнить `EXPLAIN (ANALYZE, BUFFERS)` на рабочих данных,
сверить индексы, ограничение кандидатов и использование pgvector.

### P2-K6. Timeout extraction — не изоляция от злоумышленных файлов

**Подтверждено.** `KnowledgeExtractionService.cancelAndRemove()`
использует `Future.cancel(true)`. Парсер может игнорировать interrupt
и продолжить потреблять CPU/memory. Комментарий в классе это признаёт.

**Исправление:** для недоверенных файлов применять отдельный worker/container
с memory/CPU/time лимитами, собственным lifecycle и kill после timeout.

### P2-K7. Повышенная память при параллельных больших загрузках

**Условный ресурсный риск.** `KnowledgeDocumentFileValidator` читает upload
полностью в `byte[]`; ещё есть копии, parser data, OCR base64 и embedding
batch. Даже если один файл ограничен по размеру, параллельность может
вызвать большой пик heap. Для 25–100 MB лимитов требуется load test.

**Исправление:** измерить heap peaks, задать предел одновременной загрузки,
ограничение размерности распаковки и документировать capacity envelope.

### P2-K8. Ручной процесс NEEDS_REVIEW необходимо операционализировать

**Подтверждено кодом.** S3 reconciliation намеренно переводит неоднозначные
операции в `NEEDS_REVIEW`, вместо опасного auto-retry/delete.
В предоставленном `KnowledgeStorageReconciliationScheduler` есть логи,
но не обнаружены метрики backlog NEEDS_REVIEW или alert rules.

**Исправление:** экспортировать gauge backlog/oldest age и alert,
записать runbook для подтверждения объекта и безопасного ручного решения.

### P2-K9. Проверка цитат не равна проверке истинности утверждений

**Функциональное ограничение.** `KnowledgeCitationValidator` проверяет
канонический синтаксис и связь `[C#]` с разрешённым hit, но не проверяет,
действительно ли текст источника подтверждает утверждение модели.

**Исправление:** добавить offline evaluation на groundedness/entailment и
ручной аудит регрессий, отдельно измерять ложные корректные цитаты.

### P2-K10. Retrieval и RAG receipt живут в разных транзакциях

**Подтверждено.** `KnowledgeRetrievalService` сохраняет run/hits/audit
в собственной транзакции, `KnowledgeRagContextReceiptService.record()`
использует `REQUIRES_NEW`, а паспорт ответа сохраняется при финализации.
При сбое LLM/Chat эти evidence-записи могут остаться без completed answer.

**Это может быть правильным evidence-дизайном**, но ему требуются
retention, orphan-state monitoring и связи между receipt/retrieval/turn.

### P2-K11. Изменение индекса требует контроля GC и locking

**Потенциальный нагрузочный риск.** `KnowledgeGenerationGc.collect()`
использует bounded batch и `SKIP LOCKED`, но выполняет много
соединений/подзапросов на поколения и связи с `knowledge_retrieval_hits`.
Дороговизна зависит от реальных индексов, количества поколений и data skew.

**Исправление:** EXPLAIN на production-like данных + параллельный
load test с retrieval, reindex и GC под мониторингом Hikari.

### P2-K12. Download выполняется с best-effort аудитом

**Подтверждено.** `service/KnowledgeDocumentService.java:443–459`
использует `audit.tryRecord()` при выдаче документа. Если audit storage
недоступен, скачивание может продолжиться без надёжного audit event.
Это осознанный trade-off, а не автоматически уязвимость. Если обязательна
полнота журнала чтений конфиденциальных файлов, потребуется fail-closed
контракт и отдельный UX/доступность тест; менять поведение без требований нельзя.

### P3-K13. Нет защитной проверки null в evaluation metrics

**Подтверждено.** `KnowledgeRetrievalMetrics.evaluate()` проверяет
пустоту expectedSet, но не проверяет null для обоих аргументов.
Из API они обычно формируются из уже валидированных DTO.
Отдельный unit-test контракт лучше сделать явным.

### P3-K14. Базовый AccessService проверяет только ROLE_ADMIN

**Требует сверки с проектным контрактом ролей.** `KnowledgeAccessService`
проверяет `ROLE_ADMIN`, но не `ROLE_SUPER_ADMIN`. Это может быть осознанным
запретом глобальному администратору читать tenant knowledge. Не добавляйте
суперправа без доказанного требования: это ухудшило бы tenant isolation.

## 7. Рекомендации по регрессионным тестам

- **ACL:** ORGANIZATION vs MEMBERS, viewer/editor/owner, disabled KB/document.
- **Tenant:** одинаковые IDs под разными организациями, запрещённый download.
- **Storage:** ambiguous S3 PUT, journal crash, orphan cleanup fencing, 404/503.
- **Upload:** MIME mismatch, polyglot/zip bomb, UTF-8/OOXML/XML безопасность.
- **PDF:** native/scanned/mixed страницы, неполный OCR, некорректные page IDs.
- **Lease:** re-claim, выбывший worker, двойная публикация, поздний commit.
- **Embeddings:** model mismatch, переставленные индексы, NaN, zero vector.
- **Retrieval:** ACL revoke во время запроса, vector-only/lexical-only, RRF.
- **RAG:** bogus citations, homoglyphs, sources as prompt injection, abstention.
- **Passport:** сбой сохранения citations должен откатывать finalization.
- **GC:** retained evidence не теряется при конкурентных retrieval и reindex.
- **Load:** 100 MB файлы, OCR latency, тысячи chunks, Hikari pool exhaustion.
- **Observability:** запрет raw document и токенов в exception logs.

## 8. Конфигурация и эксплуатационный чеклист

- `safeai.knowledge.storage.type=s3` в production; endpoint только HTTPS.
- `safeai.knowledge.embedding.provider=openai` в production.
- OCR включать осознанно; при disabled scanned PDF не должен стать READY.
- Размерности embeddings согласованы с pgvector: 384.
- Управлять max-upload-bytes, max-extracted-chars, parser threads и timeout.
- Следить за статусами ingestion, возрастом lease, retries и backlog FAILED.
- Следить за NEEDS_REVIEW journal, stale CLEANING, retired generations.
- Развести Secrets и диагностические логи; хранить private key вне Git.
- Проверять применённые миграции Knowledge и фактические индексы отдельно.
- Alertmanager, retention и внешний OCR требуют deployment-конфигурации.

## 9. Справочник: каждый класс и явно объявленный метод

В скобках у метода указан номер строки в переданном Java-файле.
Методы внутри вложенных `record` также учитываются. Сокращение `N арг.`
использовано только для очень длинных сигнатур; их тело не исключалось.

## 9.1. `chunking`

Структурное разбиение текста в ограниченные chunks.

### `KnowledgeChunkCandidate`

**Файл:** `chunking/KnowledgeChunkCandidate.java`.

Immutable chunk: текст, порядковый номер, источник и хеш фрагмента.
**Record-поля:** `ordinal`, `content`, `contentSha256`, `estimatedTokens`, `pageFrom`, `pageTo`,
`heading`.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

### `KnowledgeChunker`

**Файл:** `chunking/KnowledgeChunker.java`.

Разбивал извлечённые секции на перекрывающиеся сегменты с границами предложений.

**Методы/конструкторы (включая private и nested):**

- `конструктор(KnowledgeIngestionProperties)` (L32): Инициализировал зависимости и проверял
  параметры создания.
- `chunk(ExtractedDocument)` (L49): Делил секции на ограниченные куски с перекрытием и provenance.
- `structuralUnits(String, String)` (L99): Выделял структурные единицы (абзацы/предложения) из
  секции.
- `emit(4 арг.)` (L139): Добавлял сформированный сегмент и его метаданные в итоговый список.
- `chooseEnd(String, int)` (L177): Выбирал правую границу chunk с учётом разделителей и размера.
- `addClampedToLength(String, int, int)` (L245): Добавлял «clamped to length» в реализации
  KnowledgeChunker.
- `findBoundaryBackward(String, int, int, boolean)` (L262): Искал подходящую границу
  предложения/слова в обратном направлении.
- `isSentenceBoundary(int)` (L297): Определял «sentence boundary» в реализации KnowledgeChunker.
- `isWhitespace(int)` (L306): Определял «whitespace» в реализации KnowledgeChunker.
- `skipLeadingWhitespace(String, int)` (L317): Пропускал ведущие пробельные символы перед сегментом.
- `toCodePointBoundary(String, int)` (L345): Корректировал индекс так, чтобы не разорвать Unicode
  code point.
- `normalize(String)` (L374): Канонизировал входное значение по правилам конкретного компонента.
- `truncateHeading(String)` (L410): Реализовывал шаг «truncate heading» функции KnowledgeChunker.
- `estimateTokens(String)` (L440): Оценивал объём chunk в токенах для bounded indexing.
- `sha256(String)` (L455): Вычислял SHA-256 от содержимого для проверки целостности.

## 9.2. `config`

Конфигурационные инварианты и лимиты валидации при старте.

### `KnowledgeEmbeddingConfigurationInvariantVerifier`

**Файл:** `config/KnowledgeEmbeddingConfigurationInvariantVerifier.java`.

Проверял допустимость embedding-конфигурации при старте.

**Методы/конструкторы (включая private и nested):**

- `конструктор(2 арг.)` (L18): Инициализировал зависимости и проверял параметры создания.
- `verify()` (L32): Проверял конфигурационные инварианты при создании компонента.

### `KnowledgeEmbeddingProperties`

**Файл:** `config/KnowledgeEmbeddingProperties.java`.

Задавал провайдера, модель, размерность, batch и транспортные лимиты.
**Record-поля:** `provider`, `baseUrl`, `apiKey`, `model`, `dimensions`, `batchSize`,
`maxInputChars`, `connectTimeout`, `readTimeout`.

**Методы/конструкторы (включая private и nested):**

- `конструктор(9 арг.)` (L42): Инициализировал зависимости и проверял параметры создания.
- `toString()` (L179): Формировал представление без раскрытия секретов там, где это предусмотрено.
- `parseUri(String)` (L195): Разбирал «uri» в реализации KnowledgeEmbeddingProperties.
- `bounded(Integer, int, int, int, String)` (L211): Реализовывал шаг «bounded» функции
  KnowledgeEmbeddingProperties.
- `positive(Duration, Duration, String)` (L233): Реализовывал шаг «positive» функции
  KnowledgeEmbeddingProperties.
- `invalid(String)` (L253): Формировал исключение для некорректного параметра/ответа.
- `конструктор()` (L268): Инициализировал зависимости и проверял параметры создания.

### `KnowledgeIngestionProperties`

**Файл:** `config/KnowledgeIngestionProperties.java`.

Задавал очередь, lease, retries, backoff, парсинг и размер результата.
**Record-поля:** `enabled`, `pollDelay`, `batchSize`, `processingLease`, `extractionTimeout`,
`extractionThreads`, `maxAttempts`, `initialBackoff`, `maxBackoff`, `maxExtractedChars`,
`maxDocxUncompressedBytes`, `chunkSizeChars`, `chunkOverlapChars`.

**Методы/конструкторы (включая private и nested):**

- `конструктор(13 арг.)` (L24): Инициализировал зависимости и проверял параметры создания.
- `backoffForAttempt(int)` (L96): Реализовывал шаг «backoff for attempt» функции
  KnowledgeIngestionProperties.
- `defaultDuration(Duration, Duration)` (L113): Реализовывал шаг «default duration» функции
  KnowledgeIngestionProperties.
- `bounded(Integer, int, int, int, String)` (L120): Реализовывал шаг «bounded» функции
  KnowledgeIngestionProperties.
- `invalid(String)` (L134): Формировал исключение для некорректного параметра/ответа.

### `KnowledgeOcrConfigurationInvariantVerifier`

**Файл:** `config/KnowledgeOcrConfigurationInvariantVerifier.java`.

Сверял сетевые и временные OCR-ограничения.

**Методы/конструкторы (включая private и nested):**

- `конструктор` (L16): Инициализировал зависимости и проверял параметры создания.
- `verify()` (L24): Проверял конфигурационные инварианты при создании компонента.

### `KnowledgeOcrProperties`

**Файл:** `config/KnowledgeOcrProperties.java`.

Проверял OCR endpoint, allowlist, секрет, лимит PDF/ответа и тайм-ауты.
**Record-поля:** `provider`, `endpoint`, `apiKey`, `allowedHosts`, `minNativeCharsPerPage`,
`connectTimeout`, `readTimeout`, `maxResponseBytes`, `maxInputBytes`.

**Методы/конструкторы (включая private и nested):**

- `конструктор(9 арг.)` (L54): Инициализировал зависимости и проверял параметры создания.
- `toString()` (L233): Формировал представление без раскрытия секретов там, где это предусмотрено.
- `parseUri(String)` (L249): Разбирал «uri» в реализации KnowledgeOcrProperties.
- `positive(Duration, Duration, String)` (L265): Реализовывал шаг «positive» функции
  KnowledgeOcrProperties.
- `invalid(String)` (L285): Формировал исключение для некорректного параметра/ответа.

### `KnowledgeProcessingConfiguration`

**Файл:** `config/KnowledgeProcessingConfiguration.java`.

Регистрировал инфраструктурные bean-компоненты обработки.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

### `KnowledgeRagProperties`

**Файл:** `config/KnowledgeRagProperties.java`.

Проверял лимиты RAG top-k и длины materialized context.
**Record-поля:** `topK`, `maxContextChars`, `maxChunkChars`.

**Методы/конструкторы (включая private и nested):**

- `конструктор(Integer, Integer, Integer)` (L11): Инициализировал зависимости и проверял параметры
  создания.
- `bounded(Integer, int, int, int, String)` (L32): Реализовывал шаг «bounded» функции
  KnowledgeRagProperties.
- `invalid(String)` (L46): Формировал исключение для некорректного параметра/ответа.

### `KnowledgeRetrievalProperties`

**Файл:** `config/KnowledgeRetrievalProperties.java`.

Ограничивал query, top-k, кандидатов и коэффициент RRF.
**Record-поля:** `maxQueryChars`, `maxTopK`, `candidateLimit`, `rrfK`.

**Методы/конструкторы (включая private и nested):**

- `конструктор(Integer, Integer, Integer, Integer)` (L13): Инициализировал зависимости и проверял
  параметры создания.
- `valueOrDefault(Integer, int)` (L33): Реализовывал шаг «value or default» функции
  KnowledgeRetrievalProperties.
- `invalid(String)` (L37): Формировал исключение для некорректного параметра/ответа.

## 9.3. `controller`

HTTP API: маршруты, обработка principal, параметры и ответы.

### `AnswerPassportController`

**Файл:** `controller/AnswerPassportController.java`.

Отдавал подтверждённый паспорт ответа по chat/turn.

**Методы/конструкторы (включая private и nested):**

- `конструктор(AnswerPassportService)` (L22): Инициализировал зависимости и проверял параметры
  создания.
- `get(UUID, UUID, SafeAiUserPrincipal)` (L28): Получал физический или логический ресурс по
  идентификатору.

### `KnowledgeBaseController`

**Файл:** `controller/KnowledgeBaseController.java`.

Предоставлял CRUD БЗ и администрирование участников.

**Методы/конструкторы (включая private и nested):**

- `findAll(SafeAiUserPrincipal, int, int)` (L47): Возвращал tenant-scoped список элементов с
  фильтрацией/пагинацией.
- `findById(UUID, SafeAiUserPrincipal)` (L68): Искал ресурс по идентификатору и области доступа.
- `effectiveAccess(UUID, SafeAiUserPrincipal)` (L80): Реализовывал шаг «effective access» функции
  KnowledgeBaseController.
- `create(CreateKnowledgeBaseRequest, SafeAiUserPrincipal)` (L95): Создавал ресурс по API с
  проверкой условий и прав.
- `update(UUID, UpdateKnowledgeBaseRequest, SafeAiUserPrincipal)` (L110): Изменял ресурс по API с
  проверками доступа и версии.
- `findMembers(UUID, SafeAiUserPrincipal, int, int)` (L127): Выбирал участников указанной БЗ с ролью
  и пагинацией.
- `searchMemberCandidates(String, int, SafeAiUserPrincipal)` (L151): Искал «member candidates» в
  реализации KnowledgeBaseController.
- `addMember(3 арг.)` (L170): Добавлял «member» в реализации KnowledgeBaseController.
- `updateMember(4 арг.)` (L187): Обновлял «member» в реализации KnowledgeBaseController.
- `removeMember(UUID, UUID, long, SafeAiUserPrincipal)` (L206): Удалял «member» в реализации
  KnowledgeBaseController.

### `KnowledgeDocumentController`

**Файл:** `controller/KnowledgeDocumentController.java`.

Предоставлял загрузку, версии, скачивание, health и reindex.

**Методы/конструкторы (включая private и nested):**

- `health(UUID, SafeAiUserPrincipal)` (L51): Вычислял агрегированное состояние выбранной базы
  знаний.
- `reindex(UUID, UUID, SafeAiUserPrincipal)` (L63): Инициировал повторную обработку текущей версии
  документа.
- `list(UUID, int, int, SafeAiUserPrincipal)` (L78): Возвращал страницу ресурсов после проверки
  параметров и доступа.
- `listVersions(UUID, UUID, int, int, SafeAiUserPrincipal)` (L99): Возвращал страницу исторических
  версий документа.
- `update(4 арг.)` (L122): Изменял ресурс по API с проверками доступа и версии.
- `upload(UUID, MultipartFile, String, SafeAiUserPrincipal)` (L140): Выполнял REST-приём нового
  документа.
- `uploadVersion(UUID, UUID, MultipartFile, SafeAiUserPrincipal)` (L159): Принимал новую физическую
  версию существующего документа.
- `downloadCurrent(UUID, UUID, SafeAiUserPrincipal)` (L180): Возвращал содержимое текущей версии по
  HTTP.
- `downloadVersion(UUID, UUID, UUID, SafeAiUserPrincipal)` (L197): Возвращал содержимое явно
  указанной исторической версии.
- `download(KnowledgeDocumentService.Download)` (L215): Готовил выдачу версии документа с проверкой
  разрешений.

### `KnowledgeEvaluationController`

**Файл:** `controller/KnowledgeEvaluationController.java`.

Открывал административную оценку retrieval.

**Методы/конструкторы (включая private и nested):**

- `конструктор(KnowledgeEvaluationService)` (L25): Инициализировал зависимости и проверял параметры
  создания.
- `evaluate(UUID, KnowledgeEvaluationRequest, SafeAiUserPrincipal)` (L31): Запускал оценку качества
  выдачи на наборе кейсов.

### `KnowledgeRetrievalController`

**Файл:** `controller/KnowledgeRetrievalController.java`.

Открывал ручную операцию поиска по базе знаний.

**Методы/конструкторы (включая private и nested):**

- `конструктор(KnowledgeRetrievalService)` (L25): Инициализировал зависимости и проверял параметры
  создания.
- `retrieve(UUID, KnowledgeRetrievalRequest, SafeAiUserPrincipal)` (L31): Реализовывал шаг
  «retrieve» функции KnowledgeRetrievalController.

## 9.4. `dto`

Контракты REST: запросы, DTO ответов, ограничения и версионные поля.

### `AnswerCitationResponse`

**Файл:** `dto/AnswerCitationResponse.java`.

Передавал ссылку цитаты на физический chunk и версию документа.
**Record-поля:** `label`, `chunkId`, `documentId`, `documentVersionId`, `documentName`,
`versionNumber`, `chunkOrdinal`, `pageFrom`, `pageTo`, `heading`, `contentSha256`.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

### `AnswerPassportResponse`

**Файл:** `dto/AnswerPassportResponse.java`.

Возвращал маршрут, источник, хеши и достаточность доказательств ответа.
**Record-поля:** `id`, `chatTurnId`, `retrievalRunId`, `knowledgeBaseId`, `modelRouteDecisionId`,
`knowledgeMode`, `provider`, `requestedModel`, `resolvedModel`, `embeddingModel`, `contextSha256`,
`answerSha256`, `evidenceSufficient`, `citationsValid`, `createdAt`, `citations`.

**Методы/конструкторы (включая private и nested):**

- `конструктор(16 арг.)` (L28): Инициализировал зависимости и проверял параметры создания.

### `CreateKnowledgeBaseMemberRequest`

**Файл:** `dto/CreateKnowledgeBaseMemberRequest.java`.

Запрашивал выдачу роли участнику Knowledge Base.
**Record-поля:** `userId`, `accessLevel`.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

### `CreateKnowledgeBaseRequest`

**Файл:** `dto/CreateKnowledgeBaseRequest.java`.

Создавал БЗ с именем, видимостью и включённым состоянием.
**Record-поля:** `name`, `description`, `visibility`.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

### `KnowledgeBaseAccessResponse`

**Файл:** `dto/KnowledgeBaseAccessResponse.java`.

Возвращал эффективный уровень прав текущего пользователя.
**Record-поля:** `knowledgeBaseId`, `accessLevel`, `administrator`, `canEditDocuments`,
`canManageBase`, `canManageMembers`.

**Методы/конструкторы (включая private и nested):**

- `конструктор(6 арг.)` (L18): Инициализировал зависимости и проверял параметры создания.
- `from(KnowledgeAccessService.Access)` (L29): Создавал DTO/модель из входного объекта.

### `KnowledgeBaseMemberPageResponse`

**Файл:** `dto/KnowledgeBaseMemberPageResponse.java`.

Упаковывал страницу участников с пагинацией.
**Record-поля:** `content`, `page`, `size`, `totalElements`, `totalPages`.

**Методы/конструкторы (включая private и nested):**

- `конструктор(5 арг.)` (L15): Инициализировал зависимости и проверял параметры создания.
- `from(Page<KnowledgeBaseMemberResponse>)` (L19): Создавал DTO/модель из входного объекта.

### `KnowledgeBaseMemberResponse`

**Файл:** `dto/KnowledgeBaseMemberResponse.java`.

Передавал роль участника и версионные поля.
**Record-поля:** `knowledgeBaseId`, `userId`, `email`, `fullName`, `accessLevel`, `version`,
`createdAt`, `updatedAt`.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

### `KnowledgeBasePageResponse`

**Файл:** `dto/KnowledgeBasePageResponse.java`.

Упаковывал страницу БЗ и метаданные пагинации.
**Record-поля:** `content`, `page`, `size`, `totalElements`, `totalPages`.

**Методы/конструкторы (включая private и nested):**

- `конструктор(List<KnowledgeBaseResponse>, int, int, long, int)` (L15): Инициализировал зависимости
  и проверял параметры создания.
- `from(Page<KnowledgeBaseResponse>)` (L19): Создавал DTO/модель из входного объекта.

### `KnowledgeBaseResponse`

**Файл:** `dto/KnowledgeBaseResponse.java`.

Возвращал свойства и версию БЗ.
**Record-поля:** `id`, `organizationId`, `name`, `description`, `visibility`, `enabled`,
`createdByUserId`, `version`, `createdAt`, `updatedAt`.

**Методы/конструкторы (включая private и nested):**

- `from(KnowledgeBaseEntity)` (L22): Создавал DTO/модель из входного объекта.

### `KnowledgeDocumentPageResponse`

**Файл:** `dto/KnowledgeDocumentPageResponse.java`.

Упаковывал страницу документов.
**Record-поля:** `content`, `page`, `size`, `totalElements`, `totalPages`.

**Методы/конструкторы (включая private и nested):**

- `конструктор` (L12): Инициализировал зависимости и проверял параметры создания.

### `KnowledgeDocumentResponse`

**Файл:** `dto/KnowledgeDocumentResponse.java`.

Описывал документ, его текущую версию и ingest-статус.
**Record-поля:** `id`, `knowledgeBaseId`, `name`, `enabled`, `version`, `currentVersionId`,
`searchableVersionId`, `versionNumber`, `originalFilename`, `mediaType`, `sizeBytes`, `status`,
`createdAt`, `updatedAt`.

**Методы/конструкторы (включая private и nested):**

- `from(3 арг.)` (L24): Создавал DTO/модель из входного объекта.

### `KnowledgeDocumentVersionPageResponse`

**Файл:** `dto/KnowledgeDocumentVersionPageResponse.java`.

Упаковывал историю версий документа.
**Record-поля:** `content`, `page`, `size`, `totalElements`, `totalPages`.

**Методы/конструкторы (включая private и nested):**

- `конструктор(5 арг.)` (L14): Инициализировал зависимости и проверял параметры создания.
- `from(Page<KnowledgeDocumentVersionResponse>)` (L18): Создавал DTO/модель из входного объекта.

### `KnowledgeDocumentVersionResponse`

**Файл:** `dto/KnowledgeDocumentVersionResponse.java`.

Передавал метаданные версии, SHA-256 и состояние.
**Record-поля:** `id`, `documentId`, `versionNumber`, `originalFilename`, `mediaType`, `sizeBytes`,
`sha256`, `createdByUserId`, `createdAt`.

**Методы/конструкторы (включая private и nested):**

- `from(KnowledgeDocumentVersionEntity)` (L19): Создавал DTO/модель из входного объекта.

### `KnowledgeEvaluationCaseRequest`

**Файл:** `dto/KnowledgeEvaluationCaseRequest.java`.

Передавал запрос поиска и множество ожидаемых версий.
**Record-поля:** `query`, `expectedDocumentVersionIds`.

**Методы/конструкторы (включая private и nested):**

- `конструктор(String, Set<UUID>)` (L18): Инициализировал зависимости и проверял параметры создания.

### `KnowledgeEvaluationCaseResponse`

**Файл:** `dto/KnowledgeEvaluationCaseResponse.java`.

Возвращал показатели качества отдельного evaluation-кейса.
**Record-поля:** `ordinal`, `retrievalRunId`, `recall`, `reciprocalRank`, `ndcg`,
`firstRelevantRank`.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

### `KnowledgeEvaluationRequest`

**Файл:** `dto/KnowledgeEvaluationRequest.java`.

Запрашивал batch evaluation с dataset name и top-k.
**Record-поля:** `datasetName`, `topK`, `cases`.

**Методы/конструкторы (включая private и nested):**

- `конструктор(3 арг.)` (L24): Инициализировал зависимости и проверял параметры создания.

### `KnowledgeEvaluationResponse`

**Файл:** `dto/KnowledgeEvaluationResponse.java`.

Возвращал итоговые значения recall, MRR и nDCG.
**Record-поля:** `evaluationRunId`, `knowledgeBaseId`, `datasetName`, `topK`, `meanRecall`,
`meanReciprocalRank`, `meanNdcg`, `createdAt`, `cases`.

**Методы/конструкторы (включая private и nested):**

- `конструктор(9 арг.)` (L18): Инициализировал зависимости и проверял параметры создания.

### `KnowledgeHealthResponse`

**Файл:** `dto/KnowledgeHealthResponse.java`.

Передавал агрегированное состояние БЗ и счётчики задач.
**Record-поля:** `knowledgeBaseId`, `state`, `activeEmbeddingModel`, `totalDocuments`,
`enabledDocuments`, `searchableDocuments`, `pendingDocuments`, `processingDocuments`,
`failedDocuments`, `staleEmbeddingDocuments`, `activeChunks`, `checkedAt`.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

### `KnowledgeMemberCandidateResponse`

**Файл:** `dto/KnowledgeMemberCandidateResponse.java`.

Представлял кандидата для приглашения в БЗ.
**Record-поля:** `userId`, `email`, `fullName`.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

### `KnowledgeReindexResponse`

**Файл:** `dto/KnowledgeReindexResponse.java`.

Подтверждал постановку версии документа на переиндексацию.
**Record-поля:** `knowledgeBaseId`, `documentId`, `documentVersionId`, `ingestionJobId`, `status`,
`requestedAt`.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

### `KnowledgeRetrievalHitResponse`

**Файл:** `dto/KnowledgeRetrievalHitResponse.java`.

Описывал источник, ранги и оценки одного retrieval hit.
**Record-поля:** `rank`, `chunkId`, `documentId`, `documentVersionId`, `documentName`,
`versionNumber`, `chunkOrdinal`, `content`, `pageFrom`, `pageTo`, `heading`, `fusedScore`,
`lexicalRank`, `semanticRank`, `lexicalScore`, `cosineSimilarity`, `contentSha256`.

**Методы/конструкторы (включая private и nested):**

- `from(int, KnowledgeRetrievalHit)` (L27): Создавал DTO/модель из входного объекта.

### `KnowledgeRetrievalRequest`

**Файл:** `dto/KnowledgeRetrievalRequest.java`.

Запрашивал строку поиска и top-k.
**Record-поля:** `query`, `topK`.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

### `KnowledgeRetrievalResponse`

**Файл:** `dto/KnowledgeRetrievalResponse.java`.

Возвращал ID retrieval run, хеш запроса и отсортированные hits.
**Record-поля:** `retrievalRunId`, `knowledgeBaseId`, `querySha256`, `embeddingModel`,
`completedAt`, `hits`.

**Методы/конструкторы (включая private и nested):**

- `конструктор(6 арг.)` (L16): Инициализировал зависимости и проверял параметры создания.

### `UpdateKnowledgeBaseMemberRequest`

**Файл:** `dto/UpdateKnowledgeBaseMemberRequest.java`.

Менял права участника с контролем ожидаемой версии.
**Record-поля:** `accessLevel`, `expectedVersion`.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

### `UpdateKnowledgeBaseRequest`

**Файл:** `dto/UpdateKnowledgeBaseRequest.java`.

Менял свойства БЗ с ожидаемой версией.
**Record-поля:** `name`, `description`, `visibility`, `enabled`, `expectedVersion`.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

### `UpdateKnowledgeDocumentRequest`

**Файл:** `dto/UpdateKnowledgeDocumentRequest.java`.

Менял метаданные документа.
**Record-поля:** `name`, `enabled`, `expectedVersion`.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

## 9.5. `embedding`

Абстракция векторизации, OpenAI и локальный hashing-adapter.

### `HashingKnowledgeEmbeddingProvider`

**Файл:** `embedding/HashingKnowledgeEmbeddingProvider.java`.

Детерминированный локальный hashing embedding для тестов и демонстраций.

**Методы/конструкторы (включая private и nested):**

- `dimensions()` (L29): Возвращал размерность вектора текущей модели.
- `model()` (L34): Возвращал идентификатор применяемой модели embedding.
- `embed(String)` (L39): Вычислял или валидировал embedding для одного элемента.
- `addCharacterNgrams(float[], String)` (L61): Добавлял «character ngrams» в реализации
  HashingKnowledgeEmbeddingProvider.
- `addFeature(float[], String, float)` (L79): Добавлял «feature» в реализации
  HashingKnowledgeEmbeddingProvider.
- `sha256(String)` (L94): Вычислял SHA-256 от содержимого для проверки целостности.
- `normalize(float[])` (L104): Канонизировал входное значение по правилам конкретного компонента.

### `KnowledgeEmbeddingException`

**Файл:** `embedding/KnowledgeEmbeddingException.java`.

Хранил код ошибки и retryable-классификацию embedding.

**Методы/конструкторы (включая private и nested):**

- `конструктор(String, String, boolean)` (L10): Инициализировал зависимости и проверял параметры
  создания.
- `конструктор(String, String, boolean, Throwable)` (L23): Инициализировал зависимости и проверял
  параметры создания.
- `code()` (L40): Возвращал стабильный классификатор ошибки.
- `retryable()` (L44): Возвращал признак возможности повторного выполнения.
- `requireCode(String)` (L48): Проверял и требовал «code» в реализации KnowledgeEmbeddingException.
- `requireMessage(String)` (L68): Проверял и требовал «message» в реализации
  KnowledgeEmbeddingException.

### `KnowledgeEmbeddingProductionInvariantVerifier`

**Файл:** `embedding/KnowledgeEmbeddingProductionInvariantVerifier.java`.

Запрещал тестовый hashing embedding в production.

**Методы/конструкторы (включая private и nested):**

- `конструктор(KnowledgeEmbeddingProperties)` (L16): Инициализировал зависимости и проверял
  параметры создания.
- `verify()` (L22): Проверял конфигурационные инварианты при создании компонента.

### `KnowledgeEmbeddingProvider`

**Файл:** `embedding/KnowledgeEmbeddingProvider.java`.

Контракт для одиночной и пакетной векторизации.

**Методы/конструкторы (включая private и nested):**

- `dimensions()` (L7): Возвращал размерность вектора текущей модели.
- `model()` (L9): Возвращал идентификатор применяемой модели embedding.
- `embed(String)` (L11): Вычислял или валидировал embedding для одного элемента.
- `embedAll(List<String>)` (L13): Обрабатывал пакет текстов с сохранением позиционного соответствия.
- `preferredBatchSize()` (L25): Возвращал оптимальный размер batch для провайдера.

### `OpenAiKnowledgeEmbeddingProvider`

**Файл:** `embedding/OpenAiKnowledgeEmbeddingProvider.java`.

Вызывал внешний embedding API с строгой проверкой ответа и ограничениями.

**Методы/конструкторы (включая private и nested):**

- `конструктор(KnowledgeEmbeddingProperties)` (L54): Инициализировал зависимости и проверял
  параметры создания.
- `конструктор(KnowledgeEmbeddingProperties, RestClient)` (L69): Инициализировал зависимости и
  проверял параметры создания.
- `dimensions()` (L86): Возвращал размерность вектора текущей модели.
- `model()` (L91): Возвращал идентификатор применяемой модели embedding.
- `preferredBatchSize()` (L99): Возвращал оптимальный размер batch для провайдера.
- `embed(String)` (L104): Вычислял или валидировал embedding для одного элемента.
- `embedAll(List<String>)` (L125): Делил запросы на допустимые batch и обеспечивал порядок
  embeddings.
- `requestBatch(List<String>)` (L246): Отправлял запрос с ограничением размера и корректной
  retry-классификацией.
- `validateBatchPayloadUpperBound(List<String>)` (L355): Проверял корректность «batch payload upper
  bound» в реализации OpenAiKnowledgeEmbeddingProvider.
- `calculateBasePayloadBytes()` (L384): Оценивал базовый JSON payload перед embedding вызовом.
- `parse(JsonNode, int)` (L397): Проверял модель, индексы, размеры, численные и ненулевые значения.
- `estimateJsonStringUpperBoundBytes(String)` (L572): Оценивал «json string upper bound bytes» в
  реализации OpenAiKnowledgeEmbeddingProvider.
- `validateInput(String)` (L656): Проверял корректность «input» в реализации
  OpenAiKnowledgeEmbeddingProvider.
- `safeAdd(long, long)` (L674): Формировал безопасное значение «add» в реализации
  OpenAiKnowledgeEmbeddingProvider.
- `invalidResponse(String)` (L693): Реализовывал шаг «invalid response» функции
  OpenAiKnowledgeEmbeddingProvider.

### `PgVectorSupport`

**Файл:** `embedding/PgVectorSupport.java`.

Сериализовал проверенные float-векторы для PostgreSQL pgvector.

**Методы/конструкторы (включая private и nested):**

- `конструктор()` (L5): Инициализировал зависимости и проверял параметры создания.
- `encode(float[])` (L8): Реализовывал шаг «encode» функции PgVectorSupport.

## 9.6. `entity`

JPA-проекции таблиц: связь с организацией, версиями и ingestion.

### `KnowledgeBaseEntity`

**Файл:** `entity/KnowledgeBaseEntity.java`.

JPA-сущность БЗ с tenant ID, видимостью, флагом enabled и version.

**Методы/конструкторы (включая private и nested):**

- `prePersist()` (L71): Устанавливал инварианты сущности перед записью JPA.

### `KnowledgeBaseMembershipEntity`

**Файл:** `entity/KnowledgeBaseMembershipEntity.java`.

Связь пользователя с Knowledge Base и уровнем доступа.

**Методы/конструкторы (включая private и nested):**

- `prePersist()` (L65): Устанавливал инварианты сущности перед записью JPA.

### `KnowledgeDocumentEntity`

**Файл:** `entity/KnowledgeDocumentEntity.java`.

Сущность документа с current/searchable версиями.

**Методы/конструкторы (включая private и nested):**

- `prePersist()` (L57): Устанавливал инварианты сущности перед записью JPA.

### `KnowledgeDocumentVersionEntity`

**Файл:** `entity/KnowledgeDocumentVersionEntity.java`.

Immutable metadata версии, storage key, хеш, media type и размер.

**Методы/конструкторы (включая private и nested):**

- `prePersist()` (L107): Устанавливал инварианты сущности перед записью JPA.

### `KnowledgeIngestionJobEntity`

**Файл:** `entity/KnowledgeIngestionJobEntity.java`.

Состояние ingestion job, attempt, lease, token и технические результаты.

**Методы/конструкторы (включая private и nested):**

- `prePersist()` (L97): Устанавливал инварианты сущности перед записью JPA.

## 9.7. `evaluation`

Оценка recall, MRR и nDCG по размеченным документам.

### `KnowledgeRetrievalMetrics`

**Файл:** `evaluation/KnowledgeRetrievalMetrics.java`.

Считал recall, reciprocal rank и nDCG для retrieval run.

**Методы/конструкторы (включая private и nested):**

- `конструктор()` (L10): Инициализировал зависимости и проверял параметры создания.
- `evaluate(List<UUID>, Set<UUID>)` (L13): Считал recall, MRR и nDCG на уровне document version IDs.
- `log2(double)` (L50): Вычислял двоичный логарифм для метрики DCG.

## 9.8. `exception`

Контролируемые инфраструктурные исключения публичного API.

### `KnowledgeStorageUnavailableException`

**Файл:** `exception/KnowledgeStorageUnavailableException.java`.

Переводил недоступность object storage в безопасный HTTP 503.

**Методы/конструкторы (включая private и nested):**

- `конструктор(String, Throwable)` (L14): Инициализировал зависимости и проверял параметры создания.

## 9.9. `extraction`

Безопасное извлечение текста из разных форматов.

### `CsvKnowledgeExtractor`

**Файл:** `extraction/CsvKnowledgeExtractor.java`.

Разбирал CSV и превращал таблицы в извлекаемый текст.

**Методы/конструкторы (включая private и nested):**

- `конструктор(KnowledgeIngestionProperties)` (L18): Инициализировал зависимости и проверял
  параметры создания.
- `supports(String)` (L22): Сообщал, поддерживает ли компонент данный тип входа.
- `extract(byte[])` (L27): Извлекал содержимое поддерживаемого типа с ограничением ресурсов.
- `parse(String)` (L44): Разбирал внешний формат в проверенную внутреннюю модель.
- `addRecord(List<List<String>>, List<String>)` (L107): Добавлял «record» в реализации
  CsvKnowledgeExtractor.
- `normalizeField(StringBuilder)` (L116): Нормализовал «field» в реализации CsvKnowledgeExtractor.
- `render(List<List<String>>)` (L121): Реализовывал шаг «render» функции CsvKnowledgeExtractor.
- `appendRow(StringBuilder, List<String>, List<String>)` (L135): Форматировал одну строку таблицы.
- `invalidCsv()` (L158): Реализовывал шаг «invalid csv» функции CsvKnowledgeExtractor.

### `DocxKnowledgeExtractor`

**Файл:** `extraction/DocxKnowledgeExtractor.java`.

Извлекал абзацы и таблицы DOCX с проверкой OOXML.

**Методы/конструкторы (включая private и nested):**

- `конструктор(KnowledgeIngestionProperties)` (L27): Инициализировал зависимости и проверял
  параметры создания.
- `supports(String)` (L31): Сообщал, поддерживает ли компонент данный тип входа.
- `extract(byte[])` (L36): Извлекал содержимое поддерживаемого типа с ограничением ресурсов.
- `validateArchive(byte[])` (L72): Проверял корректность «archive» в реализации
  DocxKnowledgeExtractor.
- `appendTable(StringBuilder, XWPFTable)` (L81): Добавлял содержимое таблицы.
- `appendLine(StringBuilder, String)` (L95): Добавлял строку с проверкой форматирования.
- `ensureTextLimit(int)` (L101): Реализовывал шаг «ensure text limit» функции
  DocxKnowledgeExtractor.

### `ExtractedDocument`

**Файл:** `extraction/ExtractedDocument.java`.

Группировал секции и версию алгоритма извлечения.
**Record-поля:** `extractorVersion`, `sections`, `characterCount`.

**Методы/конструкторы (включая private и nested):**

- `конструктор(String, List<ExtractedSection>, int)` (L11): Инициализировал зависимости и проверял
  параметры создания.

### `ExtractedSection`

**Файл:** `extraction/ExtractedSection.java`.

Хранил текст секции и её страницу/заголовок.
**Record-поля:** `pageNumber`, `heading`, `text`.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

### `ExtractionTextSupport`

**Файл:** `extraction/ExtractionTextSupport.java`.

Нормализовал extracted text, UTF-8 и ограничивал объём.

**Методы/конструкторы (включая private и nested):**

- `конструктор()` (L13): Инициализировал зависимости и проверял параметры создания.
- `normalize(String)` (L16): Канонизировал входное значение по правилам конкретного компонента.
- `decodeUtf8(byte[], String)` (L39): Декодировал «utf8» в реализации ExtractionTextSupport.
- `addAndCheck(int, String, int)` (L75): Добавлял «and check» в реализации ExtractionTextSupport.
- `tooLarge()` (L99): Преобразовывал «o large» в реализации ExtractionTextSupport.

### `HtmlKnowledgeExtractor`

**Файл:** `extraction/HtmlKnowledgeExtractor.java`.

Извлекал полезный текст из HTML через парсер.

**Методы/конструкторы (включая private и nested):**

- `конструктор(KnowledgeIngestionProperties)` (L21): Инициализировал зависимости и проверял
  параметры создания.
- `supports(String)` (L25): Сообщал, поддерживает ли компонент данный тип входа.
- `extract(byte[])` (L30): Извлекал содержимое поддерживаемого типа с ограничением ресурсов.
- `blankToNull(String)` (L61): Реализовывал шаг «blank to null» функции HtmlKnowledgeExtractor.

### `JsonKnowledgeExtractor`

**Файл:** `extraction/JsonKnowledgeExtractor.java`.

Проверял JSON и извлекал текстовую репрезентацию.

**Методы/конструкторы (включая private и nested):**

- `конструктор(KnowledgeIngestionProperties)` (L21): Инициализировал зависимости и проверял
  параметры создания.
- `supports(String)` (L25): Сообщал, поддерживает ли компонент данный тип входа.
- `extract(byte[])` (L30): Извлекал содержимое поддерживаемого типа с ограничением ресурсов.

### `KnowledgeDocumentExtractor`

**Файл:** `extraction/KnowledgeDocumentExtractor.java`.

Единый интерфейс извлечения текста по media type.

**Методы/конструкторы (включая private и nested):**

- `supports(String)` (L5): Сообщал, поддерживает ли компонент данный тип входа.
- `extract(byte[])` (L7): Извлекал содержимое поддерживаемого типа с ограничением ресурсов.

### `KnowledgeExtractionService`

**Файл:** `extraction/KnowledgeExtractionService.java`.

Исполнял извлечение в ограниченном thread pool с timeout.

**Методы/конструкторы (включая private и nested):**

- `конструктор(2 арг.)` (L27): Инициализировал зависимости и проверял параметры создания.
- `extract(String, byte[])` (L86): Выполнял extraction в bounded executor с timeout и cancellation.
- `cancelAndRemove(Future<ExtractedDocument>)` (L198): Отменял Future; это не гарантировало
  остановку нативного парсера.
- `shutdown()` (L213): Реализовывал шаг «shutdown» функции KnowledgeExtractionService.
- `threadFactory()` (L218): Реализовывал шаг «thread factory» функции KnowledgeExtractionService.

### `MarkdownKnowledgeExtractor`

**Файл:** `extraction/MarkdownKnowledgeExtractor.java`.

Извлекал Markdown и заголовки документа.

**Методы/конструкторы (включая private и nested):**

- `конструктор(KnowledgeIngestionProperties)` (L16): Инициализировал зависимости и проверял
  параметры создания.
- `supports(String)` (L22): Сообщал, поддерживает ли компонент данный тип входа.
- `extract(byte[])` (L27): Извлекал содержимое поддерживаемого типа с ограничением ресурсов.
- `firstHeading(String)` (L44): Извлекал первый Markdown-заголовок.

### `OoxmlPackageSupport`

**Файл:** `extraction/OoxmlPackageSupport.java`.

Проверял zip/OOXML структуру, имена entry и декомпрессию.

**Методы/конструкторы (включая private и nested):**

- `конструктор()` (L28): Инициализировал зависимости и проверял параметры создания.
- `validate(byte[], String, long, String)` (L30): Реализовывал шаг «validate» функции
  OoxmlPackageSupport.
- `конструктор(Map<String, Integer>, byte[])` (L47): Инициализировал зависимости и проверял
  параметры создания.
- `contentTypes()` (L52): Возвращал байты metadata OOXML архива.
- `count(String)` (L57): Подсчитывал количество связанных записей.
- `inspect(byte[], long, String)` (L62): Проверял архив на небезопасные entries и лимит распаковки.
- `isUnsafeEntryName(String)` (L119): Определял «unsafe entry name» в реализации
  OoxmlPackageSupport.
- `invalid(String, String)` (L125): Формировал исключение для некорректного параметра/ответа.
- `invalid(String, String, Throwable)` (L129): Формировал исключение для некорректного
  параметра/ответа.

### `PdfKnowledgeExtractor`

**Файл:** `extraction/PdfKnowledgeExtractor.java`.

Читал PDF постранично и запрашивал OCR только для нужных страниц.

**Методы/конструкторы (включая private и nested):**

- `конструктор(3 арг.)` (L35): Инициализировал зависимости и проверял параметры создания.
- `supports(String)` (L60): Сообщал, поддерживает ли компонент данный тип входа.
- `extract(byte[])` (L69): Читал текст постранично, выбирал OCR-страницы, проверял полноту.
- `requiredOcrPages(List<String>)` (L271): Определял PDF-страницы, где native text недостаточен.
- `validateAndMapOcrPages(OcrDocument, int, Set<Integer>)` (L311): Проверял полноту обязательных
  OCR-страниц и их нумерацию.
- `boundedVersion(String)` (L400): Реализовывал шаг «bounded version» функции PdfKnowledgeExtractor.
- `invalidOcrResponse(String)` (L422): Реализовывал шаг «invalid ocr response» функции
  PdfKnowledgeExtractor.

### `PlainTextKnowledgeExtractor`

**Файл:** `extraction/PlainTextKnowledgeExtractor.java`.

Декодировал и валидировал plain text.

**Методы/конструкторы (включая private и nested):**

- `конструктор(KnowledgeIngestionProperties)` (L17): Инициализировал зависимости и проверял
  параметры создания.
- `supports(String)` (L23): Сообщал, поддерживает ли компонент данный тип входа.
- `extract(byte[])` (L32): Извлекал содержимое поддерживаемого типа с ограничением ресурсов.

### `PptxKnowledgeExtractor`

**Файл:** `extraction/PptxKnowledgeExtractor.java`.

Извлекал содержимое слайдов, групп и таблиц PPTX.

**Методы/конструкторы (включая private и nested):**

- `конструктор(KnowledgeIngestionProperties)` (L31): Инициализировал зависимости и проверял
  параметры создания.
- `supports(String)` (L35): Сообщал, поддерживает ли компонент данный тип входа.
- `extract(byte[])` (L40): Извлекал содержимое поддерживаемого типа с ограничением ресурсов.
- `extractShapes(List<XSLFShape>)` (L86): Извлекал «shapes» в реализации PptxKnowledgeExtractor.
- `appendTable(StringBuilder, XSLFTable)` (L103): Добавлял содержимое таблицы.
- `appendText(StringBuilder, String)` (L121): Добавлял выделенный текст в итоговый буфер.
- `heading(XSLFSlide, int)` (L132): Извлекал название заголовка секции.

### `XlsxKnowledgeExtractor`

**Файл:** `extraction/XlsxKnowledgeExtractor.java`.

Извлекал значения ячеек и листов XLSX.

**Методы/конструкторы (включая private и nested):**

- `конструктор(KnowledgeIngestionProperties)` (L29): Инициализировал зависимости и проверял
  параметры создания.
- `supports(String)` (L33): Сообщал, поддерживает ли компонент данный тип входа.
- `extract(byte[])` (L38): Извлекал содержимое поддерживаемого типа с ограничением ресурсов.
- `extractSheet(Sheet, DataFormatter)` (L85): Извлекал «sheet» в реализации XlsxKnowledgeExtractor.

### `XmlKnowledgeExtractor`

**Файл:** `extraction/XmlKnowledgeExtractor.java`.

Использовал безопасный streaming XML reader.

**Методы/конструкторы (включая private и nested):**

- `конструктор(KnowledgeIngestionProperties)` (L24): Инициализировал зависимости и проверял
  параметры создания.
- `supports(String)` (L28): Сообщал, поддерживает ли компонент данный тип входа.
- `extract(byte[])` (L33): Извлекал содержимое поддерживаемого типа с ограничением ресурсов.
- `extract(XMLStreamReader)` (L68): Извлекал содержимое поддерживаемого типа с ограничением
  ресурсов.
- `appendAttributes(3 арг.)` (L127): Добавлял XML-атрибуты в извлечённое представление.
- `appendLine(StringBuilder, String, String)` (L145): Добавлял строку с проверкой форматирования.
- `path(Deque<ElementState>, String)` (L156): Реализовывал шаг «path» функции XmlKnowledgeExtractor.
- `secureFactory()` (L176): Создавал XML-парсер с защитой от DTD/XXE.
- `closeQuietly(XMLStreamReader)` (L190): Освобождал ресурс, не подменяя исходную ошибку.
- `конструктор(String)` (L203): Инициализировал зависимости и проверял параметры создания.

## 9.10. `generation`

Сборка мусора индексов и сохранение воспроизводимых поколений.

### `KnowledgeGenerationGc`

**Файл:** `generation/KnowledgeGenerationGc.java`.

Очищал неиспользуемые поколения индекса при сохранении retrieval evidence.

**Методы/конструкторы (включая private и nested):**

- `конструктор(JdbcTemplate)` (L25): Инициализировал зависимости и проверял параметры создания.
- `collect(Duration, int, int)` (L34): Удалял RETIRED generations без retrieval evidence в bounded
  TX.
- `collect(Duration, int, int, int)` (L40): Удалял RETIRED generations без retrieval evidence в
  bounded TX.
- `validate(Duration, int, int, int)` (L175): Реализовывал шаг «validate» функции
  KnowledgeGenerationGc.

### `KnowledgeGenerationGcScheduler`

**Файл:** `generation/KnowledgeGenerationGcScheduler.java`.

Запускал ограниченный плановый garbage collection индексов.

**Методы/конструкторы (включая private и nested):**

- `конструктор(KnowledgeGenerationGc, Duration, int, int, int)` (L23): Инициализировал зависимости и
  проверял параметры создания.
- `collect()` (L47): Реализовывал шаг «collect» функции KnowledgeGenerationGcScheduler.

## 9.11. `ingestion`

Асинхронная обработка с владением lease, retries и атомарной публикацией.

### `EmbeddedKnowledgeChunk`

**Файл:** `ingestion/EmbeddedKnowledgeChunk.java`.

Связывал chunk с рассчитанным вектором.
**Record-поля:** `chunk`, `embedding`.

**Методы/конструкторы (включая private и nested):**

- `конструктор(KnowledgeChunkCandidate, float[])` (L10): Инициализировал зависимости и проверял
  параметры создания.
- `embedding()` (L14): Реализовывал шаг «embedding» функции EmbeddedKnowledgeChunk.

### `ExpiredKnowledgeIngestionJob`

**Файл:** `ingestion/ExpiredKnowledgeIngestionJob.java`.

Идентифицировал задачу с потерянным lease.
**Record-поля:** `jobId`, `organizationId`, `knowledgeBaseId`, `documentId`, `documentVersionId`,
`attempt`.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

### `KnowledgeChunkPersistenceService`

**Файл:** `ingestion/KnowledgeChunkPersistenceService.java`.

Атомарно публиковал chunks/поколение и READY + обязательный audit.

**Методы/конструкторы (включая private и nested):**

- `конструктор(3 арг.)` (L34): Инициализировал зависимости и проверял параметры создания.
- `replaceChunksAndComplete(5 арг.)` (L44): Писал chunks и READY + обязательный Audit в одной
  транзакции.
- `lockOwnedJob(KnowledgeIngestionClaim, Instant)` (L233): Проверял processing token и lease перед
  физической публикацией.
- `setNullableInteger(PreparedStatement, int, Integer)` (L259): Устанавливал «nullable integer» в
  реализации KnowledgeChunkPersistenceService.
- `setNullableHeading(PreparedStatement, String)` (L277): Устанавливал «nullable heading» в
  реализации KnowledgeChunkPersistenceService.

### `KnowledgeIngestionClaim`

**Файл:** `ingestion/KnowledgeIngestionClaim.java`.

Фиксировал владение задачей через jobId, processing token и attempt.
**Record-поля:** `jobId`, `organizationId`, `knowledgeBaseId`, `documentId`, `documentVersionId`,
`processingToken`, `attempt`, `leaseUntil`.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

### `KnowledgeIngestionException`

**Файл:** `ingestion/KnowledgeIngestionException.java`.

Передавал стабильный код failure и флаг возможности повторной попытки.

**Методы/конструкторы (включая private и nested):**

- `конструктор(String, String, boolean)` (L8): Инициализировал зависимости и проверял параметры
  создания.
- `конструктор(String, String, boolean, Throwable)` (L18): Инициализировал зависимости и проверял
  параметры создания.
- `code()` (L29): Возвращал стабильный классификатор ошибки.
- `retryable()` (L33): Возвращал признак возможности повторного выполнения.

### `KnowledgeIngestionFailureService`

**Файл:** `ingestion/KnowledgeIngestionFailureService.java`.

Регистрировал ошибку/повтор и системное audit-событие.

**Методы/конструкторы (включая private и nested):**

- `конструктор` (L17): Инициализировал зависимости и проверял параметры создания.
- `recordFailure(7 арг.)` (L25): Фиксировал «failure» в реализации KnowledgeIngestionFailureService.

### `KnowledgeIngestionMaintenanceService`

**Файл:** `ingestion/KnowledgeIngestionMaintenanceService.java`.

Закрывал исчерпанные lease задачи и инициировал maintenance.

**Методы/конструкторы (включая private и nested):**

- `конструктор` (L18): Инициализировал зависимости и проверял параметры создания.
- `failExhaustedExpired(Instant, int)` (L26): Переводил в ошибку «exhausted expired» в реализации
  KnowledgeIngestionMaintenanceService.

### `KnowledgeIngestionProcessor`

**Файл:** `ingestion/KnowledgeIngestionProcessor.java`.

Оркестрировал validate → extract → chunk → embed → publish.

**Методы/конструкторы (включая private и nested):**

- `конструктор(11 арг.)` (L53): Инициализировал зависимости и проверял параметры создания.
- `process(KnowledgeIngestionClaim)` (L79): Проверял объект, извлекал, chunk-ил, embed-ил и
  публиковал версию.
- `embedWithLeaseRenewal(2 арг.)` (L191): Запрашивал embeddings пакетами, продлевал lease между
  вызовами.
- `requireExactVersion(KnowledgeIngestionClaim)` (L271): Проверял и требовал «exact version» в
  реализации KnowledgeIngestionProcessor.
- `readAndValidateObject(KnowledgeDocumentVersionEntity)` (L290): Сверял размер объекта и SHA-256 с
  неизменяемыми метаданными.
- `validateStoredLength(3 арг.)` (L327): Сравнивал размер объекта с immutable metadata.
- `readContent(StoredObject, int)` (L347): Читал «content» в реализации KnowledgeIngestionProcessor.
- `validateReadLength` (L362): Сверял число реально прочитанных байтов.
- `validateContentHash(byte[], KnowledgeDocumentVersionEntity)` (L378): Сверял SHA-256 фактического
  объекта и версии.
- `maximumObjectBytes()` (L404): Вычислял максимум «imum object bytes» в реализации
  KnowledgeIngestionProcessor.
- `embed(KnowledgeChunkCandidate, float[])` (L421): Отбрасывал нечисловые, нулевые и неверной
  размерности векторы.
- `transition(3 арг.)` (L468): Реализовывал шаг «transition» функции KnowledgeIngestionProcessor.
- `renewLease(KnowledgeIngestionClaim, KnowledgeIngestionStatus)` (L487): Продлевал «lease» в
  реализации KnowledgeIngestionProcessor.
- `renewLeaseAt(3 арг.)` (L498): Продлевал «lease at» в реализации KnowledgeIngestionProcessor.
- `fail(KnowledgeIngestionClaim, String, String, boolean)` (L513): Реализовывал шаг «fail» функции
  KnowledgeIngestionProcessor.
- `sha256Bytes(byte[])` (L544): Вычислял SHA-256 «256 bytes» в реализации
  KnowledgeIngestionProcessor.
- `decodeExpectedSha256(String)` (L563): Декодировал «expected sha256» в реализации
  KnowledgeIngestionProcessor.

### `KnowledgeIngestionQueueRepository`

**Файл:** `ingestion/KnowledgeIngestionQueueRepository.java`.

Реализовал SKIP LOCKED claim, lease renewal, retries и state transitions.

**Методы/конструкторы (включая private и nested):**

- `конструктор(JdbcTemplate)` (L26): Инициализировал зависимости и проверял параметры создания.
- `claimNext(Instant, Instant, int)` (L36): Атомарно claim с SKIP LOCKED и новым processing token.
- `transition(5 арг.)` (L182): Менял статус только при совпадении token, статуса и действующего
  lease.
- `renewLease(4 арг.)` (L256): Продлевал lease при сохранённом владении задачей.
- `fail(7 арг.)` (L322): Сохранял retryable состояние PENDING либо терминальный FAILED.
- `failExhaustedExpiredAndReturn(Instant, int)` (L384): Закрывал просроченные задачи с исчерпанными
  попытками.
- `scheduleRetry(5 арг.)` (L446): Реализовывал шаг «schedule retry» функции
  KnowledgeIngestionQueueRepository.
- `markFailed(KnowledgeIngestionClaim, String, String, Instant)` (L490): Помечал «failed» в
  реализации KnowledgeIngestionQueueRepository.
- `mapClaim(ResultSet)` (L533): Преобразовывал «claim» в реализации
  KnowledgeIngestionQueueRepository.
- `mapExpired(ResultSet)` (L579): Преобразовывал «expired» в реализации
  KnowledgeIngestionQueueRepository.
- `requireOwnership(int)` (L609): Отбрасывал stale token или потерянный lease.
- `truncateErrorMessage(String)` (L617): Реализовывал шаг «truncate error message» функции
  KnowledgeIngestionQueueRepository.

### `KnowledgeIngestionScheduler`

**Файл:** `ingestion/KnowledgeIngestionScheduler.java`.

Периодически захватывал и обрабатывал bounded batch задач.

**Методы/конструкторы (включая private и nested):**

- `конструктор(5 арг.)` (L33): Инициализировал зависимости и проверял параметры создания.
- `poll()` (L47): Последовательно захватывал до batchSize задач и вызывал процессор.

### `StaleIngestionOwnershipException`

**Файл:** `ingestion/StaleIngestionOwnershipException.java`.

Обозначал устаревший processing token или истёкший lease.

**Методы/конструкторы (включая private и nested):**

- `конструктор()` (L5): Инициализировал зависимости и проверял параметры создания.

## 9.12. `model`

Перечисления доступа, видимости, здоровья и состояний ingestion.

### `KnowledgeBaseAccessLevel`

**Файл:** `model/KnowledgeBaseAccessLevel.java`.

Типизированные роли OWNER, EDITOR и VIEWER.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

### `KnowledgeBaseVisibility`

**Файл:** `model/KnowledgeBaseVisibility.java`.

Разделял доступ уровня организации и приглашённых участников.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

### `KnowledgeHealthState`

**Файл:** `model/KnowledgeHealthState.java`.

Перечислял состояния здоровья индекса БЗ.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

### `KnowledgeIngestionStatus`

**Файл:** `model/KnowledgeIngestionStatus.java`.

Перечислял статусы очереди и обработки.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

## 9.13. `ocr`

Распознавание PDF-страниц через внешний OCR-сервис.

### `DisabledKnowledgeOcrProvider`

**Файл:** `ocr/DisabledKnowledgeOcrProvider.java`.

Отказывал в OCR при отключённой интеграции.

**Методы/конструкторы (включая private и nested):**

- `enabled()` (L14): Возвращал состояние включения провайдера/функциональности.
- `extractPdf(byte[])` (L19): Извлекал «pdf» в реализации DisabledKnowledgeOcrProvider.

### `HttpKnowledgeOcrProvider`

**Файл:** `ocr/HttpKnowledgeOcrProvider.java`.

Отправлял PDF в OCR API и валидировал страницы и версию модели.

**Методы/конструкторы (включая private и nested):**

- `конструктор(KnowledgeOcrProperties)` (L36): Инициализировал зависимости и проверял параметры
  создания.
- `enabled()` (L49): Возвращал состояние включения провайдера/функциональности.
- `extractPdf(byte[])` (L54): Проверял размер PDF и вызывал OCR API с bearer-token.
- `parse(JsonNode)` (L133): Проверял ответ, порядок/номера страниц и метаданные модели.
- `stringValue(JsonNode, String)` (L210): Реализовывал шаг «string value» функции
  HttpKnowledgeOcrProvider.
- `pageNumber(JsonNode)` (L221): Извлекал и проверял номер страницы.
- `validModelVersion(String)` (L237): Проверял допустимость версии OCR-модели.
- `invalidResponse()` (L252): Реализовывал шаг «invalid response» функции HttpKnowledgeOcrProvider.

### `KnowledgeOcrProvider`

**Файл:** `ocr/KnowledgeOcrProvider.java`.

Интерфейс извлечения текста страниц через OCR.

**Методы/конструкторы (включая private и nested):**

- `enabled()` (L5): Возвращал состояние включения провайдера/функциональности.
- `extractPdf(byte[])` (L7): Извлекал «pdf» в реализации KnowledgeOcrProvider.

### `OcrDocument`

**Файл:** `ocr/OcrDocument.java`.

Хранил результат OCR с версией модели.
**Record-поля:** `modelVersion`, `pages`.

**Методы/конструкторы (включая private и nested):**

- `конструктор(String, List<OcrPage>)` (L10): Инициализировал зависимости и проверял параметры
  создания.

### `OcrPage`

**Файл:** `ocr/OcrPage.java`.

Описывал страницу OCR с нумерацией и распознанным текстом.
**Record-поля:** `pageNumber`, `text`.

**Методы/конструкторы (включая private и nested):**

- `конструктор(int, String)` (L9): Инициализировал зависимости и проверял параметры создания.

## 9.14. `rag`

Генерация RAG-контекста, цитирование и воспроизводимый паспорт ответа.

### `AnswerPassportService`

**Файл:** `rag/AnswerPassportService.java`.

Транзакционно сохранял и выдавал evidence/persistent citation для ответа.

**Методы/конструкторы (включая private и nested):**

- `конструктор(JdbcTemplate, AuditEventService)` (L34): Инициализировал зависимости и проверял
  параметры создания.
- `persist(6 арг.)` (L46): Встраивал паспорт ответа и citations в транзакцию финализации чата.
- `findByTurn(UUID, SafeAiUserPrincipal)` (L269): Находил «by turn» в реализации
  AnswerPassportService.
- `requireByTurn(UUID, UUID, SafeAiUserPrincipal)` (L330): Требовал существующий passport для
  конкретного turn.
- `findCitations(UUID)` (L397): Находил «citations» в реализации AnswerPassportService.
- `validateCompletion(RagPreparation, RagCompletion)` (L447): Проверял непротиворечивость
  завершённой RAG-операции.
- `sourceIndex(List<KnowledgeContextSource>)` (L472): Реализовывал шаг «source index» функции
  AnswerPassportService.
- `requireSource(Map<String, KnowledgeContextSource>, String)` (L503): Находил цитируемый источник
  либо завершал с ошибкой.
- `normalizeProvider(String)` (L520): Нормализовал «provider» в реализации AnswerPassportService.
- `toResponse(9 арг.)` (L542): Собирал публичное DTO из внутренней модели.
- `toResponse(PassportRow, List<AnswerCitationResponse>)` (L600): Собирал публичное DTO из
  внутренней модели.
- `mapPassport(ResultSet, int)` (L624): Преобразовывал «passport» в реализации
  AnswerPassportService.
- `mapCitation(ResultSet, int)` (L684): Преобразовывал «citation» в реализации
  AnswerPassportService.
- `nullableInteger(ResultSet, String)` (L730): Реализовывал шаг «nullable integer» функции
  AnswerPassportService.

### `KnowledgeChatContext`

**Файл:** `rag/KnowledgeChatContext.java`.

Передавал контекст chat-turn в RAG слой.

**Методы/конструкторы (включая private и nested):**

- `chatId()` (L15): Возвращал идентификатор чата.
- `turnId()` (L17): Возвращал идентификатор текущего turn.
- `modelRouteDecisionId()` (L19): Возвращал идентификатор решения маршрутизации.
- `aiRequest()` (L21): Возвращал подготовленный AI request.
- `knowledgeBaseId()` (L23): Возвращал идентификатор базы знаний.
- `knowledgeMode()` (L25): Возвращал выбранный режим использования знаний.

### `KnowledgeCitationValidator`

**Файл:** `rag/KnowledgeCitationValidator.java`.

Проверял формат и принадлежность цитат конкретному набору источников.

**Методы/конструкторы (включая private и nested):**

- `validate(RagPreparation, AiChatResponse)` (L42): Проверял допустимые [C1]..[C999] и связь с
  materialized sources.
- `parseOrdinal(String)` (L211): Проверял канонический номер цитаты C1–C999.
- `withContent(AiChatResponse, String)` (L238): Возвращал AiChatResponse с заменённым видимым
  текстом.

### `KnowledgeContextAssembler`

**Файл:** `rag/KnowledgeContextAssembler.java`.

Составлял bounded AI-контекст с политикой untrusted sources.

**Методы/конструкторы (включая private и nested):**

- `конструктор(KnowledgeRagProperties)` (L56): Инициализировал зависимости и проверял параметры
  создания.
- `assemble(3 арг.)` (L66): Собирал контекст с untrusted-source policy и input envelope check.
- `largestFittingSourceBlock(7 арг.)` (L224): Подбирал наибольший source-block внутри input
  envelope.
- `materializeRequest(AiChatRequest, String, String, String)` (L364): Собирал фактический AI request
  из system/context исходников.
- `fitsReservedInput(AiChatRequest, Long)` (L393): Оценивал вхождение текста в зарезервированный
  input envelope.
- `requireWithinReservedInput(AiChatRequest, Long)` (L411): Проверял и требовал «within reserved
  input» в реализации KnowledgeContextAssembler.
- `renderSourceBlock(String, KnowledgeRetrievalHit, String)` (L426): Форматировал блок `[C#]` с
  метаданными версии/chunk.
- `sanitizeSourceText(String)` (L464): Обезвреживал похожие на цитаты маркеры в недоверенном тексте.
- `sanitizeMetadata(String)` (L481): Экранировал служебные маркеры и переводы строк в метаданных.
- `truncateCodePoints(String, int)` (L517): Реализовывал шаг «truncate code points» функции
  KnowledgeContextAssembler.
- `page(KnowledgeRetrievalHit)` (L549): Вычислял/возвращал страницу документа или ответа.
- `combine(String[])` (L576): Реализовывал шаг «combine» функции KnowledgeContextAssembler.
- `конструктор` (L603): Инициализировал зависимости и проверял параметры создания.

### `KnowledgeContextSource`

**Файл:** `rag/KnowledgeContextSource.java`.

Связывал метку [C1] и конкретный retrieved hit.
**Record-поля:** `label`, `hit`.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

### `KnowledgeHashing`

**Файл:** `rag/KnowledgeHashing.java`.

Формировал стабильные SHA-256-хеши evidence.

**Методы/конструкторы (включая private и nested):**

- `конструктор()` (L10): Инициализировал зависимости и проверял параметры создания.
- `sha256(String)` (L13): Вычислял SHA-256 от содержимого для проверки целостности.

### `KnowledgeMode`

**Файл:** `rag/KnowledgeMode.java`.

Определял обычный, assisted и knowledge-only режимы.

**Методы/конструкторы (включая private и nested):**

- `usesKnowledge()` (L10): Реализовывал шаг «uses knowledge» функции KnowledgeMode.

### `KnowledgeRagContextReceiptRepository`

**Файл:** `rag/KnowledgeRagContextReceiptRepository.java`.

Сохранял hash prepared RAG-запроса для последующей проверки.

**Методы/конструкторы (включая private и nested):**

- `конструктор(JdbcTemplate)` (L18): Инициализировал зависимости и проверял параметры создания.
- `insert(9 арг.)` (L25): Добавлял проверенную запись в PostgreSQL.

### `KnowledgeRagContextReceiptService`

**Файл:** `rag/KnowledgeRagContextReceiptService.java`.

Фиксировал immutable receipt RAG в отдельной транзакции.

**Методы/конструкторы (включая private и nested):**

- `конструктор(KnowledgeRagContextReceiptRepository, Clock)` (L32): Инициализировал зависимости и
  проверял параметры создания.
- `record(8 арг.)` (L46): Сохранял SHA-256 prepared request в отдельной REQUIRES_NEW транзакции.
- `requirePreparedRequestIdentity(AiChatRequest, UUID, UUID)` (L89): Проверял и требовал «prepared
  request identity» в реализации KnowledgeRagContextReceiptService.
- `validateEmbeddingModel(String)` (L102): Проверял корректность «embedding model» в реализации
  KnowledgeRagContextReceiptService.
- `validateSha256(String, String)` (L116): Проверял корректность «sha256» в реализации
  KnowledgeRagContextReceiptService.

### `KnowledgeRagService`

**Файл:** `rag/KnowledgeRagService.java`.

Координировал retrieval, materialization, receipt и citation validation.

**Методы/конструкторы (включая private и nested):**

- `конструктор(5 арг.)` (L22): Инициализировал зависимости и проверял параметры создания.
- `prepare(KnowledgeChatContext, SafeAiUserPrincipal)` (L51): Проверял chat identity, готовил
  retrieval/context и durable receipt.
- `complete(RagPreparation, AiChatResponse)` (L138): Проверял ответ и оформлял валидированную RAG
  completion.

### `RagCitation`

**Файл:** `rag/RagCitation.java`.

Связывал валидную метку цитирования и UUID chunk.
**Record-поля:** `label`, `ordinal`, `chunkId`.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

### `RagCompletion`

**Файл:** `rag/RagCompletion.java`.

Хранил проверенный результат LLM и набор цитат.
**Record-поля:** `preparation`, `response`, `citations`, `citationsValid`, `evidenceSufficient`.

**Методы/конструкторы (включая private и nested):**

- `конструктор(5 арг.)` (L14): Инициализировал зависимости и проверял параметры создания.
- `general(RagPreparation, AiChatResponse)` (L18): Создавал контекст без обращения к Knowledge
  источникам.

### `RagPreparation`

**Файл:** `rag/RagPreparation.java`.

Хранил подготовленный RAG-запрос, источники и хеши.
**Record-поля:** `mode`, `knowledgeBaseId`, `retrievalRunId`, `embeddingModel`, `contextSha256`,
`sources`, `aiRequest`.

**Методы/конструкторы (включая private и nested):**

- `конструктор(7 арг.)` (L25): Инициализировал зависимости и проверял параметры создания.
- `general(AiChatRequest)` (L61): Создавал контекст без обращения к Knowledge источникам.
- `validateGeneralState(5 арг.)` (L75): Проверял корректность «general state» в реализации
  RagPreparation.
- `validateKnowledgeState(UUID, UUID, String, String)` (L93): Проверял корректность «knowledge
  state» в реализации RagPreparation.
- `requireEmbeddingModel(String)` (L118): Проверял и требовал «embedding model» в реализации
  RagPreparation.
- `requireSha256(String)` (L129): Проверял и требовал «sha256» в реализации RagPreparation.

## 9.15. `repository`

Доступ к данным: tenant-scoped выборки, членство, блокировки, страницы.

### `KnowledgeBaseMembershipRepository`

**Файл:** `repository/KnowledgeBaseMembershipRepository.java`.

Читал/блокировал членство пользователя в БЗ.

**Методы/конструкторы (включая private и nested):**

- `existsByKnowledgeBaseIdAndOrganizationIdAndUserId(3 арг.)` (L19): Реализовывал шаг «exists by
  knowledge base id and organization id and user id» функции KnowledgeBaseMembershipRepository.
- `findByKnowledgeBaseIdAndOrganizationIdAndUserId` (L25): Находил «by knowledge base id and
  organization id and user id» в реализации KnowledgeBaseMembershipRepository.
- `findForUpdate(UUID, UUID, UUID)` (L29): Блокировал найденную строку для конкурентно-безопасного
  изменения.
- `findMemberResponses(UUID, UUID, Pageable)` (L43): Находил «member responses» в реализации
  KnowledgeBaseMembershipRepository.

### `KnowledgeBaseRepository`

**Файл:** `repository/KnowledgeBaseRepository.java`.

Выбирал БЗ с tenant-scoped условиями и блокировками.

**Методы/конструкторы (включая private и nested):**

- `findAllByOrganizationId(UUID, Pageable)` (L19): Находил «all by organization id» в реализации
  KnowledgeBaseRepository.
- `findByIdAndOrganizationId(UUID, UUID)` (L24): Находил «by id and organization id» в реализации
  KnowledgeBaseRepository.
- `findForUpdate(UUID, UUID)` (L29): Блокировал найденную строку для конкурентно-безопасного
  изменения.
- `findVisibleForUser` (L41): Находил «visible for user» в реализации KnowledgeBaseRepository.
- `existsByOrganizationIdAndNameIgnoreCase(UUID, String)` (L65): Реализовывал шаг «exists by
  organization id and name ignore case» функции KnowledgeBaseRepository.
- `existsByOrganizationIdAndNameIgnoreCaseAndIdNot(3 арг.)` (L70): Реализовывал шаг «exists by
  organization id and name ignore case and id not» функции KnowledgeBaseRepository.

### `KnowledgeDocumentQueryRepository`

**Файл:** `repository/KnowledgeDocumentQueryRepository.java`.

Реализовал эффективные списки и проекции документов.

**Методы/конструкторы (включая private и nested):**

- `конструктор(JdbcTemplate)` (L21): Инициализировал зависимости и проверял параметры создания.
- `list(UUID, UUID, int, int, boolean)` (L27): Возвращал страницу ресурсов после проверки параметров
  и доступа.
- `countDocuments(UUID, UUID, String)` (L128): Реализовывал шаг «count documents» функции
  KnowledgeDocumentQueryRepository.
- `map(ResultSet, int)` (L153): Маппил строку/данные во внутреннюю структуру.
- `nullableVersionNumber(ResultSet)` (L214): Реализовывал шаг «nullable version number» функции
  KnowledgeDocumentQueryRepository.
- `sizeBytesOrZero(ResultSet)` (L227): Возвращал размер, подставляя ноль при отсутствии.
- `totalPages(long, int)` (L240): Преобразовывал «tal pages» в реализации
  KnowledgeDocumentQueryRepository.
- `requireValidPage(int, int)` (L261): Проверял и требовал «valid page» в реализации
  KnowledgeDocumentQueryRepository.

### `KnowledgeDocumentRepository`

**Файл:** `repository/KnowledgeDocumentRepository.java`.

Читал и блокировал tenant-scoped документы.

**Методы/конструкторы (включая private и nested):**

- `findByIdAndKnowledgeBaseIdAndOrganizationId(UUID, UUID, UUID)` (L16): Находил «by id and
  knowledge base id and organization id» в реализации KnowledgeDocumentRepository.
- `findForUpdate(UUID, UUID, UUID)` (L33): Блокировал найденную строку для конкурентно-безопасного
  изменения.
- `existsByKnowledgeBaseIdAndOrganizationIdAndNameIgnoreCase` (L50): Реализовывал шаг «exists by
  knowledge base id and organization id and name ignore case» функции KnowledgeDocumentRepository.
- `existsByKnowledgeBaseIdAndOrganizationIdAndNameIgnoreCaseAndIdNot` (L56): Реализовывал шаг
  «exists by knowledge base id and organization id and name ignore case and id not» функции
  KnowledgeDocumentRepository.
- `currentVersionNumber(UUID, UUID, UUID)` (L63): Реализовывал шаг «current version number» функции
  KnowledgeDocumentRepository.

### `KnowledgeDocumentVersionRepository`

**Файл:** `repository/KnowledgeDocumentVersionRepository.java`.

Выбирал неизменяемую версию по составной области доступа.

**Методы/конструкторы (включая private и nested):**

- `findByIdAndDocumentIdAndKnowledgeBaseIdAndOrganizationId` (L14): Находил «by id and document id
  and knowledge base id and organization id» в реализации KnowledgeDocumentVersionRepository.
- `findAllByDocumentIdAndKnowledgeBaseIdAndOrganizationId(4 арг.)` (L22): Находил «all by document
  id and knowledge base id and organization id» в реализации KnowledgeDocumentVersionRepository.

### `KnowledgeIngestionJobRepository`

**Файл:** `repository/KnowledgeIngestionJobRepository.java`.

JPA-доступ к ingest-job.

**Методы/конструкторы (включая private и nested):**

- `findByDocumentVersionIdAndOrganizationId(UUID, UUID)` (L10): Находил «by document version id and
  organization id» в реализации KnowledgeIngestionJobRepository.

### `KnowledgeMemberDirectoryRepository`

**Файл:** `repository/KnowledgeMemberDirectoryRepository.java`.

Подбирал кандидатов в участники из организации.

**Методы/конструкторы (включая private и nested):**

- `search(UUID, String, Pageable)` (L16): Реализовывал шаг «search» функции
  KnowledgeMemberDirectoryRepository.

## 9.16. `retrieval`

Гибридный lexical + vector поиск с RRF.

### `KnowledgeRetrievalExecution`

**Файл:** `retrieval/KnowledgeRetrievalExecution.java`.

Связывал результаты одного поискового запуска и его identity.
**Record-поля:** `retrievalRunId`, `knowledgeBaseId`, `chatTurnId`, `querySha256`, `embeddingModel`,
`completedAt`, `hits`.

**Методы/конструкторы (включая private и nested):**

- `конструктор(7 арг.)` (L16): Инициализировал зависимости и проверял параметры создания.

### `KnowledgeRetrievalHit`

**Файл:** `retrieval/KnowledgeRetrievalHit.java`.

Хранил provenance chunk и rank/score из гибридного поиска.
**Record-поля:** `chunkId`, `documentId`, `documentVersionId`, `documentName`, `versionNumber`,
`chunkOrdinal`, `content`, `pageFrom`, `pageTo`, `heading`, `fusedScore`, `lexicalRank`,
`semanticRank`, `lexicalScore`, `cosineSimilarity`, `contentSha256`.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

### `KnowledgeRetrievalRepository`

**Файл:** `retrieval/KnowledgeRetrievalRepository.java`.

Выполнял tenant-safe hybrid SQL: tsvector + pgvector + RRF.

**Методы/конструкторы (включая private и nested):**

- `конструктор(NamedParameterJdbcTemplate)` (L19): Инициализировал зависимости и проверял параметры
  создания.
- `hybridSearch(10 арг.)` (L25): Делал tenant/ACL-filtered lexical+cosine поиск и RRF top-k.
- `mapHit(ResultSet, int)` (L341): Преобразовывал «hit» в реализации KnowledgeRetrievalRepository.
- `nullableInteger(ResultSet, String)` (L406): Реализовывал шаг «nullable integer» функции
  KnowledgeRetrievalRepository.
- `nullableFloat(ResultSet, String)` (L420): Реализовывал шаг «nullable float» функции
  KnowledgeRetrievalRepository.

## 9.17. `service`

Бизнес-операции: права, загрузка, каталог БЗ, retrieval и evaluation.

### `KnowledgeAccessService`

**Файл:** `service/KnowledgeAccessService.java`.

Вычислял effective ACL с tenant isolation и скрытием MEMBERS БЗ.

**Методы/конструкторы (включая private и nested):**

- `конструктор(2 арг.)` (L26): Инициализировал зависимости и проверял параметры создания.
- `requireAccess` (L34): Проверял tenant, видимость и минимальную роль, возвращал ACL snapshot.
- `atLeast(KnowledgeBaseAccessLevel, KnowledgeBaseAccessLevel)` (L108): Реализовывал шаг «at least»
  функции KnowledgeAccessService.
- `isAdmin(SafeAiUserPrincipal)` (L115): Проверял явную authority ROLE_ADMIN; SUPER_ADMIN здесь
  отдельно не распознаётся.
- `rank(KnowledgeBaseAccessLevel)` (L132): Реализовывал шаг «rank» функции KnowledgeAccessService.
- `notFound()` (L142): Формировал унифицированный not-found ответ без раскрытия tenant.
- `конструктор(3 арг.)` (L153): Инициализировал зависимости и проверял параметры создания.
- `atLeast(KnowledgeBaseAccessLevel)` (L164): Реализовывал шаг «at least» функции
  KnowledgeAccessService.

### `KnowledgeBaseNameNormalizer`

**Файл:** `service/KnowledgeBaseNameNormalizer.java`.

Нормализовал название Knowledge Base.

**Методы/конструкторы (включая private и nested):**

- `конструктор()` (L8): Инициализировал зависимости и проверял параметры создания.
- `normalize(String)` (L11): Канонизировал входное значение по правилам конкретного компонента.

### `KnowledgeBaseService`

**Файл:** `service/KnowledgeBaseService.java`.

Управлял БЗ и членством с блокировками и audit.

**Методы/конструкторы (включая private и nested):**

- `findAll(SafeAiUserPrincipal, int, int)` (L68): Возвращал tenant-scoped список элементов с
  фильтрацией/пагинацией.
- `findById(UUID, SafeAiUserPrincipal)` (L97): Искал ресурс по идентификатору и области доступа.
- `create(CreateKnowledgeBaseRequest, SafeAiUserPrincipal)` (L136): Создавал БЗ под блокировкой
  организации и фиксировал обязательный audit.
- `update(UUID, UpdateKnowledgeBaseRequest, SafeAiUserPrincipal)` (L219): Изменял БЗ с optimistic
  version/DB locking и повторной проверкой прав.
- `findMembers(UUID, SafeAiUserPrincipal, int, int)` (L328): Выбирал участников указанной БЗ с ролью
  и пагинацией.
- `searchMemberCandidates(String, int, SafeAiUserPrincipal)` (L351): Искал «member candidates» в
  реализации KnowledgeBaseService.
- `addMember(3 арг.)` (L387): Добавлял участника после tenant-scoped проверки и защиты от
  конфликтов.
- `updateMember(4 арг.)` (L478): Менял роль участника с ожидаемой версией и Audit.
- `removeMember(UUID, UUID, Long, SafeAiUserPrincipal)` (L550): Удалял участника с проверкой
  полномочий и инвариантов владельца.
- `saveAndRefresh(KnowledgeBaseEntity)` (L606): Сохранял «and refresh» в реализации
  KnowledgeBaseService.
- `saveMembershipAndRefresh(KnowledgeBaseMembershipEntity)` (L616): Сохранял «membership and
  refresh» в реализации KnowledgeBaseService.
- `lockTenantUser(UUID, UUID)` (L626): Блокировал «tenant user» в реализации KnowledgeBaseService.
- `lockEnabledOrganization(UUID)` (L656): Блокировал «enabled organization» в реализации
  KnowledgeBaseService.
- `assertKnowledgeBaseExists(UUID, UUID)` (L680): Проверял физическое существование БЗ в tenant
  scope.
- `requireAdmin(SafeAiUserPrincipal)` (L694): Требовал административную роль в контексте операции.
- `requireDataPlaneUser(SafeAiUserPrincipal)` (L707): Проверял, может ли principal выполнять tenant
  data-plane запрос.
- `hasAuthority(SafeAiUserPrincipal, String)` (L723): Проверял присутствие требуемой роли.
- `knowledgePageRequest(int, int)` (L733): Реализовывал шаг «knowledge page request» функции
  KnowledgeBaseService.
- `memberPageRequest(int, int)` (L749): Реализовывал шаг «member page request» функции
  KnowledgeBaseService.
- `validatePage(int, int)` (L761): Проверял корректность «page» в реализации KnowledgeBaseService.
- `normalizeDescription(String)` (L776): Нормализовал «description» в реализации
  KnowledgeBaseService.
- `requireExpectedVersion(Long, long, String)` (L815): Проверял ожидание optimistic version перед
  записью.
- `toMemberResponse(KnowledgeBaseMembershipEntity, UserEntity)` (L845): Собирал DTO участника БЗ.
- `requireValue(T, String)` (L861): Требовал корректное обязательное значение.
- `duplicateKnowledgeBaseName(String)` (L874): Формировал конфликт повторного имени базы знаний.
- `duplicateMembership(UUID)` (L882): Формировал конфликт повторного назначения членства.
- `knowledgeBaseNotFound(UUID)` (L888): Формировал контролируемый not-found базы знаний.
- `membershipNotFound(UUID)` (L896): Формировал контролируемый not-found участника.

### `KnowledgeDocumentFileValidator`

**Файл:** `service/KnowledgeDocumentFileValidator.java`.

Проверял файл, размер, имя, содержимое и media type.

**Методы/конструкторы (включая private и nested):**

- `конструктор(KnowledgeStorageProperties)` (L41): Инициализировал зависимости и проверял параметры
  создания.
- `конструктор(2 арг.)` (L45): Инициализировал зависимости и проверял параметры создания.
- `validate(MultipartFile)` (L58): Проверял физические байты, лимит, имя, extension, media type и
  SHA-256.
- `конструктор(byte[], String, String, String)` (L106): Инициализировал зависимости и проверял
  параметры создания.
- `bytes()` (L110): Возвращал защищённую копию массива байтов.
- `sizeBytes()` (L115): Возвращал размер содержимого в байтах.
- `openStream()` (L123): Открывал новый InputStream сохранённых байтов.

### `KnowledgeDocumentMediaTypeDetector`

**Файл:** `service/KnowledgeDocumentMediaTypeDetector.java`.

Определял фактический формат по сигнатуре/структуре.

**Методы/конструкторы (включая private и nested):**

- `конструктор()` (L7): Инициализировал зависимости и проверял параметры создания.
- `конструктор(long)` (L11): Инициализировал зависимости и проверял параметры создания.
- `detect(byte[], String)` (L15): Реализовывал шаг «detect» функции
  KnowledgeDocumentMediaTypeDetector.
- `detectBinaryType(byte[])` (L60): Определял «binary type» в реализации
  KnowledgeDocumentMediaTypeDetector.
- `isPdf(byte[])` (L69): Определял «pdf» в реализации KnowledgeDocumentMediaTypeDetector.
- `matchesAt(byte[], byte[], int)` (L95): Реализовывал шаг «matches at» функции
  KnowledgeDocumentMediaTypeDetector.
- `extensionMatches(String, String)` (L108): Реализовывал шаг «extension matches» функции
  KnowledgeDocumentMediaTypeDetector.

### `KnowledgeDocumentMediaTypes`

**Файл:** `service/KnowledgeDocumentMediaTypes.java`.

Задавал разрешённые MIME-константы.

**Методы/конструкторы (включая private и nested):**

- `конструктор()` (L22): Инициализировал зависимости и проверял параметры создания.

### `KnowledgeDocumentNameNormalizer`

**Файл:** `service/KnowledgeDocumentNameNormalizer.java`.

Нормализовал имя документа.

**Методы/конструкторы (включая private и nested):**

- `конструктор()` (L11): Инициализировал зависимости и проверял параметры создания.
- `normalize(String)` (L14): Канонизировал входное значение по правилам конкретного компонента.

### `KnowledgeDocumentService`

**Файл:** `service/KnowledgeDocumentService.java`.

Координировал upload/download, права, версии и S3 journal.

**Методы/конструкторы (включая private и nested):**

- `конструктор(8 арг.)` (L54): Инициализировал зависимости и проверял параметры создания.
- `конструктор(9 арг.)` (L68): Инициализировал зависимости и проверял параметры создания.
- `list(UUID, SafeAiUserPrincipal, int, int)` (L130): Возвращал страницу ресурсов после проверки
  параметров и доступа.
- `listVersions(UUID, UUID, SafeAiUserPrincipal, int, int)` (L168): Возвращал страницу исторических
  версий документа.
- `update(4 арг.)` (L217): Изменял ресурс по API с проверками доступа и версии.
- `uploadNew(UUID, String, MultipartFile, SafeAiUserPrincipal)` (L231): Валидировал upload, создавал
  storage intent и публиковал метаданные.
- `uploadVersion(UUID, UUID, MultipartFile, SafeAiUserPrincipal)` (L311): Создавал следующую версию
  через object storage и DB транзакцию.
- `download(UUID, UUID, UUID, SafeAiUserPrincipal)` (L364): Повторно проверял ACL и версию;
  возвращал resource и фиксировал best-effort audit.
- `requireDocument(UUID, UUID, UUID)` (L468): Проверял и требовал «document» в реализации
  KnowledgeDocumentService.
- `storeUploadedContent(5 арг.)` (L493): Реализовывал шаг «store uploaded content» функции
  KnowledgeDocumentService.
- `putObject` (L524): Сохранял «object» в реализации KnowledgeDocumentService.
- `compensateFailedMetadataPublication(String, RuntimeException)` (L558): Не удалял journaled STORED
  объект вне fenced cleanup; legacy fallback ограничен.
- `deleteObjectQuietly(String, RuntimeException)` (L567): Удалял «object quietly» в реализации
  KnowledgeDocumentService.
- `storageKey(UUID, UUID, UUID, UUID)` (L586): Собирал организационно-изолированный ключ объекта.
- `validatePage(int, int)` (L601): Проверял корректность «page» в реализации
  KnowledgeDocumentService.
- `duplicateDocumentName()` (L620): Формировал конфликт повторного имени документа.
- `documentNotFound()` (L626): Формировал контролируемый not-found документа.
- `versionNotFound()` (L632): Формировал контролируемый not-found версии.
- `конструктор(StoredObject, String, String)` (L644): Инициализировал зависимости и проверял
  параметры создания.

### `KnowledgeDocumentWriteService`

**Файл:** `service/KnowledgeDocumentWriteService.java`.

Атомарно публиковал метаданные документа, job и audit.

**Методы/конструкторы (включая private и nested):**

- `конструктор(5 арг.)` (L43): Инициализировал зависимости и проверял параметры создания.
- `конструктор(6 арг.)` (L53): Инициализировал зависимости и проверял параметры создания.
- `createInitialVersion(7 арг.)` (L70): В одной транзакции создавал документ, версию, job и audit.
- `addVersion(6 арг.)` (L146): В одной транзакции добавлял версию и ingestion job.
- `registerUploadedVersion(4 арг.)` (L226): Реализовывал шаг «register uploaded version» функции
  KnowledgeDocumentWriteService.
- `updateDocument(4 арг.)` (L254): Обновлял свойства документа под проверками доступа и целостности.
- `responseFor(KnowledgeDocumentEntity, SafeAiUserPrincipal)` (L377): Реализовывал шаг «response
  for» функции KnowledgeDocumentWriteService.
- `createVersion(6 арг.)` (L413): Создавал «version» в реализации KnowledgeDocumentWriteService.
- `createIngestionJob(3 арг.)` (L461): Создавал «ingestion job» в реализации
  KnowledgeDocumentWriteService.
- `recordVersionUploaded(3 арг.)` (L490): Записывал Audit о публикации версии.
- `duplicateDocumentName()` (L514): Формировал конфликт повторного имени документа.

### `KnowledgeEvaluationService`

**Файл:** `service/KnowledgeEvaluationService.java`.

Прогонял тестовые retrieval dataset и записывал метрики.

**Методы/конструкторы (включая private и nested):**

- `конструктор(6 арг.)` (L44): Инициализировал зависимости и проверял параметры создания.
- `evaluate(UUID, KnowledgeEvaluationRequest, SafeAiUserPrincipal)` (L63): Проводил dataset
  evaluation и фиксировал recall/MRR/nDCG.
- `validateExpectedVersions(UUID, Set<UUID>, UUID)` (L321): Проверял корректность «expected
  versions» в реализации KnowledgeEvaluationService.
- `normalizeDatasetName(String)` (L370): Нормализовал «dataset name» в реализации
  KnowledgeEvaluationService.
- `average(List<CaseResult>, Metric)` (L407): Вычислял среднее значение метрики на наборе кейсов.

### `KnowledgeNameNormalizerSupport`

**Файл:** `service/KnowledgeNameNormalizerSupport.java`.

Проверял Unicode, controls, пробелы и длину имён.

**Методы/конструкторы (включая private и nested):**

- `конструктор()` (L27): Инициализировал зависимости и проверял параметры создания.
- `normalize(String, String)` (L30): Канонизировал входное значение по правилам конкретного
  компонента.
- `validateNoControlCharacters(String, String)` (L63): Проверял корректность «no control characters»
  в реализации KnowledgeNameNormalizerSupport.
- `normalizeWhitespace(String)` (L79): Нормализовал «whitespace» в реализации
  KnowledgeNameNormalizerSupport.
- `removeTrailingSpace(StringBuilder)` (L121): Удалял «trailing space» в реализации
  KnowledgeNameNormalizerSupport.
- `validateNotBlank(String, String)` (L132): Проверял корректность «not blank» в реализации
  KnowledgeNameNormalizerSupport.
- `validateLength(String, String)` (L144): Проверял корректность «length» в реализации
  KnowledgeNameNormalizerSupport.

### `KnowledgeOoxmlDetector`

**Файл:** `service/KnowledgeOoxmlDetector.java`.

Определял DOCX/PPTX/XLSX после проверки ZIP-структуры.

**Методы/конструкторы (включая private и nested):**

- `конструктор()` (L19): Инициализировал зависимости и проверял параметры создания.
- `конструктор(long)` (L23): Инициализировал зависимости и проверял параметры создания.
- `detect(byte[])` (L40): Реализовывал шаг «detect» функции KnowledgeOoxmlDetector.
- `hasZipLocalHeader(byte[])` (L96): Проверял наличие «zip local header» в реализации
  KnowledgeOoxmlDetector.
- `parseOoxmlMainTypes(byte[])` (L106): Разбирал «ooxml main types» в реализации
  KnowledgeOoxmlDetector.

### `KnowledgeOperationsService`

**Файл:** `service/KnowledgeOperationsService.java`.

Оценивал здоровье БЗ и отправлял версии на reindex.

**Методы/конструкторы (включая private и nested):**

- `конструктор(6 арг.)` (L47): Инициализировал зависимости и проверял параметры создания.
- `health(UUID, SafeAiUserPrincipal)` (L63): Вычислял агрегированное состояние выбранной базы
  знаний.
- `reindex(UUID, UUID, SafeAiUserPrincipal)` (L279): Инициировал повторную обработку текущей версии
  документа.
- `resolveState(KnowledgeAccessService.Access, Counts)` (L398): Реализовывал шаг «resolve state»
  функции KnowledgeOperationsService.
- `mapCounts(ResultSet, int)` (L426): Преобразовывал «counts» в реализации
  KnowledgeOperationsService.
- `mapJob(ResultSet, int)` (L458): Преобразовывал «job» в реализации KnowledgeOperationsService.
- `notFound()` (L473): Формировал унифицированный not-found ответ без раскрытия tenant.

### `KnowledgeRetrievalService`

**Файл:** `service/KnowledgeRetrievalService.java`.

Делал embedding запроса, hybrid search и сохранял run/hits/audit.

**Методы/конструкторы (включая private и nested):**

- `конструктор(8 арг.)` (L51): Инициализировал зависимости и проверял параметры создания.
- `retrieve(UUID, KnowledgeRetrievalRequest, SafeAiUserPrincipal)` (L75): Выполнял ручной retrieval
  с DTO ответа и run ID.
- `retrieveForChat(UUID, UUID, String, int, SafeAiUserPrincipal)` (L121): Выполнял retrieval с
  привязкой к chat-turn для RAG.
- `execute(UUID, String, Integer, UUID, SafeAiUserPrincipal)` (L137): Сверял ACL, embed-ил запрос,
  сохранял hits и обязательный audit.
- `normalizeQuery(String)` (L219): Нормализовал «query» в реализации KnowledgeRetrievalService.
- `validateEmbedding(float[])` (L261): Отбрасывал embedding с неверной размерностью или non-finite.
- `persistRun(9 арг.)` (L296): Записывал run, включая исходный query_text и его SHA-256.
- `persistHits(UUID, UUID, UUID, List<KnowledgeRetrievalHit>)` (L347): Сохранял «hits» в реализации
  KnowledgeRetrievalService.
- `setNullableInteger(PreparedStatement, int, Integer)` (L454): Устанавливал «nullable integer» в
  реализации KnowledgeRetrievalService.
- `setNullableFloat(PreparedStatement, int, Float)` (L472): Устанавливал «nullable float» в
  реализации KnowledgeRetrievalService.
- `sha256(String)` (L490): Вычислял SHA-256 от содержимого для проверки целостности.

### `KnowledgeStructuredTextValidator`

**Файл:** `service/KnowledgeStructuredTextValidator.java`.

Проверял строгие JSON/XML/UTF-8 и управляющие символы.

**Методы/конструкторы (включая private и nested):**

- `конструктор()` (L18): Инициализировал зависимости и проверял параметры создания.
- `decodeStrictUtf8(byte[])` (L21): Декодировал «strict utf8» в реализации
  KnowledgeStructuredTextValidator.
- `validateTextControls(String)` (L36): Отбрасывал запрещённые управляющие символы входного текста.
- `looksLikeHtml(String)` (L49): Реализовывал шаг «looks like html» функции
  KnowledgeStructuredTextValidator.
- `validateJson(String)` (L60): Проверял корректность «json» в реализации
  KnowledgeStructuredTextValidator.
- `validateXml(String)` (L66): Проверял корректность «xml» в реализации
  KnowledgeStructuredTextValidator.
- `containsForbiddenXmlDeclaration(String)` (L104): Проверял наличие запрещённой XML-декларации.
- `secureXmlInputFactory()` (L112): Создавал защищённый XMLInputFactory.
- `closeQuietly(XMLStreamReader)` (L126): Освобождал ресурс, не подменяя исходную ошибку.
- `stripUtf8Bom(String)` (L140): Удалял границы «utf8 bom» в реализации
  KnowledgeStructuredTextValidator.
- `конструктор(String)` (L153): Инициализировал зависимости и проверял параметры создания.
- `parseDocument()` (L160): Разбирал «document» в реализации KnowledgeStructuredTextValidator.
- `parseValue(int)` (L172): Разбирал «value» в реализации KnowledgeStructuredTextValidator.
- `parseObject(int)` (L228): Разбирал «object» в реализации KnowledgeStructuredTextValidator.
- `parseArray(int)` (L280): Разбирал «array» в реализации KnowledgeStructuredTextValidator.
- `parseString()` (L316): Разбирал «string» в реализации KnowledgeStructuredTextValidator.
- `parseEscape()` (L344): Разбирал «escape» в реализации KnowledgeStructuredTextValidator.
- `parseUnicodeEscape()` (L366): Разбирал «unicode escape» в реализации
  KnowledgeStructuredTextValidator.
- `isSimpleEscape(char)` (L383): Определял «simple escape» в реализации
  KnowledgeStructuredTextValidator.
- `parseLiteral(String)` (L396): Разбирал «literal» в реализации KnowledgeStructuredTextValidator.
- `parseNumber()` (L410): Разбирал «number» в реализации KnowledgeStructuredTextValidator.
- `requireDigitOneToNine()` (L484): Проверял и требовал «digit one to nine» в реализации
  KnowledgeStructuredTextValidator.
- `requireDigit()` (L502): Проверял и требовал «digit» в реализации
  KnowledgeStructuredTextValidator.
- `skipWhitespace()` (L515): Реализовывал шаг «skip whitespace» функции
  KnowledgeStructuredTextValidator.
- `consume(char)` (L533): Реализовывал шаг «consume» функции KnowledgeStructuredTextValidator.
- `expect(char)` (L547): Реализовывал шаг «expect» функции KnowledgeStructuredTextValidator.
- `isDigit(char)` (L557): Определял «digit» в реализации KnowledgeStructuredTextValidator.
- `isHex(char)` (L564): Определял «hex» в реализации KnowledgeStructuredTextValidator.
- `fail()` (L575): Реализовывал шаг «fail» функции KnowledgeStructuredTextValidator.

### `KnowledgeUploadFileSupport`

**Файл:** `service/KnowledgeUploadFileSupport.java`.

Безопасно читал MultipartFile, проверял расширение и SHA-256.

**Методы/конструкторы (включая private и nested):**

- `конструктор()` (L32): Инициализировал зависимости и проверял параметры создания.
- `requireNonEmptyFile(MultipartFile)` (L35): Проверял и требовал «non empty file» в реализации
  KnowledgeUploadFileSupport.
- `requireWithinUploadLimit(long, long)` (L45): Проверял и требовал «within upload limit» в
  реализации KnowledgeUploadFileSupport.
- `readFileBytes(MultipartFile)` (L62): Читал «file bytes» в реализации KnowledgeUploadFileSupport.
- `safeFilename(String)` (L75): Формировал безопасное значение «filename» в реализации
  KnowledgeUploadFileSupport.
- `requireSupportedExtension(String)` (L84): Проверял и требовал «supported extension» в реализации
  KnowledgeUploadFileSupport.
- `extension(String)` (L92): Реализовывал шаг «extension» функции KnowledgeUploadFileSupport.
- `sha256(byte[])` (L104): Вычислял SHA-256 от содержимого для проверки целостности.
- `requireOriginalFilename(String)` (L121): Проверял и требовал «original filename» в реализации
  KnowledgeUploadFileSupport.
- `filenameBasename(String)` (L131): Реализовывал шаг «filename basename» функции
  KnowledgeUploadFileSupport.
- `validateFilename(String)` (L140): Проверял корректность «filename» в реализации
  KnowledgeUploadFileSupport.

### `KnowledgeValidationErrors`

**Файл:** `service/KnowledgeValidationErrors.java`.

Формировал стандартные ошибки валидации входных файлов.

**Методы/конструкторы (включая private и nested):**

- `конструктор()` (L10): Инициализировал зависимости и проверял параметры создания.
- `unsupportedType()` (L13): Реализовывал шаг «unsupported type» функции KnowledgeValidationErrors.
- `extensionMismatch()` (L21): Реализовывал шаг «extension mismatch» функции
  KnowledgeValidationErrors.
- `invalidStructuredFormat(String)` (L30): Реализовывал шаг «invalid structured format» функции
  KnowledgeValidationErrors.
- `fileTooLarge(long)` (L39): Реализовывал шаг «file too large» функции KnowledgeValidationErrors.

## 9.18. `storage`

Чтение и запись бинарных объектов в local/S3.

### `KnowledgeStorageConfiguration`

**Файл:** `storage/KnowledgeStorageConfiguration.java`.

Регистрировал настройки object storage.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

### `KnowledgeStorageProductionInvariantVerifier`

**Файл:** `storage/KnowledgeStorageProductionInvariantVerifier.java`.

Требовал S3 и HTTPS в production.

**Методы/конструкторы (включая private и nested):**

- `конструктор(KnowledgeStorageProperties)` (L17): Инициализировал зависимости и проверял параметры
  создания.
- `verify()` (L23): Проверял конфигурационные инварианты при создании компонента.

### `KnowledgeStorageProperties`

**Файл:** `storage/KnowledgeStorageProperties.java`.

Проверял локальные/S3 настройки и скрывал секреты в toString.
**Record-поля:** `type`, `localRoot`, `maxUploadBytes`, `endpoint`, `accessKey`, `secretKey`,
`bucket`.

**Методы/конструкторы (включая private и nested):**

- `конструктор(7 арг.)` (L53): Инициализировал зависимости и проверял параметры создания.
- `toString()` (L129): Маскировал accessKey/secretKey, но оставлял имя endpoint.
- `resolveMaxUploadBytes(Long)` (L143): Реализовывал шаг «resolve max upload bytes» функции
  KnowledgeStorageProperties.
- `normalizeNullable(String)` (L160): Нормализовал «nullable» в реализации
  KnowledgeStorageProperties.
- `requireConfigured(String, String)` (L169): Проверял и требовал «configured» в реализации
  KnowledgeStorageProperties.
- `validateEndpoint(String)` (L183): Проверял корректность «endpoint» в реализации
  KnowledgeStorageProperties.
- `validateBucket(String)` (L233): Проверял корректность «bucket» в реализации
  KnowledgeStorageProperties.
- `invalid(String)` (L252): Формировал исключение для некорректного параметра/ответа.
- `invalidEndpoint(Throwable)` (L262): Реализовывал шаг «invalid endpoint» функции
  KnowledgeStorageProperties.

### `KnowledgeStorageType`

**Файл:** `storage/KnowledgeStorageType.java`.

Выбирал LOCAL/S3 реализацию.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

### `LocalObjectStorage`

**Файл:** `storage/LocalObjectStorage.java`.

Хранил объекты локально для local/test профиля.

**Методы/конструкторы (включая private и nested):**

- `конструктор(KnowledgeStorageProperties)` (L31): Инициализировал зависимости и проверял параметры
  создания.
- `put(String, InputStream)` (L48): Размещал объект в сконфигурированном хранилище.
- `get(String)` (L95): Получал физический или логический ресурс по идентификатору.
- `delete(String)` (L120): Удалял объект с контролем допустимого ключа.
- `moveIntoPlace(Path, Path)` (L131): Реализовывал шаг «move into place» функции LocalObjectStorage.
- `resolve(String)` (L151): Реализовывал шаг «resolve» функции LocalObjectStorage.
- `normalizeKey(String)` (L164): Нормализовал «key» в реализации LocalObjectStorage.
- `resolveInsideRoot(Path)` (L190): Реализовывал шаг «resolve inside root» функции
  LocalObjectStorage.

### `ObjectStorage`

**Файл:** `storage/ObjectStorage.java`.

Единый интерфейс операций put/get/delete.

**Методы/конструкторы (включая private и nested):**

- `put(String, InputStream)` (L7): Размещал объект в сконфигурированном хранилище.
- `get(String)` (L9): Получал физический или логический ресурс по идентификатору.
- `delete(String)` (L11): Удалял объект с контролем допустимого ключа.

### `S3ObjectStorage`

**Файл:** `storage/S3ObjectStorage.java`.

Хранил объекты в MinIO/S3, различал 404 и ошибки сети.

**Методы/конструкторы (включая private и nested):**

- `конструктор(KnowledgeStorageProperties)` (L50): Инициализировал зависимости и проверял параметры
  создания.
- `put(String, InputStream)` (L78): Размещал объект в сконфигурированном хранилище.
- `get(String)` (L113): Получал физический или логический ресурс по идентификатору.
- `delete(String)` (L161): Удалял объект с контролем допустимого ключа.
- `ensureBucketExists()` (L182): Реализовывал шаг «ensure bucket exists» функции S3ObjectStorage.
- `isObjectNotFound(ErrorResponseException)` (L212): Определял «object not found» в реализации
  S3ObjectStorage.
- `requireKey(String)` (L222): Проверял и требовал «key» в реализации S3ObjectStorage.
- `closeClient()` (L232): Закрывал «client» в реализации S3ObjectStorage.

### `StoredObject`

**Файл:** `storage/StoredObject.java`.

Передавал Spring Resource и длину объекта.
**Record-поля:** `resource`, `contentLength`.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

### `KnowledgeStorageReconciliationConfiguration`

**Файл:** `storage/reconciliation/KnowledgeStorageReconciliationConfiguration.java`.

Включал конфигурацию reconciliation.
**Методы:** отсутствуют явные объявления; используются значения record/enum.

### `KnowledgeStorageReconciliationProperties`

**Файл:** `storage/reconciliation/KnowledgeStorageReconciliationProperties.java`.

Валидировал настройки reconciliation scheduler.
**Record-поля:** `enabled`, `pollDelay`.

**Методы/конструкторы (включая private и nested):**

- `конструктор(Boolean, Duration)` (L17): Инициализировал зависимости и проверял параметры создания.

### `KnowledgeStorageReconciliationScheduler`

**Файл:** `storage/reconciliation/KnowledgeStorageReconciliationScheduler.java`.

Карантинировал неоднозначные S3 операции и чистил доказанные orphan.

**Методы/конструкторы (включая private и nested):**

- `конструктор` (L33): Инициализировал зависимости и проверял параметры создания.
- `poll()` (L40): Последовательно выполнял карантин, linked reconciliation и fenced delete.

### `KnowledgeStorageUploadJournal`

**Файл:** `storage/reconciliation/KnowledgeStorageUploadJournal.java`.

Фиксировал durable intent перед PUT и fencing перед cleanup.

**Методы/конструкторы (включая private и nested):**

- `конструктор(JdbcTemplate, PlatformTransactionManager)` (L24): Инициализировал зависимости и
  проверял параметры создания.
- `begin` (L35): Записывал durable INTENT в независимой транзакции перед S3 PUT.
- `stored(UUID, UUID)` (L53): Подтверждал успешный PUT переходом INTENT → STORED.
- `linked(UUID, String, UUID)` (L66): В той же транзакции метаданных связывал STORED → LINKED.
- `quarantineUncertain(Instant, int)` (L79): Отправлял неоднозначные PUT в NEEDS_REVIEW без
  удаления.
- `quarantineExpiredCleanup(Instant, int)` (L95): Отправлял устаревший cleanup в NEEDS_REVIEW вместо
  слепого повтора.
- `reconcileLinked(Instant, int)` (L113): Сверял «linked» в реализации
  KnowledgeStorageUploadJournal.
- `claimStoredOrphan(Instant)` (L136): Взял orphan в cleanup только при доказанном отсутствии
  версии.
- `cleaned(CleanupClaim)` (L165): Фиксировал безопасное завершение очистки после физического DELETE.
- `cleanupUncertain(CleanupClaim)` (L177): Помещал неоднозначный результат DELETE в NEEDS_REVIEW.
- `конструктор(UUID, String, UUID)` (L190): Инициализировал зависимости и проверял параметры
  создания.

## 10. Краткий итог

**Вердикт:** базовая архитектура надёжная по транзакционным инвариантам,
ACL, fencing и происхождению RAG evidence. Для production с чувствительными
документами приоритетен P1-K1, затем heartbeat, качество retrieval, privacy
и эксплуатационный цикл восстановления. Непроверенные пункты требуют тестов,
а не декларации об исправлении.
