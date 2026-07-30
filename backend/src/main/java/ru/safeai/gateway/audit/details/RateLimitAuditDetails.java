package ru.safeai.gateway.audit.details;

import java.util.LinkedHashMap;
import java.util.Map;

public record RateLimitAuditDetails(
        String type,
        int limit,
        String window,
        long count
) implements AuditDetails {

    public RateLimitAuditDetails {
        type = requireText(type, "type");
        window = requireText(window, "window");

        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "limit должен быть положительным"
            );
        }

        if (count < 0) {
            throw new IllegalArgumentException(
                    "count не может быть отрицательным"
            );
        }
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("type", type);
        result.put("limit", limit);
        result.put("window", window);
        result.put("count", count);

        return Map.copyOf(result);
    }

    private static String requireText(
            String value,
            String field
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " не должен быть пустым"
            );
        }

        return value.trim();
    }
}
