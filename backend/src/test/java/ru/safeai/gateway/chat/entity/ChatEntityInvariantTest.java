package ru.safeai.gateway.chat.entity;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.metadata.AiResponseStatus;
import ru.safeai.gateway.ai.metadata.PricingStatus;
import ru.safeai.gateway.ai.metadata.UsageStatus;
import ru.safeai.gateway.chat.testsupport.ChatTestFixtures;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatEntityInvariantTest {

    @Test
    void userFactoryRequiresClientRequestIdAndNoAiMetadata() {
        ChatMessageEntity message = ChatMessageEntity.user(
                ChatTestFixtures.session(),
                "Question",
                ChatTestFixtures.CLIENT_REQUEST_ID,
                ChatTestFixtures.NOW
        );

        assertThat(message.getRole()).isEqualTo(ChatMessageRole.USER);
        assertThat(message.getClientRequestId())
                .isEqualTo(ChatTestFixtures.CLIENT_REQUEST_ID);
        assertThat(message.getUsageStatus())
                .isEqualTo(UsageStatus.NOT_APPLICABLE);
        assertThat(message.getPricingStatus())
                .isEqualTo(PricingStatus.NOT_APPLICABLE);
    }

    @Test
    void completedAssistantPersistsResolvedProviderMetadata() {
        ChatMessageEntity message = ChatMessageEntity.completedAssistant(
                ChatTestFixtures.ASSISTANT_MESSAGE_ID,
                ChatTestFixtures.session(),
                ChatTestFixtures.USER_MESSAGE_ID,
                ChatTestFixtures.pricedResponse(),
                ChatTestFixtures.NOW
        );

        assertThat(message.getRequestedModel()).isEqualTo("requested-model");
        assertThat(message.getModel()).isEqualTo("resolved-model");
        assertThat(message.getProviderMessageId())
                .isEqualTo("provider-message-id");
        assertThat(message.getProviderRequestId())
                .isEqualTo("provider-request-id");
        assertThat(message.getAiResponseStatus())
                .isEqualTo(AiResponseStatus.COMPLETED);
        assertThat(message.getCostUsd())
                .isEqualByComparingTo("0.012345678901");
    }

    @Test
    void refusedAndIncompleteOutcomesAreValidCompletedAssistantMessages() {
        ChatMessageEntity refused = ChatMessageEntity.completedAssistant(
                UUID.randomUUID(),
                ChatTestFixtures.session(),
                ChatTestFixtures.USER_MESSAGE_ID,
                ChatTestFixtures.refusedResponse(),
                ChatTestFixtures.NOW
        );
        ChatMessageEntity incomplete = ChatMessageEntity.completedAssistant(
                UUID.randomUUID(),
                ChatTestFixtures.session(),
                UUID.randomUUID(),
                ChatTestFixtures.incompleteResponse(),
                ChatTestFixtures.NOW
        );

        assertThat(refused.getAiResponseStatus())
                .isEqualTo(AiResponseStatus.REFUSED);
        assertThat(incomplete.getAiResponseStatus())
                .isEqualTo(AiResponseStatus.INCOMPLETE);
        assertThat(incomplete.getUsageStatus()).isEqualTo(UsageStatus.PARTIAL);
    }

    @Test
    void oversizedProviderMetadataIsRejectedBeforeSql() {
        var response = ChatTestFixtures.pricedResponse();
        ChatMessageEntity message = ChatMessageEntity.completedAssistant(
                UUID.randomUUID(),
                ChatTestFixtures.session(),
                UUID.randomUUID(),
                response,
                ChatTestFixtures.NOW
        );
        message.setProviderRequestId("x".repeat(256));

        assertThatThrownBy(message::validateInvariant)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerRequestId");
    }

    @Test
    void nonAssistantMetadataIsNotSilentlyCleared() {
        ChatMessageEntity message = ChatMessageEntity.user(
                ChatTestFixtures.session(),
                "Question",
                UUID.randomUUID(),
                ChatTestFixtures.NOW
        );
        message.setModel("should-fail");

        assertThatThrownBy(message::validateInvariant)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI metadata");
        assertThat(message.getModel()).isEqualTo("should-fail");
    }

    @Test
    void processingTurnContainsPersistentOperationAndFencingToken() {
        ChatTurnEntity turn = processingTurn();

        assertThat(turn.getProviderOperationId())
                .isEqualTo(ChatTestFixtures.PROVIDER_OPERATION_ID);
        assertThat(turn.getProcessingToken())
                .isEqualTo(ChatTestFixtures.PROCESSING_TOKEN);
        assertThat(turn.getState()).isEqualTo(ChatTurnState.PROCESSING);
        assertThat(turn.getLeaseUntil()).isAfter(ChatTestFixtures.NOW);
    }

    @Test
    void processingTurnRejectsInvalidContentHash() {
        assertThatThrownBy(() -> ChatTurnEntity.processing(
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.session(),
                ChatTestFixtures.CLIENT_REQUEST_ID,
                "not-a-hash",
                ChatTestFixtures.PROVIDER_OPERATION_ID,
                ChatTestFixtures.USER_MESSAGE_ID,
                ChatTestFixtures.PROCESSING_TOKEN,
                ChatTestFixtures.NOW,
                ChatTestFixtures.NOW.plus(Duration.ofMinutes(3)),
                "openai"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHA-256");
    }


    @Test
    void succeededTurnRequiresResolvedModelsAndProviderCallMarker() {
        ChatTurnEntity turn = processingTurn();
        turn.setState(ChatTurnState.SUCCEEDED);
        turn.setProcessingToken(null);
        turn.setLeaseUntil(null);
        turn.setAssistantMessageId(ChatTestFixtures.ASSISTANT_MESSAGE_ID);
        turn.setCompletedAt(ChatTestFixtures.NOW.plusSeconds(1));

        assertThatThrownBy(turn::validateInvariant)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("providerCallStartedAt");

        turn.setProviderCallStartedAt(ChatTestFixtures.NOW);

        assertThatThrownBy(turn::validateInvariant)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requestedModel");
    }

    @Test
    void failedAndAmbiguousTurnsCannotReferenceAssistantMessage() {
        ChatTurnEntity failed = processingTurn();
        failed.setState(ChatTurnState.FAILED);
        failed.setProcessingToken(null);
        failed.setLeaseUntil(null);
        failed.setFailureCode("AI_PROVIDER_INVALID_REQUEST");
        failed.setAssistantMessageId(ChatTestFixtures.ASSISTANT_MESSAGE_ID);
        failed.setCompletedAt(ChatTestFixtures.NOW.plusSeconds(1));

        assertThatThrownBy(failed::validateInvariant)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("assistantMessageId");

        ChatTurnEntity ambiguous = processingTurn();
        ambiguous.setState(ChatTurnState.AMBIGUOUS);
        ambiguous.setProcessingToken(null);
        ambiguous.setLeaseUntil(null);
        ambiguous.setProviderCallStartedAt(ChatTestFixtures.NOW);
        ambiguous.setFailureCode("AI_PROVIDER_OUTCOME_AMBIGUOUS");
        ambiguous.setOutcomeAmbiguous(true);
        ambiguous.setAssistantMessageId(ChatTestFixtures.ASSISTANT_MESSAGE_ID);
        ambiguous.setCompletedAt(ChatTestFixtures.NOW.plusSeconds(1));

        assertThatThrownBy(ambiguous::validateInvariant)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("assistantMessageId");
    }

    @Test
    void sessionTouchUsesInjectedTimeAndRejectsTimeTravel() {
        ChatSessionEntity session = ChatTestFixtures.session();
        session.touch(ChatTestFixtures.NOW);

        assertThat(session.getUpdatedAt()).isEqualTo(ChatTestFixtures.NOW);
        assertThatThrownBy(() -> session.touch(
                session.getCreatedAt().minusSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static ChatTurnEntity processingTurn() {
        return ChatTurnEntity.processing(
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.session(),
                ChatTestFixtures.CLIENT_REQUEST_ID,
                "0".repeat(64),
                ChatTestFixtures.PROVIDER_OPERATION_ID,
                ChatTestFixtures.USER_MESSAGE_ID,
                ChatTestFixtures.PROCESSING_TOKEN,
                ChatTestFixtures.NOW,
                ChatTestFixtures.NOW.plus(Duration.ofMinutes(3)),
                "openai"
        );
    }
}
