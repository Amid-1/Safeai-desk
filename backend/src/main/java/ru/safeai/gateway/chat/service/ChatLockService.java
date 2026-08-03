package ru.safeai.gateway.chat.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.chat.config.ChatLockProperties;
import ru.safeai.gateway.chat.observability.ChatMetrics;
import ru.safeai.gateway.common.exception.ChatBusyException;
import ru.safeai.gateway.common.exception.ChatLockUnavailableException;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis lock обеспечивает быстрое межпроцессное исключение и понятную
 * ошибку для клиента.
 * Корректность обработки дополнительно обеспечивается состоянием chat_turns,
 * processing_token, уникальным индексом одного PROCESSING turn на сессию
 * и условными переходами состояния в базе данных.
 */
@Slf4j
@Service
public class ChatLockService {

    private static final String RENEWAL_THREAD_NAME_PREFIX =
            "chat-redis-lock-renewal-";

    private static final Long REDIS_OPERATION_SUCCEEDED = 1L;

    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT =
            createScript("""
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                        return redis.call('DEL', KEYS[1])
                    end
                    return 0
                    """);

    private static final DefaultRedisScript<Long> RENEW_LOCK_SCRIPT =
            createScript("""
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                        return redis.call('PEXPIRE', KEYS[1], ARGV[2])
                    end
                    return 0
                    """);

    private final StringRedisTemplate redisTemplate;
    private final ChatLockProperties properties;
    private final ChatMetrics metrics;
    private final ScheduledThreadPoolExecutor renewalExecutor;
    private final Semaphore activeLocks;

    public ChatLockService(
            StringRedisTemplate redisTemplate,
            ChatLockProperties properties,
            ChatMetrics metrics
    ) {
        this.redisTemplate = Objects.requireNonNull(
                redisTemplate,
                "redisTemplate не должен быть null"
        );
        this.properties = Objects.requireNonNull(
                properties,
                "properties не должен быть null"
        );
        this.metrics = Objects.requireNonNull(
                metrics,
                "metrics не должен быть null"
        );

        this.renewalExecutor = new ScheduledThreadPoolExecutor(
                properties.renewalThreads(),
                renewalThreadFactory()
        );
        this.renewalExecutor.setRemoveOnCancelPolicy(true);
        this.renewalExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(
                false
        );
        this.renewalExecutor.setContinueExistingPeriodicTasksAfterShutdownPolicy(
                false
        );

        this.activeLocks = new Semaphore(
                properties.maxActiveLocks(),
                true
        );
    }

    public ChatLock lock(UUID chatId) {
        Objects.requireNonNull(
                chatId,
                "chatId не должен быть null"
        );

        if (!activeLocks.tryAcquire()) {
            throw new ChatLockUnavailableException(
                    "Достигнут лимит активных блокировок чатов",
                    null
            );
        }

        String key = buildKey(chatId);
        String ownerToken = UUID.randomUUID().toString();

        Boolean locked;

        try {
            locked = redisTemplate
                    .opsForValue()
                    .setIfAbsent(
                            key,
                            ownerToken,
                            properties.ttl()
                    );
        } catch (RuntimeException exception) {
            activeLocks.release();

            throw new ChatLockUnavailableException(
                    "Сервис блокировки чата временно недоступен",
                    exception
            );
        }

        if (!Boolean.TRUE.equals(locked)) {
            activeLocks.release();

            throw new ChatBusyException(
                    "В этот чат уже отправляется сообщение"
            );
        }

        AtomicBoolean valid = new AtomicBoolean(true);
        AtomicBoolean closed = new AtomicBoolean(false);
        Duration renewalInterval = properties.renewalInterval();

        ScheduledFuture<?> renewalTask;

        try {
            renewalTask = renewalExecutor.scheduleWithFixedDelay(
                    () -> renew(
                            chatId,
                            key,
                            ownerToken,
                            valid
                    ),
                    renewalInterval.toMillis(),
                    renewalInterval.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (RuntimeException exception) {
            valid.set(false);
            releaseOwnedKeyQuietly(key, ownerToken);
            activeLocks.release();

            throw new ChatLockUnavailableException(
                    "Не удалось запустить watchdog Redis-блокировки чата",
                    exception
            );
        }

        return new ChatLock(
                chatId,
                key,
                ownerToken,
                valid,
                renewalTask,
                closed
        );
    }

    public void ensureValid(ChatLock lock) {
        Objects.requireNonNull(
                lock,
                "lock не должен быть null"
        );

        if (!lock.valid().get()) {
            throw new ChatLockUnavailableException(
                    "Блокировка чата была потеряна во время обработки",
                    null
            );
        }
    }

    public void unlock(ChatLock lock) {
        Objects.requireNonNull(
                lock,
                "lock не должен быть null"
        );

        if (!lock.closed().compareAndSet(false, true)) {
            return;
        }

        lock.renewalTask().cancel(false);
        lock.valid().set(false);

        try {
            redisTemplate.execute(
                    RELEASE_LOCK_SCRIPT,
                    List.of(lock.key()),
                    lock.ownerToken()
            );
        } catch (RuntimeException exception) {
            throw new ChatLockUnavailableException(
                    "Не удалось освободить блокировку чата",
                    exception
            );
        } finally {
            activeLocks.release();
        }
    }

    public void unlockQuietly(ChatLock lock) {
        if (lock == null) {
            return;
        }

        try {
            unlock(lock);
        } catch (RuntimeException exception) {
            log.warn(
                    "Не удалось освободить Redis-блокировку чата: chatId={}",
                    lock.chatId(),
                    exception
            );
        }
    }

    private void renew(
            UUID chatId,
            String key,
            String ownerToken,
            AtomicBoolean valid
    ) {
        if (!valid.get()) {
            return;
        }

        try {
            Long renewed = redisTemplate.execute(
                    RENEW_LOCK_SCRIPT,
                    List.of(key),
                    ownerToken,
                    Long.toString(properties.ttl().toMillis())
            );

            if (!REDIS_OPERATION_SUCCEEDED.equals(renewed)) {
                markLockAsLost(
                        chatId,
                        valid,
                        null
                );
            }
        } catch (RuntimeException exception) {
            markLockAsLost(
                    chatId,
                    valid,
                    exception
            );
        }
    }

    private void markLockAsLost(
            UUID chatId,
            AtomicBoolean valid,
            RuntimeException cause
    ) {
        if (!valid.compareAndSet(true, false)) {
            return;
        }

        metrics.recordOwnershipLoss("redis");

        if (cause == null) {
            log.error(
                    "Право владения Redis-блокировкой чата потеряно: chatId={}",
                    chatId
            );
        } else {
            log.error(
                    "Ошибка продления Redis-блокировки чата: chatId={}",
                    chatId,
                    cause
            );
        }
    }

    private void releaseOwnedKeyQuietly(
            String key,
            String ownerToken
    ) {
        try {
            redisTemplate.execute(
                    RELEASE_LOCK_SCRIPT,
                    List.of(key),
                    ownerToken
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "Не удалось удалить Redis-блокировку после ошибки запуска watchdog",
                    exception
            );
        }
    }

    private String buildKey(UUID chatId) {
        return properties.keyPrefix()
                + ":"
                + chatId;
    }

    private static ThreadFactory renewalThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();

        return runnable -> {
            Thread thread = new Thread(
                    runnable,
                    RENEWAL_THREAD_NAME_PREFIX
                            + sequence.incrementAndGet()
            );

            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler(
                    (ignoredThread, throwable) ->
                            log.error(
                                    "Необработанная ошибка watchdog Redis-блокировки",
                                    throwable
                            )
            );

            return thread;
        };
    }

    private static DefaultRedisScript<Long> createScript(
            String scriptText
    ) {
        DefaultRedisScript<Long> script =
                new DefaultRedisScript<>();

        script.setResultType(Long.class);
        script.setScriptText(scriptText);

        return script;
    }

    @PreDestroy
    void shutdown() {
        renewalExecutor.shutdownNow();

        try {
            boolean terminated = renewalExecutor.awaitTermination(
                    properties.shutdownTimeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );

            if (!terminated) {
                log.warn(
                        "Executor продления Redis-блокировок не остановился вовремя"
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            log.warn(
                    "Ожидание остановки executor Redis-блокировок было прервано"
            );
        }
    }

    public record ChatLock(
            UUID chatId,
            String key,
            String ownerToken,
            AtomicBoolean valid,
            ScheduledFuture<?> renewalTask,
            AtomicBoolean closed
    ) {

        public ChatLock {
            Objects.requireNonNull(
                    chatId,
                    "chatId не должен быть null"
            );
            Objects.requireNonNull(
                    key,
                    "key не должен быть null"
            );
            Objects.requireNonNull(
                    ownerToken,
                    "ownerToken не должен быть null"
            );
            Objects.requireNonNull(
                    valid,
                    "valid не должен быть null"
            );
            Objects.requireNonNull(
                    renewalTask,
                    "renewalTask не должен быть null"
            );
            Objects.requireNonNull(
                    closed,
                    "closed не должен быть null"
            );
        }
    }
}