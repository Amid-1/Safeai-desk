package ru.safeai.gateway.ai.execution;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.provider.AiProviderAttemptContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Application preflight; V72 SQL trigger remains the authoritative race fence. */
class AiExecutionAttemptOrganizationGateTest {

    @Test
    void revokedOrganizationIsRejectedBeforeAnyPhysicalAttemptInsert() {
        ModelExecutionAttemptRepository repository =
                mock(ModelExecutionAttemptRepository.class);
        UUID planId = UUID.randomUUID();
        when(repository.organizationEpochCurrent(planId)).thenReturn(false);
        AiExecutionAttemptRecorder recorder = new AiExecutionAttemptRecorder(
                repository, Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneOffset.UTC));
        AiProviderAttemptContext attempt = new AiProviderAttemptContext(
                UUID.randomUUID(), UUID.randomUUID(), 1, 1);

        assertThatThrownBy(() -> recorder.started(
                planId, ProviderExecutionTarget.staticTarget("mock", "mock-model"), attempt))
                .isInstanceOf(AiOrganizationExecutionDeniedException.class);
        verify(repository, never()).saveAndFlush(any(ModelExecutionAttemptEntity.class));
    }
}
