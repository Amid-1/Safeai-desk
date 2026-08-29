package ru.safeai.gateway.chat.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.chat.testsupport.ChatTestFixtures;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatProcessingContextTest {

    @Test
    void processingContextCarriesV45RouteDecisionIdentity() {
        ChatProcessingContext context = ChatProcessingContextTestFixtures.processing(
                aiRequest(ChatTestFixtures.PROVIDER_OPERATION_ID)
        );

        assertThat(context.modelRouteDecisionId())
                .isEqualTo(ChatProcessingContextTestFixtures.MODEL_ROUTE_DECISION_ID);
        assertThat(context.replay()).isFalse();
    }

    @Test
    void withAiRequestPreservesDurableRouteAndProcessingIdentity() {
        ChatProcessingContext original = ChatProcessingContextTestFixtures.processing(
                aiRequest(ChatTestFixtures.PROVIDER_OPERATION_ID)
        );
        AiChatRequest replacement = aiRequest(ChatTestFixtures.PROVIDER_OPERATION_ID);

        ChatProcessingContext updated = original.withAiRequest(replacement);

        assertThat(updated.modelRouteDecisionId())
                .isEqualTo(original.modelRouteDecisionId());
        assertThat(updated.processingToken())
                .isEqualTo(original.processingToken());
        assertThat(updated.leaseUntil())
                .isEqualTo(original.leaseUntil());
        assertThat(updated.aiRequest()).isSameAs(replacement);
    }

    @Test
    void processingContextRejectsMismatchedProviderOperationIdentity() {
        AiChatRequest request = aiRequest(UUID.randomUUID());

        assertThatThrownBy(() -> ChatProcessingContextTestFixtures.processing(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerOperationId context и AI request не совпадают");
    }

    @Test
    void replayContextContainsNoProcessingMetadata() {
        ChatProcessingContext replay = ChatProcessingContextTestFixtures.replay();

        assertThat(replay.replay()).isTrue();
        assertThat(replay.processingToken()).isNull();
        assertThat(replay.leaseUntil()).isNull();
        assertThat(replay.aiRequest()).isNull();
        assertThat(replay.modelRouteDecisionId())
                .isEqualTo(ChatProcessingContextTestFixtures.MODEL_ROUTE_DECISION_ID);
    }

    private static AiChatRequest aiRequest(UUID providerOperationId) {
        return new AiChatRequest(
                ChatTestFixtures.USER_ID,
                ChatTestFixtures.ORGANIZATION_ID,
                ChatTestFixtures.CHAT_ID,
                providerOperationId,
                null,
                null,
                "Question",
                List.of()
        );
    }
}
