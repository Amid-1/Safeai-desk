# Тесты Knowledge

[Архитектура, API и конфигурация модуля](../../../../../../main/java/ru/safeai/gateway/knowledge/README.md).

Тестовый пакет покрывает права доступа, загрузку и версионирование документов, очередь ingestion,
публикацию индекса, поиск, RAG, паспорт ответа, физическое удаление и восстановление.

## Структура тестов

```text
knowledge/
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
│   ├── KnowledgeErasureTerminalAuditIntegrationTest.java
│   ├── KnowledgeGenerationLifecycleIntegrationTest.java
│   ├── KnowledgeGenerationUpgradeIntegrationTest.java
│   ├── KnowledgeMigrationIntegrityIntegrationTest.java
│   ├── KnowledgePersistenceIntegrationTest.java
│   ├── KnowledgeRagErasureGuardIntegrationTest.java
│   └── KnowledgeS3EndToEndIntegrationTest.java
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
├── testsupport/
│   ├── KnowledgeErasureTestJournal.java
│   └── KnowledgeRegressionFixtures.java
└── README.md
```

## Покрытие по компонентам

### Доступ, документы и индекс

- MVC и DTO проверяют аутентификацию, роли, multipart, валидацию и контракты ответов.
- `KnowledgeAccessControlIntegrationTest` проверяет изоляцию организаций, видимость базы и членство.
- `KnowledgeBaseConcurrencyIntegrationTest` и `KnowledgeDocumentVersionConcurrencyIntegrationTest`
  проверяют конкурентные изменения базы и версий документа.
- Тесты extraction, OCR и chunking проверяют форматы файлов, структурные фрагменты и ошибки провайдера.
  `KnowledgeOoxmlUploadLimitRegressionTest` проверяет ограничения обработки OOXML.
- `KnowledgeIngestionQueueIntegrationTest` проверяет очередь на PostgreSQL.
  `KnowledgeIngestionProcessorRegressionTest` и `KnowledgeIngestionSafeLoggingTest`
  проверяют обработку заданий и безопасное логирование.
- `KnowledgeChunkPublicationIntegrationTest`, `KnowledgeGenerationLifecycleIntegrationTest`
  и `KnowledgeGenerationUpgradeIntegrationTest` проверяют публикацию и миграцию поколений индекса.

### Поиск, RAG и V85

- `KnowledgeRetrievalRepositoryIntegrationTest` проверяет реальный запрос к PostgreSQL.
  Тесты `KnowledgeRetrievalMetrics*` проверяют расчёт метрик поиска.
- Тесты контекста, цитат и паспорта проверяют допустимые источники, неверные ссылки и лимиты запроса.
- `KnowledgeRagGovernedEnvelopeTest` проверяет бюджет контекста, выделенный Chat.
- `KnowledgeRagV85SchemaIntegrationTest` проверяет таблицы, колонки и включённые триггеры.
- `KnowledgeRagErasureGuardIntegrationTest` проверяет отзыв разрешений при удалении, активную аренду,
  запрет новой попытки, привязку разрешения к ходу и финализацию. Отдельно проверяется пустой результат поиска.
- `KnowledgeRagExpiredLeaseRecoveryPolicyTest` проверяет решение об обработке истёкшей аренды.

### Хранилище и физическое удаление

- Тесты Local и S3 проверяют адаптеры, ограничения production-конфигурации и сверку загрузок.
- `KnowledgeS3EndToEndIntegrationTest` проверяет загрузку и чтение с PostgreSQL и MinIO.
- Тесты документа покрывают приём операции, исполнителя и удаление объектов в MinIO.
  `S3VerifiedKnowledgeObjectPurgeIntegrationTest` проверяет удаление версий объекта.
- Тесты базы покрывают удаление базы с дочерними операциями, Object Lock, конкурентное восстановление
  и перезапуск после остановки отдельной JVM. `KnowledgeDeletionWorkerProcessMain` запускает тестовый воркер.
- `KnowledgeDeletionOperatorSafetyIntegrationTest` проверяет ограничения операторского восстановления.
  Тесты доказательств удаления и terminal audit проверяют сохранение истории операции.

## Запуск

Из корня репозитория с Java 21:

```bash
cd backend

# Модульные и MVC-проверки без Docker
./mvnw test '-Dtest=ru.safeai.gateway.knowledge.**.*Test,!**/*IntegrationTest'

# Весь пакет Knowledge, включая PostgreSQL и MinIO
./mvnw test '-Dtest=ru.safeai.gateway.knowledge.**.*Test'

# Полная проверка backend
./mvnw clean verify
```

Для второй и третьей команд запустите Docker. Testcontainers поднимает изолированные PostgreSQL
с pgvector и MinIO для сценариев, которым они нужны. При оценке результата проверяйте также
`target/surefire-reports` и причины пропусков. Для отдельного запуска схемы V85:

```bash
./mvnw test -Dtest=KnowledgeRagV85SchemaIntegrationTest,KnowledgeRagErasureGuardIntegrationTest
```

## Приёмка на стенде

1. Проверить миграции V1–V85 на чистой БД и обновление копии существующей схемы.
2. Проверить RAG на синтетическом корпусе. Включить обычный PDF, скан с OCR, новую версию,
   вопрос без подтверждённого ответа, закрытую базу и пользователя другой организации.
   Для найденного ответа сверять текст, источники и версии документов.
3. Измерить время обработки и пиковое использование памяти для предельных PDF и OOXML.
4. На целевом S3 отдельно проверить удаление документа и базы, версии объектов, Object Lock,
   частичный сбой, повтор после перезапуска, работу двух реплик и восстановление из резервной копии.
5. Проверить гонки с RAG до отправки провайдеру и после физического старта. Учитывать отдельно
   сведения, которые хранятся у внешнего провайдера, в аудите и резервных копиях.
