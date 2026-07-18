package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.dto.AiMessageRole;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiMessageAndRequestTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ORGANIZATION_ID = UUID.randomUUID();
    private static final UUID CHAT_ID = UUID.randomUUID();

    @Test
    void stringRoleConstructorParsesKnownRole() {
        AiMessage message = new AiMessage(
                " assistant ",
                "Ответ"
        );

        assertThat(message.role())
                .isEqualTo(AiMessageRole.ASSISTANT);
        assertThat(message.content()).isEqualTo("Ответ");
    }

    @Test
    void messageRejectsUnknownRoleAndBlankContent() {
        assertThatThrownBy(() ->
                new AiMessage("ASSITANT", "Ответ")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Недопустимая");

        assertThatThrownBy(() ->
                new AiMessage(AiMessageRole.USER, " ")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content");
    }

    @Test
    void requestGeneratesProviderOperationIdForLegacyConstructor() {
        AiChatRequest request = new AiChatRequest(
                USER_ID,
                ORGANIZATION_ID,
                CHAT_ID,
                "Привет",
                List.of()
        );

        assertThat(request.providerOperationId()).isNotNull();
        assertThat(request.history()).isEmpty();
    }

    @Test
    void requestPreservesExplicitOperationIdAndCopiesHistory() {
        UUID operationId = UUID.randomUUID();
        List<AiMessage> source = new java.util.ArrayList<>();
        source.add(new AiMessage(AiMessageRole.USER, "История"));

        AiChatRequest request = new AiChatRequest(
                USER_ID,
                ORGANIZATION_ID,
                CHAT_ID,
                operationId,
                "Привет",
                source
        );

        source.clear();

        assertThat(request.providerOperationId()).isEqualTo(operationId);
        assertThat(request.history()).hasSize(1);
    }

    @Test
    void requestRejectsBlankMessageAndNullHistoryElement() {
        assertThatThrownBy(() -> new AiChatRequest(
                USER_ID,
                ORGANIZATION_ID,
                CHAT_ID,
                " ",
                List.of()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userMessage");

        assertThatThrownBy(() -> new AiChatRequest(
                USER_ID,
                ORGANIZATION_ID,
                CHAT_ID,
                UUID.randomUUID(),
                "Привет",
                java.util.Arrays.asList(
                        new AiMessage(AiMessageRole.USER, "История"),
                        null
                )
        )).isInstanceOf(NullPointerException.class);
    }
}
