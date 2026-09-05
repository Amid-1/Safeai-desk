package ru.safeai.gateway.model.dto;

import ru.safeai.gateway.model.domain.RuntimeModelProbeResult;
import ru.safeai.gateway.model.domain.RuntimeModelProbeStatus;

import java.time.Instant;
import java.util.Objects;

/** Public sanitized runtime connectivity-probe response. */
public record RuntimeModelProbeResponse(
        String provider,
        String model,
        RuntimeModelProbeStatus status,
        Instant checkedAt,
        long latencyMs,
        Integer httpStatus,
        String message
) {

    public static RuntimeModelProbeResponse from(
            RuntimeModelProbeResult result
    ) {
        Objects.requireNonNull(result, "result не должен быть null");

        return new RuntimeModelProbeResponse(
                result.provider(),
                result.model(),
                result.status(),
                result.checkedAt(),
                result.latencyMs(),
                result.httpStatus(),
                result.message()
        );
    }
}
