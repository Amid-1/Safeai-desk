package ru.safeai.gateway.knowledge.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import ru.safeai.gateway.knowledge.chunking.KnowledgeChunkCandidate;
import ru.safeai.gateway.knowledge.chunking.KnowledgeChunker;
import ru.safeai.gateway.knowledge.config.KnowledgeIngestionProperties;
import ru.safeai.gateway.knowledge.embedding.KnowledgeEmbeddingProvider;
import ru.safeai.gateway.knowledge.entity.KnowledgeDocumentVersionEntity;
import ru.safeai.gateway.knowledge.extraction.ExtractedDocument;
import ru.safeai.gateway.knowledge.extraction.ExtractedSection;
import ru.safeai.gateway.knowledge.extraction.KnowledgeExtractionService;
import ru.safeai.gateway.knowledge.model.KnowledgeIngestionStatus;
import ru.safeai.gateway.knowledge.repository.KnowledgeDocumentVersionRepository;
import ru.safeai.gateway.knowledge.storage.KnowledgeStorageProperties;
import ru.safeai.gateway.knowledge.storage.ObjectStorage;
import ru.safeai.gateway.knowledge.storage.StoredObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static ru.safeai.gateway.knowledge.testsupport.KnowledgeRegressionFixtures.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class KnowledgeIngestionProcessorRegressionTest {
    private static final byte[] PAYLOAD = "Immutable document bytes".getBytes(StandardCharsets.UTF_8);
    private final KnowledgeIngestionClaim claim = new KnowledgeIngestionClaim(
            UUID.randomUUID(), ORG_ID, KB_ID, UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), 1, NOW.plusSeconds(120));
    @Mock
    KnowledgeIngestionQueueRepository queue;
    @Mock
    KnowledgeDocumentVersionRepository versions;
    @Mock
    ObjectStorage storage;
    @Mock
    KnowledgeStorageProperties storageProperties;
    @Mock
    KnowledgeExtractionService extraction;
    @Mock
    KnowledgeChunker chunker;
    @Mock
    KnowledgeEmbeddingProvider embedding;
    @Mock
    KnowledgeChunkPersistenceService persistence;
    @Mock
    KnowledgeIngestionFailureService failure;
    private KnowledgeIngestionProcessor processor;
    private final ExtractedDocument extracted = new ExtractedDocument("test-v1",
            List.of(new ExtractedSection(1, "Heading", "chunk")), 5);

    @BeforeEach
    void setUp() throws IOException {
        var properties = new KnowledgeIngestionProperties(false, Duration.ofSeconds(2), 2,
                Duration.ofMinutes(3), Duration.ofSeconds(30), 1, 3,
                Duration.ofSeconds(10), Duration.ofMinutes(2), 2_000,
                104_857_600L, 200, 20);
        processor = new KnowledgeIngestionProcessor(queue, versions, storage,
                storageProperties, extraction, chunker, embedding, persistence,
                failure, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(storageProperties.maxUploadBytes()).thenReturn(26_214_400L);
        lenient().when(versions.findByIdAndDocumentIdAndKnowledgeBaseIdAndOrganizationId(
                        claim.documentVersionId(), claim.documentId(), claim.knowledgeBaseId(), claim.organizationId()))
                .thenReturn(Optional.of(version()));
        lenient().when(storage.get("document/object")).thenReturn(
                new StoredObject(new ByteArrayResource(PAYLOAD), PAYLOAD.length));
        lenient().when(extraction.extract(eq("text/plain"), any(byte[].class))).thenReturn(extracted);
        lenient().when(chunker.chunk(extracted)).thenReturn(List.of(chunk()));
        lenient().when(embedding.preferredBatchSize()).thenReturn(2);
        lenient().when(embedding.dimensions()).thenReturn(384);
        lenient().when(embedding.embedAll(List.of("chunk"))).thenReturn(List.of(vector()));
    }

    @Test
    void successfulFlowFencesAndValidatesBeforePersistingReadyGeneration() {
        processor.process(claim);
        var order = inOrder(queue, extraction, chunker, embedding, persistence);
        order.verify(queue).renewLease(eq(claim), eq(KnowledgeIngestionStatus.VALIDATING), any(), any());
        order.verify(queue).transition(eq(claim), eq(KnowledgeIngestionStatus.VALIDATING),
                eq(KnowledgeIngestionStatus.EXTRACTING), any(), any());
        order.verify(extraction).extract(eq("text/plain"), aryEq(PAYLOAD));
        order.verify(queue).transition(eq(claim), eq(KnowledgeIngestionStatus.EXTRACTING),
                eq(KnowledgeIngestionStatus.CHUNKING), any(), any());
        order.verify(chunker).chunk(extracted);
        order.verify(queue).renewLease(eq(claim), eq(KnowledgeIngestionStatus.CHUNKING), any(), any());
        order.verify(embedding).embedAll(List.of("chunk"));
        order.verify(persistence).replaceChunksAndComplete(eq(claim), eq(extracted),
                eq(KnowledgeChunker.VERSION), anyList(), eq(NOW));
        verifyNoInteractions(failure);
    }

    @Test
    void missingExactVersionFailsTerminallyBeforeAnyStorageIo() {
        when(versions.findByIdAndDocumentIdAndKnowledgeBaseIdAndOrganizationId(
                claim.documentVersionId(), claim.documentId(), claim.knowledgeBaseId(), claim.organizationId()))
                .thenReturn(Optional.empty());
        processor.process(claim);
        verifyFailure("VERSION_NOT_FOUND", false);
        verifyNoInteractions(storage, persistence);
    }

    @Test
    void mismatchedStoredContentLengthDoesNotReachExtractor() throws Exception {
        when(storage.get("document/object")).thenReturn(
                new StoredObject(new ByteArrayResource(PAYLOAD), PAYLOAD.length + 1L));
        processor.process(claim);
        verifyFailure("STORAGE_SIZE_MISMATCH", false);
        verifyNoInteractions(extraction, persistence);
    }

    @Test
    void mismatchedActualReadLengthDoesNotReachExtractor() throws Exception {
        when(storage.get("document/object")).thenReturn(new StoredObject(
                new ByteArrayResource("other".getBytes(StandardCharsets.UTF_8)), PAYLOAD.length));
        processor.process(claim);
        verifyFailure("STORAGE_SIZE_MISMATCH", false);
        verifyNoInteractions(extraction, persistence);
    }

    @Test
    void sameLengthTamperedObjectFailsCryptographicContentCheck() throws Exception {
        byte[] corrupted = PAYLOAD.clone();
        corrupted[0] ^= 1;
        when(storage.get("document/object")).thenReturn(
                new StoredObject(new ByteArrayResource(corrupted), PAYLOAD.length));
        processor.process(claim);
        verifyFailure("STORAGE_HASH_MISMATCH", false);
        verifyNoInteractions(extraction, persistence);
    }

    @Test
    void invalidImmutableHashMetadataFailsWithoutEmbedding() {
        var bad = version();
        bad.setSha256("not-a-hash");
        when(versions.findByIdAndDocumentIdAndKnowledgeBaseIdAndOrganizationId(
                claim.documentVersionId(), claim.documentId(), claim.knowledgeBaseId(), claim.organizationId()))
                .thenReturn(Optional.of(bad));
        processor.process(claim);
        verifyFailure("STORAGE_HASH_METADATA_INVALID", false);
        verifyNoInteractions(embedding, persistence);
    }

    @Test
    void truncatedEmbeddingBatchIsNotPublished() {
        when(embedding.embedAll(List.of("chunk"))).thenReturn(List.of());
        processor.process(claim);
        verifyFailure("EMBEDDING_BATCH_SIZE_MISMATCH", true);
        verifyNoInteractions(persistence);
    }

    @Test
    void invalidVectorDimensionIsNotPublished() {
        when(embedding.embedAll(List.of("chunk"))).thenReturn(List.of(new float[10]));
        processor.process(claim);
        verifyFailure("EMBEDDING_DIMENSION_MISMATCH", false);
        verifyNoInteractions(persistence);
    }

    @Test
    void zeroAndNonFiniteVectorsAreNotPublished() {
        when(embedding.embedAll(List.of("chunk"))).thenReturn(List.of(new float[384]));
        processor.process(claim);
        verifyFailure("EMBEDDING_INVALID_VECTOR", false);
        reset(failure, persistence);
        float[] nonFinite = vector();
        nonFinite[0] = Float.NaN;
        when(embedding.embedAll(List.of("chunk"))).thenReturn(List.of(nonFinite));
        processor.process(claim);
        verifyFailure("EMBEDDING_INVALID_VECTOR", false);
        verifyNoInteractions(persistence);
    }

    @Test
    void temporaryStorageIOExceptionIsRetryableWithoutLeakingStoragePath() throws Exception {
        when(storage.get("document/object")).thenThrow(new IOException("secret-storage-internals"));
        processor.process(claim);
        verify(failure).recordFailure(eq(claim), eq("STORAGE_UNAVAILABLE"),
                argThat(message -> !message.contains("secret-storage-internals")),
                eq(true), eq(3), eq(NOW), eq(NOW.plusSeconds(10)));
        verifyNoInteractions(persistence);
    }

    @Test
    void staleOwnershipDoesNotScheduleSecondFailureOrPersistChunks() {
        doThrow(new StaleIngestionOwnershipException()).when(queue)
                .renewLease(eq(claim), eq(KnowledgeIngestionStatus.VALIDATING), any(), any());
        processor.process(claim);
        verifyNoInteractions(failure, persistence, storage);
    }

    private void verifyFailure(String code, boolean retryable) {
        verify(failure).recordFailure(eq(claim), eq(code), anyString(), eq(retryable),
                eq(3), eq(NOW), eq(NOW.plusSeconds(10)));
    }

    private KnowledgeDocumentVersionEntity version() {
        var entity = new KnowledgeDocumentVersionEntity();
        entity.setId(claim.documentVersionId());
        entity.setOrganizationId(claim.organizationId());
        entity.setKnowledgeBaseId(claim.knowledgeBaseId());
        entity.setDocumentId(claim.documentId());
        entity.setMediaType("text/plain");
        entity.setStorageKey("document/object");
        entity.setSizeBytes(PAYLOAD.length);
        entity.setSha256(sha256());
        return entity;
    }

    private static KnowledgeChunkCandidate chunk() {
        return new KnowledgeChunkCandidate(0, "chunk", "a".repeat(64), 1, 1, 1, "Heading");
    }

    private static float[] vector() {
        float[] values = new float[384];
        values[0] = 1f;
        return values;
    }

    private static String sha256() {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(PAYLOAD)
            );
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
