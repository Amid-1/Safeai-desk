package ru.safeai.gateway.knowledge.ingestion;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ru.safeai.gateway.knowledge.chunking.KnowledgeChunker;
import ru.safeai.gateway.knowledge.config.KnowledgeIngestionProperties;
import ru.safeai.gateway.knowledge.embedding.KnowledgeEmbeddingProvider;
import ru.safeai.gateway.knowledge.extraction.KnowledgeExtractionService;
import ru.safeai.gateway.knowledge.repository.KnowledgeDocumentVersionRepository;
import ru.safeai.gateway.knowledge.storage.KnowledgeStorageProperties;
import ru.safeai.gateway.knowledge.storage.ObjectStorage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeIngestionSafeLoggingTest {
    @Test
    void exceptionMessageAndCauseNeverAppearInIngestionLog() {
        KnowledgeIngestionQueueRepository queue = mock(KnowledgeIngestionQueueRepository.class);
        KnowledgeDocumentVersionRepository versions =
                mock(KnowledgeDocumentVersionRepository.class);
        KnowledgeIngestionFailureService failures = mock(KnowledgeIngestionFailureService.class);
        KnowledgeIngestionProperties properties = mock(KnowledgeIngestionProperties.class);
        when(properties.processingLease()).thenReturn(Duration.ofMinutes(3));
        when(properties.maxAttempts()).thenReturn(5);
        when(properties.backoffForAttempt(1)).thenReturn(Duration.ofSeconds(10));
        UUID jobId = UUID.randomUUID();
        KnowledgeIngestionClaim claim = new KnowledgeIngestionClaim(
                jobId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1,
                Instant.parse("2026-10-17T13:03:00Z"));
        when(versions.findByIdAndDocumentIdAndKnowledgeBaseIdAndOrganizationId(
                any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("SECRET_PII_MARKER"));
        KnowledgeIngestionProcessor processor = new KnowledgeIngestionProcessor(
                queue, versions, mock(ObjectStorage.class),
                mock(KnowledgeStorageProperties.class),
                mock(KnowledgeExtractionService.class), mock(KnowledgeChunker.class),
                mock(KnowledgeEmbeddingProvider.class),
                mock(KnowledgeChunkPersistenceService.class), failures, properties,
                Clock.fixed(Instant.parse("2026-10-17T13:00:00Z"), ZoneOffset.UTC));
        Logger logger = (Logger) LoggerFactory.getLogger(KnowledgeIngestionProcessor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            processor.process(claim);
            assertThat(appender.list).isNotEmpty();
            for (ILoggingEvent event : appender.list) {
                assertThat(event.getFormattedMessage()).doesNotContain("SECRET_PII_MARKER");
                assertThat(event.getThrowableProxy()).isNull();
            }
        } finally {
            logger.detachAppender(appender);
            processor.shutdownHeartbeats();
        }
    }
}
