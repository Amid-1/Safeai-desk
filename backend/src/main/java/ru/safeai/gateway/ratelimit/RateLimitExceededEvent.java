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
        int limit,
        String window,
        Map<String, Object> details
) {
    public RateLimitExceededEvent {
        Objects.requireNonNull(
                actorUserId,
                "actorUserId не должен быть null"
        );
        Objects.requireNonNull(
                actorOrganizationId,
                "actorOrganizationId не должен быть null"
        );
        Objects.requireNonNull(
                targetOrganizationId,
                "targetOrganizationId не должен быть null"
        );
        Objects.requireNonNull(
                actorEmail,
                "actorEmail не должен быть null"
        );
        Objects.requireNonNull(type, "type не должен быть null");
        Objects.requireNonNull(window, "window не должен быть null");

        actorEmail = actorEmail
                .trim()
                .toLowerCase(Locale.ROOT);

        actorDisplayName =
                normalize(actorDisplayName);

        if (actorEmail.isBlank()) {
            throw new IllegalArgumentException(
                    "actorEmail не должен быть пустым"
            );
        }

        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "limit должен быть положительным"
            );
        }

        details = details == null
                ? Map.of()
                : Map.copyOf(details);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}
