package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.RateLimitUnavailableException;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatLockServiceTest {

    private static final UUID CHAT_ID =
            UUID.fromString("2251f787-044c-4ef8-80d7-60d3ce4d72af");

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ChatLockService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);

        when(redisTemplate.opsForValue())
                .thenReturn(valueOperations);

        service = new ChatLockService(
                redisTemplate,
                new ChatLockProperties(
                        "safeai:test:chat-lock",
                        Duration.ofSeconds(120)
                )
        );
    }

    @Test
    void lock_whenRedisSetIfAbsentReturnsTrue_shouldReturnLock() {
        when(valueOperations.setIfAbsent(
                eq("safeai:test:chat-lock:" + CHAT_ID),
                anyString(),
                eq(Duration.ofSeconds(120))
        )).thenReturn(true);

        ChatLockService.ChatLock lock = service.lock(CHAT_ID);

        assertThat(lock.chatId()).isEqualTo(CHAT_ID);
        assertThat(lock.key()).isEqualTo("safeai:test:chat-lock:" + CHAT_ID);
        assertThat(lock.token()).isNotBlank();
    }

    @Test
    void lock_whenChatAlreadyLocked_shouldThrowConflict() {
        when(valueOperations.setIfAbsent(
                eq("safeai:test:chat-lock:" + CHAT_ID),
                anyString(),
                eq(Duration.ofSeconds(120))
        )).thenReturn(false);

        assertThatThrownBy(() -> service.lock(CHAT_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("В этот чат уже отправляется сообщение");
    }

    @Test
    void lock_whenRedisFails_shouldThrowRateLimitUnavailable() {
        when(valueOperations.setIfAbsent(
                anyString(),
                anyString(),
                any(Duration.class)
        )).thenThrow(new RuntimeException("Redis unavailable"));

        assertThatThrownBy(() -> service.lock(CHAT_ID))
                .isInstanceOf(RateLimitUnavailableException.class)
                .hasMessageContaining("Redis chat lock недоступен");
    }

    @Test
    void unlockQuietly_whenLockIsNull_shouldDoNothing() {
        service.unlockQuietly(null);

        verify(redisTemplate, never()).execute(any(), anyList(), anyString());
    }
}