package ru.safeai.gateway.ai.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.model.service.ModelRouteExecutionIdentity;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiExecutionPlanServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-09-20T00:00:00Z");

    @Mock
    private ModelExecutionPlanRepository repository;

    @Mock
    private ModelExecutionAttemptRepository attemptRepository;

    @Test
    void createsExactlyOnePlanWithExactLogicalAndPhysicalIdentity() {
        AiExecutionRequest request = request();
        UUID operationId = request.aiRequest().providerOperationId();

        when(repository.findByProviderOperationId(operationId))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ModelExecutionPlanEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ModelExecutionPlanEntity actual = service().createOrRead(request);

        ArgumentCaptor<ModelExecutionPlanEntity> saved =
                ArgumentCaptor.forClass(ModelExecutionPlanEntity.class);
        verify(repository).findByProviderOperationId(operationId);
        verify(repository).saveAndFlush(saved.capture());
        verifyNoMoreInteractions(repository);

        assertThat(actual).isSameAs(saved.getValue());
        assertThat(actual.getId()).isNotNull();
        assertThat(actual.getProviderOperationId()).isEqualTo(operationId);
        assertThat(actual.getChatTurnId()).isEqualTo(request.chatTurnId());
        assertThat(actual.getOrganizationId())
                .isEqualTo(request.aiRequest().organizationId());
        assertThat(actual.getModelRouteDecisionId())
                .isEqualTo(request.modelRouteDecisionId());
        assertThat(actual.getRequestedModel()).isEqualTo("gpt-5");
        assertThat(actual.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void existingPlanIsReadWithoutReinsertionOrIdentityMutation() {
        AiExecutionRequest request = request();
        UUID operationId = request.aiRequest().providerOperationId();
        ModelExecutionPlanEntity existing = ModelExecutionPlanEntity.create(
                operationId,
                request.chatTurnId(),
                request.aiRequest().organizationId(),
                request.modelRouteDecisionId(),
                "gpt-5",
                NOW
        );

        UUID persistedId = existing.getId();
        Instant persistedAt = existing.getCreatedAt();

        when(repository.findByProviderOperationId(operationId))
                .thenReturn(Optional.of(existing));

        ModelExecutionPlanEntity result = service().createOrRead(request);

        // createOrRead is idempotent at the persistence layer: finding the
        // original plan does not authorize a new physical execution.
        assertThat(result).isSameAs(existing);
        assertThat(result.getId()).isEqualTo(persistedId);
        assertThat(result.getCreatedAt()).isEqualTo(persistedAt);
        assertThat(result.getProviderOperationId()).isEqualTo(operationId);
        assertThat(result.getChatTurnId()).isEqualTo(request.chatTurnId());
        assertThat(result.getOrganizationId())
                .isEqualTo(request.aiRequest().organizationId());
        assertThat(result.getModelRouteDecisionId())
                .isEqualTo(request.modelRouteDecisionId());
        assertThat(result.getRequestedModel()).isEqualTo("gpt-5");

        verify(repository).findByProviderOperationId(operationId);
        verify(repository, never()).saveAndFlush(any(ModelExecutionPlanEntity.class));
        verifyNoMoreInteractions(repository);
    }

    private AiExecutionPlanService service() {
        return new AiExecutionPlanService(
                repository,
                attemptRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static AiExecutionRequest request() {
        UUID turn = UUID.randomUUID();
        UUID decision = UUID.randomUUID();

        AiChatRequest base = new AiChatRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                "hello",
                List.of(),
                512L,
                64
        );

        ModelRouteExecutionIdentity identity =
                mock(ModelRouteExecutionIdentity.class);
        when(identity.decisionId()).thenReturn(decision);
        when(identity.chatTurnId()).thenReturn(turn);

        return new AiExecutionRequest(
                turn,
                decision,
                base,
                ProviderExecutionTarget.staticTarget("openai", "gpt-5"),
                identity,
                base
        );
    }
}
