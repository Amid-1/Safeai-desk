package ru.safeai.gateway.ai.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.ai.provider.AiProviderAttemptContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiExecutionAttemptRecorderTest {
    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");
    private static final UUID PLAN = UUID.randomUUID();
    private static final UUID OPERATION = UUID.randomUUID();
    private static final ProviderExecutionTarget TARGET =
            ProviderExecutionTarget.staticTarget("openai", "gpt-5");
    @Mock ModelExecutionAttemptRepository repository;

    @Test
    void firstAttemptMustBeNumberOneAndPersistsStartedEvidence() {
        when(repository.findTopByExecutionPlanIdOrderByAttemptNumberDesc(PLAN))
                .thenReturn(Optional.empty());
        recorder().started(PLAN, TARGET, attempt(1));
        ArgumentCaptor<ModelExecutionAttemptEntity> saved =
                ArgumentCaptor.forClass(ModelExecutionAttemptEntity.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getAttemptNumber()).isEqualTo(1);
        assertThat(saved.getValue().getOutcome()).isEqualTo(ExecutionAttemptOutcome.STARTED);
        assertThat(saved.getValue().getOutcomeCertainty()).isEqualTo(OutcomeCertainty.AMBIGUOUS);
        assertThat(saved.getValue().getProviderType()).isEqualTo("openai");
        assertThat(saved.getValue().getRequestedPhysicalModel()).isEqualTo("gpt-5");
    }

    @Test
    void gapBeforeFirstAttemptFailsClosed() {
        when(repository.findTopByExecutionPlanIdOrderByAttemptNumberDesc(PLAN))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> recorder().started(PLAN, TARGET, attempt(2)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("#1");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void pendingAmbiguousSucceededOrRetryForbiddenPredecessorCannotBeFollowed() {
        for (ExecutionAttemptOutcome outcome : new ExecutionAttemptOutcome[] {
                ExecutionAttemptOutcome.STARTED, ExecutionAttemptOutcome.AMBIGUOUS,
                ExecutionAttemptOutcome.SUCCEEDED, ExecutionAttemptOutcome.FAILED}) {
            reset(repository);
            ModelExecutionAttemptEntity prior = predecessor(outcome,
                    outcome == ExecutionAttemptOutcome.FAILED
                            ? RetrySafety.SAME_TARGET_RETRY_FORBIDDEN
                            : RetrySafety.SAME_TARGET_RETRY_ALLOWED);
            when(repository.findTopByExecutionPlanIdOrderByAttemptNumberDesc(PLAN))
                    .thenReturn(Optional.of(prior));
            assertThatThrownBy(() -> recorder().started(PLAN, TARGET, attempt(2)))
                    .as(outcome.name()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not authorize");
            verify(repository, never()).saveAndFlush(any());
        }
    }

    @Test
    void allowedFailedRetryRequiresExactSameProviderConfigDeploymentAndModel() {
        ModelExecutionAttemptEntity prior = predecessor(ExecutionAttemptOutcome.FAILED,
                RetrySafety.SAME_TARGET_RETRY_ALLOWED);
        when(repository.findTopByExecutionPlanIdOrderByAttemptNumberDesc(PLAN))
                .thenReturn(Optional.of(prior));
        ProviderExecutionTarget changed = new ProviderExecutionTarget("openai",
                "static:openai", "static-v2", "static:openai:gpt-5", "static-v1",
                "gpt-5", java.util.Set.of("gpt-5"));
        assertThatThrownBy(() -> recorder().started(PLAN, changed, attempt(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Same-target retry");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void exactSameTargetAfterKnownRejectedRetryAllowedMayAdvanceOnce() {
        ModelExecutionAttemptEntity prior = predecessor(ExecutionAttemptOutcome.FAILED,
                RetrySafety.SAME_TARGET_RETRY_ALLOWED);
        when(repository.findTopByExecutionPlanIdOrderByAttemptNumberDesc(PLAN))
                .thenReturn(Optional.of(prior));
        recorder().started(PLAN, TARGET, attempt(2));
        verify(repository).saveAndFlush(argThat(value ->
                value.getAttemptNumber() == 2
                        && value.getProviderConfigurationVersion().equals("static-v1")));
    }

    private ModelExecutionAttemptEntity predecessor(ExecutionAttemptOutcome outcome, RetrySafety retry) {
        ModelExecutionAttemptEntity previous = mock(ModelExecutionAttemptEntity.class);
        when(previous.getAttemptNumber()).thenReturn(1);
        when(previous.getOutcome()).thenReturn(outcome);
        if (outcome == ExecutionAttemptOutcome.FAILED) {
            when(previous.getOutcomeCertainty()).thenReturn(OutcomeCertainty.KNOWN_REJECTED);
            when(previous.getRetrySafety()).thenReturn(retry);
            if (retry == RetrySafety.SAME_TARGET_RETRY_ALLOWED) {
                lenient().when(previous.getProviderType()).thenReturn(TARGET.providerType());
                lenient().when(previous.getProviderConfigurationRef()).thenReturn(TARGET.providerConfigurationRef());
                lenient().when(previous.getProviderConfigurationVersion()).thenReturn(TARGET.providerConfigurationVersion());
                lenient().when(previous.getDeploymentRef()).thenReturn(TARGET.deploymentRef());
                lenient().when(previous.getDeploymentVersion()).thenReturn(TARGET.deploymentVersion());
                lenient().when(previous.getRequestedPhysicalModel()).thenReturn(TARGET.requestedPhysicalModel());
            }
        }
        return previous;
    }

    private AiExecutionAttemptRecorder recorder() {
        return new AiExecutionAttemptRecorder(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AiProviderAttemptContext attempt(int number) {
        return new AiProviderAttemptContext(OPERATION, UUID.randomUUID(), number, 3);
    }
}
