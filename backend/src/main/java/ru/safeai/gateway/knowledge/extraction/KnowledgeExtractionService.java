package ru.safeai.gateway.knowledge.extraction;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.knowledge.config.KnowledgeIngestionProperties;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionException;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class KnowledgeExtractionService {

    private final List<KnowledgeDocumentExtractor> extractors;
    private final KnowledgeIngestionProperties properties;
    private final ExecutorService executor;

    public KnowledgeExtractionService(
            List<KnowledgeDocumentExtractor> extractors,
            KnowledgeIngestionProperties properties
    ) {
        this.extractors = List.copyOf(extractors);
        this.properties = properties;
        this.executor = Executors.newFixedThreadPool(
                properties.extractionThreads(),
                threadFactory()
        );
    }

    public ExtractedDocument extract(String mediaType, byte[] content) {
        KnowledgeDocumentExtractor extractor = extractors.stream()
                .filter(candidate -> candidate.supports(mediaType))
                .findFirst()
                .orElseThrow(() -> new KnowledgeIngestionException(
                        "UNSUPPORTED_MEDIA_TYPE",
                        "Формат документа не поддерживается: " + mediaType,
                        false
                ));

        Future<ExtractedDocument> future = executor.submit(
                () -> extractor.extract(content)
        );
        try {
            return future.get(
                    properties.extractionTimeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new KnowledgeIngestionException(
                    "EXTRACTION_TIMEOUT",
                    "Превышен лимит времени извлечения текста",
                    true,
                    exception
            );
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new KnowledgeIngestionException(
                    "EXTRACTION_INTERRUPTED",
                    "Извлечение текста прервано",
                    true,
                    exception
            );
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof KnowledgeIngestionException ingestion) {
                throw ingestion;
            }
            throw new KnowledgeIngestionException(
                    "EXTRACTION_FAILED",
                    "Не удалось извлечь текст документа",
                    false,
                    cause
            );
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static ThreadFactory threadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(
                    task,
                    "knowledge-extraction-" + sequence.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        };
    }
}
