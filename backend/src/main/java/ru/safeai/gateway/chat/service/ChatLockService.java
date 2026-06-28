package ru.safeai.gateway.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.RateLimitUnavailableException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(ChatLockProperties.class)
public class ChatLockService {

    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT;

    static {
        RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>();
        RELEASE_LOCK_SCRIPT.setResultType(Long.class);
        RELEASE_LOCK_SCRIPT.setScriptText("""
                if redis.call('GET', KEYS[1]) == ARGV[1] then
                    return redis.call('DEL', KEYS[1])
                end
                return 0
                """);
    }

    private final StringRedisTemplate redisTemplate;
    private final ChatLockProperties properties;

    public ChatLock lock(UUID chatId) {
        Objects.requireNonNull(chatId, "chatId не должен быть null");

        String key = key(chatId);
        String token = UUID.randomUUID().toString();

        Boolean locked;

        try {
            locked = redisTemplate.opsForValue().setIfAbsent(
                    key,
                    token,
                    properties.effectiveTtl()
            );
        } catch (RuntimeException exception) {
            throw new RateLimitUnavailableException(
                    "Redis chat lock недоступен",
                    exception
            );
        }

        if (!Boolean.TRUE.equals(locked)) {
            throw new ConflictException("В этот чат уже отправляется сообщение");
        }

        return new ChatLock(chatId, key, token);
    }

    public void unlock(ChatLock lock) {
        Objects.requireNonNull(lock, "lock не должен быть null");

        redisTemplate.execute(
                RELEASE_LOCK_SCRIPT,
                List.of(lock.key()),
                lock.token()
        );
    }

    public void unlockQuietly(ChatLock lock) {
        if (lock == null) {
            return;
        }

        try {
            unlock(lock);
        } catch (RuntimeException exception) {
            log.warn("Failed to unlock chat: chatId={}", lock.chatId(), exception);
        }
    }

    private String key(UUID chatId) {
        return properties.effectiveKeyPrefix() + ":" + chatId;
    }

    public record ChatLock(
            UUID chatId,
            String key,
            String token
    ) {
    }
}