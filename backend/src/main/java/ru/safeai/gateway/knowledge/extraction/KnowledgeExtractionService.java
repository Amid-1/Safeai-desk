package ru.safeai.gateway.knowledge.extraction;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.knowledge.config.KnowledgeIngestionProperties;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionException;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class KnowledgeExtractionService {

    private final List<KnowledgeDocumentExtractor> extractors;
    private final KnowledgeIngestionProperties properties;
    private final ThreadPoolExecutor executor;

    public KnowledgeExtractionService(
            List<KnowledgeDocumentExtractor> extractors,
            KnowledgeIngestionProperties properties
    ) {
        Objects.requireNonNull(
                extractors,
                "extractors не должен быть null"
        );

        this.properties =
                Objects.requireNonNull(
                        properties,
                        "properties не должен быть null"
                );

        this.extractors =
                List.copyOf(
                        extractors
                );

        int threads =
                properties.extractionThreads();

        if (threads <= 0) {
            throw new IllegalStateException(
                    "extractionThreads должен быть положительным"
            );
        }

        /*
         * Bounded backpressure boundary.
         *
         * Максимум:
         *
         * N active parser jobs
         * +
         * N queued parser jobs
         *
         * После заполнения bounded queue новый submit получает
         * RejectedExecutionException и преобразуется в retryable
         * EXTRACTION_SATURATED.
         *
         * Executors.newFixedThreadPool() здесь намеренно не используется,
         * поскольку он создаёт unbounded LinkedBlockingQueue.
         */
        this.executor =
                new ThreadPoolExecutor(
                        threads,
                        threads,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(
                                threads
                        ),
                        threadFactory(),
                        new ThreadPoolExecutor.AbortPolicy()
                );
    }

    public ExtractedDocument extract(
            String mediaType,
            byte[] content
    ) {
        Objects.requireNonNull(
                mediaType,
                "mediaType не должен быть null"
        );

        Objects.requireNonNull(
                content,
                "content не должен быть null"
        );

        KnowledgeDocumentExtractor extractor =
                extractors.stream()
                        .filter(
                                candidate ->
                                        candidate.supports(
                                                mediaType
                                        )
                        )
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new KnowledgeIngestionException(
                                                "UNSUPPORTED_MEDIA_TYPE",
                                                "Формат документа не поддерживается: "
                                                        + mediaType,
                                                false
                                        )
                        );

        final Future<ExtractedDocument> future;

        try {
            future =
                    executor.submit(
                            () ->
                                    extractor.extract(
                                            content
                                    )
                    );
        } catch (RejectedExecutionException exception) {
            throw new KnowledgeIngestionException(
                    "EXTRACTION_SATURATED",
                    "Пул извлечения текста временно перегружен",
                    true,
                    exception
            );
        }

        try {
            return future.get(
                    properties.extractionTimeout()
                            .toMillis(),
                    TimeUnit.MILLISECONDS
            );

        } catch (TimeoutException exception) {
            cancelAndRemove(
                    future
            );

            throw new KnowledgeIngestionException(
                    "EXTRACTION_TIMEOUT",
                    "Превышен лимит времени извлечения текста",
                    true,
                    exception
            );

        } catch (InterruptedException exception) {
            cancelAndRemove(
                    future
            );

            Thread.currentThread()
                    .interrupt();

            throw new KnowledgeIngestionException(
                    "EXTRACTION_INTERRUPTED",
                    "Извлечение текста прервано",
                    true,
                    exception
            );

        } catch (ExecutionException exception) {
            Throwable cause =
                    exception.getCause();

            if (cause
                    instanceof KnowledgeIngestionException ingestion) {
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

    /**
     * Attempts to cancel the extraction task and removes a still-queued
     * cancelled task from the bounded executor queue.
     *
     * <p>Thread interruption is not a hard process boundary. A parser that
     * ignores interruption can continue executing. Full isolation requires
     * a separate worker process/container.</p>
     */
    private void cancelAndRemove(
            Future<ExtractedDocument> future
    ) {
        future.cancel(
                true
        );

        if (future
                instanceof Runnable runnable) {
            executor.remove(
                    runnable
            );
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static ThreadFactory threadFactory() {
        AtomicInteger sequence =
                new AtomicInteger();

        return task -> {
            Thread thread =
                    new Thread(
                            task,
                            "knowledge-extraction-"
                                    + sequence.incrementAndGet()
                    );

            thread.setDaemon(
                    true
            );

            thread.setUncaughtExceptionHandler(
                    (
                            ignoredThread,
                            ignoredException
                    ) -> {
                        /*
                         * Exceptions from submitted jobs are normally captured
                         * by Future and surfaced through ExecutionException.
                         */
                    }
            );

            return thread;
        };
    }
}