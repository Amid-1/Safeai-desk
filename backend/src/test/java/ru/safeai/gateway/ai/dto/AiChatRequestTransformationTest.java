package ru.safeai.gateway.ai.dto;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class AiChatRequestTransformationTest {
    @Test
    void ragAndTruncationTransformationsPreserveEveryIdentityAndGovernanceField() {
        AiChatRequest original = request();
        AiChatRequest withInstructions = original.withInstructions("rag-system", "rag-developer");
        AiChatRequest withHistory = withInstructions.withHistory(List.of(
                new AiMessage(AiMessageRole.USER, "earlier"),
                new AiMessage(AiMessageRole.ASSISTANT, "answer")));
        for (AiChatRequest prepared : new AiChatRequest[] {withInstructions, withHistory}) {
            assertThat(prepared.userId()).isEqualTo(original.userId());
            assertThat(prepared.organizationId()).isEqualTo(original.organizationId());
            assertThat(prepared.chatId()).isEqualTo(original.chatId());
            assertThat(prepared.providerOperationId()).isEqualTo(original.providerOperationId());
            assertThat(prepared.userMessage()).isEqualTo(original.userMessage());
            assertThat(prepared.reservedInputTokens()).isEqualTo(4096L);
            assertThat(prepared.maxOutputTokens()).isEqualTo(512);
            assertThat(prepared.effectiveMaxOutputTokens(1024)).isEqualTo(512);
            assertThat(prepared.effectiveMaxOutputTokens(128)).isEqualTo(128);
        }
        assertThat(withInstructions.systemInstructions()).isEqualTo("rag-system");
        assertThat(withInstructions.developerInstructions()).isEqualTo("rag-developer");
        assertThat(withHistory.history()).hasSize(2);
    }

    @Test
    void invalidEnvelopeCannotBeCreated() {
        AiChatRequest original = request();
        assertThatThrownBy(() -> new AiChatRequest(original.userId(), original.organizationId(),
                original.chatId(), original.providerOperationId(), null, null,
                "question", List.of(), -1L, 512)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiChatRequest(original.userId(), original.organizationId(),
                original.chatId(), original.providerOperationId(), null, null,
                "question", List.of(), 10L, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> original.effectiveMaxOutputTokens(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AiChatRequest request() {
        return new AiChatRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "system", "developer", "question", List.of(), 4096L, 512);
    }
}
