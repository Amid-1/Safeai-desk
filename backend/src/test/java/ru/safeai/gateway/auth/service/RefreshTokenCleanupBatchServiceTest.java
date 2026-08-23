package ru.safeai.gateway.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.auth.repository.RefreshTokenRepository;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenCleanupBatchServiceTest {

    private static final Instant THRESHOLD =
            Instant.parse(
                    "2026-08-16T12:00:00Z"
            );

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private RefreshTokenCleanupBatchService service;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenCleanupBatchService(
                refreshTokenRepository
        );
    }

    @Test
    void validBatchDelegatesThresholdAndSizeAndReturnsDeletedCount() {
        when(refreshTokenRepository.deleteExpiredBatch(
                THRESHOLD,
                500
        )).thenReturn(137);

        assertThat(
                service.deleteNextBatch(
                        THRESHOLD,
                        500
                )
        ).isEqualTo(137);

        verify(refreshTokenRepository)
                .deleteExpiredBatch(
                        THRESHOLD,
                        500
                );
    }

    @Test
    void nullThresholdIsRejectedBeforeRepositoryCall() {
        assertThatThrownBy(() ->
                service.deleteNextBatch(
                        null,
                        500
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessageContaining(
                        "threshold"
                );

        verify(refreshTokenRepository, never())
                .deleteExpiredBatch(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt()
                );
    }

    @Test
    void batchSizeOutsideAllowedRangeIsRejectedBeforeRepositoryCall() {
        assertInvalidBatchSize(0);
        assertInvalidBatchSize(10_001);
    }

    @Test
    void repositoryCannotReportNegativeDeletedCount() {
        when(refreshTokenRepository.deleteExpiredBatch(
                THRESHOLD,
                10
        )).thenReturn(-1);

        assertThatThrownBy(() ->
                service.deleteNextBatch(
                        THRESHOLD,
                        10
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "некорректное количество"
                );
    }

    @Test
    void repositoryCannotReportMoreRowsThanRequestedBatchSize() {
        when(refreshTokenRepository.deleteExpiredBatch(
                THRESHOLD,
                10
        )).thenReturn(11);

        assertThatThrownBy(() ->
                service.deleteNextBatch(
                        THRESHOLD,
                        10
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "некорректное количество"
                );
    }

    private void assertInvalidBatchSize(
            int batchSize
    ) {
        assertThatThrownBy(() ->
                service.deleteNextBatch(
                        THRESHOLD,
                        batchSize
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "1..10000"
                );

        verify(refreshTokenRepository, never())
                .deleteExpiredBatch(
                        THRESHOLD,
                        batchSize
                );
    }
}
