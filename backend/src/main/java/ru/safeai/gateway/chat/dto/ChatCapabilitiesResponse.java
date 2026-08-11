package ru.safeai.gateway.chat.dto;

/**
 * Public runtime limits used by the web client.

 * Keeping these values server-driven prevents the frontend from drifting from
 * environment-specific ChatProperties values after a configuration change.
 */
public record ChatCapabilitiesResponse(
        int maxMessageChars,
        int maxChatPageSize,
        int maxMessagePageSize,
        int detailsMessageLimit
) {
    public ChatCapabilitiesResponse {
        if (maxMessageChars < 1) {
            throw new IllegalArgumentException(
                    "maxMessageChars должен быть положительным"
            );
        }
        if (maxChatPageSize < 1) {
            throw new IllegalArgumentException(
                    "maxChatPageSize должен быть положительным"
            );
        }
        if (maxMessagePageSize < 1) {
            throw new IllegalArgumentException(
                    "maxMessagePageSize должен быть положительным"
            );
        }
        if (detailsMessageLimit < 1) {
            throw new IllegalArgumentException(
                    "detailsMessageLimit должен быть положительным"
            );
        }
    }
}
