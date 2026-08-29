package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.domain.ModelCapability;
import ru.safeai.gateway.model.domain.ModelRouteDecision;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;
import ru.safeai.gateway.model.domain.ModelRouteReason;
import ru.safeai.gateway.model.domain.ModelRouteRequest;
import ru.safeai.gateway.model.exception.ModelRouteDeniedException;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRouteDecisionFactoryTest {

    private final ModelRouteDecisionFactory factory =
            new ModelRouteDecisionFactory();

    @Test
    void allowedDecisionIsSealedAndCanBeVerified() {
        ModelRouteDecision decision = buildAllowedDecision(
                ModelTestFixtures.routeRequest(
                        "openai:gpt-test",
                        Set.of(ModelCapability.TOOLS)
                )
        );

        assertThat(decision.decisionSha256())
                .matches("[0-9a-f]{64}");
        assertThatCode(() ->
                ModelRouteDecisionIntegrity.requireValid(decision)
        ).doesNotThrowAnyException();
    }

    @Test
    void canonicalHashRemainsCompatibleWithOriginalV45FieldOrder() {
        ModelRouteDecision decision = fixedDecision();

        assertThat(
                ModelRouteDecisionIntegrity.calculateSha256(decision)
        ).isEqualTo(
                "b32afbb831aa8475a0ee2f11be87c4a76a64ef0c47def462e4e9976dab6fb76a"
        );
    }

    @Test
    void newDecisionTimestampIsCanonicalizedToPostgresMicroseconds() {
        Instant highPrecision =
                Instant.parse("2026-08-28T12:00:00.123456789Z");
        ModelRouteRequest request = ModelTestFixtures.routeRequest(
                null,
                Set.of()
        );

        ModelRouteDecision decision = factory.buildDecision(
                request,
                null,
                new ModelRouteDecisionFactory.DecisionDraft(
                        ModelTestFixtures.freeEntry(),
                        "openai:gpt-test",
                        "openai",
                        "gpt-test",
                        10L,
                        20L,
                        BigDecimal.ZERO,
                        true,
                        ModelRoutingCostPolicy.BudgetSnapshot.none(),
                        ModelRouteOutcome.ALLOWED,
                        ModelRouteReason.RUNTIME_ONLY_MATCH
                ),
                request.plannedTurnId(),
                highPrecision
        );

        assertThat(decision.createdAt())
                .isEqualTo(
                        Instant.parse(
                                "2026-08-28T12:00:00.123456Z"
                        )
                );
        assertThatCode(() ->
                ModelRouteDecisionIntegrity.requireValid(decision)
        ).doesNotThrowAnyException();
    }

    @Test
    void earlyV45NanosecondTimestampHashRemainsVerifiableAfterDbPrecisionLoss() {
        Instant originalCreatedAt =
                Instant.parse("2026-08-28T12:00:00.123456789Z");
        ModelRouteDecision original = copyWithCreatedAt(
                fixedDecision(),
                originalCreatedAt,
                ""
        );
        String legacyHash = ModelRouteDecisionIntegrity
                .calculateSha256(original);

        ModelRouteDecision databaseShape = copyWithCreatedAt(
                original,
                Instant.parse("2026-08-28T12:00:00.123456Z"),
                legacyHash
        );

        assertThatCode(() ->
                ModelRouteDecisionIntegrity.requireValid(databaseShape)
        ).doesNotThrowAnyException();
    }

    @Test
    void earlyV45FreeZeroHashRemainsVerifiableAfterPostgresScaleCoercion() {
        ModelRouteDecision originalShape = copyWithEstimatedCost(
                fixedDecision(),
                BigDecimal.ZERO,
                ""
        );
        String legacyHash = ModelRouteDecisionIntegrity
                .calculateSha256(originalShape);

        ModelRouteDecision databaseShape = copyWithEstimatedCost(
                originalShape,
                new BigDecimal("0.000000000000"),
                legacyHash
        );

        assertThatCode(() ->
                ModelRouteDecisionIntegrity.requireValid(databaseShape)
        ).doesNotThrowAnyException();
    }

    @Test
    void tamperedEvidenceFailsIntegrityVerification() {
        ModelRouteDecision sealed =
                ModelRouteDecisionIntegrity.seal(
                        fixedDecision()
                );

        ModelRouteDecision tampered =
                tamperSelectedProviderModelId(
                        sealed
                );

        assertThatThrownBy(() ->
                ModelRouteDecisionIntegrity.requireValid(tampered)
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity check failed");
    }

    @Test
    void replayRequiresSameRequestedModelKey() {
        ModelRouteRequest original = ModelTestFixtures.routeRequest(
                "openai:gpt-test",
                Set.of()
        );
        ModelRouteDecision decision = buildAllowedDecision(
                original
        );
        ModelRouteRequest changed = new ModelRouteRequest(
                original.organizationId(),
                original.userId(),
                original.chatId(),
                original.plannedTurnId(),
                original.clientRequestId(),
                original.requestContentHash(),
                "openai:other-model",
                original.userMessage(),
                original.history(),
                original.requiredCapabilities()
        );

        assertThatThrownBy(() ->
                factory.replayDecision(
                        decision,
                        changed
                )
        ).isInstanceOf(ConflictException.class);
    }

    @Test
    void replayRequiresSameCapabilitiesEvenWhenContentHashMatches() {
        ModelRouteRequest original = ModelTestFixtures.routeRequest(
                null,
                Set.of()
        );
        ModelRouteDecision decision = buildAllowedDecision(
                original
        );
        ModelRouteRequest changed = new ModelRouteRequest(
                original.organizationId(),
                original.userId(),
                original.chatId(),
                original.plannedTurnId(),
                original.clientRequestId(),
                original.requestContentHash(),
                original.requestedModelKey(),
                original.userMessage(),
                original.history(),
                Set.of(ModelCapability.VISION)
        );

        assertThatThrownBy(() ->
                factory.replayDecision(
                        decision,
                        changed
                )
        ).isInstanceOf(ConflictException.class);
    }

    @Test
    void replayRequiresSamePlannedTurnForAllowedDecision() {
        ModelRouteRequest original = ModelTestFixtures.routeRequest(
                null,
                Set.of()
        );
        ModelRouteDecision decision = buildAllowedDecision(
                original
        );
        ModelRouteRequest changedTurn = new ModelRouteRequest(
                original.organizationId(),
                original.userId(),
                original.chatId(),
                UUID.randomUUID(),
                original.clientRequestId(),
                original.requestContentHash(),
                original.requestedModelKey(),
                original.userMessage(),
                original.history(),
                original.requiredCapabilities()
        );

        assertThatThrownBy(() ->
                factory.replayDecision(
                        decision,
                        changedTurn
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("planned ChatTurn identity");
    }

    @Test
    void deniedReplayReturnsStableGovernanceDenial() {
        ModelRouteRequest request = ModelTestFixtures.routeRequest(
                "openai:gpt-test",
                Set.of()
        );
        ModelRouteDecision denied = factory.buildDecision(
                request,
                null,
                new ModelRouteDecisionFactory.DecisionDraft(
                        ModelTestFixtures.freeEntry(),
                        "openai:gpt-test",
                        "openai",
                        "gpt-test",
                        null,
                        null,
                        null,
                        true,
                        ModelRoutingCostPolicy.BudgetSnapshot.none(),
                        ModelRouteOutcome.DENIED,
                        ModelRouteReason.MODEL_DISABLED
                ),
                null,
                ModelTestFixtures.NOW
        );

        assertThatThrownBy(() ->
                factory.replayDecision(
                        denied,
                        request
                )
        )
                .isInstanceOf(ModelRouteDeniedException.class)
                .extracting("reason")
                .isEqualTo(ModelRouteReason.MODEL_DISABLED);
    }

    @Test
    void toResultRejectsDeniedDecisionInsteadOfFailingWithIncidentalNull() {
        ModelRouteDecision denied = ModelRouteDecisionIntegrity.seal(
                asDeniedModelDisabled(
                        fixedDecision()
                )
        );

        assertThatThrownBy(() -> factory.toResult(denied))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ALLOWED");
    }

    private ModelRouteDecision buildAllowedDecision(
            ModelRouteRequest request
    ) {
        return factory.buildDecision(
                request,
                null,
                new ModelRouteDecisionFactory.DecisionDraft(
                        ModelTestFixtures.freeEntry(),
                        "openai:gpt-test",
                        "openai",
                        "gpt-test",
                        10L,
                        20L,
                        BigDecimal.ZERO,
                        true,
                        ModelRoutingCostPolicy.BudgetSnapshot.none(),
                        ModelRouteOutcome.ALLOWED,
                        ModelRouteReason.RUNTIME_ONLY_MATCH
                ),
                request.plannedTurnId(),
                ModelTestFixtures.NOW
        );
    }

    private static ModelRouteDecision fixedDecision() {
        return new ModelRouteDecision(
                UUID.fromString("88888888-8888-4888-8888-888888888888"),
                ModelTestFixtures.ORGANIZATION_ID,
                ModelTestFixtures.USER_ID,
                ModelTestFixtures.CHAT_ID,
                ModelTestFixtures.TURN_ID,
                ModelTestFixtures.CLIENT_REQUEST_ID,
                ModelTestFixtures.REQUEST_HASH,
                "openai:gpt-test",
                ModelTestFixtures.CATALOG_ENTRY_ID,
                2,
                "openai:gpt-test",
                "openai",
                "gpt-test",
                ModelTestFixtures.POLICY_ID,
                3,
                Set.of(
                        ModelCapability.TOOLS,
                        ModelCapability.VISION
                ),
                123L,
                4_096L,
                new BigDecimal("0.020000000000"),
                new BigDecimal("100.000000000000"),
                new BigDecimal("10.000000000000"),
                new BigDecimal("10.020000000000"),
                true,
                BudgetEnforcement.HARD,
                false,
                true,
                ModelRouteOutcome.ALLOWED,
                ModelRouteReason.POLICY_DEFAULT,
                "",
                Instant.parse("2026-08-28T12:00:00Z")
        );
    }

    private static ModelRouteDecision tamperSelectedProviderModelId(
            ModelRouteDecision source
    ) {
        return new ModelRouteDecision(
                source.id(),
                source.organizationId(),
                source.userId(),
                source.chatId(),
                source.chatTurnId(),
                source.clientRequestId(),
                source.requestContentHash(),
                source.requestedModelKey(),
                source.selectedCatalogEntryId(),
                source.selectedCatalogVersion(),
                source.selectedModelKey(),
                source.selectedProvider(),
                "tampered-model",
                source.policyId(),
                source.policyVersion(),
                source.requiredCapabilities(),
                source.estimatedInputTokens(),
                source.estimatedOutputTokens(),
                source.estimatedMaxCostUsd(),
                source.monthlyBudgetUsd(),
                source.monthlySpentUsd(),
                source.monthlyProjectedUsd(),
                source.monthlyCostKnown(),
                source.budgetEnforcement(),
                source.budgetExceeded(),
                source.pricingComplete(),
                source.outcome(),
                source.reason(),
                source.decisionSha256(),
                source.createdAt()
        );
    }

    private static ModelRouteDecision copyWithEstimatedCost(
            ModelRouteDecision source,
            BigDecimal estimatedCost,
            String hash
    ) {
        return new ModelRouteDecision(
                source.id(),
                source.organizationId(),
                source.userId(),
                source.chatId(),
                source.chatTurnId(),
                source.clientRequestId(),
                source.requestContentHash(),
                source.requestedModelKey(),
                source.selectedCatalogEntryId(),
                source.selectedCatalogVersion(),
                source.selectedModelKey(),
                source.selectedProvider(),
                source.selectedProviderModelId(),
                source.policyId(),
                source.policyVersion(),
                source.requiredCapabilities(),
                source.estimatedInputTokens(),
                source.estimatedOutputTokens(),
                estimatedCost,
                source.monthlyBudgetUsd(),
                source.monthlySpentUsd(),
                source.monthlyProjectedUsd(),
                source.monthlyCostKnown(),
                source.budgetEnforcement(),
                source.budgetExceeded(),
                source.pricingComplete(),
                source.outcome(),
                source.reason(),
                hash,
                source.createdAt()
        );
    }

    private static ModelRouteDecision copyWithCreatedAt(
            ModelRouteDecision source,
            Instant createdAt,
            String hash
    ) {
        return new ModelRouteDecision(
                source.id(),
                source.organizationId(),
                source.userId(),
                source.chatId(),
                source.chatTurnId(),
                source.clientRequestId(),
                source.requestContentHash(),
                source.requestedModelKey(),
                source.selectedCatalogEntryId(),
                source.selectedCatalogVersion(),
                source.selectedModelKey(),
                source.selectedProvider(),
                source.selectedProviderModelId(),
                source.policyId(),
                source.policyVersion(),
                source.requiredCapabilities(),
                source.estimatedInputTokens(),
                source.estimatedOutputTokens(),
                source.estimatedMaxCostUsd(),
                source.monthlyBudgetUsd(),
                source.monthlySpentUsd(),
                source.monthlyProjectedUsd(),
                source.monthlyCostKnown(),
                source.budgetEnforcement(),
                source.budgetExceeded(),
                source.pricingComplete(),
                source.outcome(),
                source.reason(),
                hash,
                createdAt
        );
    }

    private static ModelRouteDecision asDeniedModelDisabled(
            ModelRouteDecision source
    ) {
        return new ModelRouteDecision(
                source.id(),
                source.organizationId(),
                source.userId(),
                source.chatId(),
                null,
                source.clientRequestId(),
                source.requestContentHash(),
                source.requestedModelKey(),
                source.selectedCatalogEntryId(),
                source.selectedCatalogVersion(),
                source.selectedModelKey(),
                source.selectedProvider(),
                source.selectedProviderModelId(),
                source.policyId(),
                source.policyVersion(),
                source.requiredCapabilities(),
                null,
                null,
                source.estimatedMaxCostUsd(),
                source.monthlyBudgetUsd(),
                source.monthlySpentUsd(),
                source.monthlyProjectedUsd(),
                source.monthlyCostKnown(),
                source.budgetEnforcement(),
                source.budgetExceeded(),
                source.pricingComplete(),
                ModelRouteOutcome.DENIED,
                ModelRouteReason.MODEL_DISABLED,
                source.decisionSha256(),
                source.createdAt()
        );
    }

}
