package ru.safeai.gateway.audit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.audit.config.AuditDetailsProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
public class AuditDetailsSanitizer {

    private static final String REDACTED_VALUE =
            "[REDACTED]";
    private static final String MAX_DEPTH_VALUE =
            "[MAX_DEPTH]";
    private static final String BUDGET_EXCEEDED_VALUE =
            "[BUDGET_EXCEEDED]";
    private static final String NON_FINITE_NUMBER_VALUE =
            "[NON_FINITE_NUMBER]";
    private static final String NUMBER_TOO_LARGE_VALUE =
            "[NUMBER_TOO_LARGE]";
    private static final String UNSUPPORTED_VALUE_PREFIX =
            "[UNSUPPORTED_TYPE:";

    /*
     * Exact canonical keys. Substring matching is intentionally forbidden:
     * tokenFamilyId, tokenVersion, inputTokens and responseTimeMs are safe.
     */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password",
            "passwordhash",
            "currentpassword",
            "newpassword",
            "accesstoken",
            "refreshtoken",
            "rawtoken",
            "jwttoken",
            "jwt",
            "bearer",
            "bearertoken",
            "authorization",
            "cookie",
            "setcookie",
            "apikey",
            "clientsecret",
            "credential",
            "credentials",
            "passphrase",
            "privatekey",
            "prompt",
            "promptcontent",
            "rawprompt",
            "response",
            "responsebody",
            "responsecontent",
            "rawresponse",
            "requestbody",
            "rawvalue"
    );

    /*
     * Exact path segments which always denote a secret-bearing subtree.
     * Generic "response" is excluded so response.status remains useful.
     */
    private static final Set<String> SENSITIVE_PATH_SEGMENTS = Set.of(
            "password",
            "passwordhash",
            "currentpassword",
            "newpassword",
            "accesstoken",
            "refreshtoken",
            "rawtoken",
            "jwttoken",
            "jwt",
            "bearer",
            "bearertoken",
            "authorization",
            "cookie",
            "setcookie",
            "apikey",
            "clientsecret",
            "credential",
            "credentials",
            "passphrase",
            "privatekey",
            "prompt",
            "promptcontent",
            "rawprompt",
            "responsebody",
            "responsecontent",
            "rawresponse",
            "requestbody",
            "rawvalue"
    );

    private final JsonMapper jsonMapper;
    private final AuditDetailsProperties properties;

    public Map<String, Object> sanitize(
            Map<String, Object> details
    ) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }

        Budget budget = new Budget(
                properties.maxTotalNodes(),
                properties.maxTotalStringChars(),
                Math.max(
                        1_024,
                        properties.maxJsonBytes() - 4_096
                )
        );

        budget.tryConsumeNode();

        Map<String, Object> sanitized =
                sanitizeMapValue(
                        details,
                        0,
                        budget
                );

        int serializedBytes =
                serializedSize(sanitized);

        if (serializedBytes
                <= properties.maxJsonBytes()) {
            return sanitized;
        }

        return Map.of(
                "_truncated", true,
                "_reason", "MAX_JSON_BYTES",
                "_sanitizedBytes", serializedBytes
        );
    }

    private Object sanitizeValue(
            Object value,
            int depth,
            Budget budget
    ) {
        if (value == null) {
            return null;
        }

        if (!budget.tryConsumeNode()) {
            return BUDGET_EXCEEDED_VALUE;
        }

        if (depth >= properties.maxDepth()) {
            return MAX_DEPTH_VALUE;
        }

        return switch (value) {
            case String stringValue ->
                    sanitizeString(
                            stringValue,
                            budget
                    );

            case Number numberValue ->
                    sanitizeNumber(numberValue);

            case Boolean booleanValue ->
                    booleanValue;

            case Character characterValue ->
                    sanitizeString(
                            characterValue.toString(),
                            budget
                    );

            case UUID uuid ->
                    uuid.toString();

            case Enum<?> enumValue ->
                    enumValue.name();

            case TemporalAccessor temporalAccessor ->
                    temporalAccessor.toString();

            case Map<?, ?> mapValue ->
                    sanitizeMapValue(
                            mapValue,
                            depth + 1,
                            budget
                    );

            case Iterable<?> iterableValue ->
                    sanitizeIterableValue(
                            iterableValue,
                            depth + 1,
                            budget
                    );

            default ->
                    sanitizeUnknownValue(
                            value,
                            depth,
                            budget
                    );
        };
    }

    private Object sanitizeUnknownValue(
            Object value,
            int depth,
            Budget budget
    ) {
        if (value.getClass().isArray()) {
            return sanitizeArrayValue(
                    value,
                    depth + 1,
                    budget
            );
        }

        return UNSUPPORTED_VALUE_PREFIX
                + value.getClass().getName()
                + "]";
    }

    private Map<String, Object> sanitizeMapValue(
            Map<?, ?> source,
            int depth,
            Budget budget
    ) {
        Map<String, Object> sanitized =
                new LinkedHashMap<>();

        int count = 0;

        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (count
                    >= properties.maxContainerItems()) {
                sanitized.put("_truncated", true);
                break;
            }

            String key =
                    sanitizeMapKey(entry.getKey());

            if (key == null) {
                continue;
            }

            if (!budget.tryConsumeText(key)) {
                sanitized.put(
                        "_budgetExceeded",
                        true
                );
                break;
            }

            Object value = isSensitiveKey(key)
                    ? REDACTED_VALUE
                    : sanitizeValue(
                            entry.getValue(),
                            depth,
                            budget
                    );

            if (value != null) {
                sanitized.putIfAbsent(key, value);
                count++;
            }

            if (budget.isExhausted()) {
                sanitized.put(
                        "_budgetExceeded",
                        true
                );
                break;
            }
        }

        return Collections.unmodifiableMap(sanitized);
    }

    private List<Object> sanitizeIterableValue(
            Iterable<?> source,
            int depth,
            Budget budget
    ) {
        List<Object> sanitized =
                new ArrayList<>();

        int count = 0;

        for (Object item : source) {
            if (count
                    >= properties.maxContainerItems()) {
                sanitized.add("_truncated");
                break;
            }

            Object value =
                    sanitizeValue(
                            item,
                            depth,
                            budget
                    );

            if (value != null) {
                sanitized.add(value);
                count++;
            }

            if (budget.isExhausted()) {
                sanitized.add(
                        BUDGET_EXCEEDED_VALUE
                );
                break;
            }
        }

        return List.copyOf(sanitized);
    }

    private List<Object> sanitizeArrayValue(
            Object source,
            int depth,
            Budget budget
    ) {
        List<Object> sanitized =
                new ArrayList<>();

        int length = Array.getLength(source);

        int limit = Math.min(
                length,
                properties.maxContainerItems()
        );

        for (int index = 0;
             index < limit;
             index++) {

            Object value = sanitizeValue(
                    Array.get(source, index),
                    depth,
                    budget
            );

            if (value != null) {
                sanitized.add(value);
            }

            if (budget.isExhausted()) {
                sanitized.add(
                        BUDGET_EXCEEDED_VALUE
                );
                break;
            }
        }

        if (length
                > properties.maxContainerItems()) {
            sanitized.add("_truncated");
        }

        return List.copyOf(sanitized);
    }

    private Object sanitizeNumber(Number value) {
        if (value instanceof Double doubleValue) {
            return Double.isFinite(doubleValue)
                    ? doubleValue
                    : NON_FINITE_NUMBER_VALUE;
        }

        if (value instanceof Float floatValue) {
            return Float.isFinite(floatValue)
                    ? floatValue
                    : NON_FINITE_NUMBER_VALUE;
        }

        if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            return value;
        }

        if (value instanceof AtomicInteger atomicInteger) {
            return atomicInteger.get();
        }

        if (value instanceof AtomicLong atomicLong) {
            return atomicLong.get();
        }

        if (value instanceof BigInteger bigInteger) {
            return bigInteger.toString().length()
                    <= properties.maxNumberTextLength()
                    ? bigInteger
                    : NUMBER_TOO_LARGE_VALUE;
        }

        if (value instanceof BigDecimal bigDecimal) {
            return isSafeDecimal(bigDecimal)
                    ? bigDecimal
                    : NUMBER_TOO_LARGE_VALUE;
        }

        String text = value.toString();

        if (text.length()
                > properties.maxNumberTextLength()) {
            return NUMBER_TOO_LARGE_VALUE;
        }

        try {
            BigDecimal decimal =
                    new BigDecimal(text);

            return isSafeDecimal(decimal)
                    ? decimal
                    : NUMBER_TOO_LARGE_VALUE;
        } catch (NumberFormatException ignored) {
            return UNSUPPORTED_VALUE_PREFIX
                    + value.getClass().getName()
                    + "]";
        }
    }

    private boolean isSafeDecimal(
            BigDecimal value
    ) {
        int limit =
                properties.maxNumberTextLength();

        return value.toString().length() <= limit
                && value.precision() <= limit
                && Math.abs((long) value.scale())
                <= limit;
    }

    private String sanitizeString(
            String value,
            Budget budget
    ) {
        String truncated = truncate(value);

        return truncated == null
                ? null
                : budget.consumeString(truncated);
    }

    private String sanitizeMapKey(Object rawKey) {
        if (rawKey == null) {
            return null;
        }

        return switch (rawKey) {
            case String value ->
                    truncate(value);

            case UUID value ->
                    value.toString();

            case Enum<?> value ->
                    value.name();

            case Number value ->
                    truncate(value.toString());

            case Boolean value ->
                    value.toString();

            case Character value ->
                    value.toString();

            default ->
                    null;
        };
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String trimmed = value.trim();

        return truncateByCodePoints(
                trimmed,
                properties.maxStringLength()
        );
    }

    private String truncateByCodePoints(
            String value,
            int maxUtf16Chars
    ) {
        if (value.length() <= maxUtf16Chars) {
            return value;
        }

        int end = maxUtf16Chars;

        if (end > 0
                && Character.isHighSurrogate(
                        value.charAt(end - 1)
                )) {
            end--;
        }

        return value.substring(0, end);
    }

    private boolean isSensitiveKey(String key) {
        String lower =
                key.toLowerCase(Locale.ROOT);

        String canonicalWhole =
                canonicalize(lower);

        String[] pathSegments =
                lower.split("[./:]");

        String leaf = pathSegments.length == 0
                ? lower
                : pathSegments[
                        pathSegments.length - 1
                ];

        if (SENSITIVE_KEYS.contains(canonicalWhole)
                || SENSITIVE_KEYS.contains(
                        canonicalize(leaf)
                )) {
            return true;
        }

        for (String segment : pathSegments) {
            if (SENSITIVE_PATH_SEGMENTS.contains(
                    canonicalize(segment)
            )) {
                return true;
            }
        }

        return false;
    }

    private String canonicalize(String value) {
        return value
                .replace("-", "")
                .replace("_", "")
                .replace(".", "")
                .replace("/", "")
                .replace(":", "");
    }

    private int serializedSize(
            Map<String, Object> details
    ) {
        try {
            return jsonMapper
                    .writeValueAsBytes(details)
                    .length;
        } catch (JacksonException exception) {
            throw new IllegalArgumentException(
                    "Sanitized audit details "
                            + "не сериализуется в JSON",
                    exception
            );
        }
    }

    private static final class Budget {

        private int remainingNodes;
        private int remainingStringChars;
        private int remainingEstimatedBytes;

        private Budget(
                int remainingNodes,
                int remainingStringChars,
                int remainingEstimatedBytes
        ) {
            this.remainingNodes =
                    remainingNodes;
            this.remainingStringChars =
                    remainingStringChars;
            this.remainingEstimatedBytes =
                    remainingEstimatedBytes;
        }

        private boolean tryConsumeNode() {
            if (remainingNodes <= 0
                    || remainingEstimatedBytes < 16) {
                return false;
            }

            remainingNodes--;
            remainingEstimatedBytes -= 16;

            return true;
        }

        private boolean tryConsumeText(
                String value
        ) {
            int bytes = utf8Length(value);

            if (remainingStringChars
                    < value.length()
                    || remainingEstimatedBytes
                    < bytes) {
                return false;
            }

            remainingStringChars -=
                    value.length();

            remainingEstimatedBytes -= bytes;

            return true;
        }

        private String consumeString(
                String value
        ) {
            if (remainingStringChars <= 0
                    || remainingEstimatedBytes <= 0) {
                return BUDGET_EXCEEDED_VALUE;
            }

            StringBuilder result =
                    new StringBuilder();

            int usedChars = 0;
            int usedBytes = 0;

            for (int offset = 0;
                 offset < value.length();) {

                int codePoint =
                        value.codePointAt(offset);

                String part =
                        new String(
                                Character.toChars(
                                        codePoint
                                )
                        );

                int chars = part.length();
                int bytes = utf8Length(part);

                if (usedChars + chars
                        > remainingStringChars
                        || usedBytes + bytes
                        > remainingEstimatedBytes) {
                    break;
                }

                result.append(part);
                usedChars += chars;
                usedBytes += bytes;
                offset += chars;
            }

            if (result.isEmpty()) {
                return BUDGET_EXCEEDED_VALUE;
            }

            remainingStringChars -= usedChars;
            remainingEstimatedBytes -= usedBytes;

            return result.toString();
        }

        private boolean isExhausted() {
            return remainingNodes <= 0
                    || remainingStringChars <= 0
                    || remainingEstimatedBytes <= 0;
        }

        private static int utf8Length(
                String value
        ) {
            return value
                    .getBytes(StandardCharsets.UTF_8)
                    .length;
        }
    }
}
