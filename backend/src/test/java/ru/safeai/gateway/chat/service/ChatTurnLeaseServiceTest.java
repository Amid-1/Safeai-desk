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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the fenced, database-clock-based chat turn lease watchdog.
 *
 * <p>The repository owns the SQL fencing predicate and PostgreSQL clock.
 * These tests verify the service's response to the affected-row count and
 * repository errors without assuming JVM time is the lease authority.</p>
 */
class ChatTurnLeaseServiceTest {

    private static final Duration PROCESSING_LEASE =
            Duration.ofMinutes(3);
    private static final long LEASE_MILLIS =
            PROCESSING_LEASE.toMillis();

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
        service.shutdown();
    }

    @Test
    void renewalExtendsPersistentLeaseWithFencingToken() {
        ChatTurnLeaseService.LeaseWatch watch = watch(service);

        try {
            when(repository.renewLeaseUsingDatabaseClock(
                    ChatTestFixtures.TURN_ID,
                    ChatTestFixtures.PROCESSING_TOKEN,
                    LEASE_MILLIS
            )).thenReturn(1);

            renew(service, watch);

            assertThat(watch.valid().get()).isTrue();
            service.ensureValid(watch);

            verify(repository).renewLeaseUsingDatabaseClock(
                    ChatTestFixtures.TURN_ID,
                    ChatTestFixtures.PROCESSING_TOKEN,
                    LEASE_MILLIS
            );
            verifyNoInteractions(metrics);
        } finally {
            service.close(watch);
        }
    }

    @Test
    void zeroUpdatedRowsFenceStaleProcessor() {
        ChatTurnLeaseService.LeaseWatch watch = watch(service);

        try {
            when(repository.renewLeaseUsingDatabaseClock(
                    ChatTestFixtures.TURN_ID,
                    ChatTestFixtures.PROCESSING_TOKEN,
                    LEASE_MILLIS
            )).thenReturn(0);

            renew(service, watch);

            assertStale(service, watch);
            verify(metrics).recordOwnershipLoss("database");

            // A lost lease must never be renewed or reported twice.
            renew(service, watch);
            verify(repository, times(1)).renewLeaseUsingDatabaseClock(
                    ChatTestFixtures.TURN_ID,
                    ChatTestFixtures.PROCESSING_TOKEN,
                    LEASE_MILLIS
            );
            verify(metrics, times(1)).recordOwnershipLoss("database");
        } finally {
            service.close(watch);
        }
    }

    @Test
    void unexpectedMultipleUpdatedRowsAlsoFenceProcessor() {
        ChatTurnLeaseService.LeaseWatch watch = watch(service);

        try {
            when(repository.renewLeaseUsingDatabaseClock(
                    ChatTestFixtures.TURN_ID,
                    ChatTestFixtures.PROCESSING_TOKEN,
                    LEASE_MILLIS
            )).thenReturn(2);

            renew(service, watch);

            assertStale(service, watch);
            verify(metrics).recordOwnershipLoss("database");
        } finally {
            service.close(watch);
        }
    }

    @Test
    void repositoryFailureInvalidatesWatch() {
        ChatTurnLeaseService.LeaseWatch watch = watch(service);

        try {
            when(repository.renewLeaseUsingDatabaseClock(
                    ChatTestFixtures.TURN_ID,
                    ChatTestFixtures.PROCESSING_TOKEN,
                    LEASE_MILLIS
            )).thenThrow(new RuntimeException("db down"));

            // The scheduled renewal handles repository failures internally,
            // invalidates the watch, and lets the caller detect stale work.
            renew(service, watch);

            assertStale(service, watch);
            verify(metrics).recordOwnershipLoss("database");

            renew(service, watch);
            verify(repository, times(1)).renewLeaseUsingDatabaseClock(
                    ChatTestFixtures.TURN_ID,
                    ChatTestFixtures.PROCESSING_TOKEN,
                    LEASE_MILLIS
            );
            verify(metrics, times(1)).recordOwnershipLoss("database");
        } finally {
            service.close(watch);
        }
    }

    @Test
    void boundedWatchdogCapacityRejectsOverloadAndRecoversAfterClose() {
        ChatTurnLeaseService limited = new ChatTurnLeaseService(
                repository,
                properties(1),
                metrics,
                ChatTestFixtures.CLOCK
        );

        ChatTurnLeaseService.LeaseWatch first = null;
        ChatTurnLeaseService.LeaseWatch second = null;

        try {
            first = watch(limited);

            assertThatThrownBy(() -> watchAnother(limited))
                    .isInstanceOf(ChatLeaseUnavailableException.class);

            limited.close(first);
            second = watchAnother(limited);

            assertThat(second.valid().get()).isTrue();
            limited.ensureValid(second);
        } finally {
            limited.close(second);
            limited.close(first);
            limited.shutdown();
        }
    }

    @Test
    void closeIsIdempotentAndReleasesCapacityOnce() {
        ChatTurnLeaseService.LeaseWatch first = null;
        ChatTurnLeaseService.LeaseWatch second = null;
        ChatTurnLeaseService.LeaseWatch third = null;

        try {
            first = watch(service);
            service.close(first);
            service.close(first);

            assertThat(first.closed().get()).isTrue();
            assertStale(service, first);

            // With maxWatchdogs=2, a double release would erroneously let
            // a third concurrently active watchdog acquire a permit.
            second = watchAnother(service);
            third = watchAnother(service);

            assertThatThrownBy(() -> watchAnother(service))
                    .isInstanceOf(ChatLeaseUnavailableException.class);
        } finally {
            service.close(third);
            service.close(second);
            service.close(first);
        }
    }

    @Test
    void closedWatchDoesNotRenewOrReportOwnershipLoss() {
        ChatTurnLeaseService.LeaseWatch watch = watch(service);
        service.close(watch);

        renew(service, watch);

        assertThat(watch.closed().get()).isTrue();
        assertStale(service, watch);
        verifyNoInteractions(repository, metrics);
    }

    @Test
    void nullAndInvalidWatchArgumentsFailWithoutConsumingCapacity() {
        assertThatThrownBy(() -> service.watch(
                ChatTestFixtures.CHAT_ID,
                null,
                ChatTestFixtures.CLIENT_REQUEST_ID,
                ChatTestFixtures.PROCESSING_TOKEN
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> service.watch(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.CLIENT_REQUEST_ID,
                null
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> service.ensureValid(null))
                .isInstanceOf(NullPointerException.class);

        // Invalid watch requests must not exhaust watchdog capacity.
        ChatTurnLeaseService.LeaseWatch first = null;
        ChatTurnLeaseService.LeaseWatch second = null;
        try {
            first = watch(service);
            second = watchAnother(service);
            assertThatThrownBy(() -> watchAnother(service))
                    .isInstanceOf(ChatLeaseUnavailableException.class);
        } finally {
            service.close(second);
            service.close(first);
        }
    }

    private static void assertStale(
            ChatTurnLeaseService target,
            ChatTurnLeaseService.LeaseWatch watch
    ) {
        assertThat(watch.valid().get()).isFalse();
        assertThatThrownBy(() -> target.ensureValid(watch))
                .isInstanceOf(ChatStaleProcessorException.class);
    }

    private static ChatTurnLeaseService.LeaseWatch watch(
            ChatTurnLeaseService target
    ) {
        return target.watch(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.CLIENT_REQUEST_ID,
                ChatTestFixtures.PROCESSING_TOKEN
        );
    }

    private static ChatTurnLeaseService.LeaseWatch watchAnother(
            ChatTurnLeaseService target
    ) {
        return target.watch(
                ChatTestFixtures.OTHER_CHAT_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()
        );
    }

    private static void renew(
            ChatTurnLeaseService target,
            ChatTurnLeaseService.LeaseWatch watch
    ) {
        ReflectionTestUtils.invokeMethod(
                target,
                "renew",
                watch.turnId(),
                watch.processingToken(),
                watch.valid()
        );
    }

    private static ChatProperties properties(int maxWatchdogs) {
        return new ChatProperties(
                50,
                50,
                100,
                100,
                16_000,
                PROCESSING_LEASE,
                2,
                maxWatchdogs
        );
    }
}
