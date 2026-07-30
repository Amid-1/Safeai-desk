package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.audit.repository.AuditEventRepository;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditRetentionBatchServiceTest {

    private final AuditEventRepository repository =
            mock(AuditEventRepository.class);

    private final AuditRetentionBatchService service =
            new AuditRetentionBatchService(repository);

    @Test
    void deleteBatchDelegatesToRepository() {
        Instant threshold =
                Instant.parse(
                        "2023-06-12T12:00:00Z"
                );

        when(repository.deleteBatchCreatedBefore(
                threshold,
                10_000
        )).thenReturn(321);

        assertThat(
                service.deleteBatch(
                        threshold,
                        10_000
                )
        ).isEqualTo(321);

        verify(repository)
                .deleteBatchCreatedBefore(
                        threshold,
                        10_000
                );
    }

    @Test
    void deleteBatchValidatesArguments() {
        assertThatThrownBy(() ->
                service.deleteBatch(null, 100)
        ).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() ->
                service.deleteBatch(
                        Instant.parse(
                                "2026-07-30T08:00:00Z"
                        ),
                        0
                )
        ).isInstanceOf(
                IllegalArgumentException.class
        );
    }
}
