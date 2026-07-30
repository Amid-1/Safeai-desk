package ru.safeai.gateway.audit.details;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record SecurityRefreshReuseAuditDetails(
        UUID tokenFamilyId,
        String ip,
        String userAgent,
        String requestId
) implements AuditDetails {

    public SecurityRefreshReuseAuditDetails {
        Objects.requireNonNull(
                tokenFamilyId,
                "tokenFamilyId не должен быть null"
        );

        ip = normalize(ip);
        userAgent = normalize(userAgent);
        requestId = normalize(requestId);
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("tokenFamilyId", tokenFamilyId);
        put(result, "ip", ip);
        put(result, "userAgent", userAgent);
        put(result, "requestId", requestId);

        return Map.copyOf(result);
    }

    private static void put(
            Map<String, Object> target,
            String key,
            Object value
    ) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}
