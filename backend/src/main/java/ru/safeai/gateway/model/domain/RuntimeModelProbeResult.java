package ru.safeai.gateway.model.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Internal sanitized runtime-probe result.
 *
 * <p>No credential, provider URL, provider response body or customer content
 * may be placed into this record.</p>
 */
public record RuntimeModelProbeResult(
        String provider,
        String model,
        RuntimeModelProbeStatus status,
        Instant checkedAt,
        long latencyMs,
        Integer httpStatus,
        String message
) {

    public RuntimeModelProbeResult {
        provider = requireText(provider, "provider", 32);
        model = requireText(model, "model", 100);
        status = Objects.requireNonNull(status, "status не должен быть null");
        checkedAt = Objects.requireNonNull(
                checkedAt,
                "checkedAt не должен быть null"
        );

        if (latencyMs < 0L) {
            throw new IllegalArgumentException(
                    "latencyMs не может быть отрицательным"
            );
        }

        if (httpStatus != null
                && (httpStatus < 100 || httpStatus > 599)) {
            throw new IllegalArgumentException(
                    "httpStatus должен быть в диапазоне 100..599"
            );
        }

        message = requireText(message, "message", 255);
    }

    private static String requireText(
            String value,
            String field,
            int maxChars
    ) {
        String normalized = Objects.requireNonNull(
                value,
                field + " не должен быть null"
        ).trim();

        if (normalized.isEmpty() || normalized.length() > maxChars) {
            throw new IllegalArgumentException(
                    field + " имеет недопустимую длину"
            );
        }

        for (int index = 0; index < normalized.length(); index++) {
            if (Character.isISOControl(normalized.charAt(index))) {
                throw new IllegalArgumentException(
                        field + " содержит управляющие символы"
                );
            }
        }

        return normalized;
    }
}
