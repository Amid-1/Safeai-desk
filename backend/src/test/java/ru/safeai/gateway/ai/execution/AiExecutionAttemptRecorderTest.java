package ru.safeai.gateway.ai.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.ai.provider.AiProviderAttemptContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level validation of the physical-attempt lifecycle and the V72
 * organization-epoch preflight. The authoritative concurrency fence is the
 * PostgreSQL BEFORE INSERT trigger and must be exercised in integration tests.
 */
@ExtendWith(MockitoExtension.class)
class AiExecutionAttemptRecorderTest {

    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");
    private static final UUID PLAN = UUID.randomUUID();
    private static final UUID OPERATION = UUID.randomUUID();
    private static final ProviderExecutionTarget TARGET =
            ProviderExecutionTarget.staticTarget("openai", "gpt-5");

    @Mock
    private ModelExecutionAttemptRepository repository;

    @Test
    void firstAttemptMustBeNumberOneAndPersistsStartedEvidence() {
        allowOrganizationEpoch();
        when(repository.findTopByExecutionPlanIdOrderByAttemptNumberDesc(PLAN))
                .thenReturn(Optional.empty());

        AiProviderAttemptContext attempt = attempt(1);
        recorder().started(PLAN, TARGET, attempt);

        ArgumentCaptor<ModelExecutionAttemptEntity> saved =
                ArgumentCaptor.forClass(ModelExecutionAttemptEntity.class);
        InOrder order = inOrder(repository);
        order.verify(repository).organizationEpochCurrent(PLAN);
        order.verify(repository).findTopByExecutionPlanIdOrderByAttemptNumberDesc(PLAN);
        order.verify(repository).saveAndFlush(saved.capture());

        ModelExecutionAttemptEntity started = saved.getValue();
        assertThat(started.getExecutionPlanId()).isEqualTo(PLAN);
        assertThat(started.getProviderAttemptId()).isEqualTo(attempt.attemptId());
        assertThat(started.getAttemptNumber()).isEqualTo(1);
        assertThat(started.getOutcome()).isEqualTo(ExecutionAttemptOutcome.STARTED);
        assertThat(started.getOutcomeCertainty()).isEqualTo(OutcomeCertainty.AMBIGUOUS);
        assertThat(started.getRetrySafety()).isEqualTo(RetrySafety.SAME_TARGET_RETRY_FORBIDDEN);
        assertThat(started.getFallbackSafety()).isEqualTo(FallbackSafety.FALLBACK_FORBIDDEN);
        assertThat(started.getProviderType()).isEqualTo("openai");
        assertThat(started.getRequestedPhysicalModel()).isEqualTo("gpt-5");
        assertThat(started.getStartedAt()).isEqualTo(NOW);
        assertThat(started.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void gapBeforeFirstAttemptFailsClosed() {
        allowOrganizationEpoch();
        when(repository.findTopByExecutionPlanIdOrderByAttemptNumberDesc(PLAN))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> recorder().started(PLAN, TARGET, attempt(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("#1");

        verify(repository).organizationEpochCurrent(PLAN);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void pendingAmbiguousSucceededOrRetryForbiddenPredecessorCannotBeFollowed() {
        for (ExecutionAttemptOutcome outcome : new ExecutionAttemptOutcome[] {
                ExecutionAttemptOutcome.STARTED,
                ExecutionAttemptOutcome.AMBIGUOUS,
                ExecutionAttemptOutcome.SUCCEEDED,
                ExecutionAttemptOutcome.FAILED
        }) {
            reset(repository);
            allowOrganizationEpoch();
            ModelExecutionAttemptEntity prior = predecessor(
                    outcome,
                    outcome == ExecutionAttemptOutcome.FAILED
                            ? RetrySafety.SAME_TARGET_RETRY_FORBIDDEN
                            : RetrySafety.SAME_TARGET_RETRY_ALLOWED
            );
            when(repository.findTopByExecutionPlanIdOrderByAttemptNumberDesc(PLAN))
                    .thenReturn(Optional.of(prior));

            assertThatThrownBy(() -> recorder().started(PLAN, TARGET, attempt(2)))
                    .as(outcome.name())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not authorize");

            verify(repository).organizationEpochCurrent(PLAN);
            verify(repository, never()).saveAndFlush(any());
        }
    }

    @Test
    void allowedFailedRetryRequiresExactSameProviderConfigDeploymentAndModel() {
        allowOrganizationEpoch();
        ModelExecutionAttemptEntity prior = predecessor(
                ExecutionAttemptOutcome.FAILED,
                RetrySafety.SAME_TARGET_RETRY_ALLOWED
        );
        when(repository.findTopByExecutionPlanIdOrderByAttemptNumberDesc(PLAN))
                .thenReturn(Optional.of(prior));

        ProviderExecutionTarget changed = new ProviderExecutionTarget(
                "openai",
                "static:openai",
                "static-v2",
                "static:openai:gpt-5",
                "static-v1",
                "gpt-5",
                Set.of("gpt-5")
        );

        assertThatThrownBy(() -> recorder().started(PLAN, changed, attempt(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Same-target retry");

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void exactSameTargetAfterKnownRejectedRetryAllowedMayAdvanceOnce() {
        allowOrganizationEpoch();
        ModelExecutionAttemptEntity prior = predecessor(
                ExecutionAttemptOutcome.FAILED,
                RetrySafety.SAME_TARGET_RETRY_ALLOWED
        );
        when(repository.findTopByExecutionPlanIdOrderByAttemptNumberDesc(PLAN))
                .thenReturn(Optional.of(prior));

        recorder().started(PLAN, TARGET, attempt(2));

        verify(repository).saveAndFlush(argThat(value ->
                value.getAttemptNumber() == 2
                        && value.getExecutionPlanId().equals(PLAN)
                        && value.getProviderConfigurationVersion().equals("static-v1")
                        && value.getOutcome() == ExecutionAttemptOutcome.STARTED));
    }

    @Test
    void organizationDisabledOrEpochChangedDeniesBeforePredecessorReadAndInsert() {
        when(repository.organizationEpochCurrent(PLAN)).thenReturn(false);

        assertThatThrownBy(() -> recorder().started(PLAN, TARGET, attempt(1)))
                .isInstanceOf(AiOrganizationExecutionDeniedException.class)
                .hasMessageContaining("Organization disabled or security epoch changed");

        verify(repository).organizationEpochCurrent(PLAN);
        verify(repository, never()).findTopByExecutionPlanIdOrderByAttemptNumberDesc(any());
        verify(repository, never()).saveAndFlush(any());
    }


    @Test
    void unavailableSecurityEpochPreflightFailsClosedWithoutWritingStarted() {
        IllegalStateException outage = new IllegalStateException(
                "organization security-state database unavailable"
        );
        when(repository.organizationEpochCurrent(PLAN)).thenThrow(outage);

        assertThatThrownBy(() -> recorder().started(PLAN, TARGET, attempt(1)))
                .isSameAs(outage);

        verify(repository, never()).findTopByExecutionPlanIdOrderByAttemptNumberDesc(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void authorizationRevokedAfterPreflightIsDeniedByAuthoritativeInsertFence() {
        allowOrganizationEpoch();
        when(repository.findTopByExecutionPlanIdOrderByAttemptNumberDesc(PLAN))
                .thenReturn(Optional.empty());

        RuntimeException databaseFailure = new RuntimeException(
                "failed to insert STARTED",
                new IllegalStateException(
                        "V72 physical AI attempt denied: organization disabled or authorization epoch revoked"
                )
        );
        when(repository.saveAndFlush(any(ModelExecutionAttemptEntity.class)))
                .thenThrow(databaseFailure);

        assertThatThrownBy(() -> recorder().started(PLAN, TARGET, attempt(1)))
                .isInstanceOf(AiOrganizationExecutionDeniedException.class)
                .hasMessage("Organization authorization revoked before STARTED")
                .hasCause(databaseFailure);

        // Even after a successful advisory preflight, the V72 INSERT fence
        // must reject the STARTED row and preserve the original DB failure.
        InOrder order = inOrder(repository);
        order.verify(repository).organizationEpochCurrent(PLAN);
        order.verify(repository).findTopByExecutionPlanIdOrderByAttemptNumberDesc(PLAN);
        order.verify(repository).saveAndFlush(any(ModelExecutionAttemptEntity.class));
        order.verifyNoMoreInteractions();
    }

    @Test
    void unrelatedInsertFailureIsPropagatedWithoutPretendingItWasAnEpochDenial() {
        allowOrganizationEpoch();
        when(repository.findTopByExecutionPlanIdOrderByAttemptNumberDesc(PLAN))
                .thenReturn(Optional.empty());

        RuntimeException failure = new IllegalStateException("unrelated storage error");
        when(repository.saveAndFlush(any(ModelExecutionAttemptEntity.class)))
                .thenThrow(failure);

        assertThatThrownBy(() -> recorder().started(PLAN, TARGET, attempt(1)))
                .isSameAs(failure);

        verify(repository).saveAndFlush(any(ModelExecutionAttemptEntity.class));
    }

    @Test
    void failedWithUncertainOutcomeCannotAuthorizeAnotherAttempt() {
        allowOrganizationEpoch();
        ModelExecutionAttemptEntity prior = predecessor(
                ExecutionAttemptOutcome.FAILED,
                OutcomeCertainty.AMBIGUOUS,
                RetrySafety.SAME_TARGET_RETRY_ALLOWED
        );
        when(repository.findTopByExecutionPlanIdOrderByAttemptNumberDesc(PLAN))
                .thenReturn(Optional.of(prior));

        assertThatThrownBy(() -> recorder().started(PLAN, TARGET, attempt(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not authorize");

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void gapsInRetryAttemptNumbersAreRejectedEvenAfterKnownSafeFailure() {
        allowOrganizationEpoch();
        ModelExecutionAttemptEntity prior = mock(ModelExecutionAttemptEntity.class);
        when(prior.getAttemptNumber()).thenReturn(1);
        // These values establish an otherwise retry-safe predecessor; the
        // gap check must reject #3 before it needs to read them.
        lenient().when(prior.getOutcome()).thenReturn(ExecutionAttemptOutcome.FAILED);
        lenient().when(prior.getOutcomeCertainty())
                .thenReturn(OutcomeCertainty.KNOWN_REJECTED);
        lenient().when(prior.getRetrySafety())
                .thenReturn(RetrySafety.SAME_TARGET_RETRY_ALLOWED);
        when(repository.findTopByExecutionPlanIdOrderByAttemptNumberDesc(PLAN))
                .thenReturn(Optional.of(prior));

        assertThatThrownBy(() -> recorder().started(PLAN, TARGET, attempt(3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not authorize");

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void knownNotExecutedFailureCanBeRetriedOnlyAgainstTheSameTarget() {
        allowOrganizationEpoch();
        ModelExecutionAttemptEntity prior = predecessor(
                ExecutionAttemptOutcome.FAILED,
                OutcomeCertainty.KNOWN_NOT_EXECUTED,
                RetrySafety.SAME_TARGET_RETRY_ALLOWED
        );
        when(repository.findTopByExecutionPlanIdOrderByAttemptNumberDesc(PLAN))
                .thenReturn(Optional.of(prior));

        recorder().started(PLAN, TARGET, attempt(2));

        verify(repository).saveAndFlush(argThat(value ->
                value.getAttemptNumber() == 2
                        && value.getProviderType().equals(TARGET.providerType())
                        && value.getRequestedPhysicalModel().equals(TARGET.requestedPhysicalModel())));
    }

    private void allowOrganizationEpoch() {
        // Explicit opt-in in each positive test: Mockito's default boolean
        // return value is false, which correctly represents fail-closed V72.
        when(repository.organizationEpochCurrent(PLAN)).thenReturn(true);
    }

    private static ModelExecutionAttemptEntity predecessor(
            ExecutionAttemptOutcome outcome,
            RetrySafety retry
    ) {
        return predecessor(outcome, OutcomeCertainty.KNOWN_REJECTED, retry);
    }

    private static ModelExecutionAttemptEntity predecessor(
            ExecutionAttemptOutcome outcome,
            OutcomeCertainty certainty,
            RetrySafety retry
    ) {
        ModelExecutionAttemptEntity previous = mock(ModelExecutionAttemptEntity.class);
        when(previous.getAttemptNumber()).thenReturn(1);
        when(previous.getOutcome()).thenReturn(outcome);

        if (outcome == ExecutionAttemptOutcome.FAILED) {
            when(previous.getOutcomeCertainty()).thenReturn(certainty);
            if (certainty == OutcomeCertainty.KNOWN_NOT_EXECUTED
                    || certainty == OutcomeCertainty.KNOWN_REJECTED) {
                when(previous.getRetrySafety()).thenReturn(retry);
                if (retry == RetrySafety.SAME_TARGET_RETRY_ALLOWED) {
                    // Some comparisons may short-circuit on a mismatched target.
                    lenient().when(previous.getProviderType()).thenReturn(TARGET.providerType());
                    lenient().when(previous.getProviderConfigurationRef())
                            .thenReturn(TARGET.providerConfigurationRef());
                    lenient().when(previous.getProviderConfigurationVersion())
                            .thenReturn(TARGET.providerConfigurationVersion());
                    lenient().when(previous.getDeploymentRef()).thenReturn(TARGET.deploymentRef());
                    lenient().when(previous.getDeploymentVersion()).thenReturn(TARGET.deploymentVersion());
                    lenient().when(previous.getRequestedPhysicalModel())
                            .thenReturn(TARGET.requestedPhysicalModel());
                }
            }
        }
        return previous;
    }

    private AiExecutionAttemptRecorder recorder() {
        return new AiExecutionAttemptRecorder(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static AiProviderAttemptContext attempt(int number) {
        return new AiProviderAttemptContext(OPERATION, UUID.randomUUID(), number, 3);
    }
}
