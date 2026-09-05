package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.model.exception.ModelRouteEnvelopeExceededException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRouteExecutionGuardTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final UUID OP_ID = UUID.randomUUID();
    private static final UUID DECISION_ID = UUID.randomUUID();

    @Test
    void rejectsDifferentBaseRequestEvenIfItIsSmaller() {
        AiChatRequest reserved =
                governed("original request", 10_000L);

        AiChatRequest prepared =
                new AiChatRequest(
                        USER_ID,
                        ORG_ID,
                        CHAT_ID,
                        OP_ID,
                        null,
                        null,
                        "x",
                        List.of(),
                        10_000L,
                        1024
                );

        assertThatThrownBy(() ->
                ModelRouteExecutionGuard
                        .assertWithinReservedInputEnvelope(
                                DECISION_ID,
                                reserved,
                                prepared
                        )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "route-bound request identity"
                );
    }

    @Test
    void governedRequestWithoutReservationFailsClosed() {
        AiChatRequest legacy =
                new AiChatRequest(
                        USER_ID,
                        ORG_ID,
                        CHAT_ID,
                        OP_ID,
                        null,
                        null,
                        "hello",
                        List.of()
                );

        assertThatThrownBy(() ->
                ModelRouteExecutionGuard
                        .assertWithinReservedInputEnvelope(
                                DECISION_ID,
                                legacy,
                                legacy
                        )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "reservedInputUnits"
                );
    }

    @Test
    void preparedRequestAboveReservationThrowsEnvelopeException() {
        AiChatRequest base =
                governed(
                        "hello",
                        1L
                );

        AiChatRequest prepared =
                base.withInstructions(
                        "system instruction",
                        "developer instruction"
                );

        assertThatThrownBy(() ->
                ModelRouteExecutionGuard
                        .assertWithinReservedInputEnvelope(
                                DECISION_ID,
                                base,
                                prepared
                        )
        )
                .isInstanceOf(
                        ModelRouteEnvelopeExceededException.class
                );
    }

    private static AiChatRequest governed(
            String message,
            long reservation
    ) {
        return new AiChatRequest(
                USER_ID,
                ORG_ID,
                CHAT_ID,
                OP_ID,
                null,
                null,
                message,
                List.of(),
                reservation,
                1024
        );
    }
}
