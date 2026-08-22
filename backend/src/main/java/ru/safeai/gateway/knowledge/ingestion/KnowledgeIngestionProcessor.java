package ru.safeai.gateway.knowledge.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.knowledge.chunking.KnowledgeChunkCandidate;
import ru.safeai.gateway.knowledge.chunking.KnowledgeChunker;
import ru.safeai.gateway.knowledge.config.KnowledgeIngestionProperties;
import ru.safeai.gateway.knowledge.embedding.KnowledgeEmbeddingProvider;
import ru.safeai.gateway.knowledge.entity.KnowledgeDocumentVersionEntity;
import ru.safeai.gateway.knowledge.extraction.ExtractedDocument;
import ru.safeai.gateway.knowledge.extraction.KnowledgeExtractionService;
import ru.safeai.gateway.knowledge.model.KnowledgeIngestionStatus;
import ru.safeai.gateway.knowledge.repository.KnowledgeDocumentVersionRepository;
import ru.safeai.gateway.knowledge.storage.KnowledgeStorageProperties;
import ru.safeai.gateway.knowledge.storage.ObjectStorage;
import ru.safeai.gateway.knowledge.storage.StoredObject;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Service
public class KnowledgeIngestionProcessor {

    private static final Logger log = LoggerFactory.getLogger(
            KnowledgeIngestionProcessor.class
    );

    private final KnowledgeIngestionQueueRepository queue;
    private final KnowledgeDocumentVersionRepository versions;
    private final ObjectStorage storage;
    private final KnowledgeStorageProperties storageProperties;
    private final KnowledgeExtractionService extractionService;
    private final KnowledgeChunker chunker;
    private final KnowledgeEmbeddingProvider embeddingProvider;
    private final KnowledgeChunkPersistenceService persistence;
    private final KnowledgeIngestionFailureService failureService;
    private final KnowledgeIngestionProperties properties;
    private final Clock clock;

    public KnowledgeIngestionProcessor(
            KnowledgeIngestionQueueRepository queue,
            KnowledgeDocumentVersionRepository versions,
            ObjectStorage storage,
            KnowledgeStorageProperties storageProperties,
            KnowledgeExtractionService extractionService,
            KnowledgeChunker chunker,
            KnowledgeEmbeddingProvider embeddingProvider,
            KnowledgeChunkPersistenceService persistence,
            KnowledgeIngestionFailureService failureService,
            KnowledgeIngestionProperties properties,
            Clock clock
    ) {
        this.queue = queue;
        this.versions = versions;
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.extractionService = extractionService;
        this.chunker = chunker;
        this.embeddingProvider = embeddingProvider;
        this.persistence = persistence;
        this.failureService = failureService;
        this.properties = properties;
        this.clock = clock;
    }

    public void process(KnowledgeIngestionClaim claim) {
        try {
            KnowledgeDocumentVersionEntity version = requireExactVersion(claim);
            byte[] content = readAndValidateObject(version);

            transition(
                    claim,
                    KnowledgeIngestionStatus.VALIDATING,
                    KnowledgeIngestionStatus.EXTRACTING
            );
            ExtractedDocument extracted = extractionService.extract(
                    version.getMediaType(),
                    content
            );

            transition(
                    claim,
                    KnowledgeIngestionStatus.EXTRACTING,
                    KnowledgeIngestionStatus.CHUNKING
            );
            List<KnowledgeChunkCandidate> candidates = chunker.chunk(extracted);
            List<float[]> embeddings = embeddingProvider.embedAll(
                    candidates.stream()
                            .map(KnowledgeChunkCandidate::content)
                            .toList()
            );
            if (embeddings.size() != candidates.size()) {
                throw new KnowledgeIngestionException(
                        "EMBEDDING_BATCH_SIZE_MISMATCH",
                        "Embedding provider вернул неполный batch",
                        true
                );
            }
            List<EmbeddedKnowledgeChunk> chunks =
                    java.util.stream.IntStream.range(0, candidates.size())
                            .mapToObj(index -> embed(
                                    candidates.get(index),
                                    embeddings.get(index)
                            ))
                            .toList();

            Instant completedAt = clock.instant();
            persistence.replaceChunksAndComplete(
                    claim,
                    extracted,
                    KnowledgeChunker.VERSION,
                    chunks,
                    completedAt
            );
        } catch (StaleIngestionOwnershipException exception) {
            log.info(
                    "Knowledge ingestion ownership expired: jobId={}, token={}",
                    claim.jobId(),
                    claim.processingToken()
            );
        } catch (KnowledgeIngestionException exception) {
            fail(claim, exception.code(), exception.getMessage(),
                    exception.retryable());
        } catch (IOException exception) {
            fail(claim, "STORAGE_UNAVAILABLE",
                    "Объект документа временно недоступен", true);
        } catch (RuntimeException exception) {
            log.error(
                    "Unexpected knowledge ingestion failure: jobId={}",
                    claim.jobId(),
                    exception
            );
            fail(claim, "INGESTION_INTERNAL_ERROR",
                    "Внутренняя ошибка обработки документа", true);
        }
    }

    private KnowledgeDocumentVersionEntity requireExactVersion(
            KnowledgeIngestionClaim claim
    ) {
        return versions
                .findByIdAndDocumentIdAndKnowledgeBaseIdAndOrganizationId(
                        claim.documentVersionId(),
                        claim.documentId(),
                        claim.knowledgeBaseId(),
                        claim.organizationId()
                )
                .orElseThrow(() -> new KnowledgeIngestionException(
                        "VERSION_NOT_FOUND",
                        "Версия документа не найдена",
                        false
                ));
    }

    private byte[] readAndValidateObject(
            KnowledgeDocumentVersionEntity version
    ) throws IOException {
        StoredObject stored = storage.get(version.getStorageKey());
        int maximum = maximumObjectBytes();
        if (stored.contentLength() != version.getSizeBytes()
                || stored.contentLength() < 1
                || stored.contentLength() > maximum) {
            throw new KnowledgeIngestionException(
                    "STORAGE_SIZE_MISMATCH",
                    "Размер объекта не совпадает с immutable metadata",
                    false
            );
        }
        byte[] content;
        try (InputStream input = stored.resource().getInputStream()) {
            content = input.readNBytes(maximum + 1);
        }
        if (content.length != version.getSizeBytes()
                || content.length > maximum) {
            throw new KnowledgeIngestionException(
                    "STORAGE_SIZE_MISMATCH",
                    "Фактический размер объекта не совпадает с metadata",
                    false
            );
        }
        if (!MessageDigest.isEqual(
                sha256(content).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                version.getSha256().getBytes(
                        java.nio.charset.StandardCharsets.US_ASCII
                )
        )) {
            throw new KnowledgeIngestionException(
                    "STORAGE_HASH_MISMATCH",
                    "SHA-256 объекта не совпадает с immutable metadata",
                    false
            );
        }
        return content;
    }

    private int maximumObjectBytes() {
        long configuredMaximum = storageProperties.maxUploadBytes();
        if (configuredMaximum >= Integer.MAX_VALUE) {
            throw new IllegalStateException("Upload limit is too large");
        }
        return Math.toIntExact(configuredMaximum);
    }

    private EmbeddedKnowledgeChunk embed(
            KnowledgeChunkCandidate chunk,
            float[] embedding
    ) {
        if (embedding.length != embeddingProvider.dimensions()
                || embedding.length != 384) {
            throw new KnowledgeIngestionException(
                    "EMBEDDING_DIMENSION_MISMATCH",
                    "Embedding provider вернул неверную размерность",
                    false
            );
        }
        return new EmbeddedKnowledgeChunk(chunk, embedding);
    }

    private void transition(
            KnowledgeIngestionClaim claim,
            KnowledgeIngestionStatus expected,
            KnowledgeIngestionStatus target
    ) {
        Instant now = clock.instant();
        queue.transition(
                claim,
                expected,
                target,
                now,
                now.plus(properties.processingLease())
        );
    }

    private void fail(
            KnowledgeIngestionClaim claim,
            String code,
            String message,
            boolean retryable
    ) {
        Instant now = clock.instant();
        try {
            failureService.recordFailure(
                    claim,
                    code,
                    message,
                    retryable,
                    properties.maxAttempts(),
                    now,
                    now.plus(properties.backoffForAttempt(claim.attempt()))
            );
        } catch (StaleIngestionOwnershipException exception) {
            log.info(
                    "Knowledge ingestion failure ignored after ownership loss: jobId={}",
                    claim.jobId()
            );
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
