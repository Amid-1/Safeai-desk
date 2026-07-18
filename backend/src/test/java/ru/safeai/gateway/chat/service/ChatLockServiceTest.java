package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import ru.safeai.gateway.common.exception.ChatBusyException;
import ru.safeai.gateway.common.exception.ChatLockUnavailableException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatLockServiceTest {

    private static final UUID CHAT_ID =
            UUID.fromString("2251f787-044c-4ef8-80d7-60d3ce4d72af");

    private static final Duration TTL = Duration.ofMinutes(5);

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
                        TTL
                )
        );
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void lockWhenRedisSetIfAbsentReturnsTrueReturnsLock() {
        stubValueOperations();

        when(valueOperations.setIfAbsent(
                eq("safeai:test:chat-lock:" + CHAT_ID),
                anyString(),
                eq(TTL)
        )).thenReturn(true);

        ChatLockService.ChatLock lock = service.lock(CHAT_ID);

        assertThat(lock.chatId()).isEqualTo(CHAT_ID);
        assertThat(lock.key())
                .isEqualTo("safeai:test:chat-lock:" + CHAT_ID);
        assertThat(lock.token()).isNotBlank();
        assertThat(lock.valid().get()).isTrue();
        assertNotNull(lock.renewalTask());

        verify(valueOperations).setIfAbsent(
                eq("safeai:test:chat-lock:" + CHAT_ID),
                anyString(),
                eq(TTL)
        );

        service.unlockQuietly(lock);
    }

    @Test
    void lockWhenChatAlreadyLockedThrowsChatBusy() {
        stubValueOperations();

        when(valueOperations.setIfAbsent(
                eq("safeai:test:chat-lock:" + CHAT_ID),
                anyString(),
                eq(TTL)
        )).thenReturn(false);

        assertThatThrownBy(() -> service.lock(CHAT_ID))
                .isInstanceOf(ChatBusyException.class)
                .hasMessageContaining(
                        "В этот чат уже отправляется сообщение"
                );
    }

    @Test
    void lockWhenRedisFailsThrowsChatLockUnavailable() {
        stubValueOperations();

        when(valueOperations.setIfAbsent(
                anyString(),
                anyString(),
                any(Duration.class)
        )).thenThrow(new RuntimeException("Redis unavailable"));

        assertThatThrownBy(() -> service.lock(CHAT_ID))
                .isInstanceOf(ChatLockUnavailableException.class)
                .hasMessageContaining(
                        "Сервис блокировки чата временно недоступен"
                );
    }

    @Test
    void ensureValidDoesNothingForValidLock() {
        ChatLockService.ChatLock lock = manualLock(true);

        service.ensureValid(lock);
    }

    @Test
    void ensureValidThrowsWhenOwnershipWasLost() {
        ChatLockService.ChatLock lock = manualLock(false);

        assertThatThrownBy(() -> service.ensureValid(lock))
                .isInstanceOf(ChatLockUnavailableException.class)
                .hasMessageContaining(
                        "Блокировка чата была потеряна"
                );
    }

    @Test
    void unlockCancelsRenewalInvalidatesLockAndDeletesRedisKey() {
        ChatLockService.ChatLock lock = manualLock(true);

        service.unlock(lock);

        verify(lock.renewalTask()).cancel(false);
        assertThat(lock.valid().get()).isFalse();

        verify(redisTemplate).execute(
                any(),
                eq(List.of(lock.key())),
                eq(lock.token())
        );
    }

    @Test
    void unlockQuietlyWhenLockIsNullDoesNothing() {
        service.unlockQuietly(null);

        verify(redisTemplate, never())
                .execute(any(), anyList(), anyString());

        verifyNoInteractions(valueOperations);
    }

    private ChatLockService.ChatLock manualLock(boolean valid) {
        return new ChatLockService.ChatLock(
                CHAT_ID,
                "safeai:test:chat-lock:" + CHAT_ID,
                "test-token",
                new AtomicBoolean(valid),
                mock(ScheduledFuture.class)
        );
    }

    private void stubValueOperations() {
        when(redisTemplate.opsForValue())
                .thenReturn(valueOperations);
    }
}
