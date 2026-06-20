package ru.safeai.gateway.ratelimit;

import java.util.Map;
import java.util.UUID;

public record RateLimitExceededEvent(
        UUID userId,
        UUID organizationId,
        String type,
        int limit,
        String window,
        Map<String, Object> details
) {
    public RateLimitExceededEvent {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}