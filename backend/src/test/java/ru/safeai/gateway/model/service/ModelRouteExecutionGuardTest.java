package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.model.exception.ModelRouteEnvelopeExceededException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRouteExecutionGuardTest {

    private static final long RESERVED_INPUT_TOKENS =
            1L;

    private static final int MAX_OUTPUT_TOKENS =
            128;

    @Test
    void exceededEnvelopeCarriesStructuredDecisionEvidence() {
        UUID decisionId =
                UUID.fromString(
                        "77777777-7777-4777-8777-777777777777"
                );

        AiChatRequest reserved =
                request(
                        "hello"
                );

        AiChatRequest prepared =
                request(
                        "this materialized request is larger than the reservation"
                );

        assertThatThrownBy(
                () ->
                        ModelRouteExecutionGuard
                                .assertWithinReservedInputEnvelope(
                                        decisionId,
                                        reserved,
                                        prepared
                                )
        )
                .isInstanceOf(
                        ModelRouteEnvelopeExceededException.class
                )
                .satisfies(
                        throwable -> {
                            ModelRouteEnvelopeExceededException exception =
                                    (ModelRouteEnvelopeExceededException) throwable;

                            assertThat(
                                    exception.decisionId()
                            ).isEqualTo(
                                    decisionId
                            );

                            assertThat(
                                    exception.reservedInputTokens()
                            ).isEqualTo(
                                    RESERVED_INPUT_TOKENS
                            );

                            assertThat(
                                    exception.actualEstimatedInputTokens()
                            ).isGreaterThan(
                                    exception.reservedInputTokens()
                            );
                        }
                );
    }

    private static AiChatRequest request(
            String userMessage
    ) {
        return new AiChatRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                userMessage,
                List.of(),
                RESERVED_INPUT_TOKENS,
                MAX_OUTPUT_TOKENS
        );
    }
}