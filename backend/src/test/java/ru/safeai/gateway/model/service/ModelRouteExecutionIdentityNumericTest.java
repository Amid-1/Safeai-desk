package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.input.AiInputUnitEstimator;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.knowledge.rag.KnowledgeMode;
import ru.safeai.gateway.model.domain.ModelRouteDecision;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;
import ru.safeai.gateway.model.domain.ModelRouteReason;
import ru.safeai.gateway.model.domain.ModelRouteRequest;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRouteExecutionIdentityNumericTest {

    private final UUID turnId = UUID.randomUUID();
    private final UUID clientId = UUID.randomUUID();
    private final UUID operationId = UUID.randomUUID();
    private final KnowledgeMode noKnowledge = Arrays.stream(KnowledgeMode.values())
            .filter(mode -> !mode.usesKnowledge()).findFirst().orElseThrow();
    private final long additional = 257L;
    private final long input = AiInputUnitEstimator.estimateBaseRequest("hello", List.of()) + additional;
    private final int output = 2_000;

    @Test
    void equalNumericValuesOutsideWrapperCacheRemainExecutable() {
        ModelRouteDecision decision = decision(input, (long) output);
        AiChatRequest reserved = request(input, output);
        ModelRouteExecutionIdentity identity = bind(decision, reserved);

        assertThat(identity.reservedInputUnits()).isEqualTo(input);
        assertThat(identity.maxOutputUnits()).isEqualTo(output);
        assertThatCode(() -> identity.requireSameDecision(decision)).doesNotThrowAnyException();
        assertThatCode(() -> identity.requireSameBase(reserved)).doesNotThrowAnyException();
        assertThatCode(() -> identity.requirePreparedBase(reserved, reserved)).doesNotThrowAnyException();
    }

    @Test
    void bindRejectsMissingReservedInputOrOutputCap() {
        ModelRouteDecision decision = decision(input, (long) output);
        assertThatThrownBy(() -> bind(decision, request(null, output))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> bind(decision, request(input, null))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bindRejectsDifferentNumericCaps() {
        ModelRouteDecision decision = decision(input, (long) output);
        assertThatThrownBy(() -> bind(decision, request(input + 1, output))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> bind(decision, request(input, output + 1))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void baseCheckRejectsNullAndChangedCaps() {
        ModelRouteExecutionIdentity identity = bind(decision(input, (long) output), request(input, output));
        assertThatThrownBy(() -> identity.requireSameBase(request(null, output)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> identity.requireSameBase(request(input, null)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> identity.requireSameBase(request(input + 1, output)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> identity.requireSameBase(request(input, output + 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void preparedCheckRejectsNullAndChangedCaps() {
        AiChatRequest reserved = request(input, output);
        ModelRouteExecutionIdentity identity = bind(decision(input, (long) output), reserved);
        assertThatThrownBy(() -> identity.requirePreparedBase(reserved, request(null, output)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> identity.requirePreparedBase(reserved, request(input, null)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> identity.requirePreparedBase(reserved, request(input + 1, output)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> identity.requirePreparedBase(reserved, request(input, output + 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void persistedDecisionWithMissingEstimateIsNotExecutable() {
        assertThatThrownBy(() -> bind(decision(null, (long) output), request(input, output)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> bind(decision(input, null), request(input, output)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replayAcceptsSameAdditionalBoundAndRejectsChangedBound() {
        ModelRouteDecisionFactory factory = new ModelRouteDecisionFactory();
        ModelRouteDecision decision = decision(input, (long) output);
        assertThat(factory.replayDecision(decision, routeRequest(additional)).decisionId())
                .isEqualTo(decision.id());
        assertThatThrownBy(() -> factory.replayDecision(decision, routeRequest(additional + 1)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void catalogVersionOutsideIntegerCacheIsComparedByValue() {
        ModelRouteDecision original = decision(input, (long) output);
        ModelRouteDecision revised = ModelRouteDecisionIntegrity.seal(copyEvidence(original, (short) 3,
                2_000, additional, input, (long) output, ""));
        ModelRouteExecutionIdentity identity = bind(revised, request(input, output));

        assertThat(identity.catalogVersion()).isEqualTo(2_000);
        assertThatCode(() -> identity.requireSameDecision(revised)).doesNotThrowAnyException();
        ModelRouteDecision anotherVersion = ModelRouteDecisionIntegrity.seal(copyEvidence(revised, (short) 3,
                2_001, additional, input, (long) output, ""));
        assertThatThrownBy(() -> identity.requireSameDecision(anotherVersion))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void outputEstimateIsNotSilentlyNarrowedToInt() {
        long tooLarge = (1L << 32) + output;
        assertThatThrownBy(() -> bind(decision(input, tooLarge), request(input, output)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sealedButMissingOrChangedDecisionEstimatesCannotReplaceTheOriginal() {
        ModelRouteDecision original = decision(input, (long) output);
        ModelRouteExecutionIdentity identity = bind(original, request(input, output));
        for (ModelRouteDecision revised : List.of(
                copyEvidence(original, (short) 3, original.selectedCatalogVersion(), additional, null, (long) output, ""),
                copyEvidence(original, (short) 3, original.selectedCatalogVersion(), additional, input, null, ""),
                copyEvidence(original, (short) 3, original.selectedCatalogVersion(), additional, input + 1, (long) output, ""),
                copyEvidence(original, (short) 3, original.selectedCatalogVersion(), additional, input, (long) output + 1, ""))) {
            ModelRouteDecision sealed = ModelRouteDecisionIntegrity.seal(revised);
            assertThatThrownBy(() -> identity.requireSameDecision(sealed)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void legacyReplayRemainsReadableButCannotBindForStrictExecution() {
        ModelRouteDecision original = decision(input, (long) output);
        ModelRouteDecisionFactory factory = new ModelRouteDecisionFactory();
        for (short version : new short[]{1, 2}) {
            ModelRouteDecision unsealed = copyEvidence(original, version, original.selectedCatalogVersion(),
                    null, input, (long) output, "");
            ModelRouteDecision legacy = copyEvidence(unsealed, version, unsealed.selectedCatalogVersion(),
                    null, input, (long) output, ModelRouteDecisionIntegrity.calculateSha256(unsealed));
            assertThat(factory.replayDecision(legacy, routeRequest(additional + 1)).decisionId())
                    .isEqualTo(original.id());
            assertThatThrownBy(() -> bind(legacy, request(input, output))).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void hashTamperingRemainsRejectedBeforeIdentityComparison() {
        ModelRouteDecision original = decision(input, (long) output);
        ModelRouteDecision tampered = copyEvidence(original, (short) 3, original.selectedCatalogVersion(),
                additional, input + 1, (long) output, original.decisionSha256());
        assertThatThrownBy(() -> new ModelRouteDecisionFactory().replayDecision(tampered, routeRequest(additional)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> bind(tampered, request(input, output))).isInstanceOf(IllegalStateException.class);
    }

    private static ModelRouteDecision copyEvidence(ModelRouteDecision decision, short version,
            Integer catalogVersion, Long additionalBound, Long inputEstimate, Long outputEstimate, String hash) {
        return new ModelRouteDecision(decision.id(), decision.organizationId(), decision.userId(),
                decision.chatId(), decision.chatTurnId(), decision.clientRequestId(), decision.requestContentHash(),
                decision.requestedModelKey(), decision.selectedCatalogEntryId(), catalogVersion,
                decision.selectedModelKey(), decision.selectedProvider(), decision.selectedProviderModelId(),
                decision.policyId(), decision.policyVersion(), decision.requiredCapabilities(),
                version < 3 ? null : decision.inputAccountingVersion(), additionalBound, inputEstimate, outputEstimate,
                decision.estimatedMaxCostUsd(), decision.monthlyBudgetUsd(), decision.monthlySpentUsd(),
                decision.monthlyProjectedUsd(), decision.monthlyCostKnown(), decision.budgetEnforcement(),
                decision.budgetExceeded(), decision.pricingComplete(), decision.outcome(), decision.reason(),
                version, hash, decision.createdAt());
    }

    private ModelRouteRequest routeRequest(long bound) {
        return new ModelRouteRequest(
                ModelTestFixtures.ORGANIZATION_ID, ModelTestFixtures.USER_ID,
                UUID.fromString("00000000-0000-0000-0000-000000000123"), turnId, clientId,
                ModelTestFixtures.REQUEST_HASH, "openai:gpt-test", "hello", List.of(), Set.of(), bound
        );
    }

    private ModelRouteDecision decision(Long inputEstimate, Long outputEstimate) {
        var entry = ModelTestFixtures.freeEntry();
        var draft = new ModelRouteDecisionFactory.DecisionDraft(
                entry, entry.modelKey(), entry.provider(), entry.providerModelId(),
                inputEstimate, outputEstimate, BigDecimal.ZERO, true,
                ModelRoutingCostPolicy.BudgetSnapshot.none(), ModelRouteOutcome.ALLOWED,
                ModelRouteReason.REQUESTED_MODEL
        );
        return new ModelRouteDecisionFactory().buildDecision(
                routeRequest(additional), null, draft, turnId, ModelTestFixtures.NOW
        );
    }

    private AiChatRequest request(Long inputCap, Integer outputCap) {
        ModelRouteRequest route = routeRequest(additional);
        return new AiChatRequest(route.userId(), route.organizationId(), route.chatId(), operationId,
                null, null, route.userMessage(), route.history(), inputCap, outputCap);
    }

    private ModelRouteExecutionIdentity bind(
            ModelRouteDecision decision,
            AiChatRequest reserved
    ) {
        return ModelRouteExecutionIdentity.bind(
                decision,
                turnId,
                clientId,
                operationId,
                reserved,
                null,
                noKnowledge,
                null,
                null,
                null
        );
    }
}
