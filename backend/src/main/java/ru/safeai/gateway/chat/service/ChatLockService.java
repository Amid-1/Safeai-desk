package ru.safeai.gateway.chat.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.common.exception.ChatBusyException;
import ru.safeai.gateway.common.exception.ChatLockUnavailableException;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@EnableConfigurationProperties(ChatLockProperties.class)
public class ChatLockService {

    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = script("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """);

    private static final DefaultRedisScript<Long> RENEW_LOCK_SCRIPT = script("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return 0
            """);

    private final StringRedisTemplate redisTemplate;
    private final ChatLockProperties properties;
    private final ScheduledExecutorService renewalExecutor;

    public ChatLockService(
            StringRedisTemplate redisTemplate,
            ChatLockProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "chat-lock-renewal");
            thread.setDaemon(true);
            return thread;
        };
        this.renewalExecutor = Executors.newScheduledThreadPool(1, factory);
    }

    public ChatLock lock(UUID chatId) {
        Objects.requireNonNull(chatId, "chatId не должен быть null");
        String key = key(chatId);
        String token = UUID.randomUUID().toString();

        final Boolean locked;
        try {
            locked = redisTemplate.opsForValue().setIfAbsent(
                    key,
                    token,
                    properties.effectiveTtl()
            );
        } catch (RuntimeException exception) {
            throw new ChatLockUnavailableException(
                    "Сервис блокировки чата временно недоступен",
                    exception
            );
        }

        if (!Boolean.TRUE.equals(locked)) {
            throw new ChatBusyException("В этот чат уже отправляется сообщение");
        }

        AtomicBoolean valid = new AtomicBoolean(true);
        Duration interval = properties.renewalInterval();
        ScheduledFuture<?> renewalTask = renewalExecutor.scheduleAtFixedRate(
                () -> renew(chatId, key, token, valid),
                interval.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS
        );

        return new ChatLock(chatId, key, token, valid, renewalTask);
    }

    public void ensureValid(ChatLock lock) {
        Objects.requireNonNull(lock, "lock не должен быть null");
        if (!lock.valid().get()) {
            throw new ChatLockUnavailableException(
                    "Блокировка чата была потеряна во время обработки",
                    null
            );
        }
    }

    public void unlock(ChatLock lock) {
        Objects.requireNonNull(lock, "lock не должен быть null");
        lock.renewalTask().cancel(false);
        lock.valid().set(false);
        try {
            redisTemplate.execute(
                    RELEASE_LOCK_SCRIPT,
                    List.of(lock.key()),
                    lock.token()
            );
        } catch (RuntimeException exception) {
            throw new ChatLockUnavailableException(
                    "Не удалось освободить блокировку чата",
                    exception
            );
        }
    }

    public void unlockQuietly(ChatLock lock) {
        if (lock == null) return;
        try {
            unlock(lock);
        } catch (RuntimeException exception) {
            log.warn("Failed to unlock chat: chatId={}", lock.chatId(), exception);
        }
    }

    private void renew(
            UUID chatId,
            String key,
            String token,
            AtomicBoolean valid
    ) {
        if (!valid.get()) return;
        try {
            Long renewed = redisTemplate.execute(
                    RENEW_LOCK_SCRIPT,
                    List.of(key),
                    token,
                    String.valueOf(properties.effectiveTtl().toMillis())
            );
            if (!Long.valueOf(1L).equals(renewed)) {
                valid.set(false);
                log.error("Chat lock ownership lost: chatId={}", chatId);
            }
        } catch (RuntimeException exception) {
            valid.set(false);
            log.error("Chat lock renewal failed: chatId={}", chatId, exception);
        }
    }

    private String key(UUID chatId) {
        return properties.effectiveKeyPrefix() + ":" + chatId;
    }

    private static DefaultRedisScript<Long> script(String text) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText(text);
        return script;
    }

    @PreDestroy
    void shutdown() {
        renewalExecutor.shutdownNow();
    }

    public record ChatLock(
            UUID chatId,
            String key,
            String token,
            AtomicBoolean valid,
            ScheduledFuture<?> renewalTask
    ) {
    }
}
