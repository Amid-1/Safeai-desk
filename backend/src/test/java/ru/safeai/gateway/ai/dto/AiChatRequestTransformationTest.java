package ru.safeai.gateway.ai.dto;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AiChatRequestTransformationTest {

    @Test
    void withInstructionsPreservesRouteBoundMetadata() {
        UUID operationId = UUID.randomUUID();

        AiChatRequest original = new AiChatRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                operationId,
                "system",
                "developer",
                "question",
                List.of(),
                4_096L,
                512
        );

        AiChatRequest changed = original.withInstructions(
                "new system",
                "new developer"
        );

        assertThat(changed.providerOperationId()).isEqualTo(operationId);
        assertThat(changed.reservedInputTokens()).isEqualTo(4_096L);
        assertThat(changed.maxOutputTokens()).isEqualTo(512);
        assertThat(changed.systemInstructions()).isEqualTo("new system");
        assertThat(changed.developerInstructions()).isEqualTo("new developer");
    }

    @Test
    void withHistoryPreservesRouteBoundMetadata() {
        AiChatRequest original = new AiChatRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                "question",
                List.of(),
                8_192L,
                1_024
        );

        AiChatRequest changed = original.withHistory(
                List.of()
        );

        assertThat(changed.reservedInputTokens()).isEqualTo(8_192L);
        assertThat(changed.maxOutputTokens()).isEqualTo(1_024);
        assertThat(changed.providerOperationId())
                .isEqualTo(original.providerOperationId());
    }
}
