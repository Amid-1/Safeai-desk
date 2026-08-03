package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ru.safeai.gateway.chat.config.ChatProperties;
import ru.safeai.gateway.chat.exception.ChatLeaseUnavailableException;
import ru.safeai.gateway.chat.exception.ChatStaleProcessorException;
import ru.safeai.gateway.chat.observability.ChatMetrics;
import ru.safeai.gateway.chat.repository.ChatTurnRepository;
import ru.safeai.gateway.chat.testsupport.ChatTestFixtures;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatTurnLeaseServiceTest {

    private ChatTurnRepository repository;
    private ChatMetrics metrics;
    private ChatTurnLeaseService service;

    @BeforeEach
    void setUp() {
        repository = mock(ChatTurnRepository.class);
        metrics = mock(ChatMetrics.class);

        service = new ChatTurnLeaseService(
                repository,
                properties(2),
                metrics,
                ChatTestFixtures.CLOCK
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
    void renewalExtendsPersistentLeaseWithFencingToken() {
        ChatTurnLeaseService.LeaseWatch watch = service.watch(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.CLIENT_REQUEST_ID,
                ChatTestFixtures.PROCESSING_TOKEN
        );

        when(repository.renewLease(
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.PROCESSING_TOKEN,
                ChatTestFixtures.NOW,
                ChatTestFixtures.NOW.plus(
                        Duration.ofMinutes(3)
                )
        )).thenReturn(1);

        ReflectionTestUtils.invokeMethod(
                service,
                "renew",
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.PROCESSING_TOKEN,
                watch.valid()
        );

        assertThat(watch.valid())
                .isTrue();

        service.close(watch);
    }

    @Test
    void zeroUpdatedRowsFenceStaleProcessor() {
        ChatTurnLeaseService.LeaseWatch watch = service.watch(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.CLIENT_REQUEST_ID,
                ChatTestFixtures.PROCESSING_TOKEN
        );

        when(repository.renewLease(
                any(),
                any(),
                any(),
                any()
        )).thenReturn(0);

        ReflectionTestUtils.invokeMethod(
                service,
                "renew",
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.PROCESSING_TOKEN,
                watch.valid()
        );

        assertThatThrownBy(() ->
                service.ensureValid(watch)
        ).isInstanceOf(
                ChatStaleProcessorException.class
        );

        verify(metrics)
                .recordOwnershipLoss("database");

        service.close(watch);
    }

    @Test
    void repositoryFailureInvalidatesWatch() {
        ChatTurnLeaseService.LeaseWatch watch = service.watch(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.CLIENT_REQUEST_ID,
                ChatTestFixtures.PROCESSING_TOKEN
        );

        when(repository.renewLease(
                any(),
                any(),
                any(),
                any()
        )).thenThrow(
                new RuntimeException("db down")
        );

        ReflectionTestUtils.invokeMethod(
                service,
                "renew",
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.PROCESSING_TOKEN,
                watch.valid()
        );

        assertThat(watch.valid())
                .isFalse();

        verify(metrics)
                .recordOwnershipLoss("database");

        service.close(watch);
    }

    @Test
    void boundedWatchdogCapacityRejectsOverload() {
        ChatTurnLeaseService limited =
                new ChatTurnLeaseService(
                        repository,
                        properties(1),
                        metrics,
                        ChatTestFixtures.CLOCK
                );

        try {
            ChatTurnLeaseService.LeaseWatch first =
                    limited.watch(
                            ChatTestFixtures.CHAT_ID,
                            ChatTestFixtures.TURN_ID,
                            ChatTestFixtures.CLIENT_REQUEST_ID,
                            ChatTestFixtures.PROCESSING_TOKEN
                    );

            assertThatThrownBy(() ->
                    limited.watch(
                            ChatTestFixtures.OTHER_CHAT_ID,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            UUID.randomUUID()
                    )
            ).isInstanceOf(
                    ChatLeaseUnavailableException.class
            );

            limited.close(first);
        } finally {
            ReflectionTestUtils.invokeMethod(
                    limited,
                    "shutdown"
            );
        }
    }

    @Test
    void closeIsIdempotentAndReleasesCapacityOnce() {
        ChatTurnLeaseService.LeaseWatch watch = service.watch(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.CLIENT_REQUEST_ID,
                ChatTestFixtures.PROCESSING_TOKEN
        );

        service.close(watch);
        service.close(watch);

        assertThat(watch.closed())
                .isTrue();

        assertThat(watch.valid())
                .isFalse();
    }

    private static ChatProperties properties(
            int maxWatchdogs
    ) {
        return new ChatProperties(
                50,
                50,
                100,
                100,
                16_000,
                Duration.ofMinutes(3),
                2,
                maxWatchdogs
        );
    }
}