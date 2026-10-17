package ru.safeai.gateway.chat.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.chat.config.ChatProperties;
import ru.safeai.gateway.chat.exception.ChatLeaseUnavailableException;
import ru.safeai.gateway.chat.exception.ChatStaleProcessorException;
import ru.safeai.gateway.chat.observability.ChatMetrics;
import ru.safeai.gateway.chat.repository.ChatTurnRepository;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Persistent DB lease watchdog. This is independent of the Redis lock. */
@Slf4j
@Service
public class ChatTurnLeaseService {

    private final ChatTurnRepository turnRepository;
    private final ChatProperties properties;
    private final ChatMetrics metrics;
    private final ScheduledThreadPoolExecutor executor;
    private final Semaphore activeWatchdogs;

    public ChatTurnLeaseService(
            ChatTurnRepository turnRepository,
            ChatProperties properties,
            ChatMetrics metrics,
            Clock clock
    ) {
        this.turnRepository = Objects.requireNonNull(
                turnRepository, "turnRepository не должен быть null"
        );
        this.properties = Objects.requireNonNull(
                properties, "properties не должен быть null"
        );
        this.metrics = Objects.requireNonNull(
                metrics, "metrics не должен быть null"
        );
        // Keep the existing constructor contract for callers and tests.
        // Lease renewal intentionally uses PostgreSQL time, never JVM time.
        Objects.requireNonNull(clock, "clock не должен быть null");
        this.executor = new ScheduledThreadPoolExecutor(
                properties.leaseRenewalThreads(),
                threadFactory()
        );
        this.executor.setRemoveOnCancelPolicy(true);
        this.executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        this.activeWatchdogs = new Semaphore(
                properties.maxActiveLeaseWatchdogs(),
                true
        );
    }

    public LeaseWatch watch(
            UUID chatId,
            UUID turnId,
            UUID clientRequestId,
            UUID processingToken
    ) {
        Objects.requireNonNull(turnId, "turnId не должен быть null");
        Objects.requireNonNull(
                processingToken,
                "processingToken не должен быть null"
        );
        if (!activeWatchdogs.tryAcquire()) {
            throw new ChatLeaseUnavailableException(
                    chatId,
                    turnId,
                    clientRequestId,
                    "Достигнут лимит активных chat lease watchdog",
                    null
            );
        }

        AtomicBoolean valid = new AtomicBoolean(true);
        long intervalMillis = properties.leaseRenewalInterval().toMillis();
        ScheduledFuture<?> task;
        try {
            task = executor.scheduleWithFixedDelay(
                    () -> renew(turnId, processingToken, valid),
                    intervalMillis,
                    intervalMillis,
                    TimeUnit.MILLISECONDS
            );
        } catch (RuntimeException exception) {
            activeWatchdogs.release();
            throw new ChatLeaseUnavailableException(
                    chatId,
                    turnId,
                    clientRequestId,
                    "Не удалось запустить chat lease watchdog",
                    exception
            );
        }

        return new LeaseWatch(
                chatId,
                turnId,
                clientRequestId,
                processingToken,
                valid,
                task,
                new AtomicBoolean(false)
        );
    }

    public void ensureValid(LeaseWatch watch) {
        Objects.requireNonNull(watch, "watch не должен быть null");
        if (!watch.valid().get()) {
            throw new ChatStaleProcessorException(
                    watch.chatId(),
                    watch.turnId(),
                    watch.clientRequestId()
            );
        }
    }

    public void close(LeaseWatch watch) {
        if (watch == null || !watch.closed().compareAndSet(false, true)) {
            return;
        }
        watch.task().cancel(false);
        watch.valid().set(false);
        activeWatchdogs.release();
    }

    private void renew(
            UUID turnId,
            UUID processingToken,
            AtomicBoolean valid
    ) {
        if (!valid.get()) {
            return;
        }

        try {
            // PostgreSQL provides one authoritative clock sample for both
            // the lease-expiration predicate and the renewed deadline.
            // The fenced update never revives an expired or foreign lease.
            int updated = turnRepository.renewLeaseUsingDatabaseClock(
                    turnId,
                    processingToken,
                    properties.processingLease().toMillis()
            );
            if (updated != 1) {
                valid.set(false);
                metrics.recordOwnershipLoss("database");
                log.error(
                        "Persistent chat turn lease lost: turnId={}",
                        turnId
                );
            }
        } catch (RuntimeException exception) {
            valid.set(false);
            metrics.recordOwnershipLoss("database");
            log.error(
                    "Persistent chat turn lease renewal failed: turnId={}",
                    turnId,
                    exception
            );
        }
    }

    private static ThreadFactory threadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "chat-db-lease-renewal-" + sequence.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        };
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Chat lease renewal executor did not stop in time");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    public record LeaseWatch(
            UUID chatId,
            UUID turnId,
            UUID clientRequestId,
            UUID processingToken,
            AtomicBoolean valid,
            ScheduledFuture<?> task,
            AtomicBoolean closed
    ) {
    }
}
