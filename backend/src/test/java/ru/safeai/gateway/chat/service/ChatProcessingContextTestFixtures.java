package ru.safeai.gateway.chat.service;

import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.chat.testsupport.ChatTestFixtures;

import java.time.Duration;
import java.util.UUID;

final class ChatProcessingContextTestFixtures {

    static final UUID MODEL_ROUTE_DECISION_ID = UUID.fromString(
            "55555555-5555-4555-8555-555555555555"
    );

    private ChatProcessingContextTestFixtures() {
    }

    static ChatProcessingContext processing(
            AiChatRequest aiRequest
    ) {
        return new ChatProcessingContext(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.USER_MESSAGE_ID,
                ChatTestFixtures.CLIENT_REQUEST_ID,
                ChatTestFixtures.PROVIDER_OPERATION_ID,
                ChatTestFixtures.PROCESSING_TOKEN,
                ChatTestFixtures.NOW.plus(Duration.ofMinutes(3)),
                MODEL_ROUTE_DECISION_ID,
                aiRequest,
                false
        );
    }

    static ChatProcessingContext replay() {
        return new ChatProcessingContext(
                ChatTestFixtures.CHAT_ID,
                ChatTestFixtures.TURN_ID,
                ChatTestFixtures.USER_MESSAGE_ID,
                ChatTestFixtures.CLIENT_REQUEST_ID,
                ChatTestFixtures.PROVIDER_OPERATION_ID,
                null,
                null,
                MODEL_ROUTE_DECISION_ID,
                null,
                true
        );
    }
}
