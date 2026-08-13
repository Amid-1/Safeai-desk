package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.dto.AiMessageRole;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.safeai.gateway.ai.testsupport.AiTestFixtures.CHAT_ID;
import static ru.safeai.gateway.ai.testsupport.AiTestFixtures.ORGANIZATION_ID;
import static ru.safeai.gateway.ai.testsupport.AiTestFixtures.USER_ID;

@Tag("unit")
class AiMessageAndRequestTest {

    private static final UUID PROVIDER_OPERATION_ID =
            UUID.fromString(
                    "dddddddd-dddd-dddd-dddd-dddddddddddd"
            );

    @Test
    void stringRoleConstructorParsesKnownRole() {
        AiMessage message =
                new AiMessage(
                        " assistant ",
                        "Ответ"
                );

        assertThat(message.role())
                .isEqualTo(AiMessageRole.ASSISTANT);

        assertThat(message.content())
                .isEqualTo("Ответ");
    }

    @Test
    void messageRejectsUnknownRoleBlankAndAbsoluteOversize() {
        assertThatThrownBy(
                () -> new AiMessage(
                        "ASSITANT",
                        "Ответ"
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Недопустимая");

        assertThatThrownBy(
                () -> new AiMessage(
                        AiMessageRole.USER,
                        " "
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content");

        assertThatThrownBy(
                () -> new AiMessage(
                        AiMessageRole.USER,
                        "x".repeat(
                                AiMessage.ABSOLUTE_MAX_CONTENT_CHARS + 1
                        )
                )
        )
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canonicalConstructorKeepsInstructionsSeparateFromHistory() {
        AiChatRequest request =
                new AiChatRequest(
                        USER_ID,
                        ORGANIZATION_ID,
                        CHAT_ID,
                        PROVIDER_OPERATION_ID,
                        "system-1\n\nsystem-2",
                        "developer",
                        "Привет",
                        List.of(
                                new AiMessage(
                                        AiMessageRole.USER,
                                        "old-user"
                                ),
                                new AiMessage(
                                        AiMessageRole.ASSISTANT,
                                        "old-answer"
                                )
                        )
                );

        assertThat(request.providerOperationId())
                .isEqualTo(PROVIDER_OPERATION_ID);

        assertThat(request.systemInstructions())
                .isEqualTo("system-1\n\nsystem-2");

        assertThat(request.developerInstructions())
                .isEqualTo("developer");

        assertThat(request.history())
                .extracting(AiMessage::role)
                .containsExactly(
                        AiMessageRole.USER,
                        AiMessageRole.ASSISTANT
                );
    }

    @Test
    void requestCopiesHistoryAndPreservesExplicitOperationId() {
        UUID operationId =
                UUID.fromString(
                        "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
                );

        List<AiMessage> source =
                new ArrayList<>();

        source.add(
                new AiMessage(
                        AiMessageRole.USER,
                        "История"
                )
        );

        source.add(
                new AiMessage(
                        AiMessageRole.ASSISTANT,
                        "Ответ"
                )
        );

        AiChatRequest request =
                new AiChatRequest(
                        USER_ID,
                        ORGANIZATION_ID,
                        CHAT_ID,
                        operationId,
                        null,
                        null,
                        "Привет",
                        source
                );

        source.clear();

        assertThat(request.providerOperationId())
                .isEqualTo(operationId);

        assertThat(request.history())
                .hasSize(2);

        assertThat(request.history())
                .extracting(AiMessage::content)
                .containsExactly(
                        "История",
                        "Ответ"
                );
    }

    @Test
    void canonicalHistoryRejectsSystemRole() {
        assertThatThrownBy(
                () -> createCanonicalRequest(
                        List.of(
                                new AiMessage(
                                        AiMessageRole.SYSTEM,
                                        "system"
                                )
                        )
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("USER/ASSISTANT");
    }

    @Test
    void canonicalHistoryRejectsOrphanAssistant() {
        assertThatThrownBy(
                () -> createCanonicalRequest(
                        List.of(
                                new AiMessage(
                                        AiMessageRole.ASSISTANT,
                                        "orphan"
                                )
                        )
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("начинаться с USER");
    }

    @Test
    void canonicalHistoryRejectsTwoConsecutiveUserMessages() {
        assertThatThrownBy(
                () -> createCanonicalRequest(
                        List.of(
                                new AiMessage(
                                        AiMessageRole.USER,
                                        "u1"
                                ),
                                new AiMessage(
                                        AiMessageRole.USER,
                                        "u2"
                                )
                        )
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("чередовать");
    }

    @Test
    void canonicalHistoryRejectsUnfinishedUserMessage() {
        assertThatThrownBy(
                () -> createCanonicalRequest(
                        List.of(
                                new AiMessage(
                                        AiMessageRole.USER,
                                        "unfinished"
                                )
                        )
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("завершаться ASSISTANT");
    }

    @Test
    void requestRejectsBlankMessage() {
        assertThatThrownBy(
                () -> new AiChatRequest(
                        USER_ID,
                        ORGANIZATION_ID,
                        CHAT_ID,
                        PROVIDER_OPERATION_ID,
                        null,
                        null,
                        " ",
                        List.of()
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userMessage");
    }

    @Test
    void requestRejectsNullHistoryElement() {
        List<AiMessage> historyWithNull =
                Arrays.asList(
                        new AiMessage(
                                AiMessageRole.USER,
                                "История"
                        ),
                        null
                );

        assertThatThrownBy(
                () -> new AiChatRequest(
                        USER_ID,
                        ORGANIZATION_ID,
                        CHAT_ID,
                        PROVIDER_OPERATION_ID,
                        null,
                        null,
                        "Привет",
                        historyWithNull
                )
        )
                .isInstanceOf(NullPointerException.class);
    }

    private static void createCanonicalRequest(
            List<AiMessage> history
    ) {
        new AiChatRequest(
                USER_ID,
                ORGANIZATION_ID,
                CHAT_ID,
                PROVIDER_OPERATION_ID,
                "system",
                "developer",
                "current",
                history
        );
    }
}