package ru.safeai.gateway.ratelimit;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record RateLimitExceededEvent(
        UUID actorUserId,
        UUID actorOrganizationId,
        String actorEmail,
        String actorDisplayName,
        UUID targetOrganizationId,
        String type,
        Integer limit,
        String window,
        Map<String, Object> details
) {
    public RateLimitExceededEvent {
        Objects.requireNonNull(
                targetOrganizationId,
                "targetOrganizationId не должен быть null"
        );

        type = requireText(type, "type");
        window = requireText(window, "window");

        if (limit != null && limit <= 0) {
            throw new IllegalArgumentException(
                    "limit должен быть положительным"
            );
        }

        actorEmail = normalizeEmail(actorEmail);
        actorDisplayName = normalize(actorDisplayName);

        if (actorUserId != null) {
            Objects.requireNonNull(
                    actorOrganizationId,
                    "actorOrganizationId не должен быть null "
                            + "для пользовательского события"
            );

            if (actorEmail == null) {
                throw new IllegalArgumentException(
                        "actorEmail не должен быть пустым "
                                + "для пользовательского события"
                );
            }
        }

        details = details == null
                ? Map.of()
                : Map.copyOf(details);
    }

    /**
     * Методы совместимости для существующего audit listener.
     */
    public UUID userId() {
        return actorUserId;
    }

    public UUID organizationId() {
        return targetOrganizationId;
    }

    private static String requireText(
            String value,
            String name
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    name + " не должен быть пустым"
            );
        }

        return value.trim();
    }

    private static String normalizeEmail(
            String value
    ) {
        return value == null || value.isBlank()
                ? null
                : value.trim()
                .toLowerCase(Locale.ROOT);
    }

    private static String normalize(
            String value
    ) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}
