package ru.safeai.gateway.audit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.entity.AuditOutboxEntity;
import ru.safeai.gateway.audit.repository.AuditOutboxRepository;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

import java.lang.reflect.Array;
import java.time.Clock;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditEventService {

    private static final int MAX_DETAILS_DEPTH = 4;
    private static final int MAX_DETAILS_ENTRIES = 100;
    private static final int MAX_COLLECTION_ITEMS = 100;
    private static final int MAX_STRING_LENGTH = 1_024;

    private static final String REDACTED_VALUE = "[REDACTED]";
    private static final String MAX_DEPTH_VALUE = "[MAX_DEPTH]";
    private static final String UNSUPPORTED_VALUE_PREFIX =
            "[UNSUPPORTED_TYPE:";

    private static final Set<String> SENSITIVE_KEY_PARTS = Set.of(
            "password",
            "token",
            "secret",
            "apikey",
            "authorization",
            "cookie",
            "prompt",
            "response"
    );

    private final AuditOutboxRepository auditOutboxRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    /**
     * Записывает audit outbox в ту же транзакцию, что и business mutation.
     * Ошибка записи не проглатывается: security-sensitive mutation должна
     * быть атомарна с durable audit intent.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordSystem(
            UUID organizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        record(null, organizationId, eventType, details);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(
            UUID userId,
            UUID organizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        requireArguments(organizationId, eventType);

        UserEntity actor = resolveActor(userId);

        AuditOutboxEntity outbox = new AuditOutboxEntity();
        outbox.setActorUserId(userId);

        if (actor != null) {
            outbox.setActorEmail(normalizeEmail(actor.getEmail()));
            outbox.setActorDisplayName(
                    normalizeDisplayName(actor.getFullName())
            );
        }

        outbox.setOrganizationId(organizationId);
        outbox.setEventType(eventType.name());
        outbox.setDetails(sanitizeDetails(details));
        outbox.setCreatedAt(clock.instant());

        auditOutboxRepository.save(outbox);
    }

    private void requireArguments(
            UUID organizationId,
            AuditEventType eventType
    ) {
        if (organizationId == null) {
            throw new IllegalArgumentException(
                    "organizationId не должен быть null для audit event"
            );
        }

        if (eventType == null) {
            throw new IllegalArgumentException(
                    "eventType не должен быть null для audit event"
            );
        }
    }

    private UserEntity resolveActor(UUID userId) {
        if (userId == null) {
            return null;
        }

        return userRepository
                .findByIdWithOrganization(userId)
                .orElse(null);
    }

    private Map<String, Object> sanitizeDetails(
            Map<String, Object> details
    ) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> sanitized = new LinkedHashMap<>();
        int count = 0;

        for (Map.Entry<String, Object> entry : details.entrySet()) {
            if (count >= MAX_DETAILS_ENTRIES) {
                sanitized.put("_truncated", true);
                break;
            }

            String key = sanitizeKey(entry.getKey());

            if (key == null) {
                continue;
            }

            Object value = isSensitiveKey(key)
                    ? REDACTED_VALUE
                    : sanitizeValue(entry.getValue(), 0);

            if (value != null) {
                sanitized.put(key, value);
                count++;
            }
        }

        return Collections.unmodifiableMap(sanitized);
    }

    private String sanitizeKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }

        return truncate(key.trim());
    }

    private String sanitizeMapKey(Object rawKey) {
        if (rawKey == null) {
            return null;
        }

        return switch (rawKey) {
            case String value -> sanitizeKey(value);
            case UUID value -> value.toString();
            case Enum<?> value -> value.name();
            case Number value -> value.toString();
            case Boolean value -> value.toString();
            case Character value -> value.toString();
            default -> null;
        };
    }

    private Object sanitizeValue(Object value, int depth) {
        if (value == null) {
            return null;
        }

        if (depth >= MAX_DETAILS_DEPTH) {
            return MAX_DEPTH_VALUE;
        }

        return switch (value) {
            case String stringValue -> truncate(stringValue);
            case Number numberValue -> numberValue;
            case Boolean booleanValue -> booleanValue;
            case Character characterValue -> characterValue.toString();
            case UUID uuid -> uuid.toString();
            case Enum<?> enumValue -> enumValue.name();
            case TemporalAccessor temporalAccessor ->
                    temporalAccessor.toString();
            case Map<?, ?> mapValue ->
                    sanitizeMapValue(mapValue, depth + 1);
            case Iterable<?> iterableValue ->
                    sanitizeIterableValue(iterableValue, depth + 1);
            default -> sanitizeUnknownValue(value, depth);
        };
    }

    private Object sanitizeUnknownValue(Object value, int depth) {
        if (value.getClass().isArray()) {
            return sanitizeArrayValue(value, depth + 1);
        }

        return UNSUPPORTED_VALUE_PREFIX
                + value.getClass().getName()
                + "]";
    }

    private Map<String, Object> sanitizeMapValue(
            Map<?, ?> mapValue,
            int depth
    ) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        int count = 0;

        for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
            if (count >= MAX_DETAILS_ENTRIES) {
                sanitized.put("_truncated", true);
                break;
            }

            String key = sanitizeMapKey(entry.getKey());

            if (key == null) {
                continue;
            }

            Object value = isSensitiveKey(key)
                    ? REDACTED_VALUE
                    : sanitizeValue(entry.getValue(), depth);

            if (value != null) {
                sanitized.put(key, value);
                count++;
            }
        }

        return Collections.unmodifiableMap(sanitized);
    }

    private List<Object> sanitizeIterableValue(
            Iterable<?> iterableValue,
            int depth
    ) {
        List<Object> sanitized = new ArrayList<>();
        int count = 0;

        for (Object item : iterableValue) {
            if (count >= MAX_COLLECTION_ITEMS) {
                sanitized.add("_truncated");
                break;
            }

            Object value = sanitizeValue(item, depth);

            if (value != null) {
                sanitized.add(value);
                count++;
            }
        }

        return List.copyOf(sanitized);
    }

    private List<Object> sanitizeArrayValue(
            Object arrayValue,
            int depth
    ) {
        List<Object> sanitized = new ArrayList<>();
        int length = Array.getLength(arrayValue);
        int limit = Math.min(length, MAX_COLLECTION_ITEMS);

        for (int index = 0; index < limit; index++) {
            Object value = sanitizeValue(
                    Array.get(arrayValue, index),
                    depth
            );

            if (value != null) {
                sanitized.add(value);
            }
        }

        if (length > MAX_COLLECTION_ITEMS) {
            sanitized.add("_truncated");
        }

        return List.copyOf(sanitized);
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.length() <= MAX_STRING_LENGTH
                ? trimmed
                : trimmed.substring(0, MAX_STRING_LENGTH);
    }

    private boolean isSensitiveKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }

        String normalized = key
                .toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(".", "");

        return SENSITIVE_KEY_PARTS.stream()
                .anyMatch(normalized::contains);
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }

        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }

        return truncate(displayName.trim());
    }
}
