package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.audit.repository.AuditEventRepository;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditRetentionBatchServiceTest {

    private static final Duration RETENTION_PERIOD =
            Duration.ofDays(
                    365
            );

    private final AuditEventRepository repository =
            mock(
                    AuditEventRepository.class
            );

    private final AuditRetentionBatchService service =
            new AuditRetentionBatchService(
                    repository
            );

    @Test
    void deleteBatchDelegatesRetentionPeriodToRepositoryAsSeconds() {
        long retentionSeconds =
                RETENTION_PERIOD.getSeconds();

        when(
                repository.deleteRetentionBatch(
                        retentionSeconds,
                        10_000
                )
        ).thenReturn(
                321
        );

        assertThat(
                service.deleteBatch(
                        RETENTION_PERIOD,
                        10_000
                )
        ).isEqualTo(
                321
        );

        verify(repository)
                .deleteRetentionBatch(
                        retentionSeconds,
                        10_000
                );
    }

    @Test
    void deleteBatchValidatesArguments() {
        assertThatThrownBy(() ->
                service.deleteBatch(
                        null,
                        100
                )
        ).isInstanceOf(
                NullPointerException.class
        );

        assertThatThrownBy(() ->
                service.deleteBatch(
                        Duration.ZERO,
                        100
                )
        ).isInstanceOf(
                IllegalArgumentException.class
        );

        assertThatThrownBy(() ->
                service.deleteBatch(
                        Duration.ofMillis(
                                500
                        ),
                        100
                )
        ).isInstanceOf(
                IllegalArgumentException.class
        );

        assertThatThrownBy(() ->
                service.deleteBatch(
                        RETENTION_PERIOD,
                        0
                )
        ).isInstanceOf(
                IllegalArgumentException.class
        );
    }
}
