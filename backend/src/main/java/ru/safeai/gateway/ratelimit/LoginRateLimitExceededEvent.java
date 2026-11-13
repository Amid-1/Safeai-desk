package ru.safeai.gateway.ratelimit;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Anonymous login rejection. Contains only allowlisted metadata and HMACs. */
public record LoginRateLimitExceededEvent(
        UUID targetOrganizationId,
        String type,
        String dimension,
        Integer limit,
        Duration window,
        String emailFingerprint,
        String ipFingerprint,
        int emailLimit,
        int ipLimit,
        DualRateLimitResult result
) {
    public LoginRateLimitExceededEvent {
        Objects.requireNonNull(targetOrganizationId, "targetOrganizationId");
        Objects.requireNonNull(result, "result");
        if (!type.matches("LOGIN_(EMAIL|IP|EMAIL_AND_IP)")
                || !dimension.matches("EMAIL|IP|BOTH")
                || window.isZero() || window.isNegative()
                || !emailFingerprint.matches("[0-9a-f]{64}")
                || !ipFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid login rate limit audit metadata");
        }
    }

    public Map<String, Object> auditDetails() {
        Map<String, Object> details = new HashMap<>(Map.ofEntries(
                Map.entry("type", type),
                Map.entry("window", formatWindow()),
                Map.entry("source", "LOGIN"),
                Map.entry("dimension", dimension),
                Map.entry("decision", result.decision().name()),
                Map.entry("emailFingerprint", emailFingerprint),
                Map.entry("ipFingerprint", ipFingerprint),
                Map.entry("emailLimit", emailLimit),
                Map.entry("ipLimit", ipLimit),
                Map.entry("emailCount", result.firstCount()),
                Map.entry("ipCount", result.secondCount()),
                Map.entry("emailTtlSeconds", result.firstTtlSeconds()),
                Map.entry("ipTtlSeconds", result.secondTtlSeconds()),
                Map.entry("retryAfterSeconds", result.retryAfterSeconds()),
                Map.entry("emailNotification", result.firstExceededNotification()),
                Map.entry("ipNotification", result.secondExceededNotification())
        ));
        if (limit != null) {
            details.put("limit", limit);
        }
        return Map.copyOf(details);
    }

    private String formatWindow() {
        if (window.toSeconds() < 60) {
            return window.toSeconds() + "s";
        }
        if (window.toHours() == 0 && window.toSecondsPart() == 0) {
            return window.toMinutes() + "m";
        }
        if (window.toMinutesPart() == 0 && window.toSecondsPart() == 0) {
            return window.toHours() + "h";
        }
        return window.toString();
    }

}
