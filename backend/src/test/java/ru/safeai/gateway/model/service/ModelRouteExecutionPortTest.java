package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.ai.execution.AiExecutionIdentity;
import ru.safeai.gateway.ai.execution.AiKnowledgeExecutionMode;
import ru.safeai.gateway.model.repository.ModelRouteDecisionRepository;
import ru.safeai.gateway.model.spi.ModelRequestContentHashPort;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ModelRouteExecutionPortTest {

    @Mock ModelRouteDecisionRepository decisions;
    @Mock ModelRequestContentHashPort hasher;
    @Mock ModelRouteReservedRequestService reservations;

    @Test
    void rejectsForgedExecutionCapabilityWithoutTouchingPersistence() {
        var service = new ModelRouteExecutionBindingService(decisions, hasher, reservations);
        AiExecutionIdentity forged = new AiExecutionIdentity() {
            @Override public UUID decisionId() { return UUID.randomUUID(); }
            @Override public UUID chatTurnId() { return UUID.randomUUID(); }
        };
        assertThatThrownBy(() -> service.authorizePhysicalExecution(
                forged, UUID.randomUUID(), UUID.randomUUID(), null, null, "openai", "gpt-test"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not issued by Model Control Plane");
        verifyNoInteractions(decisions, hasher, reservations);
    }

    @Test
    void rejectsUnknownKnowledgeModeWithoutTouchingPersistence() {
        var service = new ModelRouteExecutionBindingService(decisions, hasher, reservations);
        AiKnowledgeExecutionMode untrusted = () -> true;
        assertThatThrownBy(() -> service.bindExecution(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, UUID.randomUUID(), untrusted
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unrecognized knowledge execution mode");
        verifyNoInteractions(decisions, hasher, reservations);
    }
}
