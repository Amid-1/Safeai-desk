package ru.safeai.gateway.ai.input;

import ru.safeai.gateway.ai.dto.AiChatRequest;
import ru.safeai.gateway.ai.dto.AiMessage;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Shared conservative input-unit estimator for governance and materialization.
 *
 * <p>The unit is UTF-8 bytes plus a small per-message framing allowance. This
 * is deliberately tokenizer-independent and conservative. Any component that
 * reserves or materializes provider input must use this same representation;
 * char/code-point heuristics must not be used as a governance boundary.</p>
 */
public final class AiInputUnitEstimator {

    public static final long MESSAGE_OVERHEAD_UNITS =
            8L;

    private AiInputUnitEstimator() {
    }

    public static long estimatePreparedRequest(
            AiChatRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        long total =
                estimateTextMessage(
                        request.userMessage()
                );

        if (request.systemInstructions()
                != null) {
            total =
                    Math.addExact(
                            total,
                            estimateTextMessage(
                                    request.systemInstructions()
                            )
                    );
        }

        if (request.developerInstructions()
                != null) {
            total =
                    Math.addExact(
                            total,
                            estimateTextMessage(
                                    request.developerInstructions()
                            )
                    );
        }

        return Math.addExact(
                total,
                estimateHistory(
                        request.history()
                )
        );
    }

    public static long estimateHistory(
            List<AiMessage> history
    ) {
        Objects.requireNonNull(
                history,
                "history не должен быть null"
        );

        long total =
                0L;

        for (AiMessage message : history) {
            Objects.requireNonNull(
                    message,
                    "history не должен содержать null"
            );

            total =
                    Math.addExact(
                            total,
                            estimateTextMessage(
                                    message.content()
                            )
                    );
        }

        return total;
    }

    public static long utf8Length(
            String value
    ) {
        return value == null
                ? 0L
                : value.getBytes(
                        StandardCharsets.UTF_8
                ).length;
    }

    private static long estimateTextMessage(
            String value
    ) {
        return Math.addExact(
                utf8Length(
                        value
                ),
                MESSAGE_OVERHEAD_UNITS
        );
    }
}