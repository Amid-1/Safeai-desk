package ru.safeai.gateway.knowledge.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.knowledge.chunking.KnowledgeChunkCandidate;
import ru.safeai.gateway.knowledge.chunking.KnowledgeChunker;
import ru.safeai.gateway.knowledge.config.KnowledgeIngestionProperties;
import ru.safeai.gateway.knowledge.embedding.KnowledgeEmbeddingException;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

@Service
public class KnowledgeIngestionProcessor {

    private static final Logger log =
            LoggerFactory.getLogger(
                    KnowledgeIngestionProcessor.class
            );

    private static final int EMBEDDING_DIMENSIONS = 384;

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

    public void process(
            KnowledgeIngestionClaim claim
    ) {
        Objects.requireNonNull(
                claim,
                "claim не должен быть null"
        );

        try {
            KnowledgeDocumentVersionEntity version =
                    requireExactVersion(
                            claim
                    );

            renewLease(
                    claim,
                    KnowledgeIngestionStatus.VALIDATING
            );

            byte[] content =
                    readAndValidateObject(
                            version
                    );

            transition(
                    claim,
                    KnowledgeIngestionStatus.VALIDATING,
                    KnowledgeIngestionStatus.EXTRACTING
            );

            ExtractedDocument extracted =
                    extractionService.extract(
                            version.getMediaType(),
                            content
                    );

            transition(
                    claim,
                    KnowledgeIngestionStatus.EXTRACTING,
                    KnowledgeIngestionStatus.CHUNKING
            );

            List<KnowledgeChunkCandidate> candidates =
                    chunker.chunk(
                            extracted
                    );

            List<EmbeddedKnowledgeChunk> chunks =
                    embedWithLeaseRenewal(
                            claim,
                            candidates
                    );

            Instant completedAt =
                    clock.instant();

            renewLeaseAt(
                    claim,
                    KnowledgeIngestionStatus.CHUNKING,
                    completedAt
            );

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
        } catch (KnowledgeEmbeddingException exception) {
            fail(
                    claim,
                    exception.code(),
                    exception.getMessage(),
                    exception.retryable()
            );
        } catch (KnowledgeIngestionException exception) {
            fail(
                    claim,
                    exception.code(),
                    exception.getMessage(),
                    exception.retryable()
            );
        } catch (IOException exception) {
            fail(
                    claim,
                    "STORAGE_UNAVAILABLE",
                    "Объект документа временно недоступен",
                    true
            );
        } catch (RuntimeException exception) {
            log.error(
                    "Unexpected knowledge ingestion failure: jobId={}",
                    claim.jobId(),
                    exception
            );

            fail(
                    claim,
                    "INGESTION_INTERNAL_ERROR",
                    "Внутренняя ошибка обработки документа",
                    true
            );
        }
    }

    private List<EmbeddedKnowledgeChunk> embedWithLeaseRenewal(
            KnowledgeIngestionClaim claim,
            List<KnowledgeChunkCandidate> candidates
    ) {
        int providerBatchSize =
                Math.max(
                        1,
                        embeddingProvider.preferredBatchSize()
                );

        List<EmbeddedKnowledgeChunk> chunks =
                new ArrayList<>(
                        candidates.size()
                );

        for (
                int start = 0;
                start < candidates.size();
                start += providerBatchSize
        ) {
            renewLease(
                    claim,
                    KnowledgeIngestionStatus.CHUNKING
            );

            int end =
                    Math.min(
                            candidates.size(),
                            start + providerBatchSize
                    );

            List<KnowledgeChunkCandidate> batch =
                    candidates.subList(
                            start,
                            end
                    );

            List<float[]> embeddings =
                    embeddingProvider.embedAll(
                            batch.stream()
                                    .map(
                                            KnowledgeChunkCandidate::content
                                    )
                                    .toList()
                    );

            if (embeddings == null
                    || embeddings.size()
                    != batch.size()) {
                throw new KnowledgeIngestionException(
                        "EMBEDDING_BATCH_SIZE_MISMATCH",
                        "Embedding provider вернул неполный batch",
                        true
                );
            }

            for (
                    int index = 0;
                    index < batch.size();
                    index++
            ) {
                chunks.add(
                        embed(
                                batch.get(index),
                                embeddings.get(index)
                        )
                );
            }

            renewLease(
                    claim,
                    KnowledgeIngestionStatus.CHUNKING
            );
        }

        return List.copyOf(
                chunks
        );
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
                .orElseThrow(
                        () -> new KnowledgeIngestionException(
                                "VERSION_NOT_FOUND",
                                "Версия документа не найдена",
                                false
                        )
                );
    }

    private byte[] readAndValidateObject(
            KnowledgeDocumentVersionEntity version
    ) throws IOException {
        int maximum =
                maximumObjectBytes();

        StoredObject stored =
                storage.get(
                        version.getStorageKey()
                );

        validateStoredLength(
                stored,
                version,
                maximum
        );

        byte[] content =
                readContent(
                        stored,
                        maximum
                );

        validateReadLength(
                content,
                version,
                maximum
        );

        validateContentHash(
                content,
                version
        );

        return content;
    }

    private static void validateStoredLength(
            StoredObject stored,
            KnowledgeDocumentVersionEntity version,
            int maximum
    ) {
        long contentLength =
                stored.contentLength();

        if (contentLength
                != version.getSizeBytes()
                || contentLength < 1
                || contentLength > maximum) {
            throw new KnowledgeIngestionException(
                    "STORAGE_SIZE_MISMATCH",
                    "Размер объекта не совпадает с immutable metadata",
                    false
            );
        }
    }

    private static byte[] readContent(
            StoredObject stored,
            int maximum
    ) throws IOException {
        try (
                InputStream input =
                        stored.resource()
                                .getInputStream()
        ) {
            return input.readNBytes(
                    maximum + 1
            );
        }
    }

    private static void validateReadLength(
            byte[] content,
            KnowledgeDocumentVersionEntity version,
            int maximum
    ) {
        if (content.length
                != version.getSizeBytes()
                || content.length > maximum) {
            throw new KnowledgeIngestionException(
                    "STORAGE_SIZE_MISMATCH",
                    "Фактический размер объекта не совпадает с metadata",
                    false
            );
        }
    }

    private static void validateContentHash(
            byte[] content,
            KnowledgeDocumentVersionEntity version
    ) {
        byte[] actualHash =
                sha256Bytes(
                        content
                );

        byte[] expectedHash =
                decodeExpectedSha256(
                        version.getSha256()
                );

        if (!MessageDigest.isEqual(
                actualHash,
                expectedHash
        )) {
            throw new KnowledgeIngestionException(
                    "STORAGE_HASH_MISMATCH",
                    "SHA-256 объекта не совпадает с immutable metadata",
                    false
            );
        }
    }

    private int maximumObjectBytes() {
        long configuredMaximum =
                storageProperties.maxUploadBytes();

        if (configuredMaximum <= 0
                || configuredMaximum
                >= Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "Upload limit is outside supported in-memory range"
            );
        }

        return Math.toIntExact(
                configuredMaximum
        );
    }

    private EmbeddedKnowledgeChunk embed(
            KnowledgeChunkCandidate chunk,
            float[] embedding
    ) {
        if (embedding == null
                || embedding.length
                != embeddingProvider.dimensions()
                || embedding.length
                != EMBEDDING_DIMENSIONS) {
            throw new KnowledgeIngestionException(
                    "EMBEDDING_DIMENSION_MISMATCH",
                    "Embedding provider вернул неверную размерность",
                    false
            );
        }

        double squareSum = 0.0;

        for (float value : embedding) {
            if (!Float.isFinite(value)) {
                throw new KnowledgeIngestionException(
                        "EMBEDDING_INVALID_VECTOR",
                        "Embedding содержит non-finite значение",
                        false
                );
            }

            squareSum +=
                    (double) value
                            * value;
        }

        if (squareSum == 0.0
                || !Double.isFinite(squareSum)) {
            throw new KnowledgeIngestionException(
                    "EMBEDDING_INVALID_VECTOR",
                    "Embedding provider вернул нулевой vector",
                    false
            );
        }

        return new EmbeddedKnowledgeChunk(
                chunk,
                embedding
        );
    }

    private void transition(
            KnowledgeIngestionClaim claim,
            KnowledgeIngestionStatus expected,
            KnowledgeIngestionStatus target
    ) {
        Instant now =
                clock.instant();

        queue.transition(
                claim,
                expected,
                target,
                now,
                now.plus(
                        properties.processingLease()
                )
        );
    }

    private void renewLease(
            KnowledgeIngestionClaim claim,
            KnowledgeIngestionStatus status
    ) {
        renewLeaseAt(
                claim,
                status,
                clock.instant()
        );
    }

    private void renewLeaseAt(
            KnowledgeIngestionClaim claim,
            KnowledgeIngestionStatus status,
            Instant now
    ) {
        queue.renewLease(
                claim,
                status,
                now,
                now.plus(
                        properties.processingLease()
                )
        );
    }

    private void fail(
            KnowledgeIngestionClaim claim,
            String code,
            String message,
            boolean retryable
    ) {
        Instant now =
                clock.instant();

        try {
            failureService.recordFailure(
                    claim,
                    code,
                    message,
                    retryable,
                    properties.maxAttempts(),
                    now,
                    now.plus(
                            properties.backoffForAttempt(
                                    claim.attempt()
                            )
                    )
            );
        } catch (StaleIngestionOwnershipException exception) {
            log.info(
                    "Knowledge ingestion failure ignored after ownership loss: jobId={}",
                    claim.jobId()
            );
        }
    }

    private static byte[] sha256Bytes(
            byte[] content
    ) {
        try {
            return MessageDigest
                    .getInstance(
                            "SHA-256"
                    )
                    .digest(
                            content
                    );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception
            );
        }
    }

    private static byte[] decodeExpectedSha256(
            String value
    ) {
        if (value == null
                || value.length() != 64) {
            throw new KnowledgeIngestionException(
                    "STORAGE_HASH_METADATA_INVALID",
                    "Immutable SHA-256 metadata некорректна",
                    false
            );
        }

        try {
            return HexFormat.of()
                    .parseHex(
                            value
                    );
        } catch (IllegalArgumentException exception) {
            throw new KnowledgeIngestionException(
                    "STORAGE_HASH_METADATA_INVALID",
                    "Immutable SHA-256 metadata некорректна",
                    false,
                    exception
            );
        }
    }
}