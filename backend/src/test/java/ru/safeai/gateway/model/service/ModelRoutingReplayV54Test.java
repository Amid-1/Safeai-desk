package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;
import ru.safeai.gateway.model.domain.ModelRouteReason;
import ru.safeai.gateway.model.domain.ModelRouteRequest;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRoutingReplayV54Test {
    private final ModelRouteDecisionFactory factory = new ModelRouteDecisionFactory();

    @Test
    void replayMustPreserveVersionThreeRagAndInstructionUpperBound() {
        ModelRouteRequest request = ModelTestFixtures.routeRequest(
                "openai:gpt-test", Set.of(), 128L);
        var decision = build(request);
        assertThat(decision.additionalInputUnitUpperBound()).isEqualTo(128L);
        assertThat(decision.decisionIntegrityVersion()).isEqualTo((short) 3);
        assertThat(factory.replayDecision(decision, request).decisionId())
                .isEqualTo(decision.id());
        ModelRouteRequest mutated = new ModelRouteRequest(
                request.organizationId(), request.userId(), request.chatId(),
                request.plannedTurnId(), request.clientRequestId(),
                request.requestContentHash(), request.requestedModelKey(),
                request.userMessage(), request.history(), request.requiredCapabilities(), 129L);
        assertThatThrownBy(() -> factory.replayDecision(decision, mutated))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void replayMustNotReuseDecisionForAnotherTenant() {
        ModelRouteRequest request = ModelTestFixtures.routeRequest(
                "openai:gpt-test", Set.of(), 128L);
        var decision = build(request);
        ModelRouteRequest alien = new ModelRouteRequest(
                ModelTestFixtures.OTHER_ORGANIZATION_ID, request.userId(),
                request.chatId(), request.plannedTurnId(), request.clientRequestId(),
                request.requestContentHash(), request.requestedModelKey(),
                request.userMessage(), request.history(), request.requiredCapabilities(),
                request.additionalInputUnitUpperBound());
        assertThatThrownBy(() -> factory.replayDecision(decision, alien))
                .isInstanceOf(ConflictException.class);
    }

    private ru.safeai.gateway.model.domain.ModelRouteDecision build(
            ModelRouteRequest request
    ) {
        return factory.buildDecision(request, null,
                new ModelRouteDecisionFactory.DecisionDraft(
                        ModelTestFixtures.freeEntry(), "openai:gpt-test", "openai",
                        "gpt-test", 256L, 512L, BigDecimal.ZERO, true,
                        ModelRoutingCostPolicy.BudgetSnapshot.none(),
                        ModelRouteOutcome.ALLOWED, ModelRouteReason.REQUESTED_MODEL),
                request.plannedTurnId(), ModelTestFixtures.NOW);
    }
}
