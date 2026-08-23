package ru.safeai.gateway.auth.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenCleanupJobTest {

    private static final Instant NOW =
            Instant.parse(
                    "2026-08-23T12:00:00Z"
            );

    private static final Duration RETENTION =
            Duration.ofDays(7);

    @Mock
    private RefreshTokenCleanupBatchService batchService;

    @Test
    void partialRootBatchDoesNotStopCleanupChainProgress() {
        int batchSize = 10;

        RefreshTokenCleanupJob job =
                job(
                        batchSize,
                        100
                );

        Instant threshold =
                NOW.minus(RETENTION);

        when(batchService.deleteNextBatch(
                threshold,
                batchSize
        )).thenReturn(
                1,
                1,
                1,
                0
        );

        RefreshTokenCleanupJob.CleanupResult result =
                job.runCleanup();

        assertThat(result.deletedRows())
                .isEqualTo(3L);

        assertThat(result.committedBatches())
                .isEqualTo(3);

        assertThat(result.limitReached())
                .isFalse();

        verify(batchService, times(4))
                .deleteNextBatch(
                        threshold,
                        batchSize
                );
    }

    @Test
    void configuredBatchLimitStopsRunEvenWhenMoreRowsMayRemain() {
        int batchSize = 10;

        RefreshTokenCleanupJob job =
                job(
                        batchSize,
                        2
                );

        Instant threshold =
                NOW.minus(RETENTION);

        when(batchService.deleteNextBatch(
                threshold,
                batchSize
        )).thenReturn(1);

        RefreshTokenCleanupJob.CleanupResult result =
                job.runCleanup();

        assertThat(result.deletedRows())
                .isEqualTo(2L);

        assertThat(result.committedBatches())
                .isEqualTo(2);

        assertThat(result.limitReached())
                .isTrue();

        verify(batchService, times(2))
                .deleteNextBatch(
                        threshold,
                        batchSize
                );
    }

    private RefreshTokenCleanupJob job(
            int batchSize,
            int maxBatches
    ) {
        return new RefreshTokenCleanupJob(
                batchService,
                cookieProperties(),
                new RefreshTokenCleanupProperties(
                        batchSize,
                        maxBatches
                ),
                Clock.fixed(
                        NOW,
                        ZoneOffset.UTC
                )
        );
    }

    private AuthCookieProperties cookieProperties() {
        return new AuthCookieProperties(
                false,
                "Lax",
                Duration.ofMinutes(15),
                Duration.ofDays(30),
                Duration.ofDays(90),
                RETENTION,
                null,
                "safeai-access",
                "safeai-refresh"
        );
    }
}
