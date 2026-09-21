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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiExecutionPlanServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");
    @Mock ModelExecutionPlanRepository repository;

    @Test
    void createsExactlyOnePlanWithExactLogicalAndPhysicalIdentity() {
        AiExecutionRequest request = request();
        when(repository.findByProviderOperationId(request.aiRequest().providerOperationId()))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ModelExecutionPlanEntity actual = service().createOrRead(request);

        ArgumentCaptor<ModelExecutionPlanEntity> saved =
                ArgumentCaptor.forClass(ModelExecutionPlanEntity.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(actual).isSameAs(saved.getValue());
        assertThat(actual.getId()).isNotNull();
        assertThat(actual.getProviderOperationId()).isEqualTo(request.aiRequest().providerOperationId());
        assertThat(actual.getChatTurnId()).isEqualTo(request.chatTurnId());
        assertThat(actual.getOrganizationId()).isEqualTo(request.aiRequest().organizationId());
        assertThat(actual.getModelRouteDecisionId()).isEqualTo(request.modelRouteDecisionId());
        assertThat(actual.getRequestedModel()).isEqualTo("gpt-5");
        assertThat(actual.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void existingPlanBlocksReplayBeforeAnyInsertRegardlessOfTerminalHistory() {
        AiExecutionRequest request = request();
        ModelExecutionPlanEntity existing = ModelExecutionPlanEntity.create(
                request.aiRequest().providerOperationId(), request.chatTurnId(),
                request.aiRequest().organizationId(), request.modelRouteDecisionId(), "gpt-5", NOW);
        when(repository.findByProviderOperationId(request.aiRequest().providerOperationId()))
                .thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service().createOrRead(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
        verify(repository, never()).saveAndFlush(any());
    }

    private AiExecutionPlanService service() {
        return new AiExecutionPlanService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AiExecutionRequest request() {
        UUID turn = UUID.randomUUID();
        UUID decision = UUID.randomUUID();
        AiChatRequest base = new AiChatRequest(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), null, null, "hello", List.of(), 512L, 64);
        ModelRouteExecutionIdentity identity = mock(ModelRouteExecutionIdentity.class);
        when(identity.decisionId()).thenReturn(decision);
        when(identity.chatTurnId()).thenReturn(turn);
        return new AiExecutionRequest(turn, decision, base,
                ProviderExecutionTarget.staticTarget("openai", "gpt-5"), identity, base);
    }
}
