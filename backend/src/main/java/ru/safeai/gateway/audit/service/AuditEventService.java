package ru.safeai.gateway.audit.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.entity.AuditEventEntity;
import ru.safeai.gateway.audit.repository.AuditEventRepository;
import ru.safeai.gateway.user.entity.UserEntity;

import java.lang.reflect.Array;
import java.time.temporal.TemporalAccessor;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditEventService {

    private static final int MAX_DETAILS_DEPTH = 4;
    private static final int MAX_DETAILS_ENTRIES = 100;
    private static final int MAX_COLLECTION_ITEMS = 100;
    private static final int MAX_STRING_LENGTH = 2_000;

    private static final String REDACTED_VALUE = "[REDACTED]";

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

    private final AuditEventRepository auditEventRepository;
    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSystem(
            UUID organizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        record(null, organizationId, eventType, details);
    }

    /**
     * SECURITY NOTICE

     * Audit details must NEVER contain:

     * - passwords
     * - refresh tokens
     * - access tokens
     * - API keys
     * - Authorization headers
     * - cookies
     * - AI prompts
     * - AI responses

     * Audit is intended only for security metadata.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            UUID userId,
            UUID organizationId,
            AuditEventType eventType,
            Map<String, Object> details
    ) {
        try {
            if (organizationId == null) {
                throw new IllegalArgumentException("organizationId не должен быть null для audit event");
            }

            if (eventType == null) {
                throw new IllegalArgumentException("eventType не должен быть null для audit event");
            }

            UserEntity user = findUserReferenceIfExists(
                    userId,
                    organizationId,
                    eventType
            );

            AuditEventEntity event = new AuditEventEntity();
            event.setUser(user);
            event.setOrganizationId(organizationId);
            event.setEventType(eventType.name());
            event.setDetails(sanitizeDetails(details));

            auditEventRepository.save(event);
        } catch (Exception exception) {
            log.error(
                    "Не удалось записать событие аудита: userId={}, organizationId={}, eventType={}",
                    userId,
                    organizationId,
                    eventType,
                    exception
            );
        }
    }

    private UserEntity findUserReferenceIfExists(
            UUID userId,
            UUID organizationId,
            AuditEventType eventType
    ) {
        if (userId == null) {
            return null;
        }

        UserEntity user = entityManager.find(UserEntity.class, userId);

        if (user == null) {
            log.warn(
                    "Audit user reference not found, writing audit event without user FK: userId={}, organizationId={}, eventType={}",
                    userId,
                    organizationId,
                    eventType
            );
        }

        return user;
    }

    private Map<String, Object> sanitizeDetails(Map<String, Object> details) {
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

    private Object sanitizeValue(Object value, int depth) {
        if (value == null) {
            return null;
        }

        if (depth >= MAX_DETAILS_DEPTH) {
            return truncate(String.valueOf(value));
        }

        return switch (value) {
            case String stringValue -> truncate(stringValue);
            case Number numberValue -> numberValue;
            case Boolean booleanValue -> booleanValue;
            case Character characterValue -> characterValue.toString();
            case UUID uuid -> uuid.toString();
            case Enum<?> enumValue -> enumValue.name();
            case TemporalAccessor temporalAccessor -> temporalAccessor.toString();
            case Map<?, ?> mapValue -> sanitizeMapValue(mapValue, depth + 1);
            case Iterable<?> iterableValue -> sanitizeIterableValue(iterableValue, depth + 1);
            default -> {
                if (value.getClass().isArray()) {
                    yield sanitizeArrayValue(value, depth + 1);
                }

                yield truncate(String.valueOf(value));
            }
        };
    }

    private Map<String, Object> sanitizeMapValue(Map<?, ?> mapValue, int depth) {
        Map<String, Object> sanitized = new LinkedHashMap<>();

        int count = 0;

        for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
            if (count >= MAX_DETAILS_ENTRIES) {
                sanitized.put("_truncated", true);
                break;
            }

            Object rawKey = entry.getKey();

            if (rawKey == null) {
                continue;
            }

            String key = sanitizeKey(String.valueOf(rawKey));

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

        return sanitized;
    }

    private List<Object> sanitizeIterableValue(Iterable<?> iterableValue, int depth) {
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

        return sanitized;
    }

    private List<Object> sanitizeArrayValue(Object arrayValue, int depth) {
        List<Object> sanitized = new ArrayList<>();

        int length = Array.getLength(arrayValue);
        int limit = Math.min(length, MAX_COLLECTION_ITEMS);

        for (int index = 0; index < limit; index++) {
            Object value = sanitizeValue(Array.get(arrayValue, index), depth);

            if (value != null) {
                sanitized.add(value);
            }
        }

        if (length > MAX_COLLECTION_ITEMS) {
            sanitized.add("_truncated");
        }

        return sanitized;
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        if (trimmed.length() <= MAX_STRING_LENGTH) {
            return trimmed;
        }

        return trimmed.substring(0, MAX_STRING_LENGTH);
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
}