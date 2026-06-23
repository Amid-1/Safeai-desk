package ru.safeai.gateway.chat.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.common.exception.ConflictException;

import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatLockService {

    private static final String KEY_PREFIX = "chat-lock:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redisTemplate;

    public void lock(UUID chatId) {
        String key = KEY_PREFIX + chatId;

        Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                key,
                "1",
                LOCK_TTL
        );

        if (!Boolean.TRUE.equals(locked)) {
            throw new ConflictException("В этот чат уже отправляется сообщение");
        }
    }

    public void unlock(UUID chatId) {
        redisTemplate.delete(KEY_PREFIX + chatId);
    }
}