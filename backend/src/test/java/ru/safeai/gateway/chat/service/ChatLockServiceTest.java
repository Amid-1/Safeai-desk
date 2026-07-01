package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.RateLimitUnavailableException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
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
        stubValueOperations();

        when(valueOperations.setIfAbsent(
                eq("safeai:test:chat-lock:" + CHAT_ID),
                anyString(),
                eq(Duration.ofSeconds(120))
        )).thenReturn(true);

        ChatLockService.ChatLock lock = service.lock(CHAT_ID);

        assertThat(lock.chatId()).isEqualTo(CHAT_ID);
        assertThat(lock.key()).isEqualTo("safeai:test:chat-lock:" + CHAT_ID);
        assertThat(lock.token()).isNotBlank();

        verify(redisTemplate).opsForValue();
        verify(valueOperations).setIfAbsent(
                eq("safeai:test:chat-lock:" + CHAT_ID),
                anyString(),
                eq(Duration.ofSeconds(120))
        );
    }

    @Test
    void lock_whenChatAlreadyLocked_shouldThrowConflict() {
        stubValueOperations();

        when(valueOperations.setIfAbsent(
                eq("safeai:test:chat-lock:" + CHAT_ID),
                anyString(),
                eq(Duration.ofSeconds(120))
        )).thenReturn(false);

        assertThatThrownBy(() -> service.lock(CHAT_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("В этот чат уже отправляется сообщение");

        verify(redisTemplate).opsForValue();
        verify(valueOperations).setIfAbsent(
                eq("safeai:test:chat-lock:" + CHAT_ID),
                anyString(),
                eq(Duration.ofSeconds(120))
        );
    }

    @Test
    void lock_whenRedisFails_shouldThrowRateLimitUnavailable() {
        stubValueOperations();

        when(valueOperations.setIfAbsent(
                anyString(),
                anyString(),
                any(Duration.class)
        )).thenThrow(new RuntimeException("Redis unavailable"));

        assertThatThrownBy(() -> service.lock(CHAT_ID))
                .isInstanceOf(RateLimitUnavailableException.class)
                .hasMessageContaining("Redis chat lock недоступен");

        verify(redisTemplate).opsForValue();
        verify(valueOperations).setIfAbsent(
                anyString(),
                anyString(),
                any(Duration.class)
        );
    }

    @Test
    void unlockQuietly_whenLockIsNull_shouldDoNothing() {
        service.unlockQuietly(null);

        verify(redisTemplate, never()).execute(any(), anyList(), anyString());
        verifyNoInteractions(valueOperations);
    }

    @Test
    void unlock_shouldExecuteReleaseLockScript() {
        ChatLockService.ChatLock lock = new ChatLockService.ChatLock(
                CHAT_ID,
                "safeai:test:chat-lock:" + CHAT_ID,
                "test-token"
        );

        when(redisTemplate.execute(
                any(),
                eq(List.of("safeai:test:chat-lock:" + CHAT_ID)),
                eq("test-token")
        )).thenReturn(1L);

        service.unlock(lock);

        verify(redisTemplate).execute(
                any(),
                eq(List.of("safeai:test:chat-lock:" + CHAT_ID)),
                eq("test-token")
        );

        verifyNoInteractions(valueOperations);
    }

    private void stubValueOperations() {
        when(redisTemplate.opsForValue())
                .thenReturn(valueOperations);
    }
}