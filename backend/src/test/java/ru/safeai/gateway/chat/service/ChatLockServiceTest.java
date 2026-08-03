package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import ru.safeai.gateway.chat.config.ChatLockProperties;
import ru.safeai.gateway.chat.observability.ChatMetrics;
import ru.safeai.gateway.chat.testsupport.ChatTestFixtures;
import ru.safeai.gateway.common.exception.ChatBusyException;
import ru.safeai.gateway.common.exception.ChatLockUnavailableException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatLockServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> values;
    private ChatMetrics metrics;
    private ChatLockService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        metrics = mock(ChatMetrics.class);

        when(redisTemplate.opsForValue())
                .thenReturn(values);

        service = new ChatLockService(
                redisTemplate,
                new ChatLockProperties(
                        "safeai:test:chat-lock:",
                        Duration.ofSeconds(30),
                        4,
                        100,
                        Duration.ofSeconds(1)
                ),
                metrics
        );
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.invokeMethod(
                service,
                "shutdown"
        );
    }

    @Test
    void lockUsesOwnerTokenAndNormalizedPrefix() {
        when(values.setIfAbsent(
                eq(
                        "safeai:test:chat-lock:"
                                + ChatTestFixtures.CHAT_ID
                ),
                anyString(),
                eq(Duration.ofSeconds(30))
        )).thenReturn(true);

        ChatLockService.ChatLock lock = service.lock(
                ChatTestFixtures.CHAT_ID
        );

        assertThat(lock.ownerToken())
                .isNotBlank();

        assertThat(lock.valid())
                .isTrue();

        service.unlockQuietly(lock);
    }

    @Test
    void secondOwnerGetsBusy() {
        when(values.setIfAbsent(
                anyString(),
                anyString(),
                any(Duration.class)
        )).thenReturn(false);

        assertThatThrownBy(() ->
                service.lock(ChatTestFixtures.CHAT_ID)
        ).isInstanceOf(ChatBusyException.class);
    }

    @Test
    void redisOutageFailsClosed() {
        when(values.setIfAbsent(
                anyString(),
                anyString(),
                any(Duration.class)
        )).thenThrow(
                new RuntimeException("redis down")
        );

        assertThatThrownBy(() ->
                service.lock(ChatTestFixtures.CHAT_ID)
        ).isInstanceOf(ChatLockUnavailableException.class);
    }

    @Test
    void renewalFailureInvalidatesOnlyThatLock() {
        ChatLockService.ChatLock lock = manualLock();

        when(redisTemplate.execute(
                any(),
                eq(List.of(lock.key())),
                eq(lock.ownerToken()),
                eq("30000")
        )).thenReturn(0L);

        ReflectionTestUtils.invokeMethod(
                service,
                "renew",
                lock.chatId(),
                lock.key(),
                lock.ownerToken(),
                lock.valid()
        );

        assertThat(lock.valid())
                .isFalse();

        verify(metrics)
                .recordOwnershipLoss("redis");

        assertThatThrownBy(() ->
                service.ensureValid(lock)
        ).isInstanceOf(ChatLockUnavailableException.class);
    }

    @Test
    void releaseUsesCompareAndDeleteOwnerScript() {
        ChatLockService.ChatLock lock = manualLock();

        service.unlock(lock);

        verify(lock.renewalTask())
                .cancel(false);

        verify(redisTemplate).execute(
                any(),
                eq(List.of(lock.key())),
                eq(lock.ownerToken())
        );

        assertThat(lock.valid())
                .isFalse();
    }

    @Test
    void oldOwnerReleaseCannotBlindlyDeleteNewOwnerKey() {
        ChatLockService.ChatLock oldLock = manualLock();

        service.unlock(oldLock);

        verify(redisTemplate).execute(
                any(),
                eq(List.of(oldLock.key())),
                eq(oldLock.ownerToken())
        );

        verify(redisTemplate, never())
                .delete(oldLock.key());
    }

    @Test
    void renewalExecutorUsesConfiguredPoolInsteadOfSingleGlobalThread() {
        ScheduledThreadPoolExecutor executor =
                (ScheduledThreadPoolExecutor)
                        ReflectionTestUtils.getField(
                                service,
                                "renewalExecutor"
                        );

        assertThat(executor)
                .isNotNull();

        assertThat(executor.getCorePoolSize())
                .isEqualTo(4);

        assertThat(executor.getRemoveOnCancelPolicy())
                .isTrue();
    }

    @Test
    void activeLockCapacityIsBounded() {
        ChatLockService bounded = new ChatLockService(
                redisTemplate,
                new ChatLockProperties(
                        "safeai:test:bounded",
                        Duration.ofSeconds(30),
                        2,
                        1,
                        Duration.ofSeconds(1)
                ),
                metrics
        );

        try {
            when(values.setIfAbsent(
                    anyString(),
                    anyString(),
                    any(Duration.class)
            )).thenReturn(true);

            ChatLockService.ChatLock first = bounded.lock(
                    ChatTestFixtures.CHAT_ID
            );

            assertThatThrownBy(() ->
                    bounded.lock(UUID.randomUUID())
            )
                    .isInstanceOf(
                            ChatLockUnavailableException.class
                    )
                    .hasMessage(
                            "Достигнут лимит активных блокировок чатов"
                    );

            bounded.unlockQuietly(first);
        } finally {
            ReflectionTestUtils.invokeMethod(
                    bounded,
                    "shutdown"
            );
        }
    }

    @Test
    void watchdogSchedulingFailureCleansUpOwnedRedisKey() {
        when(values.setIfAbsent(
                anyString(),
                anyString(),
                any(Duration.class)
        )).thenReturn(true);

        ReflectionTestUtils.invokeMethod(
                service,
                "shutdown"
        );

        assertThatThrownBy(() ->
                service.lock(ChatTestFixtures.CHAT_ID)
        )
                .isInstanceOf(
                        ChatLockUnavailableException.class
                )
                .hasMessageContaining("watchdog");

        verify(redisTemplate).execute(
                any(),
                eq(List.of(
                        "safeai:test:chat-lock:"
                                + ChatTestFixtures.CHAT_ID
                )),
                anyString()
        );
    }

    @Test
    void unlockIsIdempotentAndReleasesOwnerOnlyOnce() {
        ChatLockService.ChatLock lock = manualLock();

        service.unlock(lock);
        service.unlock(lock);

        verify(redisTemplate, times(1))
                .execute(
                        any(),
                        eq(List.of(lock.key())),
                        eq(lock.ownerToken())
                );
    }

    private ChatLockService.ChatLock manualLock() {
        return new ChatLockService.ChatLock(
                ChatTestFixtures.CHAT_ID,
                "safeai:test:chat-lock:"
                        + ChatTestFixtures.CHAT_ID,
                "owner-token",
                new AtomicBoolean(true),
                mock(ScheduledFuture.class),
                new AtomicBoolean(false)
        );
    }
}