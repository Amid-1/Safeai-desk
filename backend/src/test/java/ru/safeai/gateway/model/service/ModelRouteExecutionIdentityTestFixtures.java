package ru.safeai.gateway.model.service;

import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.input.AiInputUnitEstimator;
import ru.safeai.gateway.chat.config.ChatProperties;
import ru.safeai.gateway.chat.service.ChatContentNormalizer;
import ru.safeai.gateway.knowledge.rag.KnowledgeMode;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Real SHA-256 receipt for a mock-issuer unit test. Package placement permits
 * invoking the production package-private base request canonicalizer; no
 * replica of the hashing algorithm or weakened production overload is needed.
 * The DB decision itself is deliberately mocked at AiExecutionService.bindExecution.
 */
public final class ModelRouteExecutionIdentityTestFixtures {

    private static final UUID CATALOG_ENTRY_ID = UUID.fromString(
            "66666666-6666-4666-8666-666666666666"
    );

    private static final ChatContentNormalizer CONTENT_NORMALIZER =
            new ChatContentNormalizer(new ChatProperties(
                    50, 50, 100, 100, 16_000,
                    Duration.ofMinutes(3), 4, 1_000
            ));

    private ModelRouteExecutionIdentityTestFixtures() {
    }

    public static ModelRouteExecutionIdentity general(
            UUID decisionId,
            UUID turnId,
            UUID clientRequestId,
            AiChatRequest reserved
    ) {
        Objects.requireNonNull(reserved, "reserved");

        return new ModelRouteExecutionIdentity(
                decisionId,
                CATALOG_ENTRY_ID,
                1,
                "mock:requested-model",
                reserved.organizationId(),
                reserved.userId(),
                reserved.chatId(),
                turnId,
                clientRequestId,
                reserved.providerOperationId(),
                CONTENT_NORMALIZER.requestHash(
                        reserved.userMessage(),
                        null,
                        KnowledgeMode.GENERAL
                ),
                ModelRouteExecutionIdentity.baseSha256(reserved),
                AiInputUnitEstimator.VERSION,
                Objects.requireNonNull(
                        reserved.reservedInputTokens(),
                        "reservedInputTokens"
                ),
                Objects.requireNonNull(
                        reserved.maxOutputTokens(),
                        "maxOutputTokens"
                ),
                "mock",
                "requested-model",
                null,
                KnowledgeMode.GENERAL
        );
    }
}
