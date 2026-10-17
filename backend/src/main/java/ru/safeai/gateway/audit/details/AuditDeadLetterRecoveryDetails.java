package ru.safeai.gateway.audit.details;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Only an event identifier and delivery count; never copy outbox payloads. */
public record AuditDeadLetterRecoveryDetails(
        UUID recoveredEventId,
        int previousAttemptCount
) implements AuditDetails {

    public AuditDeadLetterRecoveryDetails {
        Objects.requireNonNull(recoveredEventId, "recoveredEventId");
        if (previousAttemptCount < 1) {
            throw new IllegalArgumentException("previousAttemptCount должен быть положительным");
        }
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("recoveredEventId", recoveredEventId);
        details.put("previousAttemptCount", previousAttemptCount);
        return Map.copyOf(details);
    }
}
