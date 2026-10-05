package ru.safeai.gateway.model.service;

import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.input.AiInputUnitEstimator;
import ru.safeai.gateway.chat.config.ChatProperties;
import ru.safeai.gateway.chat.service.ChatContentNormalizer;
import ru.safeai.gateway.knowledge.rag.KnowledgeMode;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.domain.ModelRouteDecision;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;
import ru.safeai.gateway.model.domain.ModelRouteReason;
import ru.safeai.gateway.model.domain.ModelRouteRequest;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class ModelExecutionBindingTestSupport {

    private static final ChatContentNormalizer NORMALIZER =
            new ChatContentNormalizer(new ChatProperties(
                    50, 50, 100, 100, 16_000,
                    Duration.ofMinutes(3), 4, 1_000
            ));

    private ModelExecutionBindingTestSupport() {
    }

    static Scenario general() {
        return scenario(KnowledgeMode.GENERAL, null);
    }

    static Scenario knowledgeAssisted() {
        return scenario(KnowledgeMode.KNOWLEDGE_ASSISTED, UUID.randomUUID());
    }

    private static Scenario scenario(KnowledgeMode mode, UUID knowledgeBaseId) {
        UUID turnId = UUID.randomUUID();
        UUID clientRequestId = UUID.randomUUID();
        UUID providerOperationId = UUID.randomUUID();
        long additional = 128L;
        long reservedInput = Math.addExact(
                AiInputUnitEstimator.estimateBaseRequest("hello", List.of()),
                additional
        );
        int maxOutput = 512;
        String semanticHash = NORMALIZER.requestHash(
                "hello",
                knowledgeBaseId,
                mode
        );

        ModelCatalogEntry entry = ModelTestFixtures.freeEntry();
        ModelRouteRequest route = new ModelRouteRequest(
                ModelTestFixtures.ORGANIZATION_ID,
                ModelTestFixtures.USER_ID,
                ModelTestFixtures.CHAT_ID,
                turnId,
                clientRequestId,
                semanticHash,
                entry.modelKey(),
                "hello",
                List.of(),
                Set.of(),
                additional
        );
        ModelRouteDecisionFactory.DecisionDraft draft =
                new ModelRouteDecisionFactory.DecisionDraft(
                        entry,
                        entry.modelKey(),
                        entry.provider(),
                        entry.providerModelId(),
                        reservedInput,
                        (long) maxOutput,
                        BigDecimal.ZERO,
                        true,
                        ModelRoutingCostPolicy.BudgetSnapshot.none(),
                        ModelRouteOutcome.ALLOWED,
                        ModelRouteReason.REQUESTED_MODEL
                );
        ModelRouteDecision decision = new ModelRouteDecisionFactory().buildDecision(
                route,
                null,
                draft,
                turnId,
                ModelTestFixtures.NOW
        );
        AiChatRequest reserved = new AiChatRequest(
                ModelTestFixtures.USER_ID,
                ModelTestFixtures.ORGANIZATION_ID,
                ModelTestFixtures.CHAT_ID,
                providerOperationId,
                null,
                null,
                "hello",
                List.of(),
                reservedInput,
                maxOutput
        );

        return new Scenario(
                decision,
                reserved,
                NORMALIZER,
                turnId,
                clientRequestId,
                providerOperationId,
                knowledgeBaseId,
                mode
        );
    }

    record Scenario(
            ModelRouteDecision decision,
            AiChatRequest reserved,
            ChatContentNormalizer normalizer,
            UUID turnId,
            UUID clientRequestId,
            UUID providerOperationId,
            UUID knowledgeBaseId,
            KnowledgeMode knowledgeMode
    ) {
    }
}
