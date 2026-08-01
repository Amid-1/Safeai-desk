package ru.safeai.gateway.ai;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiMessage;
import ru.safeai.gateway.ai.dto.AiMessageRole;
import ru.safeai.gateway.ai.exception.AiContextLimitException;
import ru.safeai.gateway.ai.provider.AiContextWindowProperties;
import ru.safeai.gateway.ai.provider.AiContextWindowService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.safeai.gateway.ai.testsupport.AiTestFixtures.CHAT_ID;
import static ru.safeai.gateway.ai.testsupport.AiTestFixtures.OPERATION_ID;
import static ru.safeai.gateway.ai.testsupport.AiTestFixtures.ORGANIZATION_ID;
import static ru.safeai.gateway.ai.testsupport.AiTestFixtures.USER_ID;

@Tag("unit")
class AiContextWindowServiceTest {

    private static final int DEFAULT_MAX_INSTRUCTION_CHARS =
            100;

    private static final int DEFAULT_MAX_TOTAL_CHARS =
            1_000;

    private static final String SYSTEM_POLICY =
            "system-policy";

    private static final String DEVELOPER_POLICY =
            "developer-policy";

    @Test
    void tooLongCurrentMessageIsRejected() {
        AiContextWindowService service =
                service(
                        16,
                        100,
                        100
                );

        assertThatThrownBy(
                () -> service.prepare(
                        request(
                                "x".repeat(17),
                                List.of()
                        ),
                        1_000,
                        0
                )
        )
                .isInstanceOf(AiContextLimitException.class)
                .hasMessageContaining("userMessage");
    }

    @Test
    void historyIsTruncatedToNewestCompletePairs() {
        AiContextWindowService service =
                service(
                        100,
                        4,
                        100
                );

        List<AiMessage> history =
                List.of(
                        user("u1"),
                        assistant("a1"),
                        user("u2"),
                        assistant("a2"),
                        user("u3"),
                        assistant("a3")
                );

        AiChatRequest prepared =
                service.prepare(
                        request(
                                "new",
                                history
                        ),
                        1_000,
                        0
                );

        assertThat(prepared.history())
                .extracting(AiMessage::content)
                .containsExactly(
                        "u2",
                        "a2",
                        "u3",
                        "a3"
                );
    }

    @Test
    void charBudgetNeverLeavesOrphanAssistant() {
        AiContextWindowService service =
                service(
                        100,
                        100,
                        100
                );

        List<AiMessage> history =
                List.of(
                        user("u1"),
                        assistant("a1"),
                        user("u2"),
                        assistant("a2"),
                        user("u3"),
                        assistant("a3")
                );

        AiChatRequest prepared =
                service.prepare(
                        request(
                                "new",
                                history
                        ),
                        38,
                        0
                );

        assertThat(prepared.history().size() % 2)
                .isZero();

        if (!prepared.history().isEmpty()) {
            assertThat(
                    prepared.history()
                            .getFirst()
                            .role()
            ).isEqualTo(
                    AiMessageRole.USER
            );

            assertThat(
                    prepared.history()
                            .getLast()
                            .role()
            ).isEqualTo(
                    AiMessageRole.ASSISTANT
            );
        }
    }

    @Test
    void systemAndDeveloperInstructionsSurviveTruncation() {
        AiContextWindowService service =
                service(
                        100,
                        2,
                        100
                );

        AiChatRequest prepared =
                service.prepare(
                        request(
                                "new",
                                List.of(
                                        user("u1"),
                                        assistant("a1"),
                                        user("u2"),
                                        assistant("a2")
                                )
                        ),
                        1_000,
                        0
                );

        assertThat(prepared.systemInstructions())
                .isEqualTo(SYSTEM_POLICY);

        assertThat(prepared.developerInstructions())
                .isEqualTo(DEVELOPER_POLICY);

        assertThat(prepared.history())
                .extracting(AiMessage::content)
                .containsExactly(
                        "u2",
                        "a2"
                );
    }

    @Test
    void mandatoryInstructionsAndMessageCannotBeTruncatedAway() {
        AiContextWindowService service =
                service(
                        100,
                        100,
                        100
                );

        assertThatThrownBy(
                () -> service.prepare(
                        request(
                                "mandatory-message",
                                List.of()
                        ),
                        5,
                        0
                )
        )
                .isInstanceOf(AiContextLimitException.class)
                .hasMessageContaining("Обязательные");
    }

    @Test
    void oversizedSingleHistoryMessageIsRejected() {
        AiContextWindowService service =
                service(
                        100,
                        100,
                        5
                );

        assertThatThrownBy(
                () -> service.prepare(
                        request(
                                "new",
                                List.of(
                                        user("123456"),
                                        assistant("ok")
                                )
                        ),
                        1_000,
                        0
                )
        )
                .isInstanceOf(AiContextLimitException.class)
                .hasMessageContaining("history message");
    }

    private static AiContextWindowService service(
            int maxUserChars,
            int maxHistoryMessages,
            int maxMessageChars
    ) {
        AiContextWindowProperties properties =
                new AiContextWindowProperties(
                        maxUserChars,
                        DEFAULT_MAX_INSTRUCTION_CHARS,
                        maxHistoryMessages,
                        maxMessageChars,
                        DEFAULT_MAX_TOTAL_CHARS,
                        1,
                        0,
                        0
                );

        return new AiContextWindowService(
                properties
        );
    }

    private static AiChatRequest request(
            String currentMessage,
            List<AiMessage> history
    ) {
        return new AiChatRequest(
                USER_ID,
                ORGANIZATION_ID,
                CHAT_ID,
                OPERATION_ID,
                SYSTEM_POLICY,
                DEVELOPER_POLICY,
                currentMessage,
                history
        );
    }

    private static AiMessage user(
            String content
    ) {
        return new AiMessage(
                AiMessageRole.USER,
                content
        );
    }

    private static AiMessage assistant(
            String content
    ) {
        return new AiMessage(
                AiMessageRole.ASSISTANT,
                content
        );
    }
}